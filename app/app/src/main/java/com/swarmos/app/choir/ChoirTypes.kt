package com.swarmos.app.choir

import com.swarmos.app.core.SwarmJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString

@Serializable
enum class Kind { MATH, CODE, MCQ, TEXT }

@Serializable
enum class Role { SOLVER, ALT_SOLVER }

@Serializable
enum class CheckResult { VERIFIED, REFUTED, NOT_CHECKABLE }

@Serializable
enum class PickMethod { TOOL, MAJORITY, CONFIDENCE, JUDGE, ABSTAIN }

@Serializable
enum class LaneStatus { WAITING, SOLVING, ANSWERED, REVISING, REVISED, JUDGING, TIMED_OUT, ERROR, AWAY, NO_MODEL, NO_VALID_ANSWER }

object ChoirLimits {
    const val WIRE_MAX_BYTES = 1024
    const val PROBLEM_MAX_CHARS = 640
    const val ANSWER_MAX_CHARS = 60
    const val STEP_MAX_CHARS = 100
    const val MAX_STEPS = 3
    const val REASON_MAX_CHARS = 120
}

/** Size of a message as sent on the wire (compact JSON, UTF-8). Every choir message must stay within [ChoirLimits.WIRE_MAX_BYTES]. */
inline fun <reified T> wireBytes(v: T): Int = SwarmJson.encodeToString(v).toByteArray(Charsets.UTF_8).size

// ---------------------------------------------------------------------------------------------
// Problems
// ---------------------------------------------------------------------------------------------

/**
 * One problem. `answer` is the known correct answer and is used only for scoring (never sent to a peer).
 * `check` (MATH: an arithmetic expression) and `expected` (CODE: expected outputs, MCQ: the key letter) are what
 * the problem itself supplies to the tool checker; they are only used when the run asks for it.
 */
@Serializable
data class Problem(
    val id: String = "custom",
    val kind: Kind,
    val group: String = "",
    val text: String,
    val options: List<String> = emptyList(),
    val answer: String = "",
    val check: String? = null,
    val expected: List<String> = emptyList(),
    val source: String? = null,
) {
    /** The text a model sees: the question plus lettered options. */
    fun prompt(): String =
        if (options.isEmpty()) text
        else text + "\n" + options.mapIndexed { i, o -> "${'A' + i}) $o" }.joinToString("\n")

    /** Valid MCQ letters: from `options`, or parsed from lines like "B) ..." in the text. */
    fun optionLetters(): Set<Char> {
        if (options.isNotEmpty()) return options.indices.map { 'A' + it }.toSet()
        return OPTION_LINE.findAll(text).map { it.groupValues[1].uppercase()[0] }.toSet()
    }

    companion object {
        private val OPTION_LINE = Regex("""(?m)^\s*\(?([A-Fa-f])[).:]""")

        fun parseList(json: String): List<Problem> =
            SwarmJson.decodeFromString(ListSerializer(serializer()), json)
    }
}

// ---------------------------------------------------------------------------------------------
// Wire messages (all <= 1 KB)
// ---------------------------------------------------------------------------------------------

/** R1, coordinator -> node: POST /v1/choir/solve. */
@Serializable
data class SolveRequest(
    val taskId: String,
    val problem: String,
    val kind: Kind,
    val role: Role,
    val maxTokens: Int = 300,
    val budgetMs: Long = 60_000,
    val seed: Int? = null,
)

/** One answer as exchanged between nodes. No author names. */
@Serializable
data class Candidate(
    val answer: String,
    @SerialName("key_steps") val keySteps: List<String> = emptyList(),
)

/** R2, coordinator -> node: POST /v1/choir/revise. The node keeps the problem and its own R1 answer by taskId. */
@Serializable
data class ReviseRequest(
    val taskId: String,
    val others: List<Candidate>,
    val maxTokens: Int = 150,
    val budgetMs: Long = 60_000,
)

/** R3, coordinator -> node: POST /v1/choir/judge. Candidates are distinct answers, anonymous. */
@Serializable
data class JudgeRequest(
    val taskId: String,
    val candidates: List<Candidate>,
    val maxTokens: Int = 100,
    val budgetMs: Long = 60_000,
)

/** Response to solve and revise. `error` set means no usable answer. */
@Serializable
data class NodeAnswer(
    val taskId: String,
    val round: Int,
    val nodeId: String,
    val nodeName: String,
    val model: String,
    val fake: Boolean = false,
    val answer: String = "",
    val confidence: Double = 0.0,
    @SerialName("key_steps") val keySteps: List<String> = emptyList(),
    val tokens: Int = 0,
    val ms: Long = 0,
    val error: String? = null,
    /** The first reply had no valid answer, so the node asked once more with a shorter instruction (tokens count both). */
    val retried: Boolean = false,
)

@Serializable
data class JudgeAnswer(
    val taskId: String,
    val nodeId: String,
    val nodeName: String,
    val model: String,
    val fake: Boolean = false,
    val pick: Int = -1,
    val reason: String = "",
    val tokens: Int = 0,
    val ms: Long = 0,
    val error: String? = null,
)

