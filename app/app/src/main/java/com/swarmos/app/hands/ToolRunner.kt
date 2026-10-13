package com.swarmos.app.hands

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The apps the person said yes to. A run keeps them until it ends; calls from outside a run keep them for ten minutes. */
class Approvals(private val clock: () -> Long, private val ttlMs: Long = Long.MAX_VALUE) {
    private val at = HashMap<String, Long>()

    @Synchronized
    fun add(pkg: String) {
        at[pkg] = clock()
    }

    @Synchronized
    fun current(): Set<String> {
        val now = clock()
        at.entries.removeAll { now - it.value > ttlMs }
        return at.keys.toSet()
    }
}

/** Steps and time one run may use. Time the person spends deciding is not counted against the run. */
class Budget(val maxSteps: Int, private val maxMs: Long, private val clock: () -> Long) {
    private val start = clock()
    @Volatile var steps = 0
    @Volatile private var pausedMs = 0L

    fun pause(ms: Long) {
        pausedMs += ms.coerceAtLeast(0)
    }

    /** Why the run must stop now, or null. */
    fun exhausted(): String? = when {
        steps >= maxSteps -> "the run used its $maxSteps steps"
        clock() - start - pausedMs >= maxMs -> "the run used its ${maxMs / 60_000} minutes"
        else -> null
    }
}

/**
 * Carries out one tool call: the guard decides first, the person is asked when it says Confirm, and only then a driver acts.
 * This is the only place in the core that calls [HumanGate.confirm], and it acts only on that answer.
 */
