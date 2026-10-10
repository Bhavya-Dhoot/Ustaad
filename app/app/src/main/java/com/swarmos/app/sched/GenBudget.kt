package com.swarmos.app.sched

/**
 * How long a generation may take, for the HTTP request to a remote phone and for the scheduler's attempt on it:
 * 30 s plus the reply length at a deliberately low 5 tokens/s (a hot phone measured 6.8 tokens/s, so a 1024-token answer
 * needs about 150 s). This is separate from liveness: heartbeats decide whether a phone is still there.
 */
object GenBudget {
    const val BASE_MS = 30_000L
    const val MS_PER_TOKEN = 200L

    fun forTokens(maxTokens: Int, baseMs: Long = BASE_MS, msPerToken: Long = MS_PER_TOKEN): Long =
        baseMs + maxTokens.coerceAtLeast(0) * msPerToken
}

/** Filled in by [SwarmLlm.chatJob] for callers that want to know how long the job waited for a phone before it was dispatched. */
class JobTiming {
    /** Milliseconds from the call to the first moment a phone was picked for the job. */
    @Volatile var waitedMs: Long = 0L
}
