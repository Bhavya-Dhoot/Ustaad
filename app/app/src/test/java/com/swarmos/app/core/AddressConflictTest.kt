package com.swarmos.app.core

import com.swarmos.app.link.HeartbeatTracker
import com.swarmos.app.link.LinkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressConflictTest {
    private fun info(id: String, ip: String) = NodeInfo(id, "Phone $id", "s", ip, "0", null, null)
    private fun registry() = NodeRegistry(info("self", "10.0.0.1"))

    @Test fun twoEntriesAtOneAddressKeepOnlyTheOneThatAnswers() {
        val r = registry()
        r.add(info("old", "10.0.0.7"), nowMs = 1_000)
        r.add(info("new", "10.0.0.7"), nowMs = 2_000)
        r.markAway("old")
        assertEquals(listOf("old"), r.dropAddressConflicts())
        assertEquals(listOf("new"), r.peers.value.map { it.info.nodeId })
    }

    @Test fun ifBothClaimToAnswerTheLatestSeenWins() {
        val r = registry()
        r.add(info("old", "10.0.0.7"), nowMs = 1_000)
        r.add(info("new", "10.0.0.7"), nowMs = 2_000)
        assertEquals(listOf("old"), r.dropAddressConflicts())
    }

    @Test fun aPhoneThatIsMerelyAwayStaysListed() {
        val r = registry()
        r.add(info("b", "10.0.0.7"), nowMs = 1_000)
        r.add(info("c", "10.0.0.8"), nowMs = 1_000)
        r.markAway("b")
        assertTrue(r.dropAddressConflicts().isEmpty())
        assertEquals(setOf("b", "c"), r.peers.value.map { it.info.nodeId }.toSet())
        assertTrue(r.peers.value.first { it.info.nodeId == "b" }.away)
    }

    @Test fun whenNoneOfThemAnswersNothingIsDropped() {
        val r = registry()
        r.add(info("old", "10.0.0.7"), nowMs = 1_000)
        r.add(info("new", "10.0.0.7"), nowMs = 2_000)
        r.markAway("old"); r.markAway("new")
        assertTrue(r.dropAddressConflicts().isEmpty())
        assertEquals(2, r.peers.value.size)
    }

    @Test fun bluetoothAddressesAndDifferentPortsAreNotConflicts() {
        val r = registry()
        r.add(info("a", "bt:a"), nowMs = 1)
        r.add(info("b", "bt:b"), nowMs = 1)
        r.add(info("c", "10.0.0.9:8797"), nowMs = 1)
        r.add(info("d", "10.0.0.9"), nowMs = 1)
        assertTrue(r.dropAddressConflicts().isEmpty())
    }
}

class WrongNodeTrackerTest {
    @Test fun anotherPhoneAtTheAddressMarksThePathAwayAtOnceNotAfterThreeMisses() {
        val t = HeartbeatTracker()
        t.onSuccess(2.0, 1_000)
        assertTrue(t.onWrongNode())
        assertEquals(LinkState.AWAY, t.state)
        assertEquals(1, t.awayCount)
        assertEquals("a second answer from the wrong phone is not a new transition", false, t.onWrongNode())
    }
}
