package com.swarmos.app.choir

import com.swarmos.app.core.SwarmJson
import com.swarmos.app.llm.ChatChunk
import com.swarmos.app.llm.ChatRequest
import com.swarmos.app.llm.ChatResult
import com.swarmos.app.llm.ChatStats
import com.swarmos.app.llm.LlmClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The real outputs that broke the Choir answers. The first block is what the e2e run on Phones A and C stored
 * (docs/benchmarks/2026-10-10-e2e-choir.md); the raw replies are what Qwen3.5-4B returned for the exact solve request
 * on Phone A (see docs/benchmarks/2026-10-10-choir-answer-quality.md).
 */
class AnswerQualityTest {
    // ---- the scorer is strict: the answers the e2e run counted or mis-scored ---------------------------------

    @Test fun answersThatAreNotTheValueAreWrong() {
        val wrong = listOf(
            "{240}" to "240",                                   // counted correct before
            "{800 * 1.25 * 0.80}" to "800",                     // counted correct before (first number)
            "{264+120}" to "384",
            "{155.5}" to "164.5",
            "," to "80", ":" to "80", "}" to "240", "none" to "80", "math" to "486", "" to "80",
            "And 126 * 9 = 1134" to "3654",
            "After calculating base cost plus extra days cost, total is 4" to "486",
            "When price is raised 25% then 20% discount, final price is s" to "800",
        )
        for ((got, expected) in wrong) {
            assertFalse("'$got' must not match '$expected'", Normalizer.same(Kind.MATH, got, expected))
            assertFalse("'$got' is not a number", AnswerCheck.isValid(Kind.MATH, got))
        }
    }

    @Test fun answersThatAreTheValueWithOnlyLegitimateDecoration() {
        for (ok in listOf("240", "240.0", " 240 ", "240.00", "Rs 240", "Rs. 240", "₹240", "\$240", "240 rupees", "240 ml", "80,000", "Rs 80,000", "80,000.00")) {
            assertNotNull("'$ok' is a number", Normalizer.parseNumberStrict(ok))
        }
        assertTrue(Normalizer.same(Kind.MATH, "Rs 80,000", "80000"))
        assertTrue(Normalizer.same(Kind.MATH, "164.5 rupees", "164.5"))
        assertTrue(Normalizer.same(Kind.MATH, "15%", "15"))
        assertEquals(0.75, Normalizer.parseNumberStrict("3/4")!!, 1e-9)
        assertEquals(-5.0, Normalizer.parseNumberStrict("−5")!!, 1e-9)
    }

    @Test fun anythingElseIsNotANumber() {
        for (bad in listOf("{240}", "[240]", "240}", "x = 12", "12 and 13", "3 hours 40 minutes", "800 * 1.25", "1,2", "", " ", "-", "1e3", "12abc.def", "two hundred")) {
            assertNull("'$bad' is not a number", Normalizer.parseNumberStrict(bad))
        }
    }

    @Test fun mcqAnswersMustBeTheLetterNotASentence() {
        assertTrue(AnswerCheck.isValid(Kind.MCQ, "B", listOf('A', 'B', 'C', 'D')))
        assertTrue(AnswerCheck.isValid(Kind.MCQ, "b)", listOf('A', 'B', 'C', 'D')))
        assertTrue(AnswerCheck.isValid(Kind.MCQ, "B) Box 2", listOf('A', 'B', 'C', 'D')))
        assertFalse(AnswerCheck.isValid(Kind.MCQ, "E", listOf('A', 'B', 'C', 'D')))
        assertFalse(AnswerCheck.isValid(Kind.MCQ, "The answer is B", listOf('A', 'B', 'C', 'D')))
        assertFalse(AnswerCheck.isValid(Kind.MCQ, "", listOf('A', 'B', 'C', 'D')))
        assertFalse(Normalizer.same(Kind.MCQ, "The answer is B", "B"))
        assertTrue(Normalizer.same(Kind.MCQ, "B", "b)"))
    }

    @Test fun codeAndTextAnswersAreNeverEmptyOrJustPunctuation() {
        for (bad in listOf("", " ", ",", ":", "}", "{", "...", "-")) {
            assertFalse("'$bad'", AnswerCheck.isValid(Kind.CODE, bad))
            assertFalse("'$bad'", AnswerCheck.isValid(Kind.TEXT, bad))
        }
        for (ok in listOf("37", "4 13", "mra", "True", "[1, 2]")) assertTrue("'$ok'", AnswerCheck.isValid(Kind.CODE, ok))
        assertTrue(AnswerCheck.isValid(Kind.CODE, "[]"))        // an empty list is a legitimate program output
        assertTrue(AnswerCheck.isValid(Kind.TEXT, "in Paris"))
    }

