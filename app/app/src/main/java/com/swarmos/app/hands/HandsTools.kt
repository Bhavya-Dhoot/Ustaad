package com.swarmos.app.hands

import com.swarmos.app.core.SwarmJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** The tool list, the flat JSON the model answers with, and the mapping from that JSON to a [ToolCall]. */
object HandsTools {
    /** Links the agent may open. Anything else (intent:, file:, content:, javascript:, ustaad:, upi: ...) is refused by the guard. */
    val LINK_SCHEMES = setOf("https", "http", "geo", "tel", "mailto", "sms", "market")

    const val MAX_WAIT_SECONDS = 10
    const val NOTE_MAX = 100
    const val TEXT_MAX = 300

    private fun strProp(desc: String) = buildJsonObject { put("type", "string"); put("description", desc) }
    private val ID_INT = buildJsonObject { put("type", "integer") }
    private fun schema(props: List<Pair<String, JsonObject>>, required: List<String>) = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(props.toMap()))
        put("required", JsonArray(required.map { JsonPrimitive(it) }))
    }

    private fun idProp(desc: String) = buildJsonObject { put("type", "integer"); put("description", desc) }
    private val DIRECTION = buildJsonObject {
        put("type", "string")
        put("enum", JsonArray(listOf(JsonPrimitive("down"), JsonPrimitive("up"))))
    }

    val specs: List<ToolSpec> = listOf(
        ToolSpec("read_screen", "Read the screen again.", schema(emptyList(), emptyList()), Risk.READ),
        ToolSpec("tap", "Tap the element with this number.", schema(listOf("id" to idProp("element number")), listOf("id")), Risk.ACT),
        ToolSpec(
            "type", "Type text into the edit field with this number.",
            schema(listOf("id" to idProp("element number of an edit field"), "text" to strProp("the text to type")), listOf("id", "text")), Risk.ACT,
        ),
        ToolSpec("press_enter", "Press the enter or search key of the keyboard on the focused field.", schema(emptyList(), emptyList()), Risk.ACT),
        ToolSpec(
            "scroll", "Scroll the screen, or the list with this number. down shows what is further down.",
            schema(listOf("direction" to DIRECTION, "id" to idProp("optional element number of a list")), listOf("direction")), Risk.ACT,
        ),
        ToolSpec("back", "Press the back button.", schema(emptyList(), emptyList()), Risk.ACT),
        ToolSpec("home", "Go to the home screen.", schema(emptyList(), emptyList()), Risk.ACT),
        ToolSpec("open_app", "Open an installed app. app is its package or its name.", schema(listOf("app" to strProp("package or name")), listOf("app")), Risk.ACT),
        ToolSpec("find_app", "List installed apps whose name contains the text.", schema(listOf("text" to strProp("part of an app name")), listOf("text")), Risk.READ),
        ToolSpec(
            "open_link", "Open a link or deep link. text is the address. Only ${LINK_SCHEMES.joinToString(", ")} are allowed.",
            schema(listOf("text" to strProp("the address")), listOf("text")), Risk.ACT,
        ),
        ToolSpec(
            "wait", "Wait for the screen to load. id is the number of seconds, at most $MAX_WAIT_SECONDS.",
            schema(listOf("id" to idProp("seconds")), listOf("id")), Risk.READ,
        ),
        ToolSpec("ask_user", "Ask the person a question and wait for the answer.", schema(listOf("text" to strProp("the question")), listOf("text")), Risk.READ),
        ToolSpec(
            "ask_expert", "Ask a knowledge expert. app is the domain, text is the question.",
            schema(listOf("app" to strProp("the domain"), "text" to strProp("the question")), listOf("app", "text")), Risk.READ,
        ),
        ToolSpec("search_my_data", "Search the person's own files and notes.", schema(listOf("text" to strProp("what to look for")), listOf("text")), Risk.READ),
        ToolSpec("done", "The goal is reached. text is the short answer.", schema(listOf("text" to strProp("the answer")), listOf("text")), Risk.READ),
        ToolSpec("fail", "The goal cannot be reached. text is the reason.", schema(listOf("text" to strProp("the reason")), listOf("text")), Risk.READ),
    )

    val names: List<String> = specs.map { it.name }

    fun spec(name: String): ToolSpec? = specs.firstOrNull { it.name == name }

    /** One short line per tool for the system prompt. */
    fun promptLines(): String = specs.joinToString("\n") { s ->
        val args = (s.params["properties"] as? JsonObject)?.keys.orEmpty()
        val required = (s.params["required"] as? JsonArray)?.map { (it as JsonPrimitive).content }.orEmpty()
        val shown = args.joinToString(", ") { if (it in required) it else "$it?" }
        "- ${s.name}${if (shown.isEmpty()) "" else " {$shown}"}: ${s.description}"
    }

    /** The one flat object the model answers with. llama.cpp turns this into a grammar, so: no oneOf, no $ref. */
    val stepSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put("note", buildJsonObject { put("type", "string"); put("maxLength", NOTE_MAX) })
                put("tool", buildJsonObject { put("type", "string"); put("enum", JsonArray(names.map { JsonPrimitive(it) })) })
                put("id", ID_INT)
                put("text", buildJsonObject { put("type", "string"); put("maxLength", TEXT_MAX) })
                put("direction", DIRECTION)
                put("app", buildJsonObject { put("type", "string"); put("maxLength", 120) })
            },
        )
        put("required", JsonArray(listOf(JsonPrimitive("note"), JsonPrimitive("tool"))))
    }

    // ------------------------------------------------------------ links

    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*):")

    fun linkScheme(uri: String): String? = SCHEME.find(uri.trim())?.groupValues?.get(1)?.lowercase()

    /** True only for a plain address with one of [LINK_SCHEMES]; a control character anywhere, or no scheme at the very start, is refused. */
    fun linkAllowed(uri: String): Boolean {
        val u = uri.trim()
        if (u.isEmpty() || u.length > 2000) return false
        if (u.any { it.isISOControl() || (it.isWhitespace() && it != ' ') }) return false
        return linkScheme(u) in LINK_SCHEMES
    }

    // ------------------------------------------------------------ mapping

    sealed interface Mapped {
        data class Call(val call: ToolCall) : Mapped
        /** A missing or unusable argument. The text goes back to the model as the result of its step. */
        data class Bad(val message: String, val tool: String = "") : Mapped
    }

    data class ParsedStep(val note: String, val mapped: Mapped)

    private fun intOf(e: kotlinx.serialization.json.JsonElement?): Int? {
        val p = e as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        p.intOrNull?.let { return it }
        p.content.trim().toIntOrNull()?.let { return it }
        return p.doubleOrNull?.takeIf { it == Math.floor(it) && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }?.toInt()
    }

    private fun strOf(e: kotlinx.serialization.json.JsonElement?): String? {
        val p = e as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return p.contentOrNull
    }

    fun map(step: JsonObject): Mapped {
        val toolName = strOf(step["tool"])?.trim()?.lowercase().orEmpty()
        if (toolName.isEmpty()) return Mapped.Bad("no tool given; pick one of: ${names.joinToString(", ")}")
        val spec = spec(toolName) ?: return Mapped.Bad("unknown tool '$toolName'; pick one of: ${names.joinToString(", ")}")
        val props = (spec.params["properties"] as? JsonObject)?.keys.orEmpty()
        val required = (spec.params["required"] as? JsonArray)?.map { (it as JsonPrimitive).content }.orEmpty()
        val missing = ArrayList<String>()
        val args = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        for (key in props) {
            when (key) {
                "id" -> {
                    val v = intOf(step["id"])
                    if (v != null) args["id"] = JsonPrimitive(if (toolName == "wait") v.coerceIn(1, MAX_WAIT_SECONDS) else v)
                    else if (key in required) missing += "id"
                }
                "direction" -> {
                    val v = strOf(step["direction"])?.trim()?.lowercase()
                    if (v == "down" || v == "up") args["direction"] = JsonPrimitive(v)
                    else if (key in required) missing += "direction (down or up)"
                }
                else -> {
                    val v = strOf(step[key])
                    val ok = v != null && (v.isNotBlank() || (toolName == "type" && key == "text"))
                    if (ok) args[key] = JsonPrimitive(v) else if (key in required) missing += key
                }
            }
        }
        if (missing.isNotEmpty()) return Mapped.Bad("$toolName needs ${missing.joinToString(" and ")}", toolName)
        return Mapped.Call(ToolCall(toolName, JsonObject(args)))
    }

    private val F_TOOL = Regex(""""tool"\s*:\s*"([A-Za-z_]+)"""")
    private val F_ID = Regex(""""id"\s*:\s*"?(-?\d+)"?""")
    private val F_TEXT = Regex(""""text"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    private val F_APP = Regex(""""app"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    private val F_DIR = Regex(""""direction"\s*:\s*"([A-Za-z]+)"""")
    private val F_NOTE = Regex(""""note"\s*:\s*"((?:[^"\\]|\\.)*)"""")

    private fun slice(t: String): String {
        val a = t.indexOf('{')
        if (a < 0) return t
        val b = t.lastIndexOf('}')
        return if (b > a) t.substring(a, b + 1) else t.substring(a)
    }

    private fun unescape(s: String) = s.replace("\\\"", "\"").replace("\\n", " ").replace("\\\\", "\\")

    /** Strict JSON first; a reply cut off by the token cap falls back to picking the fields out. Null: no tool could be found at all. */
    fun parseStep(text: String): ParsedStep? {
        val root = runCatching { SwarmJson.parseToJsonElement(slice(text.trim())) as? JsonObject }.getOrNull()
        if (root != null && strOf(root["tool"]) != null) {
            return ParsedStep(strOf(root["note"]).orEmpty().trim(), map(root))
        }
        val tool = F_TOOL.find(text)?.groupValues?.get(1) ?: return null
        val obj = buildJsonObject {
            put("tool", tool)
            F_ID.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { put("id", it) }
            F_TEXT.find(text)?.groupValues?.get(1)?.let { put("text", unescape(it)) }
            F_APP.find(text)?.groupValues?.get(1)?.let { put("app", unescape(it)) }
            F_DIR.find(text)?.groupValues?.get(1)?.let { put("direction", it) }
        }
        return ParsedStep(F_NOTE.find(text)?.groupValues?.get(1)?.let { unescape(it) }.orEmpty().trim(), map(obj))
    }
}
