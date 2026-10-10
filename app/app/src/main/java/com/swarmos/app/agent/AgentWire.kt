package com.swarmos.app.agent

import com.swarmos.app.core.SwarmJson
import com.swarmos.app.llm.ChatStats
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** What the endpoint sends back: OpenAI shapes, built as plain JSON so tests can read every field. */
object AgentWire {
    const val DONE = "data: [DONE]\n\n"
    const val PING = ": ping\n\n"

    /** `{"error":{"message","type","code"}}`. */
    fun error(r: AgentRefusal): JsonObject = error(r.message ?: "", r.type, r.code)

    fun error(message: String, type: String, code: String): JsonObject = buildJsonObject {
        putJsonObject("error") {
            put("message", message)
            put("type", type)
            put("code", code)
        }
    }

    fun models(createdSec: Long): JsonObject = buildJsonObject {
        put("object", "list")
        putJsonArray("data") {
            for (m in AgentModel.entries) add(buildJsonObject {
                put("id", m.id)
                put("object", "model")
                put("created", createdSec)
                put("owned_by", "ustaad")
            })
        }
    }

    fun fingerprint(phone: String) = "ustaad:$phone"

    /** "length" when the model used its whole budget, else "stop". */
    fun finishReason(stats: ChatStats?, maxTokens: Int) = if (stats != null && stats.completionTokens >= maxTokens) "length" else "stop"

    fun usage(stats: ChatStats?): JsonObject = buildJsonObject {
        val p = stats?.promptTokens ?: 0
        val c = stats?.completionTokens ?: 0
        put("prompt_tokens", p)
        put("completion_tokens", c)
        put("total_tokens", p + c)
    }

    fun chatCompletion(id: String, createdSec: Long, model: AgentModel, fingerprint: String, text: String, stats: ChatStats?, maxTokens: Int): JsonObject =
        buildJsonObject {
            put("id", id)
            put("object", "chat.completion")
            put("created", createdSec)
            put("model", model.id)
            put("system_fingerprint", fingerprint)
            putJsonArray("choices") {
                add(buildJsonObject {
                    put("index", 0)
                    putJsonObject("message") {
                        put("role", "assistant")
                        put("content", text)
                    }
                    put("finish_reason", finishReason(stats, maxTokens))
                })
            }
            put("usage", usage(stats))
        }

    /** One `chat.completion.chunk`. [delta] is `{}` on the last chunk; [finish] and [usage] are set on that chunk only. */
    fun chunk(id: String, createdSec: Long, model: AgentModel, fingerprint: String, delta: JsonObject, finish: String? = null, usage: JsonObject? = null): JsonObject =
        buildJsonObject {
            put("id", id)
            put("object", "chat.completion.chunk")
            put("created", createdSec)
            put("model", model.id)
            put("system_fingerprint", fingerprint)
            putJsonArray("choices") {
                add(buildJsonObject {
                    put("index", 0)
                    put("delta", delta)
                    put("finish_reason", finish?.let { JsonPrimitive(it) } ?: JsonNull)
                })
            }
            if (usage != null) put("usage", usage)
        }

    fun roleDelta(): JsonObject = buildJsonObject {
        put("role", "assistant")
        put("content", "")
    }

    fun textDelta(text: String): JsonObject = buildJsonObject { put("content", text) }

    /** One server-sent event carrying [obj]. */
    fun event(obj: JsonElement): String = "data: ${SwarmJson.encodeToString(JsonElement.serializer(), obj)}\n\n"

    /** `/v1/embeddings` answer. Vectors are float arrays, or base64 of little-endian float32 when [base64]. Token counts are not measured: usage is 0. */
    fun embeddings(model: AgentModel, vectors: List<FloatArray>, base64: Boolean): JsonObject = buildJsonObject {
        put("object", "list")
        putJsonArray("data") {
            vectors.forEachIndexed { i, v ->
                add(buildJsonObject {
                    put("object", "embedding")
                    put("index", i)
                    if (base64) put("embedding", base64Of(v)) else put("embedding", JsonArray(v.map { JsonPrimitive(it) }))
                })
            }
        }
        put("model", model.id)
        putJsonObject("usage") {
            put("prompt_tokens", 0)
            put("total_tokens", 0)
        }
    }

    fun base64Of(v: FloatArray): String {
        val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (x in v) buf.putFloat(x)
        return Base64.getEncoder().encodeToString(buf.array())
    }
}
