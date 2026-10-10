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

    @Test fun aSchemaOver16KiBIsRefusedNamingTheField() {
        fun withSchema(padding: Int) =
            chat(""""response_format":{"type":"json_schema","json_schema":{"name":"n","schema":{"type":"object","description":"${"a".repeat(padding)}"}}}""")
        val overhead = """{"type":"object","description":""}""".length
        val atLimit = AgentRequests.parseChat(withSchema(AgentRequests.MAX_SCHEMA_BYTES - overhead))
        assertEquals(AgentRequests.MAX_SCHEMA_BYTES, atLimit.request.jsonSchema!!.length)
        refused(400, "response_format.json_schema.schema") { AgentRequests.parseChat(withSchema(AgentRequests.MAX_SCHEMA_BYTES - overhead + 1)) }
        assertEquals("limit_exceeded", codeOf { AgentRequests.parseChat(withSchema(20_000)) })
    }

    @Test fun responseFormatTextIsNoSchema() {
        assertNull(AgentRequests.parseChat(chat(""""response_format":{"type":"text"}""")).request.jsonSchema)
    }

    @Test fun unknownFieldsAreIgnored() {
        val c = AgentRequests.parseChat(chat(""""top_p":0.9,"user":"u","frequency_penalty":0.5,"stream_options":{"include_usage":true},"n":1,"logprobs":false"""))
        assertEquals("hi", c.request.messages.single().content)
    }

    // ---- refusals --------------------------------------------------------------------------------------------

    @Test fun theOldFunctionFieldsAreRefusedToo() {
        refused(400, "functions") { AgentRequests.parseChat(chat(""""functions":[{"name":"f"}]""")) }
        refused(400, "function_call") { AgentRequests.parseChat(chat(""""function_call":"auto"""")) }
    }

    // ---- tools -----------------------------------------------------------------------------------------------

    private val searchTool = """{"type":"function","function":{"name":"search_files","description":"Search the owner's files","parameters":{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}}}"""
    private val clockTool = """{"type":"function","function":{"name":"get_time"}}"""
    private fun withTools(extra: String = "", tools: List<String> = listOf(searchTool)) =
        chat(""""tools":[${tools.joinToString(",")}]${if (extra.isEmpty()) "" else ",$extra"}""")

    @Test fun toolsAreParsedWithAutoAndParallelByDefault() {
        val c = AgentRequests.parseChat(withTools(tools = listOf(searchTool, clockTool)))
        val spec = c.tools!!
        assertEquals(listOf("search_files", "get_time"), spec.tools.map { it.name })
        assertEquals("Search the owner's files", spec.tools[0].description)
        assertEquals("", spec.tools[1].description)
        assertEquals(AgentTools.NO_PARAMETERS, spec.tools[1].parameters)
        assertEquals(ToolChoice.Auto, spec.choice)
        assertEquals(AgentTools.MAX_PARALLEL_CALLS, spec.maxCalls)
        assertEquals(AgentTools.schema(spec), c.request.jsonSchema)
        // the tool text is a system message in front of the caller's messages
        assertEquals(listOf("system", "user"), c.request.messages.map { it.role })
        assertTrue(c.request.messages[0].content.contains("search_files"))
        assertEquals(c.request.messages.sumOf { it.content.length }, c.promptChars)
    }

    @Test fun toolChoiceIsRead() {
        assertEquals(ToolChoice.Auto, AgentRequests.parseChat(withTools(""""tool_choice":"auto"""")).tools!!.choice)
        assertEquals(ToolChoice.Required, AgentRequests.parseChat(withTools(""""tool_choice":"required"""")).tools!!.choice)
        assertEquals(
            ToolChoice.Named("get_time"),
            AgentRequests.parseChat(withTools(""""tool_choice":{"type":"function","function":{"name":"get_time"}}""", listOf(searchTool, clockTool))).tools!!.choice,
        )
        assertEquals(ToolChoice.Auto, AgentRequests.parseChat(withTools(""""tool_choice":null""")).tools!!.choice)
    }

    @Test fun toolChoiceNoneOffersNothingAndConstrainsNothing() {
        val c = AgentRequests.parseChat(withTools(""""tool_choice":"none""""))
        assertNull(c.tools)
        assertNull(c.request.jsonSchema)
        assertEquals(listOf("user" to "hi"), c.request.messages.map { it.role to it.content })
    }

    @Test fun parallelToolCallsFalseAllowsOneCall() {
        assertEquals(1, AgentRequests.parseChat(withTools(""""parallel_tool_calls":false""")).tools!!.maxCalls)
        assertEquals(AgentTools.MAX_PARALLEL_CALLS, AgentRequests.parseChat(withTools(""""parallel_tool_calls":true""")).tools!!.maxCalls)
        refused(400, "parallel_tool_calls") { AgentRequests.parseChat(withTools(""""parallel_tool_calls":"no"""")) }
    }

    @Test fun toolsAreRefusedWhenTheyAreBad() {
        refused(400, "tools[0].type") { AgentRequests.parseChat(withTools(tools = listOf("""{"type":"retrieval"}"""))) }
        refused(400, "tools[0].type") { AgentRequests.parseChat(withTools(tools = listOf("""{"function":{"name":"f"}}"""))) }
        refused(400, "tools[0].function") { AgentRequests.parseChat(withTools(tools = listOf("""{"type":"function"}"""))) }
        refused(400, "tools[0]") { AgentRequests.parseChat(withTools(tools = listOf("\"f\""))) }
        refused(400, "tools") { AgentRequests.parseChat(chat(""""tools":[]""")) }
        refused(400, "tools") { AgentRequests.parseChat(chat(""""tools":"f"""")) }
        refused(400, "tools[0].function.parameters") { AgentRequests.parseChat(withTools(tools = listOf("""{"type":"function","function":{"name":"f","parameters":"x"}}"""))) }
        refused(400, "tools[0].function.description") { AgentRequests.parseChat(withTools(tools = listOf("""{"type":"function","function":{"name":"f","description":5}}"""))) }
    }

    @Test fun badOrDuplicateToolNamesAreRefused() {
        for (name in listOf("", "has space", "dot.name", "a".repeat(65), "naïve")) {
            refused(400, "tools[0].function.name") { AgentRequests.parseChat(withTools(tools = listOf("""{"type":"function","function":{"name":"$name"}}"""))) }
        }
        refused(400, "tools[0].function.name") { AgentRequests.parseChat(withTools(tools = listOf("""{"type":"function","function":{}}"""))) }
        assertEquals("a".repeat(64), AgentRequests.parseChat(withTools(tools = listOf("""{"type":"function","function":{"name":"${"a".repeat(64)}"}}"""))).tools!!.tools.single().name)
        assertEquals("a-B_9", AgentRequests.parseChat(withTools(tools = listOf("""{"type":"function","function":{"name":"a-B_9"}}"""))).tools!!.tools.single().name)
        refused(400, "tools[1].function.name") { AgentRequests.parseChat(withTools(tools = listOf(searchTool, searchTool))) }
    }

    @Test fun toolLimitsAreEnforced() {
        fun named(i: Int) = """{"type":"function","function":{"name":"t$i"}}"""
        assertEquals(16, AgentRequests.parseChat(withTools(tools = List(16) { named(it) })).tools!!.tools.size)
        refused(400, "tools") { AgentRequests.parseChat(withTools(tools = List(17) { named(it) })) }
        val okDescription = "d".repeat(AgentTools.MAX_DESCRIPTION)
        AgentRequests.parseChat(withTools(tools = listOf("""{"type":"function","function":{"name":"f","description":"$okDescription"}}""")))
        refused(400, "tools[0].function.description") {
            AgentRequests.parseChat(withTools(tools = listOf("""{"type":"function","function":{"name":"f","description":"${okDescription}x"}}""")))
        }
        // all definitions together: over 16 KiB, even when each one is small
        val big = """{"type":"function","function":{"name":"f%d","parameters":{"type":"object","description":"${"p".repeat(5000)}"}}}"""
        refused(400, "tools") { AgentRequests.parseChat(withTools(tools = List(4) { big.format(it) })) }
    }

    @Test fun aConstraintTooBigForTheSwarmIsRefusedNamingTools() {
        // 16,000 characters of schema fit the 16 KiB of definitions but not what a phone of the swarm accepts
        val tool = """{"type":"function","function":{"name":"f","parameters":{"type":"object","description":"${"p".repeat(16_000)}"}}}"""
        refused(400, "tools") { AgentRequests.parseChat(withTools(tools = listOf(tool))) }
    }

    @Test fun toolChoiceIsRefusedWhenItDoesNotFit() {
        refused(400, "tool_choice") { AgentRequests.parseChat(withTools(""""tool_choice":"sometimes"""")) }
        refused(400, "tool_choice") { AgentRequests.parseChat(withTools(""""tool_choice":5""")) }
        refused(400, "tool_choice") { AgentRequests.parseChat(withTools(""""tool_choice":{"type":"function"}""")) }
        refused(400, "tool_choice") { AgentRequests.parseChat(withTools(""""tool_choice":{"type":"retrieval","function":{"name":"search_files"}}""")) }
        // naming a tool that was not declared
        refused(400, "tool_choice.function.name") { AgentRequests.parseChat(withTools(""""tool_choice":{"type":"function","function":{"name":"nope"}}""")) }
        // nothing to call
        refused(400, "tool_choice") { AgentRequests.parseChat(chat(""""tool_choice":"required"""")) }
        refused(400, "tool_choice") { AgentRequests.parseChat(chat(""""tool_choice":{"type":"function","function":{"name":"search_files"}}""")) }
        assertNull(AgentRequests.parseChat(chat(""""tool_choice":"none"""")).tools)
        assertNull(AgentRequests.parseChat(chat(""""tool_choice":"auto"""")).tools)
    }

    @Test fun toolsTogetherWithAResponseFormatSchemaAreRefused() {
        refused(400, "response_format") {
            AgentRequests.parseChat(withTools(""""response_format":{"type":"json_schema","json_schema":{"name":"n","schema":{"type":"object"}}}"""))
        }
        refused(400, "response_format") {
            AgentRequests.parseChat(withTools(""""tool_choice":"none","response_format":{"type":"json_schema","json_schema":{"name":"n","schema":{"type":"object"}}}"""))
        }
        // plain text format asks for nothing, so it can sit beside tools
        assertEquals(ToolChoice.Auto, AgentRequests.parseChat(withTools(""""response_format":{"type":"text"}""")).tools!!.choice)
    }

    @Test fun assistantToolCallsAndToolMessagesAreAccepted() {
        val messages = """[{"role":"user","content":"find the bill"},
            {"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"search_files","arguments":"{\"query\":\"bill\"}"}}]},
            {"role":"tool","tool_call_id":"call_1","content":"Electricity bill, 2026-09-03"}]"""
        val c = AgentRequests.parseChat(chat(messages = messages))
        assertNull(c.tools)
        assertEquals(listOf("user", "assistant", "user"), c.request.messages.map { it.role })
        assertTrue(c.request.messages[1].content.contains("search_files"))
        assertTrue(c.request.messages[2].content.contains("Electricity bill"))
        // the tool result may also come as text parts
        val parts = """[{"role":"tool","tool_call_id":"c","content":[{"type":"text","text":"a"},{"type":"text","text":"b"}]}]"""
        assertTrue(AgentRequests.parseChat(chat(messages = parts)).request.messages.single().content.endsWith("a\nb"))
    }

    @Test fun theSecondRequestOfTheOpenAiPythonLoopIsAccepted() {
        // captured from openai 1.99.9: the assistant message appended as the client's own object, then the tool result
        val body = """{"messages":[{"role":"user","content":"When is my electricity bill due?"},{"content":null,"role":"assistant","tool_calls":[{"id":"call_85334d154d4047329705601a","function":{"arguments":"{\"query\":\"electricity bill\"}","name":"search_files"},"type":"function"}]},{"role":"tool","tool_call_id":"call_85334d154d4047329705601a","content":"Electricity bill, due 2026-09-30"}],"model":"ustaad-local","tools":[{"type":"function","function":{"name":"search_files","description":"d","parameters":{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}}}]}"""
        val c = AgentRequests.parseChat(body)
        assertEquals(listOf("system", "user", "assistant", "user"), c.request.messages.map { it.role })
        assertTrue(c.request.messages[3].content.contains("Result of `search_files` (call call_85334d154d4047329705601a)"))
    }

    @Test fun badToolMessagesAreRefused() {
        refused(400, "messages[1].tool_call_id") { AgentRequests.parseChat(chat(messages = """[{"role":"user","content":"x"},{"role":"tool","content":"r"}]""")) }
        refused(400, "messages[0].tool_call_id") { AgentRequests.parseChat(chat(messages = """[{"role":"tool","tool_call_id":"","content":"r"}]""")) }
        refused(400, "messages[0].content") { AgentRequests.parseChat(chat(messages = """[{"role":"tool","tool_call_id":"c"}]""")) }
        refused(400, "messages[0].tool_calls") { AgentRequests.parseChat(chat(messages = """[{"role":"user","content":"x","tool_calls":[{"id":"1"}]}]""")) }
        refused(400, "messages[0].tool_calls[0].id") { AgentRequests.parseChat(chat(messages = """[{"role":"assistant","tool_calls":[{"type":"function","function":{"name":"f"}}]}]""")) }
        refused(400, "messages[0].tool_calls[0].function.name") { AgentRequests.parseChat(chat(messages = """[{"role":"assistant","tool_calls":[{"id":"1","function":{}}]}]""")) }
        refused(400, "messages[0].tool_calls") { AgentRequests.parseChat(chat(messages = """[{"role":"assistant","tool_calls":"f"}]""")) }
        refused(400, "messages[0].content") { AgentRequests.parseChat(chat(messages = """[{"role":"assistant"}]""")) }
        refused(400, "messages[0].role") { AgentRequests.parseChat(chat(messages = """[{"role":"function","name":"f","content":"r"}]""")) }
        refused(400, "messages[0].function_call") { AgentRequests.parseChat(chat(messages = """[{"role":"assistant","content":null,"function_call":{"name":"f"}}]""")) }
    }

    @Test fun toolRefusalsHaveStableCodes() {
        assertEquals("invalid_value", codeOf { AgentRequests.parseChat(chat(""""tools":[]""")) })
        assertEquals("unsupported_parameter", codeOf { AgentRequests.parseChat(withTools(tools = listOf("""{"type":"retrieval"}"""))) })
        assertEquals("limit_exceeded", codeOf { AgentRequests.parseChat(withTools(tools = List(17) { """{"type":"function","function":{"name":"t$it"}}""" })) })
        assertEquals("missing_required_parameter", codeOf { AgentRequests.parseChat(chat(messages = """[{"role":"tool","content":"r"}]""")) })
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
        assertEquals("unsupported_parameter", codeOf { AgentRequests.parseChat(chat(""""functions":[{"name":"f"}]""")) })
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
