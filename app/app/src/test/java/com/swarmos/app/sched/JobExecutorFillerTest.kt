package com.swarmos.app.sched

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
import java.util.concurrent.atomic.AtomicInteger

/** The "Keep busy" filler slot of [JobExecutor]: filler yields to real work, is invisible to it, and never gets it refused. */
class JobExecutorFillerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() = scope.cancel()

    private suspend fun until(what: String, ms: Long = 2_000, check: () -> Boolean) {
        withTimeout(ms) { while (!check()) delay(2) }
    }

    @Test fun fillerIsTakenOnlyWhenNoRealWorkRunsOrWaits() = runBlocking {
        val exec = JobExecutor(scope).also { it.maxQueue = 1 }
        val gate = CompletableDeferred<Unit>()
        val real = async { exec.submit("a") { gate.await(); "a" } }
        until("a running") { exec.running == 1 }
        assertNull("a real job runs", exec.tryAcquireFiller { })
        val queued = async { exec.submit("b") { "b" } }
        until("b queued") { exec.queued == 1 }
        assertNull("a real job runs and another waits", exec.tryAcquireFiller { })
        gate.complete(Unit)
        assertEquals(listOf("a", "b"), listOf(real, queued).map { it.await() })
        until("slots free") { exec.running == 0 && exec.queued == 0 }
        val slot = exec.tryAcquireFiller { }
        assertNotNull("idle: filler may take the slot", slot)
        assertNull("only one filler at a time", exec.tryAcquireFiller { })
        slot!!.release()
        assertNotNull("released: the next filler may take it", exec.tryAcquireFiller { })
    }

    @Test fun anIdleCapacityZeroExecutorLendsNoFiller() {
        val exec = JobExecutor(scope).also { it.capacity = 0 }
        assertNull("no model server, no filler", exec.tryAcquireFiller { })
    }

    @Test fun capacityGoingToZeroStopsTheFillerInFlight() {
        val exec = JobExecutor(scope)
        val stopped = AtomicInteger()
        val slot = exec.tryAcquireFiller { stopped.incrementAndGet() }
        assertNotNull(slot)
        exec.capacity = 0
        assertEquals(1, stopped.get())
        assertTrue(slot!!.isPreempted)
    }

    @Test fun aRealSubmitDuringFillerCancelsItWaitsForTheSlotAndRuns() = runBlocking {
        val exec = JobExecutor(scope)   // maxQueue 0: before filler existed this would have been refused
        val cancelled = CompletableDeferred<Unit>()
        val slot = exec.tryAcquireFiller { cancelled.complete(Unit) }!!
        val ran = AtomicInteger()
        val real = async { exec.submit("real") { ran.incrementAndGet(); "ok" } }
        withTimeout(2_000) { cancelled.await() }
        delay(50)
        assertEquals("the real job waits while the filler holds the slot", 0, ran.get())
        assertEquals("waiting only for filler counts as running, not as queued", 1, exec.running)
        assertEquals(0, exec.queued)
        slot.release()
        assertEquals("ok", real.await())
        assertEquals(1, ran.get())
        until("slot back") { exec.running == 0 }
        assertNotNull("the slot is free for filler again", exec.tryAcquireFiller { })
    }

    @Test fun realWorkIsNeverRefusedBecauseOfFiller() = runBlocking {
        val exec = JobExecutor(scope)   // maxQueue 0
        val slot = exec.tryAcquireFiller { }!!
        // a new stream (tryAcquire) waits for the filler instead of being sent elsewhere
        val stream = async { exec.tryAcquire() }
        until("preempted") { slot.isPreempted }
        assertFalse("the stream is waiting for the slot", stream.isCompleted)
        slot.release()
        assertTrue(stream.await())
        exec.release()
        // a local caller (Choir, Data Union) waits too
        val slot2 = exec.tryAcquireFiller { }!!
        val local = async { exec.acquire() }
        until("preempted") { slot2.isPreempted }
        slot2.release()
        assertTrue(local.await())
        exec.release()
        // and a second real job is refused only because of the first real job, as before
        val slot3 = exec.tryAcquireFiller { }!!
        val first = async { exec.submit("first") { delay(100); "first" } }
        until("preempted") { slot3.isPreempted }
        try {
            exec.submit("second") { "second" }
            fail("expected BusyException: the first real job is what is in the way")
        } catch (e: BusyException) {
            assertEquals("the waiting real job counts as running", 1, e.running)
        }
        slot3.release()
        assertEquals("first", first.await())
    }

    @Test fun aWaitingRoomIsNotUsedUpByFiller() = runBlocking {
        val exec = JobExecutor(scope).also { it.maxQueue = 1 }
        val slot = exec.tryAcquireFiller { }!!
        val a = async { exec.submit("a") { "a" } }
        until("a waits") { slot.isPreempted }
        val b = async { exec.submit("b") { "b" } }   // takes the one place of the waiting room
        until("b queued") { exec.queued == 1 }
        try {
            exec.submit("c") { "c" }
            fail("expected BusyException: a runs next, b waits, c has no place")
        } catch (e: BusyException) {
            assertEquals(1, e.queued)
        }
        slot.release()
        assertEquals(listOf("a", "b"), listOf(a, b).map { it.await() })
    }

    @Test fun callersSeeNoFillerInRunningOrQueued() = runBlocking {
        val phone = FakePhone("p", TestLlm(), scope)
        val slot = phone.node.chatExec.tryAcquireFiller { }!!
        val caps = phone.node.caps()
        assertEquals("filler is not a running job", 0, caps.running)
        assertEquals(0, caps.queued)
        assertEquals(1, caps.capacity)
        slot.release()
    }

    @Test fun aFillerThatIgnoresCancellationForAWhileStillLetsTheRealJobRunOnceItEnds() = runBlocking {
        val exec = JobExecutor(scope)
        val slot = exec.tryAcquireFiller { /* ignores the request to stop */ }!!
        val ran = AtomicInteger()
        val real = async { exec.submit("real") { ran.incrementAndGet(); "ok" } }
        delay(400)
        assertEquals("still waiting while the filler holds the slot", 0, ran.get())
        slot.release()
        assertEquals("ok", real.await())
        assertEquals("the job ran exactly once", 1, ran.get())
        assertEquals("the same job id returns its stored result", "ok", exec.submit("real") { ran.incrementAndGet(); "again" })
        assertEquals(1, ran.get())
    }

    @Test fun aRealJobGivesUpWhenTheFillerStaysPastTheGraceTime() = runBlocking {
        val exec = JobExecutor(scope, fillerGraceMs = 150)
        val slot = exec.tryAcquireFiller { }!!
        val t0 = System.nanoTime()
        try {
            exec.submit("late") { "never" }
            fail("expected BusyException after the grace time")
        } catch (e: BusyException) {
            assertEquals(1, e.capacity)
        }
        val waitedMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue("refused after the grace time, not at once and not much later ($waitedMs ms)", waitedMs in 100..2_000)
        assertEquals("the refused job left the queue", 0, exec.queued)
        assertEquals("and is not remembered, so a retry runs it", 0, exec.remembered)
        // a stream gives up the same way
        assertFalse(exec.tryAcquire())
        slot.release()
        assertEquals("with the filler gone the retry runs", "ok", exec.submit("late") { "ok" })
    }

    @Test fun theSameJobIdWhileFillerWindsDownRunsOnce() = runBlocking {
        val exec = JobExecutor(scope)
        val slot = exec.tryAcquireFiller { }!!
        val runs = AtomicInteger()
        val a = async { exec.submit("j") { runs.incrementAndGet(); "r" } }
        until("preempted") { slot.isPreempted }
        val b = async { exec.submit("j") { runs.incrementAndGet(); "r2" } }
        delay(30)
        slot.release()
        assertEquals(listOf("r", "r"), listOf(a, b).map { it.await() })
        assertEquals(1, runs.get())
    }

    @Test fun withTwoSlotsARealJobStartsAtOnceAndStillStopsTheFiller() = runBlocking {
        val exec = JobExecutor(scope).also { it.capacity = 2 }
        val slot = exec.tryAcquireFiller { }!!
        assertEquals("real", exec.submit("real") { "real" })
        assertTrue("real work always stops filler", slot.isPreempted)
        slot.release()
    }

    @Test fun theIdleClockCountsRealWorkOnly() = runBlocking {
        var now = 1_000L
        val exec = JobExecutor(scope, clock = { now })
        now += 7_000
        assertEquals(7_000L, exec.realIdleMs())
        val slot = exec.tryAcquireFiller { }!!
        now += 3_000
        assertEquals("filler does not reset the clock", 10_000L, exec.realIdleMs())
        slot.release()
        exec.submit("j") { "x" }
        assertEquals("a real job ending resets it", 0L, exec.realIdleMs())
        now += 5_000
        assertEquals(5_000L, exec.realIdleMs())
        val gate = CompletableDeferred<Unit>()
        val running = async { exec.submit("k") { gate.await(); "k" } }
        until("k running") { exec.running == 1 }
        now += 9_000
        assertEquals("0 while real work runs", 0L, exec.realIdleMs())
        gate.complete(Unit)
        assertEquals("k", running.await())
    }
}
