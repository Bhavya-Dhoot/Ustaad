package com.swarmos.app.agent

import com.swarmos.app.core.SwarmJson
import com.swarmos.app.llm.ChatChunk
import com.swarmos.app.llm.ChatStats
import com.swarmos.app.sched.BusyException
import com.swarmos.app.sched.ModelBusyTimeoutException
import com.swarmos.app.sched.NoPhoneAvailableException
import com.swarmos.app.sched.PhoneAbortedException
import com.swarmos.app.sched.PhoneTimeoutException
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.IOException
import java.util.UUID

/**
 * The agent endpoint: an OpenAI-format listener on 127.0.0.1 for other apps on this phone. It is a second, separate Ktor
 * engine; the node server and its signed routes are untouched. Every request passes [AgentGate] (loopback peer, own host
 * name, then the key) before anything is read from it. The class only moves bytes: parsing is [AgentRequests], shapes are
 * [AgentWire], models are [AgentBackend].
 *
 * [audit] gets `agent.start`, `agent.stop` and one `agent.chat`, `agent.embed` or `agent.files` entry per answered request and per 5xx:
 * sizes, counts and milliseconds, never the text of a prompt, an answer, a query or a snippet. Refusals (4xx) are only counted, through [onRefused].
 * `POST /v1/files/search` answers only while [filesEnabled] says the owner has switched file search on; [files] is what it searches.
 */
