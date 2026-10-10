package com.swarmos.app.memcloud

import com.swarmos.app.llm.Role
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** This phone is running rpc-server for another phone's model. Shown in the Memory tab for as long as it is listening. */
data class Lending(
    val driverId: String?,
    val driverName: String,
    val selfName: String,
    val bind: String,
    /** "direct link" or "Wi-Fi network". */
    val link: String,
    val sinceMs: Long,
    val leaseMs: Long,
) {
    val sentence: String get() = "$selfName is lending memory to $driverName on the $link ($bind)."
    val leaseSentence: String get() = "It stops by itself ${leaseMs / 1000} s after $driverName last renewed the loan."
}

/** What the lender does to the phone, so the lease runs on a desk with a fake clock (RpcLenderTest) and on the phone through [AndroidLenderEnv]. */
interface LenderEnv {
    fun nowMs(): Long
    fun selfName(): String

    /** Addresses rpc-server may bind to: Wi-Fi, hotspot and Direct interfaces from the link module's advertisable set, never cellular. */
    fun linkAddresses(): List<LinkAddr>
    fun peerName(nodeId: String?): String?
    fun threads(): Int

    /** Starts rpc-server (the shipped binary) and returns true once it is READY. */
    suspend fun startRpc(bind: String, port: Int, threads: Int, cache: Boolean): Boolean
    fun rpcReady(): Boolean
    fun rpcBind(): String?
    fun rpcPort(): Int
    fun stopRpc()

    fun modelFileExists(file: String): Boolean
    fun restoreLocal(role: Role, file: String)

    /** Memory this phone has available now, in MB, and its thermal status (0 cool .. 6 shutdown). */
    fun memAvailMb(): Long
    fun thermalStatus(): Int

    /** The chat and embedding servers of this phone that are not stopped, with the file each serves and its resident memory. */
    fun ownModels(): List<RestoreItem>
    fun stopOwn(role: Role)

    fun setActivity(owner: String, text: String, onStop: () -> Unit)
    fun clearActivity(owner: String)
    fun audit(kind: String, vararg kv: Pair<String, String>)
}

/**
 * Worker side of MemoryCloud: this phone lends its memory to another phone's model by running llama.cpp's rpc-server.
 *
 * Trust model: rpc-server has no authentication, so whoever can open its port can read and write tensors on this phone. It is
 * therefore (1) started only by a signed `/rpc/start`, i.e. by a phone that holds the pairing secret, (2) bound to one address,
 * the link address the driver reached us on, which must be one of this phone's advertisable Wi-Fi or Direct addresses (never
 * 0.0.0.0, loopback or cellular), (3) a loan with a lease: the driver renews it every few seconds and it ends [LEASE_MS] after
 * the last renewal whatever the driver's heartbeat says, (4) stopped on `/rpc/stop` when the split ends. Whenever it stops, the
 * model servers that were stopped to make room are restarted from the list the driver sent with the start request, and one
 * driver at a time can hold the loan. Anyone on the same link who knows the port can still talk to rpc-server while it
 * listens; that is the residual risk, and why the lease is short.
 */
class RpcLender(private val env: LenderEnv) {
    companion object {
        /** The loan ends this long after the last renewal. */
        const val LEASE_MS = 10_000L
        const val ACTIVITY_OWNER = "memcloud.lend"

        /** A phone at this thermal status (severe) or above does not lend memory. */
        const val THERMAL_REFUSE = 4
    }

    sealed interface StartResult {
        /** [stopped]: the servers this phone stopped itself (only when the request asked it to), null otherwise. */
        data class Ok(val ready: Boolean, val stopped: List<RestoreItem>? = null) : StartResult
        data class Refused(val message: String) : StartResult
    }

    sealed interface RenewResult {
        data class Ok(val msLeft: Long) : RenewResult
        data class Refused(val message: String) : RenewResult
    }

    private val _lending = MutableStateFlow<Lending?>(null)
    val lending: StateFlow<Lending?> = _lending.asStateFlow()

    @Volatile private var leaseUntilMs = 0L
    @Volatile private var restoreItems: List<RestoreItem> = emptyList()

    /** What ending the loan would bring back right now (for tests and the state route). */
    internal val restoring: List<RestoreItem> get() = restoreItems

    fun leaseMsLeft(): Long? = if (_lending.value == null) null else (leaseUntilMs - env.nowMs()).coerceAtLeast(0)

