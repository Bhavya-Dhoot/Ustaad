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

    @Before fun setUp() {
        BusyMode.restore(false, { saved += it }, { k, p -> audit += k to p })
    }

    @After fun tearDown() {
        scope.cancel()
        BusyMode.restore(false, {}, { _, _ -> })
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
        assertEquals(listOf("busy.on", "busy.off"), audit.map { it.first })
        assertTrue("busy.off carries the counts", audit[1].second.containsKey("jobsDone"))
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
