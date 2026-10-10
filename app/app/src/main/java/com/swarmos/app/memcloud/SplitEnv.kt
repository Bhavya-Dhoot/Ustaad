package com.swarmos.app.memcloud

import android.content.Context
import android.os.Looper
import android.util.Log
import com.swarmos.app.AppGraph
import com.swarmos.app.core.SwarmJson
import com.swarmos.app.link.LinkRoute
import com.swarmos.app.link.LinkType
import com.swarmos.app.link.NodeHttpException
import com.swarmos.app.link.PeerLinks
import com.swarmos.app.llm.LlamaRuntime
import com.swarmos.app.llm.LlmClient
import com.swarmos.app.llm.ModelStore
import com.swarmos.app.llm.ModelsModule
import com.swarmos.app.llm.ModelsStateDto
import com.swarmos.app.llm.Role
import com.swarmos.app.llm.ServerState
import com.swarmos.app.llm.ServerStatus
import io.ktor.http.HttpMethod
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/** A phone from the registry as the coordinator needs it. */
data class PeerInfo(
    val nodeId: String,
    val name: String,
    /** Address as the registry holds it: "ip" or "ip:port" of the Node API. */
    val addr: String,
    val away: Boolean,
    val ramAvailMb: Long?,
    val thermalStatus: Int?,
) {
    val host: String get() = addr.substringBefore(':')
}

/** One role of a peer's /v1/models/state. */
data class RoleSnap(val state: String, val file: String?, val rssMb: Long?)

/** One role of this phone's LlamaRuntime. */
data class LocalServer(val state: ServerState, val file: String?, val rssMb: Long?)

data class Health(val ramAvailMb: Long, val thermalStatus: Int)

/** What survives an app restart: what MemoryCloud stopped (to bring back), the peers it touched, an unfinished baseline. */
data class Persisted(
    val restore: Map<String, List<RestoreItem>> = emptyMap(),
    /** node id to Node API address of every peer that was sent a command. */
    val peerAddrs: Map<String, String> = emptyMap(),
    val baselinePending: String? = null,
)

/**
 * Everything the coordinator does to the outside world, so the state machine runs on a desk with fakes
 * (SplitCoordinatorTest) and on the phone through [AndroidSplitEnv].
 */
interface SplitEnv {
    fun nowMs(): Long
    fun selfId(): String
    fun selfName(): String
    fun selfHealth(): Health
    fun peers(): List<PeerInfo>

    /** Heartbeat round trip (an HTTP call) to a peer. */
    fun heartbeatRttMs(nodeId: String): Double?

    /** Median TCP connect time to host:port: one network round trip, no HTTP on top. Null when nothing answers. */
    suspend fun tcpRttMs(host: String, port: Int): Double?

    /** Threads for a llama.cpp process forked now (see [CpuSet]). */
    fun threads(): Int

    /** The file in the models folder for a name from a request: reduced with File(name).name, null unless it exists there. */
    fun modelFile(name: String): File?
    fun readHeader(file: File): GgufInfo

    suspend fun peerModels(addr: String): Map<String, RoleSnap>
    suspend fun peerStopRole(addr: String, role: String)
    suspend fun peerLoadModel(addr: String, role: String, file: String)
    suspend fun peerRpcStart(addr: String, req: RpcStartRequest): RpcStateDto
    /** [loan] is the token /rpc/start returned; null for a lender on the older build, which gave none. */
    suspend fun peerRpcRenew(addr: String, loan: String? = null): RpcStateDto

    /** Ends the loan. Throws [LoanNotOurs] when the lender answers that the loan is another phone's or a newer one. */
    suspend fun peerRpcStop(addr: String, loan: String? = null)
    suspend fun peerRpcState(addr: String): RpcStateDto
    fun reachable(host: String, port: Int): Boolean

    fun local(role: Role): LocalServer
    fun localPeakMb(role: Role): Long?
    fun stopLocal(role: Role)
    fun restoreLocal(role: Role, file: String)

