package com.swarmos.app.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.swarmos.app.AppGraph
import com.swarmos.app.core.NodeHealth
import com.swarmos.app.core.Ports
import com.swarmos.app.core.SwarmJson
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class BleState(
    val adapter: String,            // off | on | unsupported | no permission
    val advertising: Boolean,
    val scanning: Boolean,
    val seen: List<String>,         // paired phones heard in the last 30 s (node ids)
    val connected: List<String>,    // node ids with an open Bluetooth channel
    val lastError: String? = null,
)

/**
 * Bluetooth LE for SwarmLink, never for bulk data:
 *  - rendezvous: every phone advertises its node id and a tag keyed by the pairing secret and scans for the same,
 *    so a paired phone nearby is recognised with no Wi-Fi at all (and an unpaired one is ignored);
 *  - control path: the phone with the smaller node id opens a GATT connection to the other and both sides run the
 *    same signed requests over it ([BleRpc]), carrying heartbeats and requests up to [BT_MAX_BODY] bytes. The
 *    receiving side replays the request against its own node server on loopback, so signature check, dedup and
 *    handlers are exactly the ones used over Wi-Fi.
 * Appears to the rest of SwarmLink as path BT ([SmallTransport]).
 */
@SuppressLint("MissingPermission")
object BleLink : SmallTransport {
    private const val TAG = "SwarmLink"
    val SVC: UUID = UUID.fromString("5a1f0000-7b1c-4c55-9a52-5e0a0c6f0001")
    private val RX: UUID = UUID.fromString("5a1f0001-7b1c-4c55-9a52-5e0a0c6f0001") // central writes, peripheral receives
    private val TX: UUID = UUID.fromString("5a1f0002-7b1c-4c55-9a52-5e0a0c6f0001") // peripheral notifies, central receives
    private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val ADV_DATA = ParcelUuid.fromString("0000fe5a-0000-1000-8000-00805f9b34fb")
    private const val WANT_MTU = 247
    private const val MAX_RESPONSE = 8 * 1024

    private lateinit var ctx: Context
    private lateinit var scope: CoroutineScope
    private var manager: BluetoothManager? = null
    private var adapter: BluetoothAdapter? = null
    private var server: BluetoothGattServer? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var txChar: BluetoothGattCharacteristic? = null
    @Volatile private var running = false
    @Volatile private var radioUp = false
    @Volatile private var advertising = false
    @Volatile private var scanning = false
    @Volatile private var lastError: String? = null
    private var advCallback: AdvertiseCallback? = null
    private var scanCallback: ScanCallback? = null
    private var advFlags = -1

    private class Seen(val device: BluetoothDevice, var rssi: Int, var flags: Int, var atMs: Long, var actedMs: Long = 0)
    private val seen = ConcurrentHashMap<String, Seen>()

    /** One open channel to a peer, as central (we connected) or peripheral (it connected to our server). */
    private class Chan(val central: Boolean, val device: BluetoothDevice) {
        @Volatile var peerId: String? = null
        @Volatile var mtu = 23
        var gatt: BluetoothGatt? = null
        var rpc: BleRpc? = null
        @Volatile var notifyOn = false
        @Volatile var ready = false
        val sendLock = Mutex()
        val writeDone = Channel<Int>(Channel.CONFLATED)
        val reasm = BleReassembler { rpc?.onMessage(it) }
    }

    private val chans = ConcurrentHashMap<String, Chan>()          // by device address
    private val byNode = ConcurrentHashMap<String, Chan>()         // by node id once known
    private val connecting = ConcurrentHashMap<String, Long>()

    private val _state = MutableStateFlow(BleState("off", false, false, emptyList(), emptyList()))
    val state: StateFlow<BleState> = _state.asStateFlow()

    private fun publish() {
        val now = System.currentTimeMillis()
        _state.value = BleState(
            adapter = when {
                adapter == null -> "unsupported"
                !hasPermissions() -> "no permission"
                radioUp -> "on"
                else -> "off"
            },
            advertising = advertising, scanning = scanning,
            seen = seen.filter { now - it.value.atMs < 30_000 }.keys.sorted(),
            connected = byNode.keys.sorted(), lastError = lastError,
        )
    }

