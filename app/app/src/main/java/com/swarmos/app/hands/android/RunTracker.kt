package com.swarmos.app.hands.android

import com.swarmos.app.hands.Origin
import com.swarmos.app.hands.Phase
import com.swarmos.app.hands.RunState
import com.swarmos.app.hands.Verdict
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What the audit log keeps of a run: counts, a duration and two enum names. There is no String field on purpose, so no task text,
 * label, argument or typed text can reach the record (rule S6 keeps those in the run view on the phone, not in the audit log).
 */
data class RunAudit(
    val steps: Int,
    val allowed: Int,
    val confirmed: Int,
    val declined: Int,
    val refused: Int,
    val durationMs: Long,
    val phase: Phase,
    val origin: Origin,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("steps", steps)
        put("allowed", allowed)
        put("confirmed", confirmed)
        put("declined", declined)
        put("refused", refused)
        put("ms", durationMs)
        put("phase", phase.name)
        put("origin", origin.name)
    }

    companion object {
        const val KIND = "hands.run"

        fun of(r: RunState): RunAudit = RunAudit(
            steps = r.log.size,
            allowed = r.log.count { it.verdict == Verdict.ALLOWED },
            confirmed = r.log.count { it.verdict == Verdict.CONFIRMED },
            declined = r.log.count { it.verdict == Verdict.DECLINED },
            refused = r.log.count { it.verdict == Verdict.REFUSED },
            durationMs = (r.endedAtMs - r.startedAtMs).coerceAtLeast(0),
            phase = r.phase,
            origin = r.origin,
        )
    }
}

/** What the runtime does about one change of a run. The tracker decides; the runtime does it on the platform. */
sealed interface RunEffect {
    data class ShowStop(val runId: String) : RunEffect
    data class Notice(val line: String) : RunEffect
    data object HideStop : RunEffect
    data object ClearNotice : RunEffect
    data class Audit(val counts: RunAudit) : RunEffect
}

/**
 * Turns the engine's change events into effects: the Stop chip and the notification while a run acts (PLANNING, RUNNING,
 * WAITING_FOR_PERSON), both removed when it ends, and one audit record per run at its end. The chip is shown once per run,
 * not at every step. Events come from the engine's threads.
 */
class RunTracker {
    private var shown: String? = null
    private var line: String? = null
    private val audited = LinkedHashSet<String>()

    @Synchronized
    fun onChange(r: RunState): List<RunEffect> {
        val out = ArrayList<RunEffect>(3)
        if (acting(r.phase)) {
            if (shown != r.id) {
                shown = r.id
                out += RunEffect.ShowStop(r.id)
            }
            val l = noticeLine(r.phase)
            if (l != line) {
                line = l
                out += RunEffect.Notice(l)
            }
        } else if (terminal(r.phase)) {
            if (shown == r.id) {
                shown = null
                line = null
                out += RunEffect.HideStop
                out += RunEffect.ClearNotice
            }
            if (audited.add(r.id)) {
                if (audited.size > KEEP_IDS) audited.remove(audited.first())
                out += RunEffect.Audit(RunAudit.of(r))
            }
        }
        return out
    }

    /** The accessibility service connected (again): a run that is acting needs its Stop chip put back, the old connection took it away. */
    @Synchronized
    fun onServiceConnected(): List<RunEffect> = shown?.let { listOf(RunEffect.ShowStop(it)) } ?: emptyList()

    companion object {
        private const val KEEP_IDS = 200

        fun acting(p: Phase) = p == Phase.PLANNING || p == Phase.RUNNING || p == Phase.WAITING_FOR_PERSON
        fun terminal(p: Phase) = p == Phase.DONE || p == Phase.FAILED || p == Phase.STOPPED

        /** A status line for the notification shade: the phase, never the task or anything from a screen. */
        fun noticeLine(p: Phase): String = when (p) {
            Phase.PLANNING -> "Planning the task"
            Phase.WAITING_FOR_PERSON -> "Waiting for your answer"
            else -> "Working on your task"
        }
    }
}
