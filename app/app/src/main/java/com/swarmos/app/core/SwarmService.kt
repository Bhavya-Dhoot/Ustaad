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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service (connectedDevice): keeps the process alive and runs the node server and the model servers.
 * The wake lock and the low-latency Wi-Fi lock are held only while the app is in front or a peer is online
 * ([ServicePolicy]). "Stop Ustaad" (notification or settings) stops the server, the models and the locks; starting
 * again is [start]. All start-up and shut-down work runs off the main thread.
 */
class SwarmService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var server: NodeServer? = null
    private var startJob: Job? = null
    private val tornDown = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE) // the notification goes at once, the rest happens off the main thread
            startJob?.cancel()
            scope.launch {
                teardown()
                withContext(Dispatchers.Main) { stopSelf() }
            }
            return START_NOT_STICKY
        }
        // must be called within seconds of startForegroundService; it needs nothing from AppGraph
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        _running.value = true
        tornDown.set(false)
        if (startJob?.isActive != true && server == null) {
            startJob = scope.launch {
                AppGraph.awaitReady() // identity, secret and audit log are loaded off the main thread
                AppGraph.health.start()
                LlamaRuntime.onServiceStarted()
                val s = NodeServer().also { server = it }
                launch { s.start() }
                AppGraph.audit.append("service.start")
                holdLocksWhileNeeded()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // normally teardown() already ran (Stop); this covers the system destroying the service
        releaseLocks()
        if (!tornDown.get()) TEARDOWN_SCOPE.launch { teardown() }
        scope.cancel()
        super.onDestroy()
    }

    /** Locks follow [ServicePolicy.holdLocks]: app in front or a peer online. */
    private suspend fun holdLocksWhileNeeded() {
        combine(
            AppGraph.foreground,
            AppGraph.registry.peers.map { ServicePolicy.onlinePeers(it) },
        ) { front, peers -> ServicePolicy.holdLocks(front, peers) }
            .distinctUntilChanged()
            .collect { hold ->
                if (hold) acquireLocks() else releaseLocks()
                AppGraph.audit.append("service.locks", JsonObject(mapOf("held" to JsonPrimitive(hold))))
            }
    }

    @Synchronized
    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = applicationContext.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "swarmos:service")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (wifiLock == null) {
            wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "swarmos:wifi")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    @Synchronized
    private fun releaseLocks() {
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    /** Stops the model servers, the node server and the locks. Blocking; always called off the main thread. */
    private fun teardown() {
        if (!tornDown.compareAndSet(false, true)) return
        releaseLocks()
        LlamaRuntime.onServiceStopped()
        server?.stop()
        server = null
        AppGraph.audit.append("service.stop")
        _running.value = false
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Ustaad node", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SwarmService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Ustaad node running")
            .setContentText("API port ${Ports.NODE}")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop Ustaad", stop)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.swarmos.app.STOP"
        private const val CHANNEL_ID = "swarm"
        private const val NOTIFICATION_ID = 1
        private val TEARDOWN_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private val _running = MutableStateFlow(false)

        /** True from start until Stop finished. Settings shows "Stop Ustaad" or "Start Ustaad" from it. */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        /** Starts the service (one tap on "Start Ustaad", or opening the app). */
        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, SwarmService::class.java))
        }

        /** Stops the service, the model servers and the locks; returns at once, the work runs in the service. */
        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, SwarmService::class.java).setAction(ACTION_STOP))
        }
    }
}
