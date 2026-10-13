package com.swarmos.app.hands.ui

import com.swarmos.app.hands.Phase
import com.swarmos.app.hands.RunState
import com.swarmos.app.hands.StepLine
import com.swarmos.app.hands.SubStatus
import com.swarmos.app.hands.Tier
import com.swarmos.app.hands.Verdict
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// What the Hands screen and the task sheet show of a RunState, as plain rows. No Android and no Compose here: tested on the JVM.
// Words, not colours (DESIGN.md: no green, yellow or red for status).

fun phaseWord(phase: Phase): String = when (phase) {
    Phase.WAITING_FOR_GO -> "Waiting for Run"
    Phase.PLANNING -> "Planning"
    Phase.RUNNING -> "Running"
    Phase.WAITING_FOR_PERSON -> "Waiting for you"
    Phase.DONE -> "Done"
    Phase.FAILED -> "Failed"
    Phase.STOPPED -> "Stopped"
}

/** The run acts on the phone or is about to: a second run must not start now. */
fun isActing(phase: Phase): Boolean = phase == Phase.PLANNING || phase == Phase.RUNNING || phase == Phase.WAITING_FOR_PERSON

fun isEnded(phase: Phase): Boolean = phase == Phase.DONE || phase == Phase.FAILED || phase == Phase.STOPPED

/** The run that is acting now, if any (the list is newest first). */
fun actingRun(runs: List<RunState>): RunState? = runs.firstOrNull { isActing(it.phase) }

fun statusWord(status: SubStatus): String = when (status) {
    SubStatus.TODO -> "to do"
    SubStatus.RUNNING -> "running"
    SubStatus.DONE -> "done"
    SubStatus.FAILED -> "failed"
    SubStatus.SKIPPED -> "skipped"
}

fun verdictWord(verdict: Verdict): String = when (verdict) {
    Verdict.ALLOWED -> "allowed"
    Verdict.CONFIRMED -> "you said yes"
    Verdict.DECLINED -> "you said no"
    Verdict.REFUSED -> "refused"
}

private fun tierWord(tier: Tier): String = when (tier) {
    Tier.FAST -> "fast"
    Tier.STRONG -> "strong"
}

/** One line of a task or a result, cut with three dots when longer than [max]. */
fun oneLine(text: String, max: Int): String {
    val flat = text.trim().replace(Regex("\\s+"), " ")
    if (flat.length <= max) return flat
    var end = max - 3
    if (end > 0 && Character.isHighSurrogate(flat[end - 1])) end--
    return flat.substring(0, end).trimEnd() + "..."
}

private val TimeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

fun clockTime(ms: Long, zone: ZoneId): String = if (ms <= 0) "" else TimeFormat.format(Instant.ofEpochMilli(ms).atZone(zone))

data class RunRow(val id: String, val title: String, val phase: String, val started: String, val ended: Boolean)

fun runRows(runs: List<RunState>, zone: ZoneId): List<RunRow> =
    runs.map { RunRow(it.id, oneLine(it.task, 80), phaseWord(it.phase), clockTime(it.startedAtMs, zone), isEnded(it.phase)) }

data class PlanRow(val number: Int, val goal: String, val status: String, val outcome: String)

fun planRows(run: RunState): List<PlanRow> =
    run.plan.mapIndexed { i, s -> PlanRow(i + 1, oneLine(s.task.goal, 160), statusWord(s.status), oneLine(s.outcome, 200)) }

data class StepRow(val time: String, val what: String, val verdict: String, val result: String, val tier: String, val subtask: Int)

private fun stepWhat(line: StepLine): String = if (line.args.isBlank()) line.tool else "${line.tool} ${oneLine(line.args, 100)}"

/** The last [last] step lines, oldest of them first. */
fun stepRows(run: RunState, zone: ZoneId, last: Int = Int.MAX_VALUE): List<StepRow> =
    run.log.takeLast(last.coerceAtLeast(0)).map {
        StepRow(clockTime(it.atMs, zone), stepWhat(it), verdictWord(it.verdict), oneLine(it.result, 160), tierWord(it.tier), it.sub + 1)
    }

/** What the person is shown as the end of a run: the result, or a plain line when the run ended without one. */
fun endNote(run: RunState): String = when {
    !isEnded(run.phase) -> ""
    run.result.isNotBlank() -> run.result.trim()
    run.phase == Phase.DONE -> "Done."
    run.phase == Phase.STOPPED -> "Stopped."
    else -> "Ustaad could not finish this task."
}
