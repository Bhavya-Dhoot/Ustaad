package com.swarmos.app.choir

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A phone whose answer takes [solveMs] and whose heartbeat can stop at any moment ([gone]). */
private class Flaky(override val nodeId: String, override val name: String, private val answer: String, private val solveMs: Long) : ChoirParticipant {
    @Volatile var gone = false
    override fun isAway() = gone
    override suspend fun solve(req: SolveRequest): NodeAnswer {
        delay(solveMs)
        return NodeAnswer(req.taskId, 1, nodeId, name, "test", answer = answer, confidence = 0.8, tokens = 5)
    }
    override suspend fun revise(req: ReviseRequest) = NodeAnswer(req.taskId, 2, nodeId, name, "test", answer = answer, confidence = 0.8)
    override suspend fun judge(req: JudgeRequest) = JudgeAnswer(req.taskId, nodeId, name, "test", error = "none")
}

/** A phone whose replies are built by the test, so they can be wrong: another task, another round, an unusable answer. */
private class Raw(
    override val nodeId: String, override val name: String,
    private val r1: (SolveRequest) -> NodeAnswer, private val r2: ((ReviseRequest) -> NodeAnswer)? = null,
) : ChoirParticipant {
    var revised = 0
    override suspend fun solve(req: SolveRequest) = r1(req)
    override suspend fun revise(req: ReviseRequest): NodeAnswer { revised++; return r2?.invoke(req) ?: r1(SolveRequest(req.taskId, "", Kind.MATH, Role.SOLVER)) }
    override suspend fun judge(req: JudgeRequest) = JudgeAnswer(req.taskId, nodeId, name, "test", error = "none")
}

class ChoirHardeningTest {
    private val problem = Problem(id = "t1", kind = Kind.MATH, text = "What is six times seven?", answer = "42")
    private fun coordinator(deadlineMs: Long = 5_000, audit: (String, JsonElement) -> Unit = { _, _ -> }) = ChoirCoordinator(audit, config = ChoirConfig(deadlineMs = deadlineMs))
    private fun ok(req: SolveRequest, node: String, answer: String) = NodeAnswer(req.taskId, 1, node, node, "test", answer = answer, confidence = 0.8, tokens = 3)

    // ---- finding 10: a phone that drops mid-round does not hold the round ----------------------------------------------

    @Test fun aPhoneFlaggedAwayInTheMiddleOfARoundIsDroppedAtOnceAndTheOthersAreKept() {
        val flaky = Flaky("c", "Phone C", "42", solveMs = 20_000)
        val t0 = System.currentTimeMillis()
        val t = runBlocking {
            launch(Dispatchers.Default) { delay(300); flaky.gone = true }
            coordinator(deadlineMs = 60_000).run(problem, "t-1", Scripted("a", "Phone A", "42"), listOf(Scripted("b", "Phone B", "42"), flaky))
        }
        val took = System.currentTimeMillis() - t0
        assertTrue("took $took ms; the round's deadline is 60000 ms and the phone would answer after 20000 ms", took < 3_000)
        assertEquals(LaneStatus.AWAY, t.lanes[2].status)
        assertTrue(t.lanes[2].error!!.contains("away"))
        assertNull(t.lanes[2].r1)
        assertEquals("42", t.finalPick?.answer)
        assertEquals("DONE", t.phase)
        assertEquals(2, t.matrix!!.names.size) // the two phones that answered are the vote
    }

    @Test fun aPhoneThatStaysUpIsWaitedFor() {
        val slow = Flaky("c", "Phone C", "42", solveMs = 400)
        val t = runBlocking { coordinator().run(problem, "t-2", Scripted("a", "Phone A", "42"), listOf(slow)) }
        assertEquals(LaneStatus.ANSWERED, t.lanes[1].status)
        assertEquals(2, t.matrix!!.names.size)
    }

    // ---- finding 10: a peer's answer is checked like this phone's own ---------------------------------------------------

