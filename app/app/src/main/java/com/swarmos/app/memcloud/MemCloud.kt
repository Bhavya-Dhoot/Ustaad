package com.swarmos.app.memcloud

import android.content.Context
import com.swarmos.app.AppGraph
import com.swarmos.app.core.PhoneActivity
import com.swarmos.app.core.Ports
import com.swarmos.app.core.SwarmJson
import com.swarmos.app.link.PeerLinks
import com.swarmos.app.llm.ChatMessage
import com.swarmos.app.llm.ChatRequest
import com.swarmos.app.llm.LlamaRuntime
import com.swarmos.app.llm.ModelStore
import com.swarmos.app.llm.ModelsModule
import com.swarmos.app.llm.ModelsStateDto
import com.swarmos.app.llm.Role
import com.swarmos.app.llm.ServerState
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class RestoreItem(val role: String, val file: String, val mb: Long? = null)

/**
 * MemoryCloud coordinator: this phone drives one model whose layers live on several phones.
 *
 * Flow: PLANNING (read the GGUF header, size the split) -> STARTING_PEERS (each peer stops its own chat and embedding
 * servers through /v1/models/stop and starts rpc-server through /v1/memcloud/rpc/start; this phone stops its own too)
 * -> LOADING (llama-server with --rpc starts; progress is the bytes each peer's rpc-server has received) -> READY.
 * While LOADING or READY a 1 s watch compares the peers' heartbeats and the server process: a lost phone gives
 * DEGRADED naming it, the stream stops and the server is killed, and [replan] loads again on the phones that remain.
 * Everything is observable through [state]; the routes in [MemCloudModule] expose the same calls.
 */
object MemCloud {
    const val SPLIT_CTX = 4096
    private const val WATCH_MS = 1_000L
    private const val RPC_CALL_MS = 2_500L
    private const val LOAD_TIMEOUT_MS = 40L * 60_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(MemCloudState())
    val state: StateFlow<MemCloudState> = _state.asStateFlow()

    private val lock = Any()
    private var opJob: Job? = null
    private var watchJob: Job? = null
    private var genJob: Job? = null
    private var plan: SplitPlan? = null
    private var participants: List<Peer> = emptyList()

    /** True while the CHAT server on this phone is the one this coordinator started (split or baseline). */
    @Volatile private var ownsChat = false

    /** What each phone was running before MemoryCloud stopped it ("self" is this phone). Survives an app restart. */
    private val restore = ConcurrentHashMap<String, List<RestoreItem>>()

    /** Memory a peer would free by stopping its chat and embedding servers, from its last /v1/models/state. */
    private val freeableMb = ConcurrentHashMap<String, Long>()
    private val headerCache = ConcurrentHashMap<String, Pair<Long, GgufInfo>>()

    private class Peer(val nodeId: String, val name: String, val addr: String) {
        val host: String get() = addr.substringBefore(':')
    }

    init {
        recoverAfterKill()
        // the whole phone says what it is doing (banner and notification); the banner's stop button stops the split
        scope.launch {
            _state.collect { s ->
                val text = MemCloudLogic.activityText(s)
                if (text == null) PhoneActivity.clear("memcloud") else PhoneActivity.set("memcloud", text) { stop() }
            }
        }
    }

    // ---------- planning (also used by the screen before anything is started) ----------

    /** Reads (and caches) the GGUF header of a file in the models folder. */
    fun header(file: String): GgufInfo {
        val f = ModelStore.fileOf(file)
        val cached = headerCache[file]
        if (cached != null && cached.first == f.lastModified()) return cached.second
        val info = GgufMeta.read(f)
        headerCache[file] = f.lastModified() to info
        return info
    }

    /** Peers that are online now, ordered by name (Phone A, B, C), optionally narrowed by node id or name. */
    fun onlinePeers(selection: List<String> = emptyList()): List<PeerView> =
        AppGraph.registry.peers.value.filter { !it.away }
            .filter { p -> selection.isEmpty() || selection.any { it.equals(p.info.nodeId, true) || it.equals(p.info.name, true) } }
            .sortedBy { it.info.name }
            .map { PeerView(it.info.nodeId, it.info.name, it.info.ip) }

    data class PeerView(val nodeId: String, val name: String, val addr: String)

    /** Plan for [file] over this phone and the [peers] given (node ids or names; empty = all online). */
    fun previewPlan(file: String, peers: List<String>, mode: SplitMode, manual: List<Int>? = null): SplitPlan {
        val info = header(file)
        return SplitPlanner.plan(info, devicesFor(selectPeers(peers)), mode, manual)
    }

