package com.swarmos.app.busy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BusyPolicyTest {
    /** Conditions in which filler runs at full duty; each test changes one thing. */
    private val ok = BusyConditions(
        switchOn = true, modelReady = true, modelReadyForMs = 10 * 60_000L, modelSettleMs = BusyPolicy.SETTLE_MS,
        appInFront = true, batteryOptimizationIgnored = false, idleMs = 60_000L,
        thermalStatus = 0, batteryPct = 80, charging = false,
    )

    private fun state(c: BusyConditions) = BusyPolicy.stateFor(c)

    @Test fun fullDutyWhenEverythingAllows() {
        assertEquals(BusyState.Running, state(ok))
    }

    @Test fun offWhenTheSwitchIsOffWhateverElseIsTrue() {
        assertEquals(BusyState.Off, state(ok.copy(switchOn = false)))
        assertEquals(BusyState.Off, state(ok.copy(switchOn = false, modelReady = false, thermalStatus = 5)))
    }

    @Test fun noModelWhenNoRealChatModelIsReady() {
        assertEquals(BusyState.NoModel, state(ok.copy(modelReady = false)))
        assertEquals("no model beats the settle time", BusyState.NoModel, state(ok.copy(modelReady = false, modelReadyForMs = 0)))
    }

    @Test fun pausedWhileTheChatServerWasJustStartedOrRestarted() {
        assertEquals(BusyState.PausedModelRestarted, state(ok.copy(modelReadyForMs = 119_999L)))
        assertEquals("120 s is enough", BusyState.Running, state(ok.copy(modelReadyForMs = 120_000L)))
    }

    @Test fun afterTheServerDiedUnderFillerItNeedsFiveMinutesReady() {
        val strict = ok.copy(modelSettleMs = BusyPolicy.SETTLE_AFTER_DEATH_MS)
        assertEquals(BusyState.PausedModelRestarted, state(strict.copy(modelReadyForMs = 299_999L)))
        assertEquals(BusyState.Running, state(strict.copy(modelReadyForMs = 300_000L)))
    }

    @Test fun inTheBackgroundOnlyAChargingPhoneThatIgnoresBatteryOptimisationsRuns() {
        val away = ok.copy(appInFront = false)
        assertEquals("on battery, in the background", BusyState.PausedBackground, state(away))
        assertEquals("charging but the system may still freeze Ustaad", BusyState.PausedBackground, state(away.copy(charging = true)))
        assertEquals("ignoring optimisations but on battery", BusyState.PausedBackground, state(away.copy(batteryOptimizationIgnored = true)))
        assertEquals("charging and unrestricted", BusyState.Running, state(away.copy(charging = true, batteryOptimizationIgnored = true)))
        assertEquals("in front, nothing else needed", BusyState.Running, state(ok.copy(appInFront = true, charging = false, batteryOptimizationIgnored = false)))
    }

    @Test fun waitsUntilTheFiveSecondsOfIdleHavePassed() {
        assertEquals(BusyState.WaitingForIdle, state(ok.copy(idleMs = 0)))
        assertEquals(BusyState.WaitingForIdle, state(ok.copy(idleMs = 4_999)))
        assertEquals(BusyState.Running, state(ok.copy(idleMs = 5_000)))
    }

    @Test fun thermalStatusZeroAndOneRunAtFullDuty() {
        assertEquals(BusyState.Running, state(ok.copy(thermalStatus = 0)))
        assertEquals(BusyState.Running, state(ok.copy(thermalStatus = 1)))
    }

    @Test fun thermalStatusTwoRunsAtHalfDuty() {
        assertEquals(BusyState.HalfDuty, state(ok.copy(thermalStatus = 2)))
    }

    @Test fun hammerKeepsFullDutyAtThermalStatusTwoWithNoRest() {
        val hammer = ok.copy(thermalStatus = 2, hammer = true)
        assertEquals(BusyState.Running, state(hammer))
        assertEquals(0L, BusyPolicy.restAfterMs(state(hammer), 3_200L))
        assertEquals("hammer off is the half duty rule as before", BusyState.HalfDuty, state(hammer.copy(hammer = false)))
        assertEquals("hammer on changes nothing when cool", BusyState.Running, state(ok.copy(hammer = true)))
    }

    @Test fun hammerStillPausesForHeatFromStatusThree() {
        for (s in 3..6) assertEquals("status $s", BusyState.PausedHot, state(ok.copy(thermalStatus = s, hammer = true)))
    }

    @Test fun hammerDoesNotSkipTheBatteryFloorIdleWaitBackgroundOrModelSettle() {
        val warm = ok.copy(thermalStatus = 2, hammer = true)
        assertEquals(BusyState.PausedBattery, state(warm.copy(batteryPct = 29)))
        assertEquals("charging passes the floor", BusyState.Running, state(warm.copy(batteryPct = 5, charging = true)))
        assertEquals(BusyState.WaitingForIdle, state(warm.copy(idleMs = 4_999)))
        assertEquals(BusyState.PausedBackground, state(warm.copy(appInFront = false)))
        assertEquals(BusyState.PausedModelRestarted, state(warm.copy(modelReadyForMs = 119_999L)))
        assertEquals(BusyState.NoModel, state(warm.copy(modelReady = false)))
        assertEquals(BusyState.Off, state(warm.copy(switchOn = false)))
        assertEquals("heat beats the battery as before", BusyState.PausedHot, state(warm.copy(thermalStatus = 3, batteryPct = 10)))
    }

    @Test fun hammerOnPausesAtStatusThreeAndCarriesOnByItselfOnceCooled() {
        val hot = ok.copy(hammer = true, thermalStatus = 3)
        assertEquals(BusyState.PausedHot, state(hot))
        assertEquals("the same conditions one step cooler run at full duty", BusyState.Running, state(hot.copy(thermalStatus = 2)))
    }

    @Test fun afterHammerIsSwitchedOffNoFillerForNinetySecondsThenTheNormalRule() {
        assertEquals(90_000L, BusyPolicy.HAMMER_COOLDOWN_MS)
        assertEquals(BusyState.HammerCooldown, state(ok.copy(sinceHammerOffMs = 0)))
        assertEquals(BusyState.HammerCooldown, state(ok.copy(sinceHammerOffMs = 89_999)))
        assertEquals("90 s is enough", BusyState.Running, state(ok.copy(sinceHammerOffMs = 90_000)))
        assertEquals("then the normal rule: half duty when warm", BusyState.HalfDuty, state(ok.copy(sinceHammerOffMs = 90_000, thermalStatus = 2)))
    }

    @Test fun noCooldownWhenHammerWasNeverSwitchedOff() {
        assertEquals(Long.MAX_VALUE, ok.sinceHammerOffMs)
        assertEquals(BusyState.Running, state(ok))
        assertEquals(BusyState.HalfDuty, state(ok.copy(thermalStatus = 2)))
    }

    @Test fun switchingHammerOnAgainDuringTheCooldownRunsAtOnce() {
        assertEquals(BusyState.Running, state(ok.copy(hammer = true, sinceHammerOffMs = 1_000)))
        assertEquals(BusyState.Running, state(ok.copy(hammer = true, sinceHammerOffMs = 1_000, thermalStatus = 2)))
    }

    @Test fun theCooldownComesAfterIdleWaitingAndBeforeTheHeatAndBatteryPauses() {
        val resting = ok.copy(sinceHammerOffMs = 10_000)
        assertEquals(BusyState.WaitingForIdle, state(resting.copy(idleMs = 100)))
        assertEquals(BusyState.HammerCooldown, state(resting.copy(thermalStatus = 3)))
        assertEquals(BusyState.HammerCooldown, state(resting.copy(batteryPct = 10)))
        assertEquals(BusyState.HammerCooldown, state(resting.copy(thermalStatus = 2)))
        assertEquals(BusyState.PausedBackground, state(resting.copy(appInFront = false)))
        assertEquals(BusyState.PausedModelRestarted, state(resting.copy(modelReadyForMs = 0)))
        assertEquals(BusyState.NoModel, state(resting.copy(modelReady = false)))
        assertEquals(BusyState.Off, state(resting.copy(switchOn = false)))
    }

    @Test fun thermalStatusThreeAndAbovePauseForHeat() {
        for (s in 3..6) assertEquals("status $s", BusyState.PausedHot, state(ok.copy(thermalStatus = s)))
    }

    @Test fun batteryUnderThirtyPercentPausesOnlyWhenNotCharging() {
        assertEquals(BusyState.PausedBattery, state(ok.copy(batteryPct = 29)))
        assertEquals(BusyState.PausedBattery, state(ok.copy(batteryPct = 0)))
        assertEquals("30 is not under 30", BusyState.Running, state(ok.copy(batteryPct = 30)))
        assertEquals("charging", BusyState.Running, state(ok.copy(batteryPct = 5, charging = true)))
        assertEquals("unknown battery does not pause", BusyState.Running, state(ok.copy(batteryPct = -1)))
    }

    @Test fun freeMemoryUnderTheFloorPauses() {
        assertEquals(BusyState.PausedMemory, state(ok.copy(ramAvailMb = BusyPolicy.MEM_FLOOR_MB - 1)))
        assertEquals(BusyState.PausedMemory, state(ok.copy(ramAvailMb = 1)))
        assertEquals("the floor itself is enough", BusyState.Running, state(ok.copy(ramAvailMb = BusyPolicy.MEM_FLOOR_MB)))
        assertEquals("plenty", BusyState.Running, state(ok.copy(ramAvailMb = 4096)))
        assertEquals("charging does not make up for memory", BusyState.PausedMemory, state(ok.copy(ramAvailMb = 500, charging = true)))
    }

    @Test fun unknownMemoryDoesNotPause() {
        assertEquals(BusyState.Running, state(ok))   // the default is -1
        assertEquals(BusyState.Running, state(ok.copy(ramAvailMb = -1)))
        assertEquals("0 is what the health sample gives before its first reading", BusyState.Running, state(ok.copy(ramAvailMb = 0)))
    }

    @Test fun hammerDoesNotOverrideTheMemoryPause() {
        assertEquals(BusyState.PausedMemory, state(ok.copy(hammer = true, ramAvailMb = 500)))
        assertEquals(BusyState.PausedMemory, state(ok.copy(hammer = true, thermalStatus = 2, ramAvailMb = 500)))
        assertEquals("known and enough: Hammer runs a warm phone at full duty", BusyState.Running, state(ok.copy(hammer = true, thermalStatus = 2, ramAvailMb = 4096)))
    }

    @Test fun memoryComesAfterHeatAndBatteryAndBeforeHalfDuty() {
        assertEquals(BusyState.PausedHot, state(ok.copy(thermalStatus = 3, ramAvailMb = 500)))
        assertEquals(BusyState.PausedBattery, state(ok.copy(batteryPct = 10, ramAvailMb = 500)))
        assertEquals(BusyState.PausedMemory, state(ok.copy(thermalStatus = 2, ramAvailMb = 500)))
        assertEquals(BusyState.WaitingForIdle, state(ok.copy(idleMs = 100, ramAvailMb = 500)))
    }

    @Test fun heatBeatsBatteryAndBatteryBeatsHalfDuty() {
        assertEquals(BusyState.PausedHot, state(ok.copy(thermalStatus = 3, batteryPct = 10)))
        assertEquals(BusyState.PausedBattery, state(ok.copy(thermalStatus = 2, batteryPct = 10)))
    }

    @Test fun waitingForIdleComesBeforeTheHeatAndBatteryPauses() {
        assertEquals(BusyState.WaitingForIdle, state(ok.copy(idleMs = 100, thermalStatus = 4, batteryPct = 5)))
    }

    @Test fun backgroundAndRestartPausesComeBeforeIdleWaiting() {
        assertEquals(BusyState.PausedBackground, state(ok.copy(appInFront = false, idleMs = 0)))
        assertEquals(BusyState.PausedModelRestarted, state(ok.copy(modelReadyForMs = 0, appInFront = false)))
    }

    @Test fun restIsAsLongAsTheLastJobAtHalfDutyAndNoneAtFullDuty() {
        assertEquals(3_200L, BusyPolicy.restAfterMs(BusyState.HalfDuty, 3_200L))
        assertEquals(0L, BusyPolicy.restAfterMs(BusyState.Running, 3_200L))
        assertEquals(0L, BusyPolicy.restAfterMs(BusyState.HalfDuty, -5L))
    }

    @Test fun onlyRunningAndPausedStatesShowAWordOnTheStrip() {
        assertEquals("busy", BusyState.Running.stripWord)
        assertEquals("busy half", BusyState.HalfDuty.stripWord)
        assertEquals("paused hot", BusyState.PausedHot.stripWord)
        assertEquals("paused battery", BusyState.PausedBattery.stripWord)
        assertEquals("paused memory", BusyState.PausedMemory.stripWord)
        assertEquals("paused model", BusyState.PausedModelRestarted.stripWord)
        assertEquals("resting", BusyState.HammerCooldown.stripWord)
        assertNull(BusyState.Off.stripWord)
        assertNull(BusyState.WaitingForIdle.stripWord)
        assertNull(BusyState.NoModel.stripWord)
    }

    @Test fun everyStateHasADistinctIdForTheRoute() {
        val ids = BusyState.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}
