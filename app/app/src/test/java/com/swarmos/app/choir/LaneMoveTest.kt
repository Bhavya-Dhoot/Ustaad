package com.swarmos.app.choir

import com.swarmos.app.core.SwarmJson
import com.swarmos.app.llm.ChatChunk
import com.swarmos.app.llm.ChatRequest
import com.swarmos.app.llm.ChatResult
import com.swarmos.app.llm.ChatStats
import com.swarmos.app.llm.LlmClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A phone with a switch: while [gone] is set it is away. Its answers are scripted; [solveMs], [reviseMs] and [judgeMs] say how long
 * each call takes (a call longer than the test waits is a phone that has stopped answering). A lane it took over arrives under a
 * task id with a `~` in it, and the `moved*` settings say how it treats that one.
 */
private class Phone(
    override val nodeId: String,
    override val name: String,
    private val r1: String? = "42",
    private val r2: String? = null,
    private val judgePick: Int? = null,
    private val solveMs: Long = 0,
    private val reviseMs: Long = 0,
    private val judgeMs: Long = 0,
    private val movedMs: Long = 0,
    private val movedFails: Boolean = false,
    private val errorText: String = "scripted failure",
) : ChoirParticipant {
    @Volatile var gone = false
    override fun isAway() = gone
    val solves = Collections.synchronizedList(mutableListOf<SolveRequest>())
    val revises = Collections.synchronizedList(mutableListOf<ReviseRequest>())
    val judges = Collections.synchronizedList(mutableListOf<JudgeRequest>())

    override suspend fun solve(req: SolveRequest): NodeAnswer {
        solves.add(req)
        val moved = '~' in req.taskId
        delay(if (moved) movedMs else solveMs)
        return if (r1 == null || (moved && movedFails)) NodeAnswer(req.taskId, 1, nodeId, name, "test", error = errorText)
        else NodeAnswer(req.taskId, 1, nodeId, name, "test", answer = r1, confidence = 0.8, tokens = 10)
    }

    override suspend fun revise(req: ReviseRequest): NodeAnswer {
        revises.add(req)
        delay(reviseMs)
        val a = r2 ?: r1 ?: return NodeAnswer(req.taskId, 2, nodeId, name, "test", error = errorText)
        return NodeAnswer(req.taskId, 2, nodeId, name, "test", answer = a, confidence = 0.8, tokens = 5)
    }

    override suspend fun judge(req: JudgeRequest): JudgeAnswer {
        judges.add(req)
        delay(judgeMs)
        return if (judgePick == null) JudgeAnswer(req.taskId, nodeId, name, "test", error = errorText)
        else JudgeAnswer(req.taskId, nodeId, name, "test", pick = judgePick, reason = "scripted", tokens = 7)
    }
}

/** The pure rule that picks the phone to take a lane over. */
class LaneMovePickTest {
    private fun dev(id: String, up: Boolean = true) = LaneMove.Device(id, "Phone $id", up)
    private fun held(id: String, lane: String, running: Boolean = false) = LaneMove.Held(id, lane, running)
    private fun pick(devices: List<LaneMove.Device>, held: List<LaneMove.Held>, lane: String, left: Long = 10_000) =
        LaneMove.pick(devices, held, lane, left)?.nodeId

    @Test fun picksAPhoneThatIsUpAndDoesNotHaveTheLane() {
        val devices = listOf(dev("a"), dev("b"), dev("c"))
        val held = listOf(held("a", "a"), held("b", "b"), held("c", "c"))
        assertEquals("a", pick(devices, held, "c"))
    }

    @Test fun aPhoneThatLeftAndIsUpAgainStillHasItsOldLaneAndIsNotHandedASecondCopy() {
        val devices = listOf(dev("a"), dev("c"))
        // lane c was given to a phone that is now gone; c itself is back and up, and is the only one that is not a
        assertEquals("a", pick(devices, listOf(held("a", "a", running = true), held("c", "c")), "c"))
        assertNull(pick(listOf(dev("c")), listOf(held("c", "c")), "c"))
    }

    @Test fun aPhoneThatTookTheLaneOverAndFailedIsNotPickedForItEither() {
        val devices = listOf(dev("a"), dev("b"), dev("c"))
        val held = listOf(held("a", "a"), held("b", "b"), held("c", "c"), held("a", "c"))   // c's lane went to a, and a failed too
        assertEquals("b", pick(devices, held, "c"))
    }

