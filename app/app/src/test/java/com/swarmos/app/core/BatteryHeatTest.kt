package com.swarmos.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryHeatTest {
    /** Battery temperature in degrees as the tests think of it, to tenths as the battery broadcast carries it. */
    private fun status(celsius: Double) = BatteryHeat.statusFor(Math.round(celsius * 10).toInt())

    @Test fun theThresholdsSitWhereTheNotesSayAndNothingAboveFourIsDerived() {
        assertEquals(0, status(20.0))
        assertEquals(0, status(37.4))
        assertEquals(1, status(37.5))
        assertEquals(1, status(38.9))
        assertEquals(2, status(39.0))
        assertEquals(2, status(41.4))
        assertEquals(3, status(41.5))
        assertEquals(3, status(44.9))
        assertEquals(4, status(45.0))
        assertEquals("EMERGENCY and SHUTDOWN are never derived", 4, status(80.0))
    }

    @Test fun theStatusNeverFallsAsTheBatteryGetsHotter() {
        var last = 0
        for (tenths in 1..900) {
            val s = BatteryHeat.statusFor(tenths)
            assertTrue("at $tenths", s >= last)
            last = s
        }
    }

    @Test fun theMeasuredExtremesOfTheFittedPhonesLandWhereTheSystemPutThem() {
        // docs/benchmarks/raw: status 3 was seen between 41.6 and 42.8 C, status 0 between 32.2 and 37.8 C, status 2 around a median of 39.3 C
        assertEquals(3, status(41.6))
        assertEquals(3, status(42.8))
        assertEquals(0, status(32.2))
        assertEquals(0, status(34.9))
        assertEquals(2, status(39.3))
        // the overlap the notes admit: the warmest status-0 samples (37.8) read as LIGHT, which changes no rule
        assertEquals(1, status(37.8))
        // docs/benchmarks/2026-10-10-inapp-llm.md line 27: status 4 at 45.5 and 47.0 C, status 2 at 40.9 C
        assertEquals(4, status(45.5))
        assertEquals(4, status(47.0))
        assertEquals(2, status(40.9))
    }

    @Test fun everyRuleThatReadsTheStatusSeesTheSameScaleAsThePowerManager() {
        assertEquals("the load gate sheds from 4", ThermalLevel.SHED_FROM, status(45.0))
        assertEquals("Keep busy pauses from 3", ThermalLevel.TOO_HOT_FROM, status(41.5))
        assertEquals("Keep busy halves from 2", ThermalLevel.DEGRADED_FROM, status(39.0))
        assertFalse("a phone at 36 C is not degraded", ThermalLevel.isDegraded(status(36.0)))
        assertTrue(ThermalLevel.isShedding(status(46.0)))
    }

    @Test fun anUnreportedTemperatureIsNeverCool() {
        assertEquals(BatteryHeat.UNKNOWN_STATUS, BatteryHeat.statusFor(null))
        assertNotEquals("missing data must not read as cool", 0, BatteryHeat.UNKNOWN_STATUS)
        assertEquals(2, BatteryHeat.UNKNOWN_STATUS)
        assertTrue("a phone blind to its heat runs at half duty", ThermalLevel.isDegraded(BatteryHeat.UNKNOWN_STATUS))
        assertFalse("but it is not paused for heat it cannot show", ThermalLevel.isTooHot(BatteryHeat.UNKNOWN_STATUS))
    }

    @Test fun aTemperatureOfZeroOrLessCountsAsNotReported() {
        assertNull(BatteryHeat.tenthsOrNull(null))
        assertNull(BatteryHeat.tenthsOrNull(0))
        assertNull(BatteryHeat.tenthsOrNull(-30))
        assertEquals(375, BatteryHeat.tenthsOrNull(375))
        assertEquals(BatteryHeat.UNKNOWN_STATUS, BatteryHeat.statusFor(BatteryHeat.tenthsOrNull(0)))
    }

    @Test fun theHealthReportSaysWhetherTheStatusWasDerivedAndAnOlderPeerReadsAsNotDerived() {
        val derived = NodeHealth("n", 2, 80, false, 4096, null, null, null, thermalDerived = true)
        val sent = SwarmJson.encodeToString(NodeHealth.serializer(), derived)
        assertTrue(sent, "\"thermalDerived\":true" in sent)
        assertTrue(SwarmJson.decodeFromString(NodeHealth.serializer(), sent).thermalDerived)
        // a health report from a build before the field existed (and one from a phone with the system status) is not derived
        val old = """{"nodeId":"n","thermalStatus":2,"batteryPct":80,"charging":false,"ramAvailMb":4096,"lastDecodeTokPerSec":null,"linkStandard":null,"linkSpeedMbps":null}"""
        assertFalse(SwarmJson.decodeFromString(NodeHealth.serializer(), old).thermalDerived)
        assertFalse(NodeHealth("n", 2, 80, false, 4096, null, null, null).thermalDerived)
    }
}
