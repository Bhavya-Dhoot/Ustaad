package com.swarmos.app.pdu

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Opens an indexed file with the system viewer (ACTION_VIEW). The file itself never moves. */
object FileOpener {
    private const val CHANNEL = "pdu_open"
    private const val DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    fun mimeOf(kind: String): String = when (kind) {
        "pdf" -> "application/pdf"
        "docx" -> DOCX
        "text" -> "text/plain"
        else -> "image/*"
    }

    /** A content:// uri the viewer can read. `file:` uris (all-files demo path) go through the media scanner to get one. */
    suspend fun viewUri(ctx: Context, uri: String): Uri? {
        if (!uri.startsWith("file:")) return Uri.parse(uri)
        val path = Uri.parse(uri).path ?: return null
        return suspendCancellableCoroutine { c -> MediaScannerConnection.scanFile(ctx, arrayOf(path), null) { _, u -> c.resume(u) } }
    }

    private fun viewIntent(uri: Uri, kind: String) = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mimeOf(kind))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)

    /** Own UI is on screen. Only then may a background request start an activity directly. */
    private fun appVisible(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    /** Open on this phone's screen. If the app is not visible Android would block the start, so a tap-to-open notification is posted. */
    suspend fun open(ctx: Context, doc: DocInfo): PduOpenResult {
        val uri = viewUri(ctx, doc.uri) ?: return PduOpenResult(false, "none", doc.title, "no viewable uri")
        val intent = viewIntent(uri, doc.kind)
        return try {
            if (appVisible()) {
                ctx.startActivity(intent)
                PduOpenResult(true, "activity", doc.title)
            } else {
                notify(ctx, doc, intent)
                PduOpenResult(true, "notification", doc.title)
            }
        } catch (e: ActivityNotFoundException) {
            PduOpenResult(false, "none", doc.title, "no app can open ${doc.kind}")
        } catch (e: SecurityException) {
            PduOpenResult(false, "none", doc.title, "notifications not allowed")
        }
    }

    private fun notify(ctx: Context, doc: DocInfo, intent: Intent) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) throw SecurityException("notifications disabled")
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Open file from another phone", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(ctx, doc.docId.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Open ${doc.title}")
            .setContentText("Requested from another phone. Tap to open here.")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify(doc.docId.hashCode(), n)
    }
}
