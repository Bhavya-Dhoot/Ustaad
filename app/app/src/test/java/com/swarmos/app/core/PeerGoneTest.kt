package com.swarmos.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A device that stays away for 10 minutes is gone: the pure rule, and the registry's set and its one call each way. */
class PeerGoneTest {
    private fun info(id: String) = NodeInfo(id, "Phone $id", "s", "10.0.0.7", "0", null, null)
    private val calls = mutableListOf<Pair<String, Boolean>>()
    private fun registry() = NodeRegistry(info("self")).also { r -> r.onGoneChanged = { id, gone -> calls += id to gone } }

    private fun away(sinceMs: Long) = PeerState(info("b"), away = true, awaySinceMs = sinceMs)

    // ---- the rule --------------------------------------------------------------------------------------------

    @Test fun aDeviceIsGoneAfterTenMinutesAway() {
        assertFalse("answering", PeerPolicy.isGone(PeerState(info("b"), awaySinceMs = 0), 10_000_000))
        assertFalse("9 min 59.999 s", PeerPolicy.isGone(away(1_000), 1_000 + 599_999))
        assertTrue("10 min", PeerPolicy.isGone(away(1_000), 1_000 + 600_000))
        assertTrue("much later", PeerPolicy.isGone(away(1_000), 1_000 + 3_600_000))
        assertFalse("away with no start time is not gone", PeerPolicy.isGone(PeerState(info("b"), away = true), 10_000_000))
        assertTrue("another limit", PeerPolicy.isGone(away(0).copy(awaySinceMs = 5), 105, afterMs = 100))
    }

    @Test fun aDeviceThatAnswersIsNotGoneWhateverItsOldAwayTime() {
        assertFalse(PeerPolicy.isGone(PeerState(info("b"), away = false, awaySinceMs = 1), 10_000_000))
    }

    // ---- the registry ----------------------------------------------------------------------------------------

    @Test fun becomingGoneCallsTheListenerOnceAndTheSetHoldsTheDevice() {
        val r = registry()
        r.add(info("b"), nowMs = 0)
        r.markAway("b", nowMs = 1_000)
        r.sweepGone(nowMs = 1_000 + 599_999)
        assertTrue(calls.isEmpty())
        assertTrue(r.gone.value.isEmpty())
        r.sweepGone(nowMs = 1_000 + 600_000)
        r.sweepGone(nowMs = 1_000 + 700_000)
        assertEquals("called once, not on every look", listOf("b" to true), calls)
        assertEquals(setOf("b"), r.gone.value)
    }

    @Test fun aRepeatedAwayMarkDoesNotRestartTheTime() {
        val r = registry()
        r.add(info("b"), nowMs = 0)
        r.markAway("b", nowMs = 1_000)
        r.markAway("b", nowMs = 400_000)      // the heartbeat marks it every time it misses
        r.sweepGone(nowMs = 601_000)
        assertEquals(listOf("b" to true), calls)
    }

    @Test fun comingBackBeforeTenMinutesIsNeverGone() {
        val r = registry()
        r.add(info("b"), nowMs = 0)
        r.markAway("b", nowMs = 1_000)
        r.sweepGone(nowMs = 300_000)
        r.add(info("b"), nowMs = 400_000)     // answers again
        r.markAway("b", nowMs = 500_000)       // and a new absence starts its own ten minutes
        r.sweepGone(nowMs = 700_000)
        assertTrue(calls.isEmpty())
        r.sweepGone(nowMs = 1_100_000)
        assertEquals(listOf("b" to true), calls)
    }

    @Test fun comingBackAfterTenMinutesCallsTheListenerOnceAndTheDeviceIsNormalAtOnce() {
        val r = registry()
        r.add(info("b"), nowMs = 0)
        r.markAway("b", nowMs = 1_000)
        r.sweepGone(nowMs = 700_000)
        assertEquals(listOf("b" to true), calls)
        r.updateHealth(NodeHealth("b", 0, 80, false, 4096, null, null, null), nowMs = 800_000)   // a health beat is an answer
        assertEquals(listOf("b" to true, "b" to false), calls)
        assertTrue(r.gone.value.isEmpty())
        assertFalse(r.peers.value.first { it.info.nodeId == "b" }.away)
        r.add(info("b"), nowMs = 900_000)
        r.sweepGone(nowMs = 900_000)
        assertEquals("no second call", 2, calls.size)
    }

    @Test fun aGoneDeviceThatIsRemovedIsNotReportedAsBack() {
        val r = registry()
        r.add(info("b"), nowMs = 0)
        r.markAway("b", nowMs = 1_000)
        r.sweepGone(nowMs = 700_000)
        r.remove("b")
        assertEquals(listOf("b" to true), calls)
        assertTrue(r.gone.value.isEmpty())
    }

    @Test fun devicesAreJudgedOneByOne() {
        val r = registry()
        r.add(info("b"), nowMs = 0)
        r.add(info("c"), nowMs = 0)
        r.markAway("b", nowMs = 1_000)
        r.markAway("c", nowMs = 400_000)
        r.sweepGone(nowMs = 700_000)
        assertEquals(listOf("b" to true), calls)
        r.sweepGone(nowMs = 1_000_000)
        assertEquals(listOf("b" to true, "c" to true), calls)
        assertEquals(setOf("b", "c"), r.gone.value)
    }

    @Test fun aListenerThatThrowsDoesNotBreakTheRegistry() {
        val r = NodeRegistry(info("self"))
        r.onGoneChanged = { _, _ -> error("listener broke") }
        r.add(info("b"), nowMs = 0)
        r.markAway("b", nowMs = 1_000)
        r.sweepGone(nowMs = 700_000)
        assertEquals(setOf("b"), r.gone.value)
    }
}
