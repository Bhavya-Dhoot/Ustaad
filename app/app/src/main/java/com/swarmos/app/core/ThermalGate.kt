package com.swarmos.app.core

/** The kinds of work the thermal gate decides about. [noun] starts the refusal sentence. */
enum class WorkKind(val noun: String) {
    /** The benchmark on the Models tab (one run or the sustained run). */
    BENCHMARK("A benchmark"),

    /** The 20-problem Agent Choir evaluation. */
    EVALUATION("A 20-problem run"),

    /** A Private Data Union / Knowledge Bank index run. */
    INDEX_RUN("An index run"),

    /** Starting a llama-server (chat, embedder or rpc-server), by hand, at service start or as an automatic restart. */
    MODEL_LOAD("Loading a model"),

    /** A new job that another phone sends to this one. */
    REMOTE_JOB("A job from another phone"),
}

/** [reason] is a plain sentence for the user, set exactly when [allowed] is false. */
data class ThermalDecision(val allowed: Boolean, val reason: String? = null) {
    companion object {
        val ALLOW = ThermalDecision(true)
    }
}

/**
 * The one decision "may this phone start this work at this thermal status" (PowerManager.currentThermalStatus, 0 NONE to
 * 6 SHUTDOWN) for the whole app. Pure, so the scheduler, the Models module, Choir and the Knowledge Bank ask the same function
 * instead of comparing against their own numbers:
 *  - status 3 (SEVERE, [ThermalLevel.tooHot]): the refusals that already existed stay: the benchmark and the 20-problem run.
 *    Index runs, model loads and jobs from other phones are still allowed (the scheduler only prefers other phones).
 *  - status 4 (CRITICAL) and above ([ThermalLevel.isShedding]): every kind is refused, so a phone that is about to throttle or
 *    shut down takes on nothing new. Work already running is not touched.
 */
object ThermalGate {
    fun decide(thermalStatus: Int, work: WorkKind): ThermalDecision {
        val refused = when {
            ThermalLevel.isShedding(thermalStatus) -> true
            ThermalLevel.isTooHot(thermalStatus) -> work == WorkKind.BENCHMARK || work == WorkKind.EVALUATION
            else -> false
        }
        if (!refused) return ThermalDecision.ALLOW
        val name = HealthMonitor.thermalName(thermalStatus).lowercase()
        return ThermalDecision(false, "This phone is too hot (thermal status $name). ${work.noun} is off until it cools down.")
    }
}
