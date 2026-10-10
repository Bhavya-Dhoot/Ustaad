package com.swarmos.app.agent

import com.swarmos.app.link.NodeHttpException
import com.swarmos.app.llm.ChatMessage
import com.swarmos.app.llm.ChatRequest
import com.swarmos.app.llm.LlamaServerException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

/** One function the calling app declared. [parameters] is its JSON Schema. */
class AgentTool(val name: String, val description: String, val parameters: JsonObject)

/** What the model may do with the tools: [Auto] answer or call, [Required] call, [Named] call that one. `none` never gets this far: it is no [ToolSpec]. */
sealed interface ToolChoice {
    data object Auto : ToolChoice
    data object Required : ToolChoice
    data class Named(val name: String) : ToolChoice
}

/** The tools of one call as the model gets them. [parallel] false allows one call per answer. */
class ToolSpec(val tools: List<AgentTool>, val choice: ToolChoice, val parallel: Boolean) {
    val maxCalls: Int get() = if (parallel) AgentTools.MAX_PARALLEL_CALLS else 1
}

/** A tool call an earlier assistant message made, as the caller sent it back. [arguments] is JSON text. */
class PastToolCall(val id: String, val name: String, val arguments: String)

/** A message as the caller sent it, before it becomes prompt text. [role] is system, user, assistant or tool. */
class AgentMessage(val role: String, val content: String, val toolCalls: List<PastToolCall> = emptyList(), val toolCallId: String? = null)

/** A call the model made. [arguments] is JSON text that parsed. */
class ToolCallOut(val id: String, val name: String, val arguments: String)

/** What the model said: words, or calls. */
sealed interface ToolReply {
    class Content(val text: String) : ToolReply
    class Calls(val calls: List<ToolCallOut>) : ToolReply
}

/** What reading the model's own tool-call format found: [Words], [Calls] that passed the check, or [Invalid] (a call failed it; [tool] is the declared tool it named, if it got that far). */
sealed interface NativeRead {
    class Words(val text: String) : NativeRead
    class Calls(val calls: List<ToolCallOut>) : NativeRead
    class Invalid(val tool: String?) : NativeRead
}

/**
 * Tool calling without the chat template's tool support. With `tool_choice` "auto" the tools are described in the model's own
 * format (`<tools>` in the system message, `<tool_call>` blocks back), nothing constrains the output, and the calls it makes are
 * checked and, if one fails, asked for again once under a constraint. With "required" or a named function the output is held (the
 * model server's JSON-schema path) to `{"tool_calls": [{"name", "arguments"}]}`. The endpoint turns either into the OpenAI answer.
 * Everything here is pure, so the prompt, the schema and the parsing are tested without a model.
 */
object AgentTools {
    const val MAX_TOOLS = 16
    const val MAX_TOOLS_BYTES = 16 * 1024
    const val MAX_DESCRIPTION = 1024
    const val MAX_PARALLEL_CALLS = 4

    /** The largest constraint a phone of the swarm accepts (SchedLimits.MAX_SCHEMA_CHARS); a bigger one would fail only on `ustaad-swarm`. */
    const val MAX_SCHEMA_CHARS = 16_000

    val NAME = Regex("^[A-Za-z0-9_-]{1,64}$")
    val NO_PARAMETERS: JsonObject = buildJsonObject { put("type", "object"); putJsonObject("properties") { } }

    private val json = Json

    // ---- the prompt -----------------------------------------------------------------------------------------

    /** The one place the tool text lives. "auto" gets the model's own format, "required" and a named function the constrained one. */
    fun systemText(spec: ToolSpec): String = if (spec.choice == ToolChoice.Auto) nativeText(spec) else constrainedText(spec)

