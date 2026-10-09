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

    internal fun update(f: (PeerStatus) -> PeerStatus) = _status.update(f)
}

sealed interface ConnectResult {
    data class Ok(val status: PeerStatus) : ConnectResult
    data class Untrusted(val nodeId: String?, val name: String?) : ConnectResult
    data class Failed(val reason: String) : ConnectResult
}

data class Rejected(val ip: String, val port: Int, val nodeId: String?, val name: String?, val atMs: Long)

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
    const val HEARTBEAT_TIMEOUT_MS = 1000L
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

    /** Remember an endpoint (from NSD, a QR or "add by IP"); the retry loop connects it until it succeeds. */
    fun remember(host: String, port: Int) {
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
        return all.firstOrNull { it.status.value.link == LinkType.DIRECT && it.status.value.state == LinkState.ONLINE }
            ?: all.firstOrNull { it.status.value.state == LinkState.ONLINE }
            ?: all.firstOrNull()
    }

    /** Hello, signed health, then a heartbeat session. [announce] asks the peer to add us back (reverse registration). */
    suspend fun connect(host: String, port: Int, announce: Boolean = true): ConnectResult {
        val hostPort = "$host:$port"
        val client = AppGraph.nodeClient
        val info = try {
            client.get<NodeInfo>(hostPort, "/hello", HELLO_TIMEOUT_MS)
        } catch (t: Throwable) {
            currentCoroutineContext().ensureActive()
            return ConnectResult.Failed("hello: ${t.message ?: t::class.simpleName}")
        }
        if (info.nodeId == selfId) return ConnectResult.Failed("that is this phone")
        val health = try {
            client.get<NodeHealth>(hostPort, "/health", HELLO_TIMEOUT_MS)
        } catch (e: NodeHttpException) {
            if (e.status == 401) {
                _rejected.update { (it.filterNot { r -> r.ip == host && r.port == port } + Rejected(host, port, info.nodeId, info.name, System.currentTimeMillis())).takeLast(8) }
                AppGraph.audit.append("peer.rejected", JsonObject(mapOf("node" to JsonPrimitive(info.nodeId), "ip" to JsonPrimitive(host))))
                return ConnectResult.Untrusted(info.nodeId, info.name)
            }
            return ConnectResult.Failed("health: $e")
        } catch (t: Throwable) {
            currentCoroutineContext().ensureActive()
            return ConnectResult.Failed("health: ${t.message ?: t::class.simpleName}")
        }
        _rejected.update { it.filterNot { r -> r.ip == host && r.port == port } }

        val link = LinkRoute.typeFor(host)
        val key = "${info.nodeId}/${link.name}"
        val now = System.currentTimeMillis()
        val fresh = PeerStatus(info.nodeId, info.name, host, port, link, LinkState.ONLINE, null, now, 0, health, info)
        var created = false
        val session: PeerSession
        synchronized(this) {
            val old = sessions[key]
            if (old == null) {
                created = true
                session = PeerSession(fresh).also { s ->
                    s.tracker.onSuccess(0.0, now)
                    sessions[key] = s
                    s.job = scope.launch { heartbeatLoop(s) }
                }
            } else {
                session = old
                old.update { it.copy(name = info.name, ip = host, port = port, info = info, health = health) }
            }
        }
        remember(host, port)
        publish()
        syncRegistry(info.nodeId)
        if (created) {
            AppGraph.audit.append("peer.trusted", JsonObject(mapOf("node" to JsonPrimitive(info.nodeId), "ip" to JsonPrimitive(host), "link" to JsonPrimitive(link.name))))
            Log.i(TAG, "trusted ${info.name} ${info.nodeId} at $hostPort via $link")
            if (announce) scope.launch { announceTo(session) }
        }
        return ConnectResult.Ok(session.status.value)
    }

    private suspend fun announceTo(s: PeerSession) {
        runCatching {
            AppGraph.nodeClient.raw(HttpMethod.Post, s.status.value.hostPort, "/link/announce", """{"port":${Ports.NODE}}""", 3_000)
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
        val h: NodeHealth? = try {
            AppGraph.nodeClient.get<NodeHealth>(st.hostPort, "/health", HEARTBEAT_TIMEOUT_MS)
        } catch (t: Throwable) {
            currentCoroutineContext().ensureActive()
            null
        }
        val rtt = (System.nanoTime() - t0) / 1e6
        val now = System.currentTimeMillis()
        val changed = if (h != null) s.tracker.onSuccess(rtt, now) else s.tracker.onMiss()
        s.update {
            it.copy(
                state = s.tracker.state, rttMs = s.tracker.rttMs, misses = s.tracker.misses,
                lastSeenMs = if (h != null) now else it.lastSeenMs, health = h ?: it.health,
            )
        }
        publish()
        if (h != null) AppGraph.registry.updateHealth(h, now)
        if (changed) {
            syncRegistry(st.nodeId)
            Log.i(TAG, "${st.name} ${st.link} is now ${s.tracker.state}")
            AppGraph.audit.append("peer.${s.tracker.state.name.lowercase()}", JsonObject(mapOf("node" to JsonPrimitive(st.nodeId), "link" to JsonPrimitive(st.link.name))))
        }
        // refresh the peer's NodeInfo (model names change) every ~10 s
        if (h != null && n % 10 == 5) {
            runCatching { AppGraph.nodeClient.get<NodeInfo>(st.hostPort, "/hello", HEARTBEAT_TIMEOUT_MS) }.getOrNull()?.let { info ->
                s.update { it.copy(name = info.name, info = info) }
                syncRegistry(st.nodeId)
            }
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

    private fun publish() {
        _peers.value = sessions.values.map { it.status.value }.sortedWith(compareBy({ it.name }, { it.link }))
    }

    /** Keep AppGraph.registry in step: one entry per node, address = how we reach it, away only when every link is away. */
    private fun syncRegistry(nodeId: String) {
        val group = sessions.values.map { it.status.value }.filter { it.nodeId == nodeId }
        if (group.isEmpty()) return
        val online = group.filter { it.state == LinkState.ONLINE }
        val reg = AppGraph.registry
        if (online.isEmpty()) {
            reg.markAway(nodeId)
            return
        }
        val p = online.firstOrNull { it.link == LinkType.DIRECT } ?: online.first()
        val ipField = if (p.port == Ports.NODE) p.ip else "${p.ip}:${p.port}"
        val info = p.info.copy(ip = ipField)
        val cur = reg.peers.value.firstOrNull { it.info.nodeId == nodeId }
        if (cur == null || cur.info != info || cur.away) reg.add(info)
        p.health?.let { reg.updateHealth(it) }
    }
}
