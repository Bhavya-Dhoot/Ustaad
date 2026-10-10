package com.swarmos.app.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket

private const val SECRET = "test-secret"

class PairingPayloadTest {
    private val payload = PairingPayload(
        name = "Phone A", nodeId = "1a2b3c4d", ips = listOf("192.168.242.89", "192.168.49.1"), port = 8797,
        secret = "s3cret-with-+/=-chars", p2p = P2pCreds("DIRECT-sw-1a2b3c4d", "pass1234abcd"),
    )

    @Test fun roundTripThroughBase64Url() {
        val d = Pairing.encode(payload)
        assertFalse("url-safe, no padding", d.contains('+') || d.contains('/') || d.contains('='))
        assertEquals(payload, Pairing.decode(d))
    }

    @Test fun deepLinkRawJsonAndBareBlobAllParse() {
        assertEquals(payload, Pairing.parse(Pairing.toLink(payload)))
        assertEquals(payload, Pairing.parse("  " + Pairing.toLink(payload) + "\n"))
        assertEquals(payload, Pairing.parse(Pairing.encode(payload)))
        assertEquals(payload, Pairing.parse(com.swarmos.app.core.SwarmJson.encodeToString(PairingPayload.serializer(), payload)))
    }

    @Test fun payloadWithoutP2pIsValid() {
        val p = payload.copy(p2p = null)
        assertEquals(p, Pairing.parse(Pairing.toLink(p)))
    }

    @Test fun garbageAndEmptySecretAreRejected() {
        assertNull(Pairing.parse("hello"))
        assertNull(Pairing.parse("swarmos://pair?d=%%%"))
        assertNull(Pairing.parse("swarmos://pair"))
        assertNull(Pairing.parse(Pairing.toLink(payload.copy(secret = ""))))
        assertNull(Pairing.parse(Pairing.toLink(payload.copy(port = 0))))
    }

    @Test fun generatedValuesHaveTheRightShape() {
        assertTrue(Regex("[0-9a-f]{64}").matches(Pairing.randomSecret()))
        assertTrue(Pairing.randomSecret() != Pairing.randomSecret())
        val pass = Pairing.randomPassphrase()
        assertTrue(pass.length in 8..63)
        val ssid = Pairing.directSsid("1a2b3c4d")
        assertTrue("WifiP2pConfig needs DIRECT-xy-", Regex("^DIRECT-[a-zA-Z0-9]{2}-.+").matches(ssid))
        assertTrue(ssid.toByteArray().size <= 32)
        assertEquals(8, Pairing.fingerprint("x").length)
        assertEquals(Pairing.fingerprint("x").take(4) + " " + Pairing.fingerprint("x").drop(4), Pairing.code("x"))
    }
}

class HeartbeatTrackerTest {
    @Test fun threeMissesMarkAwayAndSuccessRecovers() {
        val t = HeartbeatTracker()
        assertTrue(t.onSuccess(2.5, 1000).not())
        assertEquals(LinkState.ONLINE, t.state)
        assertFalse(t.onMiss()); assertFalse(t.onMiss())
        assertEquals("two misses is still online", LinkState.ONLINE, t.state)
        assertTrue("third miss flips", t.onMiss())
        assertEquals(LinkState.AWAY, t.state)
        assertNull(t.rttMs)
        assertFalse("further misses are not new transitions", t.onMiss())
        assertTrue("recovery flips back", t.onSuccess(3.1, 9000))
        assertEquals(LinkState.ONLINE, t.state)
        assertEquals(0, t.misses)
        assertEquals(3.1, t.rttMs!!, 0.0)
        assertEquals(9000L, t.lastSeenMs)
    }

    @Test fun aSuccessResetsTheMissCount() {
        val t = HeartbeatTracker()
        t.onMiss(); t.onMiss()
        t.onSuccess(1.0, 1)
        t.onMiss(); t.onMiss()
        assertEquals(LinkState.ONLINE, t.state)
    }

    @Test fun byeMarksAwayAtOnce() {
        val t = HeartbeatTracker()
        assertTrue(t.onBye())
        assertEquals(LinkState.AWAY, t.state)
        assertFalse(t.onBye())
    }
}

class RttStatsTest {
    @Test fun percentilesAreNearestRank() {
        val ns = LongArray(100) { (it + 1) * 1_000_000L } // 1..100 ms
        val s = rttStats(ns)
        assertEquals(100, s.n)
        assertEquals(1.0, s.minMs, 1e-9)
        assertEquals(100.0, s.maxMs, 1e-9)
        assertEquals(50.5, s.avgMs, 1e-9)
        assertEquals(95.0, s.p95Ms, 1e-9)
    }

