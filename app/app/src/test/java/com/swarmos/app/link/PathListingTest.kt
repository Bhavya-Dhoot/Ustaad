package com.swarmos.app.link

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathListingTest {
    @Test
    fun wifiPathsAreListedAtOnceInAnyState() {
        val l = PathListing(5_000)
        assertTrue(l.update(LinkType.LAN, LinkState.ONLINE, 0))
        assertTrue(l.update(LinkType.DIRECT, LinkState.AWAY, 0))
    }

    @Test
    fun aBluetoothPathNeedsFiveSecondsOnline() {
        val l = PathListing(5_000)
        assertFalse(l.update(LinkType.BT, LinkState.ONLINE, 1_000))
        assertFalse(l.update(LinkType.BT, LinkState.ONLINE, 5_999))
        assertTrue(l.update(LinkType.BT, LinkState.ONLINE, 6_000))
    }

    @Test
    fun aChannelThatDropsBeforeItIsStableNeverAppears() {
        val l = PathListing(5_000)
        // up for 4 s, away, up for 4 s, away: each run starts the clock again, so the path is never listed
        for (round in 0 until 4) {
            val t0 = round * 10_000L
            assertFalse(l.update(LinkType.BT, LinkState.ONLINE, t0))
            assertFalse(l.update(LinkType.BT, LinkState.ONLINE, t0 + 4_000))
            assertFalse(l.update(LinkType.BT, LinkState.AWAY, t0 + 5_000))
        }
    }

    @Test
    fun onceListedAPathStaysListedWhileAway() {
        val l = PathListing(5_000)
        assertFalse(l.update(LinkType.BT, LinkState.ONLINE, 0))
        assertTrue(l.update(LinkType.BT, LinkState.ONLINE, 5_000))
        assertTrue(l.update(LinkType.BT, LinkState.AWAY, 6_000))
        assertTrue(l.update(LinkType.BT, LinkState.AWAY, 60_000))
        assertTrue(l.update(LinkType.BT, LinkState.ONLINE, 61_000))
    }
}
