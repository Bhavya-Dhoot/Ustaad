package com.swarmos.app.busy

import com.swarmos.app.core.ThermalLevel

/**
 * What the "Keep busy" loop is doing. [id] is the word in the `/v1/sched/busy` answer, [text] is for Settings, [stripWord] is
 * the short plain word on the status strip (null where it would never be seen or says nothing worth showing).
 */
enum class BusyState(val id: String, val text: String, val stripWord: String?) {
    Off("off", "off", null),
    WaitingForIdle("waiting_for_idle", "waiting for the phone to be idle", null),
    HammerCooldown("hammer_cooldown", "resting for a moment after Hammer", "resting"),
    Running("running", "running", "busy"),
    HalfDuty("half_duty", "running at half duty (warm phone)", "busy half"),
    PausedHot("paused_hot", "paused, the phone is hot", "paused hot"),
    PausedBattery("paused_battery", "paused, battery is low", "paused battery"),
    PausedBackground("paused_background", "paused, Ustaad is not in front", null),
    PausedModelRestarted("paused_model_restarted", "paused, the chat model was just (re)started", "paused model"),
    NoModel("no_model", "no chat model is loaded", null),
}

/** Everything the decision needs, read just before a filler job. */
data class BusyConditions(
    val switchOn: Boolean,
    /** A real chat model is READY on this phone. */
    val modelReady: Boolean,
    /** How long the chat server has been READY since its last (re)start; ignored while [modelReady] is false. */
    val modelReadyForMs: Long,
    /** How long it must have been READY before filler may run ([BusyPolicy.SETTLE_MS], or [BusyPolicy.SETTLE_AFTER_DEATH_MS] after it died under filler). */
    val modelSettleMs: Long,
    /** Ustaad's screen is in front (a started MainActivity). */
    val appInFront: Boolean,
    /** The system lets Ustaad ignore battery optimisations (PowerManager.isIgnoringBatteryOptimizations). */
    val batteryOptimizationIgnored: Boolean,
    /** Milliseconds since this phone last had real model work (0 while it has some). */
    val idleMs: Long,
    val thermalStatus: Int,
    /** -1 when unknown. */
    val batteryPct: Int,
    val charging: Boolean,
    /** The owner's Hammer switch on this phone: no half duty when warm. Every other rule still applies. */
    val hammer: Boolean = false,
    /** Milliseconds since Hammer was last switched off; [Long.MAX_VALUE] when it never was (or the switch-off ended with Keep busy). */
    val sinceHammerOffMs: Long = Long.MAX_VALUE,
)

/** The rules of the mode, with no clock and no phone in them. */
object BusyPolicy {
    /** Real model work must have been absent this long before filler starts. */
    const val IDLE_BEFORE_MS = 5_000L

    /** Not charging and under this battery percentage: pause. */
    const val BATTERY_FLOOR_PCT = 30

    /** How often a paused loop looks at the heat and the battery again. */
    const val PAUSE_RECHECK_MS = 10_000L

    /** Filler replies are cut at this many tokens, so one job is a few seconds. */
    const val MAX_TOKENS = 48

    /** Filler replies are cut at 48 tokens and take a few seconds; a job that has not ended after this long is a stuck server. */
    const val FILLER_TIMEOUT_MS = 60_000L

    /** No filler within this long of a chat server start or restart. */
    const val SETTLE_MS = 120_000L

    /** After the chat server died during a filler job: no filler until it has been READY this long. */
    const val SETTLE_AFTER_DEATH_MS = 300_000L

    /** After Hammer is switched off: no filler for this long, then the normal rule applies again. Real work is never held by it. */
    const val HAMMER_COOLDOWN_MS = 90_000L

    /**
     * The state the loop is in, in this order: switch off, no real chat model, the chat server not READY for [BusyConditions.modelSettleMs]
     * yet, Ustaad in the background (allowed there only while charging with battery optimisations ignored: the vendor OS
     * froze and killed Ustaad and its model servers minutes after screen-off), real work seen in the last [IDLE_BEFORE_MS],
     * Hammer switched off less than [HAMMER_COOLDOWN_MS] ago, hot (thermal status 3 or more, the shared definition of too
     * hot; it carries on by itself when the status is under 3 again), battery under the floor while not charging, then half
     * duty at thermal status 2, else full duty. An unknown battery (-1) does not pause. With [BusyConditions.hammer] on only the
     * half-duty step is skipped (status 2 runs at full duty); every rule above it, the pauses for real work, heat and battery
     * included, applies as before, and the cool-down does not apply while Hammer is on.
     */
    fun stateFor(c: BusyConditions): BusyState = when {
        !c.switchOn -> BusyState.Off
        !c.modelReady -> BusyState.NoModel
        c.modelReadyForMs < c.modelSettleMs -> BusyState.PausedModelRestarted
        !c.appInFront && !(c.charging && c.batteryOptimizationIgnored) -> BusyState.PausedBackground
        c.idleMs < IDLE_BEFORE_MS -> BusyState.WaitingForIdle
        !c.hammer && c.sinceHammerOffMs < HAMMER_COOLDOWN_MS -> BusyState.HammerCooldown
        ThermalLevel.isTooHot(c.thermalStatus) -> BusyState.PausedHot
        !c.charging && c.batteryPct in 0 until BATTERY_FLOOR_PCT -> BusyState.PausedBattery
        !c.hammer && ThermalLevel.isDegraded(c.thermalStatus) -> BusyState.HalfDuty
        else -> BusyState.Running
    }

    /** How long to rest after a job that took [jobMs]: as long as the job at half duty, none at full duty. */
    fun restAfterMs(state: BusyState, jobMs: Long): Long = if (state == BusyState.HalfDuty) jobMs.coerceAtLeast(0L) else 0L
}
