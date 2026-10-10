package com.swarmos.app.link

import android.util.Log
import com.swarmos.app.AppGraph
import com.swarmos.app.core.NodeHealth
import com.swarmos.app.core.NodeInfo
import com.swarmos.app.core.Ports
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** One heartbeat session: a peer reached over one link (LAN or DIRECT). */
class PeerSession(initial: PeerStatus) {
    private val _status = MutableStateFlow(initial)
    /** Per-peer observable state: health, rttMs, lastSeenMs, state. */
    val status: StateFlow<PeerStatus> = _status.asStateFlow()

    internal val tracker = HeartbeatTracker()
    @PublishedApi internal val probing = AtomicInteger(0)
    internal var job: Job? = null
    internal val listing = PathListing()

    internal fun update(f: (PeerStatus) -> PeerStatus) = _status.update(f)
}

sealed interface ConnectResult {
    data class Ok(val status: PeerStatus) : ConnectResult
    data class Untrusted(val nodeId: String?, val name: String?, val reason: String? = null) : ConnectResult
    data class Failed(val reason: String) : ConnectResult
}

data class Rejected(val ip: String, val port: Int, val nodeId: String?, val name: String?, val atMs: Long, val reason: String? = null)

/**
 * Trust handshake, 1 Hz heartbeat and the bridge into [AppGraph.registry].
 *
 * connect(): GET /v1/hello (unsigned) then a signed GET /v1/health. A 200 means the peer accepted our signature
 * (same secret) so it is trusted, gets a heartbeat session and is added to the registry. The registry address of a
 * peer is the one this node uses to reach it (DIRECT preferred when online), not the peer's own wlan address.
 */
object PeerLinks {
    private const val TAG = "SwarmLink"
    const val HEARTBEAT_MS = 1000L
    /** Wi-Fi heartbeat request timeout; idle-LAN stalls of 1035 to 2080 ms were measured, so 1 s marked healthy phones away. */
    const val HEARTBEAT_TIMEOUT_MS = 2500L
    /** Bluetooth heartbeat request timeout (unchanged: the radio path answers in tens of ms or not at all). */
    const val HEARTBEAT_BT_TIMEOUT_MS = 3000L
    const val HELLO_TIMEOUT_MS = 2500L

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<String, PeerSession>()
    private val _peers = MutableStateFlow<List<PeerStatus>>(emptyList())

    /** All trusted peer links, sorted by name then link. */
    val peers: StateFlow<List<PeerStatus>> = _peers.asStateFlow()

    private val _rejected = MutableStateFlow<List<Rejected>>(emptyList())
    /** Endpoints that answered /hello but rejected our signature (different pairing secret). */
    val rejected: StateFlow<List<Rejected>> = _rejected.asStateFlow()

    private class Endpoint(val host: String, val port: Int) {
        @Volatile var failures = 0
        @Volatile var lastTryMs = 0L
    }

    private val endpoints = ConcurrentHashMap<String, Endpoint>()
    private var retryJob: Job? = null

    private val selfId get() = AppGraph.registry.self.value.nodeId

    /** Which path carries traffic to a peer that has several. Replace to change transport choice everywhere. */
    @Volatile var pathPolicy: PathPolicy = MeasuredPolicy()

    private val inflight = ConcurrentHashMap<String, MutableSet<Job>>()

    /** Carries small signed requests when no Wi-Fi path is up (set by [BleLink]). */
    @Volatile var btTransport: SmallTransport? = null

    /** Online and offline paths of one node, as the UI and /v1/link/paths show them. */
    fun pathsOf(nodeId: String): List<PeerStatus> = sessions.values.map { it.status.value }.filter { it.nodeId == nodeId }

    /** Failover order for a request to [nodeId]: the policy's choice first, then the other usable paths. */
    fun candidates(nodeId: String, use: Use = Use.SMALL): List<PeerStatus> {
        val all = pathsOf(nodeId)
        val first = pathPolicy.choose(all, use)?.takeIf { it.state == LinkState.ONLINE } ?: return emptyList()
        return listOf(first) + pathPolicy.order(all, use).filter { it.key != first.key }
    }