/** Clamp text fields so the encoded message is <= 1 KB. */
fun NodeAnswer.fitWire(): NodeAnswer {
    var m = copy(
        answer = answer.take(ChoirLimits.ANSWER_MAX_CHARS),
        keySteps = keySteps.take(ChoirLimits.MAX_STEPS).map { it.take(ChoirLimits.STEP_MAX_CHARS) },
        error = error?.take(160),
        confidence = confidence.coerceIn(0.0, 1.0),
    )
    if (wireBytes(m) > ChoirLimits.WIRE_MAX_BYTES) m = m.copy(keySteps = m.keySteps.map { it.take(40) })
    if (wireBytes(m) > ChoirLimits.WIRE_MAX_BYTES) m = m.copy(keySteps = emptyList())
    if (wireBytes(m) > ChoirLimits.WIRE_MAX_BYTES) m = m.copy(answer = m.answer.take(30), error = m.error?.take(60))
    return m
}

fun JudgeAnswer.fitWire(): JudgeAnswer {
    var m = copy(reason = reason.take(ChoirLimits.REASON_MAX_CHARS), error = error?.take(160))
    if (wireBytes(m) > ChoirLimits.WIRE_MAX_BYTES) m = m.copy(reason = m.reason.take(40), error = m.error?.take(60))
    return m
}

fun Candidate.fitWire(): Candidate = copy(
    answer = answer.take(ChoirLimits.ANSWER_MAX_CHARS),
    keySteps = keySteps.take(ChoirLimits.MAX_STEPS).map { it.take(ChoirLimits.STEP_MAX_CHARS) },
)

// ---------------------------------------------------------------------------------------------
// Run trace (what the screen shows and what /v1/choir/run returns)
// ---------------------------------------------------------------------------------------------

/** One node's answer in one round, with the tool-check of that answer. `wallMs` is measured by the coordinator since the run start. */
@Serializable
data class Reply(
    val answer: String,
    val confidence: Double,
    @SerialName("key_steps") val keySteps: List<String> = emptyList(),
    val tokens: Int = 0,
    val ms: Long = 0,
    val wallMs: Long = 0,
    val check: CheckResult = CheckResult.NOT_CHECKABLE,
    val checkDetail: String = "",
    /** What the check was based on: "problem-check" (a test supplied with the problem), "model-expression", "option-letters", "quote-exists" or "none". */
    val checkSource: String = "none",
)

@Serializable
data class LaneTrace(
    val nodeId: String,
    val name: String,
    val role: Role,
    val model: String = "",
    val fake: Boolean = false,
    val status: LaneStatus = LaneStatus.WAITING,
    val r1: Reply? = null,
    val r2: Reply? = null,
    val error: String? = null,
) {
    /** The answer this node currently votes with: R2 if it revised, else R1. */
    val latest: Reply? get() = r2 ?: r1
}

@Serializable
data class JudgeTrace(
    val nodeId: String,
    val name: String,
    val candidates: List<Candidate> = emptyList(),
    val pick: Int = -1,
    val reason: String = "",
    val tokens: Int = 0,
    val ms: Long = 0,
    val error: String? = null,
)

@Serializable
data class VoteRow(val answer: String, val count: Int, val confSum: Double, val check: CheckResult)

@Serializable
data class FinalTrace(
    val answer: String?,
    val method: PickMethod,
    val verified: Boolean,
    val label: String,
    val votes: List<VoteRow> = emptyList(),
    /** Tool-check result of the chosen answer and what it was based on (see [Reply.checkSource]). */
    val check: CheckResult = CheckResult.NOT_CHECKABLE,
    val checkSource: String = "none",
)

@Serializable
data class BaselineTrace(
    val nodeName: String,
    val answer: String? = null,
    val confidence: Double = 0.0,
    val tokens: Int = 0,
    val wallMs: Long = 0,
    val status: LaneStatus = LaneStatus.SOLVING,
    val correct: Boolean? = null,
)

@Serializable
data class Matrix(val names: List<String>, val same: List<List<Boolean>>)

@Serializable
data class Totals(val tokens: Int = 0, val wallMs: Long = 0, val rounds: Int = 0)

@Serializable
data class RunTrace(
    val taskId: String,
    val problemId: String? = null,
    val problem: String,
    val kind: Kind,
    val startedMs: Long = 0,
    val deadlineMs: Long = 60_000,
    val useProblemCheck: Boolean = false,
    /** INIT, R1, R2, R3, CHECK, DONE */
    val phase: String = "INIT",
    val round: Int = 1,
    /** PENDING, AGREE or DISAGREE (on the latest answers) */
    val agreement: String = "PENDING",
    val lanes: List<LaneTrace> = emptyList(),
    val judge: JudgeTrace? = null,
    val finalPick: FinalTrace? = null,
    val finalNoTool: FinalTrace? = null,
    val baseline: BaselineTrace? = null,
    val matrix: Matrix? = null,
    val totals: Totals = Totals(),
    val knownAnswer: String? = null,
    val baselineCorrect: Boolean? = null,
    val choirCorrect: Boolean? = null,
    val choirToolCorrect: Boolean? = null,
    val fake: Boolean = false,
    val error: String? = null,
    /** Why round 2 did not run: "unanimous" (round 1 agreed), "majority" (skipped by the majority policy), or "" (it ran, or there was nothing to compare). */
    val round2Skip: String = "",
    /** The majority policy was on for this run, so a run that skipped round 2 by majority is not mistaken for a unanimous one. */
    val majorityPolicy: Boolean = false,
)
