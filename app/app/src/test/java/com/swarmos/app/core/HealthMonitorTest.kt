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
    fun thermalLevelHasTheWordAndTheTwoThresholds() {
        assertEquals(listOf("Cool", "Warm", "Hot", "Severe", "Severe", "Severe", "Severe"), (0..6).map { ThermalLevel.of(it).word })
        assertEquals(listOf(false, false, true, true, true, true, true), (0..6).map { ThermalLevel.of(it).degraded })
        assertEquals(listOf(false, false, false, true, true, true, true), (0..6).map { ThermalLevel.of(it).tooHot })
        assertEquals(ThermalLevel.Cool, ThermalLevel.of(-1))
    }

    @Test
    fun rawStatusHelpersAgreeWithTheLevel() {
        for (s in -1..7) {
            assertEquals(ThermalLevel.of(s).degraded, ThermalLevel.isDegraded(s))
            assertEquals(ThermalLevel.of(s).tooHot, ThermalLevel.isTooHot(s))
        }
        assertEquals(2, ThermalLevel.DEGRADED_FROM) // the scheduler's old HOT_THERMAL_STATUS
        assertEquals(3, ThermalLevel.TOO_HOT_FROM)  // the benchmark guard's old BLOCK_FROM_THERMAL
    }

    @Test
    fun storedPowerManagerNamesBecomeTheSharedWords() {
        assertEquals("Cool", ThermalLevel.wordForName("NONE"))
        assertEquals("Warm", ThermalLevel.wordForName("light"))
        assertEquals("Hot", ThermalLevel.wordForName("MODERATE"))
        assertEquals("Severe", ThermalLevel.wordForName("CRITICAL"))
        assertEquals("?", ThermalLevel.wordForName("?"))
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

    @Test
    fun aReadingWithoutAWifiStandardIsNamedByItsBandAsOnAndroid8To10() {
        // before API 30 WifiInfo has no standard: the reading carries 0 and the strip shows the band with the speed
        val r = LinkReading(standard = 0, speedMbps = 433, freqMhz = 5765)
        val label = HealthMonitor.linkLabel(HealthMonitor.standardName(r.standard) ?: HealthMonitor.bandName(r.freqMhz) ?: "wifi", r.speedMbps)
        assertEquals("5 GHz 433 Mbps", label)
        assertEquals(r, HealthMonitor.pickLink(listOf(r)))
    }
}