    @Test fun anUnusableAnswerFromAPeerIsNotVotedOn() {
        val bad = Raw("b", "Phone B", { ok(it, "b", "banana") })
        val t = runBlocking { coordinator().run(problem, "t-3", Scripted("a", "Phone A", "42"), listOf(bad, Scripted("c", "Phone C", "42"))) }
        assertEquals(LaneStatus.NO_VALID_ANSWER, t.lanes[1].status)
        assertNull(t.lanes[1].r1)
        assertEquals(2, t.matrix!!.names.size)
        assertEquals("42", t.finalPick?.answer)
    }

    @Test fun anAnswerForAnotherTaskIsRefused() {
        val stale = Raw("b", "Phone B", { NodeAnswer("some-other-task", 1, "b", "Phone B", "test", answer = "7", confidence = 1.0) })
        val t = runBlocking { coordinator().run(problem, "t-4", Scripted("a", "Phone A", "42"), listOf(stale)) }
        assertEquals(LaneStatus.ERROR, t.lanes[1].status)
        assertTrue(t.lanes[1].error!!.contains("another task"))
        assertNull(t.lanes[1].r1)
    }

    @Test fun anAnswerForTheWrongRoundIsRefused() {
        val wrongRound = Raw("b", "Phone B", { NodeAnswer(it.taskId, 2, "b", "Phone B", "test", answer = "7", confidence = 1.0) })
        val t = runBlocking { coordinator().run(problem, "t-5", Scripted("a", "Phone A", "42"), listOf(wrongRound)) }
        assertEquals(LaneStatus.ERROR, t.lanes[1].status)
        assertTrue(t.lanes[1].error!!.contains("round"))
    }

    @Test fun anUnusableRevisedAnswerKeepsTheRoundOneAnswer() {
        val b = Raw("b", "Phone B", { ok(it, "b", "7") }, r2 = { NodeAnswer(it.taskId, 2, "b", "Phone B", "test", answer = "{", confidence = 1.0) })
        val t = runBlocking { coordinator().run(problem, "t-6", Scripted("a", "Phone A", "42", r2 = "42"), listOf(b)) }
        assertEquals("7", t.lanes[1].latest?.answer)
        assertEquals(LaneStatus.ANSWERED, t.lanes[1].status)
        assertTrue(t.lanes[1].error!!.contains("R1 answer kept"))
    }

    @Test fun thisPhonesOwnAnswerIsNotRecheckedByTheCoordinator() {
        // the node already refused to emit an unusable answer; the coordinator only adds the check for what came over the wire
        val t = runBlocking { coordinator().run(problem, "t-7", Scripted("a", "Phone A", "Rs 42"), listOf(Scripted("b", "Phone B", "42"))) }
        assertNotNull(t.lanes[0].r1)
    }

    // ---- finding 11: an exception inside a run does not lose the votes ----------------------------------------------------

    @Test fun aFailureWhileHandlingOneLaneKeepsThatLanesAnswerAndEveryoneElses() {
        val audit = { kind: String, p: JsonElement -> if (kind == "choir.solve.res" && p.toString().contains("Phone B")) throw IllegalStateException("audit down") }
        val t = runBlocking {
            coordinator(audit = audit).run(problem, "t-8", Scripted("a", "Phone A", "42"), listOf(Scripted("b", "Phone B", "42"), Scripted("c", "Phone C", "42")))
        }
        assertEquals("DONE", t.phase)
        assertEquals(3, t.lanes.count { it.latest != null })
        assertTrue(t.lanes[1].error!!.contains("audit down"))
        assertEquals("42", t.finalPick?.answer)
    }

