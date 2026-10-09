package com.swarmos.app.choir

import com.swarmos.app.AppGraph
import com.swarmos.app.core.NodeInfo
import com.swarmos.app.link.NodeClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
) : ChoirParticipant {
    override suspend fun solve(req: SolveRequest): NodeAnswer = client.post<NodeAnswer, SolveRequest>(host, "/choir/solve", req)
    override suspend fun revise(req: ReviseRequest): NodeAnswer = client.post<NodeAnswer, ReviseRequest>(host, "/choir/revise", req)
    override suspend fun judge(req: JudgeRequest): JudgeAnswer = client.post<JudgeAnswer, JudgeRequest>(host, "/choir/judge", req)
    suspend fun setScript(script: FakeScript) { client.post<JsonObject, FakeScript>(host, "/choir/debug/script", script) }
}

@Serializable data class DebugPeers(val ips: List<String>)
@Serializable data class PeerAdded(val nodeId: String, val name: String, val ip: String)
@Serializable data class PeerFailed(val ip: String, val error: String)
@Serializable data class DebugPeersResult(val added: List<PeerAdded>, val failed: List<PeerFailed>)

/**
 * App-side glue: one run (or eval) at a time, the observable state the screen shows, peers from the registry.
 * Everything below the coordinator is plain Kotlin and covered by JVM tests.
 */
object ChoirController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = Mutex()

    /** The built-in problem set (assets/choir/problems.json). */
    val problems: List<Problem> by lazy {
        runCatching {
            AppGraph.appContext.assets.open("choir/problems.json").bufferedReader().use { Problem.parseList(it.readText()) }
        }.getOrDefault(emptyList())
    }

    val node: ChoirNode by lazy {
        ChoirNode(
            llm = { AppGraph.llm },
            identity = { AppGraph.registry.self.value.let { NodeIdentity(it.nodeId, it.name) } },
            problems = { problems },
        )
    }

    private val coordinator by lazy { ChoirCoordinator(audit = { kind, payload -> AppGraph.audit.append(kind, payload) }) }

    private val _run = MutableStateFlow<RunTrace?>(null)
    val run: StateFlow<RunTrace?> = _run.asStateFlow()

    private val _eval = MutableStateFlow(EvalProgress())
    val eval: StateFlow<EvalProgress> = _eval.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private fun selfParticipant(): ChoirParticipant {
        val me = AppGraph.registry.self.value
        return LocalParticipant(node, me.nodeId, me.name)
    }

    private fun remotePeers(): List<RemoteParticipant> =
        AppGraph.registry.peers.value.filter { !it.away }.map {
            RemoteParticipant(it.info.nodeId, it.info.name, it.info.ip, AppGraph.nodeClient)
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
        _busy.value = true
        val peers = remotePeers()
        try {
            if (scripts.isNotEmpty()) applyScripts(scripts, peers)
            coordinator.run(problem, newTaskId(), selfParticipant(), peers, options) { _run.value = it }.also { _run.value = it }
        } finally {
            if (scripts.isNotEmpty() && !keepScripts) clearScripts(peers)
            _busy.value = false
        }
    }

    fun startRun(problem: Problem, options: RunOptions = RunOptions()) {
        scope.launch { runCatching { runProblem(problem, options) } }
    }

    /** Runs the built-in set (or the given ids) one after another; stores filesDir/choir-eval-<ts>.json. */
    suspend fun runEval(ids: List<String>?, options: RunOptions = RunOptions(), withSamples: Boolean = true): EvalReport = gate.withLock {
        val list = if (ids == null) problems else ids.mapNotNull { id -> problems.firstOrNull { it.id == id } }
        _busy.value = true
        var fake = false
        val nodes = mutableSetOf<String>()
        try {
            _eval.value = EvalProgress(running = true, total = list.size)
            val rows = ChoirEval.run(
                list,
                runOne = { p ->
                    val peers = remotePeers()
                    coordinator.run(p, newTaskId(), selfParticipant(), peers, options) { _run.value = it }.also {
                        _run.value = it
                        fake = fake || it.fake
                        it.lanes.forEach { l -> nodes.add(l.name) }
                    }
                },
                runSamples = if (withSamples) ::samplesFor else null,
                onProgress = { done, cur ->
                    _eval.value = EvalProgress(
                        running = cur != null, done = done.size, total = list.size, current = cur?.id,
                        rows = done, summary = ChoirEval.summarize(done), fake = fake,
                    )
                },
            )
            val report = ChoirEval.store(
                AppGraph.appContext.filesDir,
                EvalReport(
                    ts = System.currentTimeMillis(),
                    model = AppGraph.llm.modelName,
                    fake = fake || AppGraph.llm.modelName == "FAKE",
                    nodes = nodes.toList().sorted(),
                    useProblemCheck = options.useProblemCheck,
                    deadlineMs = ChoirConfig().deadlineMs,
                    summary = ChoirEval.summarize(rows),
                    rows = rows,
                ),
            )
            _eval.value = EvalProgress(
                running = false, done = rows.size, total = list.size, lastFile = report.file,
                summary = report.summary, rows = rows, fake = report.fake,
            )
            report
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            _eval.value = _eval.value.copy(running = false, error = t.message ?: t::class.simpleName)
            throw t
        } finally {
            _busy.value = false
        }
    }

    fun startEval(options: RunOptions = RunOptions(), withSamples: Boolean = true) {
        scope.launch { runCatching { runEval(null, options, withSamples) } }
    }

    /** Debug: register peers by IP ("ip" or "ip:port"): GET /v1/hello on each, add it to the registry. */
    suspend fun registerPeers(ips: List<String>): DebugPeersResult {
        val added = mutableListOf<PeerAdded>()
        val failed = mutableListOf<PeerFailed>()
        for (raw in ips.map { it.trim() }.filter { it.isNotEmpty() }) {
            try {
                val info = AppGraph.nodeClient.get<NodeInfo>(raw, "/hello")
                AppGraph.registry.add(info.copy(ip = raw))
                added.add(PeerAdded(info.nodeId, info.name, raw))
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
                peers.firstOrNull { it.nodeId == key || it.name.equals(key, true) }?.setScript(sc)
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
