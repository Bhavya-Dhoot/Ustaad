package com.swarmos.app.link

import android.util.Log
import com.swarmos.app.AppGraph
import com.swarmos.app.core.Ports
import com.swarmos.app.core.SwarmJson
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.io.File
import java.time.OffsetDateTime

/** What the QR / deep link currently carries while this phone hosts. */
data class HostSession(val payload: PairingPayload, val direct: Boolean, val startedMs: Long)

/** Live progress of a probe for the Link screen. */
data class MeasureProgress(val peerKey: String, val phase: String, val doneBytes: Long, val totalBytes: Long)

@Serializable
data class TputResult(
    val time: String,
    val ts: Long,
    val fromNodeId: String,
    val fromName: String,
    val toNodeId: String,
    val toName: String,
    val peerIp: String,
    val link: String,
    val megabytes: Int,
    val bytes: Long? = null,
    val receiverSeconds: Double? = null,
    val mbpsReceiver: Double? = null,
    val senderSeconds: Double? = null,
    val mbpsSender: Double? = null,
    val rtt: RttStats? = null,
    val local: LinkDesc? = null,
    val thermalStatus: Int? = null,
    val batteryPct: Int? = null,
    val error: String? = null,
)

/**
 * Starts and stops everything SwarmLink runs in the background (probe server, NSD, heartbeat sessions) and holds
 * the pairing state. [NodeServer] calls [start] and [stop], so the foreground service owns the lifetime.
 */
object LinkRuntime {
    private const val TAG = "SwarmLink"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var discovery: Discovery? = null
    private var tputServer: TputServer? = null
    @Volatile private var started = false
    @Volatile private var hostCreds: P2pCreds? = null

    private val _hosting = MutableStateFlow<HostSession?>(null)
    val hosting: StateFlow<HostSession?> = _hosting.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    /** Last pairing / direct-link outcome, for the Link screen. */
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _results = MutableStateFlow<List<TputResult>>(emptyList())
    /** Newest first; mirrors filesDir/tput.jsonl. */
    val results: StateFlow<List<TputResult>> = _results.asStateFlow()

    private val _progress = MutableStateFlow<MeasureProgress?>(null)
    val progress: StateFlow<MeasureProgress?> = _progress.asStateFlow()
    private val probeLock = Mutex()

    val internetValidated: StateFlow<Boolean> get() = InternetState.validated

    private val tputFile get() = File(AppGraph.appContext.filesDir, "tput.jsonl")