    @Test fun singleSample() {
        val s = rttStats(longArrayOf(7_000_000))
        assertEquals(7.0, s.minMs, 1e-9); assertEquals(7.0, s.p95Ms, 1e-9); assertEquals(7.0, s.maxMs, 1e-9)
    }
}

class LinkRouteTest {
    private val links = listOf(
        LocalLink("wlan0", "192.168.242.89", 24),
        LocalLink("p2p-wlan0-0", "192.168.49.1", 24),
        LocalLink("rmnet_data0", "10.20.30.40", 29),
    )

    @Test fun subnetMatching() {
        assertTrue(LinkRoute.sameSubnet("192.168.242.89", "192.168.242.23", 24))
        assertFalse(LinkRoute.sameSubnet("192.168.242.89", "192.168.49.2", 24))
        assertTrue(LinkRoute.sameSubnet("10.0.0.1", "10.0.255.254", 16))
        assertFalse(LinkRoute.sameSubnet("10.0.0.1", "10.1.0.1", 16))
        assertFalse(LinkRoute.sameSubnet("not-an-ip", "10.1.0.1", 16))
        assertFalse(LinkRoute.sameSubnet("10.0.0.1", "10.0.0.2", 0))
    }

    @Test fun peerOnTheP2pSubnetIsDirectAndOthersAreLan() {
        assertEquals("p2p-wlan0-0", LinkRoute.findLocalFor("192.168.49.2", links)?.ifName)
        assertEquals(LinkType.DIRECT, LinkRoute.typeFor("192.168.49.2", links))
        assertEquals("wlan0", LinkRoute.findLocalFor("192.168.242.23", links)?.ifName)
        assertEquals(LinkType.LAN, LinkRoute.typeFor("192.168.242.23", links))
        assertNull(LinkRoute.findLocalFor("172.16.0.5", links))
        assertEquals(LinkType.LAN, LinkRoute.typeFor("172.16.0.5", links))
    }

    @Test fun advertisedAddressesSkipCellular() {
        assertEquals(listOf("wlan0", "p2p-wlan0-0"), LinkRoute.advertisable(links).map { it.ifName })
    }
}

class TputLoopbackTest {
    @Test fun probeMeasuresBytesAndRttOverRealSockets() {
        val server = TputServer(0, { SECRET })
        val port = server.start()
        try {
            val client = TputClient({ SECRET }) { Socket("127.0.0.1", port) }

            val rtt = client.rtt(50)
            assertEquals(50, rtt.n)
            assertTrue(rtt.minMs <= rtt.avgMs && rtt.avgMs <= rtt.maxMs && rtt.p95Ms <= rtt.maxMs)

            var progressed = 0L
            val t = client.throughput(4) { progressed = it }
            assertEquals("receiver counted every byte", 4L * 1024 * 1024, t.bytes)
            assertEquals(4L * 1024 * 1024, progressed)
            assertTrue("receiver-timed Mbps is positive", t.mbpsReceiver > 0)
            assertTrue(t.mbpsSender > 0)
            assertNotNull(t.receiverSeconds)
        } finally {
            server.stop()
        }
    }
}

class SoakCountersAndPathPolicyTest {
    @Test fun countersTrackBeatsMissesAwayAndLongestGap() {
        val t = HeartbeatTracker()
        t.onSuccess(2.0, 1_000)
        t.onSuccess(2.0, 2_000)
        t.onMiss(); t.onMiss(); t.onMiss()          // away once
        t.onSuccess(2.0, 9_000)                      // back after a 7 s gap
        assertEquals(3, t.beats)
        assertEquals(3, t.missesTotal)
        assertEquals(1, t.awayCount)
        assertEquals(7_000L, t.maxGapMs)
    }

    private fun status(link: LinkType, state: LinkState) = PeerStatus(
        "n1", "Phone B", "10.0.0.2", 8797, link, state, null, 0, 0, null,
        com.swarmos.app.core.NodeInfo("n1", "Phone B", "s", "10.0.0.2", "v", null, null),
    )

    @Test fun defaultPolicyPrefersAnOnlineDirectPathThenAnyOnlineThenAnything() {
        val lan = status(LinkType.LAN, LinkState.ONLINE)
        val direct = status(LinkType.DIRECT, LinkState.ONLINE)
        assertEquals(LinkType.DIRECT, PreferDirectOnline.choose(listOf(lan, direct))?.link)
        assertEquals(LinkType.LAN, PreferDirectOnline.choose(listOf(lan, direct.copy(state = LinkState.AWAY)))?.link)
        assertEquals(LinkType.LAN, PreferDirectOnline.choose(listOf(lan.copy(state = LinkState.AWAY), direct.copy(state = LinkState.AWAY)))?.link)
        assertNull(PreferDirectOnline.choose(emptyList()))
    }
}