    private fun selectPeers(sel: List<String>): List<Peer> = onlinePeers(sel).map { Peer(it.nodeId, it.name, it.addr) }

    private fun devicesFor(peers: List<Peer>): List<PlanDevice> {
        val self = AppGraph.registry.self.value
        val health = AppGraph.health.state.value
        val own = PlanDevice(self.nodeId, self.name, true, health.ramAvailMb + ownFreeableMb())
        val others = peers.map { p ->
            val ram = AppGraph.registry.peers.value.firstOrNull { it.info.nodeId == p.nodeId }?.health?.ramAvailMb ?: 0L
            PlanDevice(p.nodeId, p.name, false, ram + (freeableMb[p.nodeId] ?: 0L), p.host)
        }
        return listOf(own) + others
    }

    private fun ownFreeableMb(): Long {
        if (ownsChat) return 0
        return listOf(Role.CHAT, Role.EMBED).sumOf { r ->
            if (LlamaRuntime.statusOf(r).state == ServerState.STOPPED) 0L else (LlamaRuntime.rssKb(r) ?: 0L) / 1024
        }
    }

    /** Asks every online peer what its model servers hold, so the plan counts the memory they will free. Call it while the screen is open. */
    suspend fun refreshPeers() {
        coroutineScope {
            onlinePeers().map { p ->
                async {
                    runCatching { modelsState(p.addr) }.getOrNull()?.let { st ->
                        freeableMb[p.nodeId] = listOf("chat", "embed").sumOf { r ->
                            st.roles[r]?.takeIf { it.state != "STOPPED" }?.rssMb ?: 0L
                        }
                    }
                }
            }.awaitAll()
        }
    }

    // ---------- start / replan / stop ----------

    /** Returns null when the load was started, else the reason it was refused. */
    fun start(req: StartRequest): String? = synchronized(lock) {
        val ph = _state.value.phase
        if (opJob?.isActive == true) return "busy: ${ph.name}"
        if (!MemCloudLogic.canStart(ph)) return "a split is $ph; stop it or re-plan first"
        if (!ModelStore.fileOf(req.file).isFile) return "no such model file: ${req.file}"
        val mode = if (req.mode.equals("ram", true)) SplitMode.RAM else SplitMode.EQUAL
        opJob = scope.launch { runLoad(req.file, req.peers, mode, req.layers) }
        null
    }

    /** Load again on the phones that are still online (or [peers] when given). Allowed from DEGRADED, FAILED, READY and IDLE with a file chosen. */
    fun replan(peers: List<String> = emptyList()): String? = synchronized(lock) {
        val st = _state.value
        val file = st.file ?: return "nothing to re-plan"
        if (opJob?.isActive == true && st.phase != Phase.LOADING) return "busy: ${st.phase.name}"
        val online = AppGraph.registry.peers.value.filter { !it.away }.map { it.info.nodeId }
        val asked = peers.map { sel -> AppGraph.registry.peers.value.firstOrNull { it.info.nodeId == sel || it.info.name.equals(sel, true) }?.info?.nodeId ?: sel }
        // the phones of the last plan that are still online; when none is, whichever phones are online now
        val keep = MemCloudLogic.replanPeers(participants.map { it.nodeId }, online, asked).ifEmpty { online }
        if (keep.isEmpty()) return "no other phone is online to re-plan with"
        val old = opJob
        opJob = scope.launch {
            old?.cancel()
            teardownSplit()
            runLoad(file, keep, SplitMode.EQUAL, null)
        }
        null
    }

    fun stop() {
        scope.launch {
            try {
                val old = synchronized(lock) { opJob }
                old?.cancel()
                teardownSplit()
                restoreAll()
                _state.update { MemCloudState(phase = Phase.IDLE, baseline = it.baseline, smallModel = it.smallModel, steps = it.steps.takeLast(30) + "Stopped; chat and embedding servers restored", updatedAtMs = now()) }
            } finally {
                PhoneActivity.clear("memcloud")
            }
        }
    }