    @Test fun aPhoneThatIsDownIsNeverPicked() {
        assertNull(pick(listOf(dev("a", up = false), dev("b", up = false)), emptyList(), "c"))
        assertEquals("b", pick(listOf(dev("a", up = false), dev("b")), emptyList(), "c"))
    }

    @Test fun thePhoneWithTheFewestLanesRunningNowComesFirst() {
        val devices = listOf(dev("a"), dev("b"), dev("c"), dev("d"))
        val held = listOf(held("a", "a", running = true), held("b", "b", running = true), held("c", "c", running = false), held("d", "d", running = true))
        assertEquals("c", pick(devices, held, "x"))
    }

    @Test fun thenTheFewestLanesHeldAtAllAndAPhoneThatCameBackWithNoneBeatsThemAll() {
        val devices = listOf(dev("a"), dev("b"), dev("c"))
        val twoEach = listOf(held("a", "a"), held("a", "z"), held("b", "b"))
        assertEquals("c", pick(devices, twoEach, "x"))                 // c holds nothing
        assertEquals("b", pick(devices.filter { it.nodeId != "c" }, twoEach, "x"))   // b holds one, a holds two
    }

    @Test fun whenAllAreEqualTheEarlierPhoneInTheListWins() {
        val devices = listOf(dev("b"), dev("a"), dev("c"))
        assertEquals("b", pick(devices, emptyList(), "x"))
        assertEquals("a", pick(devices, listOf(held("b", "b")), "x"))
    }

    @Test fun nobodyIsPickedWhenTooLittleTimeIsLeft() {
        val devices = listOf(dev("a"))
        assertEquals("a", pick(devices, emptyList(), "x", left = LaneMove.MIN_LEFT_MS))
        assertNull(pick(devices, emptyList(), "x", left = LaneMove.MIN_LEFT_MS - 1))
        assertNull(pick(devices, emptyList(), "x", left = 0))
        assertNull(pick(devices, emptyList(), "x", left = -5))
    }

    @Test fun theJudgesWorkIsALaneOfItsOwn() {
        val devices = listOf(dev("a"), dev("b"), dev("c"))
        val held = listOf(held("a", "a"), held("b", "b"), held("c", "c"), held("c", LaneMove.JUDGE))
        assertEquals("a", pick(devices, held, LaneMove.JUDGE))          // c judged and failed; a still has a lane but no judging
        assertEquals("b", pick(devices.filter { it.nodeId != "a" }, held, LaneMove.JUDGE))
    }
}

class LaneMoveTest {
    private val problem = Problem(id = "t1", kind = Kind.MATH, text = "What is six times seven?", answer = "42")
    private val audits = Collections.synchronizedList(mutableListOf<Pair<String, JsonElement>>())

    /** Runs the protocol with [events] going on beside it (a phone leaving, coming back). */
    private fun go(
        taskId: String, self: ChoirParticipant, peers: List<ChoirParticipant>,
        deadlineMs: Long = 10_000, carryOn: Boolean = true, events: suspend () -> Unit = {},
    ): RunTrace = runBlocking {
        launch(Dispatchers.Default) { events() }
        ChoirCoordinator({ k, p -> audits.add(k to p) }, config = ChoirConfig(deadlineMs = deadlineMs, carryOn = carryOn)).run(problem, taskId, self, peers)
    }

    private suspend fun leaveAt(ms: Long, vararg phones: Phone) {
        delay(ms)
        phones.forEach { it.gone = true }
    }

    // ---- a lane's phone leaves in round 1 ---------------------------------------------------------------------------

