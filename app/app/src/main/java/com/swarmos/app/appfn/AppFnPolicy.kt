package com.swarmos.app.appfn

import com.swarmos.app.assist.AssistIntents
import com.swarmos.app.hands.ExpertDesk
import com.swarmos.app.hands.Hands
import com.swarmos.app.hands.Origin
import com.swarmos.app.hands.Phase
import com.swarmos.app.hands.RunState
import com.swarmos.app.hands.SubStatus
import com.swarmos.app.hands.android.ExpertCard
import com.swarmos.app.hands.android.RunLauncher

/**
 * Why a call to a Ustaad app function was refused. The service shell turns it into the library's exception class; it lives here so the
 * policy stays plain Kotlin that the unit tests can run (the library's exceptions build an Android Bundle).
 */
class AppFnRefusal(val kind: Kind, message: String) : Exception(message) {
    enum class Kind {
        /** Blank, over-long or malformed input. */
        INVALID_ARGUMENT,

        /** No such task or expert domain. */
        NOT_FOUND,

        /** An owner's switch is off. The message names it. */
        DENIED,

        /** Another task is running (the engine's own message). */
        LIMIT,

        /** Ustaad is still starting, or no model could answer. */
        UNAVAILABLE,
    }
}

/**
 * Every decision of the functions the phone's assistant may call, so that the service class is a thin shell. Plain Kotlin, no Android.
 *
 * What this class can do and what it cannot, on purpose. It starts a task with [Origin.APP_FUNCTION], reads a task it started, stops a
 * task, asks the expert desk and looks into the owner's data on this phone only. It holds no [com.swarmos.app.hands.HumanGate], so it
 * cannot answer a question Ustaad puts to the owner, and it holds only readers of the two switches, so it cannot change one. It calls
 * `go` on a task in exactly one place, and only when the owner's start switch is on; with the switch off the task waits for the
 * owner's tap on Run in Ustaad and [tellOwner] says so.
 *
 * A task is visible to the assistant that started it and to no other: an id of a task that came from another way in is "not found".
 */