    private suspend fun runLoad(file: String, sel: List<String>, mode: SplitMode, manual: List<Int>?) {
        val t0 = now()
        stopWatch()
        cancelGeneration()
        val prev = _state.value
        _state.value = MemCloudState(phase = Phase.PLANNING, file = file, loadStartedAtMs = t0, baseline = prev.baseline, smallModel = prev.smallModel, pendingRestore = pendingText(), steps = listOf("Reading the model header"), updatedAtMs = t0)
        try {
            val info = withContext(Dispatchers.IO) { header(file) }
            val peers = selectPeers(sel)
            if (peers.isEmpty()) return fail("No other phone is online. The model needs ${gb(info.fileBytes)} and one phone cannot hold it.")
            step("Asking ${peers.joinToString { it.name }} what it is running")
            val snaps = coroutineScope { peers.map { p -> async { p to runCatching { modelsState(p.addr) } } }.awaitAll() }
            snaps.firstOrNull { it.second.isFailure }?.let { (p, r) -> return fail("${p.name} did not answer: ${r.exceptionOrNull()?.message}") }
            for ((p, r) in snaps) freeableMb[p.nodeId] = r.getOrThrow().roles.filterKeys { it == "chat" || it == "embed" }.values.sumOf { if (it.state == "STOPPED") 0L else it.rssMb ?: 0L }

            val sp = SplitPlanner.plan(info, devicesFor(peers), mode, manual)
            val args = SplitPlanner.serverArgs(sp, Ports.RPC, SPLIT_CTX)
            if (!SplitPlanner.argsMatchPlan(sp, args)) return fail("Internal error: the llama-server flags do not place the layers as planned: $args")
            plan = sp
            participants = peers
            val warnings = sp.fits().filter { !it.ok }.map { "${it.device.name} has ${mb(it.haveMb)} free and the plan needs ${mb(it.needMb)}" }
            val moe = if (info.isMoe) info.moeLayers else 0
            _state.update {
                it.copy(
                    model = info.name, layers = info.blockCount, experts = info.expertCount.takeIf { n -> n > 0 }, expertsUsed = info.expertUsedCount.takeIf { n -> n > 0 },
                    moeLayers = moe, fileBytes = info.fileBytes, ctx = SPLIT_CTX, args = args,
                    shares = shareViews(sp), phones = initialPhones(sp), boundariesPerToken = sp.boundariesPerToken(), warnings = warnings,
                    steps = it.steps + "Planned ${sp.shares.joinToString { s -> "${s.device.name} layers ${s.range}" }}",
                    phase = Phase.STARTING_PEERS, updatedAtMs = now(),
                )
            }

            // memory hygiene: peers first, in parallel, then this phone
            val prepared = coroutineScope { peers.map { p -> async { p to runCatching { prepPeer(p, snaps.first { it.first === p }.second.getOrThrow()) } } }.awaitAll() }
            prepared.firstOrNull { it.second.isFailure }?.let { (p, r) -> return fail("${p.name}: ${r.exceptionOrNull()?.message}") }
            measureSmallModel()
            prepSelf(file)

            // is every rpc-server reachable from this phone?
            for (p in peers) {
                if (!reachable(p.host, Ports.RPC)) return fail("${p.name}'s rpc-server is running but ${p.host}:${Ports.RPC} does not answer from this phone")
            }

            val path = ModelStore.fileOf(file).absolutePath
            _state.update { it.copy(phase = Phase.LOADING, loadStartedAtMs = now(), steps = it.steps + "Loading: llama-server ${args.joinToString(" ")}", updatedAtMs = now()) }
            ownsChat = true
            val server = LlamaRuntime.startAsync(Role.CHAT, path, args, readyTimeoutMs = LOAD_TIMEOUT_MS)
            startWatch()
            val status = server.await()
            if (_state.value.phase != Phase.LOADING) return // lost a phone while loading: DEGRADED already says so
            if (status.state == ServerState.READY) {
                refreshLive(final = true)
                _state.update {
                    it.copy(phase = Phase.READY, loadMs = status.readyAfterMs, steps = it.steps + "Ready after ${(status.readyAfterMs ?: 0) / 1000} s", updatedAtMs = now())
                }
            } else {
                val culprit = attribute(status.reason.orEmpty() + " " + LlamaRuntime.logTail(Role.CHAT, 30).joinToString(" "))
                ownsChat = false
                if (culprit != null) degrade(culprit, status.reason ?: "llama-server stopped") else fail(status.reason ?: "llama-server did not start")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(e.message ?: e.toString())
        }
    }

    private fun fail(reason: String) {
        stopWatch()
        _state.update { it.copy(phase = Phase.FAILED, failReason = reason, steps = it.steps + "Failed: $reason", updatedAtMs = now()) }
    }

    private suspend fun teardownSplit() {
        stopWatch()
        cancelGeneration()
        if (ownsChat) { LlamaRuntime.stop(Role.CHAT); ownsChat = false }
        withContext(Dispatchers.IO) { delay(200) }
    }

    // ---------- memory hygiene ----------

    private suspend fun prepPeer(p: Peer, snap: ModelsStateDto) {
        val items = listOf("chat", "embed").mapNotNull { r ->
            snap.roles[r]?.takeIf { it.state != "STOPPED" && it.file != null }?.let { RestoreItem(r, it.file!!, it.rssMb) }
        }
        remember(p.nodeId, items)
        val freed = ArrayList<String>()
        for (r in listOf("chat", "embed")) {
            val role = snap.roles[r] ?: continue
            if (role.state == "STOPPED") continue
            setPhone(p.nodeId) { it.copy(step = "Stopping its $r server (${role.file}) to free memory") }
            step("${p.name}: stopping $r server ${role.file}${role.rssMb?.let { " (${mb(it)})" } ?: ""}")
            AppGraph.nodeClient.raw(HttpMethod.Post, p.addr, "/models/stop", """{"role":"$r"}""", 20_000)
            freed += "$r ${role.file}${role.rssMb?.let { ", ${mb(it)}" } ?: ""}"
        }
        setPhone(p.nodeId) { it.copy(step = "Starting rpc-server", freed = freed, restore = restoreText(p.nodeId)) }
        step("${p.name}: starting rpc-server on ${p.host}:${Ports.RPC} with the tensor cache")
        val body = SwarmJson.encodeToString(RpcStartRequest(port = Ports.RPC, cache = true, bind = p.host, restore = restore[p.nodeId] ?: emptyList()))
        val st = SwarmJson.decodeFromString<RpcStateDto>(AppGraph.nodeClient.raw(HttpMethod.Post, p.addr, "/memcloud/rpc/start", body, 60_000))
        if (st.state != "READY") throw IllegalStateException("rpc-server is ${st.state}: ${st.reason ?: st.lastLog.lastOrNull().orEmpty()}")
        setPhone(p.nodeId) { it.copy(step = "rpc-server listening, waiting for weights") }
    }

    /** A 48-token answer from the small chat model that is about to be stopped: its tok/s is the scale for the split's number. */
    private suspend fun measureSmallModel() {
        if (ownsChat || LlamaRuntime.statusOf(Role.CHAT).state != ServerState.READY) return
        val llm = AppGraph.llm
        if (llm.modelName == "FAKE") return
        step("Measuring ${llm.modelName} on this phone alone, for scale")
        val r = withTimeoutOrNull(60_000) {
            runCatching { llm.chat(ChatRequest(listOf(ChatMessage("user", "Explain how a heat pump works.")), maxTokens = 48, temperature = 0.0)) }.getOrNull()
        } ?: return
        val tps = r.stats.decodeTokPerSec
        if (tps > 0) _state.update { it.copy(smallModel = SmallModelView(llm.modelName, tps, r.stats.completionTokens)) }
    }

    private fun prepSelf(splitFile: String) {
        val name = AppGraph.registry.self.value.name
        val items = ArrayList<RestoreItem>()
        for (role in listOf(Role.CHAT, Role.EMBED)) {
            val st = LlamaRuntime.statusOf(role)
            if (st.state == ServerState.STOPPED) continue
            if (role == Role.CHAT && ownsChat) continue
            val file = st.modelPath?.let { File(it).name } ?: continue
            val rss = LlamaRuntime.rssKb(role)?.div(1024)
            items += RestoreItem(role.tag, file, rss)
            step("$name: stopping ${role.tag} server $file${rss?.let { " (${mb(it)})" } ?: ""} to make room for $splitFile")
            LlamaRuntime.stop(role)
        }
        remember("self", items)
        val own = AppGraph.registry.self.value.nodeId
        setPhone(own) { it.copy(step = "Stopped its own model servers, loading the split", freed = items.map { i -> "${i.role} ${i.file}${i.mb?.let { m -> ", ${mb(m)}" } ?: ""}" }, restore = restoreText("self")) }
    }

    private fun remember(key: String, items: List<RestoreItem>) {
        val merged = (restore[key] ?: emptyList()).toMutableList()
        for (i in items) if (merged.none { it.role == i.role }) merged += i
        restore[key] = merged
        persistRestore()
        _state.update { it.copy(pendingRestore = pendingText()) }
    }

    private fun pendingText(): List<String> = restore.entries.flatMap { (k, items) ->
        val who = if (k == "self") AppGraph.registry.self.value.name else AppGraph.registry.peers.value.firstOrNull { it.info.nodeId == k }?.info?.name ?: k
        items.map { "$who: ${it.role} ${it.file}" }
    }

    private fun restoreText(key: String): List<String> = (restore[key] ?: emptyList()).map { "${it.role} ${it.file}" }

    /** Brings back what [prepPeer] and [prepSelf] stopped: peers stop rpc-server and reload their models, this phone does the same. */
    private suspend fun restoreAll() {
        val peers = AppGraph.registry.peers.value
        coroutineScope {
            for (p in peers) {
                async {
                    // the worker restarts its own model servers when rpc-server stops (it holds the list from /rpc/start),
                    // and does the same on its own if this phone's heartbeat is lost for 10 s
                    runCatching { AppGraph.nodeClient.raw(HttpMethod.Post, p.info.ip, "/memcloud/rpc/stop", "", 10_000) }
                }
            }
        }
        for (i in restore["self"] ?: emptyList()) {
            val role = Role.parse(i.role) ?: continue
            if (ModelStore.fileOf(i.file).isFile) ModelsModule.loadModelAsync(role, i.file)
        }
        restore.clear()
        persistRestore()
    }

    // ---------- watching ----------

    private fun startWatch() {
        stopWatch()
        watchJob = scope.launch {
            var tick = 0
            while (isActive) {
                delay(WATCH_MS)
                tick++
                val ph = _state.value.phase
                if (!MemCloudLogic.isActive(ph)) continue
                lostPeer()?.let { (p, why) -> degrade(p, why); return@launch }
                if (ph == Phase.READY && LlamaRuntime.statusOf(Role.CHAT).state != ServerState.READY && ownsChat) {
                    val st = LlamaRuntime.statusOf(Role.CHAT)
                    val culprit = attribute(st.reason.orEmpty() + " " + LlamaRuntime.logTail(Role.CHAT, 30).joinToString(" ")) ?: unresponsivePeer()
                    ownsChat = false
                    if (culprit != null) degrade(culprit, st.reason ?: "llama-server stopped") else fail("llama-server stopped: ${st.reason ?: st.state.name}")
                    return@launch
                }
                refreshLive(final = false, rpcEvery = if (ph == Phase.LOADING) 1 else 2, tick = tick)
            }
        }
    }

    private fun stopWatch() {
        watchJob?.cancel()
        watchJob = null
    }

    /** A participant whose heartbeat says away (three missed beats, about 3 s) or that left the registry. */
    private fun lostPeer(): Pair<Peer, String>? {
        val online = AppGraph.registry.peers.value.filter { !it.away }.map { it.info.nodeId }.toSet()
        val lost = MemCloudLogic.lostParticipants(participants.map { it.nodeId }, online).firstOrNull() ?: return null
        return participants.first { it.nodeId == lost } to "heartbeat lost"
    }

    /** A participant whose rpc-server no longer answers, found by asking each one. */
    private suspend fun unresponsivePeer(): Peer? = coroutineScope {
        participants.map { p -> async { p to runCatching { rpcState(p) }.getOrNull() } }.awaitAll()
            .firstOrNull { (_, st) -> st == null || st.state != "READY" }?.first
    }

    /** Which peer a llama-server failure is about, judging by the endpoint named in its log. */
    private fun attribute(text: String): Peer? = participants.firstOrNull { text.contains("${it.host}:") || text.contains(it.host) }

    private fun degrade(p: Peer, reason: String) {
        val changed = synchronized(lock) {
            val next = MemCloudLogic.degraded(_state.value, p.nodeId, p.name, reason, now())
            if (next != null) _state.value = next
            next != null
        }
        if (!changed) return
        cancelGeneration()
        if (ownsChat) { LlamaRuntime.stop(Role.CHAT); ownsChat = false }
    }

    /** Refreshes the per-phone numbers (and the log view) in [state]. */
    private suspend fun refreshLive(final: Boolean, rpcEvery: Int = 1, tick: Int = 0) {
        val sp = plan ?: return
        val parser = LoadLogParser().also { it.feed(LlamaRuntime.logTail(Role.CHAT, 400)) }
        val log = parser.snapshot()
        val rpc: Map<String, RpcStateDto?> =
            if (final || tick % rpcEvery == 0) coroutineScope {
                participants.map { p -> async { p.nodeId to runCatching { rpcState(p) }.getOrNull() } }.awaitAll().toMap()
            } else emptyMap()
        val reg = AppGraph.registry.peers.value
        val ownHealth = AppGraph.health.state.value
        _state.update { cur ->
            val phones = cur.phones.map { v ->
                if (v.isDriver) {
                    v.copy(rssMb = LlamaRuntime.rssKb(Role.CHAT)?.div(1024), peakMb = LlamaRuntime.peakRssKb(Role.CHAT)?.div(1024), thermalStatus = ownHealth.thermalStatus, ramAvailMb = ownHealth.ramAvailMb,
                        step = when (cur.phase) {
                            Phase.READY -> "Serving; layers ${sp.driver.range}"
                            Phase.LOADING -> "Loading its layers ${sp.driver.range} and feeding the others"
                            else -> v.step
                        })
                } else {
                    val share = sp.shares.firstOrNull { it.device.nodeId == v.nodeId }
                    val entry = reg.firstOrNull { it.info.nodeId == v.nodeId }
                    val st = rpc[v.nodeId]
                    val linkRtt = PeerLinks.find(v.nodeId)?.status?.value?.rttMs
                    val received = st?.anonMb ?: v.receivedMb
                    val frac = if (share != null) MemCloudLogic.loadFraction(received, share.weightBytes) ?: v.loadFraction else v.loadFraction
                    v.copy(
                        rssMb = st?.rssMb ?: v.rssMb, peakMb = st?.peakMb ?: v.peakMb, receivedMb = received, cachedMb = st?.let { it.cachedBytes / (1024 * 1024) } ?: v.cachedMb,
                        loadFraction = if (cur.phase == Phase.READY) 1f else frac,
                        thermalStatus = entry?.health?.thermalStatus ?: st?.thermalStatus ?: v.thermalStatus,
                        ramAvailMb = entry?.health?.ramAvailMb ?: v.ramAvailMb, rttMs = linkRtt,
                        step = when {
                            !v.online -> v.step
                            cur.phase == Phase.READY -> "Serving; layers ${share?.range}"
                            frac >= 0.999f -> "Weights in place"
                            else -> "Receiving weights"
                        },
                    )
                }
            }
            cur.copy(
                phones = phones,
                rttMs = ExpertMesh.meanRtt(phones.filter { !it.isDriver }.map { it.rttMs }),
                log = LogView(log.offloaded, log.offloadableTotal, log.buffers.map { b -> "${b.name} ${b.modelMiB?.toLong() ?: "-"} MiB" }, log.listening, log.error),
                updatedAtMs = now(),
            )
        }
    }

    // ---------- chat on the split ----------

    /** Returns null when the generation started, else why not. The answer streams into [state].generation. */
    fun ask(prompt: String, maxTokens: Int = 200): String? = synchronized(lock) {
        if (_state.value.phase != Phase.READY) return "the split is ${_state.value.phase}, not READY"
        if (genJob?.isActive == true) return "an answer is already being generated"
        genJob = scope.launch { generate(prompt, maxTokens) }
        null
    }

    fun cancelGeneration() {
        genJob?.cancel()
        genJob = null
    }

    private suspend fun generate(prompt: String, maxTokens: Int) {
        _state.update { it.copy(generation = GenerationView(prompt)) }
        val t0 = System.nanoTime()
        var first = 0L
        var chunks = 0
        val text = StringBuilder()
        try {
            val llm = AppGraph.llm
            check(llm.modelName != "FAKE") { "the chat server is not running" }
            llm.chatStream(ChatRequest(listOf(ChatMessage("user", prompt)), maxTokens = maxTokens, temperature = 0.7)).collect { c ->
                if (c.delta.isNotEmpty()) {
                    val n = System.nanoTime()
                    if (first == 0L) first = n
                    chunks++
                    text.append(c.delta)
                    val rate = if (n > first) (chunks - 1) * 1e9 / (n - first) else null
                    _state.update { it.copy(generation = it.generation?.copy(text = text.toString(), ttftMs = (first - t0) / 1_000_000, decodeTokPerSec = rate, tokens = chunks)) }
                }
                c.stats?.let { s ->
                    _state.update { it.copy(generation = it.generation?.copy(decodeTokPerSec = s.decodeTokPerSec.takeIf { v -> v > 0 }, tokens = s.completionTokens)) }
                }
            }
            _state.update { it.copy(generation = it.generation?.copy(running = false)) }
        } catch (e: CancellationException) {
            _state.update { it.copy(generation = it.generation?.copy(running = false)) }
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(generation = it.generation?.copy(running = false, error = e.message ?: e.toString())) }
            // a dead stream usually means a dead phone: do not wait for the next watch tick
            if (_state.value.phase == Phase.READY) {
                val culprit = lostPeer()?.first ?: unresponsivePeer()
                if (culprit != null) degrade(culprit, "stream failed: ${e.message}")
            }
        }
    }

