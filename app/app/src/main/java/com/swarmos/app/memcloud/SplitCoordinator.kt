package com.swarmos.app.memcloud

import com.swarmos.app.core.Ports
import com.swarmos.app.llm.ChatMessage
import com.swarmos.app.llm.ChatRequest
import com.swarmos.app.llm.Role
import com.swarmos.app.llm.ServerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Cancels a load because a re-plan replaces it: the peers stay prepared, so the cleanup of a cancelled load is skipped. */
private class ReplanCancel : CancellationException("re-plan")

/**
 * MemoryCloud coordinator: this phone drives one model whose layers live on several phones.
 *
 * Flow: PLANNING (read the GGUF header, size the split; a plan that does not fit is refused unless forced) -> STARTING_PEERS
 * (each peer gets /v1/memcloud/rpc/start first, which carries the restore list and starts the lease; a peer on this build stops
 * its own chat and embedding servers as it accepts, one on the older build has them stopped through /v1/models/stop right after;
 * this phone stops its own too) -> LOADING (llama-server with --rpc starts; progress is the bytes each peer's rpc-server has
 * received) -> READY.
 * While a split is loading or serving, a renew job extends every worker's lease every few seconds (a worker whose lease
 * runs out stops rpc-server and restores its own servers, whatever this phone does), and a 1 s watch compares the
 * peers' heartbeats and the server process: a lost phone gives DEGRADED naming it, the stream stops and the server is
 * killed, and [replan] loads again on the phones that remain.
 *
 * Every way out of a split (stop, a failure, a cancelled load, the app restarting) runs the same teardown: workers get
 * /rpc/stop, this phone's chat and embedding servers come back, the activity line is cleared.
 */