    @Test fun aLaneWhoseDeviceLeavesInRoundOneIsRunAgainOnAnotherDeviceAndCompletesThere() {
        val a = Phone("a", "Phone A", "42")
        val b = Phone("b", "Phone B", "42")
        val c = Phone("c", "Phone C", "42", solveMs = 20_000)   // never answers; its heartbeat stops at 300 ms
        val t0 = System.currentTimeMillis()
        val t = go("task-1", a, listOf(b, c)) { leaveAt(300, c) }
        assertTrue("took ${System.currentTimeMillis() - t0} ms", System.currentTimeMillis() - t0 < 3_000)
        val lane = t.lanes[2]
        assertEquals(LaneStatus.ANSWERED, lane.status)
        assertEquals("Phone A", lane.movedTo)
        assertEquals("42", lane.r1?.answer)
        assertNull(lane.error)
        assertEquals(1, c.solves.size)                                    // asked once, never again
        assertEquals(listOf("task-1", "task-1~2"), a.solves.map { it.taskId })   // the lane under a task id of its own
        assertEquals(c.solves[0].role, a.solves[1].role)                   // same job: the lane's role
        assertEquals(c.solves[0].seed, a.solves[1].seed)
        assertEquals(3, t.matrix!!.names.size)                             // all three lanes vote
        val row = ChoirEval.rowOf(problem, t, null)
        assertEquals("two phones answered, not three", 2, row.nodesAnswered)   // the moved lane is Phone A answering a second time
        assertEquals(3, row.nodesTotal)
        assertEquals("DONE", t.phase)
        val moved = audits.single { it.first == "choir.lane.moved" }.second.toString()
        assertTrue(moved, moved.contains("Phone C") && moved.contains("Phone A") && moved.contains("heartbeat lost"))
        assertNull(t.lanes[0].movedTo)
        assertNull(t.lanes[1].movedTo)
    }

    @Test fun aLaneWhoseDeviceFailsIsMovedToo() {
        val a = Phone("a", "Phone A", "42")
        val b = Phone("b", "Phone B", null)    // answers with an error at once
        val t = go("task-2", a, listOf(b))
        assertEquals("Phone A", t.lanes[1].movedTo)
        assertEquals(LaneStatus.ANSWERED, t.lanes[1].status)
        assertEquals("42", t.lanes[1].r1?.answer)
        assertEquals(1, b.solves.size)
    }

    @Test fun theMovedLaneIsAskedAtMostOnceSoAReplacementThatFailsToEndsItAsItDidToday() {
        val a = Phone("a", "Phone A", "42", movedFails = true)
        val b = Phone("b", "Phone B", "42")
        val c = Phone("c", "Phone C", "42", solveMs = 20_000)
        val t = go("task-3", a, listOf(b, c)) { leaveAt(300, c) }
        assertEquals(LaneStatus.ERROR, t.lanes[2].status)
        assertEquals("Phone A", t.lanes[2].movedTo)
        assertEquals("scripted failure", t.lanes[2].error)
        assertNull(t.lanes[2].r1)
        assertEquals(2, a.solves.size)
        assertEquals(1, b.solves.size)                                    // no third try on B
        assertEquals("42", t.finalPick?.answer)
    }

    @Test fun aTimeoutAndAModelWithNoUsableAnswerAreNotMoved() {
        val a = Phone("a", "Phone A", "42")
        val slow = Phone("b", "Phone B", null, errorText = "timeout after 6000 ms")
        val none = Phone("c", "Phone C", null, errorText = ChoirNode.NO_VALID_ANSWER)
        val t = go("task-4", a, listOf(slow, none))
        assertEquals(LaneStatus.TIMED_OUT, t.lanes[1].status)
        assertEquals(LaneStatus.NO_VALID_ANSWER, t.lanes[2].status)
        assertNull(t.lanes[1].movedTo)
        assertNull(t.lanes[2].movedTo)
        assertEquals(1, a.solves.size)
    }

    @Test fun thisPhonesOwnLaneIsNeverMovedBecauseItIsTheOnePhoneBaseline() {
        val a = Phone("a", "Phone A", null)
        val b = Phone("b", "Phone B", "42")
        val t = go("task-5", a, listOf(b))
        assertEquals(LaneStatus.ERROR, t.lanes[0].status)
        assertNull(t.lanes[0].movedTo)
        assertEquals(1, b.solves.size)
        assertNull(t.baseline?.answer)
    }

    @Test fun withTheSwitchOffALaneThatLeavesEndsAsAwayLikeBefore() {
        val a = Phone("a", "Phone A", "42")
        val c = Phone("c", "Phone C", "42", solveMs = 20_000)
        val t = go("task-6", a, listOf(Phone("b", "Phone B", "42"), c), carryOn = false) { leaveAt(300, c) }
        assertEquals(LaneStatus.AWAY, t.lanes[2].status)
        assertEquals("away: heartbeat lost during the round", t.lanes[2].error)
        assertNull(t.lanes[2].movedTo)
        assertEquals(1, a.solves.size)
    }

    // ---- a lane's phone leaves in round 2 ---------------------------------------------------------------------------

