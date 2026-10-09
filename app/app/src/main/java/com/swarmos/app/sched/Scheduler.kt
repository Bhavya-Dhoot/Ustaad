package com.swarmos.app.sched

/** Ranks phones from their [Caps]. Higher is better; [Double.NEGATIVE_INFINITY] means "never use for this request". */
object Scheduler {
    const val HOT_THERMAL_STATUS = 2
    const val LOW_BATTERY_PCT = 15

    /** Assumed speed of a phone that has not decoded anything yet, so it is tried rather than starved. */
    const val UNKNOWN_TOK_PER_SEC = 5.0

    /** Pushes a degraded phone below every healthy one while keeping the faster degraded phone first. */
    const val DEGRADED_OFFSET = 1_000_000.0

    /** Thermal status 2 (moderate) or worse, or under 15 % battery and not charging. */
    fun isDegraded(caps: Caps): Boolean =
        caps.thermalStatus >= HOT_THERMAL_STATUS || (caps.batteryPct in 0 until LOW_BATTERY_PCT && !caps.charging)

    /**
     * Measured decode tokens/s, divided by 1 + queued jobs; degraded phones are used last.
     * With [pinnedModel] set, a phone running another model is skipped.
     */
    fun score(caps: Caps, pinnedModel: String? = null): Double {
        if (pinnedModel != null && caps.modelName != pinnedModel) return Double.NEGATIVE_INFINITY
        val tps = caps.lastDecodeTokPerSec?.takeIf { it > 0.0 } ?: UNKNOWN_TOK_PER_SEC
        val base = tps / (1 + caps.queued)
        return if (isDegraded(caps)) base - DEGRADED_OFFSET else base
    }

    fun eligible(caps: Caps, pinnedModel: String?): Boolean = score(caps, pinnedModel) != Double.NEGATIVE_INFINITY
}
