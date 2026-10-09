package com.swarmos.app.shell

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
    fun backgroundStartRowIsForVivoAndIqooOnly() {
        assertTrue(isVivoFamily("vivo", "vivo"))
        assertTrue(isVivoFamily("vivo", "iQOO"))
        assertTrue(isVivoFamily("Google", "IQOO"))
        assertFalse(isVivoFamily("samsung", "samsung"))
        assertFalse(isVivoFamily("Xiaomi", "Redmi"))
    }
}
