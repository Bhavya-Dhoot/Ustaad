package com.swarmos.app.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.sqrt

/** The llama-server answered with an error (HTTP status or an `error` object inside the stream). */
class LlamaServerException(message: String, val status: Int = 0) : RuntimeException(message)

/**
 * Pure (no Android, no network) helpers for the llama-server OpenAI-compatible API:
 * request bodies, the SSE stream parser, the `timings` mapping and embedding post-processing.
 */
object LlamaJson {
    val json = Json { ignoreUnknownKeys = true }

    /** Embedding size handed to callers (Matryoshka truncation), contract section 4 / PRD R3. */
    const val EMBED_DIM = 256

    /**
     * Body for POST /v1/chat/completions. Thinking is switched off through chat_template_kwargs, which
     * the server hands to the Jinja chat template (Qwen3.5 honours enable_thinking; other templates ignore it).
     */
    fun chatBody(
        req: ChatRequest,
        stream: Boolean,
        model: String,
        cachePrompt: Boolean = true,
        extra: JsonObject? = null,
    ): String {
        val body = buildJsonObject {
            put("model", model)
            put("stream", stream)
            if (stream) put("stream_options", buildJsonObject { put("include_usage", true) }) // the last chunk then carries usage and timings
            put("max_tokens", req.maxTokens)
            put("temperature", req.temperature)
            put("cache_prompt", cachePrompt)
            req.seed?.let { put("seed", it) }
            put("messages", buildJsonArray {
                for (m in req.messages) add(buildJsonObject { put("role", m.role); put("content", m.content) })
            })
            if (req.stop.isNotEmpty()) put("stop", buildJsonArray { req.stop.forEach { add(JsonPrimitive(it)) } })
            req.jsonSchema?.let { schemaText ->
                put("response_format", buildJsonObject {
                    put("type", "json_schema")
                    put("json_schema", buildJsonObject {
                        put("name", "response")
                        put("strict", true)
                        put("schema", json.parseToJsonElement(schemaText))
                    })
                })
            }
            extra?.forEach { (k, v) -> put(k, v) }
            // Thinking is off for every call and written last, so no caller or `extra` field can switch it back on.
            put("chat_template_kwargs", buildJsonObject { put("enable_thinking", false) })
        }
        return body.toString()
    }

    /** Maps the llama-server `timings` object to [ChatStats]. promptTokens = tokens evaluated for this request (cache hits excluded). */
    fun statsFromTimings(t: JsonObject): ChatStats = ChatStats(
        promptTokens = t.num("prompt_n")?.toInt() ?: 0,
        completionTokens = t.num("predicted_n")?.toInt() ?: 0,
        promptMs = t.num("prompt_ms") ?: 0.0,
        genMs = t.num("predicted_ms") ?: 0.0,
    )

    fun statsFromUsage(u: JsonObject): ChatStats = ChatStats(
        promptTokens = u.num("prompt_tokens")?.toInt() ?: 0,
        completionTokens = u.num("completion_tokens")?.toInt() ?: 0,
        promptMs = 0.0,
        genMs = 0.0,
    )

    /** Non-streaming response of /v1/chat/completions. */
    fun parseChatResponse(body: String): ChatResult {
        val root = json.parseToJsonElement(body).jsonObject
        root.errorMessage()?.let { throw LlamaServerException(it) }
        val text = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject?.get("content").stringOrEmpty()
        val stats = root["timings"]?.asObject()?.let(::statsFromTimings)
            ?: root["usage"]?.asObject()?.let(::statsFromUsage)
            ?: ChatStats(0, 0, 0.0, 0.0)
        return ChatResult(text, stats)
    }

    /** Body for POST /v1/embeddings. */
    fun embedBody(texts: List<String>, model: String): String = buildJsonObject {
        put("model", model)
        put("encoding_format", "float")
        put("input", buildJsonArray { texts.forEach { add(JsonPrimitive(it)) } })
    }.toString()

