package com.swarmos.app.llm

import com.swarmos.app.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [LlmClient] against a local llama-server (OpenAI-compatible API on 127.0.0.1).
 * [role] CHAT serves chat and JSON-schema output, EMBED serves [embed].
 *
 * Every call is cancellable: cancelling the caller (withTimeout, withTimeoutOrNull, a cancelled scope) closes the
 * socket, and llama-server aborts the generation and frees its slot when the connection drops. The socket read
 * timeout is the smaller of a 10 minute backstop and the caller's remaining [ChatBudget] (see [withChatBudget]).
 */
class LlamaServerClient(
    val role: Role,
    override val modelName: String,
    val port: Int = role.defaultPort,
) : LlmClient {
    private val base get() = "http://127.0.0.1:$port"

    override suspend fun ready(): Boolean = withContext(Dispatchers.IO) { LlamaHttp.healthOk(port) }

    override fun chatStream(req: ChatRequest): Flow<ChatChunk> = chatStreamRaw(req)

    /** [extra] adds raw top-level request fields (e.g. `ignore_eos`), used by the benchmark. */
    fun chatStreamRaw(req: ChatRequest, cachePrompt: Boolean = true, extra: JsonObject? = null): Flow<ChatChunk> = channelFlow {
        check(role == Role.CHAT) { "chat is served by the CHAT server only" }
        refuseSchemaIfChatOnly(req)
        val body = LlamaJson.chatBody(req, stream = true, model = modelName, cachePrompt = cachePrompt, extra = extra)
        val readTimeout = readTimeoutMs(STREAM_READ_TIMEOUT_MS)
        val startNs = System.nanoTime()
        val holder = ConnHolder()
        val reader = launch(Dispatchers.IO) {
            val parser = SseChatParser()
            var firstNs = 0L
            val c = LlamaHttp.newPost("$base/v1/chat/completions", readTimeout)
            if (!holder.attach(c)) return@launch // cancelled before the socket was opened
            try {
                LlamaHttp.send(c, body)
                if (c.responseCode !in 200..299) throw LlamaServerException(LlamaHttp.errorText(c), c.responseCode)
                BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { r ->
                    while (true) {
                        val line = r.readLine() ?: break
                        val delta = parser.feed(line)
                        if (delta != null) {
                            if (firstNs == 0L) firstNs = System.nanoTime()
                            send(ChatChunk(delta))
                        }
                        if (parser.done) break
                    }
                }
                val endNs = System.nanoTime()
                val first = if (firstNs == 0L) endNs else firstNs
                val stats = parser.finalStats((first - startNs) / 1e6, (endNs - first) / 1e6)
                AppGraph.health.lastDecodeTokPerSec = stats.decodeTokPerSec.takeIf { it > 0 }
                LlamaRuntime.lastStats.value = stats
                send(ChatChunk("", done = true, stats = stats))
            } catch (e: Exception) {
                // Our own disconnect (the caller gave up) makes the blocked read fail with "Socket closed": that is a
                // cancellation, not a server error, so it must not replace the caller's TimeoutCancellationException.
                if (holder.isCancelled) throw kotlinx.coroutines.CancellationException("chat cancelled by the caller")
                throw e
            } finally {
                c.disconnect()
            }
        }
        // Reader failures (server error, socket closed) propagate to the collector through the channel.
        reader.invokeOnCompletion { cause -> if (cause != null) close(cause) else close() }
        // The collector went away (timeout, cancel): close the socket so the server stops generating.
        awaitClose { holder.cancel(); reader.cancel() }
    }

    /** A model flagged chat-only in the catalog answers JSON-schema requests with empty content: fail loudly instead. */
    private fun refuseSchemaIfChatOnly(req: ChatRequest) {
        if (req.jsonSchema != null && !ModelCatalog.allowsSchema(modelName)) {
            throw LlamaServerException("$modelName is ${ModelCatalog.CHAT_ONLY_NOTE}; it returns empty content for JSON-schema requests")
        }
    }

    /** Built on the streaming path, so cancelling the caller drops the connection at once (a blocking read could not be cancelled). */
    override suspend fun chat(req: ChatRequest): ChatResult {
        val sb = StringBuilder()
        var stats: ChatStats? = null
        chatStreamRaw(req).collect { c ->
            sb.append(c.delta)
            if (c.done) stats = c.stats
        }
        return ChatResult(sb.toString(), stats ?: ChatStats(0, 0, 0.0, 0.0))
    }

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        check(role == Role.EMBED) { "embeddings come from AppGraph.embedder (the EMBED server), not the chat server" }
        val readTimeout = readTimeoutMs(EMBED_READ_TIMEOUT_MS)
        val t0 = System.nanoTime()
        val out = cancellableIo { holder ->
            val all = ArrayList<FloatArray>(texts.size)
            for (batch in texts.chunked(EMBED_BATCH)) {
                val c = LlamaHttp.newPost("$base/v1/embeddings", readTimeout)
                if (!holder.attach(c)) throw kotlinx.coroutines.CancellationException("embed cancelled")
                try {
                    LlamaHttp.send(c, LlamaJson.embedBody(batch, modelName))
                    val text = LlamaHttp.readBody(c)
                    if (c.responseCode !in 200..299) throw LlamaServerException(text, c.responseCode)
                    all += LlamaJson.parseEmbeddings(text)
                } finally {
                    c.disconnect()
                }
            }
            all
        }
        if (texts.isNotEmpty()) LlamaRuntime.recordEmbed(modelName, (System.nanoTime() - t0) / 1e6 / texts.size)
        return out
    }

    private suspend fun readTimeoutMs(backstopMs: Int): Int {
        val remaining = currentCoroutineContext()[ChatBudget]?.remainingMs() ?: return backstopMs
        return remaining.coerceIn(MIN_READ_TIMEOUT_MS.toLong(), backstopMs.toLong()).toInt()
    }

    companion object {
        private const val STREAM_READ_TIMEOUT_MS = 10 * 60_000
        private const val EMBED_READ_TIMEOUT_MS = 2 * 60_000
        private const val MIN_READ_TIMEOUT_MS = 1_000
        private const val EMBED_BATCH = 32
    }
}

