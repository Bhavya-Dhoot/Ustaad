package com.swarmos.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** OpenAI request JSON to our types, and every refusal in the contract. */
class AgentRequestsTest {
    private fun chat(extra: String = "", model: String = "ustaad-local", messages: String = """[{"role":"user","content":"hi"}]""") =
        """{"model":"$model","messages":$messages${if (extra.isEmpty()) "" else ",$extra"}}"""

    private fun refused(status: Int, mention: String, block: () -> Unit) {
        try {
            block()
            fail("expected a $status refusal mentioning $mention")
        } catch (r: AgentRefusal) {
            assertEquals(r.message, status, r.status)
            assertTrue("'${r.message}' should name $mention", r.message!!.contains(mention))
        }
    }

    // ---- mapping --------------------------------------------------------------------------------------------

    @Test fun aPlainRequestMapsToTheDefaults() {
        val c = AgentRequests.parseChat(chat())
        assertEquals(AgentModel.LOCAL, c.model)
        assertFalse(c.stream)
        assertEquals(listOf("user" to "hi"), c.request.messages.map { it.role to it.content })
        assertEquals(256, c.request.maxTokens)
        assertEquals(0.7, c.request.temperature, 0.0)
        assertNull(c.request.jsonSchema)
        assertEquals(emptyList<String>(), c.request.stop)
        assertNull(c.request.seed)
        assertEquals(2, c.promptChars)
    }

    @Test fun theSwarmModelIsAccepted() {
        assertEquals(AgentModel.SWARM, AgentRequests.parseChat(chat(model = "ustaad-swarm")).model)
    }

    @Test fun everyScalarFieldIsMapped() {
        val c = AgentRequests.parseChat(chat(""""max_tokens":100,"temperature":0.2,"seed":42,"stream":true"""))
        assertEquals(100, c.request.maxTokens)
        assertEquals(0.2, c.request.temperature, 0.0)
        assertEquals(42, c.request.seed)
        assertTrue(c.stream)
    }

    @Test fun maxCompletionTokensWinsOverMaxTokens() {
        assertEquals(50, AgentRequests.parseChat(chat(""""max_tokens":100,"max_completion_tokens":50""")).request.maxTokens)
        assertEquals(50, AgentRequests.parseChat(chat(""""max_completion_tokens":50""")).request.maxTokens)
    }

    @Test fun maxTokensIsClampedTo1024() {
        assertEquals(1024, AgentRequests.parseChat(chat(""""max_tokens":5000""")).request.maxTokens)
        assertEquals(1024, AgentRequests.parseChat(chat(""""max_tokens":1024""")).request.maxTokens)
        assertEquals(1024, AgentRequests.parseChat(chat(""""max_tokens":9999999999""")).request.maxTokens)
        assertEquals(1, AgentRequests.parseChat(chat(""""max_tokens":1""")).request.maxTokens)
    }

    @Test fun nullFieldsCountAsAbsent() {
        val c = AgentRequests.parseChat(chat(""""max_tokens":null,"temperature":null,"stop":null,"seed":null,"stream":null,"response_format":null,"tools":null,"n":null"""))
        assertEquals(256, c.request.maxTokens)
        assertFalse(c.stream)
    }

