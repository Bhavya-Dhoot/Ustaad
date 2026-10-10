package com.swarmos.app.agent

import com.swarmos.app.link.NodeHttpException
import com.swarmos.app.llm.ChatMessage
import com.swarmos.app.llm.LlamaServerException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** The prompt, the constraint and the reading of the answer for tool calling, without a model. */
class AgentToolsTest {
    private val searchParams = Json.parseToJsonElement("""{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}""").jsonObject
    private val timeParams = Json.parseToJsonElement("""{"type":"object","properties":{"zone":{"type":"string"}}}""").jsonObject
    private val search = AgentTool("search_files", "Search the owner's files.", searchParams)
    private val time = AgentTool("get_time", "", timeParams)

    private fun spec(choice: ToolChoice = ToolChoice.Auto, parallel: Boolean = true, tools: List<AgentTool> = listOf(search, time)) = ToolSpec(tools, choice, parallel)

    private fun schema(spec: ToolSpec): JsonObject = Json.parseToJsonElement(AgentTools.schema(spec)).jsonObject

    private fun callsShape(top: JsonObject): JsonObject =
        if ("anyOf" in top) top.getValue("anyOf").jsonArray.map { it.jsonObject }.single { "tool_calls" in it.getValue("properties").jsonObject } else top

    // ---- the constraint --------------------------------------------------------------------------------------

