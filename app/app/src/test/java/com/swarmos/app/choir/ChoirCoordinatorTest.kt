package com.swarmos.app.choir

import com.swarmos.app.core.AuditLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.Collections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A node with scripted answers, so the protocol runs in a JVM test with no phones and no model. */
internal class Scripted(
    override val nodeId: String,
    override val name: String,
    private val r1: String?,
    private val r2: String? = null,
    private val judgePick: Int? = null,
    private val conf: Double = 0.8,
    private val steps: List<String> = emptyList(),
    private val delay1: Long = 0,
    private val delay2: Long = 0,
    private val r1Error: String = "scripted failure",
    override val away: Boolean = false,
    override val noModel: Boolean = false,
) : ChoirParticipant {
    val solveRoles = Collections.synchronizedList(mutableListOf<Role>())
    val reviseCalls = Collections.synchronizedList(mutableListOf<ReviseRequest>())
    val judgeCalls = Collections.synchronizedList(mutableListOf<JudgeRequest>())

    override suspend fun solve(req: SolveRequest): NodeAnswer {
        solveRoles.add(req.role)
        delay(delay1)
        return if (r1 == null) NodeAnswer(req.taskId, 1, nodeId, name, "test", error = r1Error)
        else NodeAnswer(req.taskId, 1, nodeId, name, "test", answer = r1, confidence = conf, keySteps = steps, tokens = 10, ms = delay1)
    }

    override suspend fun revise(req: ReviseRequest): NodeAnswer {
        reviseCalls.add(req)
        delay(delay2)
        val a = r2 ?: r1 ?: return NodeAnswer(req.taskId, 2, nodeId, name, "test", error = "scripted failure")
        return NodeAnswer(req.taskId, 2, nodeId, name, "test", answer = a, confidence = conf, keySteps = steps, tokens = 5, ms = delay2)
    }

    override suspend fun judge(req: JudgeRequest): JudgeAnswer {
        judgeCalls.add(req)
        return if (judgePick == null) JudgeAnswer(req.taskId, nodeId, name, "test", error = "scripted failure")
        else JudgeAnswer(req.taskId, nodeId, name, "test", pick = judgePick, reason = "scripted", tokens = 7, ms = 1)
    }
}

class ChoirCoordinatorTest {
    private val problem = Problem(id = "t1", kind = Kind.MATH, text = "What is six times seven?", answer = "42")
    private val audits = Collections.synchronizedList(mutableListOf<Pair<String, JsonElement>>())

    private fun coordinator(deadlineMs: Long = 3_000) =
        ChoirCoordinator({ k, p -> audits.add(k to p) }, config = ChoirConfig(deadlineMs = deadlineMs))

    private fun run(self: Scripted, vararg peers: Scripted, deadlineMs: Long = 3_000, p: Problem = problem): RunTrace =
        runBlocking { coordinator(deadlineMs).run(p, "task-1", self, peers.toList()) }

    // ---- agree -----------------------------------------------------------------------------------

    @Test fun agreeAfterRoundOneStopsThere() {
        val a = Scripted("a", "Phone A", "42", steps = listOf("6*7=42"))
        val b = Scripted("b", "Phone B", "42", steps = listOf("6*7"))
        val c = Scripted("c", "Phone C", "Rs 42")
        val t = run(a, b, c)
        assertEquals("DONE", t.phase)
        assertEquals(1, t.totals.rounds)
        assertEquals("AGREE", t.agreement)
        assertTrue(a.reviseCalls.isEmpty() && b.reviseCalls.isEmpty() && c.reviseCalls.isEmpty())
        assertTrue(b.judgeCalls.isEmpty())
        assertEquals("42", t.finalPick?.answer)
        assertEquals(PickMethod.TOOL, t.finalPick?.method)        // Phone A's "6*7=42" verifies it
        assertTrue(t.finalPick!!.verified)
        assertEquals(PickMethod.MAJORITY, t.finalNoTool?.method)
        assertEquals(true, t.choirCorrect)
        assertEquals(true, t.choirToolCorrect)
        assertEquals(true, t.baselineCorrect)
        assertEquals(30, t.totals.tokens)
    }

    @Test fun unanimousButUnverifiedIsLabelled() {
        val t = run(Scripted("a", "Phone A", "42"), Scripted("b", "Phone B", "42"), Scripted("c", "Phone C", "42"))
        assertEquals("unanimous but unverified", t.finalPick?.label)
        assertEquals(PickMethod.MAJORITY, t.finalPick?.method)
        assertFalse(t.finalPick!!.verified)
    }

