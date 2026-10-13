package com.swarmos.app.pdu

import com.swarmos.app.pdu.AutoIndexGate.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When an automatic index run may start ([AutoIndexGate]) and when one is due ([AutoIndexPlan]). */
class AutoIndexTest {
    private fun gate(
        ready: Boolean = true, reindex: Boolean = false, active: Boolean = false, asking: Boolean = false,
        thermal: Int = 0, battery: Int = 80, charging: Boolean = false,
    ) = AutoIndexGate.decide(ready, reindex, active, asking, thermal, battery, charging)

    // ---- the gate ---------------------------------------------------------------------------------------------

    @Test fun anIdlePhoneWithAReadyEmbedderGoes() {
        assertEquals(Decision.GO, gate())
    }

    @Test fun noRunStartsWhileAQuestionIsBeingAnswered() {
        assertEquals(Decision.ASKING, gate(asking = true))
    }

    @Test fun noRunStartsAtThermalStatusThreeOrMore() {
        assertEquals(Decision.GO, gate(thermal = 2))
        for (status in 3..6) assertEquals("status $status", Decision.HOT, gate(thermal = status))
    }

    @Test fun noRunStartsUnderThirtyPercentWhenNotCharging() {
        assertEquals(Decision.BATTERY, gate(battery = 29))
        assertEquals(Decision.GO, gate(battery = 30))
        assertEquals(Decision.BATTERY, gate(battery = 0))
    }

    @Test fun aChargingPhoneRunsAtAnyBatteryLevel() {
        assertEquals(Decision.GO, gate(battery = 5, charging = true))
    }

    @Test fun anUnknownBatteryLevelDoesNotHoldARunBack() {
        assertEquals(Decision.GO, gate(battery = -1))
    }

    @Test fun aRunNeedsAReadyEmbedderAndAnIndexThatDoesNotWaitForTheOwner() {
        assertEquals(Decision.EMBEDDER, gate(ready = false))
        assertEquals(Decision.EMBEDDER, gate(reindex = true))
    }

    @Test fun aRunIsNeverStartedOnTopOfAnotherOne() {
        assertEquals(Decision.RUN_ACTIVE, gate(active = true))
    }

    @Test fun whenSeveralReasonsHoldTheFirstInOrderIsNamed() {
        assertEquals(Decision.RUN_ACTIVE, gate(active = true, ready = false, asking = true, thermal = 4, battery = 3))
        assertEquals(Decision.EMBEDDER, gate(ready = false, asking = true, thermal = 4, battery = 3))
        assertEquals(Decision.ASKING, gate(asking = true, thermal = 4, battery = 3))
        assertEquals(Decision.HOT, gate(thermal = 4, battery = 3))
    }

    @Test fun everyReasonToWaitHasAnExplanationAndGoHasNone() {
        assertTrue(Decision.entries.filter { it != Decision.GO }.all { it.why.isNotBlank() })
        assertEquals("", Decision.GO.why)
    }

    // ---- the plan -----------------------------------------------------------------------------------------------

    private val min = 60_000L
    private fun plan(start: Long = 0) = AutoIndexPlan(start)

    @Test fun nothingIsDueOnAQuietPhone() {
        assertNull(plan().due(14 * min))
    }

    @Test fun theRunAfterStartCoversPhotosAndFolders() {
        val p = plan().also { it.requested("app start") }
        val d = p.due(5_000)!!
        assertTrue(d.photos && d.trees)
        assertEquals(listOf("app start"), d.reasons)
    }

    @Test fun aRequestFromAnotherPartCoversBothSourcesAndIsNamed() {
        val p = plan()
        p.requested("cloud store: new file")
        val d = p.due(1_000)!!
        assertTrue(d.photos && d.trees)
        assertEquals(listOf("cloud store: new file"), d.reasons)
    }

    @Test fun aRunThatWasDoneIsNotDueAgain() {
        val p = plan().also { it.requested("app start") }
        val d = p.due(5_000)!!
        p.ran(6_000, d, complete = true)
        assertNull(p.due(7_000))
    }

    @Test fun aRunThatWasHeldBackHalfWayStaysDue() {
        val p = plan().also { it.requested("app start") }
        val d = p.due(5_000)!!
        p.ran(6_000, d, complete = false)
        assertNotNull(p.due(7_000))
    }

    @Test fun aLibraryChangeIsReadThirtySecondsAfterTheLastChange() {
        val p = plan()
        p.libraryChanged(1_000)
        assertNull(p.due(30_999))
        val d = p.due(31_000)!!
        assertTrue(d.photos)
        assertFalse(d.trees)
        assertEquals(listOf("photo library changed"), d.reasons)
    }

    @Test fun eachNewChangeMovesTheThirtySecondsOn() {
        val p = plan()
        p.libraryChanged(0)
        p.libraryChanged(20_000)
        assertNull(p.due(45_000))
        assertNotNull(p.due(50_000))
    }

    @Test fun aLibraryThatNeverGoesQuietIsStillReadAfterTenMinutes() {
        val p = plan()
        var t = 0L
        while (t < 10 * min) { p.libraryChanged(t); t += 20_000 } // a change every 20 s
        assertNull(p.due(10 * min - 1))
        assertNotNull(p.due(10 * min))
    }

    @Test fun picturesAreNotReadByTheTimerButFoldersAre() {
        val p = plan(start = 0)
        assertNull(p.due(15 * min - 1))
        val d = p.due(15 * min)!!
        assertTrue(d.trees)
        assertFalse(d.photos)
        assertEquals(listOf("every 15 minutes"), d.reasons)
    }

    @Test fun theTimerCountsFifteenMinutesFromTheEndOfTheLastRun() {
        val p = plan(start = 0)
        val d = p.due(15 * min)!!
        p.ran(16 * min, d, complete = true)
        assertNull(p.due(30 * min))
        assertNotNull(p.due(31 * min))
    }

    @Test fun aChangeThatArrivesDuringARunIsNotLost() {
        val p = plan()
        p.libraryChanged(0)
        val d = p.due(40_000)!!
        p.libraryChanged(45_000) // during the run
        p.ran(50_000, d, complete = true)
        assertNull(p.due(70_000))   // its own thirty seconds are not over
        assertNotNull(p.due(75_000))
    }

    @Test fun aRequestMadeDuringARunIsNotLost() {
        val p = plan().also { it.requested("app start") }
        val d = p.due(1_000)!!
        p.requested("second")
        p.ran(2_000, d, complete = true)
        assertEquals(listOf("second"), p.due(3_000)!!.reasons)
    }
}
