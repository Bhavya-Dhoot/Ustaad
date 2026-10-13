package com.swarmos.app.choir

import com.swarmos.app.core.SwarmJson
import com.swarmos.app.llm.ChatRequest
import com.swarmos.app.llm.LlmClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.util.Collections

/** What a node needs to know about itself for the wire messages. */
data class NodeIdentity(val nodeId: String, val name: String)

internal data class Parsed(val answer: String, val confidence: Double, val keySteps: List<String>)

/**
 * Node side of the choir: answers solve / revise / judge with this phone's model, as schema-constrained JSON.
 * It only talks to [LlmClient]; the FAKE model and debug scripts are substituted at the client boundary.
 */
class ChoirNode(
    private val llm: () -> LlmClient,
    private val identity: () -> NodeIdentity,
    /** Built-in problems, used only to give the FAKE model something to answer. */
    private val problems: () -> List<Problem> = { emptyList() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    var script: FakeScript? = null

    private class TaskState(val problem: String, val kind: Kind, val role: Role, @Volatile var own: Candidate?)

    private val tasks: MutableMap<String, TaskState> = Collections.synchronizedMap(
        object : LinkedHashMap<String, TaskState>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TaskState>?) = size > 16
        },
    )

    suspend fun solve(req: SolveRequest): NodeAnswer {
        val me = identity()
        val t0 = clock()
        tasks[req.taskId] = TaskState(req.problem, req.kind, req.role, null)
        val sc = script
        val letters = AnswerCheck.optionLetters(req.problem)
        val gen = generate(
            kind = req.kind, letters = letters, problem = req.problem, budgetMs = req.budgetMs,
            build = { short ->
                ChatRequest(
                    messages = ChoirPrompts.solve(req, short),
                    maxTokens = req.maxTokens,
                    temperature = ChoirPrompts.temperature(req.role),
                    jsonSchema = ChoirPrompts.solveSchema(req.kind, letters, short),
                    seed = req.seed,
                )
            },
            scripted = sc?.r1?.let { SwarmJson.encodeToString(it) },
            fake = { FakePlanner.solveJson(findProblem(req.problem), req, me.nodeId) },
            round = 1, taskId = req.taskId, nodeId = me.nodeId,
        )
        return finishAnswer(req.taskId, 1, me, gen, t0)
    }

    suspend fun revise(req: ReviseRequest): NodeAnswer {
        val me = identity()
        val t0 = clock()
        val st = tasks[req.taskId]
            ?: return NodeAnswer(req.taskId, 2, me.nodeId, me.name, llm().modelName, error = "unknown task ${req.taskId}").fitWire()
        val others = req.others.take(4).map { it.fitWire() }
        // a lane taken over from another phone is revised from that lane's answer, not from the one this phone gave
        val own = req.own?.fitWire() ?: st.own
        val sc = script
        val letters = AnswerCheck.optionLetters(st.problem)
        val gen = generate(
            kind = st.kind, letters = letters, problem = st.problem, budgetMs = req.budgetMs,
            build = { short ->
                ChatRequest(
                    messages = ChoirPrompts.revise(st.kind, st.problem, own, others, short),
                    maxTokens = req.maxTokens,
                    temperature = ChoirPrompts.TEMP_REVISE,
                    jsonSchema = ChoirPrompts.solveSchema(st.kind, letters, short),
                )
            },
            scripted = (sc?.r2 ?: sc?.r1)?.let { SwarmJson.encodeToString(it) },
            fake = { FakePlanner.reviseJson(findProblem(st.problem), req.taskId, me.nodeId, own, others) },
            round = 2, taskId = req.taskId, nodeId = me.nodeId,
        )
        return finishAnswer(req.taskId, 2, me, gen, t0, remember = req.own == null)
    }

    suspend fun judge(req: JudgeRequest): JudgeAnswer {
        val me = identity()
        val t0 = clock()
        val st = tasks[req.taskId]
            ?: return JudgeAnswer(req.taskId, me.nodeId, me.name, llm().modelName, error = "unknown task ${req.taskId}").fitWire()
        val cands = req.candidates.take(4).map { it.fitWire() }
        val sc = script
        val chat = ChatRequest(
            messages = ChoirPrompts.judge(st.kind, st.problem, cands),
            maxTokens = req.maxTokens,
            temperature = ChoirPrompts.TEMP_JUDGE,
            jsonSchema = ChoirPrompts.JUDGE_SCHEMA,
        )
        val outcome = call(
            budgetMs = req.budgetMs,
            chat = chat,
            scripted = sc?.judge?.let { SwarmJson.encodeToString(it) },
            fake = { FakePlanner.judgeJson(findProblem(st.problem), cands) },
            round = 3,
            taskId = req.taskId,
            nodeId = me.nodeId,
        )
        val ms = clock() - t0
        if (outcome.error != null) return JudgeAnswer(req.taskId, me.nodeId, me.name, outcome.model, outcome.fake, ms = ms, error = outcome.error).fitWire()
        val pick = parseJudge(outcome.text)
        if (pick == null || pick.first !in cands.indices) {
            return JudgeAnswer(req.taskId, me.nodeId, me.name, outcome.model, outcome.fake, tokens = outcome.tokens, ms = ms, error = "unparseable or out-of-range judge reply").fitWire()
        }
        return JudgeAnswer(req.taskId, me.nodeId, me.name, outcome.model, outcome.fake, pick.first, pick.second, outcome.tokens, ms).fitWire()
    }

    // ---------------------------------------------------------------------------------------------

    private class Outcome(val text: String, val tokens: Int, val model: String, val fake: Boolean, val error: String? = null)

    private suspend fun call(
        budgetMs: Long, chat: ChatRequest, scripted: String?, fake: () -> String,
        round: Int, taskId: String, nodeId: String,
    ): Outcome {
        val real = llm()
        val sc = script
        val useFake = sc != null || real.modelName == "FAKE"
        val client: LlmClient = when {
            scripted != null -> CannedLlm(scripted, sc?.delayMs ?: 0, sc?.tokens ?: 40)
            useFake -> CannedLlm(fake(), sc?.delayMs?.takeIf { it > 0 } ?: FakePlanner.delayFor(taskId, nodeId, round), sc?.tokens ?: 60)
            else -> real
        }
        // The model call runs on its own job and only the WAIT is bounded: a client whose chat() cannot be cancelled
        // (a blocking socket) is cancelled best-effort and abandoned, so this lane still ends at its deadline.
        val job = modelScope.async { client.chat(chat) }
        return try {
            val res = withTimeoutOrNull(budgetMs.coerceAtLeast(1)) { job.await() }
            if (res == null) {
                job.cancel()
                Outcome("", 0, client.modelName, useFake, "timeout after ${budgetMs} ms")
            } else {
                Outcome(res.text, res.stats.completionTokens, client.modelName, useFake)
            }
        } catch (e: CancellationException) {
            job.cancel()
            throw e
        } catch (t: Throwable) {
            Outcome("", 0, client.modelName, useFake, "model error: ${t.message ?: t::class.simpleName}")
        }
    }

    private val modelScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Generated(val outcome: Outcome, val parsed: Parsed?, val retried: Boolean)

    /**
     * One model call whose answer must be usable ([AnswerCheck]); if it is not, one more call with a shorter instruction
     * inside whatever budget is left. The tokens of both calls are counted. `parsed` is null when there is no valid answer.
     */
    private suspend fun generate(
        kind: Kind, letters: List<Char>, problem: String, budgetMs: Long, build: (short: Boolean) -> ChatRequest,
        scripted: String?, fake: () -> String, round: Int, taskId: String, nodeId: String,
    ): Generated {
        val t0 = clock()
        // a number leaves here rounded to 6 places (486.0000000000000644 is 486), so voting, scoring and display all see one form
        fun tidy(p: Parsed) = if (kind == Kind.MATH) Normalizer.roundedDecimal(p.answer)?.let { p.copy(answer = it) } ?: p else p
        val first = call(budgetMs, build(false), scripted, fake, round, taskId, nodeId)
        if (first.error != null) return Generated(first, null, false)
        val p1 = parseSolve(first.text)
        if (p1 != null && AnswerCheck.isValid(kind, p1.answer, letters, problem)) return Generated(first, tidy(p1), false)
        val left = budgetMs - (clock() - t0)
        if (left < MIN_RETRY_MS) return Generated(first, null, false)
        val second = call(left, build(true), scripted, fake, round, taskId, nodeId)
        val both = Outcome(second.text, first.tokens + second.tokens, second.model, second.fake, second.error)
        if (second.error != null) return Generated(both, null, true)
        val p2 = parseSolve(second.text)?.takeIf { AnswerCheck.isValid(kind, it.answer, letters, problem) }?.let { tidy(it) }
        return Generated(both, p2, true)
    }

    private fun finishAnswer(taskId: String, round: Int, me: NodeIdentity, g: Generated, t0: Long, remember: Boolean = true): NodeAnswer {
        val o = g.outcome
        val ms = clock() - t0
        if (o.error != null) return NodeAnswer(taskId, round, me.nodeId, me.name, o.model, o.fake, tokens = o.tokens, ms = ms, error = o.error, retried = g.retried).fitWire()
        val p = g.parsed
            ?: return NodeAnswer(taskId, round, me.nodeId, me.name, o.model, o.fake, tokens = o.tokens, ms = ms, error = NO_VALID_ANSWER, retried = g.retried).fitWire()
        val out = NodeAnswer(taskId, round, me.nodeId, me.name, o.model, o.fake, p.answer.trim(), p.confidence, p.keySteps, o.tokens, ms, retried = g.retried).fitWire()
        if (remember) tasks[taskId]?.own = Candidate(out.answer, out.keySteps)
        return out
    }

    /** Built-in problem whose full text is in the prompt (the longest match wins, since several share an opening line). */
    private fun findProblem(promptText: String): Problem? =
        problems().filter { promptText.contains(it.text) }.maxByOrNull { it.text.length }

    companion object {
        const val NO_VALID_ANSWER = "no valid answer"
        private const val MIN_RETRY_MS = 800L
        private val ANSWER_STR = Regex(""""answer"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        private val ANSWER_NUM = Regex(""""answer"\s*:\s*(-?\d+(?:\.\d+)?)""")
        private val CONF = Regex(""""confidence"\s*:\s*(-?\d+(?:\.\d+)?)""")
        private val PICK = Regex(""""pick"\s*:\s*(-?\d+)""")
        private val REASON = Regex(""""reason"\s*:\s*"((?:[^"\\]|\\.)*)"""")

        private fun jsonSlice(t: String): String {
            val a = t.indexOf('{')
            if (a < 0) return t
            val b = t.lastIndexOf('}')
            return if (b > a) t.substring(a, b + 1) else t.substring(a)
        }

        private fun conf(v: Double?): Double = when {
            v == null || v.isNaN() -> 0.5
            v > 1.0 && v < 2.0 -> 1.0
            v >= 2.0 && v <= 100.0 -> v / 100.0
            else -> v.coerceIn(0.0, 1.0)
        }

        /** Strict JSON first; a truncated reply (token cap hit) falls back to picking the fields out with regexes. */
        internal fun parseSolve(text: String): Parsed? {
            val root = runCatching { SwarmJson.parseToJsonElement(jsonSlice(text.trim())) as? JsonObject }.getOrNull()
            if (root != null) {
                val ans = (root["answer"] as? JsonPrimitive)?.content?.trim()
                if (!ans.isNullOrEmpty()) {
                    val steps = (root["key_steps"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
                    return Parsed(ans, conf((root["confidence"] as? JsonPrimitive)?.doubleOrNull), steps)
                }
            }
            val ans = ANSWER_STR.find(text)?.groupValues?.get(1)?.let { unescape(it) } ?: ANSWER_NUM.find(text)?.groupValues?.get(1)
            if (ans.isNullOrBlank()) return null
            return Parsed(ans.trim(), conf(CONF.find(text)?.groupValues?.get(1)?.toDoubleOrNull()), emptyList())
        }

        /** (pick, reason) or null. */
        internal fun parseJudge(text: String): Pair<Int, String>? {
            val root = runCatching { SwarmJson.parseToJsonElement(jsonSlice(text.trim())) as? JsonObject }.getOrNull()
            if (root != null) {
                val pick = (root["pick"] as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
                if (pick != null) return pick to ((root["reason"] as? JsonPrimitive)?.content ?: "")
            }
            val pick = PICK.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            return pick to (REASON.find(text)?.groupValues?.get(1)?.let { unescape(it) } ?: "")
        }

        private fun unescape(s: String) = s.replace("\\\"", "\"").replace("\\n", " ").replace("\\\\", "\\")
    }
}
