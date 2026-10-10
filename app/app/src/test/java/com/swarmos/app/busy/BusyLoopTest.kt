package com.swarmos.app.busy

import com.swarmos.app.core.NodeHealth
import com.swarmos.app.llm.ChatChunk
import com.swarmos.app.llm.ChatMessage
import com.swarmos.app.llm.ChatRequest
import com.swarmos.app.llm.ChatStats
import com.swarmos.app.sched.JobExecutor
import com.swarmos.app.sched.SchedNode
import com.swarmos.app.sched.TestLlm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** The filler loop, driven pass by pass ([BusyLoop.step]) with fake clocks, a fake model and the real executor. */
class BusyLoopTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() = scope.cancel()

    private var execNow = 0L          // the executor's clock: real-work idle time
    private var loopNow = 0L          // the loop's clock: job durations, rests
    private val exec = JobExecutor(scope, clock = { execNow })
    private val counters = BusyCounters { loopNow }
    private val states = ConcurrentLinkedQueue<BusyState>()
    private val requests = ConcurrentLinkedQueue<ChatRequest>()
    private val protectCalls = AtomicInteger()
    private val restoreCalls = AtomicInteger()
    @Volatile private var protectBody: () -> Unit = {}
    @Volatile private var restoreBody: () -> Unit = {}

    @Volatile private var health: NodeHealth? = NodeHealth("n", 0, 80, false, 4096, null, null, null)
    @Volatile private var server: ChatServer? = ChatServer(1, 10 * 60_000L)
    @Volatile private var on = true
    @Volatile private var front = true
    @Volatile private var hammer = false
    @Volatile private var hammerOffAt: Long? = null
    @Volatile private var external = false
    @Volatile private var prompts = listOf(prompt("p1"), prompt("p2"))

    /** What the model does for one filler request; the default takes 2 s of loop time and produces 12 tokens. */
    @Volatile private var body: suspend FlowCollector<ChatChunk>.(ChatRequest) -> Unit = {
        loopNow += 2_000
        emit(ChatChunk("a"))
        emit(ChatChunk("b"))
        emit(ChatChunk("", done = true, stats = ChatStats(5, 12, 1.0, 2000.0)))
    }

    private fun prompt(text: String, maxTokens: Int = 48) = ChatRequest(listOf(ChatMessage("user", text)), maxTokens = maxTokens, temperature = 0.0)

    private fun stream(req: ChatRequest): Flow<ChatChunk> = flow {
        requests += req
        body(req)
    }

    private fun loop(e: JobExecutor = exec) = BusyLoop(
        exec = e,
        stream = ::stream,
        prompts = { prompts },
        health = { health },
        chat = { server },
        enabled = { on },
        counters = counters,
        publish = { states += it },
        appInFront = { front },
        batteryOptimizationIgnored = { false },
        clock = { loopNow },
        externalBusy = { external },
        protect = {
            protectCalls.incrementAndGet()
            protectBody()
            val restore: () -> Unit = { restoreCalls.incrementAndGet(); restoreBody() }
            restore
        },
        hammer = { hammer },
        hammerOffAtMs = { hammerOffAt },
    )

    private fun report() = counters.report(true, states.lastOrNull() ?: BusyState.Off)

    private suspend fun until(ms: Long = 3_000, check: () -> Boolean) = withTimeout(ms) { while (!check()) delay(2) }

    @Test fun waitsForFiveSecondsOfIdleThenRunsAJobAndCountsIt() = runBlocking {
        val l = loop()
        assertEquals(500L, l.step())   // 0 s idle: look again soon
        assertEquals(BusyState.WaitingForIdle, states.last())
        assertTrue(requests.isEmpty())
        execNow = 4_900
        l.step()
        assertEquals("not yet", 0, requests.size)
        execNow = 5_000
        assertEquals("full duty: no rest, only the minimum gap", BusyLoop.MIN_GAP_MS, l.step())
        assertEquals(BusyState.Running, states.last())
        assertEquals(1, requests.size)
        val r = report()
        assertEquals(1, r.jobsDone)
        assertEquals(12L, r.tokens)
        assertEquals(2.0, r.secondsGenerating, 1e-9)
        assertEquals("the job's slot is back", 0, exec.running)
        assertNotNull(exec.tryAcquireFiller { })
    }

    @Test fun halfDutyRestsAsLongAsTheLastJobTook() = runBlocking {
        health = health!!.copy(thermalStatus = 2)
        execNow = 60_000
        assertEquals(2_000L, loop().step())
        assertEquals(BusyState.HalfDuty, states.last())
        assertEquals(1, report().jobsDone)
    }

    @Test fun hammerRunsAWarmPhoneAtFullDutyWithOnlyTheMinimumGap() = runBlocking {
        health = health!!.copy(thermalStatus = 2)
        execNow = 60_000
        hammer = true
        assertEquals("a 2 s job, yet no rest", BusyLoop.MIN_GAP_MS, loop().step())
        assertEquals(BusyState.Running, states.last())
        assertEquals(1, report().jobsDone)
        hammer = false
        assertEquals("hammer off: the rest is as long as the job", 2_000L, loop().step())
        assertEquals(BusyState.HalfDuty, states.last())
    }

    @Test fun noJobDuringTheCooldownAfterHammerAndTheNormalRuleAfterIt() = runBlocking {
        execNow = 60_000
        loopNow = 100_000
        hammerOffAt = 100_000L - 10_000L
        val l = loop()
        assertEquals("polled like the other pauses", BusyLoop.SETTLE_POLL_MS, l.step())
        assertEquals(BusyState.HammerCooldown, states.last())
        assertTrue("no job was started", requests.isEmpty())
        assertEquals("real work would get the slot at once", 0, exec.running)
        loopNow = 100_000L + 79_999L   // 89.999 s after Hammer went off
        assertEquals(BusyLoop.SETTLE_POLL_MS, l.step())
        assertTrue(requests.isEmpty())
        loopNow += 1               // 90 s
        assertEquals("the normal rule again: full duty, minimum gap", BusyLoop.MIN_GAP_MS, l.step())
        assertEquals(BusyState.Running, states.last())
        assertEquals(1, requests.size)
    }

    @Test fun hammerSwitchedOnAgainDuringTheCooldownRunsAtOnce() = runBlocking {
        execNow = 60_000
        loopNow = 100_000
        hammerOffAt = 95_000
        val l = loop()
        l.step()
        assertEquals(BusyState.HammerCooldown, states.last())
        hammer = true      // setHammer(true) also clears the moment
        hammerOffAt = null
        assertEquals(BusyLoop.MIN_GAP_MS, l.step())
        assertEquals(BusyState.Running, states.last())
        assertEquals(1, requests.size)
    }

    @Test fun hammerStillPausesAHotPhone() = runBlocking {
        health = health!!.copy(thermalStatus = 3)
        execNow = 60_000
        hammer = true
        assertEquals(10_000L, loop().step())
        assertEquals(BusyState.PausedHot, states.last())
        assertTrue("no job was started", requests.isEmpty())
    }

    @Test fun hotPhonePausesAndLooksAgainInTenSeconds() = runBlocking {
        health = health!!.copy(thermalStatus = 3)
        execNow = 60_000
        assertEquals(10_000L, loop().step())
        assertEquals(BusyState.PausedHot, states.last())
        assertTrue("no job was started", requests.isEmpty())
    }

    @Test fun lowBatteryOnBatteryPausesAndLooksAgainInTenSeconds() = runBlocking {
        health = health!!.copy(batteryPct = 29, charging = false)
        execNow = 60_000
        assertEquals(10_000L, loop().step())
        assertEquals(BusyState.PausedBattery, states.last())
        assertTrue(requests.isEmpty())
    }

    @Test fun lowFreeMemoryPausesAndLooksAgainInTenSecondsEvenWithHammer() = runBlocking {
        health = health!!.copy(ramAvailMb = BusyPolicy.MEM_FLOOR_MB - 1L)
        execNow = 60_000
        val l = loop()
        assertEquals(10_000L, l.step())
        assertEquals(BusyState.PausedMemory, states.last())
        hammer = true
        assertEquals(10_000L, l.step())
        assertEquals(BusyState.PausedMemory, states.last())
        assertTrue("no job was started", requests.isEmpty())
        assertEquals("real work would get the slot at once", 0, exec.running)
        health = health!!.copy(ramAvailMb = BusyPolicy.MEM_FLOOR_MB.toLong())
        l.step()
        assertEquals("the memory is read again before every job", BusyState.Running, states.last())
        assertEquals(1, requests.size)
    }

    @Test fun healthIsReadAgainBeforeEveryJob() = runBlocking {
        val l = loop()
        execNow = 60_000
        l.step()
        assertEquals(BusyState.Running, states.last())
        health = health!!.copy(thermalStatus = 3)
        l.step()
        assertEquals(BusyState.PausedHot, states.last())
        health = health!!.copy(thermalStatus = 0)
        l.step()
        assertEquals(BusyState.Running, states.last())
        assertEquals(2, requests.size)
    }

    @Test fun nothingRunsWhileUstaadIsNotInFront() = runBlocking {
        front = false
        execNow = 60_000
        loop().step()
        assertEquals(BusyState.PausedBackground, states.last())
        assertTrue(requests.isEmpty())
    }

    @Test fun noModelMeansNoFiller() = runBlocking {
        execNow = 60_000
        server = null
        loop().step()
        assertEquals(BusyState.NoModel, states.last())
        server = ChatServer(1, 10 * 60_000L)
        exec.capacity = 0
        loop().step()
        assertEquals("capacity 0 is no model", BusyState.NoModel, states.last())
        assertTrue(requests.isEmpty())
    }

    @Test fun aSwitchThatIsOffRunsNothing() = runBlocking {
        on = false
        execNow = 60_000
        assertEquals(BusyLoop.OFF_POLL_MS, loop().step())
        assertEquals(BusyState.Off, states.last())
        assertTrue(requests.isEmpty())
    }

    @Test fun promptsTakenInRotationAndCappedAtFortyEightTokens() = runBlocking {
        prompts = listOf(prompt("p1", maxTokens = 256), prompt("p2"), prompt("p3", maxTokens = 10))
        val l = loop()
        execNow = 60_000
        repeat(4) { l.step() }
        assertEquals(listOf("p1", "p2", "p3", "p1"), requests.map { it.messages.last().content })
        assertEquals(listOf(48, 48, 10, 48), requests.map { it.maxTokens })
    }

    @Test fun realWorkStopsTheFillerJobAtOnceAndRunsOnce() = runBlocking {
        body = { awaitCancellation() }
        execNow = 60_000
        val l = loop()
        val pass = async { l.step() }
        until { requests.size == 1 }
        val ran = AtomicInteger()
        val t0 = System.nanoTime()
        val answer = exec.submit("real") { ran.incrementAndGet(); "answer" }
        val waitedMs = (System.nanoTime() - t0) / 1_000_000
        assertEquals("answer", answer)
        assertEquals("the real job ran once", 1, ran.get())
        assertTrue("it did not wait for the filler to run out ($waitedMs ms)", waitedMs < 1_500)
        assertEquals("the loop does not rest after being cancelled", 0L, pass.await())
        val r = report()
        assertEquals(1, r.jobsCancelled)
        assertEquals(0, r.jobsDone)
        assertEquals("a cancelled job is not an error", 0, r.jobsFailed)
        assertEquals(0, exec.running)
    }

    @Test fun afterRealWorkTheLoopWaitsFiveSecondsAgain() = runBlocking {
        execNow = 60_000
        exec.submit("real") { "x" }
        val l = loop()
        l.step()
        assertEquals(BusyState.WaitingForIdle, states.last())
        execNow += 5_000
        l.step()
        assertEquals(BusyState.Running, states.last())
    }

    @Test fun fillerNeverFeedsTheSchedulersNumbers() = runBlocking {
        val llm = TestLlm()
        val node = SchedNode(scope, { "p" }, { llm }, { llm }, { null }, clock = { execNow })
        val l = BusyLoop(
            exec = node.chatExec, stream = ::stream, prompts = { prompts }, health = { health }, chat = { server },
            enabled = { on }, counters = counters, publish = { states += it }, clock = { loopNow },
        )
        execNow = 60_000
        l.step()
        assertEquals("a filler job ran", 1, requests.size)
        assertNull("noteStats was not called", node.lastTokPerSec)
        val caps = node.caps()
        assertEquals(0, caps.running)
        assertEquals(0, caps.queued)
        assertEquals("the model of the node was not used by the loop", 0, llm.streamCalls.get())
        assertEquals(0, llm.executed.size)
    }

    @Test fun aFailingModelCountsAsFailedAndBacksOff() = runBlocking {
        body = { throw IOException("server reset") }
        execNow = 60_000
        assertEquals("an error from the server keeps the usual 10 s back-off", 10_000L, loop().step())
        val r = report()
        assertEquals(1, r.jobsFailed)
        assertEquals(0, r.jobsDone)
        assertEquals(0, exec.running)
        assertNotNull("the slot is back", exec.tryAcquireFiller { })
    }

    @Test fun aServerThatDiesUnderFillerKeepsFillerOffUntilItHasBeenReadyForFiveMinutes() = runBlocking {
        body = {
            server = null            // the chat server goes away mid-job
            throw IOException("connection reset")
        }
        execNow = 60_000
        val l = loop()
        l.step()
        assertEquals(1, report().jobsFailed)
        l.step()
        assertEquals(BusyState.NoModel, states.last())
        server = ChatServer(2, 0)    // restarted
        l.step()
        assertEquals(BusyState.PausedModelRestarted, states.last())
        server = ChatServer(2, 200_000)   // past the usual 120 s, not past 5 minutes
        l.step()
        assertEquals("paused, model restarted", BusyState.PausedModelRestarted, states.last())
        server = ChatServer(2, 300_000)
        body = { emit(ChatChunk("", done = true, stats = ChatStats(1, 1, 1.0, 1.0))) }
        l.step()
        assertEquals(BusyState.Running, states.last())
        server = ChatServer(2, 301_000)
        l.step()
        assertEquals("the 120 s rule applies again", BusyState.Running, states.last())
    }

    @Test fun aFreshlyStartedServerGetsNoFillerForTwoMinutes() = runBlocking {
        execNow = 60_000
        server = ChatServer(1, 119_000)
        loop().step()
        assertEquals(BusyState.PausedModelRestarted, states.last())
        assertTrue(requests.isEmpty())
    }

    @Test fun aBenchmarkOrOtherHeavyWorkCountsAsRealWork() = runBlocking {
        execNow = 60_000
        loopNow = 100_000
        val l = loop()
        external = true
        l.step()
        assertEquals(BusyState.WaitingForIdle, states.last())
        external = false
        loopNow += 3_000
        l.step()
        assertEquals("five seconds are counted from the end of that work", BusyState.WaitingForIdle, states.last())
        loopNow += 2_000
        l.step()
        assertEquals(BusyState.Running, states.last())
    }

    @Test fun heavyWorkThatStartsDuringAJobStopsIt() = runBlocking {
        body = { awaitCancellation() }
        execNow = 60_000
        val pass = async { loop().step() }
        until { requests.size == 1 }
        external = true
        withTimeout(3_000) { pass.await() }
        assertEquals(1, report().jobsCancelled)
        assertEquals(0, exec.running)
        assertNotNull(exec.tryAcquireFiller { })
    }

    @Test fun switchingOffMidJobStopsItAndGivesTheSlotBack() = runBlocking {
        body = { awaitCancellation() }
        execNow = 60_000
        val job = scope.launch { loop().run() }
        until { requests.size == 1 }
        job.cancel()
        job.join()
        val r = report()
        assertEquals("stopping is neither done nor cancelled by real work", 0, r.jobsDone + r.jobsCancelled + r.jobsFailed)
        assertNotNull("the slot is free", exec.tryAcquireFiller { })
    }

    @Test fun theProtectHookRunsAroundEveryJob() = runBlocking {
        execNow = 60_000
        val l = loop()
        repeat(3) { l.step() }
        assertEquals(3, protectCalls.get())
        assertEquals(3, restoreCalls.get())
    }

    @Test fun aRestoreThatThrowsStillGivesTheSlotBackAndTheJobIsCounted() = runBlocking {
        execNow = 60_000
        restoreBody = { throw IllegalStateException("restore broke") }
        try {
            loop().step()
            fail("the error of the restore function is not hidden")
        } catch (e: IllegalStateException) {
            assertEquals("restore broke", e.message)
        }
        assertEquals("the slot is free", 0, exec.running)
        assertEquals("the job was counted as ended", 1, report().jobsDone)
        restoreBody = {}
        assertEquals("the next real job runs", "next", exec.submit("real") { "next" })
        assertNotNull(exec.tryAcquireFiller { })
    }

    @Test fun aProtectThatThrowsStillGivesTheSlotBackAndCountsNoJob() = runBlocking {
        execNow = 60_000
        protectBody = { throw IllegalStateException("protect broke") }
        try {
            loop().step()
            fail("the error of the protect function is not hidden")
        } catch (e: IllegalStateException) {
            assertEquals("protect broke", e.message)
        }
        assertTrue("the model was never called", requests.isEmpty())
        assertEquals("a job that never started is not counted", 0, report().jobsDone + report().jobsCancelled + report().jobsFailed)
        assertEquals(0, exec.running)
        assertNotNull("the slot is free", exec.tryAcquireFiller { })
    }

    @Test fun aStreamThatNeverAnswersEndsAtTheLimitAsFailedAndStepReturnsTheLongBackOff() = runBlocking {
        body = { awaitCancellation() }
        execNow = 60_000
        val pass = async { loop().step() }
        until { requests.size == 1 }
        loopNow += BusyPolicy.FILLER_TIMEOUT_MS - 1
        delay(700)   // the loop looks at its clock every 250 ms of real time
        assertFalse("not yet: 59.999 s of loop time", pass.isCompleted)
        loopNow += 1
        assertEquals("a stuck server is a failure, with the longer back-off", 45_000L, withTimeout(3_000) { pass.await() })
        assertEquals(BusyLoop.TIMEOUT_BACKOFF_MS, 45_000L)
        val r = report()
        assertEquals(1, r.jobsFailed)
        assertEquals("real work did not ask for the slot", 0, r.jobsCancelled)
        assertEquals(0, r.jobsDone)
        assertEquals("the slot is free", 0, exec.running)
        assertNotNull(exec.tryAcquireFiller { })
    }

    @Test fun theModelCallIsReallyEndedBeforeTheSlotGoesBackAfterTheTimeLimit() = runBlocking {
        val ended = AtomicInteger()
        body = { try { awaitCancellation() } finally { ended.incrementAndGet() } }
        execNow = 60_000
        val pass = async { loop().step() }
        until { requests.size == 1 }
        loopNow += BusyPolicy.FILLER_TIMEOUT_MS
        withTimeout(3_000) { pass.await() }
        assertEquals("the stream was cancelled and joined", 1, ended.get())
        assertEquals(1, report().jobsFailed)
    }

    @Test fun realWorkAtTenSecondsIsStillCountedCancelledNotFailed() = runBlocking {
        body = { awaitCancellation() }
        execNow = 60_000
        val pass = async { loop().step() }
        until { requests.size == 1 }
        loopNow += 10_000
        assertEquals("real job", "answer", exec.submit("real") { "answer" })
        assertEquals("no back-off after real work", 0L, withTimeout(3_000) { pass.await() })
        val r = report()
        assertEquals(1, r.jobsCancelled)
        assertEquals(0, r.jobsFailed)
    }

    @Test fun noPromptsMeansNothingToRun() = runBlocking {
        prompts = emptyList()
        execNow = 60_000
        assertEquals(BusyLoop.RETRY_MS, loop().step())
        assertFalse(requests.isNotEmpty())
    }
}
