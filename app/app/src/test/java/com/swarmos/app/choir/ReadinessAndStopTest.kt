package com.swarmos.app.choir

import com.swarmos.app.core.PhoneActivity
import com.swarmos.app.core.SwarmJson
import com.swarmos.app.llm.ChatChunk
import com.swarmos.app.llm.ChatRequest
import com.swarmos.app.llm.ChatResult
import com.swarmos.app.llm.ChatStats
import com.swarmos.app.llm.FakeLlmClient
import com.swarmos.app.llm.LlmClient
import com.swarmos.app.sched.FakePhone
import com.swarmos.app.sched.GatedLlmClient
import com.swarmos.app.sched.TestLlm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

private const val ANSWER_JSON = """{"key_steps":[],"answer":"42","confidence":0.9}"""

/** A model whose chat() holds until cancelled (or, when [uncancellable], until [release]), like a long generation. */
private class HoldingLlm(private val uncancellable: Boolean = false) : LlmClient {
    override val modelName = "test-model"
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val sawCancel = AtomicBoolean(false)
    override suspend fun ready() = true
    override fun chatStream(req: ChatRequest): Flow<ChatChunk> = flowOf()
    override suspend fun chat(req: ChatRequest): ChatResult {
        started.complete(Unit)
        try {
            if (uncancellable) withContext(NonCancellable) { release.await() } else awaitCancellation()
        } catch (e: CancellationException) {
            sawCancel.set(true)
            throw e
        }
        return ChatResult(ANSWER_JSON, ChatStats(1, 1, 1.0, 1.0))
    }
    override suspend fun embed(texts: List<String>): List<FloatArray> = emptyList()
}

private class QuickLlm(private val delayMs: Long = 20) : LlmClient {
    override val modelName = "test-model"
    override suspend fun ready() = true
    override fun chatStream(req: ChatRequest): Flow<ChatChunk> = flowOf()
    override suspend fun chat(req: ChatRequest): ChatResult {
        delay(delayMs)
        return ChatResult(ANSWER_JSON, ChatStats(1, 1, 1.0, 1.0))
    }
    override suspend fun embed(texts: List<String>): List<FloatArray> = emptyList()
}

class ReadinessAndStopTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val problem = Problem(id = "t1", kind = Kind.MATH, text = "What is six times seven?", answer = "42")

    @After fun tearDown() {
        scope.cancel()
        PhoneActivity.clear(ChoirActivity.OWNER)
    }

    private suspend fun waitUntil(what: String, cond: () -> Boolean) {
        withTimeout(3_000) { while (!cond()) delay(5) }
    }

    // ---- finding 1: no run on the placeholder model in a demo build ---------------------------------------

    @Test fun onlyARealChatModelCountsAsReady() {
        assertFalse(ChoirReadiness.realChatReady(FakeLlmClient()))
        assertTrue(ChoirReadiness.realChatReady(QuickLlm()))
        assertFalse(ChoirReadiness.peerHasRealModel(null))
        assertFalse(ChoirReadiness.peerHasRealModel(""))
        assertFalse(ChoirReadiness.peerHasRealModel("FAKE"))
        assertTrue(ChoirReadiness.peerHasRealModel("Qwen3.5-4B-Q4_K_M"))
    }

    @Test fun aDemoBuildRefusesWithoutARealModelAndADebugBuildKeepsTheFakePath() {
        assertEquals("No chat model is loaded on this phone. Open Models to load one.", ChoirReadiness.refusal(debugBuild = false, coordinatorReady = false))
        assertNull(ChoirReadiness.refusal(debugBuild = false, coordinatorReady = true))
        assertNull(ChoirReadiness.refusal(debugBuild = true, coordinatorReady = false))
        assertNull(ChoirReadiness.refusal(debugBuild = true, coordinatorReady = true))
    }

    @Test fun aPeerWithoutARealModelIsAListedLaneThatIsNotAsked() {
        val a = Scripted("a", "Phone A", "42")
        val b = Scripted("b", "Phone B", "42", noModel = true)
        val c = Scripted("c", "Phone C", "42")
        val coord = ChoirCoordinator({ _, _ -> }, config = ChoirConfig(deadlineMs = 5_000))
        val t0 = System.currentTimeMillis()
        val t = runBlocking { coord.run(problem, "t-1", a, listOf(b, c)) }
        assertTrue(System.currentTimeMillis() - t0 < 2_000)
        assertEquals(LaneStatus.NO_MODEL, t.lanes[1].status)
        assertEquals("no model loaded, not asked", t.lanes[1].error)
        assertTrue(b.solveRoles.isEmpty() && b.reviseCalls.isEmpty() && b.judgeCalls.isEmpty())
        assertEquals(3, t.lanes.size)                    // still listed
        assertEquals(2, t.matrix!!.names.size)           // not in the vote
        assertEquals(Role.ALT_SOLVER, t.lanes[2].role)   // the phones that are asked alternate: C is the first peer asked
        assertEquals("42", t.finalPick?.answer)
    }

    // ---- finding 2: an abandoned call frees its gate slot ----------------------------------------------------

    private fun gatedNode(phone: FakePhone, current: () -> LlmClient) =
        ChoirNode({ GatedLlmClient(current, phone.node) }, { NodeIdentity("a", "Phone A") })

    private fun solve(node: ChoirNode, budgetMs: Long) =
        runBlocking { node.solve(SolveRequest("t", "q", Kind.MATH, Role.SOLVER, 300, budgetMs)) }

    @Test fun aCallAbandonedAtTheDeadlineReleasesItsSlotSoTheNextRunIsNotBlocked() = runBlocking {
        val phone = FakePhone("p", TestLlm(), scope, capacity = 1)
        val hold = HoldingLlm()
        var current: LlmClient = hold
        val node = gatedNode(phone) { current }

        val first = async(Dispatchers.Default) { node.solve(SolveRequest("t1", "q", Kind.MATH, Role.SOLVER, 300, 250)) }
        hold.started.await()
        assertEquals("the call holds the only slot while it runs", 1, phone.node.chatExec.running)
        assertTrue(first.await().error!!.startsWith("timeout"))

        waitUntil("the slot is released once the call is cancelled") { phone.node.chatExec.running == 0 }
        assertTrue("the underlying call was cancelled", hold.sawCancel.get())

        current = QuickLlm()
        val t0 = System.currentTimeMillis()
        val next = node.solve(SolveRequest("t2", "q", Kind.MATH, Role.SOLVER, 300, 2_000))
        assertNull(next.error)
        assertEquals("42", next.answer)
        assertTrue("the next run was not blocked: ${System.currentTimeMillis() - t0} ms", System.currentTimeMillis() - t0 < 1_000)
        assertEquals(0, phone.node.chatExec.running)
    }

    @Test fun aCallThatCannotBeCancelledKeepsItsSlotOnlyUntilItReturns() = runBlocking {
        val phone = FakePhone("p", TestLlm(), scope, capacity = 1)
        val hold = HoldingLlm(uncancellable = true)
        var current: LlmClient = hold
        val node = gatedNode(phone) { current }

        val first = node.solve(SolveRequest("t1", "q", Kind.MATH, Role.SOLVER, 300, 250))
        assertTrue(first.error!!.startsWith("timeout"))
        assertEquals("still held: the call cannot be cancelled", 1, phone.node.chatExec.running)

        current = QuickLlm()
        val blocked = node.solve(SolveRequest("t2", "q", Kind.MATH, Role.SOLVER, 300, 250))
        assertTrue("a second run waits for the slot and times out: ${blocked.error}", blocked.error!!.startsWith("timeout"))

        hold.release.complete(Unit)                      // the underlying call returns at last
        waitUntil("the slot is released when it returns") { phone.node.chatExec.running == 0 }
        val ok = node.solve(SolveRequest("t3", "q", Kind.MATH, Role.SOLVER, 300, 2_000))
        assertNull(ok.error)
        assertEquals("42", ok.answer)
    }

    // ---- finding 3: Stop on a running evaluation keeps what it has ---------------------------------------------------

    private fun tmpDir() = File(System.getProperty("java.io.tmpdir"), "choir-stop-" + System.nanoTime()).apply { mkdirs() }

    private fun traceOf(p: Problem): RunTrace =
        runBlocking { ChoirCoordinator({ _, _ -> }, config = ChoirConfig(deadlineMs = 3_000)).run(p, "t-" + p.id, Scripted("a", "Phone A", "42"), emptyList()) }

    private fun report(rows: List<EvalRow>) = EvalReport(
        ts = 1_760_000_000_000 + rows.size, model = "FAKE", fake = true, nodes = listOf("Phone A"),
        useProblemCheck = false, deadlineMs = 60_000, summary = ChoirEval.summarize(rows), rows = rows,
    )

    private val problems = (1..4).map { Problem(id = "e$it", kind = Kind.MATH, text = "p$it", answer = "42") }

    @Test fun stoppingAnEvalFromTheLiveStateLineKeepsTheFinishedRowsAndClearsTheLine() = runBlocking {
        val session = EvalSession(scope)
        val dir = tmpDir()
        val reached = CompletableDeferred<Unit>()
        val result = async(Dispatchers.Default) {
            session.run(
                problems,
                runOne = { p -> if (p.id == "e3") { reached.complete(Unit); awaitCancellation() } else traceOf(p) },
                runSamples = null, useProblemCheck = false, newReport = { report(it) }, dir = dir,
            )
        }
        reached.await()
        // what someone who picks up the busy phone sees: the choir line, with progress and a Stop
        val line = PhoneActivity.current.value
        assertEquals("choir", line?.owner)
        assertTrue(line!!.text, line.text.contains("2 of 4"))
        assertNotNull(line.onStop)

        line.onStop!!.invoke()                       // tap Stop

        val kept = result.await()
        assertTrue(kept.stopped)
        assertEquals(listOf("e1", "e2"), kept.rows.map { it.problemId })
        val file = File(kept.file!!)
        assertTrue(file.exists())
        val stored = SwarmJson.decodeFromString<EvalReport>(file.readText())
        assertTrue(stored.stopped)
        assertEquals(2, stored.rows.size)
        assertEquals(2, stored.summary.n)

        val p = session.progress.value
        assertTrue(p.stopped)
        assertFalse(p.running)
        assertEquals(2, p.done)
        assertEquals(kept.file, p.lastFile)
        assertNull("the live-state line is cleared", PhoneActivity.current.value)
    }

    @Test fun cancelEvalWithNothingRunningDoesNothing() {
        EvalSession(scope).cancel()
        assertNull(PhoneActivity.current.value)
    }

    @Test fun stoppingBeforeAnyProblemIsFinishedStoresNothingAndStillClearsTheLine() = runBlocking {
        val session = EvalSession(scope)
        val dir = tmpDir()
        val reached = CompletableDeferred<Unit>()
        val result = async(Dispatchers.Default) {
            session.run(problems, runOne = { reached.complete(Unit); awaitCancellation() }, runSamples = null, useProblemCheck = false, newReport = { report(it) }, dir = dir)
        }
        reached.await()
        session.cancel()
        val kept = result.await()
        assertTrue(kept.stopped)
        assertTrue(kept.rows.isEmpty())
        assertNull(kept.file)
        assertEquals(0, dir.listFiles()!!.size)
        assertNull(PhoneActivity.current.value)
    }

    @Test fun anEvalThatFinishesIsNotStoppedAndClearsTheLine() = runBlocking {
        val session = EvalSession(scope)
        val done = session.run(problems, runOne = { traceOf(it) }, runSamples = null, useProblemCheck = false, newReport = { report(it) }, dir = tmpDir())
        assertFalse(done.stopped)
        assertEquals(4, done.rows.size)
        assertTrue(File(done.file!!).exists())
        assertFalse(session.progress.value.stopped)
        assertNull(PhoneActivity.current.value)
    }

    @Test fun aCancelledCallerStillSeesTheCancellationAndTheLineIsCleared() {
        val session = EvalSession(scope)
        val reached = CompletableDeferred<Unit>()
        var thrown: Throwable? = null
        runBlocking {
            val caller = launch(Dispatchers.Default) {
                try {
                    session.run(problems, runOne = { reached.complete(Unit); awaitCancellation() }, runSamples = null, useProblemCheck = false, newReport = { report(it) }, dir = tmpDir())
                } catch (e: CancellationException) {
                    thrown = e
                    throw e
                }
            }
            reached.await()
            caller.cancel()
            caller.join()
        }
        assertNotNull(thrown)
        waitBlocking { PhoneActivity.current.value == null }
    }

    private fun waitBlocking(cond: () -> Boolean) = runBlocking { waitUntil("condition") { cond() } }
}
