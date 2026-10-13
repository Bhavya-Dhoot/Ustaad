package com.swarmos.app.appfn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import com.swarmos.app.assist.AssistActivity

/**
 * The notice for a task the phone's assistant sent while the owner's start switch is off: its tap opens the task sheet on that run,
 * where the owner reads the task and taps Run (or Discard). It says nothing of the task itself, because a lock screen may show it.
 * Nothing in the notice starts the task. One notice per run; it goes when tapped.
 */
object AppFnNotice {
    private const val CHANNEL = "ustaad_assistant_task"

    fun post(ctx: Context, runId: String) {
        runCatching {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Tasks from your assistant", NotificationManager.IMPORTANCE_HIGH))
            val open = PendingIntent.getActivity(
                ctx, runId.hashCode(), AssistActivity.runIntent(ctx, runId), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("Ustaad")
                .setContentText(AppFnPolicy.NOTICE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            nm.notify(runId.hashCode(), n)
        }.onFailure { Log.w("AppFnNotice", "the notice could not be posted: ${it.javaClass.simpleName}") }
    }
}
