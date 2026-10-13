package com.swarmos.app.hands.android

import android.content.Context
import android.util.Log
import com.swarmos.app.AppGraph
import com.swarmos.app.assist.HumanGateUi
import com.swarmos.app.core.SwarmService
import com.swarmos.app.hands.Guard
import com.swarmos.app.hands.Hands
import com.swarmos.app.hands.HandsEngine
import com.swarmos.app.hands.HandsHub
import com.swarmos.app.hands.HandsStatus
import com.swarmos.app.hands.MapSkillBook
import com.swarmos.app.hands.Origin
import com.swarmos.app.hands.Tier
import com.swarmos.app.llm.ChatRequest
import com.swarmos.app.llm.LlamaRuntime
import com.swarmos.app.llm.LlamaServerClient
import com.swarmos.app.llm.LlmClient
import com.swarmos.app.llm.ModelCatalog
import com.swarmos.app.llm.Role
import com.swarmos.app.pdu.PduEngine
import com.swarmos.app.sched.Sched
import com.swarmos.app.sched.Scheduler
import com.swarmos.app.sched.newJobId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Builds the Hands engine once per app process and puts it in [HandsHub]. Called from [AppGraph.init], which runs whenever the
 * process starts: the task sheet can be opened by another app while the foreground service is not running, and nothing else
 * would set the hub then. Calling it again does nothing.
 *
 * Model calls go through [HandsBrain] (this phone's gated model, the swarm, the debug-only test brain), knowledge experts and
 * the Data Union through [HandsDesk], the person's answers through [HumanGateUi]. A run shows a Stop chip and a notification
 * while it acts and writes one `hands.run` audit record at its end ([RunTracker]).
 */
object HandsRuntime {
    private const val TAG = "HandsRuntime"
    private const val SKILLS_DIR = "hands/skills"
    private const val EXPERTS_DIR = "hands/experts"
    private const val DATA_TIMEOUT_MS = 90_000L
    private const val LOCAL_SEARCH_MS = 5_000L
    private val lock = Any()

    @Volatile
    private var engine: HandsEngine? = null

    @Volatile
    private var testClient: Pair<Int, LlmClient>? = null

    fun start(ctx: Context) {
        synchronized(lock) {
            if (engine != null) return
            val app = ctx.applicationContext
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

            val skills = MapSkillBook(readCards(app, SKILLS_DIR))
            val experts = ExpertCards.parse(readCards(app, EXPERTS_DIR))

            val brain = HandsBrain(
                local = { AppGraph.gatedLlm },
                localReady = { LlamaRuntime.realChatReady.value && ModelCatalog.allowsSchema(AppGraph.llm.modelName) },
                localModel = { AppGraph.llm.modelName },
                swarm = SchedSwarmSide,
                shareScreens = { HandsSettings.shareScreens.value },
                testClient = ::testBrainClient,
                modelBytes = ::modelBytes,
                scope = scope,
            )
            val data = DataLookup(
                shareScreens = { HandsSettings.shareScreens.value },
                askInProgress = { PduEngine.askRunning },
                federated = ::askDataUnion,
                local = ::searchOwnIndex,
            )
            val desk = HandsDesk(experts, brain, data::answer)
            val tracker = RunTracker()
            val guard = Guard(Guard.OWN_PACKAGES + app.packageName)

            val built = HandsEngine(
                drivers = { HandsDrivers.list(app) },
                brain = brain,
                desk = desk,
                skills = skills,
                gate = HumanGateUi,
                guard = guard,
                launcherPackages = { AppCatalog.launcherPackages(app) },
                scope = scope,
                clock = System::currentTimeMillis,
                delayMs = { delay(it) },
                statusOf = {
                    HandsStatus(
                        driver = HandsDrivers.list(app).firstOrNull { it.ready() }?.name.orEmpty(),
                        accessibilityOn = HandsDrivers.accessibilityOn(),
                        rootAvailable = false,
                        fast = brain.has(Tier.FAST),
                        strong = brain.has(Tier.STRONG),
                    )
                },
                onChange = { run -> tracker.onChange(run).forEach { perform(app, it) } },
            )
            engine = built
            // the notification's Stop stops whatever run is acting
            HandsNotice.onStop = {
                built.runs.value.firstOrNull { RunTracker.acting(it.phase) }?.let { built.stop(it.id) }
            }
            HandsHub.hands = built
        }
    }

    private fun perform(app: Context, effect: RunEffect) {
        runCatching {
            when (effect) {
                is RunEffect.ShowStop -> {
                    val id = effect.runId
                    StopOverlay.show { engine?.stop(id) }
                }
                is RunEffect.Notice -> HandsNotice.show(app, effect.line)
                RunEffect.HideStop -> StopOverlay.hide()
                RunEffect.ClearNotice -> HandsNotice.clear(app)
                is RunEffect.Audit -> AppGraph.audit.append(RunAudit.KIND, effect.counts.toJson())
            }
        }.onFailure { Log.w(TAG, "run effect failed: ${it.javaClass.simpleName}") }
    }

    /** The debug-only stand-in model: a llama-server on 127.0.0.1:<port>, reached from a PC over `adb reverse`. Null in any other build or with the port at 0. */
    private fun testBrainClient(): LlmClient? {
        val port = HandsSettings.testBrainPort.value
        if (port == 0) return null
        testClient?.let { if (it.first == port) return it.second }
        return LlamaServerClient(Role.CHAT, "hands-test-brain", port).also { testClient = port to it }
    }

    /** Size of a model file by name from the pinned catalog; 0 when the model is not in it. */
    private fun modelBytes(name: String): Long {
        val file = if (name.endsWith(".gguf")) name else "$name.gguf"
        return ModelCatalog.byFile(file)?.bytes ?: 0L
    }

    /**
     * The federated Data Union ask: sends [query] to the other paired devices. Reached only through [DataLookup], that is only with
     * the sharing switch on. Null when the Data Union is not up yet, gave no text, or took too long.
     */
    private suspend fun askDataUnion(query: String): String? {
        if (!AppGraph.ready) return null
        val q = withTimeoutOrNull(DATA_TIMEOUT_MS) { PduEngine.ask(query) } ?: return null
        return HandsDesk.dataText(q.phase, q.savedAnswer?.entry?.card?.answer, q.answer)
    }

    /** This phone's own index only, nothing sent anywhere: the top passages with their titles as text. Keywords alone if the embedder is slow. */
    private suspend fun searchOwnIndex(query: String): String? {
        if (!AppGraph.ready) return null
        val found = withTimeoutOrNull(LOCAL_SEARCH_MS) { PduEngine.searchLocal(query) } ?: PduEngine.searchLocalKeywords(query)
        return DataPassages.text(found.candidates.map { DataPassages.Passage(it.title, it.text, it.score) })
    }

    /**
     * Starts a run from Ustaad's own UI with the foreground service up first. Only the task sheet and the Hands screen call it: they
     * are in front when the person taps, so starting the service is allowed; nothing here starts it from the background.
     */
    fun startFromUi(ctx: Context, hands: Hands, task: String, origin: Origin): String = launcher(ctx).start(hands, task, origin)

    /** The person's tap on Run for a task from another app, from Ustaad's own UI, with the foreground service up first. */
    fun goFromUi(ctx: Context, hands: Hands, runId: String) = launcher(ctx).go(hands, runId)

    private fun launcher(ctx: Context): RunLauncher {
        val app = ctx.applicationContext
        return RunLauncher({ SwarmService.running.value }, { SwarmService.start(app) })
    }

    private fun readCards(app: Context, dir: String): Map<String, String> {
        val names = runCatching { app.assets.list(dir) }.getOrNull().orEmpty().filter { it.endsWith(".md") }
        val out = LinkedHashMap<String, String>()
        for (n in names) {
            runCatching { app.assets.open("$dir/$n").bufferedReader().use { it.readText() } }
                .onSuccess { out[n.removeSuffix(".md")] = it }
                .onFailure { Log.w(TAG, "card $dir/$n not read: ${it.javaClass.simpleName}") }
        }
        return out
    }

    /** The swarm as [HandsBrain] sees it: the scheduler's last reports and one pinned chat job. */
    private object SchedSwarmSide : SwarmSide {
        override fun peerModels(): List<String> = Sched.swarm.phoneViews().mapNotNull { v ->
            val caps = v.caps
            // a phone counts when it is up, takes chat jobs (capacity above 0), runs a real model and one that answers JSON-schema requests
            if (v.isLocal || !v.up || caps == null || !Scheduler.eligible(caps, null) || caps.modelName == "FAKE" || !ModelCatalog.allowsSchema(caps.modelName)) null
            else caps.modelName
        }

        override suspend fun refresh() = Sched.swarm.refreshCaps()

        override suspend fun chat(req: ChatRequest, pinModel: String?): String = Sched.swarm.chatJob(newJobId(), req, pinModel).text
    }
}
