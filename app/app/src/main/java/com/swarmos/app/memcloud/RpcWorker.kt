package com.swarmos.app.memcloud

import com.swarmos.app.AppGraph
import com.swarmos.app.llm.LlamaRuntime
import com.swarmos.app.llm.ProcInfo
import com.swarmos.app.llm.Role
import com.swarmos.app.llm.ServerState
import com.swarmos.app.core.localIpv4
import java.io.File
import java.net.NetworkInterface

/**
 * Worker side of MemoryCloud: this phone lends its memory to another phone's model by running llama.cpp's rpc-server.
 * Freeing memory (stopping this phone's own chat and embedding servers) is done by the coordinator through the
 * /v1/models routes before it calls /v1/memcloud/rpc/start, and undone through the same routes afterwards.
 */
object RpcWorker {
    /** rpc-server's on-disk tensor cache: LlamaRuntime sets LLAMA_CACHE to filesDir/llama-cache and rpc-server adds /rpc. */
    fun cacheDir(): File = File(File(AppGraph.appContext.filesDir, "llama-cache"), "rpc")

    /** Addresses of this phone's network interfaces, to refuse a bind address that is not ours. */
    internal fun ownAddresses(): Set<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { n -> n.inetAddresses.toList().mapNotNull { it.hostAddress } }.toSet()
    }.getOrDefault(emptySet())

    sealed interface StartResult {
        data class Ok(val state: RpcStateDto) : StartResult
        data class Refused(val message: String) : StartResult
    }

    /**
     * Starts rpc-server on [req].bind (default: this phone's link address) with the tensor cache on. Idempotent: when it
     * is already READY on the same address and port it is left alone, so a re-plan does not restart healthy workers.
     */
    suspend fun start(req: RpcStartRequest): StartResult {
        val bind = req.bind ?: localIpv4()
        if (bind != "0.0.0.0" && bind !in ownAddresses()) return StartResult.Refused("$bind is not an address of this phone")
        val cur = LlamaRuntime.statusOf(Role.RPC)
        val sameBind = cur.args.let { a -> a.lastIndexOf("-H").takeIf { it >= 0 }?.let { a.getOrNull(it + 1) } } == bind
        if (cur.state == ServerState.READY && cur.port == req.port && sameBind) return StartResult.Ok(state())
        val extra = buildList {
            add("-H"); add(bind)
            add("-t"); add(req.threads.toString())
            if (req.cache) add("-c")
        }
        LlamaRuntime.start(Role.RPC, null, extra, req.port)
        return StartResult.Ok(state())
    }

    fun stop(): RpcStateDto {
        LlamaRuntime.stop(Role.RPC)
        return state()
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
            bind = st.args.let { a -> a.lastIndexOf("-H").takeIf { it >= 0 }?.let { a.getOrNull(it + 1) } },
            pid = st.pid,
            rssMb = if (running) LlamaRuntime.rssKb(Role.RPC)?.div(1024) else null,
            anonMb = anon?.div(1024),
            cachedBytes = cache.first,
            cachedFiles = cache.second,
            reason = st.reason,
            lastLog = LlamaRuntime.logTail(Role.RPC, 8),
            thermalStatus = health.thermalStatus,
            ramAvailMb = health.ramAvailMb,
            args = st.args,
        )
    }

    private fun cacheStats(): Pair<Long, Int> {
        val files = cacheDir().listFiles { f -> f.isFile } ?: return 0L to 0
        return files.sumOf { it.length() } to files.size
    }

    private fun procKb(pid: Long, key: String): Long? = try {
        ProcInfo.statusKb(File("/proc/$pid/status").readText(), key)
    } catch (_: Exception) {
        null
    }
}
