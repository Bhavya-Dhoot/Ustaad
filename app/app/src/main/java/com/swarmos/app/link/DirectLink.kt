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
import com.swarmos.app.core.SwarmJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.io.File
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
    /** Frequency asked for when the group was made (null = the 5 GHz band request), to compare with [frequencyMhz]. */
    val requestedMhz: Int? = null,
    /** Set when a group that was up disappeared without this app asking (removed from outside, host left, radio off). */
    val lostAtMs: Long? = null,
)

/** The group this phone made or joined, kept so it can be re-formed with no QR and no tap. */
@Serializable
data class StoredDirect(val role: String, val ssid: String, val pass: String)

class P2pException(val reason: Int, msg: String) : RuntimeException(msg)

/**
 * App-created Wi-Fi Direct group, no dialogs: the host makes an autonomous group on 5 GHz with a known
 * `DIRECT-xy-` name and passphrase (WifiP2pConfig.Builder); the joiner connects with the same name and
 * passphrase through WifiP2pManager.connect and reaches the host at the group owner address.
 *
 * The group's credentials are stored (filesDir/p2p.json). [watch] notices when a group that was up has gone
 * (state returns to idle, never stays "up"), and re-forms it from the stored credentials: the host re-creates the
 * group, a client joins again once the host is heard ([DirectRendezvous]). [stop] is the user's decision and
 * forgets the credentials.
 */
@SuppressLint("MissingPermission")
object DirectLink {
    private const val TAG = "SwarmLink"
    private val lock = Mutex()
    private var channel: WifiP2pManager.Channel? = null
    private var watchJob: Job? = null

    private val _state = MutableStateFlow(DirectState())
    val state: StateFlow<DirectState> = _state.asStateFlow()

    /** Credentials of the group this phone owns, readable by paired peers through GET /v1/link/p2p-creds. */
    @Volatile var hostCreds: P2pCreds? = null

    private val storeFile get() = File(AppGraph.appContext.filesDir, "p2p.json")

