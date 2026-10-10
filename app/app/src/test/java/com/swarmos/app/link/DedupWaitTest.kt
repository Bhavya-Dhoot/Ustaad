package com.swarmos.app.link

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DedupWaitTest {
    @Test fun aDuplicateWaitsAtMostTheLimitThenIsToldBusy() = runBlocking {
        val d = RequestDedup(waitMs = 150)
        assertTrue(d.begin("slow") === RequestDedup.Begin.Run) // the first run never completes in this test
        val t0 = System.nanoTime()
        val second = d.begin("slow")
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("answered Busy (the server turns it into 409)", second === RequestDedup.Begin.Busy)
        assertTrue("waited about the limit, not for ever: $ms ms", ms in 100..2_000)
        assertEquals("the limit is 30 s in the app", 30_000L, RequestDedup.MAX_WAIT_MS)
    }

    @Test fun aDuplicateThatFindsTheFirstRunFinishedInTimeStillReplays() = runBlocking {
        val d = RequestDedup(waitMs = 2_000)
        d.begin("quick")
        val waiter = async { d.begin("quick") }
        delay(30)
        d.complete("quick", StoredResponse(200, "application/json", "{}".toByteArray()))
        assertTrue(waiter.await() is RequestDedup.Begin.Replay)
    }

    @Test fun theBusyAnswerDoesNotDisturbTheFirstRun() = runBlocking {
        val d = RequestDedup(waitMs = 50)
        d.begin("k")
        assertTrue(d.begin("k") === RequestDedup.Begin.Busy)
        d.complete("k", StoredResponse(200, null, ByteArray(0)))
        assertTrue("the first run's answer is still stored for a later retry", d.begin("k") is RequestDedup.Begin.Replay)
    }
}