    @Test fun theScorerRejectsTheBraceAnswerEndToEnd() {
        val p = Problem(id = "p2", kind = Kind.MATH, text = "q", answer = "240")
        val coord = ChoirCoordinator({ _, _ -> }, config = ChoirConfig(deadlineMs = 3_000))
        val t = runBlocking { coord.run(p, "t-1", Scripted("a", "Phone A", "{240}"), emptyList()) }
        val row = ChoirEval.rowOf(p, t)
        assertFalse(row.baselineCorrect)
        assertFalse(row.choirCorrect)
        assertEquals("t:{240}", row.baselineNormalised)
        val good = ChoirEval.rowOf(p, runBlocking { coord.run(p, "t-2", Scripted("a", "Phone A", "240"), emptyList()) })
        assertTrue(good.baselineCorrect)
        assertEquals("n:240", good.baselineNormalised)
    }

    // ---- the schema: the answer can only be what the kind allows ------------------------------------------------

    private fun answerSchema(kind: Kind, letters: List<Char> = emptyList(), short: Boolean = false): JsonObject {
        val root = SwarmJson.parseToJsonElement(ChoirPrompts.solveSchema(kind, letters, short)) as JsonObject
        return (root["properties"] as JsonObject)["answer"] as JsonObject
    }

    @Test fun theAnswerIsTypedPerKind() {
        assertEquals("number", (answerSchema(Kind.MATH)["type"] as JsonPrimitive).content)
        val mcq = answerSchema(Kind.MCQ, listOf('A', 'B', 'C'))
        assertEquals(listOf("A", "B", "C"), (mcq["enum"] as JsonArray).map { (it as JsonPrimitive).content })
        assertEquals(listOf("A", "B", "C", "D"), (answerSchema(Kind.MCQ)["enum"] as JsonArray).map { (it as JsonPrimitive).content })
        for (k in listOf(Kind.CODE, Kind.TEXT)) {
            val a = answerSchema(k)
            assertEquals("string", (a["type"] as JsonPrimitive).content)
            assertEquals("1", (a["minLength"] as JsonPrimitive).content)
            assertEquals("60", (a["maxLength"] as JsonPrimitive).content)
        }
    }

    @Test fun everyKeyStepIsCappedSoTheAnswerAlwaysHasRoom() {
        for (kind in Kind.entries) {
            val root = SwarmJson.parseToJsonElement(ChoirPrompts.solveSchema(kind)) as JsonObject
            val steps = (root["properties"] as JsonObject)["key_steps"] as JsonObject
            assertEquals("3", (steps["maxItems"] as JsonPrimitive).content)
            assertEquals("100", ((steps["items"] as JsonObject)["maxLength"] as JsonPrimitive).content)
            // the properties keep their order: reasoning, then the answer, then confidence
            assertEquals(listOf("key_steps", "answer", "confidence"), (root["properties"] as JsonObject).keys.toList())
        }
        val short = (SwarmJson.parseToJsonElement(ChoirPrompts.solveSchema(Kind.MATH, short = true)) as JsonObject)["properties"] as JsonObject
        assertEquals("1", ((short["key_steps"] as JsonObject)["maxItems"] as JsonPrimitive).content)
    }

    @Test fun optionLettersComeFromTheProblemText() {
        assertEquals(listOf('A', 'B', 'C'), AnswerCheck.optionLetters("Pick one\nA) red\nB) blue\nC) green"))
        assertEquals(listOf('A', 'B', 'C', 'D'), AnswerCheck.optionLetters("a question without options"))
    }

    // ---- a lane with no valid answer: retried once, then excluded --------------------------------------------------

    private class ScriptedLlm(private val replies: List<String>, private val tokens: Int = 40) : LlmClient {
        override val modelName = "test-model"
        val requests = mutableListOf<ChatRequest>()
        override suspend fun ready() = true
        override fun chatStream(req: ChatRequest): Flow<ChatChunk> = flowOf()
        override suspend fun chat(req: ChatRequest): ChatResult {
            requests.add(req)
            return ChatResult(replies[minOf(requests.size - 1, replies.lastIndex)], ChatStats(5, tokens, 1.0, 1.0))
        }
        override suspend fun embed(texts: List<String>): List<FloatArray> = emptyList()
    }