    @Test fun rolesAreSolverForSelfAndAlternatingForPeers() {
        val a = Scripted("a", "Phone A", "42")
        val b = Scripted("b", "Phone B", "42")
        val c = Scripted("c", "Phone C", "42")
        run(a, c, b)   // peers are sorted by name, whatever order they come in
        assertEquals(listOf(Role.SOLVER), a.solveRoles)
        assertEquals(listOf(Role.ALT_SOLVER), b.solveRoles)
        assertEquals(listOf(Role.SOLVER), c.solveRoles)
    }

    // ---- disagree, then converge -----------------------------------------------------------------

    @Test fun disagreementTriggersOneExchangeRoundAndConverges() {
        val a = Scripted("a", "Phone A", r1 = "36", r2 = "40")
        val b = Scripted("b", "Phone B", r1 = "40")
        val c = Scripted("c", "Phone C", r1 = "40")
        val t = run(a, b, c)
        assertEquals(2, t.totals.rounds)
        assertEquals(1, a.reviseCalls.size)
        assertEquals(1, b.reviseCalls.size)
        assertEquals(1, c.reviseCalls.size)
        // each node got the OTHER answers only, nobody else's name
        assertEquals(listOf("40", "40"), a.reviseCalls[0].others.map { it.answer })
        assertEquals(setOf("36", "40"), b.reviseCalls[0].others.map { it.answer }.toSet())
        assertTrue(b.judgeCalls.isEmpty())
        assertEquals("AGREE", t.agreement)
        assertEquals("40", t.finalNoTool?.answer)
        assertEquals("R1 answer survives next to the revised one", "36", t.lanes[0].r1?.answer)
        assertEquals("40", t.lanes[0].r2?.answer)
        assertTrue(t.matrix!!.same.all { row -> row.all { it } })
    }

    @Test fun partialConvergenceStillNeedsNoJudge() {
        val a = Scripted("a", "Phone A", r1 = "1", r2 = "1")
        val b = Scripted("b", "Phone B", r1 = "2", r2 = "1")
        val c = Scripted("c", "Phone C", r1 = "3", r2 = "3")
        val t = run(a, b, c)
        assertEquals(2, t.totals.rounds)
        assertNull(t.judge)
        assertEquals("1", t.finalPick?.answer)
        assertEquals("majority 2/3", t.finalPick?.label)
    }

    // ---- no majority: judge ----------------------------------------------------------------------

    @Test fun noMajorityGoesToAJudgeThatAuthoredNeitherLeadingAnswer() {
        val a = Scripted("a", "Phone A", r1 = "1")
        val b = Scripted("b", "Phone B", r1 = "2")
        val c = Scripted("c", "Phone C", r1 = "3", judgePick = 1)
        val t = run(a, b, c)
        assertEquals(3, t.totals.rounds)
        assertEquals(1, c.judgeCalls.size)
        assertTrue(a.judgeCalls.isEmpty() && b.judgeCalls.isEmpty())
        assertEquals("c", t.judge?.nodeId)
        assertEquals(listOf("1", "2", "3"), c.judgeCalls[0].candidates.map { it.answer })
        assertEquals("2", t.finalPick?.answer)
        assertEquals(PickMethod.JUDGE, t.finalPick?.method)
        assertEquals(7, t.judge?.tokens)
    }

    @Test fun twoNodesThatDisagreeAreJudgedByTheCoordinator() {
        val a = Scripted("a", "Phone A", r1 = "1", judgePick = 0)
        val b = Scripted("b", "Phone B", r1 = "2")
        val t = run(a, b)
        assertEquals(3, t.totals.rounds)
        assertEquals("a", t.judge?.nodeId)
        assertEquals("1", t.finalPick?.answer)
        assertEquals(PickMethod.JUDGE, t.finalPick?.method)
    }

    @Test fun aFailedJudgeLeavesTheTieUnresolved() {
        val t = run(Scripted("a", "Phone A", r1 = "1"), Scripted("b", "Phone B", r1 = "2"))
        assertNotNull(t.judge?.error)
        assertEquals(PickMethod.ABSTAIN, t.finalPick?.method)
        assertNull(t.finalPick?.answer)
        assertEquals(false, t.choirToolCorrect)
    }

