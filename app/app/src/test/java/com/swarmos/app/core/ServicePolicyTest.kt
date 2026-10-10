package com.swarmos.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServicePolicyTest {
    private fun peer(id: String, away: Boolean) =
        PeerState(NodeInfo(id, "Phone $id", "unknown", "10.0.0.1", "0.1.0", null, null), away = away)

    @Test
    fun locksAreHeldWhileTheAppIsInFront() {
        assertTrue(ServicePolicy.holdLocks(appInForeground = true, onlinePeers = 0))
    }

    @Test
    fun locksAreHeldWhileAPeerIsOnline() {
        assertTrue(ServicePolicy.holdLocks(appInForeground = false, onlinePeers = 1))
        assertTrue(ServicePolicy.holdLocks(appInForeground = false, onlinePeers = 2))
    }

    @Test
    fun locksAreReleasedWhenTheAppIsInTheBackgroundAndNoPeerIsOnline() {
        assertFalse(ServicePolicy.holdLocks(appInForeground = false, onlinePeers = 0))
    }

    @Test
    fun locksAreHeldWhileModelWorkRunsEvenInTheBackgroundWithNoPeer() {
        assertTrue(ServicePolicy.holdLocks(appInForeground = false, onlinePeers = 0, modelWorkRunning = true))
        assertFalse(ServicePolicy.holdLocks(appInForeground = false, onlinePeers = 0, modelWorkRunning = false))
    }

    @Test
    fun theWakeLockHasATimeoutAndIsRenewedBeforeItRunsOut() {
        assertTrue(ServicePolicy.WAKE_LOCK_TIMEOUT_MS in 60_000L..30 * 60_000L)
        assertTrue(ServicePolicy.WAKE_LOCK_RENEW_MS * 2 <= ServicePolicy.WAKE_LOCK_TIMEOUT_MS) // one missed renewal still leaves it held
        assertTrue(ServicePolicy.WORK_LINGER_MS > 0)
    }

    @Test
    fun awayPeersDoNotCountAsOnline() {
        val peers = listOf(peer("a", away = false), peer("b", away = true), peer("c", away = true))
        assertEquals(1, ServicePolicy.onlinePeers(peers))
        assertEquals(0, ServicePolicy.onlinePeers(peers.drop(1)))
        assertEquals(0, ServicePolicy.onlinePeers(emptyList()))
    }
}
