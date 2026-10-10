package com.swarmos.app.agent

import com.swarmos.app.link.NodeHttpException
import com.swarmos.app.llm.ChatMessage
import com.swarmos.app.llm.LlamaServerException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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

/**
 * Tool calling without the chat template. The tools are described to the model in a system message, and its output is
 * constrained (the model server's JSON-schema path) to `{"content": "..."}` or `{"tool_calls": [{"name", "arguments"}]}`; the
 * endpoint turns that into the OpenAI answer. Everything here is pure, so the prompt, the schema and the parsing are tested
 * without a model.
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

    /** The one place the tool text lives. */
    fun systemText(spec: ToolSpec): String = buildString {
        appendLine("You can call tools. Reply with one JSON object and nothing else.")
        if (spec.choice == ToolChoice.Auto) appendLine("""To answer in words: {"content":"<your answer>"}""")
        appendLine("""To call tools: {"tool_calls":[{"name":"<tool name>","arguments":{...}}]}. Call at most ${spec.maxCalls} in one reply.""")
        when (val c = spec.choice) {
            ToolChoice.Auto -> appendLine("Call a tool only when you need it. When a tool result already answers the question, answer in words.")
            ToolChoice.Required -> appendLine("You must call a tool.")
            is ToolChoice.Named -> appendLine("You must call `${c.name}`.")
        }
        appendLine("Tools:")
        for (t in offered(spec)) {
            append("- ").append(t.name).append(": ").append(t.description.ifBlank { "(no description)" })
            append(" Arguments, as a JSON Schema: ").appendLine(t.parameters.toString())
        }
    }.trimEnd()

    /** The tools the model may call: all of them, or the one a named choice picks. */
    private fun offered(spec: ToolSpec): List<AgentTool> = when (val c = spec.choice) {
        is ToolChoice.Named -> spec.tools.filter { it.name == c.name }
        else -> spec.tools
    }

    /**
     * The caller's messages as chat messages. A call the assistant made is shown as the JSON the model is held to; results of tools
     * (one or several in a row) become one user message, because chat templates differ on a `tool` role. With [spec], the tool text
     * joins the first system message, or becomes one.
     */
    fun render(messages: List<AgentMessage>, spec: ToolSpec?): List<ChatMessage> {
        val names = HashMap<String, String>()
        val out = ArrayList<ChatMessage>()
        var results = ArrayList<String>()
        fun flushResults() {
            if (results.isNotEmpty()) out += ChatMessage("user", results.joinToString("\n\n"))
            results = ArrayList()
        }
        for (m in messages) {
            if (m.role == "tool") {
                val id = m.toolCallId.orEmpty()
                val label = names[id]?.let { "Result of `$it` (call $id)" } ?: "Result of call $id"
                results += "$label:\n${m.content}"
                continue
            }
            flushResults()
            if (m.role == "assistant" && m.toolCalls.isNotEmpty()) {
                m.toolCalls.forEach { names[it.id] = it.name }
                out += ChatMessage("assistant", (if (m.content.isBlank()) "" else m.content + "\n") + pastCalls(m.toolCalls))
            } else {
                out += ChatMessage(m.role, m.content)
            }
        }
        flushResults()
        if (spec == null) return out
        val text = systemText(spec)
        val first = out.firstOrNull()
        if (first != null && first.role == "system") out[0] = ChatMessage("system", first.content + "\n\n" + text) else out.add(0, ChatMessage("system", text))
        return out
    }

    private fun pastCalls(calls: List<PastToolCall>): String = buildJsonObject {
        putJsonArray("tool_calls") {
            for (c in calls) add(buildJsonObject {
                put("name", c.name)
                put("arguments", try { json.parseToJsonElement(c.arguments) } catch (e: IllegalArgumentException) { JsonPrimitive(c.arguments) })
            })
        }
    }.toString()

    // ---- the constraint -------------------------------------------------------------------------------------

    /**
     * The JSON Schema the model's output is held to. `auto`: words or calls; `required`: calls; a named function: calls to that tool only.
     * Names are constants and each call's arguments follow that tool's own `parameters`, so arguments are valid JSON for the declared schema.
     */
    fun schema(spec: ToolSpec): String {
        val words = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { putJsonObject("content") { put("type", "string") } }
            putJsonArray("required") { add(JsonPrimitive("content")) }
            put("additionalProperties", false)
        }
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
        val top: JsonElement = if (spec.choice == ToolChoice.Auto) buildJsonObject { put("anyOf", buildJsonArray { add(words); add(calls) }) } else calls
        return top.toString()
    }

    // ---- the answer -----------------------------------------------------------------------------------------

    /**
     * Reads what the constrained model wrote. Anything that is not one of the two shapes (or names a tool that was not declared, or
     * has arguments that are not an object) is handed back as words, as it came, rather than failing the call.
     */
    fun parseReply(text: String, spec: ToolSpec?, newId: () -> String = ::callId): ToolReply {
        if (spec == null) return ToolReply.Content(text)
        val root = try { json.parseToJsonElement(text.trim()) as? JsonObject } catch (e: IllegalArgumentException) { null }
            ?: return ToolReply.Content(text)
        val declared = offered(spec).map { it.name }.toSet()
        val raw = root["tool_calls"] as? JsonArray
        if (raw != null && raw.isNotEmpty()) {
            val calls = raw.map { el ->
                val o = el as? JsonObject
                val name = (o?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content
                val args = o?.get("arguments") as? JsonObject
                if (name == null || name !in declared || args == null) return ToolReply.Content(text)
                ToolCallOut(newId(), name, args.toString())
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
