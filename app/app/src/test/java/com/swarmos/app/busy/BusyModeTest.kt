package com.swarmos.app.busy

import com.swarmos.app.llm.ChatMessage
import com.swarmos.app.llm.ChatRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class BusyCountersTest {
    private var now = 0L
    private val counters = BusyCounters { now }

    @Test fun countsJobsTokensAndSeconds() {
        counters.jobStarted(); now += 2_000; counters.jobEnded(BusyOutcome.Done, 40)
        counters.jobStarted(); now += 500; counters.jobEnded(BusyOutcome.Cancelled, 6)
        counters.jobStarted(); now += 100; counters.jobEnded(BusyOutcome.Failed, 0)
        counters.jobStarted(); now += 100; counters.jobEnded(BusyOutcome.Stopped, 1)
        val r = counters.report(true, BusyState.Running)
        assertEquals(1, r.jobsDone)
        assertEquals(1, r.jobsCancelled)
        assertEquals(1, r.jobsFailed)
        assertEquals(47L, r.tokens)
        assertEquals(2.7, r.secondsGenerating, 1e-9)
        assertEquals("running", r.state)
    }

    @Test fun shareOfTheLastSixtySecondsCountsTheJobInFlightAndForgetsOldJobs() {
        now = 100_000
        counters.jobStarted(); now += 12_000; counters.jobEnded(BusyOutcome.Done, 1)   // 100 s to 112 s
        now = 120_000
        assertEquals("12 s of the last 60 s", 0.2, counters.report(true, BusyState.Running).share60s, 1e-9)
        counters.jobStarted(); now += 30_000                                              // 120 s to 150 s, still running
        assertEquals("12 s + 30 s in flight", 0.7, counters.report(true, BusyState.Running).share60s, 1e-9)
        counters.jobEnded(BusyOutcome.Done, 1)
        now = 172_000                                                                     // window 112 s to 172 s: the first job just left it
        assertEquals(0.5, counters.report(true, BusyState.Running).share60s, 1e-9)
        now = 400_000
        assertEquals(0.0, counters.report(true, BusyState.Running).share60s, 1e-9)
    }

    @Test fun resetZeroesEverything() {
        counters.jobStarted(); now += 1_000; counters.jobEnded(BusyOutcome.Done, 9)
        counters.reset()
        val r = counters.report(false, BusyState.Off)
        assertEquals(0, r.jobsDone)
        assertEquals(0L, r.tokens)
        assertEquals(0.0, r.secondsGenerating, 0.0)
        assertEquals(0.0, r.share60s, 0.0)
    }

    @Test fun theCounterLineIsPlain() {
        val line = BusyReport(true, "running", 1, 3, 0, 1_540, 38.0, 0.5).line()
        assertEquals("1 job done, 3 stopped by real work, 1,540 tokens, 38 s generating.", line)
    }

    @Test fun theAuditPayloadHasCountsAndSecondsOnly() {
        val payload = BusyReport(true, "running", 4, 2, 1, 300, 12.5, 0.3).toAudit(900)
        assertEquals(setOf("jobsDone", "jobsCancelled", "jobsFailed", "tokens", "secondsGenerating", "secondsOn"), payload.keys)
        assertEquals(JsonPrimitive(900L), payload["secondsOn"])
    }
}

class BusyPromptsTest {
    private fun bench(n: Int) = (0 until n).map { ChatRequest(listOf(ChatMessage("user", "b$it")), maxTokens = 48, temperature = 0.0, seed = it) }

    @Test fun choirAndBenchPromptsAlternateUntilBothAreUsedUp() {
        val list = BusyPrompts.rotation(listOf("c0", "c1"), bench(4))
        assertEquals(listOf("c0", "b0", "c1", "b1", "b2", "b3"), list.map { it.messages.last().content })
    }

    @Test fun everyRequestIsShortAndAsDeterministicAsTheBench() {
        val list = BusyPrompts.rotation(listOf("c0"), listOf(ChatRequest(listOf(ChatMessage("user", "x")), maxTokens = 256, temperature = 0.7)))
        assertTrue(list.all { it.maxTokens <= 48 })
        assertTrue(list.all { it.temperature == 0.0 })
    }

