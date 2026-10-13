package com.swarmos.app.hands.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * The notification shown while a run is active, with a Stop action. It is the Stop that works with the
 * accessibility service off too. The wiring sets onStop to what Stop does (cancel the run). The text is readable in
 * the notification shade: pass a status line, never screen content.
 */
object HandsNotice {
    const val ACTION_STOP = "com.swarmos.app.hands.STOP"
    private const val TAG = "HandsNotice"
    private const val CHANNEL_ID = "hands"
    private const val NOTIFICATION_ID = 41

    /** What the Stop action does. null until the wiring sets it; the receiver still clears the notification. */
    @Volatile
    var onStop: (() -> Unit)? = null

    /** Shows or updates the notification. Returns false when the person has switched notifications off for Ustaad. */
    fun show(ctx: Context, text: String): Boolean {
        val app = ctx.applicationContext
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Ustaad Hands", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getBroadcast(
            app, 0, Intent(app, HandsStopReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Ustaad is working")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(0, "Stop", stop)
            .build()
        nm.notify(NOTIFICATION_ID, n)
        val shown = nm.areNotificationsEnabled() && nm.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
        visible = shown
        return shown
    }

    /** True while the last [show] reached the person's notification shade (notifications on for Ustaad and for this channel). */
    @Volatile
    var visible: Boolean = false
        private set

    fun clear(ctx: Context) {
        visible = false
        ctx.applicationContext.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    internal fun stopPressed(ctx: Context) {
        try {
            onStop?.invoke()
        } catch (e: Exception) {
            Log.w(TAG, "stop handler failed: ${e.javaClass.simpleName}")
        }
        clear(ctx)
    }
}

/** Receives the Stop action of the notification. Not exported: only our own PendingIntent reaches it. */
class HandsStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == HandsNotice.ACTION_STOP) HandsNotice.stopPressed(context)
    }
}
