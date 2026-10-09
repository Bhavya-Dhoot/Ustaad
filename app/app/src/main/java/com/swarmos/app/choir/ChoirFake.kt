package com.swarmos.app.choir

import com.swarmos.app.core.SwarmJson
import com.swarmos.app.llm.ChatChunk
import com.swarmos.app.llm.ChatRequest
import com.swarmos.app.llm.ChatResult
import com.swarmos.app.llm.ChatStats
import com.swarmos.app.llm.LlmClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.math.BigDecimal

// ---------------------------------------------------------------------------------------------
// Debug scripting: force a path (disagreement, flip, timeout) with fake per-node answers.
// Set per node with POST /v1/choir/debug/script, or for a whole run through the `scripts` field of /v1/choir/run.
// Output produced from a script or from the FAKE model is always flagged `fake` on the wire and labelled FAKE on screen.
// ---------------------------------------------------------------------------------------------

@Serializable
data class ScriptedAnswer(
    @SerialName("key_steps") val keySteps: List<String> = emptyList(),
    val answer: String,
    val confidence: Double = 0.8,
)

@Serializable
data class ScriptedJudge(val reason: String = "scripted", val pick: Int = 0)

@Serializable
data class FakeScript(
    val r1: ScriptedAnswer? = null,
    val r2: ScriptedAnswer? = null,
    val judge: ScriptedJudge? = null,
    /** Pretend model latency per call; a value above the round deadline simulates a node that times out. */
    val delayMs: Long = 0,
    val tokens: Int = 40,
) {
    fun isEmpty() = r1 == null && r2 == null && judge == null && delayMs == 0L
}

/** An [LlmClient] that returns a fixed text after a delay. The only thing in this module that is not the real client. */
class CannedLlm(private val text: String, private val delayMs: Long, private val tokens: Int) : LlmClient {
    override val modelName = "FAKE"
    override suspend fun ready() = true

    override fun chatStream(req: ChatRequest): Flow<ChatChunk> = flow {
        delay(delayMs)
        emit(ChatChunk(text))
        emit(ChatChunk("", done = true, stats = stats()))
    }

    override suspend fun chat(req: ChatRequest): ChatResult {
        delay(delayMs)
        return ChatResult(text, stats())
    }

    override suspend fun embed(texts: List<String>): List<FloatArray> = throw UnsupportedOperationException("choir does not embed")

    private fun stats() = ChatStats(promptTokens = 0, completionTokens = tokens, promptMs = 0.0, genMs = delayMs.toDouble())
}

/**
 * What the FAKE model answers when no script is set. Deterministic per (task, node). It looks the problem up in the
 * built-in set, is right a bit more than half the time and flips to the right answer in R2 when it sees one.
 */
object FakePlanner {
    private fun h(vararg s: String): Int = s.joinToString("|").hashCode() and 0x7fffffff

    fun delayFor(taskId: String, nodeId: String, round: Int): Long = 500L + h(taskId, nodeId, "delay$round") % 1500

    fun solveJson(problem: Problem?, req: SolveRequest, nodeId: String): String {
        if (problem == null) return enc(ScriptedAnswer(listOf("canned answer, no model"), "42", 0.5))
        val hv = h(req.taskId, nodeId, "r1")
        val right = hv % 100 < 55
        val ans = if (right) problem.answer else wrong(problem, hv)
        return enc(ScriptedAnswer(stepsFor(problem, ans), ans, 0.55 + (hv / 100 % 40) / 100.0))
    }

    fun reviseJson(problem: Problem?, taskId: String, nodeId: String, own: Candidate?, others: List<Candidate>): String {
        val keep = own ?: others.first()
        if (problem == null) return enc(ScriptedAnswer(keep.keySteps, keep.answer, 0.6))
        val ownRight = own != null && Normalizer.same(problem.kind, own.answer, problem.answer)
        val otherRight = others.any { Normalizer.same(problem.kind, it.answer, problem.answer) }
        val hv = h(taskId, nodeId, "r2")
        val ans = if (ownRight) keep.answer else if (otherRight && hv % 100 < 75) problem.answer else keep.answer
        val steps = if (Normalizer.same(problem.kind, ans, problem.answer)) stepsFor(problem, ans) else keep.keySteps
        return enc(ScriptedAnswer(steps, ans, 0.7 + (hv / 100 % 20) / 100.0))
    }

    fun judgeJson(problem: Problem?, candidates: List<Candidate>): String {
        val pick = problem?.let { p -> candidates.indexOfFirst { Normalizer.same(p.kind, it.answer, p.answer) } }?.takeIf { it >= 0 } ?: 0
        return enc(ScriptedJudge("fake judge compared the candidates", pick))
    }

    private fun stepsFor(p: Problem, ans: String): List<String> = when (p.kind) {
        Kind.MATH -> listOf(if (p.check != null) "${p.check} = $ans" else "computed $ans")
        Kind.MCQ -> listOf("ruled out the other options")
        Kind.CODE -> listOf("traced the code by hand")
        Kind.TEXT -> emptyList()
    }

    private fun wrong(p: Problem, hv: Int): String = when (p.kind) {
        Kind.MATH -> Normalizer.parseNumber(p.answer)?.let { plain(it + (hv % 5 + 1) * if (hv % 2 == 0) 1 else -1) } ?: "0"
        Kind.MCQ -> {
            val others = p.optionLetters().sorted().filter { it.toString() != p.answer.trim().take(1).uppercase() }
            others.getOrNull(hv % maxOf(others.size, 1))?.toString() ?: "A"
        }
        Kind.CODE -> p.answer.trim().toDoubleOrNull()?.let { plain(it + 1) } ?: (p.answer + "0")
        Kind.TEXT -> "unknown"
    }

    private fun plain(v: Double) = BigDecimal(v).setScale(4, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    private fun enc(a: ScriptedAnswer) = SwarmJson.encodeToString(a)
    private fun enc(j: ScriptedJudge) = SwarmJson.encodeToString(j)
}
