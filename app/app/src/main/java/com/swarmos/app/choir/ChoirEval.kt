package com.swarmos.app.choir

import com.swarmos.app.core.SwarmJson
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
    val baselineCorrect: Boolean = false,
    val baselineMs: Long = 0,
    val baselineTokens: Int = 0,
    val choirAnswer: String? = null,
    val choirCorrect: Boolean = false,
    val choirMs: Long = 0,
    val rounds: Int = 1,
    val tokens: Int = 0,
    val nodesAnswered: Int = 0,
    val nodesTotal: Int = 0,
    val choirToolAnswer: String? = null,
    val choirToolCorrect: Boolean = false,
    val verified: Boolean = false,
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
    val abstained: Int,
    val baselineMeanMs: Double,
    val choirMeanMs: Double,
    val meanRounds: Double,
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
)

/**
 * Eval harness: runs problems one after another through [runOne] and scores three arms from the same runs:
 * the coordinator's own R1 answer (one phone alone), the choir pick without the tool, and the choir pick with the tool.
 */
object ChoirEval {
    fun rowOf(p: Problem, t: RunTrace): EvalRow {
        val base = t.baseline
        val noTool = t.finalNoTool
        val tool = t.finalPick
        return EvalRow(
            problemId = p.id,
            kind = p.kind,
            group = p.group,
            expected = p.answer,
            baselineAnswer = base?.answer,
            baselineCorrect = t.baselineCorrect == true,
            baselineMs = base?.wallMs ?: 0,
            baselineTokens = base?.tokens ?: 0,
            choirAnswer = noTool?.answer,
            choirCorrect = t.choirCorrect == true,
            choirMs = t.totals.wallMs,
            rounds = t.totals.rounds,
            tokens = t.totals.tokens,
            nodesAnswered = t.lanes.count { it.latest != null },
            nodesTotal = t.lanes.size,
            choirToolAnswer = tool?.answer,
            choirToolCorrect = t.choirToolCorrect == true,
            verified = tool?.verified == true,
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
        return EvalSummary(
            n = n,
            baselineCorrect = b, choirCorrect = c, choirToolCorrect = ct,
            baselineAccuracy = acc(b), choirAccuracy = acc(c), choirToolAccuracy = acc(ct),
            toolVerified = rows.count { it.verified },
            abstained = rows.count { it.toolMethod == PickMethod.ABSTAIN },
            baselineMeanMs = if (n == 0) 0.0 else rows.sumOf { it.baselineMs }.toDouble() / n,
            choirMeanMs = if (n == 0) 0.0 else rows.sumOf { it.choirMs }.toDouble() / n,
            meanRounds = if (n == 0) 0.0 else rows.sumOf { it.rounds }.toDouble() / n,
        )
    }

    /** Sequential. [onProgress] gets (rows so far, index of the problem being started or null when finished). */
    suspend fun run(
        problems: List<Problem>,
        runOne: suspend (Problem) -> RunTrace,
        onProgress: (List<EvalRow>, Problem?) -> Unit = { _, _ -> },
    ): List<EvalRow> {
        val rows = mutableListOf<EvalRow>()
        for (p in problems) {
            onProgress(rows.toList(), p)
            val row = try {
                rowOf(p, runOne(p))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                errorRow(p, t.message ?: t::class.simpleName ?: "failed")
            }
            rows.add(row)
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