    suspend fun start(req: RpcStartRequest, viaAddress: String?, fromNode: String?): StartResult {
        val bind = (req.bind ?: viaAddress)?.trim().orEmpty()
        if (bind.isEmpty() || bind == "0.0.0.0" || bind == "::" || bind.startsWith("127.")) {
            return StartResult.Refused("rpc-server is never bound to '$bind': it needs the address of the link the split uses")
        }
        val link = env.linkAddresses().firstOrNull { it.address == bind }
            ?: return StartResult.Refused("$bind is not a Wi-Fi or Direct link address of this phone")
        val cur = _lending.value
        if (cur != null && cur.driverId != fromNode) return StartResult.Refused("already lending memory to ${cur.driverName}")
        val where = if (link.direct) "direct link" else "Wi-Fi network"
        val driverName = env.peerName(fromNode) ?: fromNode ?: "another phone"

        // names from the request are reduced to a file name and must exist in the models folder
        val items = req.restore.mapNotNull { i ->
            val role = Role.parse(i.role) ?: return@mapNotNull null
            val clean = ModelFiles.clean(i.file) ?: return@mapNotNull null
            if (role == Role.RPC || !env.modelFileExists(clean)) null else RestoreItem(role.tag, clean, i.mb)
        }

        val sameBind = env.rpcReady() && env.rpcPort() == req.port && env.rpcBind() == bind
        val own = if (req.stopOwn) env.ownModels() else emptyList()

        // This phone looks at itself before it lends anything. A loan that already runs is not asked again: its own weights fill the memory.
        if (!sameBind) {
            val temp = env.thermalStatus()
            if (temp >= THERMAL_REFUSE) return StartResult.Refused("this phone is too hot to lend memory (thermal status $temp, the limit is ${THERMAL_REFUSE - 1})")
            req.needMb?.let { need ->
                // the servers this phone is about to stop count as free; when the driver stopped them already they are in MemAvailable
                val have = env.memAvailMb() + own.sumOf { it.mb ?: 0L }
                if (have < need) return StartResult.Refused("this phone has about $have MB available and the split asks for $need MB")
            }
        }

        var stopped: List<RestoreItem>? = null
        if (req.stopOwn) {
            // Order matters: the restore list is recorded first, then the servers are stopped, then rpc-server starts. A driver that
            // disappears at any point after this leaves a loan whose end (lease, stop, a failed start) brings these servers back.
            restoreItems = (own + items + restoreItems).distinctBy { it.role }
            for (m in own) Role.parse(m.role)?.let { env.stopOwn(it) }
            stopped = own
        } else if (items.isNotEmpty()) {
            restoreItems = items
        }
        val ready = try {
            sameBind || env.startRpc(bind, req.port, req.threads ?: env.threads(), req.cache)
        } catch (e: Throwable) {
            if (stopped != null) stop("rpc-server did not start")
            throw e
        }
        if (!ready) {
            if (stopped != null) stop("rpc-server did not start") // the servers this phone stopped for the loan come back now
            return StartResult.Ok(false)
        }
        val now = env.nowMs()
        leaseUntilMs = now + LEASE_MS
        val since = if (sameBind) cur?.sinceMs ?: now else now
        _lending.value = Lending(fromNode, driverName, env.selfName(), bind, where, since, LEASE_MS)
        env.setActivity(ACTIVITY_OWNER, "Lending memory to $driverName.") { stop("stopped from the banner") }
        if (!sameBind) env.audit("memcloud.rpc.start", "driver" to (fromNode ?: "?"), "bind" to bind, "link" to where)
        return StartResult.Ok(true, stopped)
    }

    /** The driver extends the loan; only the phone that started it may. */
    fun renew(fromNode: String?): RenewResult {
        val cur = _lending.value ?: return RenewResult.Refused("this phone is not lending memory")
        if (cur.driverId != fromNode) return RenewResult.Refused("the loan belongs to ${cur.driverName}")
        if (!env.rpcReady()) {
            stop("rpc-server is not running")
            return RenewResult.Refused("rpc-server is not running")
        }
        leaseUntilMs = env.nowMs() + LEASE_MS
        return RenewResult.Ok(LEASE_MS)
    }

    /** Stops rpc-server and brings back the model servers the driver stopped to make room. Safe to call when not lending. */
    fun stop(reason: String) {
        val had = _lending.value
        env.stopRpc()
        _lending.value = null
        env.clearActivity(ACTIVITY_OWNER)
        val items = restoreItems
        restoreItems = emptyList()
        for (i in items) {
            val role = Role.parse(i.role) ?: continue
            if (env.modelFileExists(i.file)) env.restoreLocal(role, i.file)
        }
        if (had != null) env.audit("memcloud.rpc.stop", "reason" to reason, "restored" to items.size.toString())
    }

    /** Call about once a second: ends the loan when the lease has run out, or when rpc-server died on its own. */
    fun tick() {
        val cur = _lending.value ?: return
        if (!env.rpcReady()) {
            stop("rpc-server stopped")
            return
        }
        if (env.nowMs() >= leaseUntilMs) stop("lease of ${cur.leaseMs / 1000} s expired: ${cur.driverName} did not renew it")
    }
}
