package com.swarmos.app.core

import com.swarmos.app.core.HealthMonitor.LinkReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthMonitorTest {
    @Test
    fun thermalStatusIsOneOfFourWords() {
        assertEquals("Cool", HealthMonitor.thermalWord(0))
        assertEquals("Warm", HealthMonitor.thermalWord(1))
        assertEquals("Hot", HealthMonitor.thermalWord(2))
        assertEquals("Severe", HealthMonitor.thermalWord(3))
        assertEquals("Severe", HealthMonitor.thermalWord(6))
    }

    @Test
    fun hotStampFromThermalStatusThreeUp() {
        assertFalse(HealthMonitor.isHot(0))
        assertFalse(HealthMonitor.isHot(2))
        assertTrue(HealthMonitor.isHot(3))
        assertTrue(HealthMonitor.isHot(5))
    }

    @Test
    fun linkLabelShowsStandardAndSpeed() {
        assertEquals("11ac 433 Mbps", HealthMonitor.linkLabel("11ac", 433))
        assertEquals("wifi 72 Mbps", HealthMonitor.linkLabel(null, 72))
    }

    @Test
    fun noLinkOnlyWhenThereIsNone() {
        assertEquals("no link", HealthMonitor.linkLabel(null, null))
        assertEquals("wifi", HealthMonitor.linkLabel("wifi", null)) // connected, speed not known yet
    }

    @Test
    fun idleSecondInterfaceDoesNotHideTheRealLink() {
        val idle = LinkReading(standard = 0, speedMbps = -1, freqMhz = -1)
        val real = LinkReading(standard = 5, speedMbps = 433, freqMhz = 5765)
        assertEquals(real, HealthMonitor.pickLink(listOf(idle, real)))
        assertNull(HealthMonitor.pickLink(listOf(idle)))
        assertNull(HealthMonitor.pickLink(emptyList()))
    }

    @Test
    fun bandNameFallsBackToFrequency() {
        assertEquals("5 GHz", HealthMonitor.bandName(5765))
        assertEquals("2.4 GHz", HealthMonitor.bandName(2437))
        assertEquals("6 GHz", HealthMonitor.bandName(6415))
        assertNull(HealthMonitor.bandName(-1))
    }

    @Test
    fun standardNamesCoverTheWifiGenerations() {
        assertEquals("11ac", HealthMonitor.standardName(5))
        assertEquals("11ax", HealthMonitor.standardName(6))
        assertNull(HealthMonitor.standardName(0))
    }
}
