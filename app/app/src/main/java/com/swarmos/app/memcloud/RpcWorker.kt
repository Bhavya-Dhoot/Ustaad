package com.swarmos.app.memcloud

import android.util.Log
import com.swarmos.app.AppGraph
import com.swarmos.app.core.PhoneActivity
import com.swarmos.app.link.LinkRoute
import com.swarmos.app.link.LinkType
import com.swarmos.app.llm.LlamaRuntime
import com.swarmos.app.llm.ModelStore
import com.swarmos.app.llm.ModelsModule
import com.swarmos.app.llm.ProcInfo
import com.swarmos.app.llm.Role
import com.swarmos.app.llm.ServerState
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** This phone is running rpc-server for another phone's model. Shown in the Memory tab for as long as it is listening. */
data class Lending(
    val driverId: String?,
    val driverName: String,
    val bind: String,
    /** "direct link" or "Wi-Fi network". */
    val link: String,
    val sinceMs: Long,
) {
    val sentence: String get() = "${AppGraph.registry.self.value.name} is lending memory to $driverName on the $link ($bind)."
}

/**
 * Worker side of MemoryCloud: this phone lends its memory to another phone's model by running llama.cpp's rpc-server.
 *
 * Trust model: rpc-server has no authentication, so whoever can open its port can read and write tensors on this phone.
 * It is therefore (1) started only by a signed POST /v1/memcloud/rpc/start, i.e. by a phone that holds the pairing secret,
 * (2) bound to one address only, the Wi-Fi Direct group address when the driver reached us over Direct, else the LAN address
 * (never 0.0.0.0, never a cellular or loopback address), (3) stopped by POST /rpc/stop when the split ends, and (4) stopped
 * by a watchdog when the driver has not answered heartbeats for [DRIVER_LOST_MS]. When it stops, the model servers that were
 * stopped to make room are restarted from the list the driver sent with the start request. Anyone who is on that same link
 * and knows the port can still talk to rpc-server while it listens; that is the residual risk, and why the window is short.
 *
 * Freeing memory (stopping this phone's own chat and embedding servers) is done by the coordinator through
 * /v1/models/stop before it calls /v1/memcloud/rpc/start.
 */
object RpcWorker {
    private const val TAG = "MemCloudRpc"
    const val DRIVER_LOST_MS = 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _lending = MutableStateFlow<Lending?>(null)

    /** Non-null while rpc-server listens for a driver. */
    val lending: StateFlow<Lending?> = _lending.asStateFlow()

    @Volatile private var restoreItems: List<RestoreItem> = emptyList()
    private var watch: Job? = null

    /** rpc-server's on-disk tensor cache: LlamaRuntime sets LLAMA_CACHE to filesDir/llama-cache and rpc-server adds /rpc. */
    fun cacheDir(): File = File(File(AppGraph.appContext.filesDir, "llama-cache"), "rpc")

    sealed interface StartResult {
        data class Ok(val state: RpcStateDto) : StartResult
        data class Refused(val message: String) : StartResult
    }

    /**
     * Starts rpc-server on one link address with the tensor cache on. [viaAddress] is the local address the signed request
     * arrived on (used when the request names no [RpcStartRequest.bind]); [fromNode] is the sender's node id from the
     * signed headers. Idempotent: when it is already READY on the same address and port it is left alone, so a re-plan
     * does not restart healthy workers.
     */
    suspend fun start(req: RpcStartRequest, viaAddress: String?, fromNode: String?): StartResult {
        val bind = (req.bind ?: viaAddress)?.trim().orEmpty()
        if (bind.isEmpty() || bind == "0.0.0.0" || bind == "::" || bind.startsWith("127.")) {
            return StartResult.Refused("rpc-server is never bound to '$bind': it needs the address of the link the split uses")
        }
        val link = LinkRoute.localLinks().firstOrNull { it.address == bind }
            ?: return StartResult.Refused("$bind is not a Wi-Fi or Direct link address of this phone")
        val where = if (link.type == LinkType.DIRECT) "direct link" else "Wi-Fi network"
        val driverName = AppGraph.registry.peers.value.firstOrNull { it.info.nodeId == fromNode }?.info?.name ?: fromNode ?: "another phone"

        if (req.restore.isNotEmpty()) restoreItems = req.restore
        val cur = LlamaRuntime.statusOf(Role.RPC)
        val sameBind = bindOf(cur.args) == bind
        if (cur.state == ServerState.READY && cur.port == req.port && sameBind) {
            _lending.value = Lending(fromNode, driverName, bind, where, _lending.value?.sinceMs ?: System.currentTimeMillis())
            announce(driverName)
            return StartResult.Ok(state())
        }
        val extra = buildList {
            add("-H"); add(bind)
            add("-t"); add(req.threads.toString())
            if (req.cache) add("-c")
        }
        LlamaRuntime.start(Role.RPC, null, extra, req.port)
        if (LlamaRuntime.statusOf(Role.RPC).state == ServerState.READY) {
            _lending.value = Lending(fromNode, driverName, bind, where, System.currentTimeMillis())
            announce(driverName)
            audit("memcloud.rpc.start", "driver" to (fromNode ?: "?"), "bind" to bind, "link" to where)
            startWatch(fromNode)
        }
        return StartResult.Ok(state())
    }