    @Test fun aFailureLateInTheRunEndsItAsDoneWithTheErrorAndTheVotesGathered() {
        var updates = 0
        val t = runBlocking {
            coordinator().run(
                problem, "t-9", Scripted("a", "Phone A", "42"), listOf(Scripted("b", "Phone B", "42"), Scripted("c", "Phone C", "7")),
            ) { updates++; if (it.phase == "CHECK") throw IllegalStateException("screen state broke") }
        }
        assertEquals("DONE", t.phase)
        assertTrue(t.error!!.contains("screen state broke"))
        assertEquals(3, t.lanes.count { it.latest != null })
        assertNotNull("a pick is still made from the votes", t.finalPick)
        assertEquals("42", t.finalPick?.answer)
        assertTrue(updates > 3)
    }

    @Test fun aRunWhoseEveryUpdateFailsStillReturnsATrace() {
        val t = runBlocking {
            coordinator().run(problem, "t-10", Scripted("a", "Phone A", "42"), listOf(Scripted("b", "Phone B", "42"))) { throw IllegalStateException("always") }
        }
        assertEquals("DONE", t.phase)
        assertNotNull(t.error)
        assertEquals(2, t.lanes.size)
    }

    // ---- follow-up 2: the majority policy for round 2 -----------------------------------------------------------------------

    private fun threePhones(c: String, vararg opts: Boolean): Pair<RunTrace, List<Scripted>> {
        val a = Scripted("a", "Phone A", "42")
        val b = Scripted("b", "Phone B", "42")
        val cc = Scripted("c", "Phone C", c, r2 = "42")
        val t = runBlocking { coordinator().run(problem, "t-m", a, listOf(b, cc), RunOptions(majoritySkipsRound2 = opts.firstOrNull() ?: false)) }
        return t to listOf(a, b, cc)
    }

    @Test fun withTheSwitchOffTwoAgainstOneStillGoesToRoundTwo() {
        val (t, phones) = threePhones("7", false)
        assertEquals(2, t.totals.rounds)
        assertTrue(phones.any { it.reviseCalls.isNotEmpty() })
        assertEquals("", t.round2Skip)
        assertFalse(t.majorityPolicy)
    }

    @Test fun withTheSwitchOnTwoAgainstOneSkipsRoundTwoAndSaysSo() {
        val (t, phones) = threePhones("7", true)
        assertEquals(1, t.totals.rounds)
        assertTrue(phones.all { it.reviseCalls.isEmpty() })
        assertEquals("majority", t.round2Skip)
        assertTrue(t.majorityPolicy)
        assertEquals("42", t.finalPick?.answer)
        assertEquals("DONE", t.phase)
    }

    @Test fun aUnanimousRoundOneIsRecordedAsUnanimousWhicheverPolicyRan() {
        for (policy in listOf(false, true)) {
            val (t, _) = threePhones("42", policy)
            assertEquals("unanimous", t.round2Skip)
            assertEquals(policy, t.majorityPolicy)
            assertEquals(1, t.totals.rounds)
        }
    }

    @Test fun twoPhonesThatDisagreeStillGoToRoundTwoWhateverThePolicy() {
        val a = Scripted("a", "Phone A", "42", r2 = "42")
        val b = Scripted("b", "Phone B", "7", r2 = "42")
        val t = runBlocking { coordinator().run(problem, "t-m2", a, listOf(b), RunOptions(majoritySkipsRound2 = true)) }
        assertEquals(2, t.totals.rounds)
        assertEquals("", t.round2Skip)
    }

    @Test fun aPhoneThatDidNotAnswerRoundOneMeansRoundTwoRuns() {
        val a = Scripted("a", "Phone A", "42", r2 = "42")
        val b = Scripted("b", "Phone B", "7", r2 = "42")
        val c = Scripted("c", "Phone C", null) // asked, did not answer
        val t = runBlocking { coordinator().run(problem, "t-m3", a, listOf(b, c), RunOptions(majoritySkipsRound2 = true)) }
        assertEquals(2, t.totals.rounds)
        assertEquals("", t.round2Skip)
    }

    @Test fun theNewFieldsAreInTheEvalRow() {
        val (t, _) = threePhones("7", true)
        val row = ChoirEval.rowOf(problem, t, null)
        assertEquals("majority", row.round2Skip)
    }
}
