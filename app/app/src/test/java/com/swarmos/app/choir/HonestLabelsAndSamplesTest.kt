package com.swarmos.app.choir

import com.swarmos.app.core.SwarmJson
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Answers its solve calls from a list, one per call, after a delay: a phone sampled several times in a row. */
private class Seq(
    override val nodeId: String = "a",
    override val name: String = "Phone A",
    private val answers: List<String?>,
    private val confidences: List<Double> = emptyList(),
    private val delayMs: Long = 0,
) : ChoirParticipant {
    val calls = Collections.synchronizedList(mutableListOf<SolveRequest>())

    override suspend fun solve(req: SolveRequest): NodeAnswer {
        val i = calls.size
        calls.add(req)
        delay(delayMs)
        val a = answers.getOrNull(i)
        return if (a == null) NodeAnswer(req.taskId, 1, nodeId, name, "test", error = "no answer")
        else NodeAnswer(req.taskId, 1, nodeId, name, "test", answer = a, confidence = confidences.getOrElse(i) { 0.8 }, tokens = 10, ms = delayMs)
    }

    override suspend fun revise(req: ReviseRequest): NodeAnswer = throw UnsupportedOperationException()
    override suspend fun judge(req: JudgeRequest): JudgeAnswer = throw UnsupportedOperationException()
}

class HonestLabelsAndSamplesTest {
    private val problem = Problem(id = "t1", kind = Kind.MATH, text = "What is six times seven?", answer = "42", check = "6*7")

    // ---- item 2: what the tool check was based on ---------------------------------------------------

    private fun runWith(useProblemCheck: Boolean): RunTrace {
        val coord = ChoirCoordinator({ _, _ -> }, config = ChoirConfig(deadlineMs = 3_000))
        val a = Scripted("a", "Phone A", "42", steps = listOf("6*7=42"))
        val b = Scripted("b", "Phone B", "42", steps = listOf("6*7=42"))
        return runBlocking { coord.run(problem, "t-1", a, listOf(b), RunOptions(useProblemCheck)) }
    }

    @Test fun consistentStepsAloneAreNotATestSuppliedWithTheProblem() {
        val t = runWith(useProblemCheck = false)
        val f = t.finalPick!!
        assertEquals(CheckResult.VERIFIED, f.check)
        assertEquals("model-expression", f.checkSource)
        assertTrue(f.verified)
        assertEquals("model-expression", t.lanes[0].r1?.checkSource)
    }

    @Test fun theProblemsOwnTestIsReportedAsSuch() {
        val t = runWith(useProblemCheck = true)
        val f = t.finalPick!!
        assertEquals(CheckResult.VERIFIED, f.check)
        assertEquals(PROBLEM_CHECK, f.checkSource)
        assertEquals(PROBLEM_CHECK, t.lanes[1].r1?.checkSource)
    }

    @Test fun aTestSuppliedWithTheProblemWinsOverAConsistentExpressionInTheSameGroup() {
        val g = Group("42", listOf(
            Vote("a", "42", 0.8, CheckResult.VERIFIED, checkSource = "model-expression"),
            Vote("b", "42", 0.7, CheckResult.VERIFIED, checkSource = PROBLEM_CHECK),
        ))
        assertEquals(PROBLEM_CHECK, g.checkSource)
        assertEquals("none", Group("7", listOf(Vote("c", "7", 0.5))).checkSource)
    }

    @Test fun nothingCheckableCarriesNoSource() {
        val coord = ChoirCoordinator({ _, _ -> }, config = ChoirConfig(deadlineMs = 3_000))
        val t = runBlocking { coord.run(problem, "t-2", Scripted("a", "Phone A", "42"), listOf(Scripted("b", "Phone B", "42"))) }
        assertEquals(CheckResult.NOT_CHECKABLE, t.finalPick?.check)
        assertEquals("none", t.finalPick?.checkSource)
        assertFalse(t.finalPick!!.verified)
    }

    @Test fun evalCountsTestVerifiedAndConsistentSeparately() {
        val rows = listOf(runWith(false), runWith(true), runWith(true)).mapIndexed { i, t -> ChoirEval.rowOf(problem.copy(id = "e$i"), t) }
        assertEquals(listOf(false, true, true), rows.map { it.verifiedByTest })
        assertEquals(listOf(true, false, false), rows.map { it.consistent })
        assertTrue(rows.all { it.verified })
        val s = ChoirEval.summarize(rows)
        assertEquals(3, s.toolVerified)
        assertEquals(2, s.verifiedByTest)
        assertEquals(1, s.consistent)
    }

    // ---- item 3: one phone, three samples -------------------------------------------------------------

    @Test fun threeSamplesTakeTheMajority() = runBlocking {
        val self = Seq(answers = listOf("42", "7", "42"))
        val r = ChoirSamples.run(problem, "t-3", self)
        assertEquals("42", r.answer)
        assertEquals(listOf("42", "7", "42"), r.answers)
        assertEquals(3, r.answered)
        assertEquals(30, r.tokens)
    }

