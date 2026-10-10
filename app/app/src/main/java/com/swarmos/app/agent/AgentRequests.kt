package com.swarmos.app.agent

import com.swarmos.app.llm.ChatMessage
import com.swarmos.app.llm.ChatRequest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The models the endpoint offers. */
enum class AgentModel(val id: String) {
    LOCAL("ustaad-local"),
    SWARM("ustaad-swarm"),
    ;

    companion object {
        fun of(id: String): AgentModel? = entries.firstOrNull { it.id == id }
    }
}

/** A validated chat call: [request] is what the model gets, [maxTokens] already clamped, [promptChars] for the audit log. */
class AgentChatCall(val model: AgentModel, val request: ChatRequest, val stream: Boolean, val promptChars: Int)

/** A validated embeddings call. [base64] asks for the vectors as base64 little-endian float32, as the OpenAI Python client does by default. */
class AgentEmbedCall(val model: AgentModel, val texts: List<String>, val base64: Boolean) {
    val chars: Int get() = texts.sumOf { it.length }
}

/**
 * OpenAI request JSON to our own types, with every refusal of the contract. Each failure is an [AgentRefusal] that names
 * the field. Fields not listed here (top_p, user, stream_options, ...) are accepted and ignored.
 */
object AgentRequests {
    const val MAX_BODY_BYTES = 1L shl 20
    const val MAX_MESSAGES = 64
    const val DEFAULT_MAX_TOKENS = 256
    const val MAX_TOKENS_CAP = 1024
    const val MAX_STOP = 4
    const val MAX_EMBED_INPUTS = 64

    private val json = Json

    /** Fields that would switch on tool calling. Present with a value other than null is a 400. */
    private val TOOL_FIELDS = listOf("tools", "tool_choice", "functions", "function_call")
    private val ROLES = mapOf("system" to "system", "developer" to "system", "user" to "user", "assistant" to "assistant")

    fun parseChat(body: String): AgentChatCall {
        val o = parseObject(body)
        for (f in TOOL_FIELDS) if (o.has(f)) throw bad("`$f` is not supported: Ustaad does not do tool calling.", f)
        o.int("n")?.let { n ->
            if (n > 1) throw bad("`n` above 1 is not supported: one answer per request.", "n")
            if (n < 1) throw bad("`n` must be 1.", "n")
        }

        val messagesEl = o["messages"]
        val messageList = messagesEl as? JsonArray ?: throw bad("`messages` is required and must be an array.", "messages_required")
        if (messageList.isEmpty()) throw bad("`messages` must not be empty.", "messages_required")
        if (messageList.size > MAX_MESSAGES) throw bad("`messages` has ${messageList.size} entries; at most $MAX_MESSAGES are accepted.", "too_many_messages")
        val messages = messageList.mapIndexed { i, m -> parseMessage(i, m) }

        val model = parseModel(o)
        val stream = o.bool("stream") ?: false
        val maxTokens = (o.int("max_completion_tokens") ?: o.int("max_tokens"))?.let {
            if (it < 1) throw bad("`max_tokens` must be at least 1.", "max_tokens")
            it.coerceAtMost(MAX_TOKENS_CAP.toLong()).toInt()
        } ?: DEFAULT_MAX_TOKENS
        val defaults = ChatRequest(emptyList())
        val temperature = o.number("temperature")?.also {
            if (it < 0.0 || it > 2.0) throw bad("`temperature` must be between 0 and 2.", "temperature")
        } ?: defaults.temperature
        val seed = o.int("seed")?.let {
            if (it < Int.MIN_VALUE || it > Int.MAX_VALUE) throw bad("`seed` must fit in 32 bits.", "seed")
            it.toInt()
        }

        return AgentChatCall(
            model = model,
            request = ChatRequest(
                messages = messages,
                maxTokens = maxTokens,
                temperature = temperature,
                jsonSchema = parseResponseFormat(o["response_format"]),
                stop = parseStop(o["stop"]),
                seed = seed,
            ),
            stream = stream,
            promptChars = messages.sumOf { it.content.length },
        )
    }

