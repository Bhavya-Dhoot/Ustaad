package com.swarmos.app.hands

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs

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
 * What a run (or the calls from outside) remembers between steps: the field the last step typed into, and the codes that were
 * hidden on screens, so that an answer that repeats one does not give it away.
 */
class StepMemory {
    @Volatile var lastTyped: String? = null
    private val codes = LinkedHashSet<String>()

    @Synchronized
    fun remember(found: Collection<String>) {
        codes.addAll(found)
    }

    /** Text the model wrote that goes to the person, the log or another device: a code it saw is hidden. */
    @Synchronized
    fun scrub(text: String): String {
        var s = text
        for (c in codes) s = s.replace(c, "****")
        return if (GuardWords.CODE.matches(GuardText.norm(s))) GuardText.maskCodes(s) else s
    }
}

/**
 * Carries out one tool call: the guard decides first, the person is asked when it says Confirm, and only then a driver acts.
 * This is the only place in the core that calls [HumanGate.confirm], and it acts only on that answer.
 * An action on an element is done on the element as it is found again in a fresh read, never on a handle from an earlier read.
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
        /** The task the person gave: a link written in it needs no question. */
        val task: String = "",
        val memory: StepMemory = StepMemory(),
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

    /** A read of the phone as the model and the log may see it: a protected screen has no elements. */
    class Looked(val screen: Screen, val areas: Map<Int, Long>)

    suspend fun look(ctx: Ctx): Looked {
        val d = ctx.driver ?: throw IllegalStateException("no phone driver")
        val full = ScreenReader.compactFull(d.read())
        ctx.memory.remember(full.masked)
        val shown = guard.shield(full.screen, ctx.apps)
        return Looked(shown, if (shown === full.screen) full.areas else emptyMap())
    }

    suspend fun execute(ctx: Ctx, call0: ToolCall, screen: Screen?): Outcome {
        var call = call0
        if (call.tool == "open_app") {
            val asked = str(call, "app")
            val hit = AppMatch.resolve(asked, ctx.apps)
            if (hit == null) return Outcome(Verdict.ALLOWED, false, "no installed app matches '$asked'; use find_app", asked.take(60))
            call = ToolCall("open_app", JsonObject(mapOf("app" to JsonPrimitive(hit.pkg))))
        }
        val tool = call.tool
        val driver = ctx.driver
        val id = (call.args["id"] as? JsonPrimitive)?.content?.toIntOrNull()
        val element = if (id != null && tool != "wait") screen?.elements?.firstOrNull { it.id == id } else null
        val args = argsText(call, element)
        val uri = if (tool == "open_link") str(call, "text").trim() else ""
        val linkTarget: String? = if (tool == "open_link" && driver != null && HandsTools.linkAllowed(uri)) {
            try { driver.linkTarget(uri) } catch (e: CancellationException) { throw e } catch (e: Exception) { "" }
        } else {
            null
        }

        fun judge(s: Screen?, e: UiElement?): Decision =
            guard.check(call, s, e, ctx.planApps, ctx.approvals.current(), launcherPackages(), Guard.Context(ctx.apps, ctx.memory.lastTyped, ctx.task, linkTarget))

        fun refused(why: String, e: UiElement?) = Outcome(Verdict.REFUSED, false, "refused: $why", args, action = actionText(call, e, ctx, uri))
        fun changed(v: Verdict, e: UiElement?) = Outcome(v, false, "the screen changed; nothing was done", args, action = actionText(call, e, ctx, uri))

        var decision = judge(screen, element)
        if (decision is Decision.Refuse) return refused(decision.why, element)

        // 1. the element is found again in a fresh read; what is judged and what is acted on is that fresh element
        var target = element
        var seen = screen
        var scrollTo: String? = null
        val revalidate = driver != null && tool in REVALIDATED
        if (revalidate) {
            val first = look(ctx)
            target = if (element != null) refind(element, screen, first.screen) else null
            if (element != null && target == null) return changed(Verdict.ALLOWED, element)
            val again = judge(first.screen, target)
            if (again is Decision.Refuse) return refused(again.why, target)
            if (again is Decision.Confirm || decision is Decision.Allow) decision = again
            seen = first.screen
            if (tool == "scroll" && id == null) scrollTo = pickScroll(first)
        }

        // 2. a Confirm is the person's tap, nothing else; afterwards the element is found once more
        var verdict = Verdict.ALLOWED
        if (decision is Decision.Confirm) {
            val action = actionText(call, target, ctx, uri)
            val prompt = ConfirmPrompt(ctx.runId, appLabel(ctx, seen?.pkg.orEmpty()), action, decision.why)
            if (!waitForPerson(ctx) { gate.confirm(prompt) }) {
                return Outcome(Verdict.DECLINED, false, "the person said no: $action", args, action = action)
            }
            verdict = Verdict.CONFIRMED
            if (decision.app.isNotEmpty()) ctx.approvals.add(decision.app)
            if (revalidate) {
                val second = look(ctx)
                val moved = if (target != null) refind(target, seen, second.screen) else null
                if (target != null && moved == null) return changed(Verdict.CONFIRMED, target)
                val after = judge(second.screen, moved)
                if (after is Decision.Refuse) return refused(after.why, moved)
                target = moved
                if (tool == "scroll" && id == null) scrollTo = pickScroll(second)
            }
        }

        val (ok, text, read) = try {
            perform(ctx, call, target, scrollTo, uri, linkTarget.orEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Triple(false, "the phone refused: ${e.message}", null)
        }
        if (ok) {
            val spec = HandsTools.spec(tool)
            if (tool == "type") ctx.memory.lastTyped = target?.guard
            else if (spec != null && spec.risk != Risk.READ) ctx.memory.lastTyped = null
        }
        return Outcome(verdict, ok, text, args, read, actionText(call, target, ctx, uri))
    }

    /** The same control in a fresh screen: same app, role, flags and guard text, its centre within [NEAR] pixels. Null when it is not there. */
    private fun refind(orig: UiElement, origScreen: Screen?, fresh: Screen): UiElement? {
        if (fresh.unreadable) return null
        if (origScreen != null && origScreen.pkg != fresh.pkg) return null
        return fresh.elements
            .filter { it.role == orig.role && it.flags == orig.flags && it.guard == orig.guard && abs(it.cx - orig.cx) <= NEAR && abs(it.cy - orig.cy) <= NEAR }
            .minByOrNull { abs(it.cx - orig.cx) + abs(it.cy - orig.cy) }
    }

    /** The scrollable element with the largest area that is not a slider or a commit; null means a plain swipe. */
    private fun pickScroll(l: Looked): String? = l.screen.elements
        .filter { Flag.SCROLLABLE in it.flags && !Guard.movesOrCommits(it.guard) }
        .maxByOrNull { l.areas[it.id] ?: 0L }?.handle

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

    private suspend fun perform(ctx: Ctx, call: ToolCall, el: UiElement?, scrollTo: String?, uri: String, linkTarget: String): Triple<Boolean, String, Screen?> {
        val d = ctx.driver
        val tool = call.tool
        if (tool in NEEDS_DRIVER && d == null) return Triple(false, "no phone driver is ready", null)
        fun r(ok: Boolean, good: String, bad: String) = Triple(ok, if (ok) good else bad, null as Screen?)
        return when (tool) {
            "read_screen" -> {
                val s = look(ctx).screen
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
                r(d!!.scroll(el?.handle ?: scrollTo, fwd), "scrolled ${if (fwd) "down" else "up"}", "could not scroll")
            }
            "back" -> r(d!!.back(), "went back", "back failed")
            "home" -> r(d!!.home(), "went home", "home failed")
            "open_app" -> {
                val pkg = str(call, "app")
                r(d!!.openApp(pkg), "opened ${appLabel(ctx, pkg)}", "could not open ${appLabel(ctx, pkg)}")
            }
            "open_link" -> {
                if (!HandsTools.linkAllowed(uri) || linkTarget.isEmpty()) Triple(false, "refused: this link is not allowed", null)
                else r(d!!.openLink(uri, linkTarget), "opened the link in ${appLabel(ctx, linkTarget)}", "could not open the link")
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
                val a = waitForPerson(ctx) { gate.ask(ctx.runId, ctx.memory.scrub(str(call, "text"))) }
                if (a == null) Triple(false, "the person dismissed the question", null) else Triple(true, "the person answered: $a", null)
            }
            "ask_expert" -> {
                val a = desk.ask(str(call, "app"), ctx.memory.scrub(str(call, "text")))
                if (a == null) Triple(false, "no expert could answer", null) else Triple(true, a, null)
            }
            "search_my_data" -> {
                val a = desk.searchMyData(ctx.memory.scrub(str(call, "text")))
                if (a == null) Triple(true, "nothing found in the person's data", null) else Triple(true, a, null)
            }
            "done", "fail" -> Triple(true, ctx.memory.scrub(str(call, "text")), null)
            else -> Triple(false, "unknown tool '$tool'", null)
        }
    }

    private fun str(call: ToolCall, key: String) = (call.args[key] as? JsonPrimitive)?.content.orEmpty()

    private fun nameOf(el: UiElement?, call: ToolCall): String = if (el == null) str(call, "id") else el.label.ifEmpty { el.guard }

    private fun appLabel(ctx: Ctx, pkg: String) = ctx.apps.firstOrNull { it.pkg == pkg }?.label ?: pkg.ifEmpty { "this app" }

    /** Arguments for the log. The text typed into a password field or a secret-looking field is never written. */
    private fun argsText(call: ToolCall, el: UiElement?): String = when (call.tool) {
        "tap" -> el?.let { "${it.id} \"${it.label}\"" } ?: "id ${str(call, "id")}"
        "type" -> {
            val hide = el != null && (Flag.PASSWORD in el.flags || Guard.isSecretLabel(el.guard) || Guard.isSecretLabel(el.label))
            "${str(call, "id")} \"${if (hide) "<hidden>" else str(call, "text").take(60)}\""
        }
        "scroll" -> str(call, "direction") + (call.args["id"]?.let { " in ${str(call, "id")}" } ?: "")
        "wait" -> "${str(call, "id")} s"
        "open_app" -> str(call, "app")
        else -> call.args["text"]?.let { str(call, "text").take(80) } ?: ""
    }

    /** What the person is asked about. A link is shown in full, so nothing hides at its end. */
    private fun actionText(call: ToolCall, el: UiElement?, ctx: Ctx, uri: String): String = when (call.tool) {
        "tap" -> "tap \"${nameOf(el, call)}\""
        "type" -> "type into \"${nameOf(el, call)}\""
        "press_enter" -> "press enter"
        "scroll" -> "scroll ${str(call, "direction")}"
        "back" -> "go back"
        "home" -> "go to the home screen"
        "open_app" -> "open ${appLabel(ctx, str(call, "app"))}"
        "open_link" -> "open the link $uri"
        else -> call.tool
    }

    companion object {
        /** How far, in pixels, the centre of a control may have moved between two reads and still be the same control. */
        const val NEAR = 24

        /** Tools whose effect depends on what is on the screen right now: judged and acted on after a fresh read. */
        private val REVALIDATED = setOf("tap", "type", "press_enter", "scroll")
        private val NEEDS_DRIVER = setOf("read_screen", "tap", "type", "press_enter", "scroll", "back", "home", "open_app", "open_link", "find_app")
    }
}
