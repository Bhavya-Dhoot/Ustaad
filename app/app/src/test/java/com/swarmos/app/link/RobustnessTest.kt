package com.swarmos.app.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class RobustnessTest {
    @Test fun awayPathsAreProbedEveryFiveSecondsOnlinePathsEverySecond() {
        assertEquals(1_000L, PeerLinks.beatPeriodMs(LinkState.ONLINE))
        assertEquals(5_000L, PeerLinks.beatPeriodMs(LinkState.AWAY))
    }

    @Test fun anEndpointNothingVouchesForIsForgottenAfterTenMinutes() {
        val t0 = 1_000_000L
        assertFalse(PeerLinks.endpointExpired(t0, t0 + 10 * 60_000L))
        assertTrue(PeerLinks.endpointExpired(t0, t0 + 10 * 60_000L + 1))
        assertEquals(10 * 60_000L, PeerLinks.ENDPOINT_TTL_MS)
    }

    @Test fun theReassemblerGivesUpWhenTheMissingChunkNeverComes() {
        val r = Reassembler(total = 5, maxBuffered = 1, waitLimitMs = 150) { _, _ -> }
        r.accept(1, ByteArray(1)) // waits in the buffer: chunk 0 has not come
        val t0 = System.nanoTime()
        try {
            r.accept(2, ByteArray(1)) // the buffer is full and the gap stays open
            fail("expected the wait to end with an error")
        } catch (e: IOException) {
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue("gave up after about the limit, not for ever: $ms ms", ms in 100..3_000)
        }
    }

    @Test fun theReassemblerStillReleasesAStreamWhenTheGapCloses() {
        val seen = mutableListOf<Int>()
        val r = Reassembler(total = 3, maxBuffered = 1, waitLimitMs = 5_000) { i, _ -> synchronized(seen) { seen += i } }
        r.accept(1, ByteArray(1))
        val late = Thread { r.accept(2, ByteArray(1)) }.apply { start() } // blocks: buffer full
        Thread.sleep(120)
        r.accept(0, ByteArray(1)) // closes the gap; 1 is delivered, then 2 is accepted
        late.join(3_000)
        assertEquals(listOf(0, 1, 2), synchronized(seen) { seen.toList() })
    }
}