    /** Register a running request on a path; if the path is declared away and another is up, the job is cancelled. */
    fun trackInflight(key: String, job: Job): () -> Unit {
        val set = inflight.computeIfAbsent(key) { java.util.Collections.newSetFromMap(ConcurrentHashMap()) }
        set.add(job)
        return { set.remove(job) }
    }

    /** A request had to leave [key] (transport error or path lost). */
    fun noteFailure(key: String) {
        sessions[key]?.update { it.copy(lossCount = it.lossCount + 1) }
        publish()
    }

    /** Remember the measured bulk speed of a path so BULK requests prefer the faster one. */
    fun noteMbps(key: String, mbps: Double) {
        sessions[key]?.update { it.copy(lastMbps = mbps) }
        publish()
    }

    @Synchronized
    fun start() {
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        if (retryJob?.isActive == true) return
        retryJob = scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                for (ep in endpoints.values) {
                    val has = sessions.values.any { it.status.value.ip == ep.host && it.status.value.port == ep.port }
                    if (has) { ep.failures = 0; continue }
                    val interval = if (ep.failures < 10) 3_000L else 30_000L
                    if (now - ep.lastTryMs < interval) continue
                    ep.lastTryMs = now
                    launch {
                        val r = connect(ep.host, ep.port)
                        if (r is ConnectResult.Ok) ep.failures = 0 else ep.failures++
                    }
                }
                delay(1_000)
            }
        }
    }

    @Synchronized
    fun stop() {
        retryJob?.cancel()
        retryJob = null
        sessions.values.forEach { it.job?.cancel() }
        sessions.clear()
        endpoints.clear()
        _peers.value = emptyList()
        scope.coroutineContext[Job]?.cancel()
    }

    private fun recordRejected(host: String, port: Int, nodeId: String?, name: String?, reason: String) {
        _rejected.update { (it.filterNot { r -> r.ip == host && r.port == port } + Rejected(host, port, nodeId, name, System.currentTimeMillis(), reason)).takeLast(8) }
        AppGraph.audit.append("peer.rejected", JsonObject(mapOf("node" to JsonPrimitive(nodeId ?: "?"), "ip" to JsonPrimitive(host), "reason" to JsonPrimitive(reason))))
    }

    /** 401 from a peer: a clock more than 30 s off gives the same status as a wrong secret, so say which one it is. */
    internal fun reasonOf401(e: NodeHttpException, nowMs: Long = System.currentTimeMillis()): String {
        val skew = runCatching { com.swarmos.app.core.SwarmJson.decodeFromString<SkewBody>(e.body) }.getOrNull()
        return if (skew != null && skew.error == "clock skew") "clock off by ${kotlin.math.abs(skew.serverTimeMs - nowMs) / 1000} s" else "different pairing secret"
    }

    /** Remember an endpoint (from NSD, a QR or "add by IP"); the retry loop connects it until it succeeds. Only local addresses are accepted. */
    fun remember(host: String, port: Int) {
        if (!LinkRoute.isLocalAddress(host) || port !in 1..65535) return
        val k = "$host:$port"
        endpoints.compute(k) { _, old -> old?.also { it.failures = 0; it.lastTryMs = 0 } ?: Endpoint(host, port) }
    }

    /** The pairing secret changed: let every remembered endpoint try again right away. */
    fun retryAllNow() {
        endpoints.values.forEach { it.failures = 0; it.lastTryMs = 0 }
    }

    fun session(key: String): PeerSession? = sessions[key]

    /** The session for a node: the given link, else DIRECT when online, else any. */
    fun find(nodeId: String, link: LinkType? = null): PeerSession? {
        val all = sessions.values.filter { it.status.value.nodeId == nodeId }
        if (link != null) return all.firstOrNull { it.status.value.link == link }
        val key = pathPolicy.choose(all.map { it.status.value })?.key
        return all.firstOrNull { it.status.value.key == key }
    }

    /** Hello, signed health, then a heartbeat session. [announce] asks the peer to add us back (reverse registration). */
    suspend fun connect(host: String, port: Int, announce: Boolean = true): ConnectResult {
        if (!LinkRoute.isLocalAddress(host) || port !in 1..65535) return ConnectResult.Failed("$host:$port is not a local network address")
        val hostPort = "$host:$port"
        val client = AppGraph.nodeClient
        val info = try {
            client.getPinned<NodeInfo>(hostPort, "/hello", HELLO_TIMEOUT_MS)
        } catch (u: UntrustedResponse) {
            val why = "answered but did not prove the pairing secret"
            recordRejected(host, port, null, null, why)
            return ConnectResult.Untrusted(null, null, why)
        } catch (t: Throwable) {
            currentCoroutineContext().ensureActive()
            return ConnectResult.Failed("hello: ${t.message ?: t::class.simpleName}")
        }
        if (info.nodeId == selfId) return ConnectResult.Failed("that is this phone")
        val health = try {
            client.getPinned<NodeHealth>(hostPort, "/health", HELLO_TIMEOUT_MS)
        } catch (e: NodeHttpException) {
            if (e.status == 401) {
                val why = reasonOf401(e)
                recordRejected(host, port, info.nodeId, info.name, why)
                return ConnectResult.Untrusted(info.nodeId, info.name, why)
            }
            return ConnectResult.Failed("health: $e")
        } catch (u: UntrustedResponse) {
            recordRejected(host, port, info.nodeId, info.name, "answer not authenticated")
            return ConnectResult.Untrusted(info.nodeId, info.name, "answer not authenticated")
        } catch (t: Throwable) {
            currentCoroutineContext().ensureActive()
            return ConnectResult.Failed("health: ${t.message ?: t::class.simpleName}")
        }
        if (health.nodeId != info.nodeId) return ConnectResult.Failed("hello and health at $host came from different phones")
        _rejected.update { it.filterNot { r -> r.ip == host && r.port == port } }

        remember(host, port)
        val (session, created) = upsert(info, health, host, port, LinkRoute.typeFor(host))
        if (created && announce) scope.launch { announceTo(session) }
        return ConnectResult.Ok(session.status.value)
    }

    /**
     * Trust handshake over an open Bluetooth channel (same two steps as [connect]): the peer is reachable by
     * radio only, so the path is BT with the address "bt:<node id>". [BleLink] calls this once both sides know who they are.
     */
    suspend fun connectBt(nodeId: String, nameHint: String?): ConnectResult {
        val info = try {
            json.decodeFromString<NodeInfo>(BleLink.get(nodeId, "/v1/hello", signed = false, timeoutMs = 4_000))
        } catch (t: Throwable) {
            currentCoroutineContext().ensureActive()
            return ConnectResult.Failed("hello over bluetooth: ${t.message ?: t::class.simpleName}")
        }
        if (info.nodeId == selfId) return ConnectResult.Failed("that is this phone")
        val health = try {
            BleLink.health(nodeId, 4_000)
        } catch (e: NodeHttpException) {
            if (e.status == 401) return ConnectResult.Untrusted(info.nodeId, info.name)
            return ConnectResult.Failed("health over bluetooth: $e")
        } catch (t: Throwable) {
            currentCoroutineContext().ensureActive()
            return ConnectResult.Failed("health over bluetooth: ${t.message ?: t::class.simpleName}")
        }
        if (info.nodeId != nodeId || health.nodeId != nodeId) return ConnectResult.Failed("the Bluetooth channel for $nodeId answers as ${info.nodeId}")
        val (session, _) = upsert(info, health, "bt:$nodeId", 0, LinkType.BT)
        return ConnectResult.Ok(session.status.value)
    }

    private val json get() = com.swarmos.app.core.SwarmJson

    /** Create the heartbeat session for (node, link) or refresh it; returns it and whether it is new. */
    private fun upsert(info: NodeInfo, health: NodeHealth, host: String, port: Int, link: LinkType): Pair<PeerSession, Boolean> {
        val key = "${info.nodeId}/${link.name}"
        val now = System.currentTimeMillis()
        val fresh = PeerStatus(info.nodeId, info.name, host, port, link, LinkState.ONLINE, null, now, 0, health, info, peerForeground = peerFg[info.nodeId])
        var created = false
        var backOnline = false
        var before: PeerStatus? = null
        val session: PeerSession
        synchronized(this) {
            val old = sessions[key]
            before = old?.status?.value
            if (old == null) {
                created = true
                session = PeerSession(fresh).also { s ->
                    s.tracker.onSuccess(0.0, now)
                    sessions[key] = s
                    s.job = scope.launch { heartbeatLoop(s) }
                }
            } else {
                session = old
                val moved = old.status.value.ip != host || old.status.value.port != port
                backOnline = old.tracker.onInbound(now) // the hello and signed health that got us here prove the peer is there
                old.update {
                    it.copy(
                        name = info.name, ip = host, port = port, info = info, health = health,
                        state = old.tracker.state, misses = 0, lastSeenMs = now, awayReason = if (backOnline) null else it.awayReason,
                    )
                }
                if (moved) Log.i(TAG, "${info.name} ${link.name} moved from ${before?.ip} to $host:$port")
            }
        }
        publish()
        syncRegistry(info.nodeId)
        if (created) {
            AppGraph.audit.append("peer.trusted", JsonObject(mapOf("node" to JsonPrimitive(info.nodeId), "ip" to JsonPrimitive(host), "link" to JsonPrimitive(link.name))))
            Log.i(TAG, "trusted ${info.name} ${info.nodeId} at $host:$port via $link")
        } else if (backOnline) {
            AppGraph.audit.append("peer.online", JsonObject(mapOf("node" to JsonPrimitive(info.nodeId), "link" to JsonPrimitive(link.name), "by" to JsonPrimitive("connect"))))
        }
        return session to created
    }

    private suspend fun announceTo(s: PeerSession) {
        runCatching {
            AppGraph.nodeClient.raw(HttpMethod.Post, s.status.value.hostPort, "/link/announce", """{"port":${Ports.NODE}}""", 3_000, pinned = true)
        }
    }

    private suspend fun heartbeatLoop(s: PeerSession) {
        var next = System.nanoTime()
        var n = 0
        while (currentCoroutineContext().isActive) {
            if (s.probing.get() == 0) beat(s, n++)
            next += HEARTBEAT_MS * 1_000_000
            val wait = (next - System.nanoTime()) / 1_000_000
            if (wait > 0) delay(wait) else next = System.nanoTime()
        }
    }

    private suspend fun beat(s: PeerSession, n: Int) {
        val st = s.status.value
        val t0 = System.nanoTime()
        var failReason: String? = null
        val answer: NodeHealth? = try {
            if (st.link == LinkType.BT) BleLink.health(st.nodeId, HEARTBEAT_BT_TIMEOUT_MS)
            else AppGraph.nodeClient.getPinned<NodeHealth>(st.hostPort, "/health", HEARTBEAT_TIMEOUT_MS)
        } catch (e: NodeHttpException) {
            if (e.status == 401) failReason = reasonOf401(e)
            null
        } catch (e: UntrustedResponse) {
            failReason = "answer not authenticated (different pairing secret?)"
            null
        } catch (t: Throwable) {
            currentCoroutineContext().ensureActive()
            null
        }
        // the answer must come from the phone this path was made for: another phone at the same address is not a heartbeat
        val wrongNode = answer != null && answer.nodeId != st.nodeId
        if (wrongNode) failReason = "another phone (${answer.nodeId}) answers at ${st.hostPort}"
        val h: NodeHealth? = if (wrongNode) null else answer
        val rtt = (System.nanoTime() - t0) / 1e6
        val now = System.currentTimeMillis()
        val changed = when {
            wrongNode -> s.tracker.onWrongNode()
            h != null -> s.tracker.onSuccess(rtt, now)
            else -> s.tracker.onMiss()
        }
        s.update {
            it.copy(
                state = s.tracker.state, rttMs = s.tracker.rttMs, misses = s.tracker.misses,
                lastSeenMs = if (h != null) now else it.lastSeenMs, health = h ?: it.health,
                beats = s.tracker.beats, missesTotal = s.tracker.missesTotal, awayCount = s.tracker.awayCount, maxGapMs = s.tracker.maxGapMs,
                ewmaRttMs = if (h != null) ewma(it.ewmaRttMs, rtt) else it.ewmaRttMs,
                awayReason = if (h != null) null else failReason ?: it.awayReason,
            )
        }
        if (changed && s.tracker.state == LinkState.AWAY && pathsOf(st.nodeId).any { it.key != st.key && it.state == LinkState.ONLINE }) {
            // a path went down and another is up: requests in flight on it move to the other path (same request id)
            inflight[st.key]?.toList()?.forEach { it.cancel(PathLostCancellation(st.key)) }
        }
        if (changed) {
            val reason = failReason ?: if (s.tracker.state == LinkState.AWAY && peerFg[st.nodeId] == false) "left the foreground" else null
            s.update { it.copy(awayReason = reason) }
        }
        publish()
        if (h != null) AppGraph.registry.updateHealth(h, now)
        if (!changed && n % 5 == 0) syncRegistry(st.nodeId) // follow policy switches caused by RTT drift
        if (changed) {
            syncRegistry(st.nodeId)
            Log.i(TAG, "${st.name} ${st.link} is now ${s.tracker.state}")
            AppGraph.audit.append("peer.${s.tracker.state.name.lowercase()}", JsonObject(mapOf("node" to JsonPrimitive(st.nodeId), "link" to JsonPrimitive(st.link.name))))
        }
        // refresh the peer's NodeInfo (model names change) every ~10 s
        if (h != null && n % 10 == 5 && st.link != LinkType.BT) {
            runCatching { AppGraph.nodeClient.getPinned<NodeInfo>(st.hostPort, "/hello", HEARTBEAT_TIMEOUT_MS) }.getOrNull()?.let { info ->
                s.update { it.copy(name = info.name, info = info) }
                syncRegistry(st.nodeId)
            }
        }
        if (wrongNode && st.link != LinkType.BT) scope.launch { replaceSession(s, st) }
    }

    /**
     * Another phone answers at the address this session was made for (the phone came back under a new node id, or its
     * address went to another phone). Meet the phone that is there now; once it has its own session this one is dropped,
     * so the old id is not listed as online beside the new one. A phone that merely is away is never touched by this.
     */
    private suspend fun replaceSession(old: PeerSession, st: PeerStatus) {
        val r = connect(st.ip, st.port)
        if (r is ConnectResult.Ok && r.status.nodeId != st.nodeId && sessions[st.key] === old) {
            Log.i(TAG, "${st.name} (${st.nodeId}) is replaced by ${r.status.name} (${r.status.nodeId}) at ${st.ip}")
            forget(st.key)
        }
    }

    private val peerFg = ConcurrentHashMap<String, Boolean>()

    /** Record whether a peer's app is in front (response header X-Swarm-Fg on every call, or POST /link/fg). */
    fun noteForeground(nodeId: String, fg: Boolean) {
        peerFg[nodeId] = fg
        var changed = false
        sessions.values.filter { it.status.value.nodeId == nodeId && it.status.value.peerForeground != fg }.forEach { s ->
            s.update { it.copy(peerForeground = fg, awayReason = if (fg) null else it.awayReason) }
            changed = true
        }
        if (changed) publish()
    }

    /** The node that owns the path at [hostPort] ("ip:port" or bare ip), if any. */
    fun nodeAt(host: String): String? {
        if (host.startsWith("bt:")) return host.removePrefix("bt:").substringBefore(':')
        val ip = host.substringBefore(':')
        return sessions.values.map { it.status.value }.firstOrNull { it.ip == ip }?.nodeId
    }

    /**
     * A verified signed request from [nodeId] reached our node server from [remote]. It proves the peer is there, so it
     * counts as a heartbeat for the path it came over: three slow answers in a row no longer mark a healthy phone away.
     */
    fun noteInbound(nodeId: String, remote: String, path: String = "") {
        if (nodeId == selfId || path == "/v1/link/bye") return // the bye request must not undo itself
        val mine = sessions.values.map { it.status.value }.filter { it.nodeId == nodeId }
        if (mine.isEmpty()) return
        when (val a = Inbound.decide(mine, remote, Ports.NODE)) {
            is InboundAction.Beat -> sessions[a.key]?.let { applyInbound(it, nodeId) }
            is InboundAction.Reconnect -> if (reconnectGate.tryAcquire(nodeId)) {
                // the phone got a new address: do not wait for discovery to find it (up to a minute), go to where it is
                Log.i(TAG, "$nodeId reached us from ${a.host}, which none of its paths uses; reconnecting there")
                scope.launch { runCatching { connect(a.host, a.port, announce = false) } }
            }
            InboundAction.Ignore -> {}
        }
    }

    private val reconnectGate = RateGate(Inbound.RECONNECT_GAP_MS)

    private fun applyInbound(s: PeerSession, nodeId: String) {
        val now = System.currentTimeMillis()
        val before = s.status.value
        val changed = s.tracker.onInbound(now)
        if (!changed && before.misses == 0 && now - before.lastSeenMs < 1_000) return // cheap on a busy link
        s.update {
            it.copy(
                state = s.tracker.state, misses = 0, lastSeenMs = now, maxGapMs = s.tracker.maxGapMs,
                awayReason = if (changed) null else it.awayReason,
            )
        }
        if (changed) {
            publish()
            syncRegistry(nodeId)
            Log.i(TAG, "${before.name} ${before.link} is online again (a request from it arrived)")
            AppGraph.audit.append("peer.online", JsonObject(mapOf("node" to JsonPrimitive(nodeId), "link" to JsonPrimitive(before.link.name), "by" to JsonPrimitive("inbound"))))
        }
    }

    /** Peer said POST /link/bye: mark its sessions away at once instead of waiting for three misses. */
    fun byeFrom(nodeId: String) {
        sessions.values.filter { it.status.value.nodeId == nodeId }.forEach { s ->
            s.tracker.onBye()
            s.update { it.copy(state = LinkState.AWAY, rttMs = null, misses = s.tracker.misses) }
        }
        publish()
        syncRegistry(nodeId)
    }

    /** Hold the heartbeat while a probe saturates the link, so a busy link is not read as an absent peer. */
    inline fun <T> pausing(s: PeerSession, block: () -> T): T {
        s.probing.incrementAndGet()
        try {
            return block()
        } finally {
            s.probing.decrementAndGet()
        }
    }

    fun forget(key: String) {
        sessions.remove(key)?.let { it.job?.cancel() }
        publish()
    }

    /** Exponentially weighted moving average, alpha 0.3; the first sample seeds it. */
    internal fun ewma(prev: Double?, sample: Double, alpha: Double = 0.3): Double =
        if (prev == null) sample else prev + alpha * (sample - prev)

    private fun listed(st: PeerStatus, now: Long): Boolean = sessions[st.key]?.listing?.update(st.link, st.state, now) ?: true

    private fun publish() {
        val now = System.currentTimeMillis()
        _peers.value = sessions.values.map { it.status.value }.filter { listed(it, now) }.sortedWith(compareBy({ it.name }, { it.link }))
    }

    /** Keep AppGraph.registry in step: one entry per node, address = how we reach it, away only when every link is away. */
    private fun syncRegistry(nodeId: String) {
        syncRegistryEntry(nodeId)
        // one address is one phone: when two entries claim it, keep the one that answers (and stop beating the other's path)
        val gone = AppGraph.registry.dropAddressConflicts()
        if (gone.isNotEmpty()) {
            val taken = AppGraph.registry.peers.value.map { it.info.ip.substringBefore(':') }.toSet()
            gone.forEach { id ->
                Log.i(TAG, "$id shared an address with a phone that answers; dropped from the registry")
                sessions.values.map { it.status.value }.filter { it.nodeId == id && it.link != LinkType.BT && it.ip in taken }.forEach { forget(it.key) }
            }
        }
    }

    private fun syncRegistryEntry(nodeId: String) {
        val group = sessions.values.map { it.status.value }.filter { it.nodeId == nodeId }
        if (group.isEmpty()) return
        val online = group.filter { it.state == LinkState.ONLINE }
        val reg = AppGraph.registry
        if (online.isEmpty()) {
            reg.markAway(nodeId)
            return
        }
        val p = pathPolicy.choose(online) ?: online.first()
        val ipField = if (p.link == LinkType.BT || p.port == Ports.NODE) p.ip else "${p.ip}:${p.port}"
        val info = p.info.copy(ip = ipField)
        val cur = reg.peers.value.firstOrNull { it.info.nodeId == nodeId }
        if (cur == null || cur.info != info || cur.away) reg.add(info)
        p.health?.let { reg.updateHealth(it) }
    }
}
