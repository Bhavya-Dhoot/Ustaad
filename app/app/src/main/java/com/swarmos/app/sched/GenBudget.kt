package com.swarmos.app.sched

import kotlin.math.ceil

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

    /**
     * Allowance per token for a phone that decodes [tokPerSec]: it may run [SLACK] times slower than measured (a hot phone dropped
     * from 13.5 to 6.8, so 2.5 leaves room), never less than [floor] (today's 200 ms, which a phone above 12.5 tokens/s stays on) and
     * at most [MAX_MS_PER_TOKEN]. [tokPerSec] unknown or not positive: [floor]. A PLANNING RULE, not a measurement.
     */
    fun msPerToken(tokPerSec: Double?, floor: Long = MS_PER_TOKEN): Long {
        if (tokPerSec == null || tokPerSec <= 0.0) return floor
        return maxOf(floor, ceil(1000.0 * SLACK / tokPerSec).toLong().coerceAtMost(MAX_MS_PER_TOKEN))
    }

    /** The allowance for an attempt that may land on any of [targets]: the slowest one's figure (its profile speed), else [floor]. */
    fun slowestMsPerToken(targets: Collection<Caps>, floor: Long = MS_PER_TOKEN): Long =
        targets.maxOfOrNull { msPerToken(it.profileTokPerSec, floor) } ?: floor

    const val SLACK = 2.5
    const val MAX_MS_PER_TOKEN = 5_000L
}

/** Filled in by [SwarmLlm.chatJob] for callers that want to know how long the job waited for a phone before it was dispatched. */
class JobTiming {
    /** Milliseconds from the call to the first moment a phone was picked for the job. */
    @Volatile var waitedMs: Long = 0L
}