    @Test fun aLaneWhoseDeviceLeavesInRoundTwoIsRevisedAgainElsewhereFromItsOwnRoundOneAnswer() {
        val a = Phone("a", "Phone A", r1 = "36", r2 = "40")
        val b = Phone("b", "Phone B", r1 = "40")
        val c = Phone("c", "Phone C", r1 = "40", reviseMs = 20_000)
        val t = go("task-7", a, listOf(b, c)) { leaveAt(300, c) }
        val lane = t.lanes[2]
        assertEquals(LaneStatus.REVISED, lane.status)
        assertEquals("Phone A", lane.movedTo)
        assertEquals("40", lane.r1?.answer)
        assertEquals("40", lane.r2?.answer)
        assertNull(lane.error)
        assertEquals(1, c.revises.size)
        assertEquals(2, a.revises.size)                                   // its own lane and the one it took over
        val taken = a.revises.single { it.own != null }
        assertEquals("task-7", taken.taskId)                              // a task id Phone A holds
        assertEquals("40", taken.own?.answer)                             // Phone C's round 1 answer, which Phone A does not have
        assertEquals(listOf("36", "40"), taken.others.map { it.answer }.sorted())
        assertNull(a.revises.single { it.own == null }.own)               // its own lane's request is as before
        assertEquals("40", t.finalPick?.answer)
        assertEquals(2, t.totals.rounds)
    }

    @Test fun aLaneMovedInRoundOneIsRevisedOnTheNewDeviceUnderItsOwnTaskId() {
        val a = Phone("a", "Phone A", r1 = "36", r2 = "40")
        val b = Phone("b", "Phone B", r1 = "40")
        val c = Phone("c", "Phone C", r1 = "40", solveMs = 20_000)
        val t = go("task-8", a, listOf(b, c)) { leaveAt(300, c) }
        assertEquals(setOf("task-8", "task-8~2"), a.revises.map { it.taskId }.toSet())
        assertTrue(a.revises.all { it.own == null })                      // Phone A holds that lane's round 1 answer under that id
        assertTrue(c.revises.isEmpty())
        assertEquals(LaneStatus.REVISED, t.lanes[2].status)
        assertEquals("Phone A", t.lanes[2].movedTo)
    }

    // ---- nobody to take it, no time ---------------------------------------------------------------------------------

    @Test fun whenNoDeviceIsLeftTheLaneEndsAsAwayLikeBefore() {
        val a = Phone("a", "Phone A", "42")
        val c = Phone("c", "Phone C", "42", solveMs = 20_000)
        val t = go("task-9", a, listOf(c)) { leaveAt(300, a, c) }       // this phone's heartbeat goes too: nobody is up
        assertEquals(LaneStatus.AWAY, t.lanes[1].status)
        assertEquals("away: heartbeat lost during the round", t.lanes[1].error)
        assertNull(t.lanes[1].movedTo)
        assertEquals(1, a.solves.size)
        assertTrue(audits.none { it.first == "choir.lane.moved" })
        assertEquals("DONE", t.phase)
    }

    @Test fun aPhoneThatWasAwayAtTheStartOrHasNoModelIsNeverTheReplacement() {
        // Phone A is still busy with its own lane at 300 ms, so were B or D eligible, either would be picked before it
        val a = Phone("a", "Phone A", "42", solveMs = 1_000)
        val b = Scripted("b", "Phone B", "42", away = true)
        val d = Scripted("d", "Phone D", "42", noModel = true)
        val c = Phone("c", "Phone C", "42", solveMs = 20_000)
        val t = go("task-10", a, listOf(b, c, d)) { leaveAt(300, c) }       // lanes: A, B, C, D
        assertEquals(LaneStatus.AWAY, t.lanes[1].status)
        assertEquals("Phone A", t.lanes[2].movedTo)
        assertEquals(LaneStatus.NO_MODEL, t.lanes[3].status)
        assertTrue(b.solveRoles.isEmpty() && d.solveRoles.isEmpty())
    }

    @Test fun whenNoTimeIsLeftTheLaneEndsAsAwayLikeBefore() {
        val a = Phone("a", "Phone A", "42")
        val c = Phone("c", "Phone C", "42", solveMs = 20_000)
        // round 1 ends at 2.4 s; at 300 ms 2.1 s are left, under the 3 s floor
        val t = go("task-11", a, listOf(Phone("b", "Phone B", "42"), c), deadlineMs = 4_000) { leaveAt(300, c) }
        assertEquals(LaneStatus.AWAY, t.lanes[2].status)
        assertNull(t.lanes[2].movedTo)
        assertEquals(1, a.solves.size)
    }