    private fun hasPermissions(): Boolean = Build.VERSION.SDK_INT < 31 || listOf(
        android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_ADVERTISE, android.Manifest.permission.BLUETOOTH_CONNECT,
    ).all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

    private val selfId get() = AppGraph.registry.self.value.nodeId

    @Synchronized
    fun start(context: Context, scope: CoroutineScope) {
        if (running) return
        running = true
        ctx = context.applicationContext
        this.scope = scope
        manager = ctx.getSystemService(BluetoothManager::class.java)
        adapter = manager?.adapter
        PeerLinks.btTransport = this
        scope.launch { supervise() }
    }

    @Synchronized
    fun stop() {
        running = false
        stopRadio()
        PeerLinks.btTransport = null
    }

    /** Bring the radio side up when Bluetooth is on and permitted, take it down when not, refresh the advertisement. */
    private suspend fun supervise() {
        var n = 0
        while (running && scope.isActive) {
            val ok = adapter?.isEnabled == true && hasPermissions()
            if (ok && !radioUp) startRadio() else if (!ok && radioUp) stopRadio()
            if (radioUp) {
                val flags = currentFlags()
                if (flags != advFlags || n % 20 == 0) restartAdvertising() // 20 x 3 s: the tag rotates every minute
                if (!scanning) startScan()
                maintainConnections()
            }
            publish()
            n++
            delay(3_000)
        }
    }

    private fun currentFlags(): Int {
        val d = DirectLink.state.value
        return (if (d.role == "host" && d.phase == "up") BleAdv.FLAG_DIRECT_HOST else 0) or
            (if (AppForeground.foreground.value) BleAdv.FLAG_FOREGROUND else 0)
    }

    // ---------------------------------------------------------------- radio

    private fun startRadio() {
        val m = manager ?: return
        val svc = BluetoothGattService(SVC, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val rx = BluetoothGattCharacteristic(
            RX, BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE, BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val tx = BluetoothGattCharacteristic(TX, BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ)
        tx.addDescriptor(BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        svc.addCharacteristic(rx); svc.addCharacteristic(tx)
        rxChar = rx; txChar = tx
        server = m.openGattServer(ctx, serverCallback)
        server?.addService(svc)
        radioUp = true
        Log.i(TAG, "ble radio up")
    }

    private fun stopRadio() {
        runCatching { advCallback?.let { adapter?.bluetoothLeAdvertiser?.stopAdvertising(it) } }
        runCatching { scanCallback?.let { adapter?.bluetoothLeScanner?.stopScan(it) } }
        chans.values.forEach { c -> runCatching { c.gatt?.disconnect(); c.gatt?.close() } }
        runCatching { server?.close() }
        server = null
        chans.clear(); byNode.clear(); connecting.clear(); seen.clear()
        advCallback = null; scanCallback = null
        advertising = false; scanning = false; radioUp = false; advFlags = -1
        publish()
    }

    private fun restartAdvertising() {
        val adv = adapter?.bluetoothLeAdvertiser ?: return
        advCallback?.let { runCatching { adv.stopAdvertising(it) } }
        val flags = currentFlags()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true).setTimeout(0).build()
        val data = AdvertiseData.Builder().setIncludeDeviceName(false).addServiceUuid(ParcelUuid(SVC)).build()
        val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(false)
            .addServiceData(ADV_DATA, BleAdv.encode(AppGraph.pairingSecret, selfId, flags)).build()
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) { advertising = true; lastError = null; publish() }
            override fun onStartFailure(errorCode: Int) { advertising = false; lastError = "advertise failed: $errorCode"; Log.w(TAG, "ble advertise failed $errorCode"); publish() }
        }
        advCallback = cb
        advFlags = flags
        runCatching { adv.startAdvertising(settings, data, scanResponse, cb) }.onFailure { lastError = "advertise: $it" }
    }

