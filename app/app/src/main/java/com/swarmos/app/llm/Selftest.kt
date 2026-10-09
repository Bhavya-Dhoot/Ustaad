package com.swarmos.app.llm

import com.swarmos.app.AppGraph
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

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
)

/** On-phone check of the real client path: streamed chat, 10 JSON-schema generations, embeddings. Run through POST /v1/models/selftest. */
object Selftest {
    private const val SCHEMA = """{"type":"object","properties":{"city":{"type":"string"},"temp_c":{"type":"integer"},"tags":{"type":"array","items":{"type":"string"}}},"required":["city","temp_c","tags"],"additionalProperties":false}"""

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
        for (i in 0 until schemaRuns) {
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
        return SelftestResult(
            chatText = sb.toString(), chatTokPerSec = tps,
            schemaRuns = schemaRuns, schemaValid = valid, schemaFailures = failures,
            embedDims = dims, embedNorms = norms, cosRelated = rel, cosUnrelated = unrel,
            embedOk = dims.all { it == LlamaJson.EMBED_DIM } && norms.all { Math.abs(it - 1.0) < 1e-3 } && rel > unrel,
            embedMsPerText = LlamaRuntime.lastEmbedMsPerText.value ?: 0.0,
        )
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