    /** Starts the CHAT server with [args] after the defaults; the deferred ends READY, FAILED or superseded. */
    fun startChat(path: String, args: List<String>, readyTimeoutMs: Long): Deferred<ServerStatus>
    fun chatLog(n: Int): List<String>

    /** The chat model of this phone through the shared gate (AppGraph.gatedLlm). */
    val llm: LlmClient

    fun setActivity(owner: String, text: String, onStop: () -> Unit)
    fun clearActivity(owner: String)

    /** Blocking storage: call off the main thread. */
    fun load(): Persisted
    fun save(p: Persisted)
}

/** The real thing: AppGraph, LlamaRuntime, ModelStore, the signed Node API. */
object AndroidSplitEnv : SplitEnv {
    override fun nowMs() = System.currentTimeMillis()
    override fun selfId() = AppGraph.registry.self.value.nodeId
    override fun selfName() = AppGraph.registry.self.value.name
    override fun selfHealth() = AppGraph.health.state.value.let { Health(it.ramAvailMb, it.thermalStatus) }

    override fun peers() = AppGraph.registry.peers.value.map {
        PeerInfo(it.info.nodeId, it.info.name, com.swarmos.app.link.LinkModule.bestBulkAddress(it.info.nodeId)?.hostAddress ?: it.info.ip, it.away, it.health?.ramAvailMb, it.health?.thermalStatus)
    }

    override fun heartbeatRttMs(nodeId: String) = PeerLinks.find(nodeId)?.status?.value?.rttMs

    override suspend fun tcpRttMs(host: String, port: Int): Double? = withContext(Dispatchers.IO) {
        val samples = (1..3).mapNotNull {
            val t0 = System.nanoTime()
            try {
                Socket().use { s -> s.connect(InetSocketAddress(host, port), 1_000) }
                (System.nanoTime() - t0) / 1e6
            } catch (_: Exception) {
                null
            }
        }
        samples.sorted().getOrNull(samples.size / 2)
    }

    override fun threads() = CpuSet.threads()

    override fun modelFile(name: String): File? = ModelFiles.clean(name)?.let { ModelStore.fileOf(it) }?.takeIf { it.isFile }
    override fun readHeader(file: File) = GgufMeta.read(file)

    override suspend fun peerModels(addr: String): Map<String, RoleSnap> {
        val st = SwarmJson.decodeFromString<ModelsStateDto>(AppGraph.nodeClient.raw(HttpMethod.Get, addr, "/models/state", "", 4_000))
        return st.roles.mapValues { (_, r) -> RoleSnap(r.state, r.file, r.rssMb) }
    }

    override suspend fun peerStopRole(addr: String, role: String) {
        AppGraph.nodeClient.raw(HttpMethod.Post, addr, "/models/stop", """{"role":"$role"}""", 20_000)
    }

    override suspend fun peerLoadModel(addr: String, role: String, file: String) {
        AppGraph.nodeClient.raw(HttpMethod.Post, addr, "/models/load", """{"role":"$role","file":"$file","wait":false}""", 15_000)
    }

    override suspend fun peerRpcStart(addr: String, req: RpcStartRequest): RpcStateDto =
        SwarmJson.decodeFromString(AppGraph.nodeClient.raw(HttpMethod.Post, addr, "/memcloud/rpc/start", SwarmJson.encodeToString(req), 60_000))

    private fun loanBody(loan: String?) = if (loan == null) "" else SwarmJson.encodeToString(LoanRef(loan))

    override suspend fun peerRpcRenew(addr: String, loan: String?): RpcStateDto =
        SwarmJson.decodeFromString(AppGraph.nodeClient.raw(HttpMethod.Post, addr, "/memcloud/rpc/renew", loanBody(loan), 3_000))

    override suspend fun peerRpcStop(addr: String, loan: String?) {
        try {
            AppGraph.nodeClient.raw(HttpMethod.Post, addr, "/memcloud/rpc/stop", loanBody(loan), 10_000)
        } catch (e: NodeHttpException) {
            if (e.status == 409) throw LoanNotOurs(e.body)
            throw e
        }
    }

