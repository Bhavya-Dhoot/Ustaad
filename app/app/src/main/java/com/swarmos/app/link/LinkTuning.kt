package com.swarmos.app.link

import android.app.ActivityManager
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * Whether this app is in front. An activity-callback counter would miss the first activity (the service starts
 * after onStart), so this asks the system for this process's importance once a second: 100 (foreground) or 200
 * (visible) means an activity is on screen, 125 means only the foreground service is left.
 */
object AppForeground {
    private val _foreground = MutableStateFlow(false)
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()
    private var job: Job? = null

    @Synchronized
    fun start(ctx: Context, scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                val info = ActivityManager.RunningAppProcessInfo()
                ActivityManager.getMyMemoryState(info)
                val fg = info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
                if (fg != _foreground.value) {
                    _foreground.value = fg
                    Log.i("SwarmLink", "app foreground=$fg (importance ${info.importance})")
                    WifiLocks.reevaluate()
                    LinkRuntime.onForegroundChanged(fg)
                }
                delay(1_000)
            }
        }
    }

    /** Ends the polling loop; [start] begins a fresh one (LinkRuntime.stop, then start again). */
    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }
}

enum class LockMode { NONE, HIGH_PERF, LOW_LATENCY }

@Serializable
data class TuningView(
    val lockWanted: String,
    val lockHeld: String,
    val lockOverride: String?,
    val lowLatencySupported: Boolean,
    val foreground: Boolean,
    val sndBufKb: Int,
    val rcvBufKb: Int,
)

/**
 * The Wi-Fi lock for the swarm. Automatic mode: FULL_LOW_LATENCY while the app is in front and a swarm is active
 * (a peer online or this phone hosting), FULL_HIGH_PERF otherwise. The system only honours low-latency mode for
 * an app in front with the screen on. [override] forces a mode (NONE releases the lock) for measurements.
 */
object WifiLocks {
    private const val TAG = "SwarmLink"
    private var lock: WifiManager.WifiLock? = null
    private var wifi: WifiManager? = null
    @Volatile private var running = false
    @Volatile var override: LockMode? = null
        private set

    private val _held = MutableStateFlow(LockMode.NONE)
    val held: StateFlow<LockMode> = _held.asStateFlow()

    /** WifiManager.isLowLatencyModeSupported() is not in the public stubs; ask it reflectively. */
    val lowLatencySupported: Boolean
        get() = runCatching { wifi?.let { it.javaClass.getMethod("isLowLatencyModeSupported").invoke(it) as Boolean } == true }.getOrDefault(false)

    @Synchronized
    fun start(ctx: Context) {
        wifi = ctx.applicationContext.getSystemService(WifiManager::class.java)
        running = true
        reevaluate()
    }

    @Synchronized
    fun stop() {
        running = false
        release()
    }

    fun setOverride(mode: LockMode?) {
        override = mode
        reevaluate()
    }

    fun wanted(): LockMode = override ?: when {
        !running -> LockMode.NONE
        AppForeground.foreground.value && swarmActive() -> LockMode.LOW_LATENCY
        else -> LockMode.HIGH_PERF
    }

    /** WIFI_MODE_FULL_LOW_LATENCY is Android 10 (API 29); below it the lock that can be held is the high-performance one. */
    internal fun lockModeFor(want: LockMode, sdkInt: Int): LockMode =
        if (want == LockMode.LOW_LATENCY && sdkInt < 29) LockMode.HIGH_PERF else want

    private fun swarmActive(): Boolean =
        PeerLinks.peers.value.any { it.state == LinkState.ONLINE } || LinkRuntime.hosting.value != null

    @Synchronized
    fun reevaluate() {
        val w = wifi ?: return
        val want = lockModeFor(if (!running && override == null) LockMode.NONE else wanted(), Build.VERSION.SDK_INT)
        if (want == _held.value) return
        release()
        if (want == LockMode.NONE) return
        val mode = if (want == LockMode.LOW_LATENCY && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        lock = w.createWifiLock(mode, "swarmos:wifi").apply { setReferenceCounted(false); acquire() }
        _held.value = want
        Log.i(TAG, "wifi lock now $want")
    }

    private fun release() {
        lock?.takeIf { it.isHeld }?.release()
        lock = null
        _held.value = LockMode.NONE
    }
}

/** Socket buffer sizes for bulk sockets; 0 leaves the system default. Changed at runtime for measurements. */
object LinkTuning {
    @Volatile var sndBufKb = 0
    @Volatile var rcvBufKb = 0

    fun view() = TuningView(
        lockWanted = WifiLocks.wanted().name, lockHeld = WifiLocks.held.value.name, lockOverride = WifiLocks.override?.name,
        lowLatencySupported = WifiLocks.lowLatencySupported, foreground = AppForeground.foreground.value,
        sndBufKb = sndBufKb, rcvBufKb = rcvBufKb,
    )

    /** Apply buffer sizes to a socket that is not connected yet (the window scale is fixed at connect time). */
    fun apply(s: java.net.Socket) {
        if (sndBufKb > 0) s.sendBufferSize = sndBufKb * 1024
        if (rcvBufKb > 0) s.receiveBufferSize = rcvBufKb * 1024
    }
}
