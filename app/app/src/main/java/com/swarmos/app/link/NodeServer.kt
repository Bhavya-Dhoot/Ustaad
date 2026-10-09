package com.swarmos.app.link

import com.swarmos.app.AppGraph
import com.swarmos.app.BuildConfig
import com.swarmos.app.core.Ports
import com.swarmos.app.core.Signer
import com.swarmos.app.core.SwarmJson
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
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveText
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

@Serializable
data class ErrorBody(val error: String)

/** Response headers on every call: who answered and whether its app is in front. Read by [NodeClient]. */
const val HEADER_FG = "X-Swarm-Fg"
const val HEADER_REPLAY = "X-Swarm-Replay"
const val IDLE_TIMEOUT_S = 3600

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
        } catch (t: Throwable) {
            engine = null
            AppGraph.audit.append("server.error", JsonObject(mapOf("message" to JsonPrimitive(t.toString()))))
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
        if (req.httpMethod == HttpMethod.Get && req.path() == "/v1/hello") return
        // debug builds only: adb tooling reads the pairing payload without a signature
        if (BuildConfig.DEBUG && req.httpMethod == HttpMethod.Get && req.path() == "/v1/link/pairing-payload") return

        val node = req.header(Signer.HEADER_NODE)
        val ts = req.header(Signer.HEADER_TS)?.toLongOrNull()
        val sig = req.header(Signer.HEADER_SIG)
        if (node == null || ts == null || sig == null) return reject("missing X-Swarm-* headers")

        val hasBody = req.httpMethod in setOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Patch)
        val body = if (hasBody) call.receiveText() else ""
        if (!Signer.verify(AppGraph.pairingSecret, req.httpMethod.value, req.path(), ts, body, sig)) {
            return reject("bad signature or stale timestamp")
        }

        // a request that carries an id runs at most once: a retry (same id, another path) gets the stored response
        val reqId = req.header(HEADER_REQ)
        if (reqId != null && (hasBody || req.httpMethod == HttpMethod.Delete)) {
            val key = "$node/$reqId"
            when (val b = NodeDedup.cache.begin(key)) {
                RequestDedup.Begin.Run -> call.attributes.put(NodeDedup.ID, key)
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