    @Test fun noProblemsStillGivesTheBenchPrompts() {
        assertEquals(3, BusyPrompts.rotation(emptyList(), bench(3)).size)
        assertTrue(BusyPrompts.rotation(emptyList(), emptyList()).isEmpty())
    }
}

class BusyModeTest {
    private val audit = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    private val saved = ConcurrentLinkedQueue<Boolean>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var now = 0L   // the clock BusyMode takes the moment Hammer is switched off from

    @Before fun setUp() {
        BusyMode.restore(false, { saved += it }, { k, p -> audit += k to p })
        BusyMode.clock = { now }
    }

    @After fun tearDown() {
        scope.cancel()
        BusyMode.restore(false, {}, { _, _ -> })
    }

    /** Audit entries are written by another thread: waits until [count] have arrived, then returns their kinds in order. */
    private suspend fun auditKinds(count: Int): List<String> {
        withTimeout(2_000) { while (audit.size < count) delay(5) }
        return audit.map { it.first }
    }

    @Test fun offByDefaultAndRestoringAnOnSwitchWritesNoAuditEntry() {
        assertFalse(BusyMode.enabled.value)
        BusyMode.restore(true, { saved += it }, { k, p -> audit += k to p })
        assertTrue(BusyMode.enabled.value)
        assertTrue(audit.isEmpty())
    }

    @Test fun switchingOnAndOffIsStoredAndAuditedOnceEach() = runBlocking {
        BusyMode.setEnabled(true)
        BusyMode.setEnabled(true)   // no change, no second entry
        BusyMode.setEnabled(false)
        withTimeout(2_000) { while (saved.size < 2) delay(5) }
        // each write stores the switch as it is when the write runs, so quick changes cannot leave an old value last
        assertEquals("the last value stored is the final state", false, saved.last())
        assertEquals(listOf("busy.on", "busy.off"), auditKinds(2))
        assertTrue("busy.off carries the counts", audit[1].second.containsKey("jobsDone"))
    }

    @Test fun hammerIsOffAfterARestoreWhateverKeepBusyWas() {
        BusyMode.setHammer(true)
        BusyMode.restore(true, { saved += it }, { k, p -> audit += k to p })
        assertTrue("Keep busy comes back as stored", BusyMode.enabled.value)
        assertFalse("Hammer is never stored: off at every start", BusyMode.hammer.value)
        assertNull(BusyMode.hammerOffAtMs)
        assertFalse(BusyMode.report().hammer)
    }

    @Test fun turningHammerOnTurnsKeepBusyOn() = runBlocking {
        assertFalse(BusyMode.hammer.value)
        BusyMode.setHammer(true)
        BusyMode.setHammer(true)   // no change, no second entry
        assertTrue(BusyMode.hammer.value)
        assertTrue("Keep busy follows", BusyMode.enabled.value)
        assertTrue(BusyMode.report().hammer)
        withTimeout(2_000) { while (saved.isEmpty()) delay(5) }
        assertEquals(true, saved.last())
        assertEquals(listOf("busy.on", "busy.hammer_on"), auditKinds(2))
    }

    @Test fun turningHammerOffLeavesKeepBusyOnAndNotesTheMoment() = runBlocking {
        now = 5_000
        BusyMode.setHammer(true)
        assertNull("no cool-down while Hammer is on", BusyMode.hammerOffAtMs)
        now = 7_000
        BusyMode.setHammer(false)
        assertFalse(BusyMode.hammer.value)
        assertTrue(BusyMode.enabled.value)
        assertFalse(BusyMode.report().hammer)
        assertEquals("the moment on the clock the loop uses", 7_000L, BusyMode.hammerOffAtMs)
        assertEquals(7_000L, BusyMode.clock())
        now = 9_000
        BusyMode.setHammer(false)   // already off: the cool-down does not start again
        assertEquals(7_000L, BusyMode.hammerOffAtMs)
        assertEquals(listOf("busy.on", "busy.hammer_on", "busy.hammer_off"), auditKinds(3))
        delay(100)
        assertEquals("no fourth entry for the repeated off", 3, audit.size)
    }

