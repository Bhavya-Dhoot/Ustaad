package com.swarmos.app.choir

import com.swarmos.app.core.SwarmJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlin.random.Random

/** A node taking part in a run: this phone (direct call) or a peer (signed HTTP). */
interface ChoirParticipant {
    val nodeId: String
    val name: String

    /** The link heartbeat says this phone is away: it is shown as an away lane and never asked. */
    val away: Boolean get() = false

    /** This phone has no real chat model (demo builds): it is shown as a lane that was not asked. */
    val noModel: Boolean get() = false

    /**
     * The live form of [away]: asked again while a round runs, so a phone whose heartbeat stops in the middle of a round is
     * dropped from it at once and not when the round's deadline passes.
     */
    fun isAway(): Boolean = away

    suspend fun solve(req: SolveRequest): NodeAnswer
    suspend fun revise(req: ReviseRequest): NodeAnswer
    suspend fun judge(req: JudgeRequest): JudgeAnswer
}

class LocalParticipant(private val node: ChoirNode, override val nodeId: String, override val name: String) : ChoirParticipant {
    override suspend fun solve(req: SolveRequest) = node.solve(req)
    override suspend fun revise(req: ReviseRequest) = node.revise(req)
    override suspend fun judge(req: JudgeRequest) = node.judge(req)
}

/**
 * Round budget. Everything is measured from the start of the run: R1 may use until `r1Frac` of the deadline,
 * R2 until `r2Frac`, R3 until the deadline itself. A node that has not answered by then is dropped from the vote.
 */
data class ChoirConfig(
    val deadlineMs: Long = 60_000,
    val r1Frac: Double = 0.6,
    val r2Frac: Double = 0.85,
    val solveTokens: Int = 300,
    val reviseTokens: Int = 150,
    val judgeTokens: Int = 100,
)

data class RunOptions(
    /** Let the tool checker use the problem's own `check` / `expected` fields (an answer key for built-in problems). */
    val useProblemCheck: Boolean = false,
    /**
     * Skip round 2 when every phone that was asked answered round 1, there are at least three, and most of them agree (today only a
     * unanimous round 1 skips it). Off unless the pref `choir_majority_skips_round2` is on; the trace records which policy ran.
     */
    val majoritySkipsRound2: Boolean = false,
)

/**
 * Coordinator: R1 blind parallel solve on every node (this phone included), R2 exchange only if the normalised
 * answers differ, R3 judge only if there is still no majority, tool check, final pick. Pure logic: nodes, clock and
 * audit sink are injected, so the whole protocol runs in JVM tests.
 */
