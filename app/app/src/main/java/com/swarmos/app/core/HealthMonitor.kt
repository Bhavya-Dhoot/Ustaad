package com.swarmos.app.core

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
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

/**
 * The one definition of "how hot is this phone" for the whole app, from PowerManager.currentThermalStatus
 * (0 NONE, 1 LIGHT, 2 MODERATE, 3 SEVERE, 4 CRITICAL, 5 EMERGENCY, 6 SHUTDOWN). The status strip, the scheduler and
 * the benchmark guard all ask here instead of comparing against their own numbers.
 */
enum class ThermalLevel(val word: String, private val firstStatus: Int) {
    Cool("Cool", 0),
    Warm("Warm", 1),
    Hot("Hot", 2),
    Severe("Severe", 3);

    /** Status 2 (MODERATE) or more: the scheduler prefers other phones. */
    val degraded: Boolean get() = firstStatus >= DEGRADED_FROM

    /** Status 3 (SEVERE) or more: benchmarks and heavy generation are refused and the status strip shows the HOT stamp. */
    val tooHot: Boolean get() = firstStatus >= TOO_HOT_FROM

    companion object {
        const val DEGRADED_FROM = 2
        const val TOO_HOT_FROM = 3

        fun of(status: Int): ThermalLevel = when {
            status <= 0 -> Cool
            status == 1 -> Warm
            status == 2 -> Hot
            else -> Severe
        }

        /** [ThermalLevel.degraded] straight from a raw status, for callers that hold only the number. */
        fun isDegraded(status: Int): Boolean = status >= DEGRADED_FROM

        /** [ThermalLevel.tooHot] straight from a raw status. */
        fun isTooHot(status: Int): Boolean = status >= TOO_HOT_FROM

        /** The word for a stored PowerManager name ("NONE", "LIGHT", "MODERATE", "SEVERE" and worse); an unknown text is returned as it is. */
        fun wordForName(name: String): String = when (name.uppercase()) {
            "NONE" -> Cool.word
            "LIGHT" -> Warm.word
            "MODERATE" -> Hot.word
            "SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN" -> Severe.word
            else -> name
        }
    }
}

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

        val link = readLink()

        return NodeHealth(
            nodeId = selfId(),
            thermalStatus = pm.currentThermalStatus,
            batteryPct = pct,
            charging = charging,
            ramAvailMb = mem.availMem / (1024 * 1024),
            lastDecodeTokPerSec = lastDecodeTokPerSec,
            linkStandard = link?.let { standardName(it.standard) ?: bandName(it.freqMhz) ?: "wifi" },
            linkSpeedMbps = link?.speedMbps?.takeIf { it > 0 },
        )
    }

    /**
     * Wi-Fi link seen by this app, or null when there is none. Primary source: the transportInfo of every Wi-Fi
     * network's NetworkCapabilities (standard, PHY speed and frequency are not location-gated there). A phone with a
     * second Wi-Fi interface can answer WifiManager.connectionInfo with the idle one (speed -1), which is why that
     * call is only the last fallback and the first reading with a real speed wins.
     */
    @Suppress("DEPRECATION")
    private fun readLink(): LinkReading? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val wifi = ArrayList<WifiInfo>()
        var wifiNetworkSeen = false
        if (cm != null) {
            val nets = listOfNotNull(cm.activeNetwork) + cm.allNetworks.toList()
            for (n in nets) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                wifiNetworkSeen = true
                (caps.transportInfo as? WifiInfo)?.let { wifi += it }
            }
        }
        ctx.getSystemService(WifiManager::class.java)?.connectionInfo?.let { wifi += it }
        val readings = wifi.map { LinkReading(it.wifiStandard, it.linkSpeed, it.frequency) }
        return pickLink(readings) ?: if (wifiNetworkSeen) LinkReading(0, -1, -1) else null
    }

    /** One Wi-Fi reading; -1 and 0 mean "unknown" as in [WifiInfo]. */
    data class LinkReading(val standard: Int, val speedMbps: Int, val freqMhz: Int)

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

        /** First reading with a real speed, null when none has one. */
        fun pickLink(readings: List<LinkReading>): LinkReading? = readings.firstOrNull { it.speedMbps > 0 }

        /** Band as a fallback name when the phone does not report a Wi-Fi standard. */
        fun bandName(freqMhz: Int): String? = when (freqMhz) {
            in 2400..2500 -> "2.4 GHz"
            in 4900..5900 -> "5 GHz"
            in 5925..7125 -> "6 GHz"
            else -> null
        }

        /** Link text of the status strip: "11ac 433 Mbps", "wifi" when connected without a known speed, "no link" otherwise. */
        fun linkLabel(standard: String?, speedMbps: Int?): String = when {
            speedMbps != null -> "${standard ?: "wifi"} $speedMbps Mbps"
            standard != null -> standard
            else -> "no link"
        }

        /** Thermal state as one word for the status strip: Cool, Warm, Hot, Severe (status 3 and above). */
        fun thermalWord(status: Int): String = ThermalLevel.of(status).word

        /** The hatched HOT stamp replaces the internet stamp from this thermal status on. */
        fun isHot(status: Int): Boolean = ThermalLevel.of(status).tooHot

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