    @Synchronized
    fun start() {
        if (started) return
        started = true
        val ctx = AppGraph.appContext
        InternetState.start(ctx)
        loadResults()
        runCatching { tputServer = TputServer(Ports.TPUT).also { it.start() } }
            .onFailure { Log.w(TAG, "tput server: $it") }
        PeerLinks.start()
        scope.launch { runCatching { DirectLink.refresh() }.getOrNull()?.let { hostCreds = it } }
        val self = AppGraph.registry.self.value
        discovery = Discovery(ctx, scope) { AppGraph.registry.self.value.nodeId }.also { it.start(self.nodeId, self.name, Ports.NODE) }
        scope.launch {
            AppGraph.registry.self.map { it.name }.distinctUntilChanged().drop(1).collect { discovery?.rename(it) }
        }
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        // tell peers we are leaving so they mark us away at once; capped so service shutdown stays quick
        runCatching {
            runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(600) {
                    PeerLinks.peers.value.map { p ->
                        async { runCatching { AppGraph.nodeClient.raw(HttpMethod.Post, p.hostPort, "/link/bye", "{}", 500) } }
                    }.awaitAll()
                }
            }
        }
        discovery?.stop(); discovery = null
        tputServer?.stop(); tputServer = null
        PeerLinks.stop()
    }

    // ---------------------------------------------------------------- pairing

    /** The payload a joiner needs right now: current secret, addresses, and the Wi-Fi Direct group if one is up. */
    fun currentPayload(): PairingPayload {
        val self = AppGraph.registry.self.value
        val ips = LinkRoute.advertisable().map { it.address }.distinct()
        val d = DirectLink.state.value
        val p2p = if (d.role == "host" && d.phase == "up") hostCreds else null
        return PairingPayload(self.name, self.nodeId, ips, Ports.NODE, AppGraph.pairingSecret, p2p)
    }

    /**
     * Host a swarm: rotate to a fresh random secret and publish the payload. [direct] first creates the
     * 5 GHz Wi-Fi Direct group; "current Wi-Fi" mode tears any group down and advertises LAN addresses only.
     */
    suspend fun startHosting(direct: Boolean): PairingPayload {
        if (direct) {
            hostCreds = DirectLink.host()
        } else {
            hostCreds = null
            if (DirectLink.state.value.role == "host") runCatching { DirectLink.stop() }
        }
        AppGraph.setPairingSecret(Pairing.randomSecret())
        val payload = currentPayload()
        _hosting.value = HostSession(payload, direct, System.currentTimeMillis())
        _message.value = if (direct) "Hosting over Wi-Fi Direct ${payload.p2p?.ssid}" else "Hosting on the current Wi-Fi"
        return payload
    }

    fun stopHosting() {
        _hosting.value = null
    }

    suspend fun stopDirect() {
        hostCreds = null
        runCatching { DirectLink.stop() }
        _hosting.value = _hosting.value?.let { it.copy(payload = currentPayload(), direct = false) }
    }

    /** Adopt a scanned / injected payload: take its secret, reach the host over every address, join its group. */
    suspend fun adopt(p: PairingPayload): String {
        val self = AppGraph.registry.self.value
        if (p.nodeId == self.nodeId) return "That code is this phone's own"
        if (p.secret != AppGraph.pairingSecret) AppGraph.setPairingSecret(p.secret)
        p.ips.forEach { PeerLinks.remember(it, p.port) }
        PeerLinks.retryAllNow()
        val notes = mutableListOf<String>()
        coroutineScope {
            val direct = p.p2p?.let { creds ->
                async { runCatching { DirectLink.join(creds) } }
            }
            val lan = p.ips.map { ip -> async { ip to PeerLinks.connect(ip, p.port) } }
            lan.awaitAll().forEach { (ip, r) ->
                when (r) {
                    is ConnectResult.Ok -> notes += "${r.status.name} via ${r.status.link} $ip"
                    is ConnectResult.Untrusted -> notes += "$ip rejected our signature"
                    is ConnectResult.Failed -> {}
                }
            }
            direct?.await()?.let { res ->
                res.onSuccess { owner ->
                    PeerLinks.remember(owner, p.port)
                    when (val r = PeerLinks.connect(owner, p.port)) {
                        is ConnectResult.Ok -> notes += "${r.status.name} via DIRECT $owner"
                        is ConnectResult.Untrusted -> notes += "$owner rejected our signature"
                        is ConnectResult.Failed -> notes += "joined group but ${r.reason}"
                    }
                }.onFailure { notes += "Wi-Fi Direct: ${it.message}" }
            }
        }
        val msg = "Paired with ${p.name}: " + (if (notes.isEmpty()) "secret adopted, host not reachable yet (will keep trying)" else notes.joinToString("; "))
        _message.value = msg
        AppGraph.audit.append("pairing.adopt")
        return msg
    }

    /** `swarmos://pair?d=...`, a raw payload or JSON. Runs in the background; the outcome lands in [message]. */
    fun handleDeepLink(text: String) {
        val p = Pairing.parse(text)
        if (p == null) {
            _message.value = "Not a SwarmOS pairing code"
            return
        }
        _message.value = "Pairing with ${p.name}..."
        scope.launch { runCatching { adopt(p) }.onFailure { _message.value = "Pairing failed: ${it.message}" } }
    }

    suspend fun addPeer(host: String, port: Int): ConnectResult {
        PeerLinks.remember(host, port)
        val r = PeerLinks.connect(host, port)
        _message.value = when (r) {
            is ConnectResult.Ok -> "Added ${r.status.name} ($host)"
            is ConnectResult.Untrusted -> "${r.name ?: host} answered but rejected our signature (different secret)"
            is ConnectResult.Failed -> "No peer at $host:$port (${r.reason})"
        }
        return r
    }

    fun setMessage(m: String?) { _message.value = m }

    // ---------------------------------------------------------------- probe

    /**
     * 200 one-byte ping-pongs then [megabytes] MiB to the peer's probe port over [link] (default: the preferred
     * link). The result is appended to filesDir/tput.jsonl and published in [results].
     */
    suspend fun measure(peerNodeId: String, link: LinkType? = null, megabytes: Int = 100, pings: Int = 200): TputResult =
        probeLock.withLock {
            val ctx = AppGraph.appContext
            val self = AppGraph.registry.self.value
            val session = PeerLinks.find(peerNodeId, link)
                ?: return@withLock failed(self.nodeId, self.name, peerNodeId, "?", "", link?.name ?: "?", megabytes, "unknown peer or link")
            val st = session.status.value
            val probePort = st.port + 1
            val local = LinkRoute.findLocalFor(st.ip)
            val client = TputClient { LinkRoute.openSocket(ctx, st.ip, probePort) }
            var base = TputResult(
                time = OffsetDateTime.now().withNano(0).toString(), ts = System.currentTimeMillis(),
                fromNodeId = self.nodeId, fromName = self.name, toNodeId = st.nodeId, toName = st.name,
                peerIp = st.ip, link = st.link.name, megabytes = megabytes,
            )
            val result = try {
                withContext(Dispatchers.IO) {
                    PeerLinks.pausing(session) {
                        _progress.value = MeasureProgress(st.key, "rtt", 0, 0)
                        val rtt = client.rtt(pings)
                        val total = megabytes.toLong() * TputProtocol.MB
                        _progress.value = MeasureProgress(st.key, "throughput", 0, total)
                        var lastPublished = 0L
                        val t = client.throughput(megabytes) { done ->
                            if (done - lastPublished >= TputProtocol.MB) {
                                lastPublished = done
                                _progress.value = MeasureProgress(st.key, "throughput", done, total)
                            }
                        }
                        val h = AppGraph.health.state.value
                        base = base.copy(
                            bytes = t.bytes, receiverSeconds = t.receiverSeconds, mbpsReceiver = t.mbpsReceiver,
                            senderSeconds = t.senderSeconds, mbpsSender = t.mbpsSender, rtt = rtt,
                            local = LinkInfo.describe(ctx, local), thermalStatus = h.thermalStatus, batteryPct = h.batteryPct,
                        )
                        base
                    }
                }
            } catch (t: Throwable) {
                base.copy(error = t.message ?: t::class.simpleName, local = LinkInfo.describe(ctx, local))
            } finally {
                _progress.value = null
            }
            if (result.error == null) record(result)
            result
        }

    private fun failed(fromId: String, fromName: String, toId: String, toName: String, ip: String, link: String, mb: Int, err: String) =
        TputResult(
            time = OffsetDateTime.now().withNano(0).toString(), ts = System.currentTimeMillis(),
            fromNodeId = fromId, fromName = fromName, toNodeId = toId, toName = toName, peerIp = ip, link = link,
            megabytes = mb, error = err,
        )

    private fun record(r: TputResult) {
        runCatching { tputFile.appendText(SwarmJson.encodeToString(r) + "\n") }
        _results.update { (listOf(r) + it).take(50) }
    }

    private fun loadResults() {
        val f = tputFile
        if (!f.exists()) return
        val rows = runCatching {
            f.readLines().filter { it.isNotBlank() }.takeLast(50).mapNotNull { runCatching { SwarmJson.decodeFromString<TputResult>(it) }.getOrNull() }
        }.getOrDefault(emptyList())
        _results.value = rows.reversed()
    }
}