    fun parseEmbeddings(body: String): AgentEmbedCall {
        val o = parseObject(body)
        val model = parseModel(o)
        val input = o["input"]
        val texts = when {
            input == null || input is JsonNull -> throw bad("`input` is required.", "input_required")
            input is JsonPrimitive && input.isString -> listOf(input.content)
            input is JsonArray -> {
                if (input.isEmpty()) throw bad("`input` must not be empty.", "input_required")
                if (input.size > MAX_EMBED_INPUTS) throw bad("`input` has ${input.size} entries; at most $MAX_EMBED_INPUTS are accepted.", "too_many_inputs")
                input.mapIndexed { i, el ->
                    val p = el as? JsonPrimitive
                    if (p == null || !p.isString) throw bad("`input[$i]` must be a string: token arrays are not supported.", "input_not_text")
                    p.content
                }
            }
            else -> throw bad("`input` must be a string or a list of strings.", "input_not_text")
        }
        texts.forEachIndexed { i, t -> if (t.isEmpty()) throw bad("`input[$i]` must not be empty.", "input_empty") }
        val format = (o["encoding_format"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (o.has("encoding_format") && format != "float" && format != "base64") {
            throw bad("`encoding_format` must be \"float\" or \"base64\".", "encoding_format")
        }
        return AgentEmbedCall(model, texts, base64 = format == "base64")
    }

    // ---- pieces -------------------------------------------------------------------------------------------

    private fun parseObject(body: String): JsonObject {
        val el = try {
            json.parseToJsonElement(body)
        } catch (e: SerializationException) {
            throw bad("The request body is not valid JSON.", "invalid_json")
        } catch (e: IllegalArgumentException) {
            throw bad("The request body is not valid JSON.", "invalid_json")
        }
        return el as? JsonObject ?: throw bad("The request body must be a JSON object.", "invalid_json")
    }

    private fun parseModel(o: JsonObject): AgentModel {
        val el = o["model"]
        val id = (el as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw bad("`model` is required. Use \"${AgentModel.LOCAL.id}\" or \"${AgentModel.SWARM.id}\".", "model_required")
        return AgentModel.of(id)
            ?: throw AgentRefusal(404, "The model `$id` does not exist. Use \"${AgentModel.LOCAL.id}\" or \"${AgentModel.SWARM.id}\".", "model_not_found")
    }

    private fun parseMessage(i: Int, el: JsonElement): ChatMessage {
        val m = el as? JsonObject ?: throw bad("`messages[$i]` must be an object.", "invalid_message")
        val roleName = (m["role"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (roleName == "tool" || roleName == "function") throw bad("`messages[$i].role` \"$roleName\" is not supported: Ustaad does not do tool calling.", "tools_unsupported")
        val role = ROLES[roleName] ?: throw bad("`messages[$i].role` must be system, user or assistant.", "invalid_role")
        for (f in listOf("tool_calls", "function_call")) {
            if (m.has(f)) throw bad("`messages[$i].$f` is not supported: Ustaad does not do tool calling.", "tools_unsupported")
        }
        val content = when (val c = m["content"]) {
            is JsonPrimitive -> if (c.isString) c.content else throw bad("`messages[$i].content` must be a string or a list of text parts.", "invalid_content")
            is JsonArray -> c.mapIndexed { j, part -> textOf(i, j, part) }.joinToString("\n")
            else -> throw bad("`messages[$i].content` is required: a string or a list of text parts.", "invalid_content")
        }
        return ChatMessage(role, content)
    }

    private fun textOf(i: Int, j: Int, part: JsonElement): String {
        val p = part as? JsonObject ?: throw bad("`messages[$i].content[$j]` must be an object.", "invalid_content")
        val type = (p["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (type != "text") throw bad("`messages[$i].content[$j]` is a ${type ?: "typeless"} part: Ustaad takes text parts only (no images or audio).", "unsupported_content")
        return (p["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw bad("`messages[$i].content[$j].text` must be a string.", "invalid_content")
    }

    private fun parseStop(el: JsonElement?): List<String> {
        val list = when {
            el == null || el is JsonNull -> return emptyList()
            el is JsonPrimitive && el.isString -> listOf(el.content)
            el is JsonArray -> el.map { s ->
                (s as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw bad("`stop` must be a string or a list of strings.", "stop")
            }
            else -> throw bad("`stop` must be a string or a list of strings.", "stop")
        }
        if (list.size > MAX_STOP) throw bad("`stop` has ${list.size} entries; at most $MAX_STOP are accepted.", "stop")
        return list.filter { it.isNotEmpty() }
    }

    /** `{"type":"json_schema","json_schema":{"name":...,"schema":{...}}}` gives the schema text; `text` gives null; anything else is refused. */
    private fun parseResponseFormat(el: JsonElement?): String? {
        if (el == null || el is JsonNull) return null
        val rf = el as? JsonObject ?: throw bad("`response_format` must be an object.", "response_format")
        return when (val type = (rf["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
            "text" -> null
            "json_schema" -> {
                val schema = (rf["json_schema"] as? JsonObject)?.get("schema") as? JsonObject
                    ?: throw bad("`response_format.json_schema.schema` is required and must be an object.", "response_format")
                json.encodeToString(JsonObject.serializer(), schema)
            }
            else -> throw bad("`response_format.type` ${type?.let { "\"$it\" " } ?: ""}is not supported: use \"json_schema\" or \"text\".", "response_format")
        }
    }

    private fun bad(message: String, code: String) = AgentRefusal(400, message, code)

    // ---- typed reads of one field ------------------------------------------------------------------------------

    private fun JsonObject.has(name: String) = this[name].let { it != null && it !is JsonNull }

    /** An integer field, null when absent or null; anything else that is not a whole number is a 400 naming the field. */
    private fun JsonObject.int(name: String): Long? {
        val el = this[name]
        if (el == null || el is JsonNull) return null
        val p = el as? JsonPrimitive
        return p?.takeIf { !it.isString }?.content?.toLongOrNull() ?: throw bad("`$name` must be an integer.", name)
    }

    private fun JsonObject.number(name: String): Double? {
        val el = this[name]
        if (el == null || el is JsonNull) return null
        val p = el as? JsonPrimitive
        return p?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw bad("`$name` must be a number.", name)
    }

    private fun JsonObject.bool(name: String): Boolean? {
        val el = this[name]
        if (el == null || el is JsonNull) return null
        val p = el as? JsonPrimitive
        return p?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull() ?: throw bad("`$name` must be true or false.", name)
    }
}
