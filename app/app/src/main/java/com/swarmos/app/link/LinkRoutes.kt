package com.swarmos.app.link

import com.swarmos.app.AppGraph
import com.swarmos.app.BuildConfig
import com.swarmos.app.core.Ports
import com.swarmos.app.core.Signer
import com.swarmos.app.core.SwarmJson
import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.RoutingContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class PeerView(
    val nodeId: String,
    val name: String,
    val ip: String,
    val port: Int,
    val link: String,
    val state: String,
    val rttMs: Double?,
    val lastSeenMs: Long,
    val ageMs: Long,
    val misses: Int,
    val thermalStatus: Int?,
    val batteryPct: Int?,
    val linkStandard: String?,
    val linkSpeedMbps: Int?,
    val beats: Int = 0,
    val missesTotal: Int = 0,
    val awayCount: Int = 0,
    val maxGapMs: Long = 0,
)

@Serializable
data class RejectedView(val ip: String, val port: Int, val nodeId: String?, val name: String?)

@Serializable
data class SelfView(
    val nodeId: String,
    val name: String,
    val ip: String,
    val port: Int,
    val secretFingerprint: String,
    val hosting: Boolean,
    val direct: DirectState,
    val internetValidated: Boolean,
    /** Name of the host whose pairing code is waiting for the person to confirm, if any. */
    val pendingPairing: String? = null,
)

@Serializable
data class PeersResponse(val self: SelfView, val peers: List<PeerView>, val rejected: List<RejectedView>)

@Serializable data class TputRequest(
    val peerNodeId: String, val megabytes: Int = 100, val link: String? = null, val pings: Int = 200,
    /** 0 = the raw single-stream probe; 1..16 = striped chunks with CRC over that many TCP streams. */
    val streams: Int = 0,
    /** Striped only: stripe over these paths at once, for example ["LAN","DIRECT"]. */
    val paths: List<String>? = null,
)
@Serializable data class HostRequest(val direct: Boolean = false, val rotate: Boolean = true, val freqMhz: Int? = null)
@Serializable data class SleepResult(val slept: Long, val executions: Int)
@Serializable data class FaultRequest(val blockedFrom: List<String> = emptyList())
@Serializable data class FailoverRequest(val peerNodeId: String, val n: Int = 200, val intervalMs: Long = 100, val timeoutMs: Long = 10_000)
@Serializable data class BleBenchRequest(val peerNodeId: String, val n: Int = 50, val bodyBytes: Int = 900)
@Serializable data class AddPeerRequest(val ip: String, val port: Int? = null)
@Serializable data class AnnounceRequest(val port: Int)
@Serializable data class DirectJoinRequest(val ssid: String, val pass: String)
@Serializable data class QrSelfTest(val ok: Boolean, val chars: Int, val decodedMatches: Boolean, val error: String? = null)

@Serializable data class FgRequest(val fg: Boolean)
@Serializable data class TuningRequest(val lock: String? = null, val sndBufKb: Int? = null, val rcvBufKb: Int? = null)
@Serializable data class RttRequest(val peerNodeId: String, val link: String? = null, val n: Int = 100, val gapMs: Long = 0, val tcpPings: Int = 200)
@Serializable data class SleepRequest(val ms: Long)

@Serializable
data class PathView(
    val key: String, val link: String, val ip: String, val port: Int, val state: String,
    val rttMs: Double?, val ewmaRttMs: Double?, val loss: Int, val lastMbps: Double?,
    val misses: Int, val missesTotal: Int, val awayCount: Int, val maxGapMs: Long, val awayReason: String?,
)

@Serializable
data class PeerPaths(
    val nodeId: String, val name: String, val peerForeground: Boolean?,
    /** Path key the policy picks for small requests and for bulk transfers. */
    val small: String?, val bulk: String?, val paths: List<PathView>,
)

