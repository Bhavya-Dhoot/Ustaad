package com.swarmos.app.agent

import com.swarmos.app.llm.ChatStats
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/** Response and error shapes, and the server-sent-event framing. */
class AgentWireTest {
    private val stats = ChatStats(promptTokens = 12, completionTokens = 30, promptMs = 100.0, genMs = 1000.0)

    @Test fun errorHasMessageTypeAndCode() {
        val e = AgentWire.error(AgentRefusal(400, "`tools` is not supported", "tools_unsupported")).getValue("error").jsonObject
        assertEquals(setOf("message", "type", "code"), e.keys)
        assertEquals("`tools` is not supported", e.getValue("message").jsonPrimitive.content)
        assertEquals("invalid_request_error", e.getValue("type").jsonPrimitive.content)
        assertEquals("tools_unsupported", e.getValue("code").jsonPrimitive.content)
    }

    @Test fun serverErrorsHaveTheServerType() {
        val e = AgentWire.error(AgentRefusal(503, "No chat model is loaded on this phone", "model_not_loaded", AgentRefusal.TYPE_SERVER))
        assertEquals("server_error", e.getValue("error").jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test fun modelsListsBothModels() {
        val m = AgentWire.models(1_700_000_000)
        assertEquals("list", m.getValue("object").jsonPrimitive.content)
        val data = m.getValue("data").jsonArray
        assertEquals(listOf("ustaad-local", "ustaad-swarm"), data.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        for (d in data) {
            assertEquals("model", d.jsonObject.getValue("object").jsonPrimitive.content)
            assertEquals(1_700_000_000, d.jsonObject.getValue("created").jsonPrimitive.content.toLong())
        }
    }

    @Test fun aChatCompletionHasTheOpenAiShape() {
        val r = AgentWire.chatCompletion("chatcmpl-1", 1_700_000_000, AgentModel.LOCAL, AgentWire.fingerprint("Phone A"), "Hello \"there\"\n", stats, maxTokens = 256)
        assertEquals("chatcmpl-1", r.getValue("id").jsonPrimitive.content)
        assertEquals("chat.completion", r.getValue("object").jsonPrimitive.content)
        assertEquals("ustaad-local", r.getValue("model").jsonPrimitive.content)
        assertEquals("ustaad:Phone A", r.getValue("system_fingerprint").jsonPrimitive.content)
        val choice = r.getValue("choices").jsonArray.single().jsonObject
        assertEquals(0, choice.getValue("index").jsonPrimitive.int)
        assertEquals("assistant", choice.getValue("message").jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("Hello \"there\"\n", choice.getValue("message").jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals("stop", choice.getValue("finish_reason").jsonPrimitive.content)
        val u = r.getValue("usage").jsonObject
        assertEquals(12, u.getValue("prompt_tokens").jsonPrimitive.int)
        assertEquals(30, u.getValue("completion_tokens").jsonPrimitive.int)
        assertEquals(42, u.getValue("total_tokens").jsonPrimitive.int)
    }

    @Test fun usingTheWholeBudgetIsFinishReasonLength() {
        assertEquals("length", AgentWire.finishReason(stats, maxTokens = 30))
        assertEquals("length", AgentWire.finishReason(stats, maxTokens = 20))
        assertEquals("stop", AgentWire.finishReason(stats, maxTokens = 31))
        assertEquals("stop", AgentWire.finishReason(null, maxTokens = 1))
    }

    @Test fun missingStatsGiveZeroUsage() {
        val u = AgentWire.usage(null)
        assertEquals(0, u.getValue("total_tokens").jsonPrimitive.int)
    }

    @Test fun aChunkCarriesADeltaAndNoUsageUntilTheLastOne() {
        val first = AgentWire.chunk("id", 5, AgentModel.SWARM, "ustaad:swarm", AgentWire.roleDelta())
        assertEquals("chat.completion.chunk", first.getValue("object").jsonPrimitive.content)
        assertEquals("ustaad-swarm", first.getValue("model").jsonPrimitive.content)
        val c0 = first.getValue("choices").jsonArray.single().jsonObject
        assertEquals("assistant", c0.getValue("delta").jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals(JsonNull, c0.getValue("finish_reason"))
        assertTrue("usage" !in first)

        val last = AgentWire.chunk("id", 5, AgentModel.SWARM, "ustaad:swarm", JsonObject(emptyMap()), finish = "stop", usage = AgentWire.usage(stats))
        val c1 = last.getValue("choices").jsonArray.single().jsonObject
        assertEquals(JsonObject(emptyMap()), c1.getValue("delta"))
        assertEquals("stop", c1.getValue("finish_reason").jsonPrimitive.content)
        assertEquals(42, last.getValue("usage").jsonObject.getValue("total_tokens").jsonPrimitive.int)
    }

    @Test fun anEventIsOneDataLineAndABlankLine() {
        val e = AgentWire.event(AgentWire.chunk("id", 5, AgentModel.LOCAL, "f", AgentWire.textDelta("line1\nline2")))
        assertTrue(e.startsWith("data: {"))
        assertTrue(e.endsWith("}\n\n"))
        assertEquals("one line of data, however many lines the text has", 1, e.trimEnd().lines().size)
        val back = Json.parseToJsonElement(e.removePrefix("data: ")).jsonObject
        assertEquals("line1\nline2", back.getValue("choices").jsonArray.single().jsonObject.getValue("delta").jsonObject.getValue("content").jsonPrimitive.content)
    }

    @Test fun theStreamEndsWithDoneAndPingsAreComments() {
        assertEquals("data: [DONE]\n\n", AgentWire.DONE)
        assertTrue(AgentWire.PING.startsWith(":"))
        assertTrue(AgentWire.PING.endsWith("\n\n"))
    }

    @Test fun embeddingsAsFloats() {
        val r = AgentWire.embeddings(AgentModel.LOCAL, listOf(floatArrayOf(0.5f, -1f), floatArrayOf(2f, 0.25f)), base64 = false)
        assertEquals("list", r.getValue("object").jsonPrimitive.content)
        assertEquals("ustaad-local", r.getValue("model").jsonPrimitive.content)
        val data = r.getValue("data").jsonArray
        assertEquals(listOf(0, 1), data.map { it.jsonObject.getValue("index").jsonPrimitive.int })
        assertEquals("embedding", data[0].jsonObject.getValue("object").jsonPrimitive.content)
        assertEquals(listOf(0.5f, -1f), (data[0].jsonObject.getValue("embedding") as JsonArray).map { it.jsonPrimitive.content.toFloat() })
        assertEquals(0, r.getValue("usage").jsonObject.getValue("prompt_tokens").jsonPrimitive.int)
    }

    @Test fun embeddingsAsBase64AreLittleEndianFloats() {
        val v = floatArrayOf(1.5f, -2f, 0.125f)
        val r = AgentWire.embeddings(AgentModel.LOCAL, listOf(v), base64 = true)
        val text = r.getValue("data").jsonArray.single().jsonObject.getValue("embedding").jsonPrimitive.contentOrNull!!
        val buf = ByteBuffer.wrap(Base64.getDecoder().decode(text)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(12, buf.remaining())
        assertEquals(v.toList(), List(3) { buf.getFloat() })
        assertTrue(JsonPrimitive(text).isString)
    }
}