    fun stored(): StoredDirect? = runCatching { SwarmJson.decodeFromString<StoredDirect>(storeFile.readText()) }.getOrNull()
    private fun store(s: StoredDirect) { runCatching { storeFile.writeText(SwarmJson.encodeToString(StoredDirect.serializer(), s)) } }
    private fun forget() { runCatching { storeFile.delete() } }

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
        return wi != null && (wi.frequency > 0 || wi.linkSpeed > 0) // networkId is redacted (-1) without foreground location access
    }

    /** The channel the Wi-Fi station is on, when it is a 5 GHz one (else null). */
    @Suppress("DEPRECATION")
    private fun stationFrequency5g(ctx: Context): Int? =
        ctx.getSystemService(WifiManager::class.java)?.connectionInfo?.frequency?.takeIf { it in 5000..5900 }

    private suspend fun clearExisting(mgr: WifiP2pManager, ch: WifiP2pManager.Channel) {
        if (groupInfo(mgr, ch) == null) return
        runCatching { action("removeGroup") { mgr.removeGroup(ch, it) } }
        repeat(25) {
            if (groupInfo(mgr, ch) == null) return
            delay(200)
        }
    }

    /**
     * Start the autonomous group. Without [creds] a fresh name and passphrase are made (the QR carries them); with
     * stored [creds] the same group is re-created. [freqMhz] asks for a specific operating frequency instead of the
     * 5 GHz band (6 GHz channels start at 5955 MHz); if the radio refuses it the 5 GHz band request is used and the
     * outcome is in [DirectState.frequencyMhz] next to [DirectState.requestedMhz].
     */
    suspend fun host(creds: P2pCreds? = null, freqMhz: Int? = null): P2pCreds = lock.withLock {
        val ctx = AppGraph.appContext
        val mgr = manager(ctx)
        val ch = channel(ctx, mgr)
        val nodeId = AppGraph.registry.self.value.nodeId
        val c = creds ?: P2pCreds(Pairing.directSsid(nodeId), Pairing.randomPassphrase())
        // These phones time-slice the radio when the group is on another channel than the Wi-Fi station (multi-channel
        // concurrency: round trips of 100 ms and more, measured), so by default the group asks for the station's own
        // 5 GHz channel. freqMhz <= 0 asks for the plain 5 GHz band and lets the framework choose.
        val freqMhz: Int? = when {
            freqMhz == null -> stationFrequency5g(ctx)
            freqMhz <= 0 -> null
            else -> freqMhz
        }
        _state.value = DirectState(role = "host", phase = "starting", ssid = c.ssid, requestedMhz = freqMhz)
        val t0 = System.nanoTime()
        try {
            clearExisting(mgr, ch)
            fun config(withFreq: Boolean): WifiP2pConfig = WifiP2pConfig.Builder()
                .setNetworkName(c.ssid)
                .setPassphrase(c.pass)
                .apply { if (withFreq && freqMhz != null) setGroupOperatingFrequency(freqMhz) else setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ) }
                .enablePersistentMode(false)
                .build()
            var usedFreq = freqMhz != null
            try {
                action("createGroup") { mgr.createGroup(ch, config(usedFreq), it) }
            } catch (e: P2pException) {
                delay(1_000)
                clearExisting(mgr, ch)
                if (usedFreq && e.reason != WifiP2pManager.BUSY) usedFreq = false // that frequency is not allowed here: fall back to the band request
                action("createGroup") { mgr.createGroup(ch, config(usedFreq), it) }
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
                staConnected = staConnected(ctx), requestedMhz = freqMhz,
            )
            hostCreds = c
            store(StoredDirect("host", c.ssid, c.pass))
            Log.i(TAG, "p2p host up ${g.networkName} ${g.`interface`} ${local.address} f=$f (asked ${freqMhz ?: "5 GHz band"}) in $ms ms")
            c
        } catch (t: Throwable) {
            _state.value = DirectState(role = "host", phase = "failed", ssid = c.ssid, error = t.message ?: t.toString(), requestedMhz = freqMhz)
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
            if (owner == null || local == null) {
                // the negotiation is still pending in the framework and keeps scanning the radio (300 to 670 ms spikes on the LAN): end it
                runCatching { action("cancelConnect") { mgr.cancelConnect(ch, it) } }
                throw P2pException(-3, "did not join group ${creds.ssid} within 30 s")
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            val f = g?.let { frequencyOf(it) }
            _state.value = DirectState(
                role = "client", phase = "up", ssid = creds.ssid, iface = local.ifName, localAddress = local.address,
                ownerAddress = owner, frequencyMhz = f, band = LinkInfo.bandOf(f), formationMs = ms, staConnected = staConnected(ctx),
            )
            hostCreds = null
            store(StoredDirect("client", creds.ssid, creds.pass))
            Log.i(TAG, "p2p client up ${creds.ssid} ${local.ifName} ${local.address} owner=$owner f=$f in $ms ms")
            owner
        } catch (t: Throwable) {
            _state.value = DirectState(role = "client", phase = "failed", ssid = creds.ssid, error = t.message ?: t.toString())
            throw t
        }
    }

    /**
     * Pick up a group that outlived the app process (the system keeps it) so [state], the QR payload and probes
     * stay consistent. Returns the credentials when this phone owns the group (the owner can read the passphrase).
     */
    suspend fun refresh(): P2pCreds? = lock.withLock {
        val ctx = AppGraph.appContext
        val mgr = manager(ctx)
        val ch = channel(ctx, mgr)
        val g = groupInfo(mgr, ch) ?: return@withLock null
        val local = LinkRoute.localLinks().firstOrNull { it.ifName == g.`interface` }
        val owner = if (g.isGroupOwner) local?.address else connectionInfo(mgr, ch)?.groupOwnerAddress?.hostAddress
        val f = frequencyOf(g)
        _state.value = DirectState(
            role = if (g.isGroupOwner) "host" else "client", phase = "up", ssid = g.networkName, iface = g.`interface`,
            localAddress = local?.address, ownerAddress = owner, frequencyMhz = f, band = LinkInfo.bandOf(f), staConnected = staConnected(ctx),
        )
        val creds = if (g.isGroupOwner && !g.passphrase.isNullOrEmpty()) P2pCreds(g.networkName, g.passphrase) else null
        hostCreds = creds
        if (creds != null && stored()?.role != "host") store(StoredDirect("host", creds.ssid, creds.pass)) // so a lost group can be re-created
        creds
    }

    /**
     * Test hook: remove the group at the system level and nothing else, as if it vanished from outside (the host left, the
     * radio was reset). State and stored credentials are untouched, so [watch] has to notice and re-form it.
     */
    suspend fun dropFromOutside() {
        val ctx = AppGraph.appContext
        val mgr = manager(ctx)
        val ch = channel(ctx, mgr)
        if (groupInfo(mgr, ch) != null) runCatching { action("removeGroup") { mgr.removeGroup(ch, it) } }
    }

    /** The user ended the group: leave it and forget the credentials so nothing re-forms it. */
    suspend fun stop() = lock.withLock {
        val ctx = AppGraph.appContext
        val mgr = manager(ctx)
        val ch = channel(ctx, mgr)
        forget()
        hostCreds = null
        runCatching { clearExisting(mgr, ch) }
        _state.value = DirectState()
    }

    /**
     * Keep [state] true and re-form lost groups. Every 3 s: if the state says up but the system has no group, the group
     * was lost (removed from outside, the host left, Wi-Fi was toggled): state goes to idle with [DirectState.lostAtMs],
     * and a host re-creates the group from the stored credentials. A client joins again when [DirectRendezvous]
     * hears its host.
     */
    @Synchronized
    fun watch(scope: CoroutineScope) {
        if (watchJob?.isActive == true) return
        watchJob = scope.launch {
            var hostRetryAt = 0L
            while (isActive) {
                delay(3_000)
                runCatching {
                    val ctx = AppGraph.appContext
                    val mgr = manager(ctx)
                    val ch = channel(ctx, mgr)
                    val s = _state.value
                    val g = groupInfo(mgr, ch)
                    if (s.phase == "up" && g == null && !lock.isLocked) {
                        Log.w(TAG, "p2p group lost (was ${s.role} ${s.ssid})")
                        hostCreds = null
                        _state.value = DirectState(error = "group lost", lostAtMs = System.currentTimeMillis(), ssid = s.ssid)
                    } else if (s.phase == "idle" && g != null && !lock.isLocked) {
                        refresh() // a group appeared (made by the system or by an earlier process)
                    }
                    // a client that is in the group (also one picked up after an app restart) keeps its path to the owner remembered
                    _state.value.let { cur -> if (cur.phase == "up" && cur.role == "client") cur.ownerAddress?.let { PeerLinks.remember(it, com.swarmos.app.core.Ports.NODE) } }
                    val st = stored()
                    if (g == null && st?.role == "host" && !lock.isLocked && _state.value.phase != "starting" && System.currentTimeMillis() >= hostRetryAt) {
                        hostRetryAt = System.currentTimeMillis() + 20_000
                        Log.i(TAG, "re-creating the stored group ${st.ssid}")
                        runCatching { host(P2pCreds(st.ssid, st.pass)) }
                    }
                    if (g == null && st?.role == "client" && !lock.isLocked) {
                        // a stored client re-joins when any paired peer is up (the host is among them)
                        PeerLinks.peers.value.filter { it.state == LinkState.ONLINE }.map { it.nodeId }.distinct().forEach {
                            DirectRendezvous.onPairedPeerHeard(it, hostsDirect = false)
                        }
                    }
                }
            }
        }
    }

    /** Ends [watch] (the link runtime stopped); a later [watch] starts a fresh loop. */
    @Synchronized
    fun unwatch() {
        watchJob?.cancel()
        watchJob = null
    }
}