fun peerPaths(): List<PeerPaths> = PeerLinks.peers.value.groupBy { it.nodeId }.map { (id, all) ->
    PeerPaths(
        id, all.first().name, all.firstNotNullOfOrNull { it.peerForeground },
        PeerLinks.pathPolicy.order(all, Use.SMALL).firstOrNull()?.key, PeerLinks.pathPolicy.order(all, Use.BULK).firstOrNull()?.key,
        all.sortedBy { it.link }.map {
            PathView(it.key, it.link.name, it.ip, it.port, it.state.name.lowercase(), it.rttMs, it.ewmaRttMs, it.lossCount, it.lastMbps,
                it.misses, it.missesTotal, it.awayCount, it.maxGapMs, it.awayReason)
        },
    )
}

/** Counts how often the debug /sleep handler really ran (for the "executed once" check). */
/**
 * Debug-only fault injection for failover tests that must not touch a radio: while [blocked] addresses are set, requests
 * that arrive from them are held and answered 20 s later (a silent path: the caller times out, its heartbeat on that path
 * misses and the path goes away). Requests from other addresses, Bluetooth included, are untouched.
 */
object FaultInjection {
    @Volatile var blocked: List<String> = emptyList()
    fun blocks(remote: String): Boolean = blocked.any { remote == it }
}

object SleepCounter {
    private val n = java.util.concurrent.atomic.AtomicInteger()
    fun next() = n.incrementAndGet()
    fun value() = n.get()
}

private val routeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

fun peerViews(now: Long = System.currentTimeMillis()): List<PeerView> = PeerLinks.peers.value.map { p ->
    PeerView(
        p.nodeId, p.name, p.ip, p.port, p.link.name, p.state.name.lowercase(), p.rttMs, p.lastSeenMs,
        if (p.lastSeenMs > 0) now - p.lastSeenMs else -1, p.misses,
        p.health?.thermalStatus, p.health?.batteryPct, p.health?.linkStandard, p.health?.linkSpeedMbps,
        p.beats, p.missesTotal, p.awayCount, p.maxGapMs,
    )
}

fun selfView(): SelfView {
    val self = AppGraph.selfInfo()
    return SelfView(
        self.nodeId, self.name, self.ip, Ports.NODE, Pairing.fingerprint(AppGraph.pairingSecret),
        LinkRuntime.hosting.value != null, DirectLink.state.value, InternetState.validated.value, LinkRuntime.pendingPairing.value?.name,
    )
}

/** Routes that rotate the secret, make or join groups or change tuning are for debug builds and adb tooling only. */
private suspend fun RoutingContext.debugOnly(): Boolean {
    if (BuildConfig.DEBUG) return false
    call.respond(HttpStatusCode.NotFound, ErrorBody("not available in this build"))
    return true
}

private suspend fun RoutingContext.bad(msg: String, status: HttpStatusCode = HttpStatusCode.BadRequest) {
    call.respond(status, com.swarmos.app.link.ErrorBody(msg))
}

private suspend inline fun <reified T> RoutingContext.body(): T? =
    runCatching { SwarmJson.decodeFromString<T>(call.receiveText()) }.getOrNull()

