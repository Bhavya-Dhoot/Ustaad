package com.swarmos.app.choir

import com.swarmos.app.core.HealthMonitor
import com.swarmos.app.core.SwarmJson
import com.swarmos.app.core.ThermalLevel
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

@Serializable
data class EvalRow(
    val problemId: String,
    val kind: Kind,
    val group: String,
    val expected: String,
    val baselineAnswer: String? = null,
    /** What the scorer compared: [baselineAnswer] after normalisation (a number as a canonical decimal, an option letter). */
    val baselineNormalised: String? = null,
    /** Why the baseline has no answer ("no valid answer", a timeout), when it has none. */
    val baselineNote: String? = null,
    val baselineCorrect: Boolean = false,
    val baselineMs: Long = 0,
    val baselineTokens: Int = 0,
    val choirAnswer: String? = null,
    val choirCorrect: Boolean = false,
    val choirMs: Long = 0,
    val rounds: Int = 1,
    /** "unanimous", "majority" or "": why round 2 did not run (see RunTrace.round2Skip). */
    val round2Skip: String = "",
    val tokens: Int = 0,
    val nodesAnswered: Int = 0,
    val nodesTotal: Int = 0,
    val choirToolAnswer: String? = null,
    val choirToolCorrect: Boolean = false,
    /** The tool check verified the final answer (either of the two kinds below). */
    val verified: Boolean = false,
    /** Verified against a test supplied with the problem. */
    val verifiedByTest: Boolean = false,
    /** Only the models' own written expression was arithmetically consistent with the answer. */
    val consistent: Boolean = false,
    /** One phone, three samples: answers of the coordinator sampled three times in a row, majority taken. Null when not run. */
    val samplesAnswer: String? = null,
    /** The valid answers of the three samples in order, and what was compared for the majority pick. */
    val samplesAnswers: List<String> = emptyList(),
    val samplesNormalised: String? = null,
    val samplesFailures: List<String> = emptyList(),
    val samplesCorrect: Boolean = false,
    val samplesMs: Long = 0,
    val samplesTokens: Int = 0,
    val samplesAnswered: Int? = null,
    val toolMethod: PickMethod? = null,
    val label: String = "",
    val error: String? = null,
)

@Serializable
data class EvalSummary(
    val n: Int,
    val baselineCorrect: Int,
    val choirCorrect: Int,
    val choirToolCorrect: Int,
    val baselineAccuracy: Double,
    val choirAccuracy: Double,
    val choirToolAccuracy: Double,
    val toolVerified: Int,
    /** Of [toolVerified]: checked against the problem's own test. */
    val verifiedByTest: Int = 0,
    /** Of [toolVerified]: only the models' written steps were consistent. */
    val consistent: Int = 0,
    val abstained: Int,
    val baselineMeanMs: Double,
    val choirMeanMs: Double,
    val meanRounds: Double,
    /** One phone, three samples. [samplesN] is 0 when that arm was not run. */
    val samplesN: Int = 0,
    val samplesCorrect: Int = 0,
    val samplesAccuracy: Double = 0.0,
    val samplesMeanMs: Double = 0.0,
)

@Serializable
data class EvalReport(
    val ts: Long,
    val file: String? = null,
    val model: String,
    val fake: Boolean,
    val nodes: List<String>,
    val useProblemCheck: Boolean,
    val deadlineMs: Long,
    val summary: EvalSummary,
    val rows: List<EvalRow>,
    /** Stopped from the phone before the last problem: the rows are what was finished. */
    val stopped: Boolean = false,
    /** The run used the majority policy for round 2 (pref `choir_majority_skips_round2`); false is today's policy. */
    val majoritySkipsRound2: Boolean = false,
)

@Serializable
data class EvalProgress(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val current: String? = null,
    val lastFile: String? = null,
    val summary: EvalSummary? = null,
    val rows: List<EvalRow> = emptyList(),
    val fake: Boolean = false,
    val error: String? = null,
    /** The run used the problems' own tests (an answer key), so choir + tool check is scored against it. */
    val useProblemCheck: Boolean = false,
    val stopped: Boolean = false,
)

