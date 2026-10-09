package com.swarmos.app.llm

import com.swarmos.app.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * [LlmClient] against a local llama-server (OpenAI-compatible API on 127.0.0.1).
 * [role] CHAT serves chat and JSON-schema output, EMBED serves [embed]. Cancelling the collector of
 * [chatStream] closes the socket, which makes the server abort the generation.
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
        val startNs = System.nanoTime()
        var conn: HttpURLConnection? = null
        val reader = launch(Dispatchers.IO) {
            val parser = SseChatParser()
            var firstNs = 0L
            val c = LlamaHttp.post("$base/v1/chat/completions", body, readTimeoutMs = STREAM_READ_TIMEOUT_MS)
            conn = c
            try {
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
            } finally {
                c.disconnect()
            }
        }
        // Reader failures (server error, socket closed) propagate to the collector through the channel.
        reader.invokeOnCompletion { cause -> if (cause != null) close(cause) else close() }
        awaitClose { conn?.disconnect(); reader.cancel() }
    }

    /** A model flagged chat-only in the catalog answers JSON-schema requests with empty content: fail loudly instead. */
    private fun refuseSchemaIfChatOnly(req: ChatRequest) {
        if (req.jsonSchema != null && !ModelCatalog.allowsSchema(modelName)) {
            throw LlamaServerException("$modelName is ${ModelCatalog.CHAT_ONLY_NOTE}; it returns empty content for JSON-schema requests")
        }
    }

    override suspend fun chat(req: ChatRequest): ChatResult = withContext(Dispatchers.IO) {
        check(role == Role.CHAT) { "chat is served by the CHAT server only" }
        refuseSchemaIfChatOnly(req)
        val c = LlamaHttp.post("$base/v1/chat/completions", LlamaJson.chatBody(req, stream = false, model = modelName), STREAM_READ_TIMEOUT_MS)
        try {
            val text = LlamaHttp.readBody(c)
            if (c.responseCode !in 200..299) throw LlamaServerException(text, c.responseCode)
            LlamaJson.parseChatResponse(text).also {
                AppGraph.health.lastDecodeTokPerSec = it.stats.decodeTokPerSec.takeIf { v -> v > 0 }
                LlamaRuntime.lastStats.value = it.stats
            }
        } finally {
            c.disconnect()
        }
    }

    override suspend fun embed(texts: List<String>): List<FloatArray> = withContext(Dispatchers.IO) {
        check(role == Role.EMBED) { "embeddings come from AppGraph.embedder (the EMBED server), not the chat server" }
        val out = ArrayList<FloatArray>(texts.size)
        val t0 = System.nanoTime()
        for (batch in texts.chunked(EMBED_BATCH)) {
            val c = LlamaHttp.post("$base/v1/embeddings", LlamaJson.embedBody(batch, modelName), EMBED_READ_TIMEOUT_MS)
            try {
                val text = LlamaHttp.readBody(c)
                if (c.responseCode !in 200..299) throw LlamaServerException(text, c.responseCode)
                out += LlamaJson.parseEmbeddings(text)
            } finally {
                c.disconnect()
            }
        }
        if (texts.isNotEmpty()) LlamaRuntime.lastEmbedMsPerText.value = (System.nanoTime() - t0) / 1e6 / texts.size
        out
    }

    companion object {
        private const val STREAM_READ_TIMEOUT_MS = 10 * 60_000
        private const val EMBED_READ_TIMEOUT_MS = 2 * 60_000
        private const val EMBED_BATCH = 32
    }
}

/** Tiny HttpURLConnection helpers shared by the client, the runtime health check and the benchmark. */
internal object LlamaHttp {
    fun post(url: String, body: String, readTimeoutMs: Int): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 5_000
        c.readTimeout = readTimeoutMs
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "text/event-stream, application/json")
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        return c
    }

    fun get(url: String, readTimeoutMs: Int = 5_000): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 2_000
        c.readTimeout = readTimeoutMs
        return c
    }

    /** True when `GET /health` answers 200 (llama-server answers 503 while the model is loading). */
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
