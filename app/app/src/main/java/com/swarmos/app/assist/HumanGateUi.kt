package com.swarmos.app.assist

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import com.swarmos.app.AppGraph
import com.swarmos.app.hands.ConfirmPrompt
import com.swarmos.app.hands.HumanGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The person's side of Hands (rule S1): the confirmation prompt and the question, answered by a tap in Ustaad's own window.
 *
 * There is no way in here to answer a prompt without a tap: no setting, no extra, no test hook. [answerYes] is called by the
 * "Do it" button of [AssistActivity] alone, and [PromptBoard] refuses it for 700 ms after the prompt became visible and resumed.
 * The window ignores touches while another window covers it. A timeout after 120 s, a dismissal, back and "Do not" are all no.
 *
 * Open risk for the guard and the drivers (not solvable in this file): a click that Ustaad's own service makes on a node of
 * Ustaad's own window is not "a covered touch". The core must never act in Ustaad's own package; [isPending] lets a driver check it too.
 */
object HumanGateUi : HumanGate {
    private val board = PromptBoard()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** If the prompt is not on screen this long after the start of the sheet, a notification brings the person to it. */
    private const val NOTIFY_AFTER_MS = 1500L
    private const val CHANNEL = "ustaad_hands_prompt"
    private const val NOTIFICATION_ID = 7421

    @Volatile
    private var nudge: Job? = null

    /** The prompt the sheet shows now, or null. */
    val pending: StateFlow<PendingPrompt?> get() = board.pending

    /** True while a prompt waits for the person. */
    val isPending: Boolean get() = board.pending.value != null

    override suspend fun confirm(prompt: ConfirmPrompt): Boolean = board.confirm(prompt, ::bringToFront, ::withdraw)

    override suspend fun ask(runId: String, question: String): String? = board.ask(runId, question, ::bringToFront, ::withdraw)

    // The calls below are for AssistActivity. They exist so that the window can say what it sees and what the person tapped.

    internal fun markVisible(id: Long) {
        board.markVisible(id)
        nudge?.cancel()
        cancelNotification()
    }

    /** The prompt is covered or paused: if it stays that way, a notification brings the person back to it. */
    internal fun markHidden(id: Long) {
        board.markHidden(id)
        nudgeLater(id)
    }

    internal fun answerYes(id: Long): Boolean = board.answerYes(id)

    internal fun answerNo(id: Long): Boolean = board.answerNo(id)

    internal fun answerText(id: Long, text: String): Boolean = board.answerText(id, text)

    private fun bringToFront(p: PendingPrompt) {
        val ctx = runCatching { AppGraph.appContext }.getOrNull() ?: return
        // an open sheet shows the prompt by itself; otherwise start it (Android may refuse this from the background)
        if (!AssistActivity.isResumed) {
            runCatching { ctx.startActivity(AssistActivity.promptIntent(ctx)) }
                .onFailure { Log.w("HumanGateUi", "the prompt window could not be started: ${it.javaClass.simpleName}") }
        }
        nudgeLater(p.id)
    }

    private fun nudgeLater(id: Long) {
        val ctx = runCatching { AppGraph.appContext }.getOrNull() ?: return
        nudge?.cancel()
        nudge = scope.launch {
            delay(NOTIFY_AFTER_MS)
            if (board.pending.value?.id == id && !board.isVisible(id)) notifyPerson(ctx)
        }
    }

    private fun withdraw(@Suppress("UNUSED_PARAMETER") p: PendingPrompt) {
        nudge?.cancel()
        cancelNotification()
    }

    private fun cancelNotification() {
        val ctx = runCatching { AppGraph.appContext }.getOrNull() ?: return
        ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    /** A high-priority notification whose tap opens the prompt. It says nothing about the action: a lock screen may show it. */
    private fun notifyPerson(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Ustaad asks you", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            ctx, 0, AssistActivity.promptIntent(ctx), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Ustaad needs your answer")
            .setContentText("Tap to read the question. Nothing happens until you answer.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(PromptBoard.TIMEOUT_MS)
            .setContentIntent(open)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
    }
}
