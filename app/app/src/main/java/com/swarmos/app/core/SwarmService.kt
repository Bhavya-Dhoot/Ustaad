package com.swarmos.app.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.swarmos.app.AppGraph
import com.swarmos.app.MainActivity
import com.swarmos.app.link.NodeServer
import com.swarmos.app.llm.LlamaRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Foreground service (connectedDevice): keeps the process alive, holds wake and Wi-Fi locks, runs the node server. */
class SwarmService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: Boolean? = null
    private var server: NodeServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        acquireLocks()
        AppGraph.health.start()
        LlamaRuntime.onServiceStarted()
        if (server == null) {
            val s = NodeServer().also { server = it }
            scope.launch { s.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        LlamaRuntime.onServiceStopped()
        server?.stop()
        server = null
        AppGraph.audit.append("service.stop")
        if (wifiLock != null) com.swarmos.app.link.WifiLocks.stop()
        wakeLock?.takeIf { it.isHeld }?.release()
        scope.cancel()
        super.onDestroy()
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = applicationContext.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "swarmos:service")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (wifiLock == null) {
            // the Wi-Fi lock is managed by link/WifiLocks (low latency while in front with a swarm, high perf otherwise)
            com.swarmos.app.link.WifiLocks.start(applicationContext)
            wifiLock = true
            AppGraph.audit.append("service.start")
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Ustaad node", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Ustaad node running")
            .setContentText("API port ${Ports.NODE}")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.swarmos.app.STOP"
        private const val CHANNEL_ID = "swarm"
        private const val NOTIFICATION_ID = 1

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, SwarmService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, SwarmService::class.java).setAction(ACTION_STOP))
        }
    }
}
