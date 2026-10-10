package com.swarmos.app.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartbeatPauseTest {
    @Test fun aProbeHoldsTheHeartbeatButNotLongerThanThirtySeconds() {
        var now = 0L
        val p = HeartbeatPause { now }
        assertFalse("no probe: the heartbeat runs", p.paused())
        p.enter()
        assertTrue(p.paused())
        now = 29_999
        assertTrue("still inside the cap", p.paused())
        now = 30_000
        assertFalse("a probe that runs for hours cannot silence the heartbeat", p.paused())
        assertEquals(30_000L, HeartbeatPause.MAX_PAUSE_MS)
    }

    @Test fun theFirstProbeErrorResumesTheHeartbeat() {
        var now = 0L
        val p = HeartbeatPause { now }
        p.enter()
        now = 2_000
        assertTrue(p.paused())
        p.noteError()
        assertFalse("an error means the link is not merely busy", p.paused())
    }

    @Test fun theHoldEndsWithTheProbeAndTheNextProbeStartsFresh() {
        var now = 0L
        val p = HeartbeatPause { now }
        p.enter(); p.noteError(); p.exit()
        assertFalse(p.paused())
        now = 100_000
        p.enter()
        assertTrue("a new probe holds again, its error flag and clock start over", p.paused())
        now = 100_000 + 29_000
        assertTrue(p.paused())
    }

    @Test fun twoProbesOnOnePathHoldUntilTheLastEnds() {
        var now = 0L
        val p = HeartbeatPause { now }
        p.enter(); p.enter()
        p.exit()
        assertTrue(p.paused())
        p.exit()
        assertFalse(p.paused())
    }

    @Test fun anErrorWithNoProbeRunningChangesNothing() {
        val p = HeartbeatPause()
        p.noteError()
        p.enter()
        assertTrue("a stray error from before does not cancel the next probe's hold", p.paused())
    }
}
