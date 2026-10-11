package com.swarmos.app.pdu

import com.swarmos.app.sched.JobExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class AnsweringTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun stop() = scope.cancel()

    @Test fun theWaitForTheModelGivesUpAndLeavesNoPlaceInLineAndNoSlotHeld() = runBlocking {
        val exec = JobExecutor(scope)
        assertEquals(true, exec.acquire()) // another job holds the only slot
        assertEquals(ModelWait.Result.TimedOut, ModelWait.acquire(exec, waitMs = 80))
        assertEquals(0, exec.queued)
        assertEquals(1, exec.running) // still only the other job's slot
        exec.release()
        assertEquals(ModelWait.Result.Slot(true), ModelWait.acquire(exec, waitMs = 80))
        assertEquals(1, exec.running)
        exec.release()
    }

    @Test fun aCallerCancelledInTheInstantTheSlotWasTakenGivesTheSlotBack() = runBlocking {
        var caller: Job? = null
        var cancelled = false
        // acquire() reads the clock after it was called and before it returns true: the caller is cancelled in that instant
        val exec = JobExecutor(scope, clock = {
            val c = caller
            if (c != null && !cancelled) {
                cancelled = true
                c.cancel()
            }
            System.currentTimeMillis()
        })
        caller = scope.launch(start = CoroutineStart.LAZY) { ModelWait.acquire(exec, waitMs = 5_000) }
        caller.start()
        caller.join()
        assertEquals(true, cancelled)
        assertEquals("the slot is back", 0, exec.running)
    }

    @Test fun aSlotThatFreesUpInTimeIsTaken() = runBlocking {
        val exec = JobExecutor(scope)
        exec.acquire()
        scope.launch { delay(60); exec.release() }
        assertEquals(ModelWait.Result.Slot(true), ModelWait.acquire(exec, waitMs = 5_000))
        assertEquals(1, exec.running)
        exec.release()
    }

    @Test fun withoutAModelServerThereIsNoGateAndNoWait() = runBlocking {
        val exec = JobExecutor(scope).also { it.capacity = 0 }
        assertEquals(ModelWait.Result.Slot(false), ModelWait.acquire(exec, waitMs = 80))
        assertEquals(0, exec.running)
    }

    @Test fun theNotesSayWhatTheOwnerNeedsToKnow() {
        assertEquals("No chat model is loaded on this phone, showing sources only", AnswerNotes.NO_CHAT_MODEL)
        assertEquals("Waiting for the model", AnswerNotes.WAITING)
        assertEquals("Gave up waiting for the model after 45 s, showing sources only", AnswerNotes.GAVE_UP)
    }
}