    @Test fun aClearConfidenceLeadResolvesWithoutTheJudgeAnswer() {
        val a = Scripted("a", "Phone A", r1 = "1", conf = 0.95)
        val b = Scripted("b", "Phone B", r1 = "2", conf = 0.5)
        val c = Scripted("c", "Phone C", r1 = "3", conf = 0.5, judgePick = 2)
        val t = run(a, b, c)
        assertEquals(PickMethod.CONFIDENCE, t.finalPick?.method)
        assertEquals("1", t.finalPick?.answer)
    }

    // ---- timeouts and deadlines ------------------------------------------------------------------

    @Test fun allNodesTimingOutAbstainWithinTheDeadline() {
        val deadline = 600L
        val a = Scripted("a", "Phone A", "42", delay1 = 10_000)
        val b = Scripted("b", "Phone B", "42", delay1 = 10_000)
        val c = Scripted("c", "Phone C", "42", delay1 = 10_000)
        val t0 = System.currentTimeMillis()
        val t = run(a, b, c, deadlineMs = deadline)
        val took = System.currentTimeMillis() - t0
        assertTrue("took $took ms", took < 1_500)
        assertTrue(t.lanes.all { it.status == LaneStatus.TIMED_OUT })
        assertEquals(PickMethod.ABSTAIN, t.finalPick?.method)
        assertEquals("no node answered", t.finalPick?.label)
        assertNull(t.finalPick?.answer)
        assertEquals(1, t.totals.rounds)
        assertEquals("DONE", t.phase)
    }

    @Test fun aSlowNodeIsDroppedFromTheVoteAndShownAsTimedOut() {
        val a = Scripted("a", "Phone A", "42")
        val b = Scripted("b", "Phone B", "17", delay1 = 10_000)   // misses the R1 deadline
        val c = Scripted("c", "Phone C", "42")
        val t0 = System.currentTimeMillis()
        val t = run(a, b, c, deadlineMs = 1_000)
        assertTrue(System.currentTimeMillis() - t0 < 2_000)
        assertEquals(LaneStatus.TIMED_OUT, t.lanes[1].status)
        assertNotNull(t.lanes[1].error)
        assertNull(t.lanes[1].r1)
        assertEquals("42", t.finalPick?.answer)
        assertEquals("AGREE", t.agreement)
        assertTrue(b.reviseCalls.isEmpty())        // a dropped node is not asked again
        assertEquals(2, t.matrix!!.names.size)     // and it is not in the matrix
    }

    @Test fun aNodeThatMissesTheExchangeDeadlineKeepsItsRoundOneAnswer() {
        val a = Scripted("a", "Phone A", r1 = "36", r2 = "40")
        val b = Scripted("b", "Phone B", r1 = "40")
        val c = Scripted("c", "Phone C", r1 = "40", delay2 = 10_000)   // R2 never arrives
        val t = run(a, b, c, deadlineMs = 2_000)
        assertEquals(LaneStatus.ANSWERED, t.lanes[2].status)
        assertTrue(t.lanes[2].error!!.contains("R1 answer kept"))
        assertEquals("40", t.lanes[2].latest?.answer)
        assertEquals("40", t.finalPick?.answer)
        assertEquals(2, t.totals.rounds)
    }

    @Test fun aNodeThatErrorsIsDroppedLikeATimeout() {
        val t = run(Scripted("a", "Phone A", "42"), Scripted("b", "Phone B", null), Scripted("c", "Phone C", "42"))
        assertEquals(LaneStatus.ERROR, t.lanes[1].status)
        assertEquals("42", t.finalPick?.answer)
    }

    @Test fun aNodeThatReportsItsOwnTimeoutIsShownAsTimedOut() {
        val t = run(Scripted("a", "Phone A", "42"), Scripted("b", "Phone B", null, r1Error = "timeout after 36000 ms"), Scripted("c", "Phone C", "42"))
        assertEquals(LaneStatus.TIMED_OUT, t.lanes[1].status)
        assertEquals("42", t.finalPick?.answer)
    }

    @Test fun roundOneRunsInParallelNotOneAfterAnother() {
        val a = Scripted("a", "Phone A", "42", delay1 = 300)
        val b = Scripted("b", "Phone B", "42", delay1 = 300)
        val c = Scripted("c", "Phone C", "42", delay1 = 300)
        val t0 = System.currentTimeMillis()
        run(a, b, c, deadlineMs = 5_000)
        assertTrue("took ${System.currentTimeMillis() - t0}", System.currentTimeMillis() - t0 < 800)
    }