    @Test fun whenNoTimeIsLeftInRoundTwoTheLaneKeepsItsRoundOneAnswerLikeBefore() {
        val a = Phone("a", "Phone A", r1 = "36", r2 = "40")
        val c = Phone("c", "Phone C", r1 = "40", reviseMs = 20_000)
        // round 2 ends at 2.975 s; at 300 ms 2.675 s are left, under the 3 s floor
        val t = go("task-12", a, listOf(Phone("b", "Phone B", "40"), c), deadlineMs = 3_500) { leaveAt(300, c) }
        assertEquals(LaneStatus.ANSWERED, t.lanes[2].status)
        assertTrue(t.lanes[2].error!!.contains("R1 answer kept"))
        assertNull(t.lanes[2].movedTo)
        assertEquals("40", t.lanes[2].latest?.answer)
    }

    // ---- a device that comes back ---------------------------------------------------------------------------------------

    @Test fun aDeviceThatComesBackMidRunDoesNotGetASecondCopyOfItsOldLane() {
        val a = Phone("a", "Phone A", r1 = "36", r2 = "36", movedMs = 1_200)
        val b = Phone("b", "Phone B", r1 = "40")
        val c = Phone("c", "Phone C", r1 = "40", solveMs = 20_000)
        val t = go("task-13", a, listOf(b, c)) {
            leaveAt(300, c)
            delay(400)
            c.gone = false                                                // back at 700 ms, while Phone A is still solving its lane
        }
        assertEquals("Phone A", t.lanes[2].movedTo)
        assertEquals("36", t.lanes[2].r1?.answer)                          // the answer came from Phone A
        assertEquals(1, c.solves.size)
        assertTrue("the lane is revised where it sits, not on the phone that left", c.revises.isEmpty())
        assertTrue(c.judges.isEmpty())
        assertEquals(setOf("task-13", "task-13~2"), a.revises.map { it.taskId }.toSet())
        assertEquals(LaneStatus.REVISED, t.lanes[2].status)
    }

    // ---- the judge ---------------------------------------------------------------------------------------------------------

    @Test fun aJudgeWhoseDeviceLeavesIsReplacedByASecondJudgeWhoAnswers() {
        val a = Phone("a", "Phone A", r1 = "1", judgePick = 1)
        val b = Phone("b", "Phone B", r1 = "2")
        val c = Phone("c", "Phone C", r1 = "3", judgePick = 0, judgeMs = 20_000)
        val t = go("task-14", a, listOf(b, c)) { leaveAt(600, c) }
        assertEquals("a", t.judge?.nodeId)
        assertEquals("Phone C", t.judge?.replaced)
        assertNull(t.judge?.error)
        assertEquals(1, c.judges.size)
        assertEquals(1, a.judges.size)
        assertEquals("task-14", a.judges[0].taskId)                       // a task id Phone A holds
        assertEquals(listOf("1", "2", "3"), a.judges[0].candidates.map { it.answer })
        assertEquals(PickMethod.JUDGE, t.finalPick?.method)
        assertEquals("2", t.finalPick?.answer)
        assertEquals(3, t.totals.rounds)
        assertTrue(audits.any { it.first == "choir.judge.replaced" && it.second.toString().contains("Phone A") })
    }

    @Test fun aJudgeThatFailsWithoutLeavingIsReplacedToo() {
        val a = Phone("a", "Phone A", r1 = "1", judgePick = 2)
        val c = Phone("c", "Phone C", r1 = "3")                           // judge answers with an error
        val b = Phone("b", "Phone B", r1 = "2")
        val t = go("task-15", a, listOf(b, c))
        assertEquals("a", t.judge?.nodeId)
        assertEquals("Phone C", t.judge?.replaced)
        assertEquals("3", t.finalPick?.answer)
        assertEquals(PickMethod.JUDGE, t.finalPick?.method)
    }

