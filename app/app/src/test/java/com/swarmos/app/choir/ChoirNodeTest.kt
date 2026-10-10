package com.swarmos.app.choir

import com.swarmos.app.llm.ChatChunk
import com.swarmos.app.llm.ChatRequest
import com.swarmos.app.llm.ChatResult
import com.swarmos.app.llm.ChatStats
import com.swarmos.app.llm.FakeLlmClient
import com.swarmos.app.llm.LlmClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** An LlmClient that answers with whatever [reply] returns and remembers every request. */
private class TextLlm(
    override val modelName: String = "test-model",
    private val delayMs: Long = 0,
    private val fail: Boolean = false,
    private val reply: (ChatRequest) -> String,
) : LlmClient {
    val requests = mutableListOf<ChatRequest>()
    override suspend fun ready() = true
    override fun chatStream(req: ChatRequest): Flow<ChatChunk> = flowOf(ChatChunk(reply(req), done = true))
    override suspend fun chat(req: ChatRequest): ChatResult {
        requests.add(req)
        if (delayMs > 0) delay(delayMs)
        if (fail) error("llama-server went away")
        return ChatResult(reply(req), ChatStats(promptTokens = 11, completionTokens = 22, promptMs = 1.0, genMs = 2.0))
    }
    override suspend fun embed(texts: List<String>): List<FloatArray> = emptyList()
}

class ChoirNodeTest {
    private val me = NodeIdentity("n1", "Phone B")
    private fun node(llm: LlmClient, problems: List<Problem> = emptyList()) = ChoirNode({ llm }, { me }, { problems })
    private fun solveReq(role: Role = Role.SOLVER, kind: Kind = Kind.MATH, budget: Long = 5_000) =
        SolveRequest("t1", "What is six times seven?", kind, role, 300, budget, seed = 5)

    private val good = """{"key_steps":["6*7=42"],"answer":"42","confidence":0.9}"""

    @Test fun solveParsesConstrainedJsonAndCallsTheModelWithTheRoleSettings() = runBlocking {
        val llm = TextLlm { good }
        val a = node(llm).solve(solveReq(Role.SOLVER))
        assertNull(a.error)
        assertEquals("42", a.answer)
        assertEquals(0.9, a.confidence, 1e-9)
        assertEquals(listOf("6*7=42"), a.keySteps)
        assertEquals(22, a.tokens)
        assertEquals("test-model", a.model)
        assertFalse(a.fake)
        val req = llm.requests.single()
        assertEquals(ChoirPrompts.solveSchema(Kind.MATH, listOf('A', 'B', 'C', 'D')), req.jsonSchema)
        assertEquals(300, req.maxTokens)
        assertEquals(ChoirPrompts.TEMP_SOLVER, req.temperature, 1e-9)
        assertEquals(5, req.seed)
        assertTrue(req.messages.last().content.contains("six times seven"))
        assertFalse(req.messages.first().content.contains("no_think"))   // the LLM client switches thinking off itself
    }

    @Test fun altSolverGetsADifferentMethodAndTemperature() = runBlocking {
        val llm = TextLlm { good }
        node(llm).solve(solveReq(Role.ALT_SOLVER))
        val req = llm.requests.single()
        assertEquals(ChoirPrompts.TEMP_ALT_SOLVER, req.temperature, 1e-9)
        assertTrue(req.messages.first().content.contains("ONE arithmetic expression"))
        val code = TextLlm { good }
        node(code).solve(solveReq(Role.ALT_SOLVER, Kind.CODE))
        assertTrue(code.requests.single().messages.first().content.contains("one concrete test case"))
    }

    @Test fun schemaIsValidJsonWithTheThreeFieldsAndAtMostThreeSteps() {
        val root = com.swarmos.app.core.SwarmJson.parseToJsonElement(ChoirPrompts.solveSchema(Kind.MATH)) as kotlinx.serialization.json.JsonObject
        val props = root["properties"] as kotlinx.serialization.json.JsonObject
        assertEquals(setOf("key_steps", "answer", "confidence"), props.keys)
        assertEquals("3", ((props["key_steps"] as kotlinx.serialization.json.JsonObject)["maxItems"]).toString())
        com.swarmos.app.core.SwarmJson.parseToJsonElement(ChoirPrompts.JUDGE_SCHEMA)
    }

    @Test fun truncatedOutputStillYieldsTheAnswer() {
        val p = ChoirNode.parseSolve("""{"key_steps":["6*7=42"],"answer":"17","confi""")
        assertEquals("17", p?.answer)
        assertEquals(0.5, p!!.confidence, 1e-9)
        assertNull(ChoirNode.parseSolve("""{"key_steps":["a","b"],"ans"""))
        assertNull(ChoirNode.parseSolve("I think it is 42"))
    }

    @Test fun fencedJsonAndPercentConfidenceAreAccepted() {
        val p = ChoirNode.parseSolve("```json\n{\"key_steps\":[],\"answer\":\"B\",\"confidence\":85}\n```")
        assertEquals("B", p?.answer)
        assertEquals(0.85, p!!.confidence, 1e-9)
        assertEquals("7", ChoirNode.parseSolve("""{"key_steps":[],"answer":7,"confidence":1.5}""")?.answer)
        assertEquals(1.0, ChoirNode.parseSolve("""{"key_steps":[],"answer":7,"confidence":1.0}""")!!.confidence, 1e-9)
    }

    @Test fun garbageIsAnErrorNotACrash() = runBlocking {
        val a = node(TextLlm { "sorry, I cannot do that" }).solve(solveReq())
        assertNotNull(a.error)
        assertEquals("", a.answer)
    }

