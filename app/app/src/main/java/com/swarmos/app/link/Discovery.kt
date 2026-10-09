package com.swarmos.app.link

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import com.swarmos.app.core.Ports
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import kotlin.coroutines.resume

/**
 * NSD on a shared LAN: registers `_swarmos._tcp` (port = node port, TXT nodeId and name) and browses for the
 * same type. Every resolved endpoint with this build's node port is handed to [PeerLinks], which does the
 * hello plus signed health trust check.
 */
@Suppress("DEPRECATION")
class Discovery(ctx: Context, private val scope: CoroutineScope, private val selfId: () -> String) {
    private val nsd = ctx.getSystemService(NsdManager::class.java)
    private val resolveLock = Mutex()
    private var reg: NsdManager.RegistrationListener? = null
    private var disc: NsdManager.DiscoveryListener? = null
    private var refreshJob: Job? = null
    @Volatile private var running = false

    private var nodeId = ""
    private var name = ""
    private var port = 0

    @Synchronized
    fun start(nodeId: String, name: String, port: Int) {
        this.nodeId = nodeId; this.name = name; this.port = port
        running = true
        register()
        startBrowse()
        refreshJob = scope.launch {
            // some ROMs let browse go quiet; a fresh browse replays every live service
            while (isActive) {
                delay(REFRESH_MS)
                restartBrowse()
            }
        }
    }

    @Synchronized
    fun rename(newName: String) {
        if (!running || newName == name) return
        name = newName
        register()
    }

    @Synchronized
    fun stop() {
        running = false
        refreshJob?.cancel()
        reg?.let { runCatching { nsd.unregisterService(it) } }
        disc?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        reg = null
        disc = null
    }

    @Synchronized
    private fun register() {
        reg?.let { runCatching { nsd.unregisterService(it) } }
        val info = NsdServiceInfo().apply {
            serviceName = "swarmos-$nodeId-$port"
            serviceType = SERVICE_TYPE
            this.port = this@Discovery.port
            setAttribute("nodeId", nodeId)
            setAttribute("name", name)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(si: NsdServiceInfo) { Log.i(TAG, "nsd registered ${si.serviceName}") }
            override fun onRegistrationFailed(si: NsdServiceInfo, code: Int) { Log.w(TAG, "nsd register failed $code") }
            override fun onServiceUnregistered(si: NsdServiceInfo) {}
            override fun onUnregistrationFailed(si: NsdServiceInfo, code: Int) {}
        }
        reg = l
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { Log.w(TAG, "nsd register threw $it") }
    }

    @Synchronized
    private fun startBrowse() {
        if (!running) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) { Log.i(TAG, "nsd browse started") }
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) {
                Log.w(TAG, "nsd browse failed $code")
                scope.launch { delay(5_000); restartBrowse() }
            }
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
            override fun onServiceLost(si: NsdServiceInfo) {}
            override fun onServiceFound(si: NsdServiceInfo) {
                if (si.serviceName.startsWith("swarmos-$nodeId-")) return // our own registration
                scope.launch { resolveAndConnect(si) }
            }
        }
        disc = l
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { Log.w(TAG, "nsd browse threw $it") }
    }

    @Synchronized
    private fun restartBrowse() {
        if (!running) return
        disc?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        disc = null
        scope.launch { delay(1_000); startBrowse() }
    }

    private suspend fun resolveAndConnect(si: NsdServiceInfo) {
        val r = resolveLock.withLock {
            withTimeoutOrNull(5_000) {
                suspendCancellableCoroutine<NsdServiceInfo?> { cont ->
                    runCatching {
                        nsd.resolveService(si, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(s: NsdServiceInfo, code: Int) { if (cont.isActive) cont.resume(null) }
                            override fun onServiceResolved(s: NsdServiceInfo) { if (cont.isActive) cont.resume(s) }
                        })
                    }.onFailure { if (cont.isActive) cont.resume(null) }
                }
            }
        } ?: return
        val peerId = r.attributes["nodeId"]?.let { String(it) }
        if (peerId == selfId()) return
        val addr = if (Build.VERSION.SDK_INT >= 34) {
            r.hostAddresses.firstOrNull { it is Inet4Address }
        } else {
            r.host?.takeIf { it is Inet4Address }
        } ?: return
        val host = addr.hostAddress ?: return
        if (r.port != Ports.NODE) return // another build variant on that phone
        Log.i(TAG, "nsd resolved ${r.serviceName} -> $host:${r.port}")
        PeerLinks.remember(host, r.port)
        PeerLinks.connect(host, r.port)
    }

    companion object {
        const val SERVICE_TYPE = "_swarmos._tcp"
        private const val TAG = "SwarmLink"
        private const val REFRESH_MS = 60_000L
    }
}