    /**
     * The model's trained tool format. Descriptions and schemas go in as JSON, and every `<` inside them is written `<`, so a
     * description cannot close `</tools>` or open a `<tool_call>`.
     */
    private fun nativeText(spec: ToolSpec): String = buildString {
        appendLine("# Tools")
        appendLine()
        appendLine("You may call one or more functions to assist with the user query.")
        appendLine()
        appendLine("You are provided with function signatures within <tools></tools> XML tags:")
        appendLine("<tools>")
        for (t in spec.tools) {
            appendLine(spaced(buildJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", t.name)
                    put("description", t.description)
                    put("parameters", t.parameters)
                }
            }))
        }
        appendLine("</tools>")
        appendLine()
        appendLine("For each function call, return a json object with function name and arguments within <tool_call></tool_call> XML tags:")
        appendLine("<tool_call>")
        appendLine("""{"name": <function-name>, "arguments": <args-json-object>}""")
        append("</tool_call>")
    }

    /** For "required" and a named function: the output is a JSON object held to a schema, so the text says which. */
    private fun constrainedText(spec: ToolSpec): String = buildString {
        appendLine("You can call tools. Reply with one JSON object and nothing else.")
        appendLine("""To call tools: {"tool_calls":[{"name":"<tool name>","arguments":{...}}]}. Call at most ${spec.maxCalls} in one reply.""")
        when (val c = spec.choice) {
            ToolChoice.Required -> appendLine("You must call a tool.")
            is ToolChoice.Named -> appendLine("You must call `${c.name}`.")
            ToolChoice.Auto -> error("auto is not constrained")
        }
        appendLine("Tools:")
        for (t in offered(spec)) {
            append("- ").append(t.name).append(": ").append(t.description.ifBlank { "(no description)" })
            append(" Arguments, as a JSON Schema: ").appendLine(t.parameters.toString())
        }
    }.trimEnd()

    /** JSON the way the model's template prints it (`, ` and `: `), with no raw `<`. */
    internal fun spaced(el: JsonElement): String = spacedRaw(el).replace("<", "\\u003c")

    private fun spacedRaw(el: JsonElement): String = when (el) {
        is JsonObject -> el.entries.joinToString(", ", "{", "}") { (k, v) -> JsonPrimitive(k).toString() + ": " + spacedRaw(v) }
        is JsonArray -> el.joinToString(", ", "[", "]") { spacedRaw(it) }
        else -> el.toString()
    }

    /** Text from outside the owner's request (a tool result, a past answer) cannot close or open the blocks the prompt is built from. */
    private val BLOCK_TAG = Regex("</?\\s*(tool_call|tool_response|tools)\\s*>", RegexOption.IGNORE_CASE)

    internal fun neutralize(text: String): String = BLOCK_TAG.replace(text) { "&lt;" + it.value.substring(1) }

    /** The tools the model may call: all of them, or the one a named choice picks. */
    private fun offered(spec: ToolSpec): List<AgentTool> = when (val c = spec.choice) {
        is ToolChoice.Named -> spec.tools.filter { it.name == c.name }
        else -> spec.tools
    }

    /**
     * The caller's messages as chat messages; with [spec], the tool text joins the first system message, or becomes one. Results of
     * tools (one or several in a row) become one user message, because chat templates differ on a `tool` role.
     * With "auto" the past turns are in the model's own format: a call the assistant made is `<tool_call>` blocks, results are
     * `<tool_response>` blocks in the order they came. Otherwise a past call is the JSON the constrained model is held to, and a
     * result says which call it answers.
     */
    fun render(messages: List<AgentMessage>, spec: ToolSpec?): List<ChatMessage> {
        val native = spec?.choice == ToolChoice.Auto
        val names = HashMap<String, String>()
        val out = ArrayList<ChatMessage>()
        var results = ArrayList<String>()
        fun flushResults() {
            if (results.isNotEmpty()) out += ChatMessage("user", results.joinToString(if (native) "\n" else "\n\n"))
            results = ArrayList()
        }
        for (m in messages) {
            if (m.role == "tool") {
                val id = m.toolCallId.orEmpty()
                results += if (native) {
                    "<tool_response>\n${neutralize(m.content)}\n</tool_response>"
                } else {
                    (names[id]?.let { "Result of `$it` (call $id)" } ?: "Result of call $id") + ":\n${m.content}"
                }
                continue
            }
            flushResults()
            if (m.role == "assistant" && m.toolCalls.isNotEmpty()) {
                m.toolCalls.forEach { names[it.id] = it.name }
                val words = if (m.content.isBlank()) "" else (if (native) neutralize(m.content) else m.content) + "\n"
                out += ChatMessage("assistant", words + if (native) pastBlocks(m.toolCalls) else pastCalls(m.toolCalls))
            } else {
                out += ChatMessage(m.role, if (native && m.role == "assistant") neutralize(m.content) else m.content)
            }
        }
        flushResults()
        if (spec == null) return out
        val text = systemText(spec)
        val first = out.firstOrNull()
        if (first != null && first.role == "system") out[0] = ChatMessage("system", first.content + "\n\n" + text) else out.add(0, ChatMessage("system", text))
        return out
    }

    private fun argumentsOf(text: String): JsonElement = try { json.parseToJsonElement(text) } catch (e: IllegalArgumentException) { JsonPrimitive(text) }

    private fun pastCalls(calls: List<PastToolCall>): String = buildJsonObject {
        putJsonArray("tool_calls") {
            for (c in calls) add(buildJsonObject {
                put("name", c.name)
                put("arguments", argumentsOf(c.arguments))
            })
        }
    }.toString()

    private fun pastBlocks(calls: List<PastToolCall>): String = calls.joinToString("\n") { c ->
        "<tool_call>\n" + spaced(buildJsonObject { put("name", c.name); put("arguments", argumentsOf(c.arguments)) }) + "\n</tool_call>"
    }

    // ---- the constraint -------------------------------------------------------------------------------------

    /**
     * The JSON Schema the output is held to when a call is forced: `required` allows calls to any tool, a named function calls to that
     * tool only ("auto" is not constrained; a repair asks again under one of these). Names are constants and each call's arguments follow
     * that tool's own `parameters`, so arguments are valid JSON for the declared schema.
     */
    fun schema(spec: ToolSpec): String {
        require(spec.choice != ToolChoice.Auto) { "auto is not constrained" }
        val shapes = offered(spec).map { t ->
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("name") { put("const", t.name) }
                    put("arguments", t.parameters)
                }
                putJsonArray("required") { add(JsonPrimitive("name")); add(JsonPrimitive("arguments")) }
                put("additionalProperties", false)
            }
        }
        val calls = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("tool_calls") {
                    put("type", "array")
                    put("minItems", 1)
                    put("maxItems", spec.maxCalls)
                    put("items", if (shapes.size == 1) shapes.single() else buildJsonObject { put("anyOf", JsonArray(shapes)) })
                }
            }
            putJsonArray("required") { add(JsonPrimitive("tool_calls")) }
            put("additionalProperties", false)
        }
        return calls.toString()
    }

    // ---- the answer -----------------------------------------------------------------------------------------

    private const val OPEN = "<tool_call>"
    private const val CLOSE = "</tool_call>"

    /** The text of each `<tool_call>` block, trimmed. A block that is not closed ends at the next block or at the end of the text. */
    internal fun toolCallBlocks(text: String): List<String> {
        val blocks = ArrayList<String>()
        var open = text.indexOf(OPEN)
        while (open >= 0) {
            val from = open + OPEN.length
            val close = text.indexOf(CLOSE, from)
            val next = text.indexOf(OPEN, from)
            if (close >= 0 && (next < 0 || close < next)) {
                blocks += text.substring(from, close).trim()
                open = text.indexOf(OPEN, close + CLOSE.length)
            } else {
                blocks += text.substring(from, if (next >= 0) next else text.length).trim()
                open = next
            }
        }
        return blocks
    }

    /** Every `required` key of the tool's `parameters` is in [args]. */
    private fun hasRequired(tool: AgentTool, args: JsonObject): Boolean =
        (tool.parameters["required"] as? JsonArray).orEmpty().all { k -> (k as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { it in args } ?: true }

    /**
     * Reads the model's own format ("auto"). Each `<tool_call>` block is one call; text around the blocks is dropped. No block: the
     * whole text is the answer in words. A call is accepted only if its JSON parses, its name is a declared tool, its arguments are a
     * JSON object and every required key of that tool is there; at most [ToolSpec.maxCalls] blocks are looked at. If any of those
     * fails, the result is [NativeRead.Invalid] and the caller asks once more under a constraint.
     */
    fun readNative(text: String, spec: ToolSpec, newId: () -> String = ::callId): NativeRead {
        val blocks = toolCallBlocks(text)
        if (blocks.isEmpty()) return NativeRead.Words(text)
        val calls = ArrayList<ToolCallOut>()
        var invalid = false
        var repairTool: String? = null
        for (block in blocks.take(spec.maxCalls)) {
            val o = try { json.parseToJsonElement(block) as? JsonObject } catch (e: IllegalArgumentException) { null }
            val name = (o?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content
            val tool = spec.tools.firstOrNull { it.name == name }
            val args = o?.get("arguments") as? JsonObject
            if (tool == null || args == null || !hasRequired(tool, args)) {
                if (!invalid) repairTool = tool?.name
                invalid = true
            } else {
                calls += ToolCallOut(newId(), tool.name, args.toString())
            }
        }
        return if (invalid) NativeRead.Invalid(repairTool) else NativeRead.Calls(calls)
    }

    /** The choice a repair asks under: the tool the failed call named, or any tool when it named none that is declared. */
    fun repairSpec(spec: ToolSpec, tool: String?): ToolSpec =
        ToolSpec(spec.tools, if (tool != null) ToolChoice.Named(tool) else ToolChoice.Required, spec.parallel)

    /** The same conversation as [req], asked again under the constraint of [repair]. */
    fun repairRequest(req: ChatRequest, history: List<AgentMessage>, repair: ToolSpec): ChatRequest =
        req.copy(messages = render(history, repair), jsonSchema = schema(repair))

    /**
     * Reads what a constrained model wrote. Anything that is not the expected shape (or names a tool that was not offered, or has
     * arguments that are not an object or lack a required key) is handed back as words, as it came, rather than failing the call.
     */
    fun parseReply(text: String, spec: ToolSpec?, newId: () -> String = ::callId): ToolReply {
        if (spec == null) return ToolReply.Content(text)
        val root = try { json.parseToJsonElement(text.trim()) as? JsonObject } catch (e: IllegalArgumentException) { null }
            ?: return ToolReply.Content(text)
        val offered = offered(spec)
        val raw = root["tool_calls"] as? JsonArray
        if (raw != null && raw.isNotEmpty()) {
            val calls = raw.map { el ->
                val o = el as? JsonObject
                val name = (o?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content
                val tool = offered.firstOrNull { it.name == name }
                val args = o?.get("arguments") as? JsonObject
                if (tool == null || args == null || !hasRequired(tool, args)) return ToolReply.Content(text)
                ToolCallOut(newId(), tool.name, args.toString())
            }
            return ToolReply.Calls(calls.take(spec.maxCalls))
        }
        val words = (root["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return ToolReply.Content(words ?: text)
    }

    /** `call_` and 24 random characters, the shape OpenAI clients expect. */
    fun callId(): String = "call_" + UUID.randomUUID().toString().replace("-", "").take(24)

    // ---- a model server that refuses the schema ---------------------------------------------------------------

    /**
     * True when [e] is the model server (or a phone of the swarm relaying it) saying it cannot use the schema it was given: a 400 from
     * the server, or an error that names the schema or the grammar. The cause chain is followed, because the swarm wraps errors.
     */
    fun isSchemaRejection(e: Throwable): Boolean {
        var t: Throwable? = e
        repeat(4) {
            val cur = t ?: return false
            if (cur is LlamaServerException && cur.status == 400) return true
            val text = cur.message.orEmpty()
            if ((cur is LlamaServerException || cur is NodeHttpException) && (text.contains("schema", true) || text.contains("grammar", true))) return true
            t = cur.cause
        }
        return false
    }
}