    @Test fun modelFailureAndTimeoutAreReportedAsErrors() = runBlocking {
        val failed = node(TextLlm(fail = true) { good }).solve(solveReq())
        assertTrue(failed.error!!.contains("llama-server went away"))
        val slow = node(TextLlm(delayMs = 5_000) { good }).solve(solveReq(budget = 100))
        assertTrue(slow.error!!.startsWith("timeout"))
    }

    @Test fun reviseUsesTheStoredProblemAndOwnAnswer() = runBlocking {
        val llm = TextLlm { good }
        val n = node(llm)
        n.solve(solveReq())
        val r = n.revise(ReviseRequest("t1", listOf(Candidate("36", listOf("6*6=36")), Candidate("40"))))
        assertNull(r.error)
        assertEquals(2, r.round)
        val req = llm.requests.last()
        assertEquals(ChoirPrompts.TEMP_REVISE, req.temperature, 1e-9)
        assertEquals(150, req.maxTokens)
        val user = req.messages.last().content
        assertTrue(user.contains("six times seven"))
        assertTrue(user, user.contains("Your earlier answer. Answer: 42"))
        assertTrue(user, user.contains("1. Answer: 36\n   Steps: 6*6=36"))
        assertFalse(user.contains("Phone"))   // no author names
        assertNotNull(node(llm).revise(ReviseRequest("never-seen", listOf(Candidate("1")))).error)
    }

    @Test fun judgePicksByIndexAndRejectsGarbage() = runBlocking {
        val n = node(TextLlm { """{"reason":"b recomputed it","pick":1}""" })
        n.solve(solveReq())
        val j = n.judge(JudgeRequest("t1", listOf(Candidate("36"), Candidate("42"))))
        assertNull(j.error)
        assertEquals(1, j.pick)
        assertEquals("b recomputed it", j.reason)
        val bad = node(TextLlm { """{"reason":"x","pick":9}""" })
        bad.solve(solveReq())
        assertNotNull(bad.judge(JudgeRequest("t1", listOf(Candidate("36"), Candidate("42")))).error)
        assertEquals(2 to "ok", ChoirNode.parseJudge("""{"pick":2,"reason":"ok"}"""))
        assertEquals(1 to "r", ChoirNode.parseJudge("""{"reason":"r","pick":1"""))   // truncated
        assertNull(ChoirNode.parseJudge("no idea"))
    }

    @Test fun scriptedAnswersReplaceTheModelAndAreFlaggedFake() = runBlocking {
        val llm = TextLlm { good }
        val n = node(llm)
        n.script = FakeScript(r1 = ScriptedAnswer(listOf("6*6=36"), "36", 0.7), r2 = ScriptedAnswer(answer = "42", confidence = 0.9), judge = ScriptedJudge("because", 1))
        val a = n.solve(solveReq())
        assertEquals("36", a.answer)
        assertEquals(0.7, a.confidence, 1e-9)
        assertTrue(a.fake)
        assertEquals("FAKE", a.model)
        assertEquals("42", n.revise(ReviseRequest("t1", listOf(Candidate("42")))).answer)
        assertEquals(1, n.judge(JudgeRequest("t1", listOf(Candidate("36"), Candidate("42")))).pick)
        assertTrue(llm.requests.isEmpty())   // the real client was never called
        n.script = null
        assertEquals("42", n.solve(solveReq()).answer)
        assertEquals(1, llm.requests.size)
    }

    @Test fun scriptedDelayCanSimulateATimeout() = runBlocking {
        val n = node(TextLlm { good })
        n.script = FakeScript(r1 = ScriptedAnswer(answer = "36"), delayMs = 5_000)
        val a = n.solve(solveReq(budget = 100))
        assertTrue(a.error!!.startsWith("timeout"))
    }

    @Test fun theFakeModelAnswersBuiltInProblemsDeterministically() = runBlocking {
        val p = Problem(id = "x1", kind = Kind.MATH, text = "What is six times seven? Give a number.", answer = "42", check = "6*7")
        val n = node(FakeLlmClient(), listOf(p))
        val req = SolveRequest("task-7", p.prompt(), Kind.MATH, Role.SOLVER, 300, 10_000)
        val a1 = n.solve(req)
        val a2 = n.solve(req)
        assertNull(a1.error)
        assertTrue(a1.fake)
        assertEquals("FAKE", a1.model)
        assertEquals(a1.answer, a2.answer)
        assertTrue(a1.keySteps.single().startsWith("6*7 = "))
    }

    @Test fun theFakeModelTellsProblemsWithTheSameOpeningLineApart() = runBlocking {
        val head = "What does this Python program print? Give the exact output.\n"
        val p1 = Problem(id = "c1", kind = Kind.CODE, text = head + "print(37)", answer = "37", expected = listOf("37"))
        val p2 = Problem(id = "c2", kind = Kind.CODE, text = head + "print(4, 13)", answer = "4 13", expected = listOf("4 13"))
        val n = node(FakeLlmClient(), listOf(p1, p2))
        n.script = FakeScript(delayMs = 1)     // keep the fake model's answers, only make them quick
        val a = n.solve(SolveRequest("task-8", p2.prompt(), Kind.CODE, Role.SOLVER, 300, 10_000))
        assertTrue(a.answer, a.answer.startsWith("4 13"))
    }

    @Test fun answersFromTheNodeStayUnderOneKilobyte() = runBlocking {
        val long = "x".repeat(500)
        val n = node(TextLlm { """{"key_steps":["$long","$long","$long"],"answer":"$long","confidence":0.5}""" })
        val a = n.solve(solveReq())
        assertTrue(wireBytes(a) <= ChoirLimits.WIRE_MAX_BYTES)
        assertTrue(a.answer.length <= ChoirLimits.ANSWER_MAX_CHARS)
    }
}
