package com.swarmos.app.sched

import com.swarmos.app.core.ThermalLevel

/**
 * One phone's thermal status as the scheduler steers by it, with hysteresis: a reading at or above the current level is taken
 * at once, a lower one only after the phone has read below that level for [holdMs] (20 s), and then only down to the
 * highest reading seen in that time. So a phone excluded at status 4 comes back at 3 after 20 s of reading 3 or less, and is
 * fully back (below 3) after 20 s more of reading 2 or less. Below the exclusion line ([ThermalLevel.TOO_HOT_FROM]) the
 * reading is the level. Pure: time is passed in.
 */
class ThermalGate(private val holdMs: Long = HOLD_MS) {
    /** The level to steer by. */
    var level: Int = 0
        private set
    private var lowerSince = -1L
    private var lowerMax = 0

    /** Feeds one reading taken at [nowMs] and returns the level. */
    fun update(status: Int, nowMs: Long): Int {
        val s = status.coerceAtLeast(0)
        when {
            s >= level || level < ThermalLevel.TOO_HOT_FROM -> {
                level = s
                lowerSince = -1L
            }
            else -> {
                if (lowerSince < 0) {
                    lowerSince = nowMs
                    lowerMax = s
                } else {
                    lowerMax = maxOf(lowerMax, s)
                }
                if (nowMs - lowerSince >= holdMs) {
                    level = lowerMax
                    lowerSince = -1L
                }
            }
        }
        return level
    }

    companion object {
        const val HOLD_MS = 20_000L
    }
}
