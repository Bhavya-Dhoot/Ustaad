package com.swarmos.app.choir

import com.swarmos.app.AppGraph
import com.swarmos.app.BuildConfig
import com.swarmos.app.core.AuditScope
import com.swarmos.app.core.Ports
import com.swarmos.app.core.SwarmPrefs
import com.swarmos.app.link.ConnectResult
import com.swarmos.app.link.NodeClient
import com.swarmos.app.link.PeerLinks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.UUID

/** A peer reached over signed HTTP (`host` is "ip" or "ip:port"). */
class RemoteParticipant(
    override val nodeId: String,
    override val name: String,
    private val host: String,
    private val client: NodeClient,
    override val away: Boolean = false,
    override val noModel: Boolean = false,
    /** The live answer to "is this phone away now", read again while a round runs. */
    private val awayNow: () -> Boolean = { away },
) : ChoirParticipant {
    override fun isAway(): Boolean = awayNow()
    override suspend fun solve(req: SolveRequest): NodeAnswer = client.post<NodeAnswer, SolveRequest>(host, "/choir/solve", req)
    override suspend fun revise(req: ReviseRequest): NodeAnswer = client.post<NodeAnswer, ReviseRequest>(host, "/choir/revise", req)
    override suspend fun judge(req: JudgeRequest): JudgeAnswer = client.post<JudgeAnswer, JudgeRequest>(host, "/choir/judge", req)
    suspend fun setScript(script: FakeScript) { client.post<JsonObject, FakeScript>(host, "/choir/debug/script", script) }
}

/** A run or eval was refused before it started (no real chat model in a demo build, a phone that is too hot). */
open class RunRefused(message: String) : IllegalStateException(message)

class EvalRefused(message: String) : RunRefused(message)

@Serializable data class DebugPeers(val ips: List<String>)
@Serializable data class PeerAdded(val nodeId: String, val name: String, val ip: String)
@Serializable data class PeerFailed(val ip: String, val error: String)
@Serializable data class DebugPeersResult(val added: List<PeerAdded>, val failed: List<PeerFailed>)

/**
 * App-side glue: one run (or eval) at a time, the observable state the screen shows, peers from the registry.
 * Everything below the coordinator is plain Kotlin and covered by JVM tests.
 */
