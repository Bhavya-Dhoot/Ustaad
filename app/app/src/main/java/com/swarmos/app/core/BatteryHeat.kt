package com.swarmos.app.core

/**
 * A stand-in for PowerManager.getCurrentThermalStatus on Android 8 and 9 (API 26 to 28). The call and its listener exist from API 29,
 * and every heat rule in the app reads that status (the load gate at 4, Keep busy pausing at 3 and halving at 2, the scheduler, the
 * benchmark and Choir guards), so an older phone gets a status of the same scale from the battery temperature of the battery
 * broadcast, which every version sends. No Android classes here: the choice is unit-tested on the JVM.
 *
 * THE THRESHOLDS ARE FITTED TO THREE iQOO 15 PHONES ON ANDROID 16, NOT MEASURED ON AN OLD PHONE. The fit is 5,290 samples that pair the
 * system's status with the battery temperature (docs/benchmarks/raw: gate-9b/hammer, gate-9b/throttle-passive, final-audit/watch-AC and
 * final-audit/pass5/06-keepbusy; charging and not charging). The battery temperature each status was seen at, lowest to highest:
 *
 *  - status 0 (NONE): 32.2 to 37.8 C, 95 % of samples below 37.2 C
 *  - status 1 (LIGHT): 36.1 to 40.0 C, 95 % between 37.8 and 39.5 C
 *  - status 2 (MODERATE): 37.9 to 42.8 C, median 39.3 C, 95 % below 41.9 C
 *  - status 3 (SEVERE): 41.6 to 42.8 C, median 42.2 C
 *  - status 4 (CRITICAL): not in those files; two readings of 45.5 and 47.0 C (docs/benchmarks/2026-10-10-inapp-llm.md line 27)
 *
 * Why these cut points:
 *  - 37.5 C starts LIGHT: 98 % of the status-0 samples are below it and 99.7 % of the samples at status 1 or more above it. LIGHT
 *    changes no rule; it only stops the strip from saying Cool on a warm phone.
 *  - 39.0 C starts MODERATE: 88 % of the samples at status 2 or more are at or above it (at 39.5 only 34 % would be), at the price of
 *    calling 57 % of the status-1 samples MODERATE. Battery temperature does not separate status 1 from 2 on these phones (the system
 *    reads the skin, which runs about 3 C above the battery), so this cut leans to the cautious side: Keep busy halves a little early.
 *  - 41.5 C starts SEVERE: every status-3 sample is at or above it, and 6.5 % of the samples at status 2 or less. This is the cut that
 *    matters most, since it pauses Keep busy and refuses benchmarks; missing it would let a hot phone carry on.
 *  - 45.0 C starts CRITICAL (no new work at all): between the hottest status-3 sample (42.8) and the cooler status-4 reading (45.5).
 *    Only two readings back it.
 *  - EMERGENCY (5) and SHUTDOWN (6) are never derived: nothing here separates them from CRITICAL and the app treats all three alike.
 *
 * A phone differs from these three in how its battery sits against its skin and chip, so an old phone may read hotter or cooler than
 * the system would have said; that is why the health report carries [NodeHealth.thermalDerived] and the status is never presented as
 * the system's. No hysteresis: the reading is taken every 2 s and the system's own status also moves by a step at a time.
 */
object BatteryHeat {
    /** Tenths of a degree Celsius, the unit of BatteryManager.EXTRA_TEMPERATURE. */
    const val LIGHT_FROM = 375
    const val MODERATE_FROM = 390
    const val SEVERE_FROM = 415
    const val CRITICAL_FROM = 450

    /**
     * The status when no battery temperature is reported: MODERATE (2). It is never NONE, because "cool" would be a claim made from
     * missing data, and not LIGHT either: a phone that gives neither a thermal status nor a battery temperature is blind to its own
     * heat, so Keep busy runs at half duty on it and the scheduler prefers other phones (the rules that start at 2). A phone with no
     * battery thermistor is marked derived.
     */
    const val UNKNOWN_STATUS = 2

    /** The temperature in tenths of a degree from the battery broadcast's extra, or null when it is not reported (missing or 0 or less). */
    fun tenthsOrNull(extra: Int?): Int? = extra?.takeIf { it > 0 }

    /** The PowerManager-scale status (0 to 4) for a battery temperature in tenths of a degree; [UNKNOWN_STATUS] when null. */
    fun statusFor(tenthsC: Int?): Int = when {
        tenthsC == null -> UNKNOWN_STATUS
        tenthsC >= CRITICAL_FROM -> 4
        tenthsC >= SEVERE_FROM -> 3
        tenthsC >= MODERATE_FROM -> 2
        tenthsC >= LIGHT_FROM -> 1
        else -> 0
    }
}

/** A thermal status and whether it was derived from the battery temperature ([BatteryHeat]) instead of read from the system. */
data class ThermalReading(val status: Int, val derived: Boolean)
