package com.swarmos.app.link

import com.swarmos.app.AppGraph
import com.swarmos.app.BuildConfig
import com.swarmos.app.core.Ports
import com.swarmos.app.core.Signer
import com.swarmos.app.core.SwarmJson
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.PipelineCall
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.doublereceive.DoubleReceive
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.util.pipeline.PipelineContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs

@Serializable
data class ErrorBody(val error: String)

/** 401 body for a request outside the +-30 s window: tells the caller how far apart the clocks are. */
@Serializable
data class SkewBody(val error: String = "clock skew", val serverTimeMs: Long)

const val MAX_BODY_BYTES = 1L shl 20

/** Response headers on every call: who answered and whether its app is in front. Read by [NodeClient]. */
const val HEADER_FG = "X-Swarm-Fg"
const val HEADER_REPLAY = "X-Swarm-Replay"
/** A connection silent for this long is closed: 10 minutes covers a non-streamed model call (streams send pings) without keeping dead sockets for an hour. */
const val IDLE_TIMEOUT_S = 600

val SwarmHeadersPlugin = createApplicationPlugin("SwarmHeaders") {
    onCall { call ->
        call.response.headers.append(Signer.HEADER_NODE, AppGraph.registry.self.value.nodeId)
        call.response.headers.append(HEADER_FG, if (AppForeground.foreground.value) "1" else "0")
    }
}

/**
 * Node API on [Ports.NODE]. `GET /v1/hello` is the only unsigned route. Every other /v1 route must carry
 * valid X-Swarm-* headers (see [Signer]). Each module's routes are mounted under /v1/<module id>/.
 */
class NodeServer {
    private var engine: EmbeddedServer<*, *>? = null

    /** Blocks briefly while binding; call off the main thread. */
    @Synchronized
    fun start() {
        if (engine != null) return
        try {
            engine = embeddedServer(
                CIO, applicationEnvironment { },
                {
                    connector { port = Ports.NODE; host = "0.0.0.0" }
                    // long model calls run for minutes on one connection; the CIO default drops a connection idle for 45 s
                    connectionIdleTimeoutSeconds = IDLE_TIMEOUT_S
                },
            ) { nodeModule() }.also { it.start(wait = false) }
            AppGraph.audit.append("server.start", JsonObject(mapOf("port" to JsonPrimitive(Ports.NODE))))
            LinkRuntime.reportServerError(null)
        } catch (t: Throwable) {
            engine = null
            AppGraph.audit.append("server.error", JsonObject(mapOf("message" to JsonPrimitive(t.toString()))))
            LinkRuntime.reportServerError("Node API port ${Ports.NODE} could not be opened (${t.message ?: t::class.simpleName}); another app may be using it")
            return
        }
        // SwarmLink background work (probe server, NSD, heartbeats) lives and dies with the node server
        runCatching { LinkRuntime.start() }.onFailure {
            AppGraph.audit.append("link.error", JsonObject(mapOf("message" to JsonPrimitive(it.toString()))))
        }
    }

    @Synchronized
    fun stop() {
        runCatching { LinkRuntime.stop() }
        engine?.stop(300, 1000)
        engine = null
    }

    private fun Application.nodeModule() {
        install(ContentNegotiation) { json(SwarmJson) }
        install(DoubleReceive) // lets the signature check read the body and the handler read it again
        install(SSE)
        install(RequestDedupPlugin)
        install(SwarmHeadersPlugin)

        routing {
            route("/v1") {
                intercept(ApplicationCallPipeline.Plugins) { requireSignature() }

                get("/hello") { call.respond(AppGraph.selfInfo()) }
                get("/health") { call.respond(AppGraph.health.state.value) }

                for (m in AppGraph.modules) {
                    route("/${m.id}") { with(m) { routes() } }
                }
            }
        }
    }

    private suspend fun PipelineContext<Unit, PipelineCall>.requireSignature() {
        val req = call.request
        if (BuildConfig.DEBUG && FaultInjection.blocks(req.origin.remoteAddress)) { // failover tests only
            kotlinx.coroutines.delay(20_000)
            return finish()
        }
        if (req.httpMethod == HttpMethod.Get && req.path() == "/v1/hello") return

        val node = req.header(Signer.HEADER_NODE)
        val ts = req.header(Signer.HEADER_TS)?.toLongOrNull()
        val sig = req.header(Signer.HEADER_SIG)
        if (node == null || ts == null || sig == null) return reject("missing X-Swarm-* headers")

        // cheap checks first: nothing is read from an oversized request or one that is out of the time window
        val hasBody = req.httpMethod in setOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch)
        val declaredHeader = req.header(HttpHeaders.ContentLength)?.toLongOrNull()
        if (hasBody && declaredHeader == null && req.header(HttpHeaders.TransferEncoding) != null) {
            // a chunked body has no length to check before reading and would be read whole: it is not accepted (no Content-Length = empty)
            call.respond(HttpStatusCode.LengthRequired, ErrorBody("Content-Length is required (chunked bodies are not accepted)"))
            return finish()
        }
        if ((declaredHeader ?: 0L) > MAX_BODY_BYTES) {
            call.respond(HttpStatusCode.PayloadTooLarge, ErrorBody("body over $MAX_BODY_BYTES bytes"))
            return finish()
        }
        val now = System.currentTimeMillis()
        if (abs(now - ts) > Signer.MAX_SKEW_MS) {
            call.respond(HttpStatusCode.Unauthorized, SkewBody(serverTimeMs = now))
            return finish()
        }

        val body = if (hasBody) call.receiveText() else ""
        // the signed path is the raw URI, query string included
        if (!Signer.verify(AppGraph.pairingSecret, req.httpMethod.value, req.uri, ts, body, sig)) {
            return reject("bad signature")
        }
        // a verified request is proof of life: it counts as a heartbeat on the path it came over
        runCatching { PeerLinks.noteInbound(node, req.origin.remoteAddress, req.path()) }

        // a request that carries an id runs at most once: a retry (same id, another path) gets the stored response
        val reqId = req.header(HEADER_REQ)
        if (reqId == null && (hasBody || req.httpMethod == HttpMethod.Delete)) {
            call.respond(HttpStatusCode.BadRequest, ErrorBody("X-Swarm-Req is required on requests that change state"))
            return finish()
        }
        if (reqId != null && (hasBody || req.httpMethod == HttpMethod.Delete)) {
            val key = "$node/$reqId"
            when (val b = NodeDedup.cache.begin(key)) {
                RequestDedup.Begin.Run -> call.attributes.put(NodeDedup.ID, key)
                RequestDedup.Begin.Busy -> {
                    // the first run of this request is still going: do not hold this connection for ever
                    call.respond(HttpStatusCode.Conflict, ErrorBody("the same request (X-Swarm-Req) is still running"))
                    finish()
                }
                is RequestDedup.Begin.Replay -> {
                    call.response.headers.append(HEADER_REPLAY, "1")
                    call.respondBytes(b.stored.body, b.stored.contentTypeOrJson(), HttpStatusCode.fromValue(b.stored.status))
                    finish()
                }
            }
        }
    }

    private suspend fun PipelineContext<Unit, PipelineCall>.reject(msg: String) {
        call.respond(HttpStatusCode.Unauthorized, ErrorBody(msg))
        finish()
    }
}
