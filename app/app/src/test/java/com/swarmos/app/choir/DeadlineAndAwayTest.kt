package com.swarmos.app.choir

import com.swarmos.app.llm.ChatChunk
import com.swarmos.app.llm.ChatRequest
import com.swarmos.app.llm.ChatResult
import com.swarmos.app.llm.ChatStats
import com.swarmos.app.llm.LlmClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A model whose chat() suspends forever: nothing ever comes back, and it is cancellable. */
private class ForeverLlm : LlmClient {
    override val modelName = "test-model"
    override suspend fun ready() = true
    override fun chatStream(req: ChatRequest): Flow<ChatChunk> = flowOf()
    override suspend fun chat(req: ChatRequest): ChatResult = awaitCancellation()
    override suspend fun embed(texts: List<String>): List<FloatArray> = emptyList()
}

/** A model whose chat() blocks a thread and ignores cancellation, like a blocking socket read. */
private class StuckLlm(private val ms: Long) : LlmClient {
    override val modelName = "test-model"
    override suspend fun ready() = true
    override fun chatStream(req: ChatRequest): Flow<ChatChunk> = flowOf()
    override suspend fun chat(req: ChatRequest): ChatResult = withContext(Dispatchers.IO + NonCancellable) {
        Thread.sleep(ms)
        ChatResult("""{"key_steps":[],"answer":"42","confidence":0.9}""", ChatStats(1, 1, 1.0, 1.0))
    }
    override suspend fun embed(texts: List<String>): List<FloatArray> = emptyList()
}

class DeadlineAndAwayTest {
    private val problem = Problem(id = "t1", kind = Kind.MATH, text = "What is six times seven?", answer = "42")

    private fun localOf(llm: LlmClient) =
        LocalParticipant(ChoirNode({ llm }, { NodeIdentity("a", "Phone A") }), "a", "Phone A")

    private fun coordinator(deadlineMs: Long) = ChoirCoordinator({ _, _ -> }, config = ChoirConfig(deadlineMs = deadlineMs))

    // ---- finding 1: the deadline holds whatever the model does ---------------------------------------

    @Test fun aModelThatNeverAnswersEndsTheRunAtTheDeadlineAndTheLaneIsTimedOut() {
        val t0 = System.currentTimeMillis()
        val t = runBlocking { coordinator(600).run(problem, "t-1", localOf(ForeverLlm()), emptyList()) }
        val took = System.currentTimeMillis() - t0
        assertTrue("took $took ms", took < 1_000)
        assertEquals(LaneStatus.TIMED_OUT, t.lanes[0].status)
        assertEquals(PickMethod.ABSTAIN, t.finalPick?.method)
        assertEquals("DONE", t.phase)
    }

    @Test fun aCallThatCannotBeCancelledStillCannotHoldTheRunPastTheDeadline() {
        val t0 = System.currentTimeMillis()
        val t = runBlocking { coordinator(600).run(problem, "t-2", localOf(StuckLlm(4_000)), emptyList()) }
        val took = System.currentTimeMillis() - t0
        assertTrue("took $took ms, the stuck call lasts 4000 ms", took < 1_000)
        assertEquals(LaneStatus.TIMED_OUT, t.lanes[0].status)
    }

    @Test fun aStuckCoordinatorLaneNeverBlocksTheVoteOfTheOthers() {
        val t0 = System.currentTimeMillis()
        val t = runBlocking {
            coordinator(1_000).run(
                problem, "t-3", localOf(ForeverLlm()),
                listOf(Scripted("b", "Phone B", "42"), Scripted("c", "Phone C", "42")),
            )
        }
        assertTrue("took ${System.currentTimeMillis() - t0} ms", System.currentTimeMillis() - t0 < 1_500)
        assertEquals(LaneStatus.TIMED_OUT, t.lanes[0].status)
        assertEquals("42", t.finalPick?.answer)
        assertEquals("AGREE", t.agreement)
        assertEquals(2, t.matrix!!.names.size)   // the stuck coordinator is not in the vote
    }

    @Test fun theNodeItselfReturnsATimeoutErrorAtItsBudget() {
        val node = ChoirNode({ StuckLlm(4_000) }, { NodeIdentity("a", "Phone A") })
        val t0 = System.currentTimeMillis()
        val a = runBlocking { node.solve(SolveRequest("t-4", "q", Kind.MATH, Role.SOLVER, 300, 300)) }
        assertTrue(System.currentTimeMillis() - t0 < 1_000)
        assertTrue(a.error!!.startsWith("timeout"))
    }

