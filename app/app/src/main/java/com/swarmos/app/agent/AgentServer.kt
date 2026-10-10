package com.swarmos.app.agent

import com.swarmos.app.core.SwarmJson
import com.swarmos.app.link.IDLE_TIMEOUT_S
import com.swarmos.app.llm.ChatChunk
import com.swarmos.app.llm.ChatStats
import com.swarmos.app.sched.ModelBusyTimeoutException
import com.swarmos.app.sched.NoPhoneAvailableException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * The agent endpoint: an OpenAI-format listener on 127.0.0.1 for other apps on this phone. It is a second, separate Ktor
 * engine; the node server and its signed routes are untouched. Every request passes [AgentGate] (loopback peer, then the
 * key) before anything is read from it. The class only moves bytes: parsing is [AgentRequests], shapes are [AgentWire],
 * models are [AgentBackend].
 *
 * [audit] gets `agent.start`, `agent.stop` and one `agent.chat` or `agent.embed` entry per request: sizes, counts and
 * milliseconds, never the text of a prompt or an answer.
 */
class AgentServer(
    private val port: Int,
    private val key: () -> String?,
    private val backend: AgentBackend,
    private val audit: (kind: String, payload: JsonObject) -> Unit,
    private val onServed: () -> Unit = {},
    /** A comment line goes out this often while a stream is open, so a vanished client is noticed and its model slot freed. */
    private val pingMs: Long = PING_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var engine: EmbeddedServer<*, *>? = null
    private val startedSec = clock() / 1000

    /** Opens the port. Returns null, or why it could not be opened. Blocks briefly; call off the main thread. */
    @Synchronized
    fun start(): String? {
        if (engine != null) return null
        val listenPort = port
        return try {
            engine = embeddedServer(
                CIO, applicationEnvironment { },
                {
                    connector { this.port = listenPort; host = LOOPBACK }
                    // a long prompt can keep a connection silent for minutes before the first byte
                    connectionIdleTimeoutSeconds = IDLE_TIMEOUT_S
                },
            ) { agentModule() }.also { it.start(wait = false) }
            record("agent.start", buildJsonObject { put("port", listenPort) })
            null
        } catch (t: Throwable) {
            engine = null
            record("agent.error", buildJsonObject { put("message", t.toString()) })
            "Agent port $listenPort could not be opened (${t.message ?: t::class.simpleName}); another app may be using it"
        }
    }

    @Synchronized
    fun stop() {
        val e = engine ?: return
        engine = null
        e.stop(200, 1000)
        record("agent.stop", buildJsonObject { put("port", port) })
    }

    private fun Application.agentModule() {
        intercept(ApplicationCallPipeline.Plugins) {
            val refusal = AgentGate.check(call.request.origin.remoteAddress, call.request.header(HttpHeaders.Authorization), key())
            if (refusal != null) {
                if (refusal.status == 401) call.response.header(HttpHeaders.WWWAuthenticate, "Bearer")
                call.respondJson(HttpStatusCode.fromValue(refusal.status), AgentWire.error(refusal))
                finish()
            }
        }

        routing {
            route("/v1") {
                get("/models") {
                    call.respondJson(HttpStatusCode.OK, AgentWire.models(startedSec))
                    onServed()
                }
                post("/chat/completions") { call.chat() }
                post("/embeddings") { call.embeddings() }
            }
            route("/{path...}") {
                handle { call.respondJson(HttpStatusCode.NotFound, AgentWire.error("No such route: ${call.request.local.method.value} ${call.request.local.uri}", AgentRefusal.TYPE_INVALID_REQUEST, "unknown_route")) }
            }
        }
    }

    // ---- chat ------------------------------------------------------------------------------------------------

    private suspend fun RoutingCall.chat() {
        val t0 = clock()
        var parsed: AgentChatCall? = null
        var status = HttpStatusCode.OK
        val body: JsonObject = try {
            parsed = AgentRequests.parseChat(readBody())
            backend.chatUnavailable(parsed.model)?.let { throw AgentRefusal(503, it, "model_not_loaded", AgentRefusal.TYPE_SERVER) }
            if (parsed.stream) {
                streamChat(parsed, t0) // answers itself once the first chunk is in; only a failure before that comes back here
                return
            }
            val reply = backend.chat(parsed.model, parsed.request)
            auditChat(parsed, 200, reply.text.length, reply.stats, t0)
            AgentWire.chatCompletion(newId(), clock() / 1000, parsed.model, AgentWire.fingerprint(reply.phone), reply.text, reply.stats, parsed.request.maxTokens)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val r = refusalOf(e)
            auditChat(parsed, r.status, 0, null, t0)
            status = HttpStatusCode.fromValue(r.status)
            AgentWire.error(r)
        }
        respondJson(status, body)
        if (status == HttpStatusCode.OK) onServed()
    }

    private class StreamState {
        @Volatile var chars = 0
        @Volatile var stats: ChatStats? = null
        @Volatile var finished = false
    }

    private sealed interface Item {
        class Chunk(val chunk: ChatChunk) : Item
        class Failed(val error: Exception) : Item
    }

    /**
     * Answers as server-sent events. The 200 is committed only when the first chunk (or the first failure) is in, so a model
     * that is busy or missing still gets its 503. Chunks are handed over one at a time; when the client goes away the next
     * write or ping fails, which cancels the producer, which releases the model slot.
     */
    private suspend fun RoutingCall.streamChat(c: AgentChatCall, t0: Long) = coroutineScope<Unit> {
        val flow = backend.chatStream(c.model, c.request)
        val upstream = produce<Item>(capacity = Channel.RENDEZVOUS) {
            try {
                flow.collect { send(Item.Chunk(it)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                send(Item.Failed(e))
            }
        }
        val state = StreamState()
        var committed = false
        try {
            val first = upstream.receiveCatching().getOrNull()
            if (first is Item.Failed) throw first.error
            response.header(HttpHeaders.CacheControl, "no-cache")
            committed = true
            try {
                respondBytesWriter(ContentType.Text.EventStream) { writeStream(c, first, upstream, state) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // the client closed the connection mid-stream; the audit entry below records that it did not finish
            }
        } finally {
            upstream.cancel()
            // also when the call itself was cancelled because the client went away
            if (committed) {
                auditChat(c, 200, state.chars, state.stats, t0, aborted = !state.finished)
                if (state.finished) onServed()
            }
        }
    }

    private suspend fun ByteWriteChannel.writeStream(c: AgentChatCall, first: Item?, upstream: ReceiveChannel<Item>, state: StreamState) = coroutineScope<Unit> {
        val id = newId()
        val created = clock() / 1000
        val fingerprint = AgentWire.fingerprint(backend.streamPhone(c.model))
        val lock = Mutex()
        suspend fun send(text: String) = lock.withLock {
            writeStringUtf8(text)
            flush()
        }
        val ping = launch {
            while (true) {
                delay(pingMs)
                send(AgentWire.PING)
            }
        }
        try {
            send(AgentWire.event(AgentWire.chunk(id, created, c.model, fingerprint, AgentWire.roleDelta())))
            var item = first
            while (item != null) {
                when (item) {
                    is Item.Failed -> {
                        send(AgentWire.event(AgentWire.error(refusalOf(item.error))))
                        return@coroutineScope
                    }
                    is Item.Chunk -> {
                        val chunk = item.chunk
                        if (chunk.done) {
                            state.stats = chunk.stats
                        } else if (chunk.delta.isNotEmpty()) {
                            state.chars += chunk.delta.length
                            send(AgentWire.event(AgentWire.chunk(id, created, c.model, fingerprint, AgentWire.textDelta(chunk.delta))))
                        }
                    }
                }
                item = upstream.receiveCatching().getOrNull()
            }
            val last = AgentWire.chunk(
                id, created, c.model, fingerprint, JsonObject(emptyMap()),
                finish = AgentWire.finishReason(state.stats, c.request.maxTokens), usage = AgentWire.usage(state.stats),
            )
            send(AgentWire.event(last))
            send(AgentWire.DONE)
            state.finished = true
        } finally {
            ping.cancel()
            upstream.cancel()
        }
    }

    // ---- embeddings -------------------------------------------------------------------------------------------

    private suspend fun RoutingCall.embeddings() {
        val t0 = clock()
        var parsed: AgentEmbedCall? = null
        var status = HttpStatusCode.OK
        val body: JsonObject = try {
            parsed = AgentRequests.parseEmbeddings(readBody())
            backend.embedUnavailable()?.let { throw AgentRefusal(503, it, "model_not_loaded", AgentRefusal.TYPE_SERVER) }
            val vectors = backend.embed(parsed.texts)
            check(vectors.size == parsed.texts.size) { "the embedding model returned ${vectors.size} vectors for ${parsed.texts.size} inputs" }
            auditEmbed(parsed, 200, t0)
            AgentWire.embeddings(parsed.model, vectors, parsed.base64)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val r = refusalOf(e)
            auditEmbed(parsed, r.status, t0)
            status = HttpStatusCode.fromValue(r.status)
            AgentWire.error(r)
        }
        respondJson(status, body)
        if (status == HttpStatusCode.OK) onServed()
    }

    // ---- shared -----------------------------------------------------------------------------------------------

    /** The whole body as text. A declared length over the limit is refused unread; a body with no length is read only up to the limit. */
    private suspend fun RoutingCall.readBody(): String {
        val max = AgentRequests.MAX_BODY_BYTES
        val declared = request.header(HttpHeaders.ContentLength)?.toLongOrNull()
        if (declared != null && declared > max) throw tooLarge()
        val bytes = receiveChannel().readRemaining(max + 1).readByteArray()
        if (bytes.size > max) throw tooLarge()
        return bytes.toString(Charsets.UTF_8)
    }

    /**
     * JSON answers are written as plain text, not through content negotiation: a client that sends `Accept: text/event-stream`
     * (many streaming clients do) would otherwise get 406 instead of the real error.
     */
    private suspend fun ApplicationCall.respondJson(status: HttpStatusCode, body: JsonObject) =
        respondText(SwarmJson.encodeToString(JsonObject.serializer(), body), ContentType.Application.Json, status)

    private fun tooLarge() = AgentRefusal(413, "The request body is over ${AgentRequests.MAX_BODY_BYTES} bytes.", "request_too_large")

    private fun refusalOf(e: Exception): AgentRefusal = when (e) {
        is AgentRefusal -> e
        is ModelBusyTimeoutException -> AgentRefusal(503, e.message ?: "The model is busy.", "model_busy", AgentRefusal.TYPE_SERVER)
        is NoPhoneAvailableException -> AgentRefusal(503, e.message ?: AgentBackend.NO_MODEL_SWARM, "model_not_loaded", AgentRefusal.TYPE_SERVER)
        else -> AgentRefusal(500, e.message ?: e::class.simpleName ?: "error", "internal_error", AgentRefusal.TYPE_SERVER)
    }

    private fun newId() = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(24)

    private fun auditChat(c: AgentChatCall?, status: Int, completionChars: Int, stats: ChatStats?, t0: Long, aborted: Boolean = false) {
        record("agent.chat", buildJsonObject {
            c?.let {
                put("model", it.model.id)
                put("stream", it.stream)
                put("promptChars", it.promptChars)
            }
            put("status", status)
            put("completionChars", completionChars)
            stats?.let {
                put("promptTokens", it.promptTokens)
                put("completionTokens", it.completionTokens)
            }
            if (aborted) put("aborted", true)
            put("ms", clock() - t0)
        })
    }

    private fun auditEmbed(c: AgentEmbedCall?, status: Int, t0: Long) {
        record("agent.embed", buildJsonObject {
            c?.let {
                put("model", it.model.id)
                put("inputs", it.texts.size)
                put("chars", it.chars)
            }
            put("status", status)
            put("ms", clock() - t0)
        })
    }

    private fun record(kind: String, payload: JsonObject) {
        runCatching { audit(kind, payload) } // a full disk must not turn an answer into an error
    }

    companion object {
        const val LOOPBACK = "127.0.0.1"
        const val PING_MS = 2_000L
    }
}