    @Test fun autoAllowsWordsOrCalls() {
        val top = schema(spec())
        val shapes = top.getValue("anyOf").jsonArray.map { it.jsonObject }
        assertEquals(2, shapes.size)
        val words = shapes.single { "content" in it.getValue("properties").jsonObject }
        assertEquals("string", words.getValue("properties").jsonObject.getValue("content").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(listOf("content"), words.getValue("required").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("false", words.getValue("additionalProperties").jsonPrimitive.content)
        val calls = callsShape(top).getValue("properties").jsonObject.getValue("tool_calls").jsonObject
        assertEquals("array", calls.getValue("type").jsonPrimitive.content)
        assertEquals(1, calls.getValue("minItems").jsonPrimitive.int)
        assertEquals(4, calls.getValue("maxItems").jsonPrimitive.int)
        val names = calls.getValue("items").jsonObject.getValue("anyOf").jsonArray.map {
            it.jsonObject.getValue("properties").jsonObject.getValue("name").jsonObject.getValue("const").jsonPrimitive.content
        }
        assertEquals(listOf("search_files", "get_time"), names)
    }

    @Test fun requiredAllowsOnlyCalls() {
        val top = schema(spec(ToolChoice.Required))
        assertFalse("anyOf" in top)
        assertEquals(listOf("tool_calls"), top.getValue("required").jsonArray.map { it.jsonPrimitive.content })
        assertFalse("content" in top.getValue("properties").jsonObject)
    }

    @Test fun aNamedFunctionAllowsOnlyThatTool() {
        val top = schema(spec(ToolChoice.Named("get_time")))
        val items = top.getValue("properties").jsonObject.getValue("tool_calls").jsonObject.getValue("items").jsonObject
        assertFalse("anyOf" in items)
        assertEquals("get_time", items.getValue("properties").jsonObject.getValue("name").jsonObject.getValue("const").jsonPrimitive.content)
    }

    @Test fun argumentsFollowTheToolsOwnParameters() {
        val items = schema(spec(ToolChoice.Named("search_files"))).getValue("properties").jsonObject.getValue("tool_calls").jsonObject.getValue("items").jsonObject
        assertEquals(searchParams, items.getValue("properties").jsonObject.getValue("arguments"))
        assertEquals(listOf("name", "arguments"), items.getValue("required").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test fun noParallelCallsMeansOneCall() {
        val calls = callsShape(schema(spec(parallel = false))).getValue("properties").jsonObject.getValue("tool_calls").jsonObject
        assertEquals(1, calls.getValue("maxItems").jsonPrimitive.int)
    }

    // ---- the prompt ------------------------------------------------------------------------------------------

    @Test fun theSystemTextNamesEveryToolWithItsSchema() {
        val t = AgentTools.systemText(spec())
        assertTrue(t.contains("- search_files: Search the owner's files. Arguments, as a JSON Schema: $searchParams"))
        assertTrue(t.contains("- get_time: (no description)"))
        assertTrue(t.contains("""{"content":"<your answer>"}"""))
        assertTrue(t.contains("at most 4"))
    }

    @Test fun theSystemTextFollowsTheChoice() {
        val required = AgentTools.systemText(spec(ToolChoice.Required))
        assertTrue(required.contains("You must call a tool."))
        assertFalse(required.contains("""{"content":"<your answer>"}"""))
        val named = AgentTools.systemText(spec(ToolChoice.Named("get_time"), parallel = false))
        assertTrue(named.contains("You must call `get_time`."))
        assertTrue(named.contains("- get_time:"))
        assertFalse(named.contains("- search_files:"))
        assertTrue(named.contains("at most 1"))
    }

    // ---- messages into the prompt ----------------------------------------------------------------------------

    private fun user(text: String) = AgentMessage("user", text)

    @Test fun ordinaryMessagesAreUnchangedWithoutTools() {
        val out = AgentTools.render(listOf(AgentMessage("system", "be brief"), user("hi"), AgentMessage("assistant", "hello")), null)
        assertEquals(listOf(ChatMessage("system", "be brief"), ChatMessage("user", "hi"), ChatMessage("assistant", "hello")), out)
    }

    @Test fun theToolTextJoinsTheFirstSystemMessage() {
        val s = spec()
        val out = AgentTools.render(listOf(AgentMessage("system", "be brief"), user("hi")), s)
        assertEquals(2, out.size)
        assertEquals("system", out[0].role)
        assertEquals("be brief\n\n" + AgentTools.systemText(s), out[0].content)
    }

    @Test fun withoutASystemMessageTheToolTextBecomesOne() {
        val s = spec()
        val out = AgentTools.render(listOf(user("hi")), s)
        assertEquals(listOf("system", "user"), out.map { it.role })
        assertEquals(AgentTools.systemText(s), out[0].content)
    }

    @Test fun anAssistantCallIsShownAsTheJsonTheModelIsHeldTo() {
        val call = PastToolCall("call_1", "search_files", """{"query":"bill"}""")
        val out = AgentTools.render(listOf(user("find the bill"), AgentMessage("assistant", "", listOf(call))), null)
        assertEquals("assistant", out[1].role)
        assertEquals("""{"tool_calls":[{"name":"search_files","arguments":{"query":"bill"}}]}""", out[1].content)
    }

    @Test fun wordsBeforeACallAreKept() {
        val call = PastToolCall("c", "f", "{}")
        val out = AgentTools.render(listOf(AgentMessage("assistant", "Looking.", listOf(call))), null)
        assertEquals("Looking.\n" + """{"tool_calls":[{"name":"f","arguments":{}}]}""", out.single().content)
    }

    @Test fun argumentsThatAreNotJsonAreShownAsText() {
        val out = AgentTools.render(listOf(AgentMessage("assistant", "", listOf(PastToolCall("c", "f", "not json")))), null)
        assertEquals("""{"tool_calls":[{"name":"f","arguments":"not json"}]}""", out.single().content)
    }

    @Test fun aToolResultIsAUserMessageThatNamesTheCall() {
        val call = PastToolCall("call_1", "search_files", """{"query":"bill"}""")
        val msgs = listOf(user("find"), AgentMessage("assistant", "", listOf(call)), AgentMessage("tool", "Electricity bill", toolCallId = "call_1"))
        val out = AgentTools.render(msgs, null)
        assertEquals(listOf("user", "assistant", "user"), out.map { it.role })
        assertEquals("Result of `search_files` (call call_1):\nElectricity bill", out[2].content)
    }

    @Test fun resultsInARowBecomeOneMessageInOrder() {
        val calls = listOf(PastToolCall("a", "f", "{}"), PastToolCall("b", "g", "{}"))
        val msgs = listOf(
            user("go"), AgentMessage("assistant", "", calls),
            AgentMessage("tool", "first", toolCallId = "a"), AgentMessage("tool", "second", toolCallId = "b"),
            user("and now?"),
        )
        val out = AgentTools.render(msgs, null)
        assertEquals(listOf("user", "assistant", "user", "user"), out.map { it.role })
        assertEquals("Result of `f` (call a):\nfirst\n\nResult of `g` (call b):\nsecond", out[2].content)
        assertEquals("and now?", out[3].content)
    }

    @Test fun aResultForACallNobodyMadeStillReachesTheModel() {
        val out = AgentTools.render(listOf(AgentMessage("tool", "late", toolCallId = "zzz")), null)
        assertEquals("Result of call zzz:\nlate", out.single().content)
    }

    // ---- reading the answer ----------------------------------------------------------------------------------

    private var next = 0
    private fun parse(text: String, spec: ToolSpec? = spec()) = AgentTools.parseReply(text, spec) { "id${next++}" }

    @Test fun callsAreRead() {
        val r = parse("""{"tool_calls":[{"name":"search_files","arguments":{"query":"bill"}},{"name":"get_time","arguments":{}}]}""") as ToolReply.Calls
        assertEquals(listOf("search_files", "get_time"), r.calls.map { it.name })
        assertEquals("""{"query":"bill"}""", r.calls[0].arguments)
        assertEquals("{}", r.calls[1].arguments)
        assertEquals(2, r.calls.map { it.id }.toSet().size)
    }

    @Test fun wordsAreRead() {
        assertEquals("The bill is 40.", (parse("""{"content":"The bill is 40."}""") as ToolReply.Content).text)
        assertEquals("", (parse("""{"content":""}""") as ToolReply.Content).text)
    }

    @Test fun garbageIsReturnedAsWordsAsItCame() {
        val garbage = listOf(
            "plain words", "", "{not json", "[1,2]", "42", "null", """{"other":1}""", """{"content":5}""", """{"content":null}""",
            """{"tool_calls":[]}""", """{"tool_calls":"x"}""", """{"tool_calls":[1]}""",
            """{"tool_calls":[{"name":"nope","arguments":{}}]}""",
            """{"tool_calls":[{"name":"search_files","arguments":"{\"query\":\"x\"}"}]}""",
            """{"tool_calls":[{"name":"search_files"}]}""",
            """{"tool_calls":[{"name":"search_files","arguments":{}},{"name":"nope","arguments":{}}]}""",
        )
        for (g in garbage) {
            val r = parse(g)
            assertTrue("'$g' should come back as words", r is ToolReply.Content)
            assertEquals(g, (r as ToolReply.Content).text)
        }
    }

    @Test fun moreCallsThanAllowedAreCutToTheLimit() {
        val one = """{"name":"get_time","arguments":{}}"""
        val r = parse("""{"tool_calls":[$one,$one,$one,$one,$one,$one]}""") as ToolReply.Calls
        assertEquals(4, r.calls.size)
        val single = parse("""{"tool_calls":[$one,$one]}""", spec(parallel = false)) as ToolReply.Calls
        assertEquals(1, single.calls.size)
    }

    @Test fun aNamedChoiceAcceptsOnlyThatTool() {
        val s = spec(ToolChoice.Named("get_time"))
        assertTrue(parse("""{"tool_calls":[{"name":"get_time","arguments":{}}]}""", s) is ToolReply.Calls)
        assertTrue(parse("""{"tool_calls":[{"name":"search_files","arguments":{"query":"x"}}]}""", s) is ToolReply.Content)
    }

    @Test fun withoutToolsTheTextIsTheAnswer() {
        val raw = """{"tool_calls":[{"name":"search_files","arguments":{}}]}"""
        assertEquals(raw, (parse(raw, null) as ToolReply.Content).text)
    }

    @Test fun callIdsLookLikeOpenAiIds() {
        val id = AgentTools.callId()
        assertTrue(id, Regex("^call_[0-9a-f]{24}$").matches(id))
        assertFalse(AgentTools.callId() == id)
    }

    // ---- a model server that refuses the schema --------------------------------------------------------------

    @Test fun aSchemaTheServerRefusesIsToldApart() {
        assertTrue(AgentTools.isSchemaRejection(LlamaServerException("""{"error":{"code":400,"message":"bad"}}""", 400)))
        assertTrue(AgentTools.isSchemaRejection(LlamaServerException("JSON schema conversion failed: Unrecognized schema", 500)))
        assertTrue(AgentTools.isSchemaRejection(LlamaServerException("Failed to parse grammar", 0)))
        // a phone of the swarm relays the server's message inside its own 500
        assertTrue(AgentTools.isSchemaRejection(NodeHttpException(500, """{"error":"Unrecognized schema"}""")))
        assertTrue(AgentTools.isSchemaRejection(IOException("phone failed", LlamaServerException("x", 400))))
    }

    @Test fun otherFailuresAreNotSchemaRejections() {
        assertFalse(AgentTools.isSchemaRejection(LlamaServerException("out of memory", 500)))
        assertFalse(AgentTools.isSchemaRejection(LlamaServerException("Loading model", 503)))
        assertFalse(AgentTools.isSchemaRejection(IOException("connection reset")))
        assertFalse(AgentTools.isSchemaRejection(NodeHttpException(500, """{"error":"out of memory"}""")))
        assertFalse(AgentTools.isSchemaRejection(IllegalStateException("the schema of the universe")))
        assertFalse(AgentTools.isSchemaRejection(RuntimeException()))
    }
}