/**
 * Eval harness: runs problems one after another through [runOne] and scores three arms from the same runs:
 * the coordinator's own R1 answer (one phone alone), the choir pick without the tool, and the choir pick with the tool.
 */
/** The evaluation was cancelled; [rows] are the problems that were finished before that. */
class EvalCancelled(val rows: List<EvalRow>) : kotlinx.coroutines.CancellationException("evaluation stopped")

object ChoirEval {
    /** Why a 20-problem eval must not start now, or null. Uses the shared thermal threshold ([ThermalLevel.isTooHot]). */
    fun refuseWhenHot(thermalStatus: Int): String? =
        if (ThermalLevel.isTooHot(thermalStatus)) {
            "This phone is too hot for a 20-problem run (thermal status ${HealthMonitor.thermalName(thermalStatus).lowercase()}). Let it cool down, then run again."
        } else null

    fun rowOf(p: Problem, t: RunTrace, samples: SamplesResult? = null): EvalRow {
        val base = t.baseline
        val noTool = t.finalNoTool
        val tool = t.finalPick
        return EvalRow(
            problemId = p.id,
            kind = p.kind,
            group = p.group,
            expected = p.answer,
            baselineAnswer = base?.answer,
            baselineNormalised = base?.answer?.let { Normalizer.normalize(p.kind, it) },
            baselineNote = if (base?.answer == null) t.lanes.firstOrNull()?.error else null,
            baselineCorrect = t.baselineCorrect == true,
            baselineMs = base?.wallMs ?: 0,
            baselineTokens = base?.tokens ?: 0,
            choirAnswer = noTool?.answer,
            choirCorrect = t.choirCorrect == true,
            choirMs = t.totals.wallMs,
            rounds = t.totals.rounds,
            round2Skip = t.round2Skip,
            tokens = t.totals.tokens,
            nodesAnswered = t.lanes.count { it.latest != null },
            nodesTotal = t.lanes.size,
            choirToolAnswer = tool?.answer,
            choirToolCorrect = t.choirToolCorrect == true,
            verified = tool?.verified == true,
            verifiedByTest = tool?.verified == true && tool.checkSource == PROBLEM_CHECK,
            consistent = tool?.verified == true && tool.checkSource != PROBLEM_CHECK,
            samplesAnswer = samples?.answer,
            samplesAnswers = samples?.answers ?: emptyList(),
            samplesNormalised = samples?.answer?.let { Normalizer.normalize(p.kind, it) },
            samplesFailures = samples?.failures ?: emptyList(),
            samplesCorrect = samples?.answer?.let { Normalizer.same(p.kind, it, p.answer) } == true && p.answer.isNotEmpty(),
            samplesMs = samples?.wallMs ?: 0,
            samplesTokens = samples?.tokens ?: 0,
            samplesAnswered = samples?.answered,
            toolMethod = tool?.method,
            label = tool?.label ?: "",
            error = t.error,
        )
    }

    fun errorRow(p: Problem, message: String) = EvalRow(p.id, p.kind, p.group, p.answer, error = message)

    fun summarize(rows: List<EvalRow>): EvalSummary {
        val n = rows.size
        fun acc(c: Int) = if (n == 0) 0.0 else c.toDouble() / n
        val b = rows.count { it.baselineCorrect }
        val c = rows.count { it.choirCorrect }
        val ct = rows.count { it.choirToolCorrect }
        val sampled = rows.filter { it.samplesAnswered != null }
        return EvalSummary(
            n = n,
            baselineCorrect = b, choirCorrect = c, choirToolCorrect = ct,
            baselineAccuracy = acc(b), choirAccuracy = acc(c), choirToolAccuracy = acc(ct),
            toolVerified = rows.count { it.verified },
            verifiedByTest = rows.count { it.verifiedByTest },
            consistent = rows.count { it.consistent },
            abstained = rows.count { it.toolMethod == PickMethod.ABSTAIN },
            baselineMeanMs = if (n == 0) 0.0 else rows.sumOf { it.baselineMs }.toDouble() / n,
            choirMeanMs = if (n == 0) 0.0 else rows.sumOf { it.choirMs }.toDouble() / n,
            meanRounds = if (n == 0) 0.0 else rows.sumOf { it.rounds }.toDouble() / n,
            samplesN = sampled.size,
            samplesCorrect = sampled.count { it.samplesCorrect },
            samplesAccuracy = if (sampled.isEmpty()) 0.0 else sampled.count { it.samplesCorrect }.toDouble() / sampled.size,
            samplesMeanMs = if (sampled.isEmpty()) 0.0 else sampled.sumOf { it.samplesMs }.toDouble() / sampled.size,
        )
    }

