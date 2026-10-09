package com.swarmos.app.choir

import com.swarmos.app.core.SwarmJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProblemSetAndEvalTest {
    private fun loadProblems(): List<Problem> =
        Problem.parseList(File("src/main/assets/choir/problems.json").readText())

    // ---- the built-in set --------------------------------------------------------------------------

    @Test fun builtInSetHasTwentyProblemsInTheRequestedMix() {
        val ps = loadProblems()
        assertEquals(20, ps.size)
        assertEquals(20, ps.map { it.id }.toSet().size)
        assertEquals(mapOf("arith" to 8, "pct" to 4, "mcq" to 4, "code" to 4), ps.groupingBy { it.group }.eachCount())
        assertEquals(12, ps.count { it.kind == Kind.MATH })
        assertEquals(4, ps.count { it.kind == Kind.MCQ })
        assertEquals(4, ps.count { it.kind == Kind.CODE })
        assertTrue(ps.all { it.answer.isNotBlank() })
    }

    @Test fun everyMathCheckExpressionEvaluatesToItsAnswer() {
        for (p in loadProblems().filter { it.kind == Kind.MATH }) {
            val v = (ArithEval.eval(p.check!!) as ArithResult.Ok).value
            assertEquals(p.id, p.answer.toDouble(), v, 0.005)
        }
    }

    @Test fun mcqAndCodeProblemsCarryTheirKeyAndExpectedOutput() {
        for (p in loadProblems()) {
            when (p.kind) {
                Kind.MCQ -> {
                    assertEquals(4, p.options.size)
                    assertEquals(listOf(p.answer), p.expected)
                    assertTrue(p.answer.single() in p.optionLetters())
                }
                Kind.CODE -> assertEquals(listOf(p.answer), p.expected)
                else -> {}
            }
        }
    }

    @Test fun everyProblemFitsInAOneKilobyteSolveRequest() {
        for (p in loadProblems()) {
            assertTrue("${p.id} prompt is ${p.prompt().length} chars", p.prompt().length <= ChoirLimits.PROBLEM_MAX_CHARS)
            val req = SolveRequest("t-12345678", p.prompt(), p.kind, Role.ALT_SOLVER, 300, 60_000, seed = Int.MAX_VALUE)
            assertTrue("${p.id} request is ${wireBytes(req)} bytes", wireBytes(req) <= ChoirLimits.WIRE_MAX_BYTES)
        }
    }

    @Test fun theProblemsOwnCheckVerifiesTheKnownAnswerAndRefutesAWrongOne() {
        for (p in loadProblems()) {
            val right = ToolChecker.check(p, p.answer, emptyList(), useProblemCheck = true)
            assertEquals("${p.id}: ${right.detail}", CheckResult.VERIFIED, right.result)
            val wrongAnswer = when (p.kind) {
                Kind.MATH -> (p.answer.toDouble() + 1).toString()
                Kind.MCQ -> if (p.answer == "A") "B" else "A"
                else -> p.answer + "0"
            }
            assertEquals(p.id, CheckResult.REFUTED, ToolChecker.check(p, wrongAnswer, emptyList(), useProblemCheck = true).result)
        }
    }

    @Test fun withoutTheKeyOnlyModelWrittenExpressionsCanVerify() {
        for (p in loadProblems()) {
            val r = ToolChecker.check(p, p.answer, emptyList(), useProblemCheck = false)
            assertEquals(p.id, CheckResult.NOT_CHECKABLE, r.result)
        }
        val p = loadProblems().first { it.id == "m1" }
        assertEquals(CheckResult.VERIFIED, ToolChecker.check(p, "3654", listOf(p.check!!), useProblemCheck = false).result)
    }

    // ---- eval harness ----------------------------------------------------------------------------------

    private fun tracesFor(problems: List<Problem>): List<RunTrace> {
        val coord = ChoirCoordinator({ _, _ -> }, config = ChoirConfig(deadlineMs = 3_000))
        return problems.map { p ->
            runBlocking {
                coord.run(p, "t-" + p.id, Scripted("a", "Phone A", "42"), listOf(Scripted("b", "Phone B", "42"), Scripted("c", "Phone C", "42")))
            }
        }
    }

    @Test fun evalScoresThreeArmsFromTheSameRuns() {
        val problems = listOf(
            Problem(id = "e1", kind = Kind.MATH, text = "one", answer = "42"),
            Problem(id = "e2", kind = Kind.MATH, text = "two", answer = "7"),
            Problem(id = "e3", kind = Kind.MATH, text = "three", answer = "42"),
        )
        val coord = ChoirCoordinator({ _, _ -> }, config = ChoirConfig(deadlineMs = 3_000))
        val rows = runBlocking {
            ChoirEval.run(problems, { p -> coord.run(p, "t-" + p.id, Scripted("a", "Phone A", "42"), listOf(Scripted("b", "Phone B", "42"))) })
        }
        assertEquals(listOf(true, false, true), rows.map { it.baselineCorrect })
        assertEquals(listOf(true, false, true), rows.map { it.choirCorrect })
        assertEquals(listOf(true, false, true), rows.map { it.choirToolCorrect })
        assertTrue(rows.all { it.rounds == 1 && it.nodesAnswered == 2 && it.nodesTotal == 2 })
        val s = ChoirEval.summarize(rows)
        assertEquals(3, s.n)
        assertEquals(2, s.baselineCorrect)
        assertEquals(2.0 / 3, s.choirToolAccuracy, 1e-9)
        assertEquals(1.0, s.meanRounds, 1e-9)
    }

    @Test fun evalSurvivesARunThatThrowsAndStoresTheReport() {
        val problems = listOf(Problem(id = "e1", kind = Kind.MATH, text = "one", answer = "42"), Problem(id = "e2", kind = Kind.MATH, text = "two", answer = "7"))
        val trace = tracesFor(listOf(problems[0])).single()
        val seen = mutableListOf<Pair<Int, String?>>()
        val rows = runBlocking {
            ChoirEval.run(
                problems,
                runOne = { p -> if (p.id == "e1") trace else error("boom") },
                onProgress = { done, cur -> seen.add(done.size to cur?.id) },
            )
        }
        assertEquals("boom", rows[1].error)
        assertFalse(rows[1].choirCorrect)
        assertEquals(listOf(0 to "e1", 1 to "e2", 2 to null), seen)

        val dir = File(System.getProperty("java.io.tmpdir"), "choir-eval-" + System.nanoTime())
        val report = ChoirEval.store(
            dir,
            EvalReport(ts = 1760000000000, model = "FAKE", fake = true, nodes = listOf("Phone A"), useProblemCheck = false, deadlineMs = 60_000, summary = ChoirEval.summarize(rows), rows = rows),
        )
        val f = File(report.file!!)
        assertTrue(f.exists())
        assertEquals("choir-eval-1760000000000.json", f.name)
        val back = SwarmJson.decodeFromString<EvalReport>(f.readText())
        assertEquals(2, back.rows.size)
        assertEquals(2, back.summary.n)
        assertNotNull(back.rows[0].choirToolAnswer)
    }
}