    // ---- audit log, message size, limits -------------------------------------------------------------

    @Test fun everyMessageIsAuditedAndStaysUnderOneKilobyte() {
        val dir = File(System.getProperty("java.io.tmpdir"), "choir-audit-" + System.nanoTime()).apply { mkdirs() }
        val log = AuditLog(File(dir, "audit.jsonl"))
        val coord = ChoirCoordinator({ k, p -> log.append(k, p) }, config = ChoirConfig(deadlineMs = 3_000))
        val a = Scripted("a", "Phone A", r1 = "1", conf = 0.8)
        val b = Scripted("b", "Phone B", r1 = "2")
        val c = Scripted("c", "Phone C", r1 = "3", judgePick = 0)
        runBlocking { coord.run(problem, "task-9", a, listOf(b, c)) }
        val kinds = log.entries().map { it.kind }
        assertEquals(1, kinds.count { it == "choir.run.start" })
        assertEquals(3, kinds.count { it == "choir.solve.req" })
        assertEquals(3, kinds.count { it == "choir.solve.res" })
        assertEquals(3, kinds.count { it == "choir.revise.req" })
        assertEquals(3, kinds.count { it == "choir.revise.res" })
        assertEquals(1, kinds.count { it == "choir.judge.req" })
        assertEquals(1, kinds.count { it == "choir.judge.res" })
        assertEquals(3, kinds.count { it == "choir.toolcheck" })
        assertEquals(1, kinds.count { it == "choir.final" })
        assertTrue(kinds.indexOf("choir.run.start") < kinds.indexOf("choir.revise.req"))
        assertTrue(kinds.indexOf("choir.revise.res") < kinds.indexOf("choir.judge.req"))
        assertEquals("choir.final", kinds.last())
        for (e in log.entries()) {
            val msg = (e.payload as JsonObject)["msg"]
            if (msg != null) assertTrue("${e.kind} is ${msg.toString().length} chars", msg.toString().toByteArray().size <= ChoirLimits.WIRE_MAX_BYTES)
        }
        assertTrue(log.verify().ok)
    }

    @Test fun timeoutsAreAuditedToo() {
        val t = run(Scripted("a", "Phone A", "42"), Scripted("b", "Phone B", "42", delay1 = 10_000), deadlineMs = 500)
        assertEquals(LaneStatus.TIMED_OUT, t.lanes[1].status)
        assertTrue(audits.any { it.first == "choir.timeout" })
    }

    @Test fun anOversizedProblemIsRefusedBeforeAnythingIsSent() {
        val a = Scripted("a", "Phone A", "42")
        val long = Problem(kind = Kind.TEXT, text = "x".repeat(ChoirLimits.PROBLEM_MAX_CHARS + 1))
        val t = run(a, p = long)
        assertNotNull(t.error)
        assertTrue(a.solveRoles.isEmpty())
        assertEquals("DONE", t.phase)
    }

    @Test fun soloCoordinatorStillAnswers() {
        val t = run(Scripted("a", "Phone A", "42"))
        assertEquals("42", t.finalPick?.answer)
        assertEquals(1, t.totals.rounds)
        assertEquals("PENDING", t.agreement)
    }

    @Test fun revisionAndJudgeRequestsFitInOneKilobyte() {
        val big = List(3) { Candidate("a".repeat(200), List(5) { "step ".repeat(60) }) }
        val r = ChoirCoordinator.fitRevise(ReviseRequest("t", big))
        val j = ChoirCoordinator.fitJudge(JudgeRequest("t", big))
        assertTrue(wireBytes(r) <= ChoirLimits.WIRE_MAX_BYTES)
        assertTrue(wireBytes(j) <= ChoirLimits.WIRE_MAX_BYTES)
        val n = NodeAnswer("t", 1, "n", "Phone A", "m", answer = "z".repeat(900), keySteps = List(6) { "k".repeat(400) }, error = "e".repeat(900)).fitWire()
        assertTrue(wireBytes(n) <= ChoirLimits.WIRE_MAX_BYTES)
        assertEquals(ChoirLimits.MAX_STEPS, minOf(n.keySteps.size, ChoirLimits.MAX_STEPS))
    }
}