    // ---------- baseline: the same model on this phone alone ----------

    /** Returns null when the attempt started. It replaces the split server (they share the chat port). */
    fun baseline(timeoutSec: Int = 120): String? = synchronized(lock) {
        val st = _state.value
        val file = st.file ?: return "choose a model first (start a split once, or pass a file)"
        if (opJob?.isActive == true && st.phase != Phase.READY && st.phase != Phase.DEGRADED && st.phase != Phase.FAILED) return "busy: ${st.phase.name}"
        opJob = scope.launch { runBaseline(file, timeoutSec) }
        null
    }

    private suspend fun runBaseline(file: String, timeoutSec: Int) {
        teardownSplit()
        val t0 = now()
        _state.update { it.copy(phase = Phase.BASELINE, baseline = BaselineView(true, "LOADING", "Loading $file on this phone with no RPC", 0), steps = it.steps + "Baseline: loading $file on this phone alone (timeout $timeoutSec s)", updatedAtMs = t0) }
        prepSelf(file)
        val path = ModelStore.fileOf(file).absolutePath
        val args = listOf("-ngl", "0", "-c", SPLIT_CTX.toString(), "-np", "1", "-fit", "off")
        markBaselinePending(file, t0, timeoutSec)
        ownsChat = true
        val server = LlamaRuntime.startAsync(Role.CHAT, path, args, readyTimeoutMs = timeoutSec * 1000L)
        val ticker = scope.launch {
            while (isActive) {
                val rss = LlamaRuntime.rssKb(Role.CHAT)?.div(1024)
                _state.update { it.copy(baseline = it.baseline?.copy(elapsedMs = now() - t0, detail = "Loading on this phone alone${rss?.let { r -> ", ${mb(r)} resident" } ?: ""}")) }
                delay(1000)
            }
        }
        var result: BaselineView
        try {
            val status = server.await()
            result = when {
                status.state == ServerState.READY -> measureBaselineDecode(t0)
                (status.reason ?: "").contains("killed by signal") -> BaselineView(false, "KILLED", status.reason.orEmpty(), now() - t0)
                (status.reason ?: "").startsWith("not ready after") -> BaselineView(false, "TIMEOUT", "Not loaded after $timeoutSec s. ${LlamaRuntime.logTail(Role.CHAT, 1).lastOrNull().orEmpty()}", now() - t0)
                else -> {
                    val err = LoadLogParser().also { it.feed(LlamaRuntime.logTail(Role.CHAT, 200)) }.snapshot().error
                    BaselineView(false, "FAILED", err ?: status.reason.orEmpty(), now() - t0)
                }
            }
        } finally {
            ticker.cancel()
            LlamaRuntime.stop(Role.CHAT)
            ownsChat = false
            clearBaselinePending()
        }
        _state.update { it.copy(phase = Phase.IDLE, baseline = result, phones = emptyList(), steps = it.steps + "Baseline: ${result.outcome} after ${result.elapsedMs / 1000} s", updatedAtMs = now()) }
    }