/**
 * The caller's time budget, carried in the coroutine context by [withChatBudget]. [LlamaServerClient] caps its socket
 * read timeout with what is left of it, so a call that outlives the budget fails on the socket even if nobody cancels it.
 */
class ChatBudget(private val deadlineNanos: Long) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ChatBudget>

    fun remainingMs(nowNanos: Long = System.nanoTime()): Long = ((deadlineNanos - nowNanos) / 1_000_000).coerceAtLeast(0)
}

/** Runs [block] with a hard deadline of [ms]: it is cancelled when the budget is spent, and model calls inside see the budget. */
suspend fun <T> withChatBudget(ms: Long, block: suspend () -> T): T =
    withTimeout(ms) { withContext(ChatBudget(System.nanoTime() + ms * 1_000_000)) { block() } }

/**
 * [withChatBudget] for callers that record a failure instead of being cancelled (the selftest, the benchmark): a spent
 * budget becomes an IllegalStateException with a readable message instead of a TimeoutCancellationException.
 */
internal suspend fun <T> withChatBudgetOrFail(what: String, ms: Long, block: suspend () -> T): T = try {
    withChatBudget(ms, block)
} catch (e: TimeoutCancellationException) {
    throw IllegalStateException("$what did not finish within ${ms / 1000} s", e)
}

/** The connection of an in-flight request, so a cancelling caller can close it from another thread. */
internal class ConnHolder {
    @Volatile private var conn: HttpURLConnection? = null
    @Volatile private var cancelled = false
    val isCancelled: Boolean get() = cancelled

    /** Registers [c]; false when [cancel] already ran (the caller must not start I/O on it). */
    fun attach(c: HttpURLConnection): Boolean {
        conn = c
        return !cancelled
    }

    fun cancel() {
        cancelled = true
        try { conn?.disconnect() } catch (_: Exception) { }
    }
}

private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Runs blocking socket work on the IO pool and resumes the caller with its result. Cancelling the caller calls
 * [ConnHolder.cancel], which closes the socket and so unblocks the worker thread.
 */
internal suspend fun <T> cancellableIo(block: (ConnHolder) -> T): T = suspendCancellableCoroutine { cont ->
    val holder = ConnHolder()
    cont.invokeOnCancellation { holder.cancel() }
    ioScope.launch {
        try {
            cont.resume(block(holder))
        } catch (e: Throwable) {
            if (cont.isActive) cont.resumeWithException(e)
        }
    }
}

/**
 * Tiny HttpURLConnection helpers shared by the client, the runtime health check, the warm-up, the self-test and the
 * benchmark. Every call to a local llama-server carries the process's API key ([LlamaKey]); a URL that is not a loopback
 * address never gets it.
 */
internal object LlamaHttp {
    private const val LOCAL_PREFIX = "http://127.0.0.1:"

    /** The `Authorization` value for a call to [url]: the key for a local llama-server, null for any other address. */
    fun authHeaderFor(url: String, key: String = LlamaKey.value): String? = if (url.startsWith(LOCAL_PREFIX)) LlamaKey.bearer(key) else null

    private fun authorize(c: HttpURLConnection, url: String, key: String) {
        authHeaderFor(url, key)?.let { c.setRequestProperty("Authorization", it) }
    }

    /** A configured POST connection; no I/O happens until [send]. */
    fun newPost(url: String, readTimeoutMs: Int, key: String = LlamaKey.value): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 5_000
        c.readTimeout = readTimeoutMs
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "text/event-stream, application/json")
        authorize(c, url, key)
        return c
    }

    fun send(c: HttpURLConnection, body: String) {
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
    }

    fun post(url: String, body: String, readTimeoutMs: Int): HttpURLConnection =
        newPost(url, readTimeoutMs).also { send(it, body) }

    fun get(url: String, readTimeoutMs: Int = 5_000, key: String = LlamaKey.value): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 2_000
        c.readTimeout = readTimeoutMs
        authorize(c, url, key)
        return c
    }

    /**
     * True when `GET /health` answers 200 (llama-server answers 503 while the model is loading). With `--api-key` the server
     * leaves `/health` public (tools/server/server-http.cpp); the header is sent anyway, it does no harm.
     */
    fun healthOk(port: Int): Boolean = try {
        val c = get("http://127.0.0.1:$port/health", 3_000)
        try { c.responseCode == 200 } finally { c.disconnect() }
    } catch (_: Exception) {
        false
    }

    fun readBody(c: HttpURLConnection): String {
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
    }

    fun errorText(c: HttpURLConnection): String = readBody(c).ifBlank { "HTTP ${c.responseCode}" }
}
