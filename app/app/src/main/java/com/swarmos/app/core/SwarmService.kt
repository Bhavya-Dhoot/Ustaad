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
import com.swarmos.app.agent.AgentAccess
import com.swarmos.app.agent.AgentBackend
import com.swarmos.app.agent.AgentServer
import com.swarmos.app.agent.FileSource
import com.swarmos.app.busy.BusyApp
import com.swarmos.app.busy.BusyMode
import com.swarmos.app.link.NodeServer
import com.swarmos.app.llm.LlamaRuntime
import com.swarmos.app.llm.Role
import com.swarmos.app.llm.ServerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service (connectedDevice): keeps the process alive and runs the node server and the model servers.
 * The wake lock and the low-latency Wi-Fi lock are held only while the app is in front, a peer is online, or model work
 * runs on this phone ([ServicePolicy]); the wake lock has a timeout that is renewed while that holds. "Stop Ustaad" (notification or settings) stops the server, the models and the locks; starting
 * again is [start]. All start-up and shut-down work runs off the main thread.
 */
class SwarmService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: Boolean? = null
    private var server: NodeServer? = null
    private var agent: AgentServer? = null
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
            this, NOTIFICATION_ID, buildNotification("Starting Ustaad."), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
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
                launch { serveAgentWhileEnabled() }
                launch { BusyMode.serve { BusyApp.loop().run() } } // filler jobs while the "Keep busy" switch is on; ends with this job
                AppGraph.audit.append("service.start")
                launch { keepNotificationCurrent() }
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

    /** The agent endpoint listens while the Settings switch is on and this service runs; it is never opened otherwise. */
    private suspend fun serveAgentWhileEnabled() {
        val a = AgentServer(
            Ports.AGENT, { AgentAccess.key.value }, AgentBackend.app(),
            files = FileSource.app(), filesEnabled = { AgentAccess.files.value },
            audit = { kind, payload -> AppGraph.audit.append(kind, payload) },
            onServed = AgentAccess::noteServed,
            onRefused = AgentAccess::noteRefused,
        ).also { agent = it }
        AgentAccess.enabled.collect { on ->
            if (on) {
                AgentAccess.reportError(a.start())
            } else {
                a.stop()
                AgentAccess.reportError(null)
            }
        }
    }

    /**
     * Locks follow [ServicePolicy.holdLocks]: app in front, a peer online, or model work running on this phone. The wake lock
     * has a timeout and is renewed every [ServicePolicy.WAKE_LOCK_RENEW_MS] for as long as the condition holds.
     */
    private suspend fun holdLocksWhileNeeded() {
        combine(
            AppGraph.foreground,
            AppGraph.registry.peers.map { ServicePolicy.onlinePeers(it) },
            LlamaRuntime.working,
        ) { front, peers, working -> ServicePolicy.holdLocks(front, peers, working) }
            .distinctUntilChanged()
            .collectLatest { hold ->
                if (!hold) {
                    releaseLocks()
                    AppGraph.audit.append("service.locks", JsonObject(mapOf("held" to JsonPrimitive(false))))
                    return@collectLatest
                }
                acquireLocks()
                AppGraph.audit.append("service.locks", JsonObject(mapOf("held" to JsonPrimitive(true))))
                while (true) { // cancelled by collectLatest when the condition ends
                    delay(ServicePolicy.WAKE_LOCK_RENEW_MS)
                    renewWakeLock()
                }
            }
    }

    /** The notification text is a live state line ([ServiceLine]): peers online, chat model state, or the current activity. */
    private suspend fun keepNotificationCurrent() {
        val nm = getSystemService(NotificationManager::class.java)
        combine(
            AppGraph.registry.peers.map { ServicePolicy.onlinePeers(it) },
            LlamaRuntime.status.map { chatOf(it.getValue(Role.CHAT).state) },
            PhoneActivity.current.map { it?.text },
        ) { peers, chat, activity -> ServiceLine.describe(peers, chat, activity) }
            .distinctUntilChanged()
            .conflate()
            .collect { line ->
                nm.notify(NOTIFICATION_ID, buildNotification(line))
                delay(1000) // at most one update a second, newer lines replace the ones skipped meanwhile
            }
    }

    private fun chatOf(state: ServerState): ServiceLine.Chat = when (state) {
        ServerState.READY -> ServiceLine.Chat.READY
        ServerState.STARTING -> ServiceLine.Chat.LOADING
        ServerState.FAILED -> ServiceLine.Chat.FAILED
        ServerState.STOPPED -> ServiceLine.Chat.NONE
    }

    @Synchronized
    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = applicationContext.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "swarmos:service")
                .apply { setReferenceCounted(false); acquire(ServicePolicy.WAKE_LOCK_TIMEOUT_MS) }
        }
        if (wifiLock == null) {
            // the Wi-Fi lock is managed by link/WifiLocks (low latency while in front with a swarm, high perf otherwise);
            // this service only decides when it is held at all ([ServicePolicy.holdLocks])
            com.swarmos.app.link.WifiLocks.start(applicationContext)
            wifiLock = true
        }
    }

    /** Pushes the wake lock's timeout out again (a non-reference-counted lock restarts its timer on every acquire). */
    @Synchronized
    private fun renewWakeLock() {
        wakeLock?.acquire(ServicePolicy.WAKE_LOCK_TIMEOUT_MS)
    }

    @Synchronized
    private fun releaseLocks() {
        if (wifiLock != null) com.swarmos.app.link.WifiLocks.stop()
        wifiLock = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    /** Stops the model servers, the node server and the locks. Blocking; always called off the main thread. */
    private fun teardown() {
        if (!tornDown.compareAndSet(false, true)) return
        releaseLocks()
        LlamaRuntime.onServiceStopped()
        AppGraph.health.stop() // the 2 s sampler runs only while the service does (start() calls AppGraph.health.start() again)
        server?.stop()
        server = null
        agent?.stop()
        agent = null
        BusyMode.hammerOffForStop() // Hammer is not stored: a start in this same process must not find it still on
        AppGraph.audit.append("service.stop")
        PhoneActivity.clearAll()
        _running.value = false
    }

    private fun buildNotification(line: String): Notification {
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
            .setContentText(line)
            .setStyle(NotificationCompat.BigTextStyle().bigText(line))
            .setSubText("API port ${Ports.NODE}")
            .setOnlyAlertOnce(true)
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
