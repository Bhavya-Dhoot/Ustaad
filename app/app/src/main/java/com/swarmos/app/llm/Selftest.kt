package com.swarmos.app.llm

import com.swarmos.app.AppGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

@Serializable
data class SelftestResult(
    val chatText: String,
    val chatTokPerSec: Double,
    val schemaRuns: Int,
    val schemaValid: Int,
    val schemaFailures: List<String>,
    val embedDims: List<Int>,
    val embedNorms: List<Double>,
    val cosRelated: Double,
    val cosUnrelated: Double,
    val embedOk: Boolean,
    val embedMsPerText: Double,
    /** One reasoning-prone request through the client's own request body: characters of reasoning_content (0 = thinking is off). */
    val thinkingReasoningChars: Int = 0,
    val thinkingTagSeen: Boolean = false,
    val thinkingCompletionTokens: Int = 0,
    val thinkingAnswer: String = "",
)

/**
 * On-phone check of the real client path: streamed chat, 10 JSON-schema generations, embeddings, and one request that
 * tries to make the model think. Started with POST /v1/models/selftest (202, then poll GET /v1/models/selftest).
 */
object Selftest {
    private const val SCHEMA = """{"type":"object","properties":{"city":{"type":"string"},"temp_c":{"type":"integer"},"tags":{"type":"array","items":{"type":"string"}}},"required":["city","temp_c","tags"],"additionalProperties":false}"""

    private val mutex = Mutex()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _last = MutableStateFlow<SelftestResult?>(null)
    val last: StateFlow<SelftestResult?> = _last.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Runs the selftest and records the outcome in [last] / [error] (what the route polls). Never throws except cancellation. */
    suspend fun runRecorded(): Unit = mutex.withLock {
        _running.value = true
        _error.value = null
        _last.value = null
        try {
            _last.value = run()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _error.value = e.message ?: e.toString()
        } finally {
            _running.value = false
        }
    }

    suspend fun run(schemaRuns: Int = 10): SelftestResult {
        val llm = AppGraph.llm
        check(llm is LlamaServerClient) { "chat server is not READY" }
        val embedder = AppGraph.embedder
        check(embedder is LlamaServerClient) { "embedding server is not READY" }

        val sb = StringBuilder()
        var tps = 0.0
        llm.chatStream(ChatRequest(listOf(ChatMessage("user", "Say hello in five words.")), maxTokens = 24, temperature = 0.0)).collect {
            sb.append(it.delta)
            it.stats?.let { s -> tps = s.decodeTokPerSec }
        }

        var valid = 0
        val failures = mutableListOf<String>()
        val runs = if (ModelCatalog.allowsSchema(llm.modelName)) schemaRuns else 0
        if (runs == 0) failures += "skipped: ${llm.modelName} is ${ModelCatalog.CHAT_ONLY_NOTE}"
        for (i in 0 until runs) {
            val r = llm.chat(
                ChatRequest(
                    listOf(ChatMessage("user", "Invent a weather report for a random city. Variation $i.")),
                    maxTokens = 160, temperature = 0.9, jsonSchema = SCHEMA, seed = 100 + i,
                ),
            )
            val problem = validate(r.text)
            if (problem == null) valid++ else failures += "run $i: $problem :: ${r.text.take(120)}"
        }

        val texts = listOf("warranty bill for laptop", "laptop purchase receipt", "how to bake sourdough bread")
        val v = embedder.embed(texts)
        val dims = v.map { it.size }
        val norms = v.map { x -> Math.sqrt(x.fold(0.0) { a, f -> a + f * f }) }
        val rel = LlamaJson.cosine(v[0], v[1])
        val unrel = LlamaJson.cosine(v[0], v[2])
        val think = thinkingProbe(llm)
        return SelftestResult(
            chatText = sb.toString(), chatTokPerSec = tps,
            schemaRuns = runs, schemaValid = valid, schemaFailures = failures,
            embedDims = dims, embedNorms = norms, cosRelated = rel, cosUnrelated = unrel,
            embedOk = dims.all { it == LlamaJson.EMBED_DIM } && norms.all { Math.abs(it - 1.0) < 1e-3 } && rel > unrel,
            embedMsPerText = LlamaRuntime.lastEmbedMsPerText.value ?: 0.0,
            thinkingReasoningChars = think.reasoningChars,
            thinkingTagSeen = think.tagSeen,
            thinkingCompletionTokens = think.completionTokens,
            thinkingAnswer = think.answer.take(200),
        )
    }

    private class Think(val reasoningChars: Int, val tagSeen: Boolean, val completionTokens: Int, val answer: String)

    /**
     * One request that invites step-by-step reasoning, sent with exactly the body [LlamaJson.chatBody] builds for every
     * client call, parsed raw so `reasoning_content` and `<think>` are visible. Both must be absent when thinking is off.
     */
    private fun thinkingProbe(llm: LlamaServerClient): Think {
        val body = LlamaJson.chatBody(
            ChatRequest(
                listOf(ChatMessage("user", "A farmer has 17 sheep. All but 9 run away. How many are left? Think step by step before you answer.")),
                maxTokens = 200, temperature = 0.0, seed = 1,
            ),
            stream = false, model = llm.modelName,
        )
        val c = LlamaHttp.post("http://127.0.0.1:${llm.port}/v1/chat/completions", body, 120_000)
        try {
            val root = LlamaJson.json.parseToJsonElement(LlamaHttp.readBody(c)) as? JsonObject ?: return Think(-1, false, 0, "unparsable")
            val msg = (root["choices"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("message") as? JsonObject
            val reasoning = (msg?.get("reasoning_content") as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull.orEmpty()
            val content = (msg?.get("content") as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull.orEmpty()
            val tokens = (root["usage"] as? JsonObject)?.get("completion_tokens")?.let { (it as? JsonPrimitive)?.intOrNull } ?: 0
            return Think(reasoning.length, content.contains("<think") || content.contains("</think"), tokens, content)
        } finally {
            c.disconnect()
        }
    }

    /** null when [text] is valid against [SCHEMA]. */
    private fun validate(text: String): String? = try {
        val o = LlamaJson.json.parseToJsonElement(text) as? JsonObject ?: return "not an object"
        if (o.keys != setOf("city", "temp_c", "tags")) return "keys ${o.keys}"
        if ((o["city"] as? JsonPrimitive)?.isString != true) return "city not a string"
        if ((o["temp_c"] as? JsonPrimitive)?.let { !it.isString && it.intOrNull != null } != true) return "temp_c not an integer"
        val tags = o["tags"] as? JsonArray ?: return "tags not an array"
        if (tags.any { (it as? JsonPrimitive)?.isString != true }) return "tags item not a string"
        null
    } catch (e: Exception) {
        "parse error: ${e.message}"
    }
}