    /** Sequential. [onProgress] gets (rows so far, index of the problem being started or null when finished). */
    suspend fun run(
        problems: List<Problem>,
        runOne: suspend (Problem) -> RunTrace,
        onProgress: (List<EvalRow>, Problem?) -> Unit = { _, _ -> },
        /** Equal-compute baseline, run after the choir on the same problem; null skips that arm. */
        runSamples: (suspend (Problem) -> SamplesResult)? = null,
    ): List<EvalRow> {
        val rows = mutableListOf<EvalRow>()
        try {
            for (p in problems) {
                onProgress(rows.toList(), p)
                val row = try {
                    val trace = runOne(p)
                    rowOf(p, trace, runSamples?.invoke(p))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    errorRow(p, t.message ?: t::class.simpleName ?: "failed")
                }
                rows.add(row)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            if (e is EvalCancelled) throw e
            throw EvalCancelled(rows.toList()).also { it.initCause(e) }
        }
        onProgress(rows.toList(), null)
        return rows
    }

    /** Writes `choir-eval-<ts>.json` into [dir] and returns the report with `file` set. */
    fun store(dir: File, report: EvalReport): EvalReport {
        dir.mkdirs()
        val f = File(dir, "choir-eval-${report.ts}.json")
        val withFile = report.copy(file = f.absolutePath)
        f.writeText(SwarmJson.encodeToString(withFile))
        return withFile
    }
}

/** What "one phone, three samples" produced for one problem. [answered] counts the samples that returned a usable answer. */
data class SamplesResult(
    val answers: List<String>, val answer: String?, val tokens: Int, val wallMs: Long, val answered: Int,
    /** One entry per sample that gave no usable answer: why. */
    val failures: List<String> = emptyList(),
)

/**
 * The equal-compute baseline: the coordinator alone samples the problem three times in a row (roles and
 * temperatures of the choir's own lanes: solver, alt solver, solver; a different seed each) and takes the
 * majority. Same token cap as a choir lane. A sample that errors or exceeds its budget is dropped; with no
 * majority the highest summed confidence wins (see [FinalPick]); with that tied too, the first sample stands.
 */
object ChoirSamples {
    val ROLES = listOf(Role.SOLVER, Role.ALT_SOLVER, Role.SOLVER)
    const val SAMPLE_BUDGET_MS = 45_000L

    suspend fun run(
        problem: Problem,
        taskId: String,
        self: ChoirParticipant,
        config: ChoirConfig = ChoirConfig(),
        clock: () -> Long = System::currentTimeMillis,
    ): SamplesResult {
        val t0 = clock()
        val prompt = problem.prompt()
        val votes = mutableListOf<Vote>()
        val failures = mutableListOf<String>()
        var tokens = 0
        for ((i, role) in ROLES.withIndex()) {
            val req = SolveRequest("$taskId-s$i", prompt, problem.kind, role, config.solveTokens, SAMPLE_BUDGET_MS, ChoirCoordinator.seedFor(taskId, "sample$i"))
            val a = try {
                kotlinx.coroutines.withTimeoutOrNull(SAMPLE_BUDGET_MS) { self.solve(req) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                null
            }
            if (a != null) tokens += a.tokens
            if (a != null && a.error == null) {
                votes.add(Vote("s$i", a.answer, a.confidence, keySteps = a.keySteps))
            } else {
                failures.add(a?.error ?: "timeout")
            }
        }
        return SamplesResult(votes.map { it.answer }, pick(problem.kind, votes), tokens, clock() - t0, votes.size, failures)
    }

    fun pick(kind: Kind, votes: List<Vote>): String? {
        if (votes.isEmpty()) return null
        return FinalPick.decide(kind, votes, null, useTool = false).answer ?: votes.first().answer
    }
}
