package com.swarmos.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalGateTest {
    private fun allowed(status: Int, work: WorkKind) = ThermalGate.decide(status, work).allowed

    @Test fun belowSevereEverythingIsAllowed() {
        for (status in 0..2) for (work in WorkKind.entries) {
            val d = ThermalGate.decide(status, work)
            assertTrue("status $status $work", d.allowed)
            assertNull(d.reason)
        }
    }

    @Test fun atSevereTheExistingRefusalsStayAndNothingElseIsRefused() {
        assertFalse(allowed(3, WorkKind.BENCHMARK))
        assertFalse(allowed(3, WorkKind.EVALUATION))
        assertTrue(allowed(3, WorkKind.INDEX_RUN))
        assertTrue(allowed(3, WorkKind.MODEL_LOAD))
        assertTrue(allowed(3, WorkKind.REMOTE_JOB))
    }

    @Test fun fromCriticalOnNoNewWorkIsAccepted() {
        for (status in 4..6) for (work in WorkKind.entries) {
            val d = ThermalGate.decide(status, work)
            assertFalse("status $status $work", d.allowed)
            assertTrue(d.reason!!.contains(HealthMonitor.thermalName(status).lowercase()))
            assertTrue(d.reason!!.contains(work.noun))
        }
    }

    @Test fun theBenchmarkAndEvaluationFollowTheSharedTooHotThreshold() {
        for (status in -1..7) {
            assertEquals("benchmark at $status", !ThermalLevel.isTooHot(status), allowed(status, WorkKind.BENCHMARK))
            assertEquals("evaluation at $status", !ThermalLevel.isTooHot(status), allowed(status, WorkKind.EVALUATION))
        }
    }

    @Test fun theSheddingThresholdIsStatusFourAndAboveTheTooHotThreshold() {
        assertEquals(4, ThermalLevel.SHED_FROM)
        assertTrue(ThermalLevel.SHED_FROM > ThermalLevel.TOO_HOT_FROM)
        assertFalse(ThermalLevel.isShedding(3))
        assertTrue(ThermalLevel.isShedding(4))
    }

    @Test fun anUnknownLowStatusIsAllowed() {
        assertTrue(allowed(-1, WorkKind.REMOTE_JOB))
    }
}
