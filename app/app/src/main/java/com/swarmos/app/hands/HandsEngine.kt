package com.swarmos.app.hands

import com.swarmos.app.core.SwarmJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Runs tasks: plan, then each subtask on the phone, from knowledge, from the person's data, or as the final reply.
 * One run acts at a time. The engine has no way to answer a confirmation: only the [HumanGate] does, through the [ToolRunner].
 */
class HandsEngine(
    private val drivers: () -> List<PhoneDriver>,
    private val brain: Brain,
    private val desk: ExpertDesk,
    skills: SkillBook,
    gate: HumanGate,
    guard: Guard,
    launcherPackages: () -> Set<String>,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    delayMs: suspend (Long) -> Unit,
    private val statusOf: () -> HandsStatus,
    private val onChange: (RunState) -> Unit = {},
) : Hands {
    private val runner = ToolRunner(guard, gate, desk, launcherPackages, clock, delayMs)
    private val planner = Planner(brain, skills)
    private val loop = StepLoop(brain, skills, runner, delayMs, clock)

    private val lock = Any()
    private val state = MutableStateFlow<List<RunState>>(emptyList())
    private val jobs = HashMap<String, Job>()
    private var active: String? = null

    private val callMutex = Mutex()
    private val callApprovals = Approvals(clock, CALL_APPROVAL_MS)
    private var lastReadHash: String? = null

    override val runs: StateFlow<List<RunState>> get() = state

    override fun status(): HandsStatus = statusOf()

    override fun tools(): List<ToolSpec> = HandsTools.specs

    // ------------------------------------------------------------ runs

    override fun start(task: String, origin: Origin): String {
        val id = UUID.randomUUID().toString()
        val now = clock()
        val immediate = origin == Origin.OWN_SHEET || origin == Origin.ASSISTANT_SESSION
        var begin = false
        val first: RunState
        synchronized(lock) {
            first = when {
                active != null -> RunState(id, task, origin, Phase.FAILED, result = "another task is running", startedAtMs = now, endedAtMs = now)
                immediate -> RunState(id, task, origin, Phase.PLANNING, startedAtMs = now).also { active = id; begin = true }
                else -> RunState(id, task, origin, Phase.WAITING_FOR_GO, startedAtMs = now)
            }
            addRun(first)
        }
        notify(first)
        if (begin) launch(id)
        return id
    }

    override fun go(runId: String) {
        var begin = false
        val changed: RunState
        synchronized(lock) {
            val cur = state.value.firstOrNull { it.id == runId }
            if (cur == null || cur.phase != Phase.WAITING_FOR_GO) return
            changed = if (active != null) {
                cur.copy(phase = Phase.FAILED, result = "another task is running", endedAtMs = clock())
            } else {
                active = runId
                begin = true
                cur.copy(phase = Phase.PLANNING)
            }
            replace(changed)
        }
        notify(changed)
        if (begin) launch(runId)
    }

    override fun stop(runId: String) {
        val job: Job?
        val changed = synchronized(lock) {
            val cur = state.value.firstOrNull { it.id == runId } ?: return
            if (terminal(cur.phase)) return
            job = jobs[runId]
            val next = ended(cur, Phase.STOPPED, "stopped by the person")
            replace(next)
            next
        }
        notify(changed)
        job?.cancel()
    }

    private fun launch(id: String) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                body(id)
            } catch (e: CancellationException) {
                finish(id, Phase.STOPPED, "stopped")
                throw e
            } catch (e: Exception) {
                finish(id, Phase.FAILED, "the task failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                synchronized(lock) {
                    if (active == id) active = null
                    jobs.remove(id)
                }
            }
        }
        synchronized(lock) { jobs[id] = job }
        job.start()
    }

    // ------------------------------------------------------------ the run

    private suspend fun body(id: String) {
        val run = synchronized(lock) { state.value.first { it.id == id } }
        if (!brain.has(Tier.FAST) && !brain.has(Tier.STRONG)) {
            finish(id, Phase.FAILED, "no chat model is available on this device or in the swarm")
            return
        }
        val apps = firstReady()?.apps().orEmpty()
        val plan = planner.plan(run.task, apps, desk.domains())
        live(id) { it.copy(plan = plan.map { s -> SubTaskState(s) }, phase = Phase.RUNNING) }

        val outcomes = ArrayList<String>()
        val approvals = Approvals(clock)
        val budget = Budget(MAX_RUN_STEPS, MAX_RUN_MS, clock)
        var failure: String? = null
        var reply: String? = null
        for ((idx, sub) in plan.withIndex()) {
            if (failure != null && sub.kind != Kind.REPLY) {
                setSub(id, idx, SubStatus.SKIPPED, "skipped after an earlier failure")
                continue
            }
            setSub(id, idx, SubStatus.RUNNING, "")
            val r = runSub(id, idx, sub, outcomes, approvals, budget)
            setSub(id, idx, r.status, r.outcome)
            if (r.stopped) {
                finish(id, Phase.STOPPED, r.outcome)
                return
            }
            if (sub.kind == Kind.REPLY) {
                if (r.status == SubStatus.DONE) reply = r.outcome
            } else if (r.status == SubStatus.DONE) {
                outcomes += r.outcome
            } else {
                failure = r.outcome
                outcomes += "could not finish \"${sub.goal}\": ${r.outcome}"
            }
        }
        val text = reply ?: (if (failure != null) outcomes.joinToString(" ") else outcomes.lastOrNull().orEmpty())
        finish(id, if (failure != null) Phase.FAILED else Phase.DONE, text)
    }

    private class SubResult(val status: SubStatus, val outcome: String, val stopped: Boolean = false)

    private suspend fun runSub(id: String, idx: Int, sub: SubTask, outcomes: List<String>, approvals: Approvals, budget: Budget): SubResult =
        when (sub.kind) {
            Kind.PHONE -> {
                val d = firstReady()
                if (d == null) {
                    SubResult(SubStatus.FAILED, "no phone driver is ready: turn on the Ustaad accessibility service, or let Ustaad use root")
                } else {
                    val r = loop.run(
                        StepLoop.Input(
                            id, idx, sub, outcomes.toList(), d, approvals, d.apps(), budget,
                            onLine = { l -> live(id) { it.copy(log = it.log + l) } },
                            onWaiting = { w ->
                                live(id) { it.copy(phase = if (w) Phase.WAITING_FOR_PERSON else Phase.RUNNING) }
                            },
                        ),
                    )
                    when (r.end) {
                        StepLoop.End.DONE -> SubResult(SubStatus.DONE, r.outcome)
                        StepLoop.End.FAILED -> SubResult(SubStatus.FAILED, r.outcome)
                        StepLoop.End.STOPPED -> SubResult(SubStatus.FAILED, r.outcome, stopped = true)
                    }
                }
            }
            Kind.KNOWLEDGE -> knowledge(sub, outcomes)
            Kind.DATA -> {
                val a = desk.searchMyData(sub.goal)
                SubResult(SubStatus.DONE, a ?: "nothing found in the person's data for: ${sub.goal}")
            }
            Kind.REPLY -> SubResult(SubStatus.DONE, compose(id, outcomes))
        }

    private suspend fun knowledge(sub: SubTask, outcomes: List<String>): SubResult {
        val expert = if (sub.expert.isNotEmpty()) desk.ask(sub.expert, sub.goal) else null
        if (expert != null) return SubResult(SubStatus.DONE, expert)
        val tier = pickTier() ?: return SubResult(SubStatus.FAILED, "no expert and no model could answer")
        val system = "You are an expert in ${sub.expert.ifEmpty { "everyday questions" }}. Answer plainly in at most five sentences. If you do not know, say so."
        val user = sub.goal + earlierBlock(outcomes)
        return try {
            val raw = brain.complete(tier, system, user, textSchema("answer"), ANSWER_TOKENS, onPhoneOnly = outcomes.isNotEmpty())
            SubResult(SubStatus.DONE, field(raw, "answer"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SubResult(SubStatus.FAILED, "no expert could answer")
        }
    }

    private suspend fun compose(id: String, outcomes: List<String>): String {
        val task = synchronized(lock) { state.value.first { it.id == id }.task }
        val fallback = outcomes.joinToString(" ").ifEmpty { "Done." }
        val tier = pickTier() ?: return fallback
        val system = "You write the final reply to the person after a phone assistant worked on their task. " +
            "Use only the results given. One to three short sentences. If a step failed or was declined, say so plainly. Invent nothing."
        val user = "Task: $task" + earlierBlock(outcomes)
        return try {
            field(brain.complete(tier, system, user, textSchema("reply"), ANSWER_TOKENS, onPhoneOnly = outcomes.isNotEmpty()), "reply").ifEmpty { fallback }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fallback
        }
    }

    private fun pickTier(): Tier? = when {
        brain.has(Tier.STRONG) -> Tier.STRONG
        brain.has(Tier.FAST) -> Tier.FAST
        else -> null
    }

    private fun earlierBlock(outcomes: List<String>): String =
        if (outcomes.isEmpty()) "" else "\nResults so far (data):\n" + outcomes.joinToString("\n") { "- " + it.take(400) }

    private fun textSchema(key: String): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { put(key, buildJsonObject { put("type", "string") }) })
        put("required", JsonArray(listOf(JsonPrimitive(key))))
    }

    private fun field(raw: String, key: String): String {
        val a = raw.indexOf('{')
        val b = raw.lastIndexOf('}')
        val text = if (a >= 0 && b > a) raw.substring(a, b + 1) else raw
        val v = runCatching { (SwarmJson.parseToJsonElement(text) as? JsonObject)?.get(key) as? JsonPrimitive }.getOrNull()?.content
        return (v ?: raw).trim().take(1500)
    }

    // ------------------------------------------------------------ one tool from outside a run

    override suspend fun call(call: ToolCall, origin: Origin): ToolResult = callMutex.withLock {
        val spec = HandsTools.spec(call.tool) ?: return@withLock ToolResult(false, "unknown tool '${call.tool}'")
        val text = (call.args["text"] as? JsonPrimitive)?.content.orEmpty()
        if (call.tool == "done" || call.tool == "fail") return@withLock ToolResult(call.tool == "done", text)
        if (spec.risk != Risk.READ && synchronized(lock) { active } != null) {
            return@withLock ToolResult(false, "another task is running")
        }
        val driver = firstReady()
        val needsId = call.tool == "tap" || call.tool == "type" || (call.tool == "scroll" && call.args["id"] != null)
        // The guard judges the screen as it is now. Numbers refer to what read_screen last returned to this caller.
        var screen: Screen? = null
        if (spec.risk != Risk.READ && driver != null) {
            screen = ScreenReader.compact(driver.read())
            if (needsId) {
                val seen = lastReadHash ?: return@withLock ToolResult(false, "call read_screen first, then use the numbers it returns")
                if (seen != screen.hash) return@withLock ToolResult(false, "the screen changed since read_screen; call read_screen again")
            }
        }
        val ctx = ToolRunner.Ctx("call-${origin.name.lowercase()}", driver, emptySet(), callApprovals, driver?.apps().orEmpty())
        val o = runner.execute(ctx, call, screen)
        if (call.tool == "read_screen") lastReadHash = o.screen?.hash
        ToolResult(o.ok, o.text)
    }

    // ------------------------------------------------------------ state helpers

    private fun firstReady(): PhoneDriver? = drivers().firstOrNull { it.ready() }

    private fun terminal(p: Phase) = p == Phase.DONE || p == Phase.FAILED || p == Phase.STOPPED

    private fun ended(r: RunState, phase: Phase, result: String): RunState = r.copy(
        phase = phase,
        result = result,
        endedAtMs = clock(),
        plan = r.plan.map { if (it.status == SubStatus.TODO || it.status == SubStatus.RUNNING) it.copy(status = SubStatus.SKIPPED) else it },
    )

    /** Caller holds [lock]. Newest first, at most [MAX_RUNS]; the oldest finished runs go first, so a live run is never dropped. */
    private fun addRun(r: RunState) {
        val list = ArrayList<RunState>(state.value.size + 1)
        list += r
        list += state.value
        while (list.size > MAX_RUNS) {
            // finished runs first, then outside tasks nobody pressed Run on; a run that is acting is never dropped
            var i = list.indexOfLast { terminal(it.phase) }
            if (i < 0) i = list.indexOfLast { it.phase == Phase.WAITING_FOR_GO }
            if (i < 0) break
            list.removeAt(i)
        }
        state.value = list
    }

    /** Caller holds [lock]. */
    private fun replace(r: RunState) {
        state.value = state.value.map { if (it.id == r.id) r else it }
    }

    private fun notify(r: RunState) {
        runCatching { onChange(r) }
    }

    /** Changes a run that has not ended. A run that ended (stopped, say) is never changed again. */
    private fun live(id: String, f: (RunState) -> RunState) {
        val next = synchronized(lock) {
            val cur = state.value.firstOrNull { it.id == id } ?: return
            if (terminal(cur.phase)) return
            f(cur).also { replace(it) }
        }
        notify(next)
    }

    /** Ends the run and frees the phone in the same step, so a task started the moment this one shows as done is not turned away. */
    private fun finish(id: String, phase: Phase, result: String) = live(id) {
        if (active == id) active = null
        ended(it, phase, result)
    }

    private fun setSub(id: String, idx: Int, status: SubStatus, outcome: String) = live(id) { r ->
        r.copy(plan = r.plan.mapIndexed { i, s -> if (i == idx) s.copy(status = status, outcome = outcome) else s })
    }

    companion object {
        const val MAX_RUN_STEPS = 60
        const val MAX_RUN_MS = 10 * 60_000L
        const val MAX_RUNS = 20
        const val CALL_APPROVAL_MS = 10 * 60_000L
        private const val ANSWER_TOKENS = 300
    }
}