    private suspend fun measureBaselineDecode(t0: Long): BaselineView {
        val loadedMs = now() - t0
        _state.update { it.copy(baseline = it.baseline?.copy(outcome = "LOADING", detail = "Loaded in ${loadedMs / 1000} s; generating 32 tokens")) }
        val r = withTimeoutOrNull(120_000) {
            runCatching { AppGraph.llm.chat(ChatRequest(listOf(ChatMessage("user", "Explain how a heat pump works.")), maxTokens = 32, temperature = 0.0)) }
        }
        return when {
            r == null -> BaselineView(false, "TIMEOUT", "Loaded in ${loadedMs / 1000} s, but 32 tokens took more than 120 s", now() - t0)
            r.isFailure -> BaselineView(false, "FAILED", "Loaded in ${loadedMs / 1000} s, then: ${r.exceptionOrNull()?.message}", now() - t0)
            else -> {
                val s = r.getOrThrow().stats
                BaselineView(false, "OK", "Loaded in ${loadedMs / 1000} s; prefill ${"%.1f".format(s.promptTokens * 1000.0 / s.promptMs.coerceAtLeast(1.0))} tok/s", now() - t0, s.decodeTokPerSec)
            }
        }
    }

    // ---------- persistence across an app kill ----------

    private fun prefs() = AppGraph.appContext.getSharedPreferences("memcloud", Context.MODE_PRIVATE)