    @Test fun samplesUseTheChoirsOwnRolesSequentiallyWithDistinctSeeds() = runBlocking {
        val self = Seq(answers = listOf("1", "1", "1"), delayMs = 150)
        val t0 = System.currentTimeMillis()
        val r = ChoirSamples.run(problem, "t-4", self)
        val took = System.currentTimeMillis() - t0
        assertEquals(listOf(Role.SOLVER, Role.ALT_SOLVER, Role.SOLVER), self.calls.map { it.role })
        assertEquals(3, self.calls.map { it.seed }.toSet().size)
        assertEquals(3, self.calls.map { it.taskId }.toSet().size)
        assertTrue(self.calls.all { it.maxTokens == 300 && it.problem == problem.prompt() })
        assertTrue("one after another, took $took ms", took >= 430)
        assertTrue(r.wallMs >= 430)
    }

    @Test fun noMajorityFallsBackToConfidenceThenToTheFirstSample() = runBlocking {
        val lead = ChoirSamples.run(problem, "t-5", Seq(answers = listOf("1", "2", "3"), confidences = listOf(0.5, 0.95, 0.5)))
        assertEquals("2", lead.answer)
        val tied = ChoirSamples.run(problem, "t-6", Seq(answers = listOf("1", "2", "3")))
        assertEquals("1", tied.answer)
    }

    @Test fun aFailedSampleIsDroppedAndAllFailedGivesNoAnswer() = runBlocking {
        val partial = ChoirSamples.run(problem, "t-7", Seq(answers = listOf("42", null, "42")))
        assertEquals("42", partial.answer)
        assertEquals(2, partial.answered)
        val none = ChoirSamples.run(problem, "t-8", Seq(answers = listOf(null, null, null)))
        assertNull(none.answer)
        assertEquals(0, none.answered)
        assertEquals(0, none.tokens)
    }

    @Test fun evalScoresTheFourArmsAndRecordsSampleTimings() {
        val problems = listOf(
            Problem(id = "e1", kind = Kind.MATH, text = "one", answer = "42"),
            Problem(id = "e2", kind = Kind.MATH, text = "two", answer = "7"),
        )
        val coord = ChoirCoordinator({ _, _ -> }, config = ChoirConfig(deadlineMs = 3_000))
        val rows = runBlocking {
            ChoirEval.run(
                problems,
                runOne = { p -> coord.run(p, "t-" + p.id, Scripted("a", "Phone A", "42"), listOf(Scripted("b", "Phone B", "42"))) },
                runSamples = { p -> ChoirSamples.run(p, "s-" + p.id, Seq(answers = listOf("42", "42", "9"), delayMs = 20)) },
            )
        }
        assertEquals(listOf(true, false), rows.map { it.samplesCorrect })
        assertEquals(listOf(3, 3), rows.map { it.samplesAnswered })
        assertTrue(rows.all { it.samplesMs >= 55 && it.samplesTokens == 30 })
        val s = ChoirEval.summarize(rows)
        assertEquals(2, s.samplesN)
        assertEquals(1, s.samplesCorrect)
        assertEquals(0.5, s.samplesAccuracy, 1e-9)
        assertTrue(s.samplesMeanMs >= 55)
        assertEquals(1, s.baselineCorrect)
        assertEquals(1, s.choirCorrect)
    }

    @Test fun anEvalWithoutTheSamplesArmReportsNoSampleRows() {
        val coord = ChoirCoordinator({ _, _ -> }, config = ChoirConfig(deadlineMs = 3_000))
        val p = Problem(id = "e1", kind = Kind.MATH, text = "one", answer = "42")
        val rows = runBlocking { ChoirEval.run(listOf(p), { coord.run(it, "t-x", Scripted("a", "Phone A", "42"), emptyList()) }) }
        assertNull(rows[0].samplesAnswered)
        assertEquals(0, ChoirEval.summarize(rows).samplesN)
    }

    @Test fun reportsWithTheNewFieldsRoundTripAndOldOnesStillDecode() {
        val row = EvalRow("e1", Kind.MATH, "arith", "42", samplesAnswer = "42", samplesCorrect = true, samplesMs = 123, samplesAnswered = 3, verifiedByTest = true, verified = true)
        val report = EvalReport(1L, null, "FAKE", true, listOf("Phone A"), false, 60_000, ChoirEval.summarize(listOf(row)), listOf(row))
        val back = SwarmJson.decodeFromString<EvalReport>(SwarmJson.encodeToString(report))
        assertEquals(1, back.summary.samplesN)
        assertEquals(1, back.summary.verifiedByTest)
        assertEquals(3, back.rows[0].samplesAnswered)
        val old = """{"ts":1,"model":"FAKE","fake":true,"nodes":[],"useProblemCheck":false,"deadlineMs":60000,
            "summary":{"n":0,"baselineCorrect":0,"choirCorrect":0,"choirToolCorrect":0,"baselineAccuracy":0.0,"choirAccuracy":0.0,"choirToolAccuracy":0.0,"toolVerified":0,"abstained":0,"baselineMeanMs":0.0,"choirMeanMs":0.0,"meanRounds":0.0},
            "rows":[{"problemId":"e1","kind":"MATH","group":"arith","expected":"42"}]}"""
        val o = SwarmJson.decodeFromString<EvalReport>(old)
        assertEquals(0, o.summary.samplesN)
        assertNull(o.rows[0].samplesAnswered)
    }
}
