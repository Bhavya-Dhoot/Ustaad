package com.swarmos.app.agent

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

/**
 * A validated chat call: [request] is what the model gets, [maxTokens] already clamped, [promptChars] for the audit log.
 * [tools] is set when the model may call tools: [request] then carries the tool text, and for "required" or a named function the
 * schema that constrains its output. [history] is the caller's messages before they were rendered, to ask again under a constraint.
 */
class AgentChatCall(
    val model: AgentModel, val request: ChatRequest, val stream: Boolean, val promptChars: Int,
    val tools: ToolSpec? = null, val history: List<AgentMessage> = emptyList(),
)

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
    const val MAX_DEPTH = 64
    const val MAX_SCHEMA_BYTES = 16 * 1024

    private val json = Json

    /** The old function-calling fields. Present with a value other than null is a 400: use `tools` and `tool_choice`. */
    private val OLD_TOOL_FIELDS = listOf("functions", "function_call")
    private val ROLES = mapOf("system" to "system", "developer" to "system", "user" to "user", "assistant" to "assistant", "tool" to "tool")

    fun parseChat(body: String): AgentChatCall {
        val o = parseObject(body)
        for (f in OLD_TOOL_FIELDS) if (o.has(f)) throw bad("`$f` is not supported: use `tools` and `tool_choice`.", UNSUPPORTED)
        o.int("n")?.let { n ->
            if (n > 1) throw bad("`n` above 1 is not supported: one answer per request.", UNSUPPORTED)
            if (n < 1) throw bad("`n` must be 1.", INVALID)
        }

        val messagesEl = o["messages"]
        val messageList = messagesEl as? JsonArray ?: throw bad("`messages` is required and must be an array.", MISSING)
        if (messageList.isEmpty()) throw bad("`messages` must not be empty.", INVALID)
        if (messageList.size > MAX_MESSAGES) throw bad("`messages` has ${messageList.size} entries; at most $MAX_MESSAGES are accepted.", LIMIT)
        val messages = messageList.mapIndexed { i, m -> parseMessage(i, m) }
        val tools = parseTools(o)

        val model = parseModel(o)
        val stream = o.bool("stream") ?: false
        val maxTokens = (o.int("max_completion_tokens") ?: o.int("max_tokens"))?.let {
            if (it < 1) throw bad("`max_tokens` must be at least 1.", INVALID)
            it.coerceAtMost(MAX_TOKENS_CAP.toLong()).toInt()
        } ?: DEFAULT_MAX_TOKENS
        val defaults = ChatRequest(emptyList())
        val temperature = o.number("temperature")?.also {
            if (it < 0.0 || it > 2.0) throw bad("`temperature` must be between 0 and 2.", INVALID)
        } ?: defaults.temperature
        val seed = o.int("seed")?.let {
            if (it < Int.MIN_VALUE || it > Int.MAX_VALUE) throw bad("`seed` must fit in 32 bits.", INVALID)
            it.toInt()
        }

        val responseSchema = parseResponseFormat(o["response_format"])
        if (tools.declared && responseSchema != null) throw bad("`response_format` cannot be used together with `tools`: both constrain the answer.", UNSUPPORTED)
        val spec = tools.spec
        // "auto" sends no constraint (the model answers in its own format); "required" and a named function are held to a schema
        val toolSchema = spec?.takeIf { it.choice != ToolChoice.Auto }?.let { AgentTools.schema(it) }
        if (toolSchema != null && toolSchema.length > AgentTools.MAX_SCHEMA_CHARS) {
            throw bad("`tools` need a constraint of ${toolSchema.length} characters; at most ${AgentTools.MAX_SCHEMA_CHARS} are accepted. Shorten the `parameters` schemas.", LIMIT)
        }
        val rendered = AgentTools.render(messages, spec)

        return AgentChatCall(
            model = model,
            request = ChatRequest(
                messages = rendered,
                maxTokens = maxTokens,
                temperature = temperature,
                jsonSchema = toolSchema ?: responseSchema,
                stop = parseStop(o["stop"]),
                seed = seed,
            ),
            stream = stream,
            promptChars = rendered.sumOf { it.content.length },
            tools = spec,
            history = messages,
        )
    }

    fun parseEmbeddings(body: String): AgentEmbedCall {
        val o = parseObject(body)
        val model = parseModel(o)
        val input = o["input"]
        val texts = when {
            input == null || input is JsonNull -> throw bad("`input` is required.", MISSING)
            input is JsonPrimitive && input.isString -> listOf(input.content)
            input is JsonArray -> {
                if (input.isEmpty()) throw bad("`input` must not be empty.", MISSING)
                if (input.size > MAX_EMBED_INPUTS) throw bad("`input` has ${input.size} entries; at most $MAX_EMBED_INPUTS are accepted.", LIMIT)
                input.mapIndexed { i, el ->
                    val p = el as? JsonPrimitive
                    if (p == null || !p.isString) throw bad("`input[$i]` must be a string: token arrays are not supported.", INVALID)
                    p.content
                }
            }
            else -> throw bad("`input` must be a string or a list of strings.", INVALID)
        }
        texts.forEachIndexed { i, t -> if (t.isEmpty()) throw bad("`input[$i]` must not be empty.", INVALID) }
        val format = (o["encoding_format"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (o.has("encoding_format") && format != "float" && format != "base64") {
            throw bad("`encoding_format` must be \"float\" or \"base64\".", INVALID)
        }
        return AgentEmbedCall(model, texts, base64 = format == "base64")
    }

    fun parseFileSearch(body: String): FileSearchCall {
        val o = parseObject(body)
        val query = (o["query"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw bad("`query` is required and must be a string.", MISSING)
        if (query.isBlank()) throw bad("`query` must not be empty.", INVALID)
        if (query.length > AgentFiles.MAX_QUERY) throw bad("`query` is ${query.length} characters; at most ${AgentFiles.MAX_QUERY} are accepted.", LIMIT)
        val scope = when (val el = o["scope"]) {
            null, is JsonNull -> FileScope.PHONE
            is JsonPrimitive -> FileScope.of(el.content).takeIf { el.isString } ?: throw bad("`scope` must be \"phone\" or \"swarm\".", INVALID)
            else -> throw bad("`scope` must be \"phone\" or \"swarm\".", INVALID)
        }
        val max = o.int("max_results") ?: AgentFiles.DEFAULT_RESULTS.toLong()
        if (max < 1 || max > AgentFiles.MAX_RESULTS) throw bad("`max_results` must be 1 to ${AgentFiles.MAX_RESULTS}.", LIMIT)
        return FileSearchCall(query, scope, max.toInt())
    }

    // ---- pieces -------------------------------------------------------------------------------------------

    private fun parseObject(body: String): JsonObject {
        if (nestingDepth(body) > MAX_DEPTH) throw bad("The JSON in the request body is nested more than $MAX_DEPTH levels deep.", INVALID_JSON)
        val el = try {
            json.parseToJsonElement(body)
        } catch (e: SerializationException) {
            throw bad("The request body is not valid JSON.", INVALID_JSON)
        } catch (e: IllegalArgumentException) {
            throw bad("The request body is not valid JSON.", INVALID_JSON)
        }
        return el as? JsonObject ?: throw bad("The request body must be a JSON object.", INVALID_JSON)
    }

    /** How deep brackets nest in [text], strings skipped. The JSON parser recurses, so a megabyte of '[' must not reach it. */
    internal fun nestingDepth(text: String): Int {
        var depth = 0
        var max = 0
        var inString = false
        var escaped = false
        for (ch in text) {
            if (inString) {
                if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') inString = false
            } else when (ch) {
                '"' -> inString = true
                '[', '{' -> if (++depth > max) max = depth
                ']', '}' -> depth--
            }
        }
        return max
    }

    private fun parseModel(o: JsonObject): AgentModel {
        val el = o["model"]
        val id = (el as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw bad("`model` is required. Use \"${AgentModel.LOCAL.id}\" or \"${AgentModel.SWARM.id}\".", MISSING)
        return AgentModel.of(id)
            ?: throw AgentRefusal(404, "The model `$id` does not exist. Use \"${AgentModel.LOCAL.id}\" or \"${AgentModel.SWARM.id}\".", "model_not_found")
    }

    private fun parseMessage(i: Int, el: JsonElement): AgentMessage {
        val m = el as? JsonObject ?: throw bad("`messages[$i]` must be an object.", INVALID)
        val roleName = (m["role"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (roleName == "function") throw bad("`messages[$i].role` \"function\" is not supported: use \"tool\".", UNSUPPORTED)
        val role = ROLES[roleName] ?: throw bad("`messages[$i].role` must be system, user, assistant or tool.", INVALID)
        if (m.has("function_call")) throw bad("`messages[$i].function_call` is not supported: use `tool_calls`.", UNSUPPORTED)
        if (m.has("tool_calls") && role != "assistant") throw bad("`messages[$i].tool_calls` is only valid on an assistant message.", INVALID)
        val toolCalls = if (m.has("tool_calls")) parseToolCalls(i, m.getValue("tool_calls")) else emptyList()
        val toolCallId = (m["tool_call_id"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content
        if (role == "tool" && toolCallId == null) throw bad("`messages[$i].tool_call_id` is required on a tool message.", MISSING)
        val content = when (val c = m["content"]) {
            // an assistant message that only calls tools has no words (JsonNull is a JsonPrimitive: it has to come first)
            null, is JsonNull -> if (toolCalls.isNotEmpty()) "" else throw bad("`messages[$i].content` is required: a string or a list of text parts.", INVALID)
            is JsonPrimitive -> if (c.isString) c.content else throw bad("`messages[$i].content` must be a string or a list of text parts.", INVALID)
            is JsonArray -> c.mapIndexed { j, part -> textOf(i, j, part) }.joinToString("\n")
            else -> throw bad("`messages[$i].content` is required: a string or a list of text parts.", INVALID)
        }
        return AgentMessage(role, content, toolCalls, toolCallId)
    }

    /** `[{"id","type":"function","function":{"name","arguments"}}]` of an assistant message; `arguments` may be JSON text or an object. */
    private fun parseToolCalls(i: Int, el: JsonElement): List<PastToolCall> {
        val list = el as? JsonArray ?: throw bad("`messages[$i].tool_calls` must be a list.", INVALID)
        return list.mapIndexed { j, c ->
            val where = "messages[$i].tool_calls[$j]"
            val o = c as? JsonObject ?: throw bad("`$where` must be an object.", INVALID)
            val id = (o["id"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content
                ?: throw bad("`$where.id` is required.", MISSING)
            val type = (o["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (type != null && type != "function") throw bad("`$where.type` must be \"function\".", INVALID)
            val f = o["function"] as? JsonObject ?: throw bad("`$where.function` is required.", MISSING)
            val name = (f["name"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content
                ?: throw bad("`$where.function.name` is required.", MISSING)
            val args = when (val a = f["arguments"]) {
                null, is JsonNull -> "{}"
                is JsonPrimitive -> if (a.isString) a.content else throw bad("`$where.function.arguments` must be JSON text.", INVALID)
                is JsonObject -> a.toString()
                else -> throw bad("`$where.function.arguments` must be JSON text.", INVALID)
            }
            PastToolCall(id, name, args)
        }
    }

    // ---- tools ---------------------------------------------------------------------------------------------

    /** [declared]: the request carried tools (even with `tool_choice` "none"); [spec]: the model may call them. */
    private class ToolsParsed(val declared: Boolean, val spec: ToolSpec?)

    private fun parseTools(o: JsonObject): ToolsParsed {
        val choiceEl = o["tool_choice"]?.takeIf { it !is JsonNull }
        val none = choiceEl is JsonPrimitive && choiceEl.isString && choiceEl.content == "none"
        val choice: ToolChoice? = if (none) null else parseToolChoice(choiceEl)
        val parallel = o.bool("parallel_tool_calls") ?: true
        val toolsEl = o["tools"]?.takeIf { it !is JsonNull }
        if (toolsEl == null) {
            if (choice != null && choice != ToolChoice.Auto) throw bad("`tool_choice` needs `tools`: there is nothing to call.", INVALID)
            return ToolsParsed(false, null)
        }
        val list = toolsEl as? JsonArray ?: throw bad("`tools` must be a list.", INVALID)
        if (list.isEmpty()) throw bad("`tools` must not be empty.", INVALID)
        if (list.size > AgentTools.MAX_TOOLS) throw bad("`tools` has ${list.size} entries; at most ${AgentTools.MAX_TOOLS} are accepted.", LIMIT)
        val bytes = json.encodeToString(JsonArray.serializer(), list).toByteArray(Charsets.UTF_8).size
        if (bytes > AgentTools.MAX_TOOLS_BYTES) throw bad("`tools` is $bytes bytes; at most ${AgentTools.MAX_TOOLS_BYTES} are accepted.", LIMIT)
        val tools = list.mapIndexed { i, t -> parseTool(i, t) }
        val seen = HashSet<String>()
        tools.forEachIndexed { i, t -> if (!seen.add(t.name)) throw bad("`tools[$i].function.name` \"${t.name}\" is declared twice.", INVALID) }
        if (choice is ToolChoice.Named && tools.none { it.name == choice.name }) {
            throw bad("`tool_choice.function.name` \"${choice.name}\" is not one of the declared tools.", INVALID)
        }
        return ToolsParsed(true, choice?.let { ToolSpec(tools, it, parallel) })
    }

    private fun parseToolChoice(el: JsonElement?): ToolChoice {
        val shape = "`tool_choice` must be \"auto\", \"none\", \"required\" or {\"type\":\"function\",\"function\":{\"name\":...}}."
        return when {
            el == null -> ToolChoice.Auto
            el is JsonPrimitive && el.isString -> when (el.content) {
                "auto" -> ToolChoice.Auto
                "required" -> ToolChoice.Required
                else -> throw bad(shape, INVALID)
            }
            el is JsonObject -> {
                val type = (el["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val name = ((el["function"] as? JsonObject)?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (type != "function" || name == null) throw bad(shape, INVALID)
                ToolChoice.Named(name)
            }
            else -> throw bad(shape, INVALID)
        }
    }

    private fun parseTool(i: Int, el: JsonElement): AgentTool {
        val t = el as? JsonObject ?: throw bad("`tools[$i]` must be an object.", INVALID)
        val type = (t["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (type != "function") throw bad("`tools[$i].type` ${type?.let { "\"$it\" " } ?: ""}is not supported: only \"function\" tools.", UNSUPPORTED)
        val f = t["function"] as? JsonObject ?: throw bad("`tools[$i].function` is required and must be an object.", MISSING)
        val name = (f["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (name == null || !AgentTools.NAME.matches(name)) throw bad("`tools[$i].function.name` must be 1 to 64 letters, digits, _ or -.", INVALID)
        val description = when (val d = f["description"]) {
            null, is JsonNull -> ""
            is JsonPrimitive -> if (d.isString) d.content else throw bad("`tools[$i].function.description` must be a string.", INVALID)
            else -> throw bad("`tools[$i].function.description` must be a string.", INVALID)
        }
        if (description.length > AgentTools.MAX_DESCRIPTION) {
            throw bad("`tools[$i].function.description` is ${description.length} characters; at most ${AgentTools.MAX_DESCRIPTION} are accepted.", LIMIT)
        }
        val parameters = when (val p = f["parameters"]) {
            null, is JsonNull -> AgentTools.NO_PARAMETERS
            is JsonObject -> p
            else -> throw bad("`tools[$i].function.parameters` must be a JSON Schema object.", INVALID)
        }
        return AgentTool(name, description, parameters)
    }

    private fun textOf(i: Int, j: Int, part: JsonElement): String {
        val p = part as? JsonObject ?: throw bad("`messages[$i].content[$j]` must be an object.", INVALID)
        val type = (p["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (type != "text") throw bad("`messages[$i].content[$j]` is a ${type ?: "typeless"} part: Ustaad takes text parts only (no images or audio).", UNSUPPORTED_CONTENT)
        return (p["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw bad("`messages[$i].content[$j].text` must be a string.", INVALID)
    }

    private fun parseStop(el: JsonElement?): List<String> {
        val list = when {
            el == null || el is JsonNull -> return emptyList()
            el is JsonPrimitive && el.isString -> listOf(el.content)
            el is JsonArray -> el.map { s ->
                (s as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw bad("`stop` must be a string or a list of strings.", INVALID)
            }
            else -> throw bad("`stop` must be a string or a list of strings.", INVALID)
        }
        if (list.size > MAX_STOP) throw bad("`stop` has ${list.size} entries; at most $MAX_STOP are accepted.", LIMIT)
        return list.filter { it.isNotEmpty() }
    }

    /** `{"type":"json_schema","json_schema":{"name":...,"schema":{...}}}` gives the schema text; `text` gives null; anything else is refused. */
    private fun parseResponseFormat(el: JsonElement?): String? {
        if (el == null || el is JsonNull) return null
        val rf = el as? JsonObject ?: throw bad("`response_format` must be an object.", INVALID)
        return when (val type = (rf["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
            "text" -> null
            "json_schema" -> {
                val schema = (rf["json_schema"] as? JsonObject)?.get("schema") as? JsonObject
                    ?: throw bad("`response_format.json_schema.schema` is required and must be an object.", INVALID)
                json.encodeToString(JsonObject.serializer(), schema).also {
                    if (it.toByteArray(Charsets.UTF_8).size > MAX_SCHEMA_BYTES) {
                        throw bad("`response_format.json_schema.schema` is over $MAX_SCHEMA_BYTES bytes.", LIMIT)
                    }
                }
            }
            else -> throw bad("`response_format.type` ${type?.let { "\"$it\" " } ?: ""}is not supported: use \"json_schema\" or \"text\".", UNSUPPORTED)
        }
    }

    private fun bad(message: String, code: String) = AgentRefusal(400, message, code)

    // error codes of the 400 answers
    private const val INVALID_JSON = "invalid_json"
    private const val MISSING = "missing_required_parameter"
    private const val INVALID = "invalid_value"
    private const val UNSUPPORTED = "unsupported_parameter"
    private const val UNSUPPORTED_CONTENT = "unsupported_content"
    private const val LIMIT = "limit_exceeded"

    // ---- typed reads of one field ------------------------------------------------------------------------------

    private fun JsonObject.has(name: String) = this[name].let { it != null && it !is JsonNull }

    /** An integer field, null when absent or null; anything else that is not a whole number is a 400 naming the field. */
    private fun JsonObject.int(name: String): Long? {
        val el = this[name]
        if (el == null || el is JsonNull) return null
        val p = el as? JsonPrimitive
        return p?.takeIf { !it.isString }?.content?.toLongOrNull() ?: throw bad("`$name` must be an integer.", INVALID)
    }

    private fun JsonObject.number(name: String): Double? {
        val el = this[name]
        if (el == null || el is JsonNull) return null
        val p = el as? JsonPrimitive
        return p?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw bad("`$name` must be a number.", INVALID)
    }

    private fun JsonObject.bool(name: String): Boolean? {
        val el = this[name]
        if (el == null || el is JsonNull) return null
        val p = el as? JsonPrimitive
        return p?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull() ?: throw bad("`$name` must be true or false.", INVALID)
    }
}
