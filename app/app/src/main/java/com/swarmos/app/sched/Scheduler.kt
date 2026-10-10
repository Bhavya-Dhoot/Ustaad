package com.swarmos.app.sched

import com.swarmos.app.core.ThermalLevel

/** Ranks phones from their [Caps]. Higher is better; [Double.NEGATIVE_INFINITY] means "never use for this request". */
object Scheduler {
    const val LOW_BATTERY_PCT = 15

    /** Assumed speed of a phone that has not decoded anything yet, so it is tried rather than starved. */
    const val UNKNOWN_TOK_PER_SEC = 5.0

    /** Pushes a degraded phone below every healthy one while keeping the faster degraded phone first. */
    const val DEGRADED_OFFSET = 1_000_000.0

    /** From this thermal status (CRITICAL) a phone takes no job at all. Status 3 takes jobs only when no cooler phone is up (see SwarmLlm). */
    const val EXCLUDE_FROM_STATUS = 4

    /** Status 2 (MODERATE) is where decode speed was measured to drop: a mild penalty in the score. */
    const val MODERATE_FACTOR = 0.8

    /** Too hot by the app's one definition ([ThermalLevel.isTooHot], the HOT stamp), or under 15 % battery and not charging. */
    fun isDegraded(caps: Caps, thermal: Int = caps.thermalStatus): Boolean =
        ThermalLevel.isTooHot(thermal) || (caps.batteryPct in 0 until LOW_BATTERY_PCT && !caps.charging)

    /**
     * Measured decode tokens/s, divided by 1 + queued jobs, times [MODERATE_FACTOR] at thermal status 2; degraded phones are
     * used last. A phone with capacity 0 (no model server running) is unavailable, so is one at thermal status 4 or more, and
     * with [pinnedModel] set a phone running another model is skipped. [thermal] is the status to steer by (the scheduler
     * passes its hysteresis level); by default the one in [caps].
     */
    fun score(caps: Caps, pinnedModel: String? = null, thermal: Int = caps.thermalStatus): Double {
        if (caps.capacity <= 0) return Double.NEGATIVE_INFINITY
        if (pinnedModel != null && caps.modelName != pinnedModel) return Double.NEGATIVE_INFINITY
        if (thermal >= EXCLUDE_FROM_STATUS) return Double.NEGATIVE_INFINITY
        val tps = caps.lastDecodeTokPerSec?.takeIf { it > 0.0 } ?: UNKNOWN_TOK_PER_SEC
        val base = tps / (1 + caps.queued) * (if (thermal == ThermalLevel.DEGRADED_FROM) MODERATE_FACTOR else 1.0)
        return if (isDegraded(caps, thermal)) base - DEGRADED_OFFSET else base
    }

    fun eligible(caps: Caps, pinnedModel: String?, thermal: Int = caps.thermalStatus): Boolean =
        score(caps, pinnedModel, thermal) != Double.NEGATIVE_INFINITY
}