    @Test fun switchingHammerOnAgainEndsTheCooldownAtOnce() {
        now = 1_000
        BusyMode.setHammer(true)
        now = 2_000
        BusyMode.setHammer(false)
        assertEquals(2_000L, BusyMode.hammerOffAtMs)
        now = 3_000
        BusyMode.setHammer(true)
        assertNull(BusyMode.hammerOffAtMs)
        now = 4_000
        BusyMode.setHammer(false)
        assertEquals("a new cool-down from the new switch-off", 4_000L, BusyMode.hammerOffAtMs)
    }

    @Test fun turningKeepBusyOffTurnsHammerOffWithoutACooldown() = runBlocking {
        BusyMode.setHammer(true)
        BusyMode.setEnabled(false)
        assertFalse(BusyMode.enabled.value)
        assertFalse(BusyMode.hammer.value)
        assertNull("switching Keep busy off does not count as stopping Hammer", BusyMode.hammerOffAtMs)
        BusyMode.setEnabled(true)
        assertFalse("Hammer does not come back with Keep busy", BusyMode.hammer.value)
        assertNull(BusyMode.hammerOffAtMs)
        assertEquals(listOf("busy.on", "busy.hammer_on", "busy.off", "busy.hammer_off", "busy.on"), auditKinds(5))
    }

    @Test fun hammerOffIsNeverSeenWithoutItsSwitchOffMoment() {
        // an unconfined collector runs inside the switch itself, at the instant the flag changes
        val seen = CopyOnWriteArrayList<Pair<Boolean, Long?>>()
        val watcher = scope.launch(Dispatchers.Unconfined) { BusyMode.hammer.collect { seen += it to BusyMode.hammerOffAtMs } }
        now = 1_000
        BusyMode.setHammer(true)
        now = 2_000
        BusyMode.setHammer(false)
        watcher.cancel()
        assertEquals(listOf(false to null, true to null, false to 2_000L), seen.toList())
    }

    @Test fun switchingKeepBusyOffEndsARestThatWasStillRunning() {
        now = 1_000
        BusyMode.setHammer(true)
        now = 2_000
        BusyMode.setHammer(false)
        assertEquals("Hammer is off and the rest runs", 2_000L, BusyMode.hammerOffAtMs)
        BusyMode.setEnabled(false)
        assertNull("the rest does not wait for Keep busy to come back", BusyMode.hammerOffAtMs)
        BusyMode.setEnabled(true)
        assertNull("and does not start again", BusyMode.hammerOffAtMs)
        assertFalse(BusyMode.hammer.value)
    }

    @Test fun stopUstaadLeavesHammerOffWithoutACooldownAndKeepBusyAsItWas() = runBlocking {
        BusyMode.setHammer(true)
        BusyMode.hammerOffForStop()   // what the service's teardown calls
        assertFalse("a start in this same process finds Hammer off", BusyMode.hammer.value)
        assertTrue("Keep busy is the owner's stored switch: untouched", BusyMode.enabled.value)
        assertNull("the stop is not the owner's switch-off: no rest", BusyMode.hammerOffAtMs)
        assertFalse(BusyMode.report().hammer)
        now = 5_000
        BusyMode.setHammer(true)
        BusyMode.setHammer(false)
        assertEquals(5_000L, BusyMode.hammerOffAtMs)
        BusyMode.hammerOffForStop()   // Hammer was off already, a rest was running: it ends with the stop
        assertNull(BusyMode.hammerOffAtMs)
        assertEquals(listOf("busy.on", "busy.hammer_on", "busy.hammer_off", "busy.hammer_on", "busy.hammer_off"), auditKinds(5))
        BusyMode.hammerOffForStop()   // a second stop changes nothing
        delay(100)
        assertEquals(5, audit.size)
    }