    /** Stops rpc-server and brings back the model servers the driver stopped to make room. */
    fun stop(reason: String = "stopped"): RpcStateDto {
        watch?.cancel()
        watch = null
        val had = _lending.value
        LlamaRuntime.stop(Role.RPC)
        _lending.value = null
        PhoneActivity.clear("memcloud")
        val items = restoreItems
        restoreItems = emptyList()
        for (i in items) {
            val role = Role.parse(i.role) ?: continue
            if (ModelStore.fileOf(i.file).isFile) ModelsModule.loadModelAsync(role, i.file)
        }
        if (had != null) audit("memcloud.rpc.stop", "reason" to reason, "restored" to items.size.toString())
        return state()
    }

    /** The phone-wide activity line while rpc-server listens; its stop button ends the loan like the Memory tab's button. */
    private fun announce(driverName: String) {
        PhoneActivity.set("memcloud", "Lending memory to $driverName.") { stop("stopped from the banner") }
    }

    /** The driver's heartbeat must keep arriving: three misses mark it away (about 3 s), and 10 s away ends the loan. */
    private fun startWatch(driverId: String?) {
        watch?.cancel()
        watch = scope.launch {
            var lostSince = 0L
            while (isActive) {
                delay(1_000)
                val st = LlamaRuntime.statusOf(Role.RPC).state
                if (st != ServerState.READY && st != ServerState.STARTING) {
                    _lending.value = null
                    PhoneActivity.clear("memcloud")
                    return@launch
                }
                val driverOnline = driverId == null || AppGraph.registry.peers.value.any { it.info.nodeId == driverId && !it.away }
                if (driverOnline) { lostSince = 0L; continue }
                val now = System.currentTimeMillis()
                if (lostSince == 0L) lostSince = now
                else if (now - lostSince >= DRIVER_LOST_MS) {
                    Log.w(TAG, "driver $driverId has not answered for ${DRIVER_LOST_MS / 1000} s: stopping rpc-server")
                    stop("driver heartbeat lost for ${DRIVER_LOST_MS / 1000} s")
                    return@launch
                }
            }
        }
    }

    fun state(): RpcStateDto {
        val st = LlamaRuntime.statusOf(Role.RPC)
        val running = st.state != ServerState.STOPPED
        val cache = cacheStats()
        val anon = st.pid?.takeIf { running }?.let { procKb(it, "RssAnon") }
        val health = AppGraph.health.state.value
        return RpcStateDto(
            state = st.state.name,
            port = st.port,
            bind = bindOf(st.args),
            pid = st.pid,
            rssMb = if (running) LlamaRuntime.rssKb(Role.RPC)?.div(1024) else null,
            peakMb = if (running) LlamaRuntime.peakRssKb(Role.RPC)?.div(1024) else null,
            anonMb = anon?.div(1024),
            cachedBytes = cache.first,
            cachedFiles = cache.second,
            reason = st.reason,
            lastLog = LlamaRuntime.logTail(Role.RPC, 8),
            thermalStatus = health.thermalStatus,
            ramAvailMb = health.ramAvailMb,
            args = st.args,
            lendingTo = _lending.value?.driverName,
        )
    }

    private fun bindOf(args: List<String>): String? = args.lastIndexOf("-H").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }

    private fun cacheStats(): Pair<Long, Int> {
        val files = cacheDir().listFiles { f -> f.isFile } ?: return 0L to 0
        return files.sumOf { it.length() } to files.size
    }

    private fun procKb(pid: Long, key: String): Long? = try {
        ProcInfo.statusKb(File("/proc/$pid/status").readText(), key)
    } catch (_: Exception) {
        null
    }

    private fun audit(kind: String, vararg kv: Pair<String, String>) {
        runCatching { AppGraph.audit.append(kind, JsonObject(kv.associate { it.first to JsonPrimitive(it.second) })) }
    }
}
