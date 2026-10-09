package com.swarmos.app.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LlamaJsonTest {
    // Shapes follow llama-server's OpenAI-compatible streaming output: first chunk carries the role,
    // content arrives in delta.content, the last chunk has finish_reason and the timings object.
    private val sse = """
data: {"choices":[{"finish_reason":null,"index":0,"delta":{"role":"assistant","content":null}}],"created":1760000000,"id":"chatcmpl-1","model":"qwen","system_fingerprint":"b11533","object":"chat.completion.chunk"}

data: {"choices":[{"finish_reason":null,"index":0,"delta":{"content":"Hel"}}],"created":1760000000,"id":"chatcmpl-1","model":"qwen","system_fingerprint":"b11533","object":"chat.completion.chunk"}

data: {"choices":[{"finish_reason":null,"index":0,"delta":{"reasoning_content":"hidden thought"}}],"created":1760000000,"id":"chatcmpl-1","model":"qwen","object":"chat.completion.chunk"}

data: {"choices":[{"finish_reason":null,"index":0,"delta":{"content":"lo"}}],"created":1760000000,"id":"chatcmpl-1","model":"qwen","object":"chat.completion.chunk"}

data: {"choices":[{"finish_reason":"stop","index":0,"delta":{}}],"created":1760000000,"id":"chatcmpl-1","model":"qwen","object":"chat.completion.chunk","timings":{"cache_n":4,"prompt_n":18,"prompt_ms":95.3,"prompt_per_token_ms":5.29,"prompt_per_second":188.9,"predicted_n":12,"predicted_ms":690.0,"predicted_per_token_ms":57.5,"predicted_per_second":17.39}}

data: [DONE]

""".trimIndent()

    @Test fun streamParserYieldsDeltasAndTimings() {
        val p = SseChatParser()
        val deltas = sse.lines().mapNotNull { p.feed(it) }
        assertEquals(listOf("Hel", "lo"), deltas) // role chunk, reasoning_content and the finish chunk add no text
        assertTrue(p.done)
        assertEquals("stop", p.finishReason)
        val s = p.stats
        assertNotNull(s)
        assertEquals(18, s!!.promptTokens)
        assertEquals(12, s.completionTokens)
        assertEquals(95.3, s.promptMs, 1e-9)
        assertEquals(690.0, s.genMs, 1e-9)
        assertEquals(12 * 1000.0 / 690.0, s.decodeTokPerSec, 1e-9)
    }

    @Test fun includeUsageChunkWithEmptyChoicesCarriesTheTimings() {
        // shape documented in docs/llama-server-api-notes.md (stream_options.include_usage = true)
        val p = SseChatParser()
        p.feed("""data: {"choices":[{"finish_reason":"stop","index":0,"delta":{}}],"object":"chat.completion.chunk"}""")
        p.feed("""data: {"choices":[],"object":"chat.completion.chunk","usage":{"completion_tokens":6,"prompt_tokens":20,"total_tokens":26,"prompt_tokens_details":{"cached_tokens":0}},"timings":{"cache_n":0,"prompt_n":20,"prompt_ms":549.5,"predicted_n":6,"predicted_ms":385.3,"predicted_per_second":12.98}}""")
        p.feed("data: [DONE]")
        assertTrue(p.done)
        val s = p.finalStats(1.0, 1.0)
        assertEquals(20, s.promptTokens)
        assertEquals(6, s.completionTokens)
        assertEquals(385.3, s.genMs, 1e-9)
    }

    @Test fun usageChunkIsUsedWhenThereAreNoTimings() {
        val p = SseChatParser()
        p.feed("""data: {"choices":[],"usage":{"prompt_tokens":21,"completion_tokens":9,"total_tokens":30}}""")
        p.feed("data: [DONE]")
        assertNull(p.stats)
        val s = p.finalStats(localPromptMs = 100.0, localGenMs = 450.0)
        assertEquals(21, s.promptTokens)
        assertEquals(9, s.completionTokens)
        assertEquals(20.0, s.decodeTokPerSec, 1e-9)
    }

    @Test fun errorInsideTheStreamThrows() {
        val p = SseChatParser()
        try {
            p.feed("""data: {"error":{"code":500,"message":"slot busy","type":"server_error"}}""")
            fail("expected LlamaServerException")
        } catch (e: LlamaServerException) {
            assertEquals("slot busy", e.message)
        }
    }

    @Test fun commentsAndBlankLinesAreIgnored() {
        val p = SseChatParser()
        assertNull(p.feed(""))
        assertNull(p.feed(": keep-alive"))
        assertNull(p.feed("event: ping"))
        assertFalse(p.done)
    }

    @Test fun chatBodyCarriesSchemaAndDisablesThinking() {
        val schema = """{"type":"object","properties":{"n":{"type":"integer"}},"required":["n"]}"""
        val body = Json.parseToJsonElement(
            LlamaJson.chatBody(
                ChatRequest(listOf(ChatMessage("user", "hi")), maxTokens = 64, temperature = 0.2, jsonSchema = schema, stop = listOf("END"), seed = 7),
                stream = true, model = "m",
            ),
        ).jsonObject
        assertTrue(body["stream"]!!.jsonPrimitive.boolean)
        assertTrue(body["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.boolean)
        assertEquals("64", body["max_tokens"]!!.jsonPrimitive.content)
        assertEquals("7", body["seed"]!!.jsonPrimitive.content)
        assertEquals("END", body["stop"]!!.jsonArray[0].jsonPrimitive.content)
        assertFalse(body["chat_template_kwargs"]!!.jsonObject["enable_thinking"]!!.jsonPrimitive.boolean)
        val rf = body["response_format"]!!.jsonObject
        assertEquals("json_schema", rf["type"]!!.jsonPrimitive.content)
        val sent: JsonObject = rf["json_schema"]!!.jsonObject["schema"]!!.jsonObject
        assertEquals(Json.parseToJsonElement(schema), sent)
    }

    @Test fun chatBodyWithoutSchemaHasNoResponseFormat() {
        val body = Json.parseToJsonElement(
            LlamaJson.chatBody(ChatRequest(listOf(ChatMessage("user", "hi"))), stream = false, model = "m", extra = null),
        ).jsonObject
        assertFalse(body.containsKey("response_format"))
        assertFalse(body.containsKey("seed"))
        assertFalse(body.containsKey("stop"))
    }

    @Test fun nonStreamingResponseParses() {
        val r = LlamaJson.parseChatResponse(
            """{"choices":[{"finish_reason":"stop","index":0,"message":{"role":"assistant","content":"{\"n\":3}"}}],"usage":{"completion_tokens":5,"prompt_tokens":20,"total_tokens":25},"timings":{"prompt_n":20,"prompt_ms":100.0,"predicted_n":5,"predicted_ms":250.0}}""",
        )
        assertEquals("""{"n":3}""", r.text)
        assertEquals(20.0, r.stats.decodeTokPerSec, 1e-9)
    }

    @Test fun embeddingsAreTruncatedTo256AndUnitLength() {
        val raw = (0 until 768).joinToString(",") { ((it % 7) - 3).toString() + ".5" }
        val body = """{"data":[{"index":1,"embedding":[1.0,0.0]},{"index":0,"embedding":[$raw]}]}"""
        val out = LlamaJson.parseEmbeddings(body)
        assertEquals(2, out.size)
        assertEquals(256, out[0].size) // sorted by index: the 768-dim row comes first
        assertEquals(2, out[1].size)   // short vectors are kept, only normalised
        for (v in out) assertEquals(1.0, v.fold(0.0) { a, x -> a + x * x }, 1e-5)
    }

    @Test fun cosineOrdersSimilarAboveUnrelated() {
        val a = LlamaJson.truncateAndNormalise(floatArrayOf(1f, 1f, 0f, 0f))
        val near = LlamaJson.truncateAndNormalise(floatArrayOf(1f, 0.8f, 0.1f, 0f))
        val far = LlamaJson.truncateAndNormalise(floatArrayOf(0f, 0f, 1f, 1f))
        assertTrue(LlamaJson.cosine(a, near) > LlamaJson.cosine(a, far))
    }
}
