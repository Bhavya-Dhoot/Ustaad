package com.swarmos.app.hands.android

import org.junit.Assert.assertEquals
import org.junit.Test

class HandsSettingsTest {
    @Test
    fun theTestBrainPortCountsOnlyInADebugBuild() {
        assertEquals(8099, HandsSettings.effectivePort(8099, debug = true))
        assertEquals("a release or demo build ignores the stored port", 0, HandsSettings.effectivePort(8099, debug = false))
    }

    @Test
    fun cautionIsCarefulUnlessTheSwitchIsOn() {
        assertEquals(com.swarmos.app.hands.Caution.CAREFUL, HandsSettings.cautionOf(false))
        assertEquals(com.swarmos.app.hands.Caution.FEWER_QUESTIONS, HandsSettings.cautionOf(true))
        assertEquals("a phone that has not loaded its settings is careful", com.swarmos.app.hands.Caution.CAREFUL, HandsSettings.caution())
    }

    @Test
    fun thePhonesAssistantMayNeitherStartTasksNorSearchDataUntilTheOwnerSaysSo() {
        assertEquals("a phone that has not loaded its settings", false, HandsSettings.assistantMayStart.value)
        assertEquals("a phone that has not loaded its settings", false, HandsSettings.assistantMaySearchData.value)
    }

    @Test
    fun onlyARealPortNumberCounts() {
        for (bad in listOf(0, -1, 65536, Int.MAX_VALUE, Int.MIN_VALUE)) assertEquals(bad.toString(), 0, HandsSettings.effectivePort(bad, debug = true))
        assertEquals(1, HandsSettings.effectivePort(1, debug = true))
        assertEquals(65535, HandsSettings.effectivePort(65535, debug = true))
    }
}