    @Test fun whenBothJudgesFailTheJudgeErrorStandsAsToday() {
        val a = Phone("a", "Phone A", r1 = "1")                           // judge answers with an error
        val b = Phone("b", "Phone B", r1 = "2", judgePick = 0)
        val c = Phone("c", "Phone C", r1 = "3", judgeMs = 20_000)
        val t = go("task-16", a, listOf(b, c)) { leaveAt(600, c) }
        assertNotNull(t.judge?.error)
        assertEquals("scripted failure", t.judge?.error)
        assertEquals("a", t.judge?.nodeId)
        assertEquals("Phone C", t.judge?.replaced)
        assertEquals(1, a.judges.size)
        assertEquals(1, c.judges.size)
        assertTrue("only one replacement", b.judges.isEmpty())
        assertFalse(t.finalPick?.method == PickMethod.JUDGE)
        assertEquals("DONE", t.phase)
    }

    @Test fun aFailedJudgeWithNoTimeLeftHasNoSecondJudge() {
        val a = Phone("a", "Phone A", r1 = "1", judgePick = 0)
        val c = Phone("c", "Phone C", r1 = "3")                           // judge answers with an error; 2.5 s deadline, under the 3 s floor
        val t = go("task-17", a, listOf(Phone("b", "Phone B", "2"), c), deadlineMs = 2_500)
        assertEquals("c", t.judge?.nodeId)
        assertNull(t.judge?.replaced)
        assertNotNull(t.judge?.error)
        assertTrue(a.judges.isEmpty())
    }

    @Test fun withTheSwitchOffAFailedJudgeHasNoSecondJudgeLikeBefore() {
        val a = Phone("a", "Phone A", r1 = "1", judgePick = 0)
        val c = Phone("c", "Phone C", r1 = "3")
        val t = go("task-18", a, listOf(Phone("b", "Phone B", "2"), c), carryOn = false)
        assertEquals("c", t.judge?.nodeId)
        assertNotNull(t.judge?.error)
        assertTrue(a.judges.isEmpty())
    }

    // ---- the real node under it: state per task id -----------------------------------------------------------------------

    /** Answers like a model: solve replies come from [solves] in turn, a revise reply is [revise], and every revise prompt is kept. */
    private class PromptLlm(private val solves: List<String>, private val revise: String) : LlmClient {
        override val modelName = "test-model"
        val revisePrompts = Collections.synchronizedList(mutableListOf<String>())
        private val n = AtomicInteger()
        override suspend fun ready() = true
        override fun chatStream(req: ChatRequest): Flow<ChatChunk> = flowOf()
        override suspend fun embed(texts: List<String>): List<FloatArray> = emptyList()
        override suspend fun chat(req: ChatRequest): ChatResult {
            val user = req.messages.last().content
            val answer = if (user.contains("Other solvers answered")) { revisePrompts.add(user); revise } else solves[minOf(n.getAndIncrement(), solves.lastIndex)]
            return ChatResult("""{"key_steps":[],"answer":"$answer","confidence":0.8}""", ChatStats(1, 1, 1.0, 1.0))
        }
    }

    /** A real [ChoirNode] behind a switch: [gone] makes it away, [hangSolve] and [hangRevise] make it stop answering. */
    private class NodePhone(
        val llm: PromptLlm, override val nodeId: String, override val name: String,
        private val hangSolve: Boolean = false, private val hangRevise: Boolean = false,
    ) : ChoirParticipant {
        private val node = ChoirNode({ llm }, { NodeIdentity(nodeId, name) })
        @Volatile var gone = false
        override fun isAway() = gone
        override suspend fun solve(req: SolveRequest): NodeAnswer = if (hangSolve) awaitCancellation() else node.solve(req)
        override suspend fun revise(req: ReviseRequest): NodeAnswer = if (hangRevise) awaitCancellation() else node.revise(req)
        override suspend fun judge(req: JudgeRequest): JudgeAnswer = node.judge(req)
    }

    @Test fun aLaneMovedInRoundOneIsRevisedByTheNewNodeFromTheAnswerItGaveForThatLaneNotForItsOwn() {
        val a = NodePhone(PromptLlm(listOf("36", "41"), revise = "40"), "a", "Phone A")
        val b = NodePhone(PromptLlm(listOf("40"), revise = "40"), "b", "Phone B")
        val c = NodePhone(PromptLlm(listOf("40"), revise = "40"), "c", "Phone C", hangSolve = true)
        val t = go("task-19", a, listOf(b, c)) { delay(300); c.gone = true }
        assertTrue(t.lanes.all { it.error == null })
        assertEquals("Phone A", t.lanes[2].movedTo)
        assertEquals("41", t.lanes[2].r1?.answer)
        assertEquals(2, a.llm.revisePrompts.size)
        assertEquals(1, a.llm.revisePrompts.count { it.contains("Your earlier answer. Answer: 36") })   // its own lane
        assertEquals(1, a.llm.revisePrompts.count { it.contains("Your earlier answer. Answer: 41") })   // the lane it took over
        assertEquals(LaneStatus.REVISED, t.lanes[2].status)
    }