    @Test fun stopIsAStringOrAList() {
        assertEquals(listOf("END"), AgentRequests.parseChat(chat(""""stop":"END"""")).request.stop)
        assertEquals(listOf("a", "b"), AgentRequests.parseChat(chat(""""stop":["a","b"]""")).request.stop)
        assertEquals(emptyList<String>(), AgentRequests.parseChat(chat(""""stop":[""]""")).request.stop)
    }

    @Test fun messagesKeepTheirOrderAndRoles() {
        val c = AgentRequests.parseChat(
            chat(messages = """[{"role":"system","content":"s"},{"role":"user","content":"u"},{"role":"assistant","content":"a"},{"role":"developer","content":"d"}]"""),
        )
        assertEquals(listOf("system" to "s", "user" to "u", "assistant" to "a", "system" to "d"), c.request.messages.map { it.role to it.content })
    }

    @Test fun anArrayOfTextPartsIsJoined() {
        val c = AgentRequests.parseChat(
            chat(messages = """[{"role":"user","content":[{"type":"text","text":"one"},{"type":"text","text":"two"}]}]"""),
        )
        assertEquals("one\ntwo", c.request.messages.single().content)
        assertEquals(7, c.promptChars)
    }

    @Test fun jsonSchemaBecomesTheSchemaText() {
        val c = AgentRequests.parseChat(
            chat(""""response_format":{"type":"json_schema","json_schema":{"name":"x","strict":true,"schema":{"type":"object","properties":{"a":{"type":"integer"}},"required":["a"]}}}"""),
        )
        assertEquals("""{"type":"object","properties":{"a":{"type":"integer"}},"required":["a"]}""", c.request.jsonSchema)
    }

    @Test fun responseFormatTextIsNoSchema() {
        assertNull(AgentRequests.parseChat(chat(""""response_format":{"type":"text"}""")).request.jsonSchema)
    }

    @Test fun unknownFieldsAreIgnored() {
        val c = AgentRequests.parseChat(chat(""""top_p":0.9,"user":"u","frequency_penalty":0.5,"stream_options":{"include_usage":true},"n":1,"logprobs":false"""))
        assertEquals("hi", c.request.messages.single().content)
    }

    // ---- refusals --------------------------------------------------------------------------------------------

    @Test fun toolsAreRefused() {
        refused(400, "tools") { AgentRequests.parseChat(chat(""""tools":[{"type":"function","function":{"name":"f"}}]""")) }
        refused(400, "tools") { AgentRequests.parseChat(chat(""""tools":[]""")) }
    }

    @Test fun toolChoiceIsRefused() {
        refused(400, "tool_choice") { AgentRequests.parseChat(chat(""""tool_choice":"auto"""")) }
        refused(400, "tool_choice") { AgentRequests.parseChat(chat(""""tool_choice":"none"""")) }
    }

    @Test fun theOldFunctionFieldsAreRefusedToo() {
        refused(400, "functions") { AgentRequests.parseChat(chat(""""functions":[{"name":"f"}]""")) }
        refused(400, "function_call") { AgentRequests.parseChat(chat(""""function_call":"auto"""")) }
    }

    @Test fun toolMessagesAreRefused() {
        refused(400, "messages[1].role") { AgentRequests.parseChat(chat(messages = """[{"role":"user","content":"x"},{"role":"tool","content":"r","tool_call_id":"1"}]""")) }
        refused(400, "messages[0].tool_calls") { AgentRequests.parseChat(chat(messages = """[{"role":"assistant","content":null,"tool_calls":[{"id":"1"}]}]""")) }
    }

    @Test fun moreThanOneAnswerIsRefused() {
        refused(400, "`n`") { AgentRequests.parseChat(chat(""""n":2""")) }
        refused(400, "`n`") { AgentRequests.parseChat(chat(""""n":0""")) }
        refused(400, "`n`") { AgentRequests.parseChat(chat(""""n":"two"""")) }
    }

    @Test fun imagePartsAreRefused() {
        refused(400, "messages[0].content[1]") {
            AgentRequests.parseChat(chat(messages = """[{"role":"user","content":[{"type":"text","text":"look"},{"type":"image_url","image_url":{"url":"data:image/png;base64,AAAA"}}]}]"""))
        }
    }

    @Test fun audioPartsAreRefused() {
        refused(400, "messages[0].content[0]") {
            AgentRequests.parseChat(chat(messages = """[{"role":"user","content":[{"type":"input_audio","input_audio":{"data":"AAAA","format":"wav"}}]}]"""))
        }
    }

    @Test fun moreThan64MessagesAreRefused() {
        val many = List(65) { """{"role":"user","content":"m"}""" }.joinToString(",", "[", "]")
        refused(400, "messages") { AgentRequests.parseChat(chat(messages = many)) }
        val ok = List(64) { """{"role":"user","content":"m"}""" }.joinToString(",", "[", "]")
        assertEquals(64, AgentRequests.parseChat(chat(messages = ok)).request.messages.size)
    }

    @Test fun anUnknownModelIs404() {
        refused(404, "gpt-4o") { AgentRequests.parseChat(chat(model = "gpt-4o")) }
        refused(404, "ustaad-local ") { AgentRequests.parseChat(chat(model = "ustaad-local ")) }
    }

    @Test fun aMissingModelIs400() {
        refused(400, "model") { AgentRequests.parseChat("""{"messages":[{"role":"user","content":"x"}]}""") }
    }

    @Test fun missingOrEmptyMessagesAreRefused() {
        refused(400, "messages") { AgentRequests.parseChat("""{"model":"ustaad-local"}""") }
        refused(400, "messages") { AgentRequests.parseChat(chat(messages = "[]")) }
        refused(400, "messages") { AgentRequests.parseChat(chat(messages = """"text"""")) }
    }

    @Test fun badMessagesAreRefused() {
        refused(400, "messages[0]") { AgentRequests.parseChat(chat(messages = """["hi"]""")) }
        refused(400, "messages[0].role") { AgentRequests.parseChat(chat(messages = """[{"role":"robot","content":"x"}]""")) }
        refused(400, "messages[0].content") { AgentRequests.parseChat(chat(messages = """[{"role":"user"}]""")) }
        refused(400, "messages[0].content") { AgentRequests.parseChat(chat(messages = """[{"role":"user","content":5}]""")) }
        refused(400, "messages[0].content[0]") { AgentRequests.parseChat(chat(messages = """[{"role":"user","content":["x"]}]""")) }
        refused(400, "messages[0].content[0].text") { AgentRequests.parseChat(chat(messages = """[{"role":"user","content":[{"type":"text"}]}]""")) }
    }

    @Test fun badNumbersAreRefusedByName() {
        refused(400, "max_tokens") { AgentRequests.parseChat(chat(""""max_tokens":0""")) }
        refused(400, "max_tokens") { AgentRequests.parseChat(chat(""""max_tokens":-5""")) }
        refused(400, "max_tokens") { AgentRequests.parseChat(chat(""""max_tokens":"10"""")) }
        refused(400, "max_tokens") { AgentRequests.parseChat(chat(""""max_tokens":1.5""")) }
        refused(400, "temperature") { AgentRequests.parseChat(chat(""""temperature":3""")) }
        refused(400, "temperature") { AgentRequests.parseChat(chat(""""temperature":-0.1""")) }
        refused(400, "temperature") { AgentRequests.parseChat(chat(""""temperature":"hot"""")) }
        refused(400, "seed") { AgentRequests.parseChat(chat(""""seed":99999999999""")) }
        refused(400, "stream") { AgentRequests.parseChat(chat(""""stream":"yes"""")) }
    }

    @Test fun badStopIsRefused() {
        refused(400, "stop") { AgentRequests.parseChat(chat(""""stop":["a","b","c","d","e"]""")) }
        refused(400, "stop") { AgentRequests.parseChat(chat(""""stop":[1]""")) }
        refused(400, "stop") { AgentRequests.parseChat(chat(""""stop":5""")) }
    }

    @Test fun otherResponseFormatsAreRefused() {
        refused(400, "response_format") { AgentRequests.parseChat(chat(""""response_format":{"type":"json_object"}""")) }
        refused(400, "response_format") { AgentRequests.parseChat(chat(""""response_format":{"type":"json_schema"}""")) }
        refused(400, "response_format") { AgentRequests.parseChat(chat(""""response_format":"json"""")) }
    }

    @Test fun invalidJsonIsRefused() {
        refused(400, "JSON") { AgentRequests.parseChat("") }
        refused(400, "JSON") { AgentRequests.parseChat("{not json") }
        refused(400, "JSON") { AgentRequests.parseChat("[1,2]") }
        refused(400, "JSON") { AgentRequests.parseChat("\"text\"") }
        refused(400, "JSON") { AgentRequests.parseEmbeddings("nope") }
    }

    @Test fun deeplyNestedJsonIsRefusedNotCrashed() {
        val deep = "[".repeat(100_000)
        refused(400, "JSON") { AgentRequests.parseChat(deep) }
    }

    @Test fun bracketsInsideStringsDoNotCountAsNesting() {
        assertEquals(3, AgentRequests.nestingDepth("""{"a":[1,{"b":2}]}"""))
        assertEquals(1, AgentRequests.nestingDepth("""{"a":"[[[[ \" ]]]] \\"}"""))
        assertEquals(0, AgentRequests.nestingDepth("plain"))
        val ok = "[".repeat(AgentRequests.MAX_DEPTH - 1) + "]".repeat(AgentRequests.MAX_DEPTH - 1)
        assertEquals(AgentRequests.MAX_DEPTH - 1, AgentRequests.nestingDepth(ok))
    }

    private fun codeOf(block: () -> Unit): String {
        try {
            block()
        } catch (r: AgentRefusal) {
            return r.code
        }
        fail("expected a refusal")
        return ""
    }

    @Test fun refusalsHaveStableCodes() {
        assertEquals("unsupported_parameter", codeOf { AgentRequests.parseChat(chat(""""tools":[]""")) })
        assertEquals("unsupported_parameter", codeOf { AgentRequests.parseChat(chat(""""n":2""")) })
        assertEquals("unsupported_content", codeOf { AgentRequests.parseChat(chat(messages = """[{"role":"user","content":[{"type":"image_url"}]}]""")) })
        assertEquals("missing_required_parameter", codeOf { AgentRequests.parseChat("""{"messages":[{"role":"user","content":"x"}]}""") })
        assertEquals("invalid_value", codeOf { AgentRequests.parseChat(chat(""""max_tokens":0""")) })
        assertEquals("limit_exceeded", codeOf { AgentRequests.parseChat(chat(messages = List(65) { """{"role":"user","content":"m"}""" }.joinToString(",", "[", "]"))) })
        assertEquals("invalid_json", codeOf { AgentRequests.parseChat("nope") })
        assertEquals("model_not_found", codeOf { AgentRequests.parseChat(chat(model = "x")) })
    }

    @Test fun refusalsCarryTheOpenAiErrorType() {
        try {
            AgentRequests.parseChat(chat(""""tools":[]"""))
            fail()
        } catch (r: AgentRefusal) {
            assertEquals("invalid_request_error", r.type)
        }
    }

    // ---- embeddings -------------------------------------------------------------------------------------------

    @Test fun embeddingInputIsAStringOrAListOfStrings() {
        assertEquals(listOf("one"), AgentRequests.parseEmbeddings("""{"model":"ustaad-local","input":"one"}""").texts)
        val c = AgentRequests.parseEmbeddings("""{"model":"ustaad-swarm","input":["a","bc"]}""")
        assertEquals(AgentModel.SWARM, c.model)
        assertEquals(listOf("a", "bc"), c.texts)
        assertEquals(3, c.chars)
        assertFalse(c.base64)
    }

    @Test fun base64EncodingIsAskedFor() {
        assertTrue(AgentRequests.parseEmbeddings("""{"model":"ustaad-local","input":"x","encoding_format":"base64"}""").base64)
        assertFalse(AgentRequests.parseEmbeddings("""{"model":"ustaad-local","input":"x","encoding_format":"float"}""").base64)
        refused(400, "encoding_format") { AgentRequests.parseEmbeddings("""{"model":"ustaad-local","input":"x","encoding_format":"hex"}""") }
    }

    @Test fun badEmbeddingRequestsAreRefused() {
        refused(400, "input") { AgentRequests.parseEmbeddings("""{"model":"ustaad-local"}""") }
        refused(400, "input") { AgentRequests.parseEmbeddings("""{"model":"ustaad-local","input":[]}""") }
        refused(400, "input[0]") { AgentRequests.parseEmbeddings("""{"model":"ustaad-local","input":[[1,2,3]]}""") }
        refused(400, "input[1]") { AgentRequests.parseEmbeddings("""{"model":"ustaad-local","input":["a",7]}""") }
        refused(400, "input") { AgentRequests.parseEmbeddings("""{"model":"ustaad-local","input":5}""") }
        refused(400, "input[0]") { AgentRequests.parseEmbeddings("""{"model":"ustaad-local","input":""}""") }
        refused(400, "model") { AgentRequests.parseEmbeddings("""{"input":"x"}""") }
        refused(404, "text-embedding-3-small") { AgentRequests.parseEmbeddings("""{"model":"text-embedding-3-small","input":"x"}""") }
        val many = List(65) { "\"t\"" }.joinToString(",", "[", "]")
        refused(400, "input") { AgentRequests.parseEmbeddings("""{"model":"ustaad-local","input":$many}""") }
    }
}
