package com.swarmos.app.busy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/** How one filler job ended. [Stopped] is the owner switching the mode off (or Ustaad stopping) mid-job. */
enum class BusyOutcome { Done, Cancelled, Failed, Stopped }

/** The answer of `GET /v1/sched/busy`: the switch, the state, the Hammer switch and the counters since the switch was last turned on. */
@Serializable
data class BusyReport(
    val enabled: Boolean,
    val state: String,
    val jobsDone: Int,
    val jobsCancelled: Int,
    val jobsFailed: Int,
    val tokens: Long,
    val secondsGenerating: Double,
    /** Share of the last 60 s (0.0 to 1.0) this phone spent generating filler. */
    val share60s: Double,
    /** The Hammer switch of this phone is on: it does not drop to half duty when warm. */
    val hammer: Boolean = false,
) {
    /** The counters as one plain line for Settings. Failed jobs are named only when there were some. */
    fun line(): String = String.format(
        Locale.US, "%,d %s done, %,d stopped by real work, %s%,d tokens, %.0f s generating.",
        jobsDone, if (jobsDone == 1) "job" else "jobs", jobsCancelled,
        if (jobsFailed > 0) String.format(Locale.US, "%,d failed, ", jobsFailed) else "", tokens, secondsGenerating,
    )
}

/**
 * In-memory counters of the filler loop, safe to read from any thread. Jobs are counted when they end; the job in flight
 * counts toward the seconds and the 60 s share. Filler counts live only here: they are never merged into benchmark,
 * scoreboard or batch figures.
 */
class BusyCounters(private val clock: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private val lock = Any()
    private var done = 0
    private var cancelled = 0
    private var failed = 0
    private var tokens = 0L
    private var generatingMs = 0L
    private var runningSince = -1L
    private val spans = ArrayDeque<LongArray>()   // finished jobs: start and end, kept while they can touch the last 60 s

    /** Zero everything; called when the switch is turned on. */
    fun reset() = synchronized(lock) {
        done = 0
        cancelled = 0
        failed = 0
        tokens = 0
        generatingMs = 0
        runningSince = -1
        spans.clear()
    }

    fun jobStarted() = synchronized(lock) { runningSince = clock() }

    /** [tokens] are the tokens that job produced, also when it was cancelled part way. */
    fun jobEnded(outcome: BusyOutcome, tokens: Long) = synchronized(lock) {
        val end = clock()
        val start = if (runningSince >= 0) runningSince else end
        runningSince = -1
        generatingMs += (end - start).coerceAtLeast(0L)
        this.tokens += tokens.coerceAtLeast(0L)
        when (outcome) {
            BusyOutcome.Done -> done++
            BusyOutcome.Cancelled -> cancelled++
            BusyOutcome.Failed -> failed++
            BusyOutcome.Stopped -> Unit
        }
        spans.addLast(longArrayOf(start, end))
        prune(end)
    }

    fun report(enabled: Boolean, state: BusyState, hammer: Boolean = false): BusyReport = synchronized(lock) {
        val now = clock()
        prune(now)
        val inFlight = if (runningSince >= 0) (now - runningSince).coerceAtLeast(0L) else 0L
        val from = now - WINDOW_MS
        var inWindow = 0L
        for (s in spans) inWindow += (minOf(s[1], now) - maxOf(s[0], from)).coerceAtLeast(0L)
        if (runningSince >= 0) inWindow += (now - maxOf(runningSince, from)).coerceAtLeast(0L)
        BusyReport(
            enabled = enabled,
            state = state.id,
            jobsDone = done,
            jobsCancelled = cancelled,
            jobsFailed = failed,
            tokens = tokens,
            secondsGenerating = round3((generatingMs + inFlight) / 1000.0),
            share60s = round3((inWindow.toDouble() / WINDOW_MS).coerceIn(0.0, 1.0)),
            hammer = hammer,
        )
    }

    private fun prune(now: Long) {
        while (spans.isNotEmpty() && spans.first()[1] < now - WINDOW_MS) spans.removeFirst()
    }

    private fun round3(v: Double) = Math.round(v * 1000.0) / 1000.0

    companion object {
        const val WINDOW_MS = 60_000L
    }
}

/** The payload of the audit entries `busy.summary` and `busy.off`: counts and seconds, nothing about what was asked. */
fun BusyReport.toAudit(onSeconds: Long): JsonObject = JsonObject(
    mapOf(
        "jobsDone" to JsonPrimitive(jobsDone),
        "jobsCancelled" to JsonPrimitive(jobsCancelled),
        "jobsFailed" to JsonPrimitive(jobsFailed),
        "tokens" to JsonPrimitive(tokens),
        "secondsGenerating" to JsonPrimitive(secondsGenerating),
        "secondsOn" to JsonPrimitive(onSeconds),
    ),
)