    /** Response of /v1/embeddings -> one truncated, L2-normalised vector per input, in input order. */
    fun parseEmbeddings(body: String, dim: Int = EMBED_DIM): List<FloatArray> {
        val root = json.parseToJsonElement(body).jsonObject
        root.errorMessage()?.let { throw LlamaServerException(it) }
        val rows = (root["data"] ?: throw LlamaServerException("embeddings response has no data")).jsonArray
        return rows
            .sortedBy { it.jsonObject["index"]?.jsonPrimitive?.intOrNull ?: 0 }
            .map { row ->
                val emb = row.jsonObject["embedding"]!!.jsonArray
                // pooling=none would return one vector per token (array of arrays); take the first row.
                val flat = if (emb.firstOrNull() is JsonArray) emb.first().jsonArray else emb
                truncateAndNormalise(FloatArray(flat.size) { flat[it].jsonPrimitive.content.toFloat() }, dim)
            }
    }

    /** First [dim] components (Matryoshka truncation), then L2 normalisation. */
    fun truncateAndNormalise(v: FloatArray, dim: Int = EMBED_DIM): FloatArray {
        val out = if (v.size > dim) v.copyOf(dim) else v.copyOf()
        var sum = 0.0
        for (x in out) sum += x.toDouble() * x
        val norm = sqrt(sum).toFloat()
        if (norm > 0f) for (i in out.indices) out[i] /= norm
        return out
    }

    fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i].toDouble() * b[i]; na += a[i].toDouble() * a[i]; nb += b[i].toDouble() * b[i] }
        return if (na == 0.0 || nb == 0.0) 0.0 else dot / (sqrt(na) * sqrt(nb))
    }

    // ---- helpers ----

    internal fun JsonObject.num(key: String): Double? = this[key]?.let { (it as? JsonPrimitive)?.doubleOrNull }

    internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

    internal fun JsonElement?.stringOrEmpty(): String =
        if (this == null || this is JsonNull) "" else (this as? JsonPrimitive)?.contentOrNull ?: ""

    /** `{"error":{"message":"..."}}` or `{"error":"..."}` -> message. */
    internal fun JsonObject.errorMessage(): String? {
        val e = this["error"] ?: return null
        if (e is JsonNull) return null
        return (e as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: e.toString()
    }
}

/**
 * Incremental parser for the Server-Sent-Events stream of /v1/chat/completions (`stream: true`).
 * Feed it one line at a time; it returns the text delta carried by that line (null when none).
 * The final chunk carries `timings`; [stats] is filled from it (or from `usage`, or from a local fallback).
 */
class SseChatParser {
    var done = false
        private set
    var finishReason: String? = null
        private set

    /** Stats from the server timings object (preferred) or usage; null until one was seen. */
    var stats: ChatStats? = null
        private set
    private var usageStats: ChatStats? = null

    /** Number of non-empty content deltas, used only when the server sent no timings at all. */
    var contentChunks = 0
        private set

    fun feed(rawLine: String): String? {
        val line = rawLine.trimEnd('\r')
        if (line.isBlank() || line.startsWith(":")) return null
        if (!line.startsWith("data:")) {
            // llama-server may report a stream error as a bare `error: {...}` event
            if (line.startsWith("error:")) throw LlamaServerException(line.removePrefix("error:").trim())
            return null
        }
        val payload = line.removePrefix("data:").trim()
        if (payload == "[DONE]") { done = true; return null }
        if (payload.isEmpty()) return null

        val obj = LlamaJson.json.parseToJsonElement(payload) as? JsonObject ?: return null
        with(LlamaJson) { obj.errorMessage() }?.let { throw LlamaServerException(it) }

        with(LlamaJson) {
            obj["timings"].asObject()?.let { stats = statsFromTimings(it) }
            obj["usage"].asObject()?.let { usageStats = statsFromUsage(it) }
        }
        val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        choice["finish_reason"]?.let { fr -> if (fr !is JsonNull) finishReason = fr.jsonPrimitive.contentOrNull }
        val delta = choice["delta"] as? JsonObject ?: return null
        // `reasoning_content` (thinking) is deliberately ignored: only the answer text is returned.
        val text = with(LlamaJson) { delta["content"].stringOrEmpty() }
        if (text.isEmpty()) return null
        contentChunks++
        return text
    }

    /** Final stats. [localPromptMs]/[localGenMs] are wall-clock fallbacks used only if the server sent neither timings nor usage. */
    fun finalStats(localPromptMs: Double, localGenMs: Double): ChatStats =
        stats ?: usageStats?.copy(promptMs = localPromptMs, genMs = localGenMs)
        ?: ChatStats(0, contentChunks, localPromptMs, localGenMs)
}