/**
 * Decides when a phone with no Wi-Fi Direct group should join one, with no QR and no tap: when a paired peer is heard
 * (Bluetooth advertisement or a Wi-Fi path) that hosts a group. Credentials come from this phone's stored client
 * credentials, or from the peer: `GET /v1/link/p2p-creds` returns them sealed with the pairing secret ([SecretBox]).
 * When this phone and the peer both host a group, the smaller node id keeps hosting and the other joins ([RendezvousPolicy]).
 */
object DirectRendezvous {
    private const val TAG = "SwarmLink"
    internal val policy = RendezvousPolicy()
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    suspend fun onPairedPeerHeard(nodeId: String, hostsDirect: Boolean) {
        val st = DirectLink.state.value
        val stored = DirectLink.stored()
        val selfId = AppGraph.registry.self.value.nodeId
        val step = policy.decide(selfId, nodeId, st.role, st.phase, hostsDirect, stored?.role == "client")
        if (step == RendezvousStep.SKIP || !busy.compareAndSet(false, true)) return
        policy.noteTry(nodeId)
        try {
            if (step == RendezvousStep.YIELD_AND_JOIN) {
                // both phones host: the smaller node id keeps its group, this one tears its own down (and forgets it, so the
                // watch loop does not re-create it) and joins the other's
                Log.i(TAG, "rendezvous: $nodeId hosts too and has the smaller node id; closing our group to join it")
                LinkRuntime.stopDirect()
            }
            // the host's current credentials first (it may have made a new group with a new passphrase since the last join:
            // measured, a stored passphrase that is stale keeps the client in "starting" for ever); the stored ones when it cannot be asked
            val creds = fetchCreds(nodeId) ?: DirectLink.stored()?.takeIf { it.role == "client" }?.let { P2pCreds(it.ssid, it.pass) }
            if (creds == null) {
                policy.onJoinFailed(nodeId)
                return
            }
            Log.i(TAG, "rendezvous: joining ${creds.ssid} because $nodeId was heard")
            val owner = DirectLink.join(creds)
            policy.onJoined(nodeId)
            // reach the owner over the new group; the heartbeat session for DIRECT starts from here
            PeerLinks.connect(owner, com.swarmos.app.core.Ports.NODE)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            policy.onJoinFailed(nodeId)
            Log.w(TAG, "rendezvous join failed: ${t.message}")
        } finally {
            busy.set(false)
        }
    }

    /** Ask the peer for its group credentials over whatever path reaches it (Wi-Fi, or Bluetooth when Wi-Fi is down). */
    suspend fun fetchCreds(nodeId: String): P2pCreds? {
        val host = PeerLinks.pathsOf(nodeId).firstOrNull { it.state == LinkState.ONLINE }?.ip ?: return null
        val text = runCatching { AppGraph.nodeClient.get<CredsBlob>(host, "/link/p2p-creds", 6_000) }.getOrNull() ?: return null
        val plain = SecretBox.open(AppGraph.pairingSecret, text.blob, nodeId) ?: return null
        return runCatching { SwarmJson.decodeFromString<P2pCreds>(String(plain)) }.getOrNull()
    }
}

@Serializable
data class CredsBlob(val blob: String)