/** Routes mounted under /v1/link/. All are signed except the debug-only GET /pairing-payload. */
fun Route.linkRoutes() {
    get("/peers") {
        call.respond(PeersResponse(selfView(), peerViews(), PeerLinks.rejected.value.map { RejectedView(it.ip, it.port, it.nodeId, it.name) }))
    }

    post("/peers/add") {
        val r = body<AddPeerRequest>() ?: return@post bad("body must be {\"ip\":\"x.x.x.x\",\"port\":8797?}")
        when (val res = LinkRuntime.addPeer(r.ip, r.port ?: Ports.NODE)) {
            is ConnectResult.Ok -> call.respond(peerViews().filter { it.nodeId == res.status.nodeId })
            is ConnectResult.Untrusted -> call.respond(HttpStatusCode.Forbidden, ErrorBody("peer not trusted: ${res.reason ?: "different pairing secret"}"))
            is ConnectResult.Failed -> call.respond(HttpStatusCode.BadGateway, ErrorBody(res.reason))
        }
    }

    // peer tells us it reached us; we verify it back on the address its request came from
    post("/announce") {
        val r = body<AnnounceRequest>() ?: return@post bad("body must be {\"port\":8797}")
        val from = call.request.origin.remoteAddress
        PeerLinks.remember(from, r.port)
        routeScope.launch { PeerLinks.connect(from, r.port, announce = false) }
        call.respond(mapOf("ok" to true))
    }

    post("/bye") {
        call.request.header(Signer.HEADER_NODE)?.let { PeerLinks.byeFrom(it) }
        call.respond(mapOf("ok" to true))
    }

    post("/tput") {
        val r = body<TputRequest>() ?: return@post bad("body must be {\"peerNodeId\":\"...\",\"megabytes\":100}")
        val link = r.link?.let { l -> LinkType.entries.firstOrNull { it.name.equals(l, ignoreCase = true) } ?: return@post bad("link must be LAN or DIRECT") }
        val paths = r.paths?.map { l -> LinkType.entries.firstOrNull { it.name.equals(l, ignoreCase = true) && it != LinkType.BT } ?: return@post bad("paths must be LAN and/or DIRECT") }
        val res = LinkRuntime.measure(r.peerNodeId, link, r.megabytes.coerceIn(1, 2048), r.pings.coerceIn(0, 5000), r.streams.coerceIn(0, 16), paths)
        call.respond(if (res.error == null) HttpStatusCode.OK else HttpStatusCode.BadGateway, res)
    }

    get("/tput-history") { call.respond(LinkRuntime.results.value) }

    get("/paths") { call.respond(peerPaths()) }

    get("/ble") { call.respond(BleLink.state.value) }

    // sealed with the pairing secret, so only a paired phone can read the group credentials (also over Bluetooth)
    get("/p2p-creds") {
        val c = DirectLink.hostCreds
        val d = DirectLink.state.value
        if (c == null || d.role != "host" || d.phase != "up") return@get call.respond(HttpStatusCode.NotFound, ErrorBody("not hosting a Wi-Fi Direct group"))
        val plain = SwarmJson.encodeToString(P2pCreds.serializer(), c).toByteArray()
        call.respond(CredsBlob(SecretBox.seal(AppGraph.pairingSecret, plain, AppGraph.registry.self.value.nodeId)))
    }

    // returns what it was sent: used to measure request throughput over any path, Bluetooth included
    post("/echo") { call.respond(mapOf("echo" to call.receiveText())) }

    post("/fault") {
        if (debugOnly()) return@post
        val r = body<FaultRequest>() ?: return@post bad("body must be {\"blockedFrom\":[\"192.168.49.1\"]} (empty list clears)")
        FaultInjection.blocked = r.blockedFrom
        call.respond(r)
    }

    post("/failover-test") {
        if (debugOnly()) return@post
        val r = body<FailoverRequest>() ?: return@post bad("body must be {\"peerNodeId\":\"...\",\"n\":200,\"intervalMs\":100}")
        call.respond(LinkRuntime.failoverTest(r.peerNodeId, r.n.coerceIn(1, 2000), r.intervalMs.coerceIn(0, 5000), r.timeoutMs.coerceIn(500, 60_000)))
    }

    post("/ble-bench") {
        val r = body<BleBenchRequest>() ?: return@post bad("body must be {\"peerNodeId\":\"...\",\"n\":50,\"bodyBytes\":900}")
        val res = LinkRuntime.bleBench(r.peerNodeId, r.n.coerceIn(1, 500), r.bodyBytes.coerceIn(0, BT_MAX_BODY - 120))
        call.respond(if (res.error == null) HttpStatusCode.OK else HttpStatusCode.BadGateway, res)
    }

    post("/fg") {
        val r = body<FgRequest>() ?: return@post bad("body must be {\"fg\":true|false}")
        call.request.header(Signer.HEADER_NODE)?.let { PeerLinks.noteForeground(it, r.fg) }
        call.respond(mapOf("ok" to true))
    }

    get("/tuning") { call.respond(LinkTuning.view()) }
    post("/tuning") {
        if (debugOnly()) return@post
        val r = body<TuningRequest>() ?: return@post bad("body must be {\"lock\":\"none|high_perf|low_latency|auto\",\"sndBufKb\":0,\"rcvBufKb\":0}")
        r.lock?.let { l ->
            when (l.lowercase()) {
                "auto" -> WifiLocks.setOverride(null)
                "none" -> WifiLocks.setOverride(LockMode.NONE)
                "high_perf" -> WifiLocks.setOverride(LockMode.HIGH_PERF)
                "low_latency" -> WifiLocks.setOverride(LockMode.LOW_LATENCY)
                else -> return@post bad("lock must be none, high_perf, low_latency or auto")
            }
        }
        r.sndBufKb?.let { LinkTuning.sndBufKb = it.coerceIn(0, 16384) }
        r.rcvBufKb?.let { LinkTuning.rcvBufKb = it.coerceIn(0, 16384); LinkRuntime.restartTputServer() }
        call.respond(LinkTuning.view())
    }

    post("/rtt") {
        val r = body<RttRequest>() ?: return@post bad("body must be {\"peerNodeId\":\"...\",\"n\":100,\"gapMs\":0}")
        val link = r.link?.let { l -> LinkType.entries.firstOrNull { it.name.equals(l, ignoreCase = true) } ?: return@post bad("link must be LAN, DIRECT or BT") }
        val res = LinkRuntime.rttProbe(r.peerNodeId, link, r.n.coerceIn(0, 5000), r.gapMs.coerceIn(0, 10_000), r.tcpPings.coerceIn(0, 5000))
        call.respond(if (res.error == null) HttpStatusCode.OK else HttpStatusCode.BadGateway, res)
    }

    post("/host") {
        if (debugOnly()) return@post
        val r = body<HostRequest>() ?: HostRequest()
        try {
            call.respond(LinkRuntime.startHosting(r.direct, r.rotate, r.freqMhz))
        } catch (t: Throwable) {
            call.respond(HttpStatusCode.BadGateway, ErrorBody(t.message ?: t.toString()))
        }
    }
    post("/host/stop") {
        if (debugOnly()) return@post
        LinkRuntime.stopHosting()
        call.respond(mapOf("ok" to true))
    }

    get("/direct") { call.respond(DirectLink.state.value) }
    post("/direct/host") {
        if (debugOnly()) return@post
        val r = body<HostRequest>() ?: HostRequest(direct = true)
        try {
            LinkRuntime.startHosting(direct = true, rotate = r.rotate, freqMhz = r.freqMhz)
            call.respond(LinkRuntime.currentPayload())
        } catch (t: Throwable) {
            call.respond(HttpStatusCode.BadGateway, ErrorBody(t.message ?: t.toString()))
        }
    }
    post("/direct/join") {
        if (debugOnly()) return@post
        val r = body<DirectJoinRequest>() ?: return@post bad("body must be {\"ssid\":\"DIRECT-xy-...\",\"pass\":\"...\"}")
        try {
            val owner = DirectLink.join(P2pCreds(r.ssid, r.pass))
            call.respond(mapOf("owner" to owner, "state" to SwarmJson.encodeToString(DirectLink.state.value)))
        } catch (t: Throwable) {
            call.respond(HttpStatusCode.BadGateway, ErrorBody(t.message ?: t.toString()))
        }
    }
    post("/direct/stop") {
        if (debugOnly()) return@post
        LinkRuntime.stopDirect()
        call.respond(DirectLink.state.value)
    }

    if (BuildConfig.DEBUG) {
        // holds the request open (to prove a long call survives the connection idle timeout)
        post("/sleep") {
            val r = body<SleepRequest>() ?: return@post bad("body must be {\"ms\":120000}")
            kotlinx.coroutines.delay(r.ms.coerceIn(0, 600_000))
            call.respond(SleepResult(r.ms, SleepCounter.next()))
        }

        get("/sleep-count") { call.respond(mapOf("executions" to SleepCounter.value())) }

        // Unsigned on purpose (see NodeServer): lets adb tooling read the payload and inject it as a deep link.
        get("/pairing-payload") { call.respond(LinkRuntime.currentPayload()) }

        // Encode the current payload as a QR with ZXing and decode it again with ML Kit (the camera path's scanner).
        get("/qr-selftest") {
            val text = Pairing.toLink(LinkRuntime.currentPayload())
            val res = try {
                val decoded = QrCode.decode(QrCode.bitmap(text))
                QrSelfTest(decoded != null, text.length, decoded == text)
            } catch (t: Throwable) {
                QrSelfTest(false, text.length, false, t.toString())
            }
            call.respond(res)
        }
    }
}
