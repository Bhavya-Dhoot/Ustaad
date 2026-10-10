package com.swarmos.app.memcloud

import android.os.SystemClock
import com.swarmos.app.AppGraph
import com.swarmos.app.core.PhoneActivity
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
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** [LenderEnv] on the real phone. */
object AndroidLenderEnv : LenderEnv {
    override fun nowMs() = System.currentTimeMillis()
    override fun elapsedMs() = SystemClock.elapsedRealtime() // the lease must not shrink or grow when the wall clock is stepped
    override fun selfName() = AppGraph.registry.self.value.name
    override fun linkAddresses() = AndroidSplitEnv.linkAddresses()
    override fun peerName(nodeId: String?) = AppGraph.registry.peers.value.firstOrNull { it.info.nodeId == nodeId }?.info?.name
    override fun threads() = CpuSet.threads()

    override suspend fun startRpc(bind: String, port: Int, threads: Int, cache: Boolean): Boolean {
        val extra = buildList {
            add("-H"); add(bind)
            add("-t"); add(threads.toString())
            if (cache) add("-c")
        }
        LlamaRuntime.start(Role.RPC, null, extra, port)
        return LlamaRuntime.statusOf(Role.RPC).state == ServerState.READY
    }

    override fun rpcReady() = LlamaRuntime.statusOf(Role.RPC).state == ServerState.READY
    override fun rpcBind() = RpcWorker.bindOf(LlamaRuntime.statusOf(Role.RPC).args)
    override fun rpcPort() = LlamaRuntime.statusOf(Role.RPC).port

    /** The pid of the rpc-server that [stopRpc] last stopped, until [awaitRpcGone] has seen it leave. LlamaRuntime forgets the pid at once. */
    @Volatile private var stoppedPid: Long? = null

    override fun stopRpc() {
        LlamaRuntime.statusOf(Role.RPC).pid?.let { stoppedPid = it }
        LlamaRuntime.stop(Role.RPC)
    }

    override fun awaitRpcGone(timeoutMs: Long): Boolean {
        val pid = stoppedPid ?: return true
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            val gone = try {
                !File("/proc/$pid").exists() || ProcInfo.isZombie(File("/proc/$pid/status").readText())
            } catch (_: Exception) {
                true
            }
            if (gone) { stoppedPid = null; return true }
            if (SystemClock.elapsedRealtime() >= end) return false
            Thread.sleep(50)
        }
    }

    override fun modelFileExists(file: String) = ModelStore.fileOf(file).isFile

    override fun restoreLocal(role: Role, file: String) {
        ModelsModule.loadModelAsync(role, file)
    }

    override fun ownModels(): List<RestoreItem> = listOf(Role.CHAT, Role.EMBED).mapNotNull { r ->
        val st = LlamaRuntime.statusOf(r)
        if (st.state == ServerState.STOPPED) null
        else st.modelPath?.let { RestoreItem(r.tag, File(it).name, LlamaRuntime.rssKb(r)?.div(1024)) }
    }

    override fun stopOwn(role: Role) = LlamaRuntime.stop(role)

    override fun memAvailMb() = AppGraph.health.state.value.ramAvailMb
    override fun thermalStatus() = AppGraph.health.state.value.thermalStatus

    // the banner's Stop runs on the main thread; ending a loan waits for rpc-server to leave, so it goes to the worker scope
    override fun setActivity(owner: String, text: String, onStop: () -> Unit) = PhoneActivity.set(owner, text) { RpcWorker.inBackground(onStop) }
    override fun clearActivity(owner: String) = PhoneActivity.clear(owner)

    override fun audit(kind: String, vararg kv: Pair<String, String>) {
        runCatching { AppGraph.audit.append(kind, JsonObject(kv.associate { it.first to JsonPrimitive(it.second) })) }
    }
}

/** Routes and screen talk to this; the loan logic is [RpcLender]. */
object RpcWorker {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lender by lazy { RpcLender(AndroidLenderEnv) }
    private var tickJob: Job? = null

    /** Non-null while rpc-server listens for a driver. */
    val lending: StateFlow<Lending?> get() = lender.lending

    /** rpc-server's on-disk tensor cache: LlamaRuntime sets LLAMA_CACHE to filesDir/llama-cache and rpc-server adds /rpc. */
    fun cacheDir(): File = File(File(AppGraph.appContext.filesDir, "llama-cache"), "rpc")

    /** Runs in this object's own scope: a driver that gives up or is cut off must not abort a start half way, with this phone's servers already stopped. */
    suspend fun start(req: RpcStartRequest, viaAddress: String?, fromNode: String?): RpcLender.StartResult =
        scope.async {
            lender.start(req, viaAddress, fromNode).also { if (it is RpcLender.StartResult.Ok && it.ready) ensureTicking() }
        }.await()

    fun renew(fromNode: String?, loan: String? = null): RpcLender.RenewResult = lender.renew(fromNode, loan)

    /** The driver ends its loan; refused when the loan is another phone's or a newer one. */
    fun stopFor(fromNode: String?, loan: String?): RpcLender.StopResult = lender.stopFrom(fromNode, loan, "stopped by the driver")

    /** The "Stop lending" button (main thread): ending the loan waits for rpc-server to leave, so it runs in this object's scope. */
    fun stop(reason: String) = inBackground { lender.stop(reason) }

    internal fun inBackground(block: () -> Unit) {
        scope.launch { block() }
    }

    @Synchronized
    private fun ensureTicking() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (isActive) {
                delay(1_000)
                lender.tick()
                if (lender.lending.value == null) return@launch
            }
        }
    }

    internal fun bindOf(args: List<String>): String? = args.lastIndexOf("-H").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }

    fun state(stopped: List<RestoreItem>? = null, loan: String? = null): RpcStateDto {
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
            lendingTo = lender.lending.value?.driverName,
            leaseMsLeft = lender.leaseMsLeft(),
            stoppedOwn = stopped,
            loan = loan,
        )
    }

    private fun cacheStats(): Pair<Long, Int> = RpcCache.stats(cacheDir()).let { it.bytes to it.files }

    /** Clears the tensor cache unless rpc-server is running (it may be reading those files) or this phone is lending. */
    fun clearCache(): RpcCache.ClearResult {
        val busy = when {
            lender.lending.value != null -> "this phone is lending memory to ${lender.lending.value?.driverName}; stop the loan first"
            LlamaRuntime.statusOf(Role.RPC).state.let { it == ServerState.READY || it == ServerState.STARTING } -> "rpc-server is running; stop it first" // FAILED has no process: the cache may go
            else -> null
        }
        return RpcCache.clear(cacheDir(), busy)
    }

    private fun procKb(pid: Long, key: String): Long? = try {
        ProcInfo.statusKb(File("/proc/$pid/status").readText(), key)
    } catch (_: Exception) {
        null
    }
}