    private fun persistRestore() {
        runCatching { prefs().edit().putString("restore", SwarmJson.encodeToString(restore.toMap())).apply() }
    }

    private fun markBaselinePending(file: String, startMs: Long, timeoutSec: Int) {
        runCatching { prefs().edit().putString("baseline_pending", "$file|$startMs|$timeoutSec").apply() }
    }

    private fun clearBaselinePending() {
        runCatching { prefs().edit().remove("baseline_pending").apply() }
    }

    /** If Android killed the whole app during the single-phone attempt, say so instead of losing the result. */
    private fun recoverAfterKill() {
        runCatching {
            val p = prefs()
            p.getString("restore", null)?.let { json ->
                SwarmJson.decodeFromString<Map<String, List<RestoreItem>>>(json).forEach { (k, v) -> restore.putIfAbsent(k, v) }
            }
            p.getString("baseline_pending", null)?.let { raw ->
                val parts = raw.split('|')
                val start = parts.getOrNull(1)?.toLongOrNull() ?: now()
                _state.update {
                    it.copy(
                        file = parts.getOrNull(0), phase = Phase.IDLE,
                        baseline = BaselineView(false, "KILLED", "The whole app was killed by Android while loading the model on this phone alone (the app restarted).", (now() - start).coerceAtLeast(0)),
                        steps = it.steps + "Baseline: the app was killed during the single-phone attempt",
                    )
                }
                clearBaselinePending()
            }
        }
    }

