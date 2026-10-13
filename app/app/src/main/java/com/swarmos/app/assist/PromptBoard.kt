package com.swarmos.app.assist

import com.swarmos.app.hands.AskKind
import com.swarmos.app.hands.ConfirmPrompt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/** A question to the person that is on screen or about to be. Texts are already cleaned for display. */
data class PendingPrompt(
    val id: Long,
    val runId: String,
    /** false: a yes or no (confirm). true: a typed answer (ask), [action] holds the question. */
    val isQuestion: Boolean,
    val app: String,
    val action: String,
    val why: String,
    /** Why the guard asked, as a kind; the window words the reason by it ([PromptWording]). */
    val kind: AskKind = AskKind.OTHER,
)

/**
 * The pending-prompt state machine behind HumanGateUi, with no Android in it.
 *
 * - One prompt is pending at a time. A second one waits for the first (at most [timeoutMs]) and is then refused.
 * - The answer is yes only through [answerYes], which the prompt's "Do it" button alone calls. A timeout, a dismissal, "Do not",
 *   a cancelled caller and a second prompt that waited too long are all no (confirm false, ask null).
 * - [answerYes] is refused until the prompt has been visible for [armMs] ([markVisible] starts the clock, [markHidden] stops it),
 *   so a tap that lands as the prompt appears, or while it is covered, answers nothing.
 * - Every answer carries the id of the prompt it is for; an answer for a prompt that is gone (a late tap, an old window) is ignored.
 */
class PromptBoard(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val timeoutMs: Long = TIMEOUT_MS,
    private val armMs: Long = ARM_MS,
) {
    private sealed interface Reply {
        data object Yes : Reply
        data object No : Reply
        class Typed(val text: String) : Reply
    }

    private class Slot(val prompt: PendingPrompt) {
        val reply = CompletableDeferred<Reply>()
        var visibleSince = NOT_VISIBLE
    }

    private val lock = Any()
    private val turn = Mutex()
    private var nextId = 1L
    private var current: Slot? = null
    private val shown = MutableStateFlow<PendingPrompt?>(null)

    /** The prompt on screen now, or null. The sheet shows exactly this. */
    val pending: StateFlow<PendingPrompt?> get() = shown

    /** [onOpen] runs once the prompt is published (bring it in front of the person); [onClose] runs when it ends, however it ends. */
    suspend fun confirm(prompt: ConfirmPrompt, onOpen: (PendingPrompt) -> Unit = {}, onClose: (PendingPrompt) -> Unit = {}): Boolean {
        val reply = open(
            { id ->
                PendingPrompt(
                    id, prompt.runId, false,
                    AssistIntents.clean(prompt.app, MAX_APP), AssistIntents.clean(prompt.action, MAX_SHOWN), AssistIntents.clean(prompt.why, MAX_SHOWN),
                    prompt.kind,
                )
            },
            onOpen, onClose,
        )
        return reply === Reply.Yes
    }

    suspend fun ask(runId: String, question: String, onOpen: (PendingPrompt) -> Unit = {}, onClose: (PendingPrompt) -> Unit = {}): String? {
        val reply = open({ id -> PendingPrompt(id, runId, true, "", AssistIntents.clean(question, MAX_SHOWN), "") }, onOpen, onClose)
        return (reply as? Reply.Typed)?.text
    }

    private suspend fun open(make: (Long) -> PendingPrompt, onOpen: (PendingPrompt) -> Unit, onClose: (PendingPrompt) -> Unit): Reply? {
        if (withTimeoutOrNull(timeoutMs) { turn.lock(); true } != true) return null
        var slot: Slot? = null
        try {
            synchronized(lock) {
                val s = Slot(make(nextId++))
                slot = s
                current = s
                shown.value = s.prompt
            }
            val s = slot!!
            onOpen(s.prompt)
            return withTimeoutOrNull(timeoutMs) { s.reply.await() }
        } finally {
            val s = slot
            synchronized(lock) {
                if (s != null && current === s) {
                    current = null
                    shown.value = null
                }
            }
            if (s != null) onClose(s.prompt)
            turn.unlock()
        }
    }

    /** The prompt is on screen, resumed and in focus: starts the [armMs] clock for "Do it". Repeated calls do not restart it. */
    fun markVisible(id: Long) {
        synchronized(lock) {
            val s = current?.takeIf { it.prompt.id == id && it.visibleSince == NOT_VISIBLE }
            if (s != null) s.visibleSince = clock()
        }
    }

    /** The prompt is covered, paused or out of focus: "Do it" is off again until it has been visible for [armMs] once more. */
    fun markHidden(id: Long) {
        synchronized(lock) {
            val s = current?.takeIf { it.prompt.id == id }
            if (s != null) s.visibleSince = NOT_VISIBLE
        }
    }

    fun isVisible(id: Long): Boolean = synchronized(lock) {
        val s = current?.takeIf { it.prompt.id == id }
        s != null && s.visibleSince != NOT_VISIBLE
    }

    /** True when "Do it" may be tapped now. */
    fun armed(id: Long): Boolean = synchronized(lock) { armedLocked(current?.takeIf { it.prompt.id == id }) }

    private fun armedLocked(s: Slot?): Boolean = s != null && s.visibleSince != NOT_VISIBLE && clock() - s.visibleSince >= armMs

    /** "Do it". Only the button of the prompt calls this. False: the prompt is gone, is a question, or "Do it" is not armed yet. */
    fun answerYes(id: Long): Boolean = synchronized(lock) {
        val s = current?.takeIf { it.prompt.id == id && !it.prompt.isQuestion } ?: return false
        if (!armedLocked(s)) return false
        s.reply.complete(Reply.Yes)
    }

    /** "Do not", the back key, a tap outside the sheet, a closed window: all of them are no. False: the prompt is gone. */
    fun answerNo(id: Long): Boolean = synchronized(lock) {
        val s = current?.takeIf { it.prompt.id == id } ?: return false
        s.reply.complete(Reply.No)
    }

    /** The typed answer of a question. Empty after cleaning: refused, the prompt stays open. */
    fun answerText(id: Long, text: String): Boolean = synchronized(lock) {
        val s = current?.takeIf { it.prompt.id == id && it.prompt.isQuestion } ?: return false
        val clean = AssistIntents.clean(text)
        if (clean.isEmpty()) return false
        s.reply.complete(Reply.Typed(clean))
    }

    companion object {
        const val TIMEOUT_MS = 120_000L
        const val ARM_MS = 700L
        private const val NOT_VISIBLE = Long.MIN_VALUE
        private const val MAX_APP = 60
        private const val MAX_SHOWN = 240
    }
}
