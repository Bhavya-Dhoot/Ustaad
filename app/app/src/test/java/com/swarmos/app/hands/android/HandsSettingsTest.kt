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
    fun onlyARealPortNumberCounts() {
        for (bad in listOf(0, -1, 65536, Int.MAX_VALUE, Int.MIN_VALUE)) assertEquals(bad.toString(), 0, HandsSettings.effectivePort(bad, debug = true))
        assertEquals(1, HandsSettings.effectivePort(1, debug = true))
        assertEquals(65535, HandsSettings.effectivePort(65535, debug = true))
    }
}