    private fun startScan() {
        val sc = adapter?.bluetoothLeScanner ?: return
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SVC)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = onSeen(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach(::onSeen) }
            override fun onScanFailed(errorCode: Int) { scanning = false; lastError = "scan failed: $errorCode"; Log.w(TAG, "ble scan failed $errorCode"); publish() }
        }
        scanCallback = cb
        scanning = true
        runCatching { sc.startScan(filters, settings, cb) }.onFailure { scanning = false; lastError = "scan: $it" }
    }

    private fun onSeen(r: ScanResult) {
        val payload = r.scanRecord?.getServiceData(ADV_DATA) ?: return
        val s = BleAdv.decode(AppGraph.pairingSecret, payload) ?: return // not paired with us
        if (s.nodeId == selfId) return
        val now = System.currentTimeMillis()
        val e = seen.compute(s.nodeId) { _, old -> old?.also { it.rssi = r.rssi; it.flags = s.flags; it.atMs = now } ?: Seen(r.device, r.rssi, s.flags, now) }!!
        if (now - e.actedMs < 2_000) return
        e.actedMs = now
        scope.launch { onPeerHeard(s.nodeId, e) }
    }

    /** A paired phone is in range: open the channel if we are the initiator, and let the Direct rendezvous react. */
    private suspend fun onPeerHeard(nodeId: String, e: Seen) {
        if (selfId < nodeId && !byNode.containsKey(nodeId) && !chans.containsKey(e.device.address)) connectTo(e.device, nodeId)
        DirectRendezvous.onPairedPeerHeard(nodeId, hostsDirect = e.flags and BleAdv.FLAG_DIRECT_HOST != 0)
    }

    private fun maintainConnections() {
        val now = System.currentTimeMillis()
        connecting.entries.removeIf { now - it.value > 20_000 }
        // a connection that never became ready is dropped so the next advertisement can retry it
        chans.values.filter { it.central && !it.ready && connecting[it.device.address] == null }.forEach { dropChan(it) }
    }

    // ---------------------------------------------------------------- central side

    private fun connectTo(device: BluetoothDevice, nodeId: String) {
        if (connecting.putIfAbsent(device.address, System.currentTimeMillis()) != null) return
        val chan = Chan(true, device).also { it.peerId = nodeId }
        chans[device.address] = chan
        Log.i(TAG, "ble connecting to $nodeId ${device.address}")
        chan.gatt = device.connectGatt(ctx, false, centralCallback(chan), BluetoothDevice.TRANSPORT_LE)
    }

    private fun centralCallback(chan: Chan) = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                if (!gatt.requestMtu(WANT_MTU)) gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "ble central disconnected status=$status")
                dropChan(chan)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) chan.mtu = mtu
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val svc = gatt.getService(SVC)
            val tx = svc?.getCharacteristic(TX)
            if (status != BluetoothGatt.GATT_SUCCESS || svc == null || tx == null || svc.getCharacteristic(RX) == null) { dropChan(chan); return }
            gatt.setCharacteristicNotification(tx, true)
            val cccd = tx.getDescriptor(CCCD)
            writeDescriptorCompat(gatt, cccd)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) channelReady(chan) else dropChan(chan)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            chan.writeDone.trySend(status)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            chan.reasm.feed(value)
        }

        @Deprecated("API 33 uses the overload with the value")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") chan.reasm.feed(characteristic.value ?: return)
        }
    }

    // ---------------------------------------------------------------- peripheral side

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "ble peripheral: ${device.address} connected")
                chans.computeIfAbsent(device.address) { Chan(false, device) }
            } else {
                chans[device.address]?.takeIf { !it.central }?.let { dropChan(it) }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) { chans[device.address]?.mtu = mtu }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor, preparedWrite: Boolean,
            responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            val chan = chans[device.address] ?: return
            if (descriptor.uuid == CCCD && value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                chan.notifyOn = true
                channelReady(chan)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean,
            responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            chans[device.address]?.reasm?.feed(value)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) { chans[device.address]?.writeDone?.trySend(status) }
    }

    // ---------------------------------------------------------------- channel

    private fun channelReady(chan: Chan) {
        synchronized(chan) {
            if (chan.ready) return
            chan.ready = true
        }
        connecting.remove(chan.device.address)
        chan.rpc = BleRpc(
            scope, send = { bytes -> sendMessage(chan, bytes) }, handler = ::handleIncoming,
            onIdentity = { id -> onIdentity(chan, id) },
        )
        // the central already knows who it dialled (node id from the advertisement); a notification that arrives before its
        // own descriptor write completes would be lost, so it does not wait for the peripheral's identity message
        if (chan.central) onIdentity(chan, BleMsg(t = "id", n = chan.peerId))
        scope.launch { runCatching { chan.rpc?.sendIdentity(selfId, AppGraph.registry.self.value.name) } }
    }

    /** Both sides announce who they are first; then each side runs the trust handshake over the channel. */
    private fun onIdentity(chan: Chan, id: BleMsg) {
        val node = id.n ?: return
        if (node == selfId) { dropChan(chan); return }
        chan.peerId = node
        byNode[node]?.takeIf { it !== chan }?.let { dropChan(it) } // a stale channel for the same phone
        byNode[node] = chan
        publish()
        scope.launch {
            val r = PeerLinks.connectBt(node, id.nm)
            Log.i(TAG, "ble handshake with $node: $r")
        }
    }

    private fun dropChan(chan: Chan) {
        chans.remove(chan.device.address, chan)
        chan.peerId?.let { byNode.remove(it, chan) }
        connecting.remove(chan.device.address)
        chan.rpc?.close()
        chan.rpc = null
        runCatching { chan.gatt?.disconnect(); chan.gatt?.close() }
        runCatching { if (!chan.central) server?.cancelConnection(chan.device) }
        publish()
    }

    private suspend fun sendMessage(chan: Chan, bytes: ByteArray) {
        chan.sendLock.withLock {
            for (frag in BleFrame.split(bytes, (chan.mtu - 3).coerceAtLeast(20))) writeFragment(chan, frag)
        }
    }

    // API 33 moved the value into the call; older releases set it on the object. Returns 0 on success.
    @Suppress("DEPRECATION")
    private fun writeDescriptorCompat(g: BluetoothGatt, d: BluetoothGattDescriptor): Int =
        if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        else { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; if (g.writeDescriptor(d)) 0 else 1 }

    @Suppress("DEPRECATION")
    private fun writeCharacteristicCompat(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray): Int =
        if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, v, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        else { c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE; c.value = v; if (g.writeCharacteristic(c)) 0 else 1 }

    @Suppress("DEPRECATION")
    private fun notifyCompat(s: BluetoothGattServer, d: BluetoothDevice, c: BluetoothGattCharacteristic, v: ByteArray): Int =
        if (Build.VERSION.SDK_INT >= 33) s.notifyCharacteristicChanged(d, c, false, v)
        else { c.value = v; if (s.notifyCharacteristicChanged(d, c, false)) 0 else 1 }

    private suspend fun writeFragment(chan: Chan, frag: ByteArray) {
        chan.writeDone.tryReceive()
        val status: Int = if (chan.central) {
            val g = chan.gatt ?: throw IOException("not connected")
            val c = g.getService(SVC)?.getCharacteristic(RX) ?: throw IOException("no rx characteristic")
            writeCharacteristicCompat(g, c, frag)
        } else {
            val s = server ?: throw IOException("gatt server closed")
            val c = txChar ?: throw IOException("no tx characteristic")
            notifyCompat(s, chan.device, c, frag)
        }
        if (status != 0) throw IOException("bluetooth write refused ($status)")
        try { withTimeout(3_000) { chan.writeDone.receive() } } catch (e: kotlinx.coroutines.TimeoutCancellationException) { throw IOException("bluetooth write timed out") }
    }

    /** A request that arrived over Bluetooth runs against this phone's own node server on loopback. */
    private suspend fun handleIncoming(msg: BleMsg): BleMsg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val reqId = msg.h?.get(HEADER_REQ) ?: ""
        // answers made here (not by the node server) are authenticated the same way
        fun local(status: Int, body: String) = BleMsg(t = "rsp", s = status, b = body, h = mapOf(HEADER_RSP_SIG to ResponseAuth.sign(AppGraph.pairingSecret, status, reqId, body)))
        val path = msg.p ?: return@withContext local(400, """{"error":"no path"}""")
        val conn = URL("http://127.0.0.1:${Ports.NODE}$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = msg.m ?: "GET"
            conn.connectTimeout = 2_000
            conn.readTimeout = 20_000
            msg.h?.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (!msg.b.isNullOrEmpty()) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(msg.b.toByteArray()) }
            }
            val code = conn.responseCode
            val body = (if (code < 400) conn.inputStream else conn.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
            if (body.size > MAX_RESPONSE) local(413, """{"error":"response too large for bluetooth"}""")
            else BleMsg(t = "rsp", s = code, b = String(body), h = conn.getHeaderField(HEADER_RSP_SIG)?.let { mapOf(HEADER_RSP_SIG to it) })
        } finally {
            conn.disconnect()
        }
    }

    // ---------------------------------------------------------------- SmallTransport

    override suspend fun send(
        path: PeerStatus, method: String, fullPath: String, body: String, headers: Map<String, String>, timeoutMs: Long,
    ): String {
        val chan = byNode[path.nodeId]?.takeIf { it.ready } ?: throw IOException("no bluetooth channel to ${path.name}")
        val rpc = chan.rpc ?: throw IOException("bluetooth channel closing")
        return unwrap(headers, rpc.call(BleMsg(t = "req", m = method, p = fullPath, h = headers, b = body.ifEmpty { null }), timeoutMs))
    }

    /** Check the answer's HMAC (as for HTTP), then return the body or throw the HTTP error it carries. */
    private fun unwrap(requestHeaders: Map<String, String>, rsp: BleMsg): String {
        val status = rsp.s ?: 502
        val body = rsp.b ?: ""
        if (!ResponseAuth.verify(AppGraph.pairingSecret, status, requestHeaders[HEADER_REQ] ?: "", body, rsp.h?.get(HEADER_RSP_SIG))) {
            throw UntrustedResponse("the Bluetooth answer is not authenticated")
        }
        if (status !in 200..299) throw NodeHttpException(status, body)
        return body
    }

    /** Unsigned or signed GET over the channel to [nodeId] (used for hello, the trust check and the heartbeat). */
    suspend fun get(nodeId: String, fullPath: String, signed: Boolean, timeoutMs: Long): String {
        val headers = if (signed) AppGraph.nodeClient.signedHeaders(HttpMethod.Get, fullPath, "", UUID.randomUUID().toString()) else emptyMap()
        val chan = byNode[nodeId]?.takeIf { it.ready } ?: throw IOException("no bluetooth channel")
        return unwrap(headers, (chan.rpc ?: throw IOException("channel closing")).call(BleMsg(t = "req", m = "GET", p = fullPath, h = headers), timeoutMs))
    }

    /** Signed POST over the channel to [nodeId] only (never falls back to Wi-Fi): bench and tests. */
    suspend fun post(nodeId: String, fullPath: String, body: String, timeoutMs: Long): String {
        val headers = AppGraph.nodeClient.signedHeaders(HttpMethod.Post, fullPath, body, UUID.randomUUID().toString())
        val chan = byNode[nodeId]?.takeIf { it.ready } ?: throw IOException("no bluetooth channel")
        return unwrap(headers, (chan.rpc ?: throw IOException("channel closing")).call(BleMsg(t = "req", m = "POST", p = fullPath, h = headers, b = body), timeoutMs))
    }

    suspend fun health(nodeId: String, timeoutMs: Long): NodeHealth =
        SwarmJson.decodeFromString(get(nodeId, "/v1/health", true, timeoutMs))

    fun isConnected(nodeId: String): Boolean = byNode[nodeId]?.ready == true
}