class ChoirCoordinator(
    private val audit: (String, JsonElement) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val config: ChoirConfig = ChoirConfig(),
) {
    private class Lane(val p: ChoirParticipant, val role: Role) {
        // written by the per-lane coroutines and the run, read by snapshot() on any thread
        @Volatile var model = ""
        @Volatile var fake = false
        @Volatile var status = LaneStatus.WAITING
        @Volatile var r1: Reply? = null
        @Volatile var r2: Reply? = null
        @Volatile var error: String? = null
        val latest: Reply? get() = r2 ?: r1
    }

    suspend fun run(
        problem: Problem,
        taskId: String,
        self: ChoirParticipant,
        peers: List<ChoirParticipant>,
        options: RunOptions = RunOptions(),
        onUpdate: (RunTrace) -> Unit = {},
    ): RunTrace {
        val t0 = clock()
        fun elapsed() = clock() - t0
        fun budget(frac: Double) = (config.deadlineMs * frac).toLong() - elapsed()

        val kind = problem.kind
        val prompt = problem.prompt()
        val sortedPeers = peers.sortedBy { it.name }
        // roles alternate over the phones that are actually asked: with two phones that is solver and alt solver
        var asked = 0
        val lanes = buildList {
            add(Lane(self, Role.SOLVER))
            sortedPeers.forEach { p -> add(Lane(p, roleForPeer(if (p.away || p.noModel) asked else asked++))) }
        }
        val active = lanes.filter { !it.p.away && !it.p.noModel }

        val lock = Any()
        // every write to the run state happens inside the lock; snapshot() reads it inside the same lock
        fun <T> locked(block: () -> T): T = synchronized(lock) { block() }
        var phase = "INIT"
        var round = 1
        var judge: JudgeTrace? = null
        var finalPick: FinalTrace? = null
        var finalNoTool: FinalTrace? = null
        var error: String? = null
        var round2Skip = ""
        lanes.filter { it.p.away }.forEach { it.status = LaneStatus.AWAY; it.error = "away: no heartbeat, not asked" }
        lanes.filter { !it.p.away && it.p.noModel }.forEach { it.status = LaneStatus.NO_MODEL; it.error = ChoirReadiness.PEER_NO_MODEL }

        fun correctOf(f: FinalTrace?): Boolean? = when {
            f == null || problem.answer.isEmpty() -> null
            f.answer == null -> false
            else -> known(kind, problem, f.answer)
        }

        fun snapshot(): RunTrace {
            val voters = lanes.filter { it.latest != null }
            val votes = voters.map { Vote(it.p.nodeId, it.latest!!.answer, it.latest!!.confidence) }
            val agreement = when {
                voters.size < 2 -> "PENDING"
                FinalPick.groups(kind, votes).size == 1 -> "AGREE"
                else -> "DISAGREE"
            }
            val base = lanes.first()
            val b = BaselineTrace(
                nodeName = base.p.name,
                answer = base.r1?.answer,
                confidence = base.r1?.confidence ?: 0.0,
                tokens = base.r1?.tokens ?: 0,
                wallMs = base.r1?.wallMs ?: elapsed(),
                status = base.status.let { if (base.r1 != null) LaneStatus.ANSWERED else it },
                correct = base.r1?.let { known(kind, problem, it.answer) },
            )
            val tokens = lanes.sumOf { (it.r1?.tokens ?: 0) + (it.r2?.tokens ?: 0) } + (judge?.tokens ?: 0)
            return RunTrace(
                taskId = taskId,
                problemId = problem.id,
                problem = prompt,
                kind = kind,
                startedMs = t0,
                deadlineMs = config.deadlineMs,
                useProblemCheck = options.useProblemCheck,
                phase = phase,
                round = round,
                agreement = agreement,
                lanes = lanes.map { LaneTrace(it.p.nodeId, it.p.name, it.role, it.model, it.fake, it.status, it.r1, it.r2, it.error) },
                judge = judge,
                finalPick = finalPick,
                finalNoTool = finalNoTool,
                baseline = b,
                matrix = if (voters.size >= 2) matrix(kind, voters.map { it.p.name to it.latest!!.answer }) else null,
                totals = Totals(tokens, elapsed(), round),
                knownAnswer = problem.answer.takeIf { it.isNotEmpty() },
                baselineCorrect = b.correct,
                choirCorrect = correctOf(finalNoTool),
                choirToolCorrect = correctOf(finalPick),
                fake = lanes.any { it.fake },
                error = error,
                round2Skip = round2Skip,
                majorityPolicy = options.majoritySkipsRound2,
            )
        }

        fun emit() = synchronized(lock) { onUpdate(snapshot()) }
        fun log(what: String, node: String?, msg: JsonElement) = audit(what, buildJsonObject {
            put("task", taskId)
            if (node != null) put("node", node)
            put("msg", msg)
        })

        if (prompt.length > ChoirLimits.PROBLEM_MAX_CHARS) {
            locked {
                error = "problem is ${prompt.length} characters; the limit is ${ChoirLimits.PROBLEM_MAX_CHARS} (each message stays within 1 KB)"
                phase = "DONE"
            }
            emit()
            return snapshot()
        }

        fun replyOf(a: NodeAnswer, wall: Long): Reply {
            val t = ToolChecker.check(problem, a.answer, a.keySteps, options.useProblemCheck)
            val basis = when (t.source) {
                PROBLEM_CHECK -> "problem's test"
                "model-expression" -> "model's own steps"
                "quote-exists" -> "source text"
                "option-letters" -> "option letters"
                else -> null
            }
            val detail = if (basis != null) "${t.detail} (${basis})" else t.detail
            return Reply(a.answer, a.confidence, a.keySteps, a.tokens, a.ms, wall, t.result, detail, t.source)
        }

        fun votesNow() = lanes.filter { it.latest != null }.map {
            Vote(it.p.nodeId, it.latest!!.answer, it.latest!!.confidence, it.latest!!.check, it.latest!!.keySteps, it.latest!!.checkSource)
        }

        // a phone's answer is checked like this phone's own are (the node does it before it answers), and must be for this task and round
        val letters = AnswerCheck.optionLetters(prompt)
        fun wrongAnswer(lane: Lane, a: NodeAnswer, round: Int): String? = when {
            lane.p === self -> null
            a.taskId != taskId -> "reply for another task (${a.taskId})"
            a.round != round -> "reply for round ${a.round}, asked round $round"
            !AnswerCheck.isValid(kind, a.answer, letters, prompt) -> ChoirNode.NO_VALID_ANSWER
            else -> null
        }

        /** Something threw while this lane's answer was being handled: the lane keeps any answer it already has, and the run goes on. */
        fun laneFailed(lane: Lane, t: Throwable) {
            synchronized(lock) {
                if (lane.latest == null) lane.status = LaneStatus.ERROR
                lane.error = "internal: ${t.javaClass.simpleName}: ${t.message}".take(160)
            }
            runCatching { emit() }
        }

        /** The run itself failed: it ends as DONE with the error, with every vote gathered so far and a pick made from them. */
        fun finishWithError(t: Throwable): RunTrace {
            val msg = "run failed: ${t.javaClass.simpleName}: ${t.message}".take(200)
            val trace = runCatching {
                synchronized(lock) {
                    error = msg
                    phase = "DONE"
                    if (finalPick == null) runCatching {
                        val votes = votesNow()
                        finalNoTool = FinalPick.decide(kind, votes, null, useTool = false, totalNodes = lanes.size)
                        finalPick = FinalPick.decide(kind, votes, null, useTool = true, totalNodes = lanes.size)
                    }
                    snapshot()
                }
            }.getOrElse {
                RunTrace(taskId = taskId, problemId = problem.id, problem = prompt, kind = kind, startedMs = t0, deadlineMs = config.deadlineMs, phase = "DONE", error = msg)
            }
            runCatching { log("choir.error", null, buildJsonObject { put("error", msg) }) }
            runCatching { onUpdate(trace) }
            return trace
        }

        try {
            log("choir.run.start", null, buildJsonObject {
                put("problemId", problem.id)
                put("kind", kind.name)
                put("nodes", lanes.joinToString(",") { it.p.name })
                put("away", lanes.filter { it.p.away }.joinToString(",") { it.p.name })
                put("deadlineMs", config.deadlineMs)
            })
            lanes.filter { it.p.away }.forEach { log("choir.away", it.p.name, buildJsonObject { put("status", "AWAY") }) }
            lanes.filter { !it.p.away && it.p.noModel }.forEach { log("choir.no_model", it.p.name, buildJsonObject { put("status", "NO_MODEL") }) }

            // ---- R1: blind parallel solve ------------------------------------------------------------
            locked { phase = "R1"; active.forEach { it.status = LaneStatus.SOLVING } }
            emit()
            coroutineScope {
                active.map { lane ->
                    async {
                        try {
                            val remaining = budget(config.r1Frac)
                            val req = SolveRequest(taskId, prompt, kind, lane.role, config.solveTokens, remaining.coerceAtLeast(1), seedFor(taskId, lane.p.nodeId))
                            log("choir.solve.req", lane.p.name, SwarmJson.encodeToJsonElement(req))
                            val res = guarded(remaining, lane.p) { lane.p.solve(req) }
                            val a = res.getOrNull()
                            val bad = if (a != null && a.error == null) wrongAnswer(lane, a, 1) else null
                            synchronized(lock) {
                                when {
                                    res.exceptionOrNull() is WentAway -> {
                                        lane.status = LaneStatus.AWAY
                                        lane.error = "away: heartbeat lost during the round"
                                    }
                                    res.isFailure -> {
                                        lane.status = LaneStatus.ERROR
                                        lane.error = res.exceptionOrNull()?.message ?: "request failed"
                                    }
                                    a == null -> {
                                        lane.status = LaneStatus.TIMED_OUT
                                        lane.error = "no answer within ${remaining / 1000.0} s"
                                    }
                                    a.error != null -> {
                                        lane.model = a.model; lane.fake = a.fake
                                        // a node that ran out of its own budget counts as timed out, not as broken
                                        lane.status = when {
                                            a.error.startsWith("timeout") -> LaneStatus.TIMED_OUT
                                            a.error == ChoirNode.NO_VALID_ANSWER -> LaneStatus.NO_VALID_ANSWER
                                            else -> LaneStatus.ERROR
                                        }
                                        lane.error = a.error
                                    }
                                    bad != null -> {
                                        lane.model = a.model; lane.fake = a.fake
                                        lane.status = if (bad == ChoirNode.NO_VALID_ANSWER) LaneStatus.NO_VALID_ANSWER else LaneStatus.ERROR
                                        lane.error = bad
                                    }
                                    else -> {
                                        lane.model = a.model; lane.fake = a.fake
                                        lane.r1 = replyOf(a, elapsed())
                                        lane.status = LaneStatus.ANSWERED
                                    }
                                }
                            }
                            if (a != null) log("choir.solve.res", lane.p.name, SwarmJson.encodeToJsonElement(a))
                            else log("choir.timeout", lane.p.name, buildJsonObject { put("round", 1); put("status", lane.status.name); put("error", lane.error ?: "") })
                            emit()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            laneFailed(lane, t)
                        }
                    }
                }.awaitAll()
            }

            // ---- R2: exchange, only if the normalised answers differ ----------------------------------
            val r1Voters = lanes.filter { it.r1 != null }
            val differ = r1Voters.size >= 2 && FinalPick.groups(kind, votesNow()).size > 1
            // the optional majority policy: every phone that was asked answered, there are three or more, and most of them agree
            val majoritySkip = options.majoritySkipsRound2 && differ && r1Voters.size >= 3 && r1Voters.size == active.size &&
                FinalPick.hasMajority(kind, votesNow())
            locked { round2Skip = if (majoritySkip) "majority" else if (r1Voters.size >= 2 && !differ) "unanimous" else "" }
            if (differ && !majoritySkip) {
                locked { phase = "R2"; round = 2; r1Voters.forEach { it.status = LaneStatus.REVISING } }
                emit()
                val r1Candidates = r1Voters.map { it to Candidate(it.r1!!.answer, it.r1!!.keySteps) }
                coroutineScope {
                    r1Voters.map { lane ->
                        async {
                            try {
                                val remaining = budget(config.r2Frac)
                                val others = r1Candidates.filter { it.first !== lane }.map { it.second }
                                    .shuffled(Random(seedFor(taskId, lane.p.nodeId)))
                                val req = fitRevise(ReviseRequest(taskId, others, config.reviseTokens, remaining.coerceAtLeast(1)))
                                log("choir.revise.req", lane.p.name, SwarmJson.encodeToJsonElement(req))
                                val res = guarded(remaining, lane.p) { lane.p.revise(req) }
                                val a = res.getOrNull()
                                val bad = if (a != null && a.error == null) wrongAnswer(lane, a, 2) else null
                                synchronized(lock) {
                                    when {
                                        a != null && a.error == null && bad == null -> {
                                            lane.r2 = replyOf(a, elapsed())
                                            lane.status = LaneStatus.REVISED
                                        }
                                        else -> {
                                            lane.status = LaneStatus.ANSWERED
                                            lane.error = "R2 " + (if (res.isFailure) "failed: ${res.exceptionOrNull()?.message}" else if (a == null) "timed out" else "failed: ${bad ?: a.error}") +
                                                "; R1 answer kept"
                                        }
                                    }
                                }
                                if (a != null) log("choir.revise.res", lane.p.name, SwarmJson.encodeToJsonElement(a))
                                else log("choir.timeout", lane.p.name, buildJsonObject { put("round", 2); put("error", lane.error ?: "") })
                                emit()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (t: Throwable) {
                                laneFailed(lane, t)
                            }
                        }
                    }.awaitAll()
                }
            }

            // ---- R3: judge, only if there is still no majority ---------------------------------------------
            var judgePick: Int? = null
            val afterR2 = votesNow()
            if (afterR2.size >= 2 && !FinalPick.hasMajority(kind, afterR2)) {
                locked { phase = "R3"; round = 3 }
                val groups = FinalPick.groups(kind, afterR2)
                val leadingNodes = groups.sortedByDescending { it.confSum }.take(2).flatMap { g -> g.members.map { it.node } }.toSet()
                val voters = lanes.filter { it.latest != null }
                val judgeLane = if (voters.size >= 3) voters.firstOrNull { it.p.nodeId !in leadingNodes } ?: lanes.first() else lanes.first()
                val prevStatus = judgeLane.status
                locked { judgeLane.status = LaneStatus.JUDGING }
                emit()
                val remaining = (config.deadlineMs - elapsed())
                val req = fitJudge(JudgeRequest(taskId, groups.map { Candidate(it.best.answer, it.best.keySteps) }, config.judgeTokens, remaining.coerceAtLeast(1)))
                log("choir.judge.req", judgeLane.p.name, SwarmJson.encodeToJsonElement(req))
                val res = guarded(remaining, judgeLane.p) { judgeLane.p.judge(req) }
                val a = res.getOrNull()
                synchronized(lock) {
                    judgeLane.status = prevStatus
                    if (a != null && a.error == null && a.pick in groups.indices) judgePick = a.pick
                    judge = JudgeTrace(
                        nodeId = judgeLane.p.nodeId,
                        name = judgeLane.p.name,
                        candidates = req.candidates,
                        pick = a?.pick ?: -1,
                        reason = a?.reason ?: "",
                        tokens = a?.tokens ?: 0,
                        ms = a?.ms ?: 0,
                        error = when {
                            res.isFailure -> res.exceptionOrNull()?.message ?: "request failed"
                            a == null -> "judge timed out"
                            else -> a.error
                        },
                    )
                }
                if (a != null) log("choir.judge.res", judgeLane.p.name, SwarmJson.encodeToJsonElement(a))
                else log("choir.timeout", judgeLane.p.name, buildJsonObject { put("round", 3) })
                emit()
            }

            // ---- tool check and final pick ----------------------------------------------------------------
            locked { phase = "CHECK" }
            emit()
            val votes = votesNow()
            for (l in lanes) {
                val r = l.latest ?: continue
                log("choir.toolcheck", l.p.name, buildJsonObject { put("answer", r.answer); put("result", r.check.name); put("detail", r.checkDetail) })
            }
            synchronized(lock) {
                finalNoTool = FinalPick.decide(kind, votes, judgePick, useTool = false, totalNodes = lanes.size)
                finalPick = FinalPick.decide(kind, votes, judgePick, useTool = true, totalNodes = lanes.size)
            }
            val trace = synchronized(lock) { phase = "DONE"; snapshot() }
            log("choir.final", null, buildJsonObject {
                put("answer", finalPick?.answer ?: "")
                put("method", finalPick?.method?.name ?: "")
                put("label", finalPick?.label ?: "")
                put("verified", finalPick?.verified ?: false)
                put("rounds", trace.totals.rounds)
                put("tokens", trace.totals.tokens)
                put("wallMs", trace.totals.wallMs)
            })
            onUpdate(trace)
            return trace
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return finishWithError(t)
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------

    /** A phone's heartbeat stopped while it was being asked. */
    private class WentAway : RuntimeException("went away during the round")

    /**
     * Runs [block] on its own job and waits for it for at most [budgetMs], or until [p] is flagged away, whichever comes first. The
     * wait is what is bounded, not the call: a call that cannot be cancelled (a blocking socket) is cancelled best-effort and
     * abandoned, so it can never hold the run, or anyone's vote, past the deadline. Null means timed out; a failure of [WentAway]
     * means the phone left.
     */
    private suspend fun <T> guarded(budgetMs: Long, p: ChoirParticipant, block: suspend () -> T): Result<T?> {
        val job = detached.async { runCatching { block() } }
        val away = detached.async { while (!p.isAway()) delay(AWAY_POLL_MS); true }
        val got: Result<T?>? = try {
            withTimeoutOrNull(budgetMs.coerceAtLeast(1)) {
                select<Result<T?>> {
                    job.onAwait { r -> r.fold({ Result.success<T?>(it) }, { Result.failure<T?>(it) }) }
                    away.onAwait { Result.failure<T?>(WentAway()) }
                }
            }
        } catch (e: CancellationException) {
            job.cancel()
            away.cancel()
            throw e
        }
        away.cancel()
        if (got == null || got.exceptionOrNull() is WentAway) job.cancel()
        return got ?: Result.success(null)
    }

    private val detached = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        /** How often a running round asks again whether a phone is away. */
        const val AWAY_POLL_MS = 200L

        /** Peers (sorted by name) alternate ALT_SOLVER, SOLVER, ...; the coordinator itself is always SOLVER. */
        fun roleForPeer(index: Int): Role = if (index % 2 == 0) Role.ALT_SOLVER else Role.SOLVER

        fun seedFor(taskId: String, nodeId: String): Int = ("$taskId|$nodeId").hashCode() and 0x7fffffff

        fun known(kind: Kind, problem: Problem, answer: String): Boolean? =
            if (problem.answer.isEmpty()) null else Normalizer.same(kind, answer, problem.answer)

        fun matrix(kind: Kind, named: List<Pair<String, String>>): Matrix =
            Matrix(named.map { it.first }, named.map { a -> named.map { b -> Normalizer.same(kind, a.second, b.second) } })

        /** Shorten key_steps until the request fits in 1 KB. */
        fun fitRevise(r: ReviseRequest): ReviseRequest {
            var m = r.copy(others = r.others.map { it.fitWire() })
            for (keep in listOf(2, 1, 0)) {
                if (wireBytes(m) <= ChoirLimits.WIRE_MAX_BYTES) break
                m = m.copy(others = m.others.map { it.copy(keySteps = it.keySteps.take(keep).map { s -> s.take(50) }) })
            }
            return m
        }

        fun fitJudge(r: JudgeRequest): JudgeRequest {
            var m = r.copy(candidates = r.candidates.map { it.fitWire() })
            for (keep in listOf(2, 1, 0)) {
                if (wireBytes(m) <= ChoirLimits.WIRE_MAX_BYTES) break
                m = m.copy(candidates = m.candidates.map { it.copy(keySteps = it.keySteps.take(keep).map { s -> s.take(50) }) })
            }
            return m
        }
    }
}
