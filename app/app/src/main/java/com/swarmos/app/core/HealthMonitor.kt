package com.swarmos.app.core

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Samples thermal status, battery, free RAM and Wi-Fi link every 2 s into [state]. */
class HealthMonitor(private val ctx: Context, private val selfId: () -> String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /** Set by the inference layer after each generation so peers can see the last decode speed. */
    @Volatile
    var lastDecodeTokPerSec: Double? = null

    private val _state = MutableStateFlow(sample())
    val state: StateFlow<NodeHealth> = _state.asStateFlow()

    @Synchronized
    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                _state.value = sample()
                delay(REFRESH_MS)
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    @Suppress("DEPRECATION")
    private fun sample(): NodeHealth {
        val pm = ctx.getSystemService(PowerManager::class.java)
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }

        val bat = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = bat?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val wi = ctx.getSystemService(WifiManager::class.java)?.connectionInfo
        val connected = wi != null && wi.linkSpeed > 0

        return NodeHealth(
            nodeId = selfId(),
            thermalStatus = pm.currentThermalStatus,
            batteryPct = pct,
            charging = charging,
            ramAvailMb = mem.availMem / (1024 * 1024),
            lastDecodeTokPerSec = lastDecodeTokPerSec,
            linkStandard = if (connected) standardName(wi!!.wifiStandard) else null,
            linkSpeedMbps = if (connected) wi!!.linkSpeed else null,
        )
    }

    companion object {
        const val REFRESH_MS = 2000L

        fun standardName(s: Int): String? = when (s) {
            1 -> "11a/b/g"
            4 -> "11n"
            5 -> "11ac"
            6 -> "11ax"
            7 -> "11ad"
            8 -> "11be"
            else -> null
        }

        fun thermalName(status: Int): String = when (status) {
            0 -> "NONE"
            1 -> "LIGHT"
            2 -> "MODERATE"
            3 -> "SEVERE"
            4 -> "CRITICAL"
            5 -> "EMERGENCY"
            6 -> "SHUTDOWN"
            else -> "?"
        }
    }
}
