package com.swarmos.app.sched

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/** Runs nothing until [runPending] is called: the executor's job coroutines stay undispatched while the test cancels the caller. */
internal class ManualDispatcher : CoroutineDispatcher() {
    private val tasks = ConcurrentLinkedQueue<Runnable>()

    val pending: Int get() = tasks.size

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        tasks += block
    }

    fun runPending() {
        while (true) tasks.poll()?.run() ?: return
    }
}

/**
 * A caller that goes away after [JobExecutor.submit] counted a slot (or queued a waiter) but before the job's coroutine ran a
 * line: the slot or the place in line must still be given back, else a phone with one slot refuses every job for good.
 */
class JobExecutorCancelBeforeStartTest {
    private val manual = ManualDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + manual)

    private suspend fun until(ms: Long = 2_000, check: () -> Boolean) = withTimeout(ms) { while (!check()) delay(2) }

    @Test fun aCallerCancelledBeforeTheJobRanGivesTheSlotBack() = runBlocking {
        var now = 0L
        val exec = JobExecutor(scope, clock = { now })
        val ran = AtomicInteger()
        val caller = async { exec.submit("a") { ran.incrementAndGet(); "a" } }
        until { exec.running == 1 && manual.pending == 1 }   // the slot is counted, the job coroutine has not run a line
        caller.cancelAndJoin()
        manual.runPending()
        assertEquals("the job never started its work", 0, ran.get())
        assertEquals("the slot is back", 0, exec.running)
        assertEquals(0, exec.remembered)
        now = 7_000
        assertEquals("the idle clock runs again", 7_000L, exec.realIdleMs())
        assertNotNull("the phone is not stuck at capacity", exec.tryAcquireFiller { })
    }

    @Test fun aResendThatLandsOnAnAbandonedRunStartsAFreshRunInsteadOfGettingItsCancellation() = runBlocking {
        val exec = JobExecutor(scope)
        val caller = async { exec.submit("a") { "first" } }
        until { exec.running == 1 && manual.pending == 1 }
        caller.cancelAndJoin()           // the attempt timed out: the run is abandoned and cancelled, but has not wound up yet
        val resend = async { exec.submit("a") { "second" } }
        delay(100)                       // the resend is there while the old entry still is
        until { manual.runPending(); resend.isCompleted }   // the old run winds up; the fresh one is queued behind it
        assertEquals("a fresh run, not the cancellation of the abandoned one", "second", resend.await())
        assertEquals(0, exec.running)
    }

    @Test fun aCallerCancelledWhileQueuedBeforeTheJobRanLeavesTheQueue() = runBlocking {
        val exec = JobExecutor(scope).also { it.maxQueue = 1 }
        assertTrue(exec.acquire())   // a local caller holds the only slot, so the job has to wait
        val caller = async { exec.submit("b") { "b" } }
        until { exec.queued == 1 && manual.pending == 1 }
        caller.cancelAndJoin()
        manual.runPending()
        assertEquals("no place in line is kept for a job that is gone", 0, exec.queued)
        exec.release()
        assertEquals("the slot is not handed to a job that is gone", 0, exec.running)
        assertNotNull(exec.tryAcquireFiller { })
    }

    @Test fun aCallerCancelledWhileWaitingForFillerBeforeTheJobRanDoesNotLoseTheSlotWhenFillerLeaves() = runBlocking {
        val exec = JobExecutor(scope)   // maxQueue 0: only filler is in the way, so the job waits for it
        val slot = exec.tryAcquireFiller { }!!
        val caller = async { exec.submit("c") { "c" } }
        until { slot.isPreempted && manual.pending == 1 }
        caller.cancelAndJoin()
        manual.runPending()
        slot.release()
        assertEquals(0, exec.running)
        assertEquals(0, exec.queued)
        assertNotNull("the slot can be lent to filler again", exec.tryAcquireFiller { })
    }

    @Test fun aJobWhoseCallerStaysStillRunsAfterTheDispatcherGetsToIt() = runBlocking {
        val exec = JobExecutor(scope)
        val caller = async { exec.submit("d") { "d" } }
        until { manual.pending == 1 }
        manual.runPending()
        assertEquals("d", caller.await())
        assertEquals(0, exec.running)
    }
}