class ToolRunner(
    private val guard: Guard,
    private val gate: HumanGate,
    private val desk: ExpertDesk,
    private val launcherPackages: () -> Set<String>,
    private val clock: () -> Long,
    private val delayMs: suspend (Long) -> Unit,
) {
    class Ctx(
        val runId: String,
        val driver: PhoneDriver?,
        val planApps: Set<String>,
        val approvals: Approvals,
        val apps: List<AppInfo>,
        val budget: Budget? = null,
        /** True while the person is being asked, so the run can show that it waits. */
        val onWaiting: (Boolean) -> Unit = {},
    )

    class Outcome(
        val verdict: Verdict,
        val ok: Boolean,
        val text: String,
        /** The arguments as shown to the person and written to the log. */
        val args: String,
        /** The screen a read_screen call returned. */
        val screen: Screen? = null,
        /** What the person was asked, when they were. */
        val action: String = "",
    )

    suspend fun execute(ctx: Ctx, call0: ToolCall, screen: Screen?): Outcome {
        var call = call0
        if (call.tool == "open_app") {
            val asked = str(call, "app")
            val hit = AppMatch.resolve(asked, ctx.apps)
            if (hit == null) return Outcome(Verdict.ALLOWED, false, "no installed app matches '$asked'; use find_app", asked.take(60))
            call = ToolCall("open_app", JsonObject(mapOf("app" to JsonPrimitive(hit.pkg))))
        }
        val id = (call.args["id"] as? JsonPrimitive)?.content?.toIntOrNull()
        val element = if (id != null && call.tool != "wait") screen?.elements?.firstOrNull { it.id == id } else null
        val args = argsText(call, element)
        val action = actionText(call, element, ctx)

        val decision = guard.check(call, screen, element, ctx.planApps, ctx.approvals.current(), launcherPackages())
        var verdict = Verdict.ALLOWED
        when (decision) {
            is Decision.Refuse -> return Outcome(Verdict.REFUSED, false, "refused: ${decision.why}", args, action = action)
            is Decision.Confirm -> {
                val prompt = ConfirmPrompt(ctx.runId, appLabel(ctx, screen?.pkg.orEmpty()), action, decision.why)
                if (!waitForPerson(ctx) { gate.confirm(prompt) }) {
                    return Outcome(Verdict.DECLINED, false, "the person said no: $action", args, action = action)
                }
                verdict = Verdict.CONFIRMED
                if (decision.app.isNotEmpty()) ctx.approvals.add(decision.app)
                // The person may have taken minutes. What they approved was an action on THIS screen.
                if (screen != null && ctx.driver != null && call.tool in SCREEN_BOUND) {
                    val fresh = ScreenReader.compact(ctx.driver.read())
                    if (fresh.hash != screen.hash) {
                        return Outcome(verdict, false, "the screen changed while the person was asked; nothing was done", args, action = action)
                    }
                }
            }
            Decision.Allow -> {}
        }
        val (ok, text, read) = try {
            perform(ctx, call, element)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Triple(false, "the phone refused: ${e.message}", null)
        }
        return Outcome(verdict, ok, text, args, read, action)
    }

    private suspend fun <T> waitForPerson(ctx: Ctx, block: suspend () -> T): T {
        val t0 = clock()
        ctx.onWaiting(true)
        try {
            return block()
        } finally {
            ctx.onWaiting(false)
            ctx.budget?.pause(clock() - t0)
        }
    }

    private suspend fun perform(ctx: Ctx, call: ToolCall, el: UiElement?): Triple<Boolean, String, Screen?> {
        val d = ctx.driver
        val tool = call.tool
        if (tool in NEEDS_DRIVER && d == null) return Triple(false, "no phone driver is ready", null)
        fun r(ok: Boolean, good: String, bad: String) = Triple(ok, if (ok) good else bad, null as Screen?)
        return when (tool) {
            "read_screen" -> {
                val s = ScreenReader.compact(d!!.read())
                Triple(true, ScreenReader.render(s), s)
            }
            "tap" -> r(d!!.tap(el!!.handle, el.cx, el.cy), "tapped ${el.id} \"${el.label}\"", "the tap failed")
            "type" -> {
                val t = str(call, "text")
                r(d!!.setText(el!!.handle, el.cx, el.cy, t), "typed into ${el.id}", "typing failed")
            }
            "press_enter" -> r(d!!.enter(), "pressed enter", "enter failed")
            "scroll" -> {
                val fwd = str(call, "direction") != "up"
                r(d!!.scroll(el?.handle, fwd), "scrolled ${if (fwd) "down" else "up"}", "could not scroll")
            }
            "back" -> r(d!!.back(), "went back", "back failed")
            "home" -> r(d!!.home(), "went home", "home failed")
            "open_app" -> {
                val pkg = str(call, "app")
                r(d!!.openApp(pkg), "opened ${appLabel(ctx, pkg)}", "could not open ${appLabel(ctx, pkg)}")
            }
            "open_link" -> {
                val uri = str(call, "text").trim()
                if (!HandsTools.linkAllowed(uri)) Triple(false, "refused: this link is not allowed", null)
                else r(d!!.openLink(uri), "opened the link", "could not open the link")
            }
            "find_app" -> {
                val q = str(call, "text").trim()
                val hits = d!!.apps().filter { it.label.contains(q, ignoreCase = true) || it.pkg.contains(q, ignoreCase = true) }.take(10)
                Triple(true, if (hits.isEmpty()) "no installed app matches '$q'" else hits.joinToString("; ") { "${it.label} (${it.pkg})" }, null)
            }
            "wait" -> {
                val sec = ((call.args["id"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 1).coerceIn(1, HandsTools.MAX_WAIT_SECONDS)
                delayMs(sec * 1000L)
                Triple(true, "waited $sec s", null)
            }
            "ask_user" -> {
                val a = waitForPerson(ctx) { gate.ask(ctx.runId, str(call, "text")) }
                if (a == null) Triple(false, "the person dismissed the question", null) else Triple(true, "the person answered: $a", null)
            }
            "ask_expert" -> {
                val a = desk.ask(str(call, "app"), str(call, "text"))
                if (a == null) Triple(false, "no expert could answer", null) else Triple(true, a, null)
            }
            "search_my_data" -> {
                val a = desk.searchMyData(str(call, "text"))
                if (a == null) Triple(true, "nothing found in the person's data", null) else Triple(true, a, null)
            }
            "done", "fail" -> Triple(true, str(call, "text"), null)
            else -> Triple(false, "unknown tool '$tool'", null)
        }
    }

    private fun str(call: ToolCall, key: String) = (call.args[key] as? JsonPrimitive)?.content.orEmpty()

    private fun appLabel(ctx: Ctx, pkg: String) = ctx.apps.firstOrNull { it.pkg == pkg }?.label ?: pkg.ifEmpty { "this app" }

    /** Arguments for the log. The text typed into a password field or a secret-looking field is never written. */
    private fun argsText(call: ToolCall, el: UiElement?): String = when (call.tool) {
        "tap" -> el?.let { "${it.id} \"${it.label}\"" } ?: "id ${str(call, "id")}"
        "type" -> {
            val hide = el != null && (Flag.PASSWORD in el.flags || Guard.isSecretLabel(el.label))
            "${str(call, "id")} \"${if (hide) "<hidden>" else str(call, "text").take(60)}\""
        }
        "scroll" -> str(call, "direction") + (call.args["id"]?.let { " in ${str(call, "id")}" } ?: "")
        "wait" -> "${str(call, "id")} s"
        "open_app" -> str(call, "app")
        else -> call.args["text"]?.let { str(call, "text").take(80) } ?: ""
    }

    private fun actionText(call: ToolCall, el: UiElement?, ctx: Ctx): String = when (call.tool) {
        "tap" -> "tap \"${el?.label ?: str(call, "id")}\""
        "type" -> "type into \"${el?.label ?: str(call, "id")}\""
        "press_enter" -> "press enter"
        "scroll" -> "scroll ${str(call, "direction")}"
        "back" -> "go back"
        "home" -> "go to the home screen"
        "open_app" -> "open ${appLabel(ctx, str(call, "app"))}"
        "open_link" -> "open the link ${str(call, "text").take(80)}"
        else -> call.tool
    }

    companion object {
        /** Tools whose effect depends on what is on the screen right now. */
        private val SCREEN_BOUND = setOf("tap", "type", "press_enter", "scroll", "back")
        private val NEEDS_DRIVER = setOf("read_screen", "tap", "type", "press_enter", "scroll", "back", "home", "open_app", "open_link", "find_app")
    }
}
