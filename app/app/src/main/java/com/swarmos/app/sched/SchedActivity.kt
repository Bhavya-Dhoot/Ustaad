package com.swarmos.app.sched

import com.swarmos.app.core.PhoneActivity
import java.util.concurrent.atomic.AtomicInteger

/** The "sched" line of [PhoneActivity]: what this phone says it is doing for the swarm's job scheduler. */
internal object SchedActivity {
    const val OWNER = "sched"

    /** [phones] is how many phones are taking part right now; [finished] counts jobs that ended, failed ones included. */
    fun batch(total: Int, finished: Int, phones: Int): String {
        val jobs = if (total == 1) "1 job" else "$total jobs"
        val where = if (phones <= 1) "on this phone" else "across $phones phones"
        return "Running $jobs $where ($finished done)."
    }

    fun servingJob(name: String?): String = "Running a job for ${name ?: "another phone"}."

    fun set(text: String) = PhoneActivity.set(OWNER, text)

    fun clear() = PhoneActivity.clear(OWNER)

    /** Jobs from other phones run side by side (one per slot), so the line stays until the last of them ends. */
    private val serving = AtomicInteger()

    /** Shows that this phone runs a job for [name] while [block] runs; takes it off again when the last such job ends, also on a throw or a cancel. */
    suspend fun <T> whileServing(name: String?, block: suspend () -> T): T {
        serving.incrementAndGet()
        set(servingJob(name))
        try {
            return block()
        } finally {
            if (serving.decrementAndGet() == 0) clear()
        }
    }
}