class SplitCoordinator(
    private val env: SplitEnv,
    private val scope: CoroutineScope,
    private val timing: Timing = Timing(),
) {
    data class Timing(
        val watchMs: Long = 1_000,
        /** How often workers' leases are renewed; the lease itself is [RpcLender.LEASE_MS]. */
        val renewMs: Long = 3_000,
        /** In DEGRADED the remaining workers are still renewed this long (for a quick re-plan), then left to expire. */
        val degradedRenewMs: Long = 60_000,
        val loadTimeoutMs: Long = 40L * 60_000,
        val settleMs: Long = 200,
        /** How often a peer that has not confirmed it got its servers back is tried again. */
        val restoreRetryMs: Long = 5_000,
        /** A lender whose rpc port is still held is stopped, waited for this long and asked again, this many times. */
        val portRetries: Int = 2,
        val portRetryDelayMs: Long = 2_000,
        /** A split answer that has produced no token for this long (120 s before the first token, 30 s after) has stopped. */
        val firstTokenStallMs: Long = 120_000,
        val tokenStallMs: Long = 30_000,
        val stallPollMs: Long = 1_000,
        /** A serving split is degraded when a participant's rpc-server fails this many state checks in a row. */
        val probeFailLimit: Int = 3,
        /** A split that stays DEGRADED this long with no re-plan gives this phone's own chat and embedding models back. */
        val degradedRestoreMs: Long = 120_000,
    )

    companion object {
        const val SPLIT_CTX = 4096
        const val ACTIVITY_OWNER = "memcloud.driver"
    }

    private val _state = MutableStateFlow(MemCloudState())
    val state: StateFlow<MemCloudState> = _state.asStateFlow()

    private val lock = Any()
    private var opJob: Job? = null
    private var watchJob: Job? = null
    private var renewJob: Job? = null
    private var genJob: Job? = null
    private var plan: SplitPlan? = null
    private var participants: List<PeerInfo> = emptyList()

    /** Accumulates the start-up log of the split server across watch ticks (the log ring only keeps the last 400 lines). */
    @Volatile private var loadParser = LoadLogParser()

    /** True while the CHAT server on this phone is the one this coordinator started (split or baseline). */
    @Volatile private var ownsChat = false

    /** True when the running split was started past the memory check (a re-plan keeps the choice). */
    @Volatile private var forced = false

    /** What each phone was running before MemoryCloud stopped it ("self" is this phone). Survives an app restart. */
    private val restore = ConcurrentHashMap<String, List<RestoreItem>>()

    /** Peers that were sent any command (they get /rpc/stop on every way out), and those whose rpc-server answered READY. */
    private val touched = ConcurrentHashMap<String, PeerInfo>()
    private val rpcStarted = ConcurrentHashMap<String, PeerInfo>()

    /** The generation token each lender returned from /rpc/start (node id to token); sent back on renew and stop. A lender on the older build gives none. */
    private val loans = ConcurrentHashMap<String, String>()

    /**
     * Peers whose stopped servers are not confirmed back: node id to Node API address. Their items stay in [restore] and in the
     * persisted record until a call to the peer succeeds (the peer was unreachable, or refused).
     */
    private val unconfirmed = ConcurrentHashMap<String, String>()
    private var retryJob: Job? = null

    /** Consecutive failed rpc-server state checks per participant while serving. */
    private val probeFailures = ConcurrentHashMap<String, Int>()

    /** Waits [Timing.degradedRestoreMs] in DEGRADED, then restores; [degradedNote] says so in the state. */
    private var degradedJob: Job? = null
    @Volatile private var degradedNote: String? = null

    @Volatile private var baselinePending: String? = null

    /** Memory a peer would free by stopping its chat and embedding servers, from its last /v1/models/state. */
    private val freeableMb = ConcurrentHashMap<String, Long>()
    private val headerCache = ConcurrentHashMap<String, Pair<Long, GgufInfo>>()

    private val recoverLock = Mutex()
    @Volatile private var recovered = false

    init {
        // the whole phone says what it is doing (banner and notification); the banner's stop button stops the split.
        // Its owner is separate from the lending role's, so neither clears the other's line.
        scope.launch {
            _state.collect { s ->
                val text = MemCloudLogic.activityText(s)
                if (text == null) env.clearActivity(ACTIVITY_OWNER) else env.setActivity(ACTIVITY_OWNER, text) { stop() }
            }
        }
    }

    // ---------- start of the app: bring back what a killed session had stopped ----------

    /** Reads what an earlier session left behind (off the main thread) and cleans it up. Runs once; safe to call often. */
    suspend fun onAppStart() {
        recoverLock.withLock {
            if (recovered) return
            recovered = true
            withContext(Dispatchers.IO) { recoverAfterKill() }
        }
    }

    private suspend fun recoverAfterKill() {
        val p = runCatching { env.load() }.getOrNull() ?: return
        p.baselinePending?.let { raw ->
            val parts = raw.split('|')
            val start = parts.getOrNull(1)?.toLongOrNull() ?: env.nowMs()
            _state.update {
                it.copy(
                    file = parts.getOrNull(0), phase = Phase.IDLE,
                    baseline = BaselineView(false, "KILLED", "The whole app was killed by Android while loading the model on this phone alone (the app restarted).", (env.nowMs() - start).coerceAtLeast(0)),
                    steps = it.steps + "Baseline: the app was killed during the single-phone attempt",
                )
            }
        }
        if (p.restore.isNotEmpty() || p.peerAddrs.isNotEmpty()) {
            // a split was running when this app died: workers get /rpc/stop (they restore themselves, and would on lease expiry anyway),
            // peers that never started rpc-server get their stopped servers back, and this phone's own servers come back
            coroutineScope {
                for ((nodeId, addr) in p.peerAddrs) {
                    launch {
                        val items = p.restore[nodeId] ?: emptyList()
                        // a peer that cannot be reached keeps its record: the driver still owes it those servers
                        if (!confirmPeerRestored(addr, items) && items.isNotEmpty()) {
                            restore[nodeId] = items
                            unconfirmed[nodeId] = addr
                        }
                    }
                }
            }
            for (i in p.restore["self"] ?: emptyList()) {
                val role = Role.parse(i.role) ?: continue
                if (env.local(role).state == ServerState.STOPPED) env.restoreLocal(role, i.file)
            }
            _state.update {
                it.copy(steps = it.steps + if (unconfirmed.isEmpty()) "The app restarted during a split: workers stopped, servers restored" else "The app restarted during a split: ${unconfirmed.keys.joinToString { k -> peerName(k) }} could not be reached, its servers are still owed")
            }
        }
        baselinePending = null
        persist()
        publishPending()
        startRestoreRetry()
        env.clearActivity(ACTIVITY_OWNER)
    }

    /**
     * Brings a peer back to how it was: rpc-server stopped (a lender restores its own servers when that happens), and every server in
     * [items] that is still stopped is loaded again. False when the peer could not be reached or refused, so the caller keeps the record.
     */
    private suspend fun stopRpcOn(addr: String, loan: String? = null): Boolean = try {
        env.peerRpcStop(addr, loan)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: LoanNotOurs) {
        true // the loan is another phone's or a newer one: there is nothing of ours left to end
    } catch (e: Exception) {
        false
    }

    private suspend fun confirmPeerRestored(addr: String, items: List<RestoreItem>, loan: String? = null): Boolean = try {
        env.peerRpcStop(addr, loan)
        val snap = env.peerModels(addr)
        for (i in items) if (snap[i.role]?.state == "STOPPED") env.peerLoadModel(addr, i.role, i.file)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: LoanNotOurs) {
        true // another phone holds this lender now (or a newer loan of ours): whatever we stopped came back when ours ended
    } catch (e: Exception) {
        false
    }

    private fun peerName(nodeId: String): String = env.peers().firstOrNull { it.nodeId == nodeId }?.name ?: nodeId

    // ---------- planning (also used by the screen before anything is started) ----------

    /** Reads (and caches) the GGUF header of a file in the models folder; a corrupt file gives [GgufFormatException]. */
    fun header(file: String): GgufInfo {
        val f = env.modelFile(file) ?: throw IllegalArgumentException("no such model file in the models folder: $file")
        val cached = headerCache[f.name]
        if (cached != null && cached.first == f.lastModified()) return cached.second
        val info = env.readHeader(f)
        headerCache[f.name] = f.lastModified() to info
        return info
    }

    /** Peers that are online now, ordered by name (Phone A, B, C), optionally narrowed by node id or name. */
    fun onlinePeers(selection: List<String> = emptyList(), emptyMeansNone: Boolean = false): List<PeerInfo> =
        env.peers().filter { !it.away }
            .filter { p -> if (selection.isEmpty()) !emptyMeansNone else selection.any { it.equals(p.nodeId, true) || it.equals(p.name, true) } }
            .sortedBy { it.name }

    /** Plan for [file] over this phone and the [peers] given (node ids or names; empty = all online). */
    fun previewPlan(file: String, peers: List<String>, mode: SplitMode, manual: List<Int>? = null, emptyMeansNone: Boolean = false): SplitPlan {
        val info = header(file)
        return SplitPlanner.plan(info, devicesFor(onlinePeers(peers, emptyMeansNone)), mode, manual)
    }

    private fun devicesFor(peers: List<PeerInfo>): List<PlanDevice> {
        val health = env.selfHealth()
        val own = PlanDevice(env.selfId(), env.selfName(), true, health.ramAvailMb + ownFreeableMb())
        val others = peers.map { p ->
            val ram = env.peers().firstOrNull { it.nodeId == p.nodeId }?.ramAvailMb ?: 0L
            PlanDevice(p.nodeId, p.name, false, ram + (freeableMb[p.nodeId] ?: 0L), p.host)
        }
        return listOf(own) + others
    }

    private fun ownFreeableMb(): Long {
        if (ownsChat) return 0
        return listOf(Role.CHAT, Role.EMBED).sumOf { r ->
            val s = env.local(r)
            if (s.state == ServerState.STOPPED) 0L else s.rssMb ?: 0L
        }
    }

    /** Asks every online peer what its model servers hold, so the plan counts the memory they will free. Call it while the screen is open. */
    suspend fun refreshPeers() {
        coroutineScope {
            onlinePeers().map { p ->
                async {
                    runCatching { env.peerModels(p.addr) }.getOrNull()?.let { roles ->
                        freeableMb[p.nodeId] = listOf("chat", "embed").sumOf { r -> roles[r]?.takeIf { it.state != "STOPPED" }?.rssMb ?: 0L }
                    }
                }
            }.awaitAll()
        }
    }

    // ---------- start / replan / stop ----------

    /**
     * Why [req] cannot be accepted whatever the phones do, or null: today only an explicit [StartRequest.layers] that is wrong for the
     * model and the phones online now (see [SplitPlanner.checkLayers]). Reads the GGUF header, so call it off the main thread.
     * The route answers 400 with this text; [start] itself would fail the load with the same words.
     */
    fun validateStart(req: StartRequest): String? {
        val layers = req.layers ?: return null
        val file = env.modelFile(req.file)?.name ?: return null // start() says there is no such file
        val peers = onlinePeers(req.peers)
        if (peers.isEmpty()) return null // start() says no other phone is online
        return try {
            SplitPlanner.checkLayers(layers, header(file).blockCount, peers.size + 1)
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
    }

    /** Returns null when the load was started, else the reason it was refused. */
    fun start(req: StartRequest): String? = synchronized(lock) {
        val ph = _state.value.phase
        if (opJob?.isActive == true) return "busy: ${ph.name}"
        if (!MemCloudLogic.canStart(ph)) return "a split is $ph; stop it or re-plan first"
        val f = env.modelFile(req.file) ?: return "no such model file in the models folder: ${req.file}"
        val mode = if (req.mode.equals("ram", true)) SplitMode.RAM else SplitMode.EQUAL
        opJob = scope.launch { onAppStart(); runLoad(f.name, req.peers, mode, req.layers, req.force) }
        null
    }

    /** Load again on the phones that are still online (or [peers] when given). Allowed from DEGRADED, FAILED, READY and IDLE with a file chosen. */
    fun replan(peers: List<String> = emptyList()): String? = synchronized(lock) {
        val st = _state.value
        val file = st.file ?: return "nothing to re-plan"
        if (opJob?.isActive == true && st.phase != Phase.LOADING) return "busy: ${st.phase.name}"
        val registry = env.peers()
        val online = registry.filter { !it.away }.map { it.nodeId }
        val asked = peers.map { sel -> registry.firstOrNull { it.nodeId == sel || it.name.equals(sel, true) }?.nodeId ?: sel }
        // the phones of the last plan that are still online; when none is, whichever phones are online now
        val keep = MemCloudLogic.replanPeers(participants.map { it.nodeId }, online, asked).ifEmpty { online }
        if (keep.isEmpty()) return "no other phone is online to re-plan with"
        val old = opJob
        opJob = scope.launch {
            old?.cancel(ReplanCancel())
            teardownSplit()
            runLoad(file, keep, SplitMode.EQUAL, null, forced)
        }
        null
    }

    fun stop() {
        scope.launch {
            try {
                onAppStart()
                val old = synchronized(lock) { opJob }
                old?.cancel()
                degradedJob?.cancel()
                degradedNote = null
                teardownAll()
                _state.update { MemCloudState(phase = Phase.IDLE, baseline = it.baseline, smallModel = it.smallModel, pendingRestore = pendingText(), notices = noticesText(), steps = it.steps.takeLast(30) + if (unconfirmed.isEmpty()) "Stopped; chat and embedding servers restored" else "Stopped; ${unconfirmed.keys.sorted().joinToString { k -> peerName(k) }} did not answer, its servers will be restored when it does", updatedAtMs = env.nowMs()) }
            } finally {
                env.clearActivity(ACTIVITY_OWNER)
            }
        }
    }

    private suspend fun runLoad(file: String, sel: List<String>, mode: SplitMode, manual: List<Int>?, force: Boolean) {
        val t0 = env.nowMs()
        stopWatch()
        cancelGeneration()
        val prev = _state.value
        loadParser = LoadLogParser()
        probeFailures.clear()
        degradedJob?.cancel()
        degradedNote = null
        _state.value = MemCloudState(phase = Phase.PLANNING, file = file, loadStartedAtMs = t0, baseline = prev.baseline, smallModel = prev.smallModel, pendingRestore = pendingText(), notices = noticesText(), steps = listOf("Reading the model header"), updatedAtMs = t0)
        try {
            val info = withContext(Dispatchers.IO) { header(file) }
            val peers = onlinePeers(sel)
            if (peers.isEmpty()) return fail("No other phone is online. The model needs ${gb(info.fileBytes)} and one phone cannot hold it.")
            manual?.let { SplitPlanner.checkLayers(it, info.blockCount, peers.size + 1) }?.let { return fail(it) }
            releaseExcluded(peers)
            step("Asking ${peers.joinToString { it.name }} what it is running")
            val snaps = coroutineScope { peers.map { p -> async { p to runCatching { env.peerModels(p.addr) } } }.awaitAll() }
            snaps.firstOrNull { it.second.isFailure }?.let { (p, r) -> return fail("${p.name} did not answer: ${r.exceptionOrNull()?.message}") }
            for ((p, r) in snaps) freeableMb[p.nodeId] = r.getOrThrow().filterKeys { it == "chat" || it == "embed" }.values.sumOf { if (it.state == "STOPPED") 0L else it.rssMb ?: 0L }

            val sp = SplitPlanner.plan(info, devicesFor(peers), mode, manual)
            val args = SplitPlanner.serverArgs(sp, Ports.RPC, SPLIT_CTX, env.threads())
            if (!SplitPlanner.argsMatchPlan(sp, args)) return fail("Internal error: the llama-server flags do not place the layers as planned: $args")
            plan = sp
            participants = peers
            forced = force
            val misfits = sp.fits().filter { !it.ok }
            val warnings = misfits.map { "${it.device.name} has ${mb(it.haveMb)} free and the planning estimate is ${mb(it.needMb)}" }
            // refuse before anything is stopped or sent; `force` is the explicit way to run the "does not fit" demonstration
            if (misfits.isNotEmpty() && !force) return fail("The plan does not fit: ${warnings.joinToString("; ")}. Nothing was started or stopped. Give a phone fewer layers, add a phone, or start with force to try anyway.")
            val moe = if (info.isMoe) info.moeLayers else 0
            _state.update {
                it.copy(
                    model = info.name, layers = info.blockCount, experts = info.expertCount.takeIf { n -> n > 0 }, expertsUsed = info.expertUsedCount.takeIf { n -> n > 0 },
                    moeLayers = moe, fileBytes = info.fileBytes, ctx = SPLIT_CTX, args = args,
                    shares = shareViews(sp), phones = initialPhones(sp), boundariesPerToken = sp.boundariesPerToken(), warnings = warnings,
                    steps = it.steps + "Planned ${sp.shares.joinToString { s -> "${s.device.name} layers ${s.range}" }}",
                    phase = Phase.STARTING_PEERS, updatedAtMs = env.nowMs(),
                )
            }

            // memory hygiene: peers first, in parallel, then this phone. The leases are renewed from the first command on.
            // The 48-token run of this phone's small chat model (about 4 s) goes on while the lenders prepare, so it is not part of the wait;
            // it uses only this phone's CPU and its own server, and it has finished (or been dropped) before prepSelf stops that server.
            startRenew()
            val prepared = coroutineScope {
                val measuring = async { try { measureSmallModel() } catch (e: CancellationException) { throw e } catch (e: Exception) { /* a missing scale is not a failure */ } }
                val results = peers.map { p -> async { p to runCatching { prepPeer(p, snaps.first { it.first === p }.second.getOrThrow()) } } }.awaitAll()
                if (results.any { it.second.isFailure }) measuring.cancel() // the split cannot go ahead: do not wait for the number
                measuring.join()
                results
            }
            prepared.firstOrNull { it.second.isFailure }?.let { (p, r) -> return fail("${p.name}: ${r.exceptionOrNull()?.message}") }
            prepSelf(file)

            // is every rpc-server reachable from this phone?
            for (p in peers) {
                if (!env.reachable(p.host, Ports.RPC)) return fail("${p.name}'s rpc-server is running but ${p.host}:${Ports.RPC} does not answer from this phone")
            }

            val path = env.modelFile(file)?.absolutePath ?: return fail("the model file $file is gone")
            _state.update { it.copy(phase = Phase.LOADING, loadStartedAtMs = env.nowMs(), steps = it.steps + "Loading: llama-server ${args.joinToString(" ")}", updatedAtMs = env.nowMs()) }
            ownsChat = true
            val server = env.startChat(path, args, timing.loadTimeoutMs)
            startWatch()
            val status = server.await()
            if (_state.value.phase != Phase.LOADING) return // lost a phone while loading: DEGRADED already says so
            if (status.state == ServerState.READY) {
                refreshLive(final = true)
                _state.update {
                    it.copy(phase = Phase.READY, loadMs = status.readyAfterMs, steps = it.steps + "Ready after ${(status.readyAfterMs ?: 0) / 1000} s", updatedAtMs = env.nowMs())
                }
            } else {
                val culprit = attribute(status.reason.orEmpty() + " " + env.chatLog(30).joinToString(" "))
                ownsChat = false
                if (culprit != null) degrade(culprit, status.reason ?: "llama-server stopped") else fail(status.reason ?: "llama-server did not start")
            }
        } catch (e: CancellationException) {
            if (e !is ReplanCancel) withContext(NonCancellable) { teardownAll() }
            throw e
        } catch (e: Exception) {
            fail(e.message ?: e.toString())
        }
    }

    /** FAILED with the reason, and the same teardown as stop: workers stopped, this phone's servers back, activity line cleared. */
    private suspend fun fail(reason: String) {
        _state.update { it.copy(phase = Phase.FAILED, failReason = reason, steps = it.steps + "Failed: $reason", updatedAtMs = env.nowMs()) }
        withContext(NonCancellable) { teardownAll() }
    }

    /** Stops what runs on this phone for the split (watch, renewals, stream, llama-server) and leaves the workers prepared (for a re-plan). */
    private suspend fun teardownSplit() {
        stopWatch()
        cancelGeneration()
        if (ownsChat) { env.stopLocal(Role.CHAT); ownsChat = false }
        withContext(Dispatchers.IO) { delay(timing.settleMs) }
    }

    /**
     * Every way out of a split. [peers]: workers get /rpc/stop (a worker restores its own servers when it stops; one that
     * never got rpc-server running gets its stopped servers back through /models/load). [local]: this phone's chat and
     * embedding servers come back. Idempotent, so a cancelled load and stop() can both run it.
     */
    private suspend fun teardownAll(local: Boolean = true, peers: Boolean = true) {
        stopWatch()
        cancelGeneration()
        if (ownsChat) { env.stopLocal(Role.CHAT); ownsChat = false }
        if (peers) {
            stopRenew()
            // peers of this split, and peers an earlier release could not confirm (a second call picks up what the first left)
            val targets = LinkedHashMap<String, Pair<String, Boolean>>() // node id to (address, rpc-server ran)
            val started = rpcStarted.keys.toSet()
            for (k in touched.keys.toList()) touched.remove(k)?.let { targets[k] = it.addr to (k in started) }
            for (k in unconfirmed.keys.toList()) unconfirmed.remove(k)?.let { if (k !in targets) targets[k] = it to false }
            rpcStarted.clear()
            coroutineScope {
                for ((nodeId, t) in targets) launch { releasePeer(nodeId, t.first, t.second) }
            }
            restore.keys.filter { it != "self" && !unconfirmed.containsKey(it) }.forEach { restore.remove(it) }
        }
        if (local) {
            val items = restore.remove("self") ?: emptyList()
            for (i in items) Role.parse(i.role)?.let { env.restoreLocal(it, i.file) }
            env.clearActivity(ACTIVITY_OWNER)
        }
        persist()
        publishPending()
        startRestoreRetry()
    }

    /**
     * Gives a peer back what the split took. [rpcRan]: its rpc-server ran, so it restores its own servers when told to stop; any other
     * peer is asked what it runs and gets what is still stopped loaded. A peer that does not answer keeps its record ([restore] and
     * [unconfirmed]) and is retried by [startRestoreRetry] instead of being forgotten.
     */
    private suspend fun releasePeer(nodeId: String, addr: String, rpcRan: Boolean) {
        val items = restore[nodeId] ?: emptyList()
        val loan = loans.remove(nodeId)
        val ok = if (rpcRan) stopRpcOn(addr, loan) else confirmPeerRestored(addr, items, loan)
        if (ok || items.isEmpty()) {
            restore.remove(nodeId)
        } else {
            unconfirmed[nodeId] = addr
            step("${peerName(nodeId)} did not answer: its servers are still owed and will be retried")
        }
    }

    /**
     * A re-plan on fewer phones: the lenders of the previous plan that the new one does not use are stopped and their servers restored
     * before the new load starts, so their lease is no longer renewed and their memory is no longer held. A lender that is away is not
     * called (its lease runs out by itself); its record stays and is retried when it answers.
     */
    private suspend fun releaseExcluded(keep: List<PeerInfo>) {
        val keepIds = keep.map { it.nodeId }.toSet()
        val dropped = touched.keys.toList().filter { it !in keepIds }
        if (dropped.isEmpty()) return
        step("Releasing ${dropped.joinToString { peerName(it) }}: not in the new plan")
        val online = env.peers().filter { !it.away }.map { it.nodeId }.toSet()
        coroutineScope {
            for (k in dropped) {
                val p = touched.remove(k) ?: continue
                val rpcRan = rpcStarted.remove(k) != null
                if (k in online) {
                    launch { releasePeer(k, p.addr, rpcRan) }
                } else {
                    loans.remove(k)
                    if ((restore[k] ?: emptyList()).isNotEmpty()) unconfirmed[k] = p.addr else restore.remove(k)
                }
            }
        }
        persist()
        publishPending()
        startRestoreRetry()
    }

    /** [MemCloudState.pendingRestore] and [MemCloudState.notices] from what is still owed. */
    private fun publishPending() {
        _state.update { it.copy(pendingRestore = pendingText(), notices = noticesText()) }
    }

    private fun noticesText(): List<String> =
        unconfirmed.keys.sorted().map { "${peerName(it)} has not got its models back yet" } + listOfNotNull(degradedNote)

    /** Calls every peer that has not confirmed, every [Timing.restoreRetryMs], until all have. Skips a peer that is in a split being started or is away. */
    private fun startRestoreRetry() {
        if (unconfirmed.isEmpty() || retryJob?.isActive == true) return
        retryJob = scope.launch {
            while (isActive && unconfirmed.isNotEmpty()) {
                delay(timing.restoreRetryMs)
                if (synchronized(lock) { opJob?.isActive == true }) continue
                for ((nodeId, addr) in unconfirmed.entries.toList()) {
                    if (touched.containsKey(nodeId) || env.peers().firstOrNull { it.nodeId == nodeId }?.away != false) continue
                    if (confirmPeerRestored(addr, restore[nodeId] ?: emptyList())) {
                        unconfirmed.remove(nodeId)
                        restore.remove(nodeId)
                        step("${peerName(nodeId)} has its models back")
                    }
                }
                persist()
                publishPending()
            }
        }
    }

    // ---------- memory hygiene ----------

    private suspend fun prepPeer(p: PeerInfo, snap: Map<String, RoleSnap>) {
        touched[p.nodeId] = p
        val items = listOf("chat", "embed").mapNotNull { r ->
            snap[r]?.takeIf { it.state != "STOPPED" && it.file != null }?.let { RestoreItem(r, it.file!!, it.rssMb) }
        }
        remember(p.nodeId, items) // on disk before anything is sent to the peer

        // The loan comes first: /rpc/start carries the restore list, and the lease runs from there. A new build stops its own chat and
        // embedding servers as part of accepting, after it has recorded that list. An older build ignores that field and keeps them
        // running: the driver stops them below, when the lease and the list already exist on the peer.
        setPhone(p.nodeId) { it.copy(step = "Starting rpc-server", restore = restoreText(p.nodeId)) }
        step("${p.name}: starting rpc-server on ${p.host}:${Ports.RPC} with the tensor cache")
        val st = startRpcOn(p)
        rpcStarted[p.nodeId] = p // from here the renew job extends its lease
        val token = st.loan
        if (token != null) loans[p.nodeId] = token else loans.remove(p.nodeId)

        val freed = ArrayList<String>()
        val own = st.stoppedOwn
        if (own != null) {
            remember(p.nodeId, own) // what the peer really stopped, which may differ from the snapshot taken a moment ago
            for (m in own) {
                step("${p.name}: stopped ${m.role} server ${m.file}${m.mb?.let { " (${mb(it)})" } ?: ""}")
                freed += "${m.role} ${m.file}${m.mb?.let { ", ${mb(it)}" } ?: ""}"
            }
        } else {
            for (r in listOf("chat", "embed")) {
                val role = snap[r] ?: continue
                if (role.state == "STOPPED") continue
                setPhone(p.nodeId) { it.copy(step = "Stopping its $r server (${role.file}) to free memory") }
                step("${p.name}: stopping $r server ${role.file}${role.rssMb?.let { " (${mb(it)})" } ?: ""}")
                env.peerStopRole(p.addr, r)
                freed += "$r ${role.file}${role.rssMb?.let { ", ${mb(it)}" } ?: ""}"
            }
        }
        setPhone(p.nodeId) { it.copy(step = "rpc-server listening, waiting for weights", freed = freed, restore = restoreText(p.nodeId)) }
    }

    /** What this phone asks of [p]: its planned share and the allowance; null past the memory check (force), so the lender does not refuse what the driver was told to try. */
    private fun needMbFor(p: PeerInfo): Long? =
        if (forced) null else plan?.fits()?.firstOrNull { it.device.nodeId == p.nodeId }?.needMb

    /**
     * /rpc/start on [p]. A lender whose rpc port is still held (the earlier rpc-server has not gone yet; seen once on a phone:
     * "port 50052 is still in use after two retries") is told to stop, given [Timing.portRetryDelayMs], and asked again, up to
     * [Timing.portRetries] times, before the split fails. Any other refusal fails at once.
     */
    private suspend fun startRpcOn(p: PeerInfo): RpcStateDto {
        var retries = 0
        while (true) {
            val failure: String = try {
                val st = env.peerRpcStart(p.addr, RpcStartRequest(port = Ports.RPC, cache = true, bind = p.host, restore = restore[p.nodeId] ?: emptyList(), stopOwn = true, needMb = needMbFor(p)))
                if (st.state == "READY") return st
                "rpc-server is ${st.state}: ${st.reason ?: st.lastLog.lastOrNull().orEmpty()}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: e.toString()
            }
            if (retries >= timing.portRetries || !MemCloudLogic.portInUse(failure)) throw IllegalStateException(failure)
            retries++
            step("${p.name}: port ${Ports.RPC} is still in use; stopping its rpc-server and trying again ($retries of ${timing.portRetries})")
            stopRpcOn(p.addr)
            delay(timing.portRetryDelayMs)
        }
    }

    /** A 48-token answer from the small chat model that is about to be stopped: its tok/s is the scale for the split's number. */
    private suspend fun measureSmallModel() {
        if (ownsChat || env.local(Role.CHAT).state != ServerState.READY) return
        val llm = env.llm
        if (llm.modelName == "FAKE") return
        step("Measuring ${llm.modelName} on this phone alone, for scale")
        val r = withTimeoutOrNull(60_000) {
            runCatching { llm.chat(ChatRequest(listOf(ChatMessage("user", "Explain how a heat pump works.")), maxTokens = 48, temperature = 0.0)) }.getOrNull()
        } ?: return
        val tps = r.stats.decodeTokPerSec
        if (tps > 0) _state.update { it.copy(smallModel = SmallModelView(llm.modelName, tps, r.stats.completionTokens)) }
    }

    private fun prepSelf(splitFile: String) {
        val name = env.selfName()
        val items = ArrayList<RestoreItem>()
        for (role in listOf(Role.CHAT, Role.EMBED)) {
            val st = env.local(role)
            if (st.state == ServerState.STOPPED) continue
            if (role == Role.CHAT && ownsChat) continue
            val file = st.file ?: continue
            items += RestoreItem(role.tag, file, st.rssMb)
            step("$name: stopping ${role.tag} server $file${st.rssMb?.let { " (${mb(it)})" } ?: ""} to make room for $splitFile")
            env.stopLocal(role)
        }
        remember("self", items)
        val own = env.selfId()
        setPhone(own) { it.copy(step = "Stopped its own model servers, loading the split", freed = items.map { i -> "${i.role} ${i.file}${i.mb?.let { m -> ", ${mb(m)}" } ?: ""}" }, restore = restoreText("self")) }
    }

    private fun remember(key: String, items: List<RestoreItem>) {
        val merged = (restore[key] ?: emptyList()).toMutableList()
        for (i in items) if (merged.none { it.role == i.role }) merged += i
        restore[key] = merged
        persist()
        _state.update { it.copy(pendingRestore = pendingText()) }
    }

    private fun pendingText(): List<String> = restore.entries.flatMap { (k, items) ->
        val who = if (k == "self") env.selfName() else env.peers().firstOrNull { it.nodeId == k }?.name ?: k
        items.map { "$who: ${it.role} ${it.file}" }
    }

    private fun restoreText(key: String): List<String> = (restore[key] ?: emptyList()).map { "${it.role} ${it.file}" }

    private fun persist() {
        runCatching { env.save(Persisted(restore.toMap(), touched.mapValues { it.value.addr } + unconfirmed, baselinePending)) }
    }

    // ---------- leases, watching ----------

    /** Extends every worker's lease every [Timing.renewMs]; stops when the split is over (or 60 s after DEGRADED, so a re-plan stays quick). */
    private fun startRenew() {
        if (renewJob?.isActive == true) return
        renewJob = scope.launch {
            var degradedSince = 0L
            while (isActive) {
                delay(timing.renewMs)
                when (_state.value.phase) {
                    Phase.DEGRADED -> {
                        if (degradedSince == 0L) degradedSince = env.nowMs()
                        if (env.nowMs() - degradedSince > timing.degradedRenewMs) return@launch
                    }
                    Phase.IDLE, Phase.FAILED, Phase.BASELINE -> return@launch
                    else -> degradedSince = 0L
                }
                for (p in rpcStarted.values.toList()) launch { runCatching { env.peerRpcRenew(p.addr, loans[p.nodeId]) } }
            }
        }
    }

    private fun stopRenew() {
        renewJob?.cancel()
        renewJob = null
    }

    private fun startWatch() {
        stopWatch()
        watchJob = scope.launch {
            var tick = 0
            while (isActive) {
                delay(timing.watchMs)
                tick++
                val ph = _state.value.phase
                if (!MemCloudLogic.isActive(ph)) continue
                lostPeer()?.let { (p, why) -> degrade(p, why); return@launch }
                if (ph == Phase.READY && env.local(Role.CHAT).state != ServerState.READY && ownsChat) {
                    val culprit = attribute(env.chatLog(30).joinToString(" ")) ?: unresponsivePeer()
                    ownsChat = false
                    if (culprit != null) degrade(culprit, "llama-server stopped") else fail("llama-server stopped")
                    return@launch
                }
                refreshLive(final = false, rpcEvery = if (ph == Phase.LOADING) 1 else 2, tick = tick)
                participants.firstOrNull { (probeFailures[it.nodeId] ?: 0) >= timing.probeFailLimit }?.let { p ->
                    degrade(p, "its rpc-server failed ${timing.probeFailLimit} state checks in a row")
                    return@launch
                }
            }
        }
    }

    private fun stopWatch() {
        watchJob?.cancel()
        watchJob = null
    }

    /** A participant whose heartbeat says away (three missed beats, about 3 s) or that left the registry. */
    private fun lostPeer(): Pair<PeerInfo, String>? {
        val online = env.peers().filter { !it.away }.map { it.nodeId }.toSet()
        val lost = MemCloudLogic.lostParticipants(participants.map { it.nodeId }, online).firstOrNull() ?: return null
        return participants.first { it.nodeId == lost } to "heartbeat lost"
    }

    /** A participant whose rpc-server no longer answers, found by asking each one. */
    private suspend fun unresponsivePeer(): PeerInfo? = coroutineScope {
        participants.map { p -> async { p to runCatching { env.peerRpcState(p.addr) }.getOrNull() } }.awaitAll()
            .firstOrNull { (_, st) -> st == null || st.state != "READY" }?.first
    }

    /** Which peer a llama-server failure is about, judging by the endpoint named in its log. */
    private fun attribute(text: String): PeerInfo? = participants.firstOrNull { text.contains(it.host) }

    private fun degrade(p: PeerInfo, reason: String) = degrade(p.nodeId, p.name, reason)

    /**
     * The answer stopped coming from the split model. A phone that can be named is named (heartbeat gone, or rpc-server not answering);
     * a hung rpc-server answers its state route like a healthy one, so then the split model as a whole is blamed.
     */
    private suspend fun stalled(idleMs: Long, beforeFirstToken: Boolean) {
        val culprit = if (_state.value.phase == Phase.READY) lostPeer()?.first ?: unresponsivePeer() else null
        val name = culprit?.name ?: "the split model"
        _state.update { it.copy(generation = it.generation?.copy(running = false, error = "$name stopped answering")) }
        if (_state.value.phase != Phase.READY) return
        val reason = "no ${if (beforeFirstToken) "first " else ""}token for ${idleMs / 1000} s"
        if (culprit != null) degrade(culprit, reason) else degrade("", name, reason)
    }

    private fun degrade(nodeId: String, name: String, reason: String) {
        val changed = synchronized(lock) {
            val next = MemCloudLogic.degraded(_state.value, nodeId, name, reason, env.nowMs())
            if (next != null) _state.value = next
            next != null
        }
        if (!changed) return
        cancelGeneration()
        if (ownsChat) { env.stopLocal(Role.CHAT); ownsChat = false }
        startDegradedRestore()
    }

    /**
     * DEGRADED leaves this phone's own chat and embedding models stopped so that a re-plan is quick. If nobody acts for
     * [Timing.degradedRestoreMs], the phone gets them back by itself (the same release as Stop) and the state says so; the split stays
     * DEGRADED, and a re-plan after that stops them again. The release is the running operation, so a re-plan has to wait for it.
     */
    private fun startDegradedRestore() {
        degradedJob?.cancel()
        degradedJob = scope.launch {
            delay(timing.degradedRestoreMs)
            val run = synchronized(lock) {
                if (_state.value.phase != Phase.DEGRADED || opJob?.isActive == true) return@synchronized null
                scope.launch(start = CoroutineStart.LAZY) {
                    withContext(NonCancellable) { teardownAll() } // a Stop during the release must not leave a peer half released
                    if (_state.value.phase == Phase.DEGRADED) {
                        val minutes = timing.degradedRestoreMs / 60_000.0
                        val after = if (minutes >= 1) "%.0f minutes".format(minutes) else "${timing.degradedRestoreMs / 1000} seconds"
                        val note = "${env.selfName()}'s own chat and embedding models were restored automatically after $after in DEGRADED with no re-plan."
                        degradedNote = note
                        _state.update { it.copy(notices = noticesText(), steps = (it.steps + note).takeLast(60), updatedAtMs = env.nowMs()) }
                    }
                }.also { opJob = it }
            }
            run?.start()
        }
    }

    /** Refreshes the per-phone numbers (and the log view) in [state]. */
    private suspend fun refreshLive(final: Boolean, rpcEvery: Int = 1, tick: Int = 0) {
        val sp = plan ?: return
        val log = loadParser.also { it.feed(env.chatLog(400)) }.snapshot()
        class Probe(val rpc: RpcStateDto?, val tcpMs: Double?)
        val probes: Map<String, Probe> =
            if (final || tick % rpcEvery == 0) coroutineScope {
                participants.map { p -> async { p.nodeId to Probe(runCatching { env.peerRpcState(p.addr) }.getOrNull(), env.tcpRttMs(p.host, Ports.RPC)) } }.awaitAll().toMap()
            } else emptyMap()
        // a serving split counts state checks that fail or do not say READY; while loading, a busy link may make a check slow, so only the heartbeat decides
        if (_state.value.phase == Phase.READY) {
            for ((nodeId, probe) in probes) {
                if (probe.rpc?.state == "READY") probeFailures.remove(nodeId) else probeFailures.merge(nodeId, 1, Int::plus)
            }
        }
        val reg = env.peers()
        val ownHealth = env.selfHealth()
        val own = env.local(Role.CHAT)
        val ownPeak = env.localPeakMb(Role.CHAT)
        _state.update { cur ->
            val phones = cur.phones.map { v ->
                if (v.isDriver) {
                    v.copy(rssMb = own.rssMb, peakMb = ownPeak, thermalStatus = ownHealth.thermalStatus, ramAvailMb = ownHealth.ramAvailMb,
                        step = when (cur.phase) {
                            Phase.READY -> "Serving; layers ${sp.driver.range}"
                            Phase.LOADING -> "Loading its layers ${sp.driver.range} and feeding the others"
                            else -> v.step
                        })
                } else {
                    val share = sp.shares.firstOrNull { it.device.nodeId == v.nodeId }
                    val entry = reg.firstOrNull { it.nodeId == v.nodeId }
                    val probe = probes[v.nodeId]
                    val st = probe?.rpc
                    val received = st?.anonMb ?: v.receivedMb
                    // planned bytes include the embedding copy a tied output head needs (SplitPlanner), so this never reads "1.6 of 1.0 GB"
                    val frac = if (share != null) MemCloudLogic.loadFraction(received, share.weightBytes) ?: v.loadFraction else v.loadFraction
                    v.copy(
                        rssMb = st?.rssMb ?: v.rssMb, peakMb = st?.peakMb ?: v.peakMb, receivedMb = received, cachedMb = st?.let { it.cachedBytes / (1024 * 1024) } ?: v.cachedMb,
                        loadFraction = if (cur.phase == Phase.READY) 1f else frac,
                        thermalStatus = entry?.thermalStatus ?: st?.thermalStatus ?: v.thermalStatus,
                        ramAvailMb = entry?.ramAvailMb ?: v.ramAvailMb, rttMs = env.heartbeatRttMs(v.nodeId), tcpRttMs = probe?.tcpMs ?: v.tcpRttMs,
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
                tcpRttMs = ExpertMesh.meanRtt(phones.filter { !it.isDriver }.map { it.tcpRttMs }),
                log = LogView(log.offloaded, log.offloadableTotal, log.buffers.map { b -> "${b.name} ${b.modelMiB?.toLong() ?: "-"} MiB" }, log.listening, log.error),
                updatedAtMs = env.nowMs(),
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
        val firstNs = AtomicLong(0)
        val lastNs = AtomicLong(t0) // when anything last arrived from the stream (a token, or the end)
        var chunks = 0
        val text = StringBuilder()
        try {
            val llm = env.llm
            check(llm.modelName != "FAKE") { "the chat server is not running" }
            // The stream is read in its own job so that a stream that goes quiet can be abandoned: a hung rpc-server leaves the heartbeat
            // fine, rpc-server READY, and the socket open for its full timeout. No token for the stall limit means the split has stopped.
            val done = CompletableDeferred<Unit>()
            val reader = scope.launch {
                try {
                    llm.chatStream(ChatRequest(listOf(ChatMessage("user", prompt)), maxTokens = maxTokens, temperature = 0.7)).collect { c ->
                        lastNs.set(System.nanoTime())
                        if (c.delta.isNotEmpty()) {
                            val n = lastNs.get()
                            firstNs.compareAndSet(0, n)
                            val first = firstNs.get()
                            chunks++
                            text.append(c.delta)
                            val rate = if (n > first) (chunks - 1) * 1e9 / (n - first) else null
                            _state.update { it.copy(generation = it.generation?.copy(text = text.toString(), ttftMs = (first - t0) / 1_000_000, decodeTokPerSec = rate, tokens = chunks)) }
                        }
                        c.stats?.let { s ->
                            _state.update { it.copy(generation = it.generation?.copy(decodeTokPerSec = s.decodeTokPerSec.takeIf { v -> v > 0 }, tokens = s.completionTokens)) }
                        }
                    }
                    done.complete(Unit)
                } catch (e: Throwable) {
                    done.completeExceptionally(e)
                }
            }
            var idleMs: Long? = null
            try {
                while (withTimeoutOrNull(timing.stallPollMs) { done.join() } == null) {
                    val limit = if (firstNs.get() == 0L) timing.firstTokenStallMs else timing.tokenStallMs
                    val idle = (System.nanoTime() - lastNs.get()) / 1_000_000
                    if (idle > limit) { idleMs = idle; break }
                }
            } finally {
                reader.cancel()
            }
            if (idleMs != null) {
                stalled(idleMs, firstNs.get() == 0L)
                return
            }
            done.await() // the stream's own failure, if it ended with one, goes to the handlers below
            _state.update { it.copy(generation = it.generation?.copy(running = false)) }
        } catch (e: CancellationException) {
            _state.update { it.copy(generation = it.generation?.copy(running = false)) }
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(generation = it.generation?.copy(running = false, error = e.message ?: e.toString())) }
            // a dead stream usually means a dead phone: do not wait for the next watch tick
            if (_state.value.phase == Phase.READY) {
                val culprit = lostPeer()?.first ?: unresponsivePeer()
                if (culprit != null) degrade(culprit, "stream failed (${e.message ?: e.javaClass.simpleName})")
            }
        }
    }

    // ---------- baseline: the same model on this phone alone ----------

    /** Returns null when the attempt started. It replaces the split server (they share the chat port). [file] defaults to the one of the last split. */
    fun baseline(timeoutSec: Int = 120, file: String? = null): String? = synchronized(lock) {
        val st = _state.value
        val file = file?.let { n -> env.modelFile(n)?.name ?: return "no such model file in the models folder: $n" } ?: st.file ?: return "choose a model first"
        if (opJob?.isActive == true && st.phase != Phase.READY && st.phase != Phase.DEGRADED && st.phase != Phase.FAILED) return "busy: ${st.phase.name}"
        opJob = scope.launch { runBaseline(file, timeoutSec) }
        null
    }

    private suspend fun runBaseline(file: String, timeoutSec: Int) {
        // the workers are not needed for a single-phone attempt: release them (they restore themselves); this phone's own servers stay stopped until the end
        teardownAll(local = false, peers = true)
        val t0 = env.nowMs()
        _state.update { it.copy(phase = Phase.BASELINE, file = file, baseline = BaselineView(true, "LOADING", "Loading $file on this phone with no RPC", 0), steps = it.steps + "Baseline: loading $file on this phone alone (timeout $timeoutSec s)", updatedAtMs = t0) }
        val path = env.modelFile(file)?.absolutePath
        if (path == null) {
            _state.update { it.copy(phase = Phase.IDLE, baseline = BaselineView(false, "FAILED", "The model file $file is gone.", 0)) }
            return
        }
        prepSelf(file)
        val args = listOf("-ngl", "0", "-c", SPLIT_CTX.toString(), "-np", "1", "-fit", "off")
        baselinePending = "$file|$t0|$timeoutSec"
        persist()
        ownsChat = true
        val server = env.startChat(path, args, timeoutSec * 1000L)
        val ticker = scope.launch {
            while (isActive) {
                val rss = env.local(Role.CHAT).rssMb
                _state.update { it.copy(baseline = it.baseline?.copy(elapsedMs = env.nowMs() - t0, detail = "Loading on this phone alone${rss?.let { r -> ", ${mb(r)} resident" } ?: ""}")) }
                delay(1000)
            }
        }
        var result: BaselineView
        try {
            val status = server.await()
            result = when {
                status.state == ServerState.READY -> measureBaselineDecode(t0)
                (status.reason ?: "").contains("killed by signal") -> BaselineView(false, "KILLED", status.reason.orEmpty(), env.nowMs() - t0)
                (status.reason ?: "").startsWith("not ready after") -> BaselineView(false, "TIMEOUT", "Not loaded after $timeoutSec s. ${env.chatLog(1).lastOrNull().orEmpty()}", env.nowMs() - t0)
                else -> {
                    val err = LoadLogParser().also { it.feed(env.chatLog(200)) }.snapshot().error
                    BaselineView(false, "FAILED", err ?: status.reason.orEmpty(), env.nowMs() - t0)
                }
            }
        } finally {
            ticker.cancel()
            withContext(NonCancellable) {
                env.stopLocal(Role.CHAT)
                ownsChat = false
                baselinePending = null
                teardownAll(local = true, peers = false) // this phone's small servers come back
            }
        }
        _state.update { it.copy(phase = Phase.IDLE, baseline = result, phones = emptyList(), steps = it.steps + "Baseline: ${result.outcome} after ${result.elapsedMs / 1000} s", updatedAtMs = env.nowMs()) }
    }

    private suspend fun measureBaselineDecode(t0: Long): BaselineView {
        val loadedMs = env.nowMs() - t0
        _state.update { it.copy(baseline = it.baseline?.copy(outcome = "LOADING", detail = "Loaded in ${loadedMs / 1000} s; generating 32 tokens")) }
        val r = withTimeoutOrNull(120_000) {
            runCatching { env.llm.chat(ChatRequest(listOf(ChatMessage("user", "Explain how a heat pump works.")), maxTokens = 32, temperature = 0.0)) }
        }
        return when {
            r == null -> BaselineView(false, "TIMEOUT", "Loaded in ${loadedMs / 1000} s, but 32 tokens took more than 120 s", env.nowMs() - t0)
            r.isFailure -> BaselineView(false, "FAILED", "Loaded in ${loadedMs / 1000} s, then: ${r.exceptionOrNull()?.message}", env.nowMs() - t0)
            else -> {
                val s = r.getOrThrow().stats
                BaselineView(false, "OK", "Loaded in ${loadedMs / 1000} s; prefill ${"%.1f".format(s.promptTokens * 1000.0 / s.promptMs.coerceAtLeast(1.0))} tok/s", env.nowMs() - t0, s.decodeTokPerSec)
            }
        }
    }

    // ---------- state helpers ----------

    private fun step(text: String) {
        _state.update { it.copy(steps = (it.steps + text).takeLast(60), updatedAtMs = env.nowMs()) }
    }

    private fun setPhone(nodeId: String, f: (PhoneView) -> PhoneView) {
        _state.update { s -> s.copy(phones = s.phones.map { if (it.nodeId == nodeId) f(it) else it }, updatedAtMs = env.nowMs()) }
    }

    private fun shareViews(sp: SplitPlan): List<ShareView> = sp.shares.zip(sp.fits()).map { (s, f) ->
        ShareView(s.device.nodeId, s.device.name, s.device.isDriver, s.firstLayer, s.layerCount, s.range, (s.weightBytes / (1024 * 1024)), f.needMb, f.haveMb, f.ok)
    }

    private fun initialPhones(sp: SplitPlan): List<PhoneView> = sp.shares.map { s ->
        val self = s.device.isDriver
        val peer = if (self) null else env.peers().firstOrNull { it.nodeId == s.device.nodeId }
        val own = if (self) env.selfHealth() else null
        PhoneView(
            nodeId = s.device.nodeId, name = s.device.name, isDriver = self,
            step = if (self) "Waiting for the peers" else "Waiting",
            thermalStatus = own?.thermalStatus ?: peer?.thermalStatus, ramAvailMb = own?.ramAvailMb ?: peer?.ramAvailMb,
            rttMs = if (self) null else env.heartbeatRttMs(s.device.nodeId),
        )
    }

    private fun mb(v: Long) = if (v >= 1024) "%.1f GB".format(v / 1024.0) else "$v MB"
    private fun gb(bytes: Long) = "%.1f GB".format(bytes / 1073741824.0)
}