    // exact replies of Qwen3.5-4B to the old solve request (free-string answer), Phone A, 2026-10-10
    private val emptyAnswer = """{"key_steps": ["3 notebooks at 45 each = 135", "2 pens at 18 each = 36", "Total cost = 171, Change = 329, Half spent = 164.5, Left = 164.5"], "answer": "", "confidence": 0.0}"""
    private val braceAnswer = """{"key_steps": ["Sugar is 360 ml, water is 600 ml.", "Add x ml water: 360/(600+x) = 3/7.", "Solve for x: x = 120."], "answer": "}", "confidence": 0.9}"""
    private val goodAnswer = """{"key_steps": ["Left = 164.5"], "answer": 164.5, "confidence": 0.9}"""

    private fun node(llm: LlmClient) = ChoirNode({ llm }, { NodeIdentity("a", "Phone A") })
    private fun req(budget: Long = 10_000) = SolveRequest("t", "Ravi buys 3 notebooks at Rs 45 each.\nHow much is left?", Kind.MATH, Role.SOLVER, 300, budget)

    @Test fun anEmptyOrPunctuationAnswerIsRetriedOnceWithAShorterInstruction() = runBlocking {
        for (bad in listOf(emptyAnswer, braceAnswer)) {
            val llm = ScriptedLlm(listOf(bad, goodAnswer))
            val a = node(llm).solve(req())
            assertNull(a.error)
            assertEquals("164.5", a.answer)
            assertTrue(a.retried)
            assertEquals("tokens of both calls are counted", 80, a.tokens)
            assertEquals(2, llm.requests.size)
            val retry = llm.requests[1]
            assertTrue(retry.messages.first().content.contains("never empty"))
            assertTrue("one short key step", retry.jsonSchema!!.contains("\"maxItems\":1"))
        }
    }

    @Test fun aValidAnswerIsNeverRetried() = runBlocking {
        val llm = ScriptedLlm(listOf(goodAnswer))
        val a = node(llm).solve(req())
        assertFalse(a.retried)
        assertEquals(1, llm.requests.size)
    }

    @Test fun aSecondInvalidReplyIsMarkedNoValidAnswer() = runBlocking {
        val llm = ScriptedLlm(listOf(emptyAnswer, braceAnswer))
        val a = node(llm).solve(req())
        assertEquals("no valid answer", a.error)
        assertEquals("", a.answer)
        assertTrue(a.retried)
        assertEquals(80, a.tokens)
    }

    @Test fun noRetryWhenTheBudgetIsAlmostGone() = runBlocking {
        val llm = ScriptedLlm(listOf(emptyAnswer, goodAnswer))
        val a = node(llm).solve(req(budget = 300))
        assertEquals("no valid answer", a.error)
        assertEquals(1, llm.requests.size)
    }

    @Test fun aLaneWithNoValidAnswerIsExcludedFromTheVoteAndNeverShownToTheOthers() {
        val a = Scripted("a", "Phone A", "42")
        val b = Scripted("b", "Phone B", "7")
        val c = Scripted("c", "Phone C", null, r1Error = "no valid answer")
        val coord = ChoirCoordinator({ _, _ -> }, config = ChoirConfig(deadlineMs = 5_000))
        val t = runBlocking { coord.run(Problem(id = "t", kind = Kind.MATH, text = "q", answer = "42"), "t-1", a, listOf(b, c)) }
        assertEquals(LaneStatus.NO_VALID_ANSWER, t.lanes[2].status)
        assertEquals("no valid answer", t.lanes[2].error)
        assertNull(t.lanes[2].r1)
        assertEquals(2, t.matrix!!.names.size)
        assertTrue(c.reviseCalls.isEmpty())
        for (call in a.reviseCalls + b.reviseCalls) assertEquals(1, call.others.size)   // each sees the one other valid answer
    }

    // ---- the revise round shows clean values, never raw JSON ------------------------------------------------------

    @Test fun theReviseRoundShowsEachAnswerAsACleanValue() {
        val msgs = ChoirPrompts.revise(
            Kind.MATH, "What is six times seven?",
            Candidate("42", listOf("6*7=42")), listOf(Candidate("36", listOf("6*6=36")), Candidate("40")),
        )
        val user = msgs.last().content
        assertTrue(user, user.contains("Your earlier answer. Answer: 42\n   Steps: 6*7=42"))
        assertTrue(user, user.contains("1. Answer: 36\n   Steps: 6*6=36"))
        assertTrue(user, user.contains("2. Answer: 40\n"))
        assertFalse("no raw JSON in the prompt", user.contains("{") || user.contains("key_steps") || user.contains("\"answer\""))
    }
}
