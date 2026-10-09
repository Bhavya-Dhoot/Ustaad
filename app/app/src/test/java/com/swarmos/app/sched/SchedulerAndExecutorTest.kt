package com.swarmos.app.sched

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SchedulerScoreTest {
    @Test fun fasterMeasuredSpeedScoresHigher() {
        assertTrue(Scheduler.score(caps(tps = 30.0)) > Scheduler.score(caps(tps = 12.0)))
    }

    @Test fun unmeasuredPhoneGetsTheDefaultSpeed() {
        assertEquals(Scheduler.UNKNOWN_TOK_PER_SEC, Scheduler.score(caps(tps = null)), 1e-9)
        assertEquals(Scheduler.UNKNOWN_TOK_PER_SEC, Scheduler.score(caps(tps = 0.0)), 1e-9)
    }

    @Test fun queuedJobsLowerTheScore() {
        assertEquals(10.0, Scheduler.score(caps(tps = 20.0, queued = 1)), 1e-9)
    }

    @Test fun thermalStatusTwoOrMoreIsUsedLast() {
        val hotFast = Scheduler.score(caps(tps = 50.0, thermal = 2))
        val coolSlow = Scheduler.score(caps(tps = 2.0, thermal = 1))
        assertTrue("a moderate-thermal phone ranks below a cool one however fast it is", coolSlow > hotFast)
        assertTrue("among hot phones the faster still comes first", hotFast > Scheduler.score(caps(tps = 10.0, thermal = 3)))
    }

    @Test fun lowBatteryOnlyHurtsWhenNotCharging() {
        val low = Scheduler.score(caps(tps = 50.0, battery = 14, charging = false))
        val lowCharging = Scheduler.score(caps(tps = 50.0, battery = 14, charging = true))
        val edge = Scheduler.score(caps(tps = 50.0, battery = 15, charging = false))
        val unknown = Scheduler.score(caps(tps = 50.0, battery = -1, charging = false))
        assertTrue(low < Scheduler.score(caps(tps = 1.0)))
        assertEquals(50.0, lowCharging, 1e-9)
        assertEquals(50.0, edge, 1e-9)
        assertEquals(50.0, unknown, 1e-9)
    }

    @Test fun pinnedModelSkipsOtherModels() {
        assertEquals(Double.NEGATIVE_INFINITY, Scheduler.score(caps(model = "A"), pinnedModel = "B"), 0.0)
        assertTrue(Scheduler.score(caps(model = "B"), pinnedModel = "B") > 0)
        assertTrue(Scheduler.score(caps(model = "A"), pinnedModel = null) > 0)
        assertFalse(Scheduler.eligible(caps(model = "A"), "B"))
    }
}

class JobExecutorTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() = scope.cancel()

    @Test fun repeatedJobIdRunsOnceAndReturnsTheStoredResult() = runBlocking {
        val exec = JobExecutor(scope)
        val runs = AtomicInteger()
        val first = exec.submit("j1") { "r${runs.incrementAndGet()}" }
        val second = exec.submit("j1") { "r${runs.incrementAndGet()}" }
        assertEquals("r1", first)
        assertEquals("r1", second)
        assertEquals(1, runs.get())
    }

    @Test fun repeatWhileRunningSharesTheRunningJob() = runBlocking {
        val exec = JobExecutor(scope)
        val runs = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val a = async { exec.submit("j") { runs.incrementAndGet(); gate.await(); "done" } }
        while (exec.running == 0) delay(2)
        val b = async { exec.submit("j") { runs.incrementAndGet(); "second" } }
        delay(30)
        assertEquals("a repeat must not take another slot", 1, exec.running)
        gate.complete(Unit)
        assertEquals(listOf("done", "done"), listOf(a, b).awaitAll())
        assertEquals(1, runs.get())
    }

    @Test fun resultsAreForgottenAfterTenMinutes() = runBlocking {
        var now = 1_000L
        val exec = JobExecutor(scope, clock = { now })
        val runs = AtomicInteger()
        exec.submit("j") { runs.incrementAndGet() }
        now += 9 * 60_000
        exec.submit("j") { runs.incrementAndGet() }
        assertEquals("still remembered at 9 minutes", 1, runs.get())
        now += 2 * 60_000
        exec.submit("j") { runs.incrementAndGet() }
        assertEquals("expired after 10 minutes", 2, runs.get())
    }

    @Test fun capacityOneRefusesTheSecondJobWithBusy() = runBlocking {
        val exec = JobExecutor(scope)
        val gate = CompletableDeferred<Unit>()
        val first = async { exec.submit("a") { gate.await(); 1 } }
        while (exec.running == 0) delay(2)
        try {
            exec.submit("b") { 2 }
            fail("expected BusyException")
        } catch (e: BusyException) {
            assertEquals(1, e.running)
            assertEquals(1, e.capacity)
            assertEquals(0, e.queued)
        }
        gate.complete(Unit)
        assertEquals(1, first.await())
        assertEquals("a slot is free again", 2, exec.submit("b") { 2 })
    }

    @Test fun raisingCapacityAdmitsMoreJobs() = runBlocking {
        val exec = JobExecutor(scope)
        val gate = CompletableDeferred<Unit>()
        val a = async { exec.submit("a") { gate.await(); "a" } }
        while (exec.running == 0) delay(2)
        exec.capacity = 2
        val b = async { exec.submit("b") { gate.await(); "b" } }
        while (exec.running < 2) delay(2)
        gate.complete(Unit)
        assertEquals(listOf("a", "b"), listOf(a, b).awaitAll())
    }

    @Test fun waitingRoomHoldsOneJobAndRefusesTheNextWithTheQueueLength() = runBlocking {
        val exec = JobExecutor(scope).also { it.maxQueue = 1 }
        val gate = CompletableDeferred<Unit>()
        val a = async { exec.submit("a") { gate.await(); "a" } }
        while (exec.running == 0) delay(2)
        val b = async { exec.submit("b") { "b" } }
        while (exec.queued == 0) delay(2)
        try {
            exec.submit("c") { "c" }
            fail("expected BusyException")
        } catch (e: BusyException) {
            assertEquals(1, e.queued)
        }
        gate.complete(Unit)
        assertEquals(listOf("a", "b"), listOf(a, b).awaitAll())
        assertEquals(0, exec.running)
    }

    @Test fun aFailedJobIsNotRememberedSoARetryRuns() = runBlocking {
        val exec = JobExecutor(scope)
        try {
            exec.submit<String>("j") { error("boom") }
            fail("expected the failure")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
        assertEquals(0, exec.running)
        assertEquals("ok", exec.submit("j") { "ok" })
    }

    @Test fun whenTheOnlyCallerGoesAwayTheJobIsCancelledAndTheSlotFreed() = runBlocking {
        val exec = JobExecutor(scope)
        val started = CompletableDeferred<Unit>()
        val caller = launch { exec.submit<String>("j") { started.complete(Unit); delay(60_000); "never" } }
        started.await()
        assertEquals(1, exec.running)
        caller.cancel()
        withTimeout(2_000) { while (exec.running != 0) delay(5) }
        assertEquals(0, exec.remembered)
        assertEquals("a retry runs fresh", "again", exec.submit("j") { "again" })
    }

    @Test fun nodeRefusesAPinnedModelItDoesNotRun() = runBlocking {
        val phone = FakePhone("p", TestLlm(modelName = "A"), scope)
        try {
            phone.node.chat("j", req("x"), pinModel = "B")
            fail("expected ModelMismatchException")
        } catch (e: ModelMismatchException) {
            assertEquals("A", e.model)
        }
        assertNotEquals("A", "B")
    }

    @Test fun nodeCapacityOneAnswersBusyToAConcurrentDistinctJob() = runBlocking {
        val phone = FakePhone("p", TestLlm(delayMs = 400), scope)
        val first = async { phone.node.chat("a", req("one")) }
        while (phone.node.chatExec.running == 0) delay(2)
        try {
            phone.node.chat("b", req("two"))
            fail("expected BusyException")
        } catch (e: BusyException) {
            // the slot is taken as soon as the job is accepted, which can be before the model call starts
            assertTrue(phone.llm.maxConcurrent.get() <= 1)
        }
        assertEquals("T:one", first.await().text)
        assertEquals("never more than one model call at a time", 1, phone.llm.maxConcurrent.get())
        assertTrue("decode speed of the last job is reported in caps", phone.node.caps().lastDecodeTokPerSec!! > 0)
    }
}