    @Test fun auditEntriesAreWrittenOffTheCallersThreadAndInTheOrderOfTheCalls() = runBlocking {
        val callers = CopyOnWriteArrayList<Thread>()
        val writers = CopyOnWriteArrayList<Thread>()
        val first = AtomicInteger()
        BusyMode.restore(false, { saved += it }) { k, p ->
            if (first.getAndIncrement() == 0) Thread.sleep(500)   // a slow file: the callers must not wait for it
            writers += Thread.currentThread()
            audit += k to p
        }
        val expected = ArrayList<String>()
        val t0 = System.nanoTime()
        repeat(100) {
            callers += Thread.currentThread()
            BusyMode.setHammer(true)    // busy.on, busy.hammer_on
            BusyMode.setHammer(false)   // busy.hammer_off
            BusyMode.setEnabled(false)  // busy.off
            expected += listOf("busy.on", "busy.hammer_on", "busy.hammer_off", "busy.off")
        }
        val callMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue("the calls did not wait for the first slow write ($callMs ms)", callMs < 400)
        assertEquals("every entry arrives, one after another in call order", expected, auditKinds(expected.size))
        assertTrue("written by the save scope, not the caller", writers.none { it in callers })
    }

    @Test fun hammerOffWhileKeepBusyIsOffChangesNothing() = runBlocking {
        BusyMode.setHammer(false)
        assertFalse(BusyMode.hammer.value)
        assertFalse(BusyMode.enabled.value)
        assertNull(BusyMode.hammerOffAtMs)
        delay(100)
        assertTrue(audit.isEmpty())
    }

    @Test fun hammerIsNeverWrittenToTheStore() = runBlocking {
        BusyMode.setHammer(true)
        BusyMode.setHammer(false)
        delay(150)
        assertEquals("only the Keep busy switch is stored", listOf(true), saved.toList())
    }

    @Test fun turningItOnStartsTheCountersAgain() {
        BusyMode.setEnabled(true)
        BusyMode.counters.jobStarted()
        BusyMode.counters.jobEnded(BusyOutcome.Done, 10)
        assertEquals(1, BusyMode.report().jobsDone)
        BusyMode.setEnabled(false)
        BusyMode.setEnabled(true)
        assertEquals(0, BusyMode.report().jobsDone)
    }

    @Test fun summariesComeOnTheClockWhileOnAndNeverPerJob() = runBlocking {
        val started = AtomicInteger()
        val server = scope.launch { BusyMode.serve(summaryMs = 60) { started.incrementAndGet(); awaitCancellation() } }
        BusyMode.setEnabled(true)
        withTimeout(3_000) { while (audit.count { it.first == "busy.summary" } < 3) delay(5) }
        BusyMode.counters.jobStarted()
        BusyMode.counters.jobEnded(BusyOutcome.Done, 5)   // a job does not write an entry by itself
        BusyMode.setEnabled(false)
        delay(300)
        val kinds = audit.map { it.first }
        assertEquals("busy.on", kinds.first())
        assertEquals(1, kinds.count { it == "busy.off" })
        assertTrue("only on, off and summaries: no entry per job", kinds.all { it in setOf("busy.on", "busy.off", "busy.summary") })
        val before = audit.size
        delay(250)
        assertEquals("no summaries while off", before, audit.size)
        assertEquals(1, started.get())
        assertEquals(BusyState.Off, BusyMode.state.value)
        server.cancel()
    }

    @Test fun theLoopRunsOnlyWhileTheSwitchIsOn() = runBlocking {
        val started = AtomicInteger()
        val stopped = AtomicInteger()
        val server = scope.launch {
            BusyMode.serve {
                started.incrementAndGet()
                try {
                    awaitCancellation()
                } finally {
                    stopped.incrementAndGet()
                }
            }
        }
        delay(100)
        assertEquals("off: not running", 0, started.get())
        BusyMode.setEnabled(true)
        withTimeout(2_000) { while (started.get() < 1) delay(5) }
        BusyMode.setEnabled(false)
        withTimeout(2_000) { while (stopped.get() < 1) delay(5) }
        server.cancel()
    }

    @Test fun theLoopStopsWhenTheServiceDoes() = runBlocking {
        val stopped = AtomicInteger()
        BusyMode.setEnabled(true)
        val server = scope.launch { BusyMode.serve { try { awaitCancellation() } finally { stopped.incrementAndGet() } } }
        delay(100)
        server.cancel()
        server.join()
        assertEquals(1, stopped.get())
        assertEquals(BusyState.Off, BusyMode.state.value)
    }
}
