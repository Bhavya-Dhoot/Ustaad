package com.swarmos.app.choir

import com.swarmos.app.core.SwarmJson
import com.swarmos.app.llm.ChatRequest
import com.swarmos.app.llm.LlmClient
import kotlinx.coroutines.CancellationException
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

    private class TaskState(val problem: String, val kind: Kind, val role: Role, var own: Candidate?)

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
        val chat = ChatRequest(
            messages = ChoirPrompts.solve(req),
            maxTokens = req.maxTokens,
            temperature = ChoirPrompts.temperature(req.role),
            jsonSchema = ChoirPrompts.SOLVE_SCHEMA,
            seed = req.seed,
        )
        val outcome = call(
            budgetMs = req.budgetMs,
            chat = chat,
            scripted = sc?.r1?.let { SwarmJson.encodeToString(it) },
            fake = { FakePlanner.solveJson(findProblem(req.problem), req, me.nodeId) },
            round = 1,
            taskId = req.taskId,
            nodeId = me.nodeId,
        )
        return finishAnswer(req.taskId, 1, me, outcome, t0)
    }

    suspend fun revise(req: ReviseRequest): NodeAnswer {
        val me = identity()
        val t0 = clock()
        val st = tasks[req.taskId]
            ?: return NodeAnswer(req.taskId, 2, me.nodeId, me.name, llm().modelName, error = "unknown task ${req.taskId}").fitWire()
        val others = req.others.take(4).map { it.fitWire() }
        val sc = script
        val chat = ChatRequest(
            messages = ChoirPrompts.revise(st.kind, st.problem, st.own, others),
            maxTokens = req.maxTokens,
            temperature = ChoirPrompts.TEMP_REVISE,
            jsonSchema = ChoirPrompts.SOLVE_SCHEMA,
        )
        val outcome = call(
            budgetMs = req.budgetMs,
            chat = chat,
            scripted = (sc?.r2 ?: sc?.r1)?.let { SwarmJson.encodeToString(it) },
            fake = { FakePlanner.reviseJson(findProblem(st.problem), req.taskId, me.nodeId, st.own, others) },
            round = 2,
            taskId = req.taskId,
            nodeId = me.nodeId,
        )
        return finishAnswer(req.taskId, 2, me, outcome, t0)
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
        return try {
            val res = withTimeoutOrNull(budgetMs.coerceAtLeast(1)) { client.chat(chat) }
                ?: return Outcome("", 0, client.modelName, useFake, "timeout after ${budgetMs} ms")
            Outcome(res.text, res.stats.completionTokens, client.modelName, useFake)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Outcome("", 0, client.modelName, useFake, "model error: ${t.message ?: t::class.simpleName}")
        }
    }

    private fun finishAnswer(taskId: String, round: Int, me: NodeIdentity, o: Outcome, t0: Long): NodeAnswer {
        val ms = clock() - t0
        if (o.error != null) return NodeAnswer(taskId, round, me.nodeId, me.name, o.model, o.fake, ms = ms, error = o.error).fitWire()
        val p = parseSolve(o.text)
            ?: return NodeAnswer(taskId, round, me.nodeId, me.name, o.model, o.fake, tokens = o.tokens, ms = ms, error = "no answer in model output").fitWire()
        val out = NodeAnswer(taskId, round, me.nodeId, me.name, o.model, o.fake, p.answer, p.confidence, p.keySteps, o.tokens, ms).fitWire()
        tasks[taskId]?.own = Candidate(out.answer, out.keySteps)
        return out
    }

    private fun findProblem(promptText: String): Problem? =
        problems().firstOrNull { promptText.contains(it.text.take(60)) }

    companion object {
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
            v > 1.0 && v <= 100.0 -> v / 100.0
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
