package com.swarmos.app.link

import com.swarmos.app.core.NodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun testPath(node: String, link: LinkType, ip: String, port: Int = 8787, state: LinkState = LinkState.ONLINE) = PeerStatus(
    nodeId = node, name = "Phone $node", ip = ip, port = port, link = link, state = state, rttMs = null, lastSeenMs = 0, misses = 0,
    health = null, info = NodeInfo(node, "Phone $node", "x", ip, "0", null, null),
)

class HeartbeatTimingTest {
    @Test fun heartbeatTimeoutIsLongerThanTheMeasuredIdleStalls() {
        assertEquals(1000L, PeerLinks.HEARTBEAT_MS)
        assertEquals(2500L, PeerLinks.HEARTBEAT_TIMEOUT_MS)
        assertTrue("2080 ms was the longest stall measured on an idle LAN", PeerLinks.HEARTBEAT_TIMEOUT_MS > 2080)
    }

    @Test fun anInboundRequestResetsTheMissesSoASlowAnswerIsNotAnAbsence() {
        val t = HeartbeatTracker()
        t.onSuccess(2.0, 1_000)
        t.onMiss(); t.onMiss()
        assertFalse(t.onInbound(3_000))
        assertEquals(0, t.misses)
        t.onMiss()
        assertEquals("one miss after the inbound request is still online", LinkState.ONLINE, t.state)
        assertEquals("an inbound request is not a heartbeat answer", 1, t.beats)
        assertEquals(3_000L, t.lastSeenMs)
    }

    @Test fun withoutTheInboundRequestThreeMissesStillMarkAway() {
        val t = HeartbeatTracker()
        t.onSuccess(2.0, 1_000)
        t.onMiss(); t.onMiss()
        assertTrue(t.onMiss())
        assertEquals(LinkState.AWAY, t.state)
    }

    @Test fun anInboundRequestBringsAnAwayPathBack() {
        val t = HeartbeatTracker()
        t.onSuccess(2.0, 1_000)
        t.onMiss(); t.onMiss(); t.onMiss()
        assertEquals(LinkState.AWAY, t.state)
        assertTrue(t.onInbound(9_000))
        assertEquals(LinkState.ONLINE, t.state)
        assertEquals(0, t.misses)
        assertEquals(8_000L, t.maxGapMs)
    }
}

class InboundDecisionTest {
    private val lan = testPath("n1", LinkType.LAN, "192.168.1.20")
    private val direct = testPath("n1", LinkType.DIRECT, "192.168.49.2")
    private val bt = testPath("n1", LinkType.BT, "bt:n1", 0)

    @Test fun aRequestFromAPathsOwnAddressIsABeatOnThatPath() {
        assertEquals(InboundAction.Beat("n1/LAN"), Inbound.decide(listOf(lan, direct, bt), "192.168.1.20"))
        assertEquals(InboundAction.Beat("n1/DIRECT"), Inbound.decide(listOf(lan, direct, bt), "192.168.49.2"))
    }

    @Test fun aLoopbackRequestIsABluetoothReplay() {
        assertEquals(InboundAction.Beat("n1/BT"), Inbound.decide(listOf(lan, bt), "127.0.0.1"))
        assertEquals("no Bluetooth path: nothing to beat", InboundAction.Ignore, Inbound.decide(listOf(lan), "127.0.0.1"))
    }

    @Test fun aPrivateAddressNoPathHasMeansThePhoneMovedSoReconnectThere() {
        assertEquals(InboundAction.Reconnect("192.168.1.99", 8787), Inbound.decide(listOf(lan), "192.168.1.99"))
        assertEquals("the port of the paths already held is reused", InboundAction.Reconnect("10.0.0.7", 8797), Inbound.decide(listOf(lan.copy(port = 8797)), "10.0.0.7"))
        assertEquals("only a Bluetooth path is held: the default node port", InboundAction.Reconnect("192.168.1.99", 8787), Inbound.decide(listOf(bt), "192.168.1.99"))
    }

    @Test fun aPublicAddressIsNeverFollowed() {
        assertEquals(InboundAction.Ignore, Inbound.decide(listOf(lan), "8.8.8.8"))
        assertEquals(InboundAction.Ignore, Inbound.decide(listOf(lan), "2001:db8::1"))
    }
}

class RateGateTest {
    @Test fun oncePerGapPerKey() {
        var now = 0L
        val g = RateGate(Inbound.RECONNECT_GAP_MS) { now }
        assertTrue(g.tryAcquire("n1"))
        now += 9_999
        assertFalse("inside the 10 s gap", g.tryAcquire("n1"))
        assertTrue("another peer has its own gap", g.tryAcquire("n2"))
        now += 1
        assertTrue("10 s later", g.tryAcquire("n1"))
        assertFalse(g.tryAcquire("n1"))
    }
}
