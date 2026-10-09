package com.swarmos.app.link

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import com.swarmos.app.core.HealthMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/** What this phone can read about the radio link a probe used (read on the phone, not on the PC). */
@Serializable
data class LinkDesc(
    val iface: String?,
    val localIp: String?,
    val standard: String?,
    val phyMbps: Int?,
    val txMbps: Int?,
    val rxMbps: Int?,
    val frequencyMhz: Int?,
    val band: String?,
    val rssi: Int?,
    val source: String,
)

object LinkInfo {
    fun bandOf(mhz: Int?): String? = when {
        mhz == null || mhz <= 0 -> null
        mhz < 3000 -> "2.4 GHz"
        mhz < 5900 -> "5 GHz"
        else -> "6 GHz"
    }

    /**
     * Infrastructure Wi-Fi comes from WifiInfo. The p2p interface has no public PHY-rate API: frequency comes
     * from the WifiP2pGroup (API 36) and standard and speed stay null (read them with `dumpsys wifi` on the PC).
     */
    @Suppress("DEPRECATION")
    fun describe(ctx: Context, local: LocalLink?): LinkDesc {
        if (local == null) return LinkDesc(null, null, null, null, null, null, null, null, null, "no local interface for peer")
        if (local.type == LinkType.DIRECT) {
            val f = DirectLink.state.value.frequencyMhz
            return LinkDesc(local.ifName, local.address, null, null, null, null, f, bandOf(f), null, "WifiP2pGroup")
        }
        if (!local.ifName.startsWith("wlan")) {
            return LinkDesc(local.ifName, local.address, null, null, null, null, null, null, null, "non-station interface")
        }
        val wi = ctx.getSystemService(WifiManager::class.java)?.connectionInfo
        val connected = wi != null && wi.networkId != -1
        return LinkDesc(
            iface = local.ifName,
            localIp = local.address,
            standard = if (connected) HealthMonitor.standardName(wi!!.wifiStandard) else null,
            phyMbps = if (connected) wi!!.linkSpeed else null,
            txMbps = if (connected) wi!!.txLinkSpeedMbps else null,
            rxMbps = if (connected) wi!!.rxLinkSpeedMbps else null,
            frequencyMhz = if (connected) wi!!.frequency else null,
            band = if (connected) bandOf(wi!!.frequency) else null,
            rssi = if (connected) wi!!.rssi else null,
            source = "WifiInfo",
        )
    }
}

/**
 * "Internet validated on any network": true while at least one network has NET_CAPABILITY_VALIDATED.
 * The StatsBar can read [validated] to show whether anything is reaching the internet.
 */
object InternetState {
    private val _validated = MutableStateFlow(false)
    val validated: StateFlow<Boolean> = _validated.asStateFlow()

    private val good = HashSet<Network>()
    private var started = false

    @Synchronized
    fun start(ctx: Context) {
        if (started) return
        started = true
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        val req = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        cm.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                update { if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) good.add(network) else good.remove(network) }
            }

            override fun onLost(network: Network) {
                update { good.remove(network) }
            }
        })
    }

    @Synchronized
    private fun update(change: () -> Unit) {
        change()
        _validated.value = good.isNotEmpty()
    }
}
