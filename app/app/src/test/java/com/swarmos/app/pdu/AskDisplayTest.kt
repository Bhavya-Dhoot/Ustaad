package com.swarmos.app.pdu

import org.junit.Assert.assertEquals
import org.junit.Test

class AskDisplayTest {
    private fun peer(status: String, rtt: Long = 0) = DeviceResult("n", "Phone A", false, status, rttMs = rtt)

    @Test fun aPeerThatDidNotAnswerIsAwayOrNoReplyNeverZeroMilliseconds() {
        assertEquals("away", PeerTiming.text(peer("offline")))
        assertEquals("no reply", PeerTiming.text(peer("unreachable")))
        assertEquals("no reply", PeerTiming.text(peer("error")))
        assertEquals("-", PeerTiming.text(peer("waiting")))
    }

    @Test fun aPeerThatAnsweredShowsItsRoundTrip() {
        assertEquals("614 ms", PeerTiming.text(peer("ok", 614)))
        assertEquals("0 ms", PeerTiming.text(peer("ok", 0))) // a real, instant answer is still a number
    }
}
