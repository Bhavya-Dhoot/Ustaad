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
)

@Serializable
data class PeersResponse(val self: SelfView, val peers: List<PeerView>, val rejected: List<RejectedView>)

@Serializable data class TputRequest(val peerNodeId: String, val megabytes: Int = 100, val link: String? = null, val pings: Int = 200)
@Serializable data class HostRequest(val direct: Boolean = false)
@Serializable data class AddPeerRequest(val ip: String, val port: Int? = null)
@Serializable data class AnnounceRequest(val port: Int)
@Serializable data class DirectJoinRequest(val ssid: String, val pass: String)
@Serializable data class QrSelfTest(val ok: Boolean, val chars: Int, val decodedMatches: Boolean, val error: String? = null)

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
        LinkRuntime.hosting.value != null, DirectLink.state.value, InternetState.validated.value,
    )
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
            is ConnectResult.Untrusted -> call.respond(HttpStatusCode.Forbidden, ErrorBody("peer rejected our signature: different pairing secret"))
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
        val res = LinkRuntime.measure(r.peerNodeId, link, r.megabytes.coerceIn(1, 2048), r.pings.coerceIn(1, 5000))
        call.respond(if (res.error == null) HttpStatusCode.OK else HttpStatusCode.BadGateway, res)
    }

    get("/tput-history") { call.respond(LinkRuntime.results.value) }

    post("/host") {
        val r = body<HostRequest>() ?: HostRequest()
        try {
            call.respond(LinkRuntime.startHosting(r.direct))
        } catch (t: Throwable) {
            call.respond(HttpStatusCode.BadGateway, ErrorBody(t.message ?: t.toString()))
        }
    }
    post("/host/stop") {
        LinkRuntime.stopHosting()
        call.respond(mapOf("ok" to true))
    }

    get("/direct") { call.respond(DirectLink.state.value) }
    post("/direct/host") {
        try {
            LinkRuntime.startHosting(direct = true)
            call.respond(LinkRuntime.currentPayload())
        } catch (t: Throwable) {
            call.respond(HttpStatusCode.BadGateway, ErrorBody(t.message ?: t.toString()))
        }
    }
    post("/direct/join") {
        val r = body<DirectJoinRequest>() ?: return@post bad("body must be {\"ssid\":\"DIRECT-xy-...\",\"pass\":\"...\"}")
        try {
            val owner = DirectLink.join(P2pCreds(r.ssid, r.pass))
            call.respond(mapOf("owner" to owner, "state" to SwarmJson.encodeToString(DirectLink.state.value)))
        } catch (t: Throwable) {
            call.respond(HttpStatusCode.BadGateway, ErrorBody(t.message ?: t.toString()))
        }
    }
    post("/direct/stop") {
        LinkRuntime.stopDirect()
        call.respond(DirectLink.state.value)
    }

    if (BuildConfig.DEBUG) {
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