    // ---- finding 4: away peers are not asked, and the run says so at once ------------------------------

    @Test fun anAwayPeerIsNotAskedAndShowsAsAwayAtOnce() {
        val a = Scripted("a", "Phone A", "42")
        val b = Scripted("b", "Phone B", "42")
        val c = Scripted("c", "Phone C", "42", away = true)
        val t0 = System.currentTimeMillis()
        val t = runBlocking { coordinator(60_000).run(problem, "t-5", a, listOf(b, c)) }
        assertTrue("an away phone must not make the run wait: ${System.currentTimeMillis() - t0} ms", System.currentTimeMillis() - t0 < 2_000)
        assertEquals(LaneStatus.AWAY, t.lanes[2].status)
        assertTrue(t.lanes[2].error!!.contains("away"))
        assertNull(t.lanes[2].r1)
        assertTrue(c.solveRoles.isEmpty() && c.reviseCalls.isEmpty() && c.judgeCalls.isEmpty())
        assertEquals("42", t.finalPick?.answer)
        assertEquals(3, t.lanes.size)            // it is still listed
        assertEquals(2, t.matrix!!.names.size)   // but it is not in the vote
    }

    @Test fun withTwoPhonesTheRolesAreSolverAndAltSolverAndTheJudgeIsTheCoordinator() {
        val a = Scripted("a", "Phone A", r1 = "1", judgePick = 0)
        val b = Scripted("b", "Phone B", r1 = "2")
        val c = Scripted("c", "Phone C", r1 = "3", away = true)
        val t = runBlocking { coordinator(5_000).run(problem, "t-6", a, listOf(b, c)) }
        assertEquals(Role.SOLVER, t.lanes[0].role)
        assertEquals(Role.ALT_SOLVER, t.lanes[1].role)
        assertEquals(listOf(Role.SOLVER), a.solveRoles)
        assertEquals(listOf(Role.ALT_SOLVER), b.solveRoles)
        assertEquals("a", t.judge?.nodeId)       // two voters that disagree: the coordinator judges
        assertEquals("1", t.finalPick?.answer)
        assertTrue(c.judgeCalls.isEmpty())
    }

    @Test fun rolesAlternateOverTheAskedPhonesOnly() {
        val b = Scripted("b", "Phone B", "42", away = true)
        val c = Scripted("c", "Phone C", "42")
        val d = Scripted("d", "Phone D", "42")
        val t = runBlocking { coordinator(5_000).run(problem, "t-7", Scripted("a", "Phone A", "42"), listOf(b, c, d)) }
        assertEquals(Role.ALT_SOLVER, t.lanes.first { it.nodeId == "c" }.role)
        assertEquals(Role.SOLVER, t.lanes.first { it.nodeId == "d" }.role)
    }

    @Test fun everyoneAwayLeavesTheCoordinatorAlone() {
        val t = runBlocking { coordinator(5_000).run(problem, "t-8", Scripted("a", "Phone A", "42"), listOf(Scripted("b", "Phone B", "42", away = true))) }
        assertEquals("42", t.finalPick?.answer)
        assertEquals(1, t.totals.rounds)
        assertEquals("PENDING", t.agreement)
    }

    // ---- finding 5: no 20-problem run on a hot phone --------------------------------------------------------

    @Test fun aHotPhoneRefusesTheEvalWithAReason() {
        for (status in 0..2) assertNull("status $status", ChoirEval.refuseWhenHot(status))
        for (status in 3..5) {
            val why = ChoirEval.refuseWhenHot(status)
            assertNotNull("status $status", why)
            assertTrue(why!!, why.contains("too hot"))
        }
    }

    // ---- finding 3: state written from several threads ------------------------------------------------------

    @Test fun manyConcurrentRunsStayConsistent() = runBlocking {
        val coord = coordinator(5_000)
        val traces = (1..30).map { i ->
            async(Dispatchers.Default) {
                coord.run(
                    problem, "t-c$i", Scripted("a", "Phone A", "42", delay1 = 5),
                    listOf(Scripted("b", "Phone B", "42", delay1 = 3), Scripted("c", "Phone C", "7", delay1 = 1, r2 = "42")),
                )
            }
        }.awaitAll()
        for (t in traces) {
            assertEquals("DONE", t.phase)
            assertEquals("42", t.finalPick?.answer)
            assertEquals(3, t.lanes.count { it.latest != null })
            assertEquals(2, t.totals.rounds)
        }
    }
}