class AgentServer(
    private val port: Int,
    private val key: () -> String?,
    private val backend: AgentBackend,
    private val files: FileSource? = null,
    private val filesEnabled: () -> Boolean = { false },
    private val audit: (kind: String, payload: JsonObject) -> Unit,
    private val onServed: () -> Unit = {},
    private val onRefused: () -> Unit = {},
    /** A comment line goes out this often while a stream is open, so a vanished client is noticed and its model slot freed. */
    private val pingMs: Long = PING_MS,
    /** A write to a streaming client that takes longer than this ends the stream: a client that stopped reading must not hold the model. */
    private val writeTimeoutMs: Long = WRITE_TIMEOUT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var engine: EmbeddedServer<*, *>? = null
    private val startedSec = clock() / 1000

    /** Requests reading and parsing their body at once, and embedding calls at once; the rest are told to come back (429). */
    private val parsing = Semaphore(MAX_PARSING)
    private val embedding = Semaphore(MAX_EMBEDDING)
    private val searching = Semaphore(MAX_SEARCHING)

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
                    // an unauthenticated local app can open connections too: do not keep silent ones for an hour. A streamed answer
                    // writes pings, so only a non-streamed call slower than this is cut.
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
            val refusal = AgentGate.check(
                call.request.local.remoteAddress, call.request.header(HttpHeaders.Host), port,
                call.request.header(HttpHeaders.Authorization), key(),
            )
            if (refusal != null) {
                onRefused()
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
                post("/files/search") { call.filesSearch() }
            }
            route("/{path...}") {
                handle {
                    onRefused()
                    call.respondJson(HttpStatusCode.NotFound, AgentWire.error("No such route: ${call.request.local.method.value} ${call.request.local.uri}", AgentRefusal.TYPE_INVALID_REQUEST, "unknown_route"))
                }
            }
        }
    }

    // ---- chat ------------------------------------------------------------------------------------------------

    private suspend fun RoutingCall.chat() {
        val t0 = clock()
        var parsed: AgentChatCall? = null
        var status = HttpStatusCode.OK
        val body: JsonObject = try {
            val c = limited(parsing) { AgentRequests.parseChat(readBody()) }
            parsed = c
            backend.chatUnavailable(c.model)?.let { throw AgentRefusal(503, it, "model_not_loaded", AgentRefusal.TYPE_SERVER) }
            if (c.stream) {
                streamChat(c, t0) // answers itself once the first chunk is in; only a failure before that comes back here
                return
            }
            val reply = backend.chat(c.model, c.request)
            val (answer, repairs) = settle(c, reply.text)
            auditChat(c, 200, reply.text.length, reply.stats, t0, repairs = repairs)
            AgentWire.toolReply(newId(), clock() / 1000, c.model, AgentWire.fingerprint(reply.phone), answer, reply.stats, c.request.maxTokens)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val r = refusalOf(e, constrained = parsed?.request?.jsonSchema != null)
            if (r.status >= 500) auditChat(parsed, r.status, 0, null, t0) else onRefused()
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
        @Volatile var repairs = 0
    }

    /**
     * What a call with tools finally says, and how many repair requests it took (0 or 1). "auto" reads the model's own format; if a call
     * in it fails the check, the same conversation is asked once more under a constraint ([AgentTools.repairRequest]), and when that
     * fails too the model's own text is the answer, as words. "required" and a named function were held to a schema from the start.
     */
    private suspend fun settle(c: AgentChatCall, text: String): Pair<ToolReply, Int> {
        val spec = c.tools ?: return ToolReply.Content(text) to 0
        if (spec.choice != ToolChoice.Auto) return AgentTools.parseReply(text, spec) to 0
        return when (val read = AgentTools.readNative(text, spec)) {
            is NativeRead.Words -> ToolReply.Content(text) to 0
            is NativeRead.Calls -> ToolReply.Calls(read.calls) to 0
            is NativeRead.Invalid -> {
                val repair = AgentTools.repairSpec(spec, read.tool)
                val again = try {
                    backend.chat(c.model, AgentTools.repairRequest(c.request, c.history, repair)).text
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null // the repair failing is not the caller's problem: the model's text is still an answer
                }
                val fixed = again?.let { AgentTools.parseReply(it, repair) } as? ToolReply.Calls
                (fixed ?: ToolReply.Content(text)) to 1
            }
        }
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
                auditChat(c, 200, state.chars, state.stats, t0, aborted = !state.finished, repairs = state.repairs)
                if (state.finished) onServed()
            }
        }
    }

    private suspend fun ByteWriteChannel.writeStream(c: AgentChatCall, first: Item?, upstream: ReceiveChannel<Item>, state: StreamState) = coroutineScope<Unit> {
        val id = newId()
        val created = clock() / 1000
        val fingerprint = AgentWire.fingerprint(backend.streamPhone(c.model))
        val lock = Mutex()
        // with tools the answer is held back until it is whole: whether it is words or calls is only known then
        val buffered = c.tools != null
        val held = StringBuilder()
        // a client that stops reading blocks the write; after writeTimeoutMs the stream ends, which frees the model slot
        suspend fun send(text: String) = lock.withLock {
            withTimeoutOrNull(writeTimeoutMs) {
                writeStringUtf8(text)
                flush()
            } ?: throw IOException("the client did not read for $writeTimeoutMs ms")
        }
        val ping = launch {
            while (true) {
                delay(pingMs)
                send(AgentWire.PING)
            }
        }
        try {
            if (!buffered) send(AgentWire.event(AgentWire.chunk(id, created, c.model, fingerprint, AgentWire.roleDelta())))
            var item = first
            while (item != null) {
                when (item) {
                    is Item.Failed -> {
                        send(AgentWire.event(AgentWire.error(refusalOf(item.error, constrained = c.request.jsonSchema != null))))
                        return@coroutineScope
                    }
                    is Item.Chunk -> {
                        val chunk = item.chunk
                        if (chunk.done) {
                            state.stats = chunk.stats
                        } else if (chunk.delta.isNotEmpty()) {
                            state.chars += chunk.delta.length
                            if (buffered) held.append(chunk.delta)
                            else send(AgentWire.event(AgentWire.chunk(id, created, c.model, fingerprint, AgentWire.textDelta(chunk.delta))))
                        }
                    }
                }
                item = upstream.receiveCatching().getOrNull()
            }
            if (buffered) {
                val (reply, repairs) = settle(c, held.toString())
                state.repairs = repairs
                for (chunk in AgentWire.bufferedChunks(id, created, c.model, fingerprint, reply, state.stats, c.request.maxTokens)) send(AgentWire.event(chunk))
            } else {
                val last = AgentWire.chunk(
                    id, created, c.model, fingerprint, JsonObject(emptyMap()),
                    finish = AgentWire.finishReason(state.stats, c.request.maxTokens), usage = AgentWire.usage(state.stats),
                )
                send(AgentWire.event(last))
            }
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
            val c = limited(parsing) { AgentRequests.parseEmbeddings(readBody()) }
            parsed = c
            backend.embedUnavailable()?.let { throw AgentRefusal(503, it, "model_not_loaded", AgentRefusal.TYPE_SERVER) }
            val vectors = limited(embedding) { backend.embed(c.texts) }
            check(vectors.size == c.texts.size) { "the embedding model returned ${vectors.size} vectors for ${c.texts.size} inputs" }
            auditEmbed(c, 200, t0)
            AgentWire.embeddings(c.model, vectors, c.base64)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val r = refusalOf(e)
            if (r.status >= 500) auditEmbed(parsed, r.status, t0) else onRefused()
            status = HttpStatusCode.fromValue(r.status)
            AgentWire.error(r)
        }
        respondJson(status, body)
        if (status == HttpStatusCode.OK) onServed()
    }

    // ---- file search ------------------------------------------------------------------------------------------

    /**
     * What this phone would tell a paired phone, plus (scope swarm) what the paired phones tell it; see [AgentFiles]. The owner's switch
     * is read before the body, again right before the search and again before anything is sent: if it went off meanwhile the answer is
     * the 403 and nothing of the result leaves. A search that cannot run (no embedder, an index to rebuild) is a 503; an empty index is an empty list.
     */
    private suspend fun RoutingCall.filesSearch() {
        val t0 = clock()
        var parsed: FileSearchCall? = null
        var status = HttpStatusCode.OK
        val body: JsonObject = try {
            fun on(): FileSource = files?.takeIf { filesEnabled() }
                ?: throw AgentRefusal(403, "File search is off. The owner can turn on \"File search for other apps\" in Ustaad Settings.", "file_search_off")
            val source = on()
            val c = limited(parsing) { AgentRequests.parseFileSearch(readBody()) }
            parsed = c
            val found = limited(searching) {
                source.unavailable()?.let { throw AgentRefusal(503, it, "index_unavailable", AgentRefusal.TYPE_SERVER) }
                on()
                AgentFiles.search(c, source, newQid())
            }
            if (files == null || !filesEnabled()) {
                auditFiles(c, 403, found, t0) // the search ran, so the owner's log says so; the app gets nothing of it
                on()
            }
            auditFiles(c, 200, found, t0)
            found.body
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val r = refusalOf(e)
            if (r.status >= 500) auditFiles(parsed, r.status, null, t0) else onRefused()
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

    /** Runs [block] only if a place in [gate] is free right now; otherwise the caller is told to come back later (429). */
    private suspend fun <T> limited(gate: Semaphore, block: suspend () -> T): T {
        if (!gate.tryAcquire()) throw AgentRefusal(429, "Too many requests at once. Try again shortly.", "rate_limited", AgentRefusal.TYPE_RATE_LIMIT)
        try {
            return block()
        } finally {
            gate.release()
        }
    }

    /**
     * What an exception becomes on the wire. A 5xx never carries the exception's own message: it can name paths, ports or models.
     * [constrained]: the call carried a JSON schema, so the model server saying it cannot use that schema is the caller's 400, not our 500.
     */
    private fun refusalOf(e: Exception, constrained: Boolean = false): AgentRefusal = when {
        e is AgentRefusal -> e
        constrained && AgentTools.isSchemaRejection(e) -> AgentRefusal(
            400, "The model server could not turn the JSON schema of this request into a constraint (for tools: a `parameters` schema). Use plain types, enum, required, nested objects and arrays.",
            "unsupported_schema",
        )
        else -> serverRefusal(e)
    }

    private fun serverRefusal(e: Exception): AgentRefusal = when (e) {
        is ModelBusyTimeoutException -> AgentRefusal(503, "The model is busy. Try again shortly.", "model_busy", AgentRefusal.TYPE_SERVER)
        is NoPhoneAvailableException, is PhoneTimeoutException, is PhoneAbortedException, is BusyException ->
            AgentRefusal(503, "The swarm could not run the request. Try again shortly.", "swarm_unavailable", AgentRefusal.TYPE_SERVER)
        // a lost connection to a model server or to a phone: the swarm's StreamBrokenException reaches us as its cause
        is IOException -> AgentRefusal(503, "The model did not answer. Try again shortly.", "model_unavailable", AgentRefusal.TYPE_SERVER)
        else -> AgentRefusal(500, "The model call failed.", "internal_error", AgentRefusal.TYPE_SERVER)
    }

    private fun newId() = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(24)

    private fun newQid() = UUID.randomUUID().toString().take(8)

    private fun auditChat(c: AgentChatCall?, status: Int, completionChars: Int, stats: ChatStats?, t0: Long, aborted: Boolean = false, repairs: Int = 0) {
        record("agent.chat", buildJsonObject {
            c?.let {
                put("model", it.model.id)
                put("stream", it.stream)
                put("promptChars", it.promptChars)
                if (it.tools != null) put("repairs", repairs) // how many times a tool call had to be asked for again: a number, no text
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

    /**
     * Scope, how long the query was, how many results, which phone gave how many, how many paired phones were sent the query and how many
     * bytes of it left this phone: no query text, no title, no snippet.
     */
    private fun auditFiles(c: FileSearchCall?, status: Int, found: FilesFound?, t0: Long) {
        record("agent.files", buildJsonObject {
            c?.let {
                put("scope", it.scope.id)
                put("queryChars", it.query.length)
            }
            put("status", status)
            found?.let { f ->
                put("results", f.results)
                put("asked", f.asked) // paired phones sent the query
                put("sentBytes", f.sentBytes) // bytes of query text that left this phone for them
                putJsonArray("phones") {
                    for (p in f.phones) add(buildJsonObject {
                        put("name", p.name)
                        put("status", p.status)
                        put("results", p.results)
                    })
                }
            }
            put("ms", clock() - t0)
        })
    }

    private fun record(kind: String, payload: JsonObject) {
        runCatching { audit(kind, payload) } // a full disk must not turn an answer into an error
    }

    companion object {
        const val LOOPBACK = "127.0.0.1"
        const val PING_MS = 2_000L
        const val WRITE_TIMEOUT_MS = 30_000L
        const val IDLE_TIMEOUT_S = 300
        const val MAX_PARSING = 4
        const val MAX_EMBEDDING = 2
        const val MAX_SEARCHING = 2
    }
}
