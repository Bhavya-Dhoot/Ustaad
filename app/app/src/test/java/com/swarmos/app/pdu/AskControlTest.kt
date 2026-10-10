package com.swarmos.app.pdu

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** Finding 1: the answer stream has an idle budget, lookups have a budget with a fallback, a new ask replaces the old, Stop ends it. */
class AskControlTest {
    // ---- the idle budget of the answer stream -------------------------------------------------------------

    @Test fun aStreamThatGoesQuietEndsWithStreamIdleAndKeepsTheTextSoFar() = runBlocking {
        val got = StringBuilder()
        val frozen: Flow<String> = flow { emit("Hello "); emit("world"); awaitCancellation() }
        val t0 = System.currentTimeMillis()
        try {
            IdleStream.collect(frozen, 200) { got.append(it) }
            fail("expected StreamIdle")
        } catch (e: StreamIdle) {
            assertEquals(200, e.idleMs)
        }
        assertEquals("Hello world", got.toString())
        assertTrue("took ${System.currentTimeMillis() - t0} ms", System.currentTimeMillis() - t0 < 1_500)
    }

    @Test fun aSlowStreamThatKeepsWritingIsNeverCutOff() = runBlocking {
        val slow = flow { repeat(8) { delay(60); emit(it) } } // 480 ms in all, but never 200 ms without a token
        val got = ArrayList<Int>()
        IdleStream.collect(slow, 200) { got += it }
        assertEquals((0..7).toList(), got)
    }

    @Test fun theBudgetCoversTheWaitForTheFirstToken() = runBlocking {
        try {
            IdleStream.collect(flow<String> { awaitCancellation() }, 150) { }
            fail("expected StreamIdle")
        } catch (e: StreamIdle) {
        }
    }

    @Test fun aReadThatIgnoresCancellationCannotHoldTheCaller() = runBlocking {
        val blocked = flow<String> { emit("x"); withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { Thread.sleep(3_000) } }
        val t0 = System.currentTimeMillis()
        try {
            IdleStream.collect(blocked, 200) { }
            fail("expected StreamIdle")
        } catch (e: StreamIdle) {
        }
        assertTrue("took ${System.currentTimeMillis() - t0} ms, the read lasts 3000 ms", System.currentTimeMillis() - t0 < 1_500)
    }

    @Test fun aFailingStreamThrowsItsOwnError() = runBlocking {
        try {
            IdleStream.collect(flow<String> { emit("a"); throw IOException("reset") }, 1_000) { }
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("reset", e.message)
        }
    }

    // ---- the lookups ----------------------------------------------------------------------------------------

    @Test fun aLookupPastItsBudgetFallsBackToKeywords() = runBlocking {
        val t0 = System.currentTimeMillis()
        val r = Bounded.withFallback(200, { awaitCancellation() }, { "keywords" })
        assertEquals("keywords", r)
        assertTrue(System.currentTimeMillis() - t0 < 1_500)
    }

    @Test fun aLookupInsideItsBudgetNeverTouchesTheFallback() = runBlocking {
        var fell = false
        assertEquals("dense", Bounded.withFallback(1_000, { "dense" }, { fell = true; "keywords" }))
        assertFalse(fell)
    }

    @Test fun theLookupBudgetIsThreeSeconds() {
        assertEquals(3_000L, Bounded.LOOKUP_BUDGET_MS)
        assertEquals(45_000L, IdleStream.ANSWER_IDLE_MS)
    }

    // ---- one ask at a time -----------------------------------------------------------------------------------

    @Test fun aNewAskCancelsTheOneBeforeItAndWaitsForItsCleanUp() = runBlocking {
        val slot = AskSlot()
        val log = java.util.Collections.synchronizedList(ArrayList<String>())
        val firstRunning = CompletableDeferred<Unit>()
        val first = async(Dispatchers.Default) {
            slot.run<String> {
                try {
                    log += "first start"; firstRunning.complete(Unit); awaitCancellation()
                } finally {
                    // the clean up of the old ask finishes before the new one starts
                    withContext(kotlinx.coroutines.NonCancellable) { delay(100); log += "first cleaned up" }
                }
            }
        }
        firstRunning.await()
        val second = slot.run { log += "second start"; "answer 2" }
        assertEquals("answer 2", second)
        assertNull("the replaced ask returns null, it is not an error", first.await())
        assertEquals(listOf("first start", "first cleaned up", "second start"), log.toList())
    }

    @Test fun stopCancelsTheRunningAskAndItReturnsNull() = runBlocking {
        val slot = AskSlot()
        val started = CompletableDeferred<Unit>()
        val job = async(Dispatchers.Default) { slot.run<Unit> { started.complete(Unit); awaitCancellation() } }
        started.await()
        assertTrue(slot.running)
        assertTrue(slot.stop())
        assertNull(job.await())
        assertFalse(slot.running)
        assertFalse("nothing left to stop", slot.stop())
    }

    @Test fun anAskThatFinishesReturnsItsResultAndFreesTheSlot() = runBlocking {
        val slot = AskSlot()
        assertEquals(7, slot.run { 7 })
        assertFalse(slot.running)
    }

    @Test fun theCallersOwnCancellationStillPropagates() = runBlocking {
        val slot = AskSlot()
        val started = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) { slot.run<Unit> { started.complete(Unit); awaitCancellation() } }
        started.await()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(slot.running)
    }

    @Test fun anErrorInTheAskReachesTheCaller() = runBlocking {
        try {
            AskSlot().run<String> { throw IOException("disk") }
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("disk", e.message)
        }
        Unit
    }

    // ---- loops that must not end the process (finding 2) -------------------------------------------------------

    @Test fun aStepThatThrowsIsReportedAndTheLoopGoesOn() = runBlocking {
        val seen = ArrayList<String>()
        var ran = 0
        repeat(3) { i ->
            val ok = StepGuard.run({ seen += StepGuard.describe("loop", it) }) {
                ran++
                if (i == 1) throw IOException("database is locked")
            }
            assertEquals(i != 1, ok)
        }
        assertEquals(3, ran)
        assertEquals(listOf("loop: IOException database is locked"), seen)
    }

    @Test fun cancellationIsNotSwallowedByAStep() = runBlocking {
        try {
            StepGuard.run({ fail("cancellation is not an error") }) { throw CancellationException("stop") }
            fail("expected CancellationException")
        } catch (e: CancellationException) {
        }
    }
}