    override suspend fun peerRpcState(addr: String): RpcStateDto =
        SwarmJson.decodeFromString(AppGraph.nodeClient.raw(HttpMethod.Get, addr, "/memcloud/rpc/state", "", 2_500))

    override fun reachable(host: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), 1_500); true }
    } catch (_: Exception) {
        false
    }

    override fun local(role: Role): LocalServer {
        val st = LlamaRuntime.statusOf(role)
        return LocalServer(st.state, st.modelPath?.let { File(it).name }, if (st.state == ServerState.STOPPED) null else LlamaRuntime.rssKb(role)?.div(1024))
    }

    override fun localPeakMb(role: Role) = LlamaRuntime.peakRssKb(role)?.div(1024)
    override fun stopLocal(role: Role) = LlamaRuntime.stop(role)

    override fun restoreLocal(role: Role, file: String) {
        if (ModelStore.fileOf(file).isFile) ModelsModule.loadModelAsync(role, file)
    }

    override fun startChat(path: String, args: List<String>, readyTimeoutMs: Long) =
        LlamaRuntime.startAsync(Role.CHAT, path, args, readyTimeoutMs = readyTimeoutMs)

    override fun chatLog(n: Int) = LlamaRuntime.logTail(Role.CHAT, n)

    override val llm: LlmClient get() = AppGraph.gatedLlm

    override fun setActivity(owner: String, text: String, onStop: () -> Unit) {
        com.swarmos.app.core.PhoneActivity.set(owner, text, onStop)
    }

    override fun clearActivity(owner: String) = com.swarmos.app.core.PhoneActivity.clear(owner)

    private fun prefs() = AppGraph.appContext.getSharedPreferences("memcloud", Context.MODE_PRIVATE)

    override fun load(): Persisted {
        val p = prefs()
        val restore = runCatching { p.getString("restore", null)?.let { SwarmJson.decodeFromString<Map<String, List<RestoreItem>>>(it) } }.getOrNull() ?: emptyMap()
        val peers = runCatching { p.getString("peers", null)?.let { SwarmJson.decodeFromString<Map<String, String>>(it) } }.getOrNull() ?: emptyMap()
        return Persisted(restore, peers, p.getString("baseline_pending", null))
    }

    override fun save(p: Persisted) {
        val e = prefs().edit()
        e.putString("restore", SwarmJson.encodeToString(p.restore))
        e.putString("peers", SwarmJson.encodeToString(p.peerAddrs))
        if (p.baselinePending == null) e.remove("baseline_pending") else e.putString("baseline_pending", p.baselinePending)
        // The record of what is owed to other phones must be on disk when this returns, not queued: commit() waits for the write, so it
        // runs only off the main thread (every caller is on the coordinator's IO scope); on the main thread apply() is the fallback.
        if (Looper.myLooper() == Looper.getMainLooper()) e.apply()
        else if (!e.commit()) {
            Log.w("MemCloud", "the restore record was not written; trying once more")
            if (!e.commit()) Log.e("MemCloud", "the restore record could not be written")
        }
    }

    /** Link addresses a worker may bind rpc-server to: Wi-Fi, hotspot and Direct interfaces only (the link module's advertisable set), never cellular. */
    fun linkAddresses(): List<LinkAddr> = LinkRoute.advertisable().map { LinkAddr(it.address, it.type == LinkType.DIRECT) }
}

/** The lender refused a stop (HTTP 409): its loan belongs to another phone or to a newer loan of this one, so there is nothing of ours left to end. */
class LoanNotOurs(message: String) : Exception(message)

data class LinkAddr(val address: String, val direct: Boolean)

object ModelFiles {
    /** A file name from a request, reduced to its last path element; null when nothing usable is left or it is not a .gguf. */
    fun clean(name: String): String? = File(name).name.takeIf { it.isNotEmpty() && it != "." && it != ".." && it.endsWith(".gguf") }
}