class AppFnPolicy(
    /** Hands, or null while Ustaad is still starting. */
    private val hands: suspend () -> Hands?,
    private val desk: () -> ExpertDesk?,
    private val experts: () -> List<ExpertCard>,
    /** This phone's own index only; never the ask across devices. Null when nothing was found. */
    private val localData: suspend (String) -> String?,
    /** "The phone's assistant may start tasks". */
    private val mayStart: () -> Boolean,
    /** "The phone's assistant may search my data". */
    private val maySearchData: () -> Boolean,
    /** The way the UI starts a run (starts the foreground service first). */
    private val launcher: RunLauncher,
    /** Tells the owner a task waits for their tap. Gets the run id. */
    private val tellOwner: (String) -> Unit,
) {
    // ------------------------------------------------------------ startTask

    suspend fun startTask(rawTask: String): String {
        val task = text(rawTask, "task")
        val h = hands() ?: throw notReady()
        // The engine never starts a run of this origin by itself: it waits in WAITING_FOR_GO. With the switch off the run is only created
        // (no foreground service is started for a task nobody has accepted); with it on, the launcher does what the task sheet does.
        val id = if (mayStart()) launcher.start(h, task, Origin.APP_FUNCTION) else h.start(task, Origin.APP_FUNCTION)
        val created = h.runs.value.firstOrNull { it.id == id }
        // the engine refuses a task while one is running: it records a failed run that holds its own message
        if (created == null || created.phase == Phase.FAILED) {
            throw AppFnRefusal(AppFnRefusal.Kind.LIMIT, created?.result?.ifBlank { null } ?: ANOTHER_TASK)
        }
        // the switch is read again here: this is the only call of go in the class
        if (!mayStart()) {
            tellOwner(id)
            return "$id\n$WAITS_FOR_OWNER"
        }
        launcher.go(h, id)
        val now = h.runs.value.firstOrNull { it.id == id } ?: created
        return "$id\n${startedLine(now)}"
    }

    // ------------------------------------------------------------ taskStatus, stopTask

    suspend fun taskStatus(rawId: String): String {
        val run = ownRun(rawId)
        return statusText(run)
    }

    suspend fun stopTask(rawId: String): String {
        val run = ownRun(rawId)
        val h = hands() ?: throw notReady()
        if (run.phase.ended()) return "${run.id}\nAlready ${phaseWord(run.phase)}. Nothing to stop."
        h.stop(run.id)
        val after = h.runs.value.firstOrNull { it.id == run.id } ?: run
        return if (run.phase == Phase.WAITING_FOR_GO) {
            "${run.id}\nState: ${phaseWord(after.phase)}. The task never ran; the owner had not tapped Run."
        } else {
            "${run.id}\nStop sent. State: ${phaseWord(after.phase)}."
        }
    }

    /** The run for an id the assistant passed in, if the assistant started it. */
    private suspend fun ownRun(rawId: String): RunState {
        val id = parseId(rawId)
        val h = hands() ?: throw notReady()
        val run = h.runs.value.firstOrNull { it.id == id }
        // a task of another origin is not this caller's to read or stop; it looks the same as a task that does not exist
        if (run == null || run.origin != Origin.APP_FUNCTION) {
            throw AppFnRefusal(AppFnRefusal.Kind.NOT_FOUND, "no task with the id $id; use the id startTask returned")
        }
        return run
    }

    // ------------------------------------------------------------ experts and data

    suspend fun listExperts(): String {
        if (hands() == null) throw notReady()
        val cards = experts()
        if (cards.isEmpty()) return "No experts are installed on this phone."
        return cards.joinToString("\n") { c -> if (c.useFor.isBlank()) c.domain else "${c.domain}: ${c.useFor.trim()}" }
    }

    suspend fun askExpert(rawDomain: String, rawQuestion: String): String {
        val domain = text(rawDomain, "domain")
        val question = text(rawQuestion, "question")
        val d = desk() ?: throw notReady()
        // the desk keeps its own spelling of a domain; the match ignores case and surrounding spaces, as the desk's does
        val known = d.domains().firstOrNull { it.trim().equals(domain, ignoreCase = true) }
            ?: throw AppFnRefusal(AppFnRefusal.Kind.NOT_FOUND, "no expert for the domain '$domain'; listExperts names the domains")
        // The assistant wrote this question itself, so nothing Ustaad read off a screen is in it: the route is not screen-bound.
        val answer = d.ask(known, question, onPhoneOnly = false)?.trim().orEmpty()
        if (answer.isEmpty()) throw AppFnRefusal(AppFnRefusal.Kind.UNAVAILABLE, "the expert could not answer; no planning model is ready on this phone or in the swarm")
        return cut(answer, ANSWER_MAX)
    }

    suspend fun searchMyData(rawQuery: String): String {
        // checked first: with the switch off nothing about the owner's data is looked at, or confirmed to exist
        if (!maySearchData()) throw AppFnRefusal(AppFnRefusal.Kind.DENIED, "the owner has not allowed this: the switch \"$DATA_SWITCH\" is off in Ustaad, on the Hands screen")
        val query = text(rawQuery, "query")
        val found = localData(query)?.trim().orEmpty()
        if (found.isEmpty()) return "No passage found in the owner's data on this phone. The index may be empty, still starting, or busy; try again later."
        return cut(found, ANSWER_MAX)
    }

    // ------------------------------------------------------------ text

    companion object {
        const val START_SWITCH = "The phone's assistant may start tasks"
        const val DATA_SWITCH = "The phone's assistant may search my data"
        const val MAX_TEXT = AssistIntents.MAX_TASK
        const val RESULT_MAX = 1000
        const val ANSWER_MAX = 1500

        /** The text of the owner's notification. It says nothing of the task: a lock screen may show it. */
        const val NOTICE = "Your assistant sent Ustaad a task. Tap to read it and run it."

        const val WAITS_FOR_OWNER =
            "State: waiting for the owner. Ustaad holds the task and has not started it; it starts when the owner reads it and taps Run in Ustaad. " +
                "Ask taskStatus with this id to follow it."
        private const val ANOTHER_TASK = "another task is running"

        /** Characters of raw text looked at before the cleaning stops; the same bound [AssistIntents.clean] keeps for hostile text. */
        private const val MAX_RAW = 4000

        private val ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

        /**
         * Text the assistant passed in, cleaned as text from another app is ([AssistIntents.clean]). Blank after cleaning, or longer than
         * [MAX_TEXT] characters, is an invalid argument: a task is never cut to fit, because a cut task can mean something else.
         */
        fun text(raw: String, what: String): String {
            val cleaned = AssistIntents.clean(raw, MAX_TEXT + 1)
            if (cleaned.isEmpty()) throw AppFnRefusal(AppFnRefusal.Kind.INVALID_ARGUMENT, "$what is empty")
            if (cleaned.length > MAX_TEXT || raw.length > MAX_RAW) {
                throw AppFnRefusal(AppFnRefusal.Kind.INVALID_ARGUMENT, "$what is longer than $MAX_TEXT characters; say it shorter")
            }
            return cleaned
        }

        /**
         * The task id out of what the assistant passed: the first word, without quotes or brackets around it, in lower case. An id is the
         * 36-character text startTask returned on its first line. Anything else is an invalid argument; a well-formed id that names no
         * task is "not found" later.
         */
        fun parseId(raw: String): String {
            val cleaned = AssistIntents.clean(raw, 200)
            if (cleaned.isEmpty()) throw AppFnRefusal(AppFnRefusal.Kind.INVALID_ARGUMENT, "taskId is empty")
            val word = cleaned.substringBefore(' ').trim('"', '\'', '<', '>', '[', ']', '(', ')', '`', ',', '.', ';', ':').lowercase()
            if (!ID.matches(word)) {
                throw AppFnRefusal(AppFnRefusal.Kind.INVALID_ARGUMENT, "taskId must be the id startTask returned on its first line, like 123e4567-e89b-12d3-a456-426614174000")
            }
            return word
        }

        /** Whether the functions can be called on this phone: the platform has them (Android 16, API 36) and the library found its manager. */
        fun available(sdkInt: Int, managerFound: Boolean): Boolean = sdkInt >= 36 && managerFound

        /** The phase as one lower-case word or phrase, the same words in every reply. */
        fun phaseWord(phase: Phase): String = when (phase) {
            Phase.WAITING_FOR_GO -> "waiting for the owner"
            Phase.PLANNING -> "planning"
            Phase.RUNNING -> "running"
            Phase.WAITING_FOR_PERSON -> "waiting for the owner's answer"
            Phase.DONE -> "done"
            Phase.FAILED -> "failed"
            Phase.STOPPED -> "stopped"
        }

        private fun Phase.ended() = this == Phase.DONE || this == Phase.FAILED || this == Phase.STOPPED

        private fun subWord(s: SubStatus): String = when (s) {
            SubStatus.TODO -> "to do"
            SubStatus.RUNNING -> "running"
            SubStatus.DONE -> "done"
            SubStatus.FAILED -> "failed"
            SubStatus.SKIPPED -> "skipped"
        }

        /** What startTask says after it let the task go: the state in plain words. */
        fun startedLine(run: RunState): String = when (run.phase) {
            Phase.WAITING_FOR_GO -> WAITS_FOR_OWNER
            Phase.FAILED, Phase.STOPPED, Phase.DONE ->
                "State: ${phaseWord(run.phase)}. ${run.result.ifBlank { "Ustaad could not carry out the task." }.let { cut(it, RESULT_MAX) }}"
            else -> "State: ${phaseWord(run.phase)}. Ustaad started the task. Ask taskStatus with this id to follow it."
        }

        /**
         * The state of a task: the phase, the plan with a status per subtask, and, once it has ended, its result cut at [RESULT_MAX]
         * characters. No step log and no subtask outcomes: those can hold what was read off a screen.
         */
        fun statusText(run: RunState): String {
            val out = StringBuilder()
            out.append(run.id).append('\n')
            out.append("State: ").append(phaseWord(run.phase))
            when (run.phase) {
                Phase.WAITING_FOR_GO -> out.append(". The owner has not tapped Run in Ustaad yet.")
                Phase.WAITING_FOR_PERSON -> out.append(". Ustaad is asking the owner a question on the phone; only the owner can answer it.")
                else -> out.append('.')
            }
            if (run.plan.isEmpty()) {
                out.append("\nPlan: none yet.")
            } else {
                out.append("\nPlan:")
                run.plan.forEachIndexed { i, s -> out.append('\n').append(i + 1).append(". [").append(subWord(s.status)).append("] ").append(cut(AssistIntents.clean(s.task.goal, 160), 160)) }
            }
            if (run.phase.ended()) {
                val result = run.result.trim()
                out.append("\nResult: ").append(if (result.isEmpty()) "none" else cut(result, RESULT_MAX))
            }
            return out.toString()
        }

        /** [s] cut to at most [max] characters without splitting a surrogate pair; three dots mark a cut. */
        fun cut(s: String, max: Int): String {
            if (s.length <= max) return s
            var end = max - 3
            if (end > 0 && Character.isHighSurrogate(s[end - 1])) end--
            return s.substring(0, end).trimEnd() + "..."
        }

        private fun notReady() = AppFnRefusal(AppFnRefusal.Kind.UNAVAILABLE, "Ustaad is still starting; try again in a few seconds")
    }
}
