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

    private val location = "android.permission.ACCESS_FINE_LOCATION"
    private val bluetooth = listOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_ADVERTISE", "android.permission.BLUETOOTH_CONNECT")

    /** The whole "which permissions on which version" choice for one Android version, as the three lists the screens ask for. */
    private fun asked(sdk: Int) = Triple(nearbyPermissions(sdk), notificationPermission(sdk), photoLibraryPermission(sdk))

    @Test
    fun android8And9AskForLocationOnlyAndReadPhotosWithExternalStorage() {
        for (sdk in 26..28) {
            assertEquals("sdk $sdk", Triple(listOf(location), null, "android.permission.READ_EXTERNAL_STORAGE"), asked(sdk))
        }
    }

    @Test
    fun android10AsksForTheSameAsAndroid9() {
        assertEquals(Triple(listOf(location), null, "android.permission.READ_EXTERNAL_STORAGE"), asked(29))
    }

    @Test
    fun android11AsksForTheSameAsAndroid10() {
        // API 30: the Bluetooth permissions of Android 12 do not exist yet, so a request for them could never be granted
        assertEquals(Triple(listOf(location), null, "android.permission.READ_EXTERNAL_STORAGE"), asked(30))
    }

    @Test
    fun android12AddsTheBluetoothTrioButNotNearbyWifiOrNotifications() {
        for (sdk in 31..32) {
            assertEquals("sdk $sdk", Triple(listOf(location) + bluetooth, null, "android.permission.READ_EXTERNAL_STORAGE"), asked(sdk))
        }
    }

    @Test
    fun android13AndUpAskForNearbyWifiNotificationsAndMediaImages() {
        for (sdk in listOf(33, 34, 35, 36)) {
            assertEquals(
                "sdk $sdk",
                Triple(
                    listOf("android.permission.NEARBY_WIFI_DEVICES", location) + bluetooth,
                    "android.permission.POST_NOTIFICATIONS",
                    "android.permission.READ_MEDIA_IMAGES",
                ),
                asked(sdk),
            )
        }
    }

    @Test
    fun noVersionAsksForAPermissionThatDoesNotExistThere() {
        // a permission unknown to the system can never be granted, which would leave the setup row open forever
        val since = mapOf(
            "android.permission.NEARBY_WIFI_DEVICES" to 33, "android.permission.POST_NOTIFICATIONS" to 33,
            "android.permission.READ_MEDIA_IMAGES" to 33, "android.permission.BLUETOOTH_SCAN" to 31,
            "android.permission.BLUETOOTH_ADVERTISE" to 31, "android.permission.BLUETOOTH_CONNECT" to 31,
        )
        for (sdk in 26..36) {
            val (nearby, notifications, photos) = asked(sdk)
            for (p in nearby + listOfNotNull(notifications, photos)) assertTrue("$p on sdk $sdk", sdk >= (since[p] ?: 0))
        }
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
