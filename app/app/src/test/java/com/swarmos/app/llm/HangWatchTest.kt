package com.swarmos.app.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HangWatchTest {
    @Test fun threeMissesInARowKillTheServer() {
        val w = HangWatch()
        assertFalse(w.record(answered = false, busyWithinTimeout = false))
        assertFalse(w.record(answered = false, busyWithinTimeout = false))
        assertTrue(w.record(answered = false, busyWithinTimeout = false))
        assertEquals(3, HangWatch.MAX_MISSES)
    }

    @Test fun anAnswerInBetweenStartsTheCountAgain() {
        val w = HangWatch()
        w.record(false, false)
        w.record(false, false)
        assertFalse(w.record(true, false))
        assertEquals(0, w.misses)
        assertFalse(w.record(false, false))
        assertFalse(w.record(false, false))
        assertTrue(w.record(false, false))
    }

    @Test fun missesWhileARequestIsWithinItsTimeoutDoNotCount() {
        val w = HangWatch()
        repeat(50) { assertFalse(w.record(answered = false, busyWithinTimeout = true)) }
        assertEquals(0, w.misses)
    }

    @Test fun aBusyProbeNeitherCountsNorResets() {
        val w = HangWatch()
        w.record(false, false)
        w.record(false, false)
        assertFalse(w.record(false, true)) // busy: not a miss, the two earlier ones stay
        assertEquals(2, w.misses)
        assertTrue(w.record(false, false))
    }

    @Test fun aServerThatKeepsAnsweringIsNeverKilledHoweverBusy() {
        val w = HangWatch()
        repeat(100) { assertFalse(w.record(answered = true, busyWithinTimeout = it % 2 == 0)) }
    }

    private val port = 59_001

    @Test fun aCallCountsAsBusyOnlyUntilItsOwnTimeoutRunsOut() {
        val t0 = 1_000_000_000L
        val token = InFlight.begin(port, timeoutMs = 5_000, nowNs = t0)
        try {
            assertTrue(InFlight.busyWithinTimeout(port, nowNs = t0 + 4_999_000_000L))
            assertFalse(InFlight.busyWithinTimeout(port, nowNs = t0 + 5_001_000_000L))
            assertTrue(InFlight.active(port)) // past its timeout it is still in flight, it just no longer excuses silence
        } finally {
            InFlight.end(token, nowNs = t0 + 6_000_000_000L)
        }
        assertFalse(InFlight.active(port))
        assertFalse(InFlight.busyWithinTimeout(port, nowNs = t0 + 6_000_000_000L))
    }

    @Test fun callsAreCountedPerPortAndInTotal() {
        val before = InFlight.count.value
        val a = InFlight.begin(port + 1, 1_000)
        val b = InFlight.begin(port + 2, 1_000)
        try {
            assertEquals(before + 2, InFlight.count.value)
            assertTrue(InFlight.active(port + 1))
            assertFalse(InFlight.active(port + 3))
        } finally {
            InFlight.end(a)
            InFlight.end(b)
            InFlight.end(b) // ending twice must not count twice
        }
        assertEquals(before, InFlight.count.value)
    }

    @Test fun idleTimeIsMeasuredFromTheLastCallThatEnded() {
        val p = port + 10
        assertEquals(Long.MAX_VALUE, InFlight.idleForMs(p))
        val token = InFlight.begin(p, 1_000, nowNs = 0)
        InFlight.end(token, nowNs = 2_000_000_000L)
        assertEquals(3_000L, InFlight.idleForMs(p, nowNs = 5_000_000_000L))
    }
}
