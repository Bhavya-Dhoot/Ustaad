package com.swarmos.app.link

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Looper
import android.util.Log
import com.swarmos.app.AppGraph
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
data class DirectState(
    val role: String = "none",        // none | host | client
    val phase: String = "idle",       // idle | starting | up | failed
    val ssid: String? = null,
    val iface: String? = null,
    val localAddress: String? = null,
    val ownerAddress: String? = null,
    val frequencyMhz: Int? = null,
    val band: String? = null,
    val formationMs: Long? = null,
    val staConnected: Boolean? = null, // infrastructure Wi-Fi still associated while the group is up
    val error: String? = null,
)

class P2pException(val reason: Int, msg: String) : RuntimeException(msg)

/**
 * App-created Wi-Fi Direct group, no dialogs: the host makes an autonomous group on 5 GHz with a known
 * `DIRECT-xy-` name and passphrase (WifiP2pConfig.Builder); the joiner connects with the same name and
 * passphrase through WifiP2pManager.connect and reaches the host at the group owner address.
 */
@SuppressLint("MissingPermission")
object DirectLink {
    private const val TAG = "SwarmLink"
    private val lock = Mutex()
    private var channel: WifiP2pManager.Channel? = null

    private val _state = MutableStateFlow(DirectState())
    val state: StateFlow<DirectState> = _state.asStateFlow()

    private fun manager(ctx: Context): WifiP2pManager =
        ctx.getSystemService(WifiP2pManager::class.java) ?: throw P2pException(-1, "no WifiP2pManager on this device")

    private fun channel(ctx: Context, mgr: WifiP2pManager): WifiP2pManager.Channel =
        channel ?: mgr.initialize(ctx, Looper.getMainLooper()) { channel = null }.also { channel = it }

    private suspend fun action(what: String, block: (WifiP2pManager.ActionListener) -> Unit) {
        suspendCancellableCoroutine<Unit> { cont ->
            block(object : WifiP2pManager.ActionListener {
                override fun onSuccess() { if (cont.isActive) cont.resume(Unit) }
                override fun onFailure(reason: Int) {
                    val name = when (reason) { 0 -> "ERROR"; 1 -> "P2P_UNSUPPORTED"; 2 -> "BUSY"; 3 -> "NO_SERVICE_REQUESTS"; else -> "code $reason" }
                    if (cont.isActive) cont.resumeWithException(P2pException(reason, "$what failed: $name"))
                }
            })
        }
    }

    private suspend fun groupInfo(mgr: WifiP2pManager, ch: WifiP2pManager.Channel): WifiP2pGroup? =
        suspendCancellableCoroutine { cont -> mgr.requestGroupInfo(ch) { if (cont.isActive) cont.resume(it) } }

    private suspend fun connectionInfo(mgr: WifiP2pManager, ch: WifiP2pManager.Channel): WifiP2pInfo? =
        suspendCancellableCoroutine { cont -> mgr.requestConnectionInfo(ch) { if (cont.isActive) cont.resume(it) } }

    /** WifiP2pGroup.getFrequency() is API 36; read it reflectively so older stubs still compile. */
    private fun frequencyOf(g: WifiP2pGroup): Int? =
        runCatching { WifiP2pGroup::class.java.getMethod("getFrequency").invoke(g) as Int }.getOrNull()?.takeIf { it > 0 }

    @Suppress("DEPRECATION")
    private fun staConnected(ctx: Context): Boolean {
        val wi = ctx.getSystemService(WifiManager::class.java)?.connectionInfo
        return wi != null && wi.networkId != -1
    }

    private suspend fun clearExisting(mgr: WifiP2pManager, ch: WifiP2pManager.Channel) {
        if (groupInfo(mgr, ch) == null) return
        runCatching { action("removeGroup") { mgr.removeGroup(ch, it) } }
        repeat(25) {
            if (groupInfo(mgr, ch) == null) return
            delay(200)
        }
    }

