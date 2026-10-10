package com.swarmos.app.shell

import com.swarmos.app.busy.BusyPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupStateTest {
    @Test
    fun continueIsEnabledOnlyWhenAllThreeRowsAreDone() {
        assertFalse(SetupState(nearby = false, notifications = false, battery = false).canContinue)
        assertFalse(SetupState(nearby = true, notifications = false, battery = false).canContinue)
        assertFalse(SetupState(nearby = true, notifications = true, battery = false).canContinue)
        assertFalse(SetupState(nearby = false, notifications = true, battery = true).canContinue)
        assertTrue(SetupState(nearby = true, notifications = true, battery = true).canContinue)
    }

    @Test
    fun aDismissedOrDeniedDialogStillLetsContinueThrough() {
        // nothing granted, but every row was put to the user: Continue is enabled
        val answered = SetupState(false, false, false, nearbyAsked = true, notificationsAsked = true, batteryAsked = true)
        assertTrue(answered.canContinue)
        assertEquals(0, answered.doneCount)
    }

    @Test
    fun continueWaitsForRowsThatWereNeitherDoneNorAsked() {
        assertFalse(SetupState(true, true, false).canContinue)
        assertFalse(SetupState(true, true, false, nearbyAsked = true, notificationsAsked = true).canContinue)
        assertTrue(SetupState(true, true, false, batteryAsked = true).canContinue)
        assertFalse(SetupState(false, false, false, nearbyAsked = true, batteryAsked = true).canContinue)
    }

    @Test
    fun doneCountCountsFinishedRows() {
        assertEquals(0, SetupState(false, false, false).doneCount)
        assertEquals(2, SetupState(true, false, true).doneCount)
        assertEquals(3, SetupState(true, true, true).doneCount)
    }

    @Test
    fun nearbyAsksForAllFiveOnAndroid13AndUp() {
        assertEquals(
            listOf(
                "android.permission.NEARBY_WIFI_DEVICES",
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.BLUETOOTH_SCAN",
                "android.permission.BLUETOOTH_ADVERTISE",
                "android.permission.BLUETOOTH_CONNECT",
            ),
            nearbyPermissions(36),
        )
    }

    @Test
    fun nearbyLeavesOutNearbyWifiDevicesBeforeAndroid13() {
        val perms = nearbyPermissions(31)
        assertFalse("android.permission.NEARBY_WIFI_DEVICES" in perms)
        assertEquals(4, perms.size)
    }

    @Test
    fun cameraAndPhotosAreNotRequestedInSetup() {
        val perms = nearbyPermissions(36)
        assertFalse("android.permission.CAMERA" in perms)
        assertFalse(perms.any { it.contains("READ_MEDIA") })
    }

    @Test
    fun keepBusyLineSaysWhetherTheModeRunsWithTheScreenOff() {
        assertEquals("Also runs with the screen off while charging.", keepBusyScreenOffLine(batteryExempt = true))
        assertEquals("Runs only while Ustaad is on screen.", keepBusyScreenOffLine(batteryExempt = false))
    }

    @Test
    fun hammerButtonTextShowsWhetherHammerIsOn() {
        assertEquals("Hammer: maximum usage", hammerButtonText(on = false))
        assertEquals("Hammer is on. Tap to stop.", hammerButtonText(on = true))
    }

    @Test
    fun hammerHelperLineIsPlainAndWarnsAboutHeat() {
        assertEquals(
            "Keeps this phone generating all the time it can, even when it is warm. Answers get slower and the phone gets hot. " +
                "It pauses while the phone is too hot and carries on when it has cooled. " +
                "After you stop it the phone rests for 90 seconds, then normal Keep busy continues. " +
                "Hammer is off again whenever Ustaad restarts.",
            HAMMER_HELPER_LINE,
        )
        assertTrue("the line names the real rest", HAMMER_HELPER_LINE.contains("${BusyPolicy.HAMMER_COOLDOWN_MS / 1000} seconds"))
        assertFalse("no dashes in user text", listOf(hammerButtonText(true), hammerButtonText(false), HAMMER_HELPER_LINE).any { '\u2014' in it || '\u2013' in it })
    }

    @Test
    fun backgroundStartRowIsForVivoAndIqooOnly() {
        assertTrue(isVivoFamily("vivo", "vivo"))
        assertTrue(isVivoFamily("vivo", "iQOO"))
        assertTrue(isVivoFamily("Google", "IQOO"))
        assertFalse(isVivoFamily("samsung", "samsung"))
        assertFalse(isVivoFamily("Xiaomi", "Redmi"))
    }
}