    @Test fun aLaneMovedInRoundTwoIsRevisedByTheNewNodeFromTheLanesOwnAnswerAndItsOwnAnswerIsKept() {
        val a = NodePhone(PromptLlm(listOf("36"), revise = "40"), "a", "Phone A")
        val b = NodePhone(PromptLlm(listOf("40"), revise = "40"), "b", "Phone B")
        val c = NodePhone(PromptLlm(listOf("41"), revise = "40"), "c", "Phone C", hangRevise = true)
        val t = go("task-20", a, listOf(b, c)) { delay(300); c.gone = true }
        assertTrue(t.lanes.all { it.error == null })
        assertEquals("Phone A", t.lanes[2].movedTo)
        assertEquals("41", t.lanes[2].r1?.answer)                          // Phone C's own round 1 answer
        assertEquals(LaneStatus.REVISED, t.lanes[2].status)
        assertEquals(2, a.llm.revisePrompts.size)
        assertEquals(1, a.llm.revisePrompts.count { it.contains("Your earlier answer. Answer: 36") })
        assertEquals(1, a.llm.revisePrompts.count { it.contains("Your earlier answer. Answer: 41") })
    }

    @Test fun aReviseWithAnOwnAnswerDoesNotChangeWhatTheNodeKeepsAsItsOwn() = runBlocking {
        val llm = PromptLlm(listOf("36"), revise = "99")
        val node = ChoirNode({ llm }, { NodeIdentity("n", "Phone N") })
        node.solve(SolveRequest("t", "What is six times seven?", Kind.MATH, Role.SOLVER, 300, 5_000))
        node.revise(ReviseRequest("t", listOf(Candidate("40")), own = Candidate("41")))
        node.revise(ReviseRequest("t", listOf(Candidate("40"))))
        assertTrue(llm.revisePrompts[0], llm.revisePrompts[0].contains("Your earlier answer. Answer: 41"))
        assertTrue(llm.revisePrompts[1], llm.revisePrompts[1].contains("Your earlier answer. Answer: 36"))
        assertNotNull(ChoirNode({ llm }, { NodeIdentity("n", "Phone N") }).revise(ReviseRequest("never-seen", listOf(Candidate("1")), own = Candidate("2"))).error)
    }

    // ---- what is sent and shown ---------------------------------------------------------------------------------------------

    @Test fun aReviseRequestCarryingAnOwnAnswerStillFitsInOneKilobyte() {
        val big = List(4) { Candidate("a".repeat(200), List(5) { "step ".repeat(60) }) }
        val r = ChoirCoordinator.fitRevise(ReviseRequest("task-123~2", big, own = Candidate("b".repeat(200), List(5) { "own ".repeat(60) })))
        assertTrue(wireBytes(r).toString(), wireBytes(r) <= ChoirLimits.WIRE_MAX_BYTES)
        assertNotNull(r.own)
    }

    @Test fun anOldTraceAndAnOldReviseRequestStillDecode() {
        val lane = SwarmJson.decodeFromString(LaneTrace.serializer(), """{"nodeId":"a","name":"Phone A","role":"SOLVER"}""")
        assertNull(lane.movedTo)
        assertNull(SwarmJson.decodeFromString(ReviseRequest.serializer(), """{"taskId":"t","others":[]}""").own)
        assertNull(SwarmJson.decodeFromString(JudgeTrace.serializer(), """{"nodeId":"a","name":"Phone A"}""").replaced)
    }

    @Test fun theScreenNamesWhereALaneWentAndWhoseJudgingATakeoverTookOver() {
        assertEquals("Phone C", ChoirScreenLogic.laneLabel("Phone C", null))
        assertEquals("Phone C, moved to Phone B", ChoirScreenLogic.laneLabel("Phone C", "Phone B"))
        assertEquals("Judge: Phone A, round 3", ChoirScreenLogic.judgeTitle("Phone A", null))
        assertEquals("Judge: Phone A, round 3 (took over from Phone C)", ChoirScreenLogic.judgeTitle("Phone A", "Phone C"))
    }
}
