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
    /** Striped mode only: streams used, per-path share, CRC failures and undelivered chunks (both must be 0). */
    val streams: Int? = null,
    val paths: List<PathShare>? = null,
    val badChunks: Int? = null,
    val missing: Int? = null,
    val sndBufKb: Int? = null,
    val rcvBufKb: Int? = null,
    val lock: String? = null,
    val foreground: Boolean? = null,
)

@Serializable
data class BleBenchResult(
    val time: String,
    val from: String,
    val to: String,
    val n: Int,
    val bodyBytes: Int,
    val getRtt: RttStats? = null,
    val echoRtt: RttStats? = null,
    val kbitPerSec: Double? = null,
    val error: String? = null,
)

@Serializable
data class RttReport(
    val time: String,
    val fromName: String,
    val toName: String,
    val peerIp: String,
    val link: String,
    val n: Int,
    val gapMs: Long,
    val lock: String? = null,
    val foreground: Boolean? = null,
    val thermalStatus: Int? = null,
    val http: RttStats? = null,
    val tcp: RttStats? = null,
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
    private var hostCreds: P2pCreds?
        get() = DirectLink.hostCreds
        set(v) { DirectLink.hostCreds = v }

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
        AppForeground.start(ctx, scope)
        scope.launch { PeerLinks.peers.collect { WifiLocks.reevaluate() } }
        scope.launch { runCatching { DirectLink.refresh() } }
        DirectLink.watch(scope)
        BleLink.start(ctx, scope)
        val self = AppGraph.registry.self.value
        discovery = Discovery(ctx, scope) { AppGraph.registry.self.value.nodeId }.also { it.start(self.nodeId, self.name, Ports.NODE) }
        scope.launch {
            AppGraph.registry.self.map { it.name }.distinctUntilChanged().drop(1).collect { discovery?.rename(it) }
        }
    }

    /** Receive-buffer size is fixed when the listening socket is made, so a change needs a new one. */
    @Synchronized
    fun restartTputServer() {
        tputServer?.stop()
        runCatching { tputServer = TputServer(Ports.TPUT).also { it.start() } }.onFailure { Log.w(TAG, "tput server: $it") }
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
                        async { runCatching { AppGraph.nodeClient.raw(HttpMethod.Post, p.hostPort, "/link/bye", "{}", 500, pinned = true) } }
                    }.awaitAll()
                }
            }
        }
        BleLink.stop()
        discovery?.stop(); discovery = null
        tputServer?.stop(); tputServer = null
        PeerLinks.stop()
    }

    /** Foreground changed: tell peers (best effort, a backgrounded app is frozen soon after) and, on return, re-join. */
    fun onForegroundChanged(fg: Boolean) {
        scope.launch {
            PeerLinks.peers.value.map { it.hostPort }.distinct().map { hp ->
                launch { runCatching { AppGraph.nodeClient.raw(HttpMethod.Post, hp, "/link/fg", """{"fg":$fg}""", 800, pinned = true) } }
            }
        }
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
    suspend fun startHosting(direct: Boolean, rotate: Boolean = true, freqMhz: Int? = null): PairingPayload {
        if (direct) {
            DirectLink.host(freqMhz = freqMhz)
        } else {
            hostCreds = null
            if (DirectLink.state.value.role == "host") runCatching { DirectLink.stop() }
        }
        if (rotate) AppGraph.setPairingSecret(Pairing.randomSecret())
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
    suspend fun measure(
        peerNodeId: String, link: LinkType? = null, megabytes: Int = 100, pings: Int = 200,
        streams: Int = 0, paths: List<LinkType>? = null,
    ): TputResult =
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
                    // striped mode may use several paths at once; hold the heartbeat on every one of them
                    val used: List<PeerSession> = if (paths != null) {
                        paths.map { l -> PeerLinks.find(peerNodeId, l)?.takeIf { it.status.value.state == LinkState.ONLINE } ?: throw java.io.IOException("no online $l path") }
                    } else listOf(session)
                    used.forEach { it.probing.incrementAndGet() }
                    try {
                        _progress.value = MeasureProgress(st.key, "rtt", 0, 0)
                        // OriginOS freezes a backgrounded app and resets its sockets; a peer that was just thawed
                        // refuses the first connection, so retry the (cheap) RTT stage before the bulk run
                        var rtt: RttStats? = null
                        var attempt = 0
                        while (rtt == null && pings > 0) {
                            try {
                                rtt = client.rtt(pings)
                            } catch (e: java.io.IOException) {
                                if (++attempt >= 4) throw e
                                Thread.sleep(1_500)
                            }
                        }
                        val total = megabytes.toLong() * TputProtocol.MB
                        _progress.value = MeasureProgress(st.key, "throughput", 0, total)
                        var lastPublished = 0L
                        val onProgress = { done: Long ->
                            if (done - lastPublished >= TputProtocol.MB) {
                                lastPublished = done
                                _progress.value = MeasureProgress(st.key, "throughput", done, total)
                            }
                        }
                        val h = AppGraph.health.state.value
                        if (streams <= 0 && paths == null) {
                            val t = client.throughput(megabytes, onProgress)
                            base = base.copy(
                                bytes = t.bytes, receiverSeconds = t.receiverSeconds, mbpsReceiver = t.mbpsReceiver,
                                senderSeconds = t.senderSeconds, mbpsSender = t.mbpsSender,
                            )
                        } else {
                            val sts = used.map { it.status.value }
                            val sender = StripedSender(
                                sts.map { ps -> { LinkRoute.openSocket(ctx, ps.ip, ps.port + 1) } }, sts.map { it.link.name },
                            )
                            val t = sender.send(megabytes, maxOf(streams, 1, sts.size), onProgress = onProgress)
                            base = base.copy(
                                bytes = t.bytes, receiverSeconds = t.receiverSeconds, mbpsReceiver = t.mbpsReceiver,
                                senderSeconds = t.senderSeconds, mbpsSender = t.mbpsSender, streams = maxOf(streams, 1, sts.size),
                                paths = t.perPath, badChunks = t.badChunks, missing = t.missing,
                                link = if (sts.size > 1) sts.joinToString("+") { it.link.name } else base.link,
                            )
                        }
                        base = base.copy(
                            rtt = rtt, local = LinkInfo.describe(ctx, local), thermalStatus = h.thermalStatus, batteryPct = h.batteryPct,
                            sndBufKb = LinkTuning.sndBufKb, rcvBufKb = LinkTuning.rcvBufKb, lock = WifiLocks.held.value.name,
                            foreground = AppForeground.foreground.value,
                        )
                        base
                    } finally {
                        used.forEach { it.probing.decrementAndGet() }
                    }
                }
            } catch (t: Throwable) {
                base.copy(error = t.message ?: t::class.simpleName, local = LinkInfo.describe(ctx, local))
            } finally {
                _progress.value = null
            }
            if (result.error == null) {
                record(result)
                result.mbpsReceiver?.let { PeerLinks.noteMbps(st.key, it) }
            }
            result
        }

    /**
     * Round trip of small requests on one path, measured two ways in the same minute: [n] signed HTTP GET /health
     * calls (what a Node API request costs) every [gapMs] ms (0 = back to back, 1000 = the heartbeat rhythm), and
     * [tcpPings] one-byte TCP ping-pongs (the raw link). The heartbeat on that path is held while it runs.
     */
    suspend fun rttProbe(peerNodeId: String, link: LinkType?, n: Int, gapMs: Long, tcpPings: Int): RttReport = probeLock.withLock {
        val ctx = AppGraph.appContext
        val self = AppGraph.registry.self.value
        val session = PeerLinks.find(peerNodeId, link)
            ?: return@withLock RttReport(OffsetDateTime.now().withNano(0).toString(), self.name, "?", "", link?.name ?: "?", n, gapMs, error = "unknown peer or link")
        val st = session.status.value
        val h = AppGraph.health.state.value
        var report = RttReport(
            OffsetDateTime.now().withNano(0).toString(), self.name, st.name, st.ip, st.link.name, n, gapMs,
            lock = WifiLocks.held.value.name, foreground = AppForeground.foreground.value, thermalStatus = h.thermalStatus,
        )
        try {
            withContext(Dispatchers.IO) {
                PeerLinks.pausing(session) {
                    if (n > 0) {
                        val samples = LongArray(n)
                        for (i in 0 until n) {
                            val t0 = System.nanoTime()
                            AppGraph.nodeClient.getPinned<com.swarmos.app.core.NodeHealth>(st.hostPort, "/health", 3_000)
                            samples[i] = System.nanoTime() - t0
                            if (gapMs > 0) kotlinx.coroutines.delay(gapMs)
                        }
                        report = report.copy(http = rttStats(samples))
                    }
                    if (tcpPings > 0) {
                        val client = TputClient { LinkRoute.openSocket(ctx, st.ip, st.port + 1) }
                        report = report.copy(tcp = client.rtt(tcpPings))
                    }
                }
            }
        } catch (t: Throwable) {
            report = report.copy(error = t.message ?: t::class.simpleName)
        }
        report
    }

    /**
     * Bluetooth control path numbers: [n] signed GET /health round trips, then [n] signed POST /echo with a
     * [bodyBytes]-byte body (request plus response cross the radio), timed one after the other.
     */
    suspend fun bleBench(peerNodeId: String, n: Int, bodyBytes: Int): BleBenchResult = probeLock.withLock {
        val self = AppGraph.registry.self.value
        val peer = PeerLinks.pathsOf(peerNodeId).firstOrNull()?.name ?: peerNodeId
        var res = BleBenchResult(OffsetDateTime.now().withNano(0).toString(), self.name, peer, n, bodyBytes)
        try {
            withContext(Dispatchers.IO) {
                val get = LongArray(n)
                for (i in 0 until n) { val t0 = System.nanoTime(); BleLink.get(peerNodeId, "/v1/health", true, 5_000); get[i] = System.nanoTime() - t0 }
                res = res.copy(getRtt = rttStats(get))
                val body = "x".repeat(bodyBytes)
                val echo = LongArray(n)
                val t00 = System.nanoTime()
                for (i in 0 until n) { val t0 = System.nanoTime(); BleLink.post(peerNodeId, "/v1/link/echo", body, 8_000); echo[i] = System.nanoTime() - t0 }
                val secs = (System.nanoTime() - t00) / 1e9
                // each echo moves the body out and back (JSON-wrapped, a few bytes more)
                res = res.copy(echoRtt = rttStats(echo), kbitPerSec = n * bodyBytes * 2 * 8 / secs / 1000)
            }
        } catch (t: Throwable) {
            res = res.copy(error = t.message ?: t::class.simpleName)
        }
        res
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