    // ---------- peers over the Node API ----------

    private suspend fun modelsState(addr: String): ModelsStateDto =
        SwarmJson.decodeFromString(AppGraph.nodeClient.raw(HttpMethod.Get, addr, "/models/state", "", 4_000))

    private suspend fun rpcState(p: Peer): RpcStateDto =
        SwarmJson.decodeFromString(AppGraph.nodeClient.raw(HttpMethod.Get, p.addr, "/memcloud/rpc/state", "", RPC_CALL_MS))

    private fun reachable(host: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), 1_500); true }
    } catch (_: Exception) {
        false
    }

    // ---------- state helpers ----------

    private fun step(text: String) {
        _state.update { it.copy(steps = (it.steps + text).takeLast(60), updatedAtMs = now()) }
    }

    private fun setPhone(nodeId: String, f: (PhoneView) -> PhoneView) {
        _state.update { s -> s.copy(phones = s.phones.map { if (it.nodeId == nodeId) f(it) else it }, updatedAtMs = now()) }
    }

    private fun shareViews(sp: SplitPlan): List<ShareView> = sp.shares.zip(sp.fits()).map { (s, f) ->
        ShareView(s.device.nodeId, s.device.name, s.device.isDriver, s.firstLayer, s.layerCount, s.range, (s.weightBytes / (1024 * 1024)), f.needMb, f.haveMb, f.ok)
    }

    private fun initialPhones(sp: SplitPlan): List<PhoneView> = sp.shares.map { s ->
        val health = if (s.device.isDriver) AppGraph.health.state.value else AppGraph.registry.peers.value.firstOrNull { it.info.nodeId == s.device.nodeId }?.health
        PhoneView(
            nodeId = s.device.nodeId, name = s.device.name, isDriver = s.device.isDriver,
            step = if (s.device.isDriver) "Waiting for the peers" else "Waiting",
            thermalStatus = health?.thermalStatus, ramAvailMb = health?.ramAvailMb,
            rttMs = if (s.device.isDriver) null else PeerLinks.find(s.device.nodeId)?.status?.value?.rttMs,
        )
    }

    private fun now() = System.currentTimeMillis()
    private fun mb(v: Long) = if (v >= 1024) "%.1f GB".format(v / 1024.0) else "$v MB"
    private fun gb(bytes: Long) = "%.1f GB".format(bytes / 1073741824.0)
}