object ChoirController {
    // an exception nobody caught is one audit entry, not the end of the process
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + AuditScope.handler("choir") { AppGraph.audit })
    private val gate = Mutex()

    /** The built-in problem set (assets/choir/problems.json). */
    val problems: List<Problem> by lazy {
        runCatching {
            AppGraph.appContext.assets.open("choir/problems.json").bufferedReader().use { Problem.parseList(it.readText()) }
        }.getOrDefault(emptyList())
    }

    val node: ChoirNode by lazy {
        ChoirNode(
            llm = { AppGraph.gatedLlm },
            identity = { AppGraph.registry.self.value.let { NodeIdentity(it.nodeId, it.name) } },
            problems = { problems },
        )
    }

    private val coordinator by lazy { ChoirCoordinator(audit = { kind, payload -> AppGraph.audit.append(kind, payload) }) }

    private val _run = MutableStateFlow<RunTrace?>(null)
    val run: StateFlow<RunTrace?> = _run.asStateFlow()

    private val evalSession by lazy { EvalSession(scope) }
    val eval: StateFlow<EvalProgress> get() = evalSession.progress

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private fun selfParticipant(): ChoirParticipant {
        val me = AppGraph.registry.self.value
        return LocalParticipant(node, me.nodeId, me.name)
    }

    private fun remotePeers(): List<RemoteParticipant> =
        // phones whose link heartbeat says away stay in the list so the run shows them as away lanes; they are never asked
        AppGraph.registry.peers.value.map {
            val id = it.info.nodeId
            RemoteParticipant(
                id, it.info.name, it.info.ip, AppGraph.nodeClient, away = it.away,
                noModel = !BuildConfig.DEBUG && !ChoirReadiness.peerHasRealModel(it.info.chatModel),
                // read again during a round: a phone that drops out of the registry or is flagged away leaves the round at once
                awayNow = { AppGraph.registry.peers.value.firstOrNull { p -> p.info.nodeId == id }?.away ?: true },
            )
        }

    fun newTaskId(): String = "t-" + UUID.randomUUID().toString().take(8)

    /** One phone, three samples for the eval harness: the coordinator alone, sequentially. */
    private suspend fun samplesFor(p: Problem): SamplesResult = ChoirSamples.run(p, newTaskId(), selfParticipant())

    /** Free text problem (also used by the screen). For MCQ put the lettered options into the text. */
    fun customProblem(text: String, kind: Kind, check: String? = null, answer: String = ""): Problem =
        Problem(id = "custom", kind = kind, text = text.trim(), check = check?.takeIf { it.isNotBlank() }, answer = answer)

    suspend fun runProblem(
        problem: Problem,
        options: RunOptions = RunOptions(),
        scripts: Map<String, FakeScript> = emptyMap(),
        keepScripts: Boolean = false,
    ): RunTrace = gate.withLock {
        refusal()?.let { throw RunRefused(it) }
        _busy.value = true
        val opts = options.copy(majoritySkipsRound2 = majoritySkipsRound2())
        val peers = remotePeers()
        // fake per-node answers exist in debug builds only
        val scripts = if (BuildConfig.DEBUG) scripts else emptyMap()
        try {
            if (scripts.isNotEmpty()) applyScripts(scripts, peers)
            ChoirActivity.during(ChoirActivity.asking(1 + peers.count { !it.away && !it.noModel })) {
                coordinator.run(problem, newTaskId(), selfParticipant(), peers, opts) { _run.value = it }.also { _run.value = it }
            }
        } catch (t: Throwable) {
            // a run that ends in an exception must not leave the screen showing a snapshot that looks as if it were still solving
            if (t !is RunRefused) failRun(t)
            throw t
        } finally {
            if (scripts.isNotEmpty() && !keepScripts) clearScripts(peers)
            _busy.value = false
        }
    }

    /** Closes the run on the screen: DONE, with the reason, keeping every lane and vote it had. */
    private fun failRun(t: Throwable) {
        val why = if (t is CancellationException) "run stopped" else "run failed: ${t.javaClass.simpleName}: ${t.message}".take(200)
        _run.update { if (it != null && it.phase != "DONE") it.copy(phase = "DONE", error = it.error ?: why) else it }
    }

    /** Whether the majority policy for round 2 is on (pref `choir_majority_skips_round2`, off by default). */
    private suspend fun majoritySkipsRound2(): Boolean = runCatching { SwarmPrefs(AppGraph.appContext).choirMajoritySkipsRound2() }.getOrDefault(false)

    /** Debug builds set it with the intent extra `choirMajority`. */
    fun setMajoritySkipsRound2(on: Boolean) {
        scope.launch { SwarmPrefs(AppGraph.appContext).setChoirMajoritySkipsRound2(on) }
    }

    fun startRun(problem: Problem, options: RunOptions = RunOptions()) {
        scope.launch {
            try {
                runProblem(problem, options)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RunRefused) {
                // shown by the screen from refusal()
            } catch (t: Throwable) {
                AppGraph.audit.append("choir.run_error", JsonObject(mapOf("error" to kotlinx.serialization.json.JsonPrimitive("${t.javaClass.simpleName}: ${t.message}".take(200)))))
            }
        }
    }

    /** Runs the built-in set (or the given ids) one after another; stores filesDir/choir-eval-<ts>.json. Stop (see [cancelEval]) keeps the finished rows. */
    suspend fun runEval(ids: List<String>?, options: RunOptions = RunOptions(), withSamples: Boolean = true, withPeers: Boolean = true): EvalReport = gate.withLock {
        refusal()?.let { reason ->
            evalSession.refuse(reason)
            throw RunRefused(reason)
        }
        ChoirEval.refuseWhenHot(AppGraph.health.state.value.thermalStatus)?.let { reason ->
            evalSession.refuse(reason)
            throw EvalRefused(reason)
        }
        val list = if (ids == null) problems else ids.mapNotNull { id -> problems.firstOrNull { it.id == id } }
        val opts = options.copy(majoritySkipsRound2 = majoritySkipsRound2())
        _busy.value = true
        var fake = false
        val nodes = mutableSetOf<String>()
        try {
            evalSession.run(
                list,
                runOne = { p ->
                    coordinator.run(p, newTaskId(), selfParticipant(), if (withPeers) remotePeers() else emptyList(), opts) { _run.value = it }.also {
                        _run.value = it
                        fake = fake || it.fake
                        it.lanes.forEach { l -> nodes.add(l.name) }
                    }
                },
                runSamples = if (withSamples) ::samplesFor else null,
                useProblemCheck = options.useProblemCheck,
                newReport = { rows ->
                    EvalReport(
                        ts = System.currentTimeMillis(),
                        model = AppGraph.llm.modelName,
                        fake = fake || AppGraph.llm.modelName == "FAKE",
                        nodes = nodes.toList().sorted(),
                        useProblemCheck = options.useProblemCheck,
                        deadlineMs = ChoirConfig().deadlineMs,
                        summary = ChoirEval.summarize(rows),
                        rows = rows,
                        majoritySkipsRound2 = opts.majoritySkipsRound2,
                    )
                },
                dir = AppGraph.appContext.filesDir,
                fakeSeen = { fake },
            )
        } finally {
            _busy.value = false
        }
    }

    /** Stops the running evaluation and keeps the problems it has finished. The Stop on the phone's live-state line calls this. */
    fun cancelEval() = evalSession.cancel()

    /** Why a run must not start on this phone, or null: demo builds need a real chat model on the coordinator. */
    fun refusal(): String? = ChoirReadiness.refusal(BuildConfig.DEBUG, ChoirReadiness.realChatReady(AppGraph.llm))

    fun startEval(options: RunOptions = RunOptions(), withSamples: Boolean = true) {
        scope.launch { runCatching { runEval(null, options, withSamples) } }
    }

    /**
     * Debug builds only: connect to peers by IP ("ip" or "ip:port") through the link module, so each one gets the
     * trust handshake and a heartbeat like any discovered peer (and shows up as away when the heartbeat stops).
     */
    suspend fun registerPeers(ips: List<String>): DebugPeersResult {
        val added = mutableListOf<PeerAdded>()
        val failed = mutableListOf<PeerFailed>()
        for (raw in ips.map { it.trim() }.filter { it.isNotEmpty() }) {
            val host = raw.substringBefore(':')
            val port = raw.substringAfter(':', "").toIntOrNull() ?: Ports.NODE
            try {
                when (val r = PeerLinks.connect(host, port)) {
                    is ConnectResult.Ok -> added.add(PeerAdded(r.status.nodeId, r.status.name, raw))
                    is ConnectResult.Untrusted -> failed.add(PeerFailed(raw, "different pairing secret"))
                    is ConnectResult.Failed -> failed.add(PeerFailed(raw, r.reason))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                failed.add(PeerFailed(raw, t.message ?: t::class.simpleName ?: "failed"))
            }
        }
        return DebugPeersResult(added, failed)
    }

    private suspend fun applyScripts(scripts: Map<String, FakeScript>, peers: List<RemoteParticipant>) {
        val me = AppGraph.registry.self.value
        for ((key, sc) in scripts) {
            if (key.equals("self", true) || key == me.nodeId || key.equals(me.name, true)) {
                node.script = sc.takeUnless { it.isEmpty() }
            } else {
                // an away phone is not asked, so it gets no script either; a peer that cannot be reached is simply skipped
                peers.firstOrNull { (it.nodeId == key || it.name.equals(key, true)) && !it.away && !it.noModel }?.let { p ->
                    try {
                        p.setScript(sc)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    private suspend fun clearScripts(peers: List<RemoteParticipant>) {
        node.script = null
        for (p in peers) runCatching { p.setScript(FakeScript()) }
    }

    /** Path of the newest stored eval report in filesDir, if any. */
    fun lastEvalFile(): File? =
        AppGraph.appContext.filesDir.listFiles { f -> f.name.startsWith("choir-eval-") }?.maxByOrNull { it.name }
}