    /** Start the autonomous 5 GHz group. Returns the credentials to put in the QR. */
    suspend fun host(): P2pCreds = lock.withLock {
        val ctx = AppGraph.appContext
        val mgr = manager(ctx)
        val ch = channel(ctx, mgr)
        val nodeId = AppGraph.registry.self.value.nodeId
        val creds = P2pCreds(Pairing.directSsid(nodeId), Pairing.randomPassphrase())
        _state.value = DirectState(role = "host", phase = "starting", ssid = creds.ssid)
        val t0 = System.nanoTime()
        try {
            clearExisting(mgr, ch)
            val config = WifiP2pConfig.Builder()
                .setNetworkName(creds.ssid)
                .setPassphrase(creds.pass)
                .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
                .enablePersistentMode(false)
                .build()
            try {
                action("createGroup") { mgr.createGroup(ch, config, it) }
            } catch (e: P2pException) {
                if (e.reason != WifiP2pManager.BUSY) throw e
                delay(1_000)
                clearExisting(mgr, ch)
                action("createGroup") { mgr.createGroup(ch, config, it) }
            }
            // wait for the group and its IPv4 on the p2p interface
            var g: WifiP2pGroup? = null
            var local: LocalLink? = null
            for (i in 0 until 75) {
                val gi = groupInfo(mgr, ch)
                if (gi != null && gi.isGroupOwner) {
                    g = gi
                    local = LinkRoute.localLinks().firstOrNull { it.ifName == gi.`interface` }
                    if (local != null) break
                }
                delay(200)
            }
            if (g == null || local == null) throw P2pException(-2, "group did not come up within 15 s")
            val ms = (System.nanoTime() - t0) / 1_000_000
            val f = frequencyOf(g)
            _state.value = DirectState(
                role = "host", phase = "up", ssid = g.networkName, iface = g.`interface`, localAddress = local.address,
                ownerAddress = local.address, frequencyMhz = f, band = LinkInfo.bandOf(f), formationMs = ms,
                staConnected = staConnected(ctx),
            )
            Log.i(TAG, "p2p host up ${g.networkName} ${g.`interface`} ${local.address} f=$f in ${ms} ms")
            creds
        } catch (t: Throwable) {
            _state.value = DirectState(role = "host", phase = "failed", ssid = creds.ssid, error = t.message ?: t.toString())
            throw t
        }
    }

    /** Join a group by credentials. Returns the group owner address. */
    suspend fun join(creds: P2pCreds): String = lock.withLock {
        val ctx = AppGraph.appContext
        val mgr = manager(ctx)
        val ch = channel(ctx, mgr)
        _state.value = DirectState(role = "client", phase = "starting", ssid = creds.ssid)
        val t0 = System.nanoTime()
        try {
            clearExisting(mgr, ch)
            val config = WifiP2pConfig.Builder()
                .setNetworkName(creds.ssid)
                .setPassphrase(creds.pass)
                .enablePersistentMode(false)
                .build()
            try {
                action("connect") { mgr.connect(ch, config, it) }
            } catch (e: P2pException) {
                if (e.reason != WifiP2pManager.BUSY) throw e
                delay(1_000)
                action("connect") { mgr.connect(ch, config, it) }
            }
            var owner: String? = null
            var local: LocalLink? = null
            var g: WifiP2pGroup? = null
            for (i in 0 until 150) { // up to 30 s
                val info = connectionInfo(mgr, ch)
                if (info != null && info.groupFormed && !info.isGroupOwner && info.groupOwnerAddress != null) {
                    val o = info.groupOwnerAddress.hostAddress
                    owner = o
                    g = groupInfo(mgr, ch)
                    local = LinkRoute.localLinks().firstOrNull { it.ifName.startsWith("p2p") && o != null && LinkRoute.sameSubnet(it.address, o, it.prefix) }
                    if (local != null) break
                }
                delay(200)
            }
            if (owner == null || local == null) throw P2pException(-3, "did not join group ${creds.ssid} within 30 s")
            val ms = (System.nanoTime() - t0) / 1_000_000
            val f = g?.let { frequencyOf(it) }
            _state.value = DirectState(
                role = "client", phase = "up", ssid = creds.ssid, iface = local.ifName, localAddress = local.address,
                ownerAddress = owner, frequencyMhz = f, band = LinkInfo.bandOf(f), formationMs = ms, staConnected = staConnected(ctx),
            )
            Log.i(TAG, "p2p client up ${creds.ssid} ${local.ifName} ${local.address} owner=$owner f=$f in $ms ms")
            owner
        } catch (t: Throwable) {
            _state.value = DirectState(role = "client", phase = "failed", ssid = creds.ssid, error = t.message ?: t.toString())
            throw t
        }
    }

    suspend fun stop() = lock.withLock {
        val ctx = AppGraph.appContext
        val mgr = manager(ctx)
        val ch = channel(ctx, mgr)
        runCatching { clearExisting(mgr, ch) }
        _state.value = DirectState()
    }
}
