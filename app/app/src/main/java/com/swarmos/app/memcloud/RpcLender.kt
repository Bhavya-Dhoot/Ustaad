package com.swarmos.app.memcloud

import com.swarmos.app.llm.Role
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

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
    /** Wall-clock time; it can be stepped by the user or the network, so nothing that measures a duration uses it. */
    fun nowMs(): Long

    /** Time since boot, never stepped: the lease is measured on this clock. */
    fun elapsedMs(): Long
    fun selfName(): String

    /** Addresses rpc-server may bind to: Wi-Fi, hotspot and Direct interfaces from the link module's advertisable set, never cellular. */
    fun linkAddresses(): List<LinkAddr>
    fun peerName(nodeId: String?): String?

    /** Whether [nodeId] is a device of this local network (a later package that knows device kinds overrides this: an away client never borrows memory). */
    fun peerIsLocalNode(nodeId: String?): Boolean = true

    /** A loan for [model] (a file name, null from an older driver) is about to start rpc-server / has ended and rpc-server is gone: the cache budget keeper's two moments. */
    fun cacheLoanStarted(model: String?) {}
    fun cacheLoanEnded() {}
    fun threads(): Int

    /** Starts rpc-server (the shipped binary) and returns true once it is READY. */
    suspend fun startRpc(bind: String, port: Int, threads: Int, cache: Boolean): Boolean
    fun rpcReady(): Boolean
    fun rpcBind(): String?
    fun rpcPort(): Int
    fun stopRpc()

    /**
     * Blocks until the rpc-server process that [stopRpc] last stopped has left the process table (it still holds the split's weights
     * until then), at most [timeoutMs]. True when it is gone or there was none. Blocking: call it off the main thread.
     */
    fun awaitRpcGone(timeoutMs: Long): Boolean

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
 * model servers that were stopped to make room are restarted from the list the driver sent with the start request. One
 * driver at a time can hold the loan: it is reserved when a start is accepted, before anything suspends, and each loan has a
 * generation token that renew and stop must carry (a driver on the older build sends none, which is accepted from the owner
 * only). Anyone on the same link who knows the port can still talk to rpc-server while it
 * listens; that is the residual risk, and why the lease is short.
 */
class RpcLender(private val env: LenderEnv, private val closingWaitMs: Long = CLOSING_WAIT_MS) {
    companion object {
        /** The loan ends this long after the last renewal. */
        const val LEASE_MS = 10_000L
        const val ACTIVITY_OWNER = "memcloud.lend"

        /** A phone at this thermal status (severe) or above does not lend memory. */
        const val THERMAL_REFUSE = 4

        /** How long ending a loan waits for rpc-server's process to leave before it loads this phone's own models again (SIGTERM, then SIGKILL after 3 s). */
        const val RPC_GONE_WAIT_MS = 5_000L

        /** How long a new loan waits for the models of the loan before it to be dispatched: that wait, the loads (each shown as starting before the next), and slack. */
        const val CLOSING_WAIT_MS = RPC_GONE_WAIT_MS + 5_000L
        private const val CLOSING_POLL_MS = 50L
    }

    sealed interface StartResult {
        /**
         * [stopped]: the servers this phone stopped itself (only when the request asked it to), null otherwise.
         * [loan]: the generation token of the loan, to be sent back on renew and stop.
         */
        data class Ok(val ready: Boolean, val stopped: List<RestoreItem>? = null, val loan: String? = null) : StartResult
        data class Refused(val message: String) : StartResult
    }

    sealed interface RenewResult {
        data class Ok(val msLeft: Long) : RenewResult
        data class Refused(val message: String) : RenewResult
    }

    sealed interface StopResult {
        object Ok : StopResult
        data class Refused(val message: String) : StopResult
    }

    /**
     * The loan one driver holds. It exists from the moment a start is accepted (before anything suspends), so a second driver is
     * refused at once; [token] names this loan, so a late renew or stop from an earlier loan of the same driver finds it has ended.
     */
    private class Loan(val driverId: String?, val driverName: String, val token: String) {
        /** True while /rpc/start is still working: the lease has not begun, and nothing may expire the loan. */
        @Volatile var starting = true
    }

    private val _lending = MutableStateFlow<Lending?>(null)
    val lending: StateFlow<Lending?> = _lending.asStateFlow()

    /** The loan, or null. Changed only under the monitor of this object. */
    @Volatile private var loan: Loan? = null
    @Volatile private var leaseUntilMs = 0L
    @Volatile private var restoreItems: List<RestoreItem> = emptyList()

    /** What ending the loan would bring back right now (for tests and the state route). */
    internal val restoring: List<RestoreItem> get() = restoreItems

    fun leaseMsLeft(): Long? = if (_lending.value == null) null else (leaseUntilMs - env.elapsedMs()).coerceAtLeast(0)

    /** [viaAddress]: the local address the signed request arrived on. [driverAddr]: the address it came from. */
    suspend fun start(req: RpcStartRequest, viaAddress: String?, fromNode: String?, driverAddr: String? = null): StartResult {
        val bind = (req.bind ?: viaAddress)?.trim().orEmpty()
        if (bind.isEmpty() || bind == "0.0.0.0" || bind == "::" || bind.startsWith("127.")) {
            return StartResult.Refused("rpc-server is never bound to '$bind': it needs the address of the link the split uses")
        }
        val link = env.linkAddresses().firstOrNull { it.address == bind }
            ?: return StartResult.Refused("$bind is not a Wi-Fi or Direct link address of this phone")
        // memory is lent over the local link only: the request arrived on a Wi-Fi or Direct address with the driver on that network, and rpc-server binds such an address (maybe another link's)
        MemCloudLogic.lendRefusal(bind, viaAddress?.trim(), driverAddr, env.linkAddresses(), env.peerIsLocalNode(fromNode))?.let { return StartResult.Refused(it) }
        val where = if (link.direct) "direct link" else "Wi-Fi network"
        val driverName = env.peerName(fromNode) ?: fromNode ?: "another phone"

        // Ownership is taken here, before the first suspension: two drivers cannot both see "no loan" and both be accepted.
        // A loan that has just ended is still bringing this phone's own models back (after rpc-server has left): a new loan waits for that
        // to be dispatched, so it does not pass its memory check on memory that is about to be taken, nor find the models loaded beside it.
        // The wait is judged inside the monitor that reserves the loan, so a loan that ends after a look outside it cannot be missed. If the
        // give-back is still pending when the time is up it is stuck, and loading beside it is the harm: the start is refused, which the owner
        // of the driver can repeat, instead of going ahead.
        var fresh = false
        var waited = 0L
        var reserved: Loan? = null
        while (reserved == null) {
            reserved = synchronized(this) {
                if (closing.get() > 0) {
                    if (waited >= closingWaitMs) return StartResult.Refused("this phone is still getting its own models back from the last loan; try again in a moment")
                    return@synchronized null
                }
                val cur = loan
                if (cur != null && cur.driverId != fromNode) return StartResult.Refused("this phone is lent to ${cur.driverName}")
                if (cur != null && cur.starting) return StartResult.Refused("this phone is still starting rpc-server for ${cur.driverName}")
                if (cur != null) {
                    cur.starting = true
                    cur
                } else {
                    fresh = true
                    Loan(fromNode, driverName, java.util.UUID.randomUUID().toString()).also { loan = it }
                }
            }
            if (reserved == null) { delay(CLOSING_POLL_MS); waited += CLOSING_POLL_MS }
        }
        val mine: Loan = reserved
        var done = false
        try {
            val r = begin(req, bind, where, driverName, fromNode, mine, fresh)
            done = r is StartResult.Ok && r.ready
            return r
        } finally {
            synchronized(this) {
                mine.starting = false
                // a start that did not end in a running loan gives the reservation back (a loan that was already running stays as it was)
                if (!done && fresh && loan === mine) {
                    loan = null
                    restoreItems = emptyList() // a driver on the older build restores its own servers when its start fails
                }
            }
        }
    }

    private suspend fun begin(req: RpcStartRequest, bind: String, where: String, driverName: String, fromNode: String?, mine: Loan, fresh: Boolean): StartResult {
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
        if (!sameBind) env.cacheLoanStarted(req.model?.let { ModelFiles.clean(it) }) // before rpc-server writes its first tensor
        val ready = try {
            sameBind || env.startRpc(bind, req.port, req.threads ?: env.threads(), req.cache)
        } catch (e: Throwable) {
            if (stopped != null) stopIf(mine, "rpc-server did not start")
            throw e
        }
        if (!ready) {
            if (stopped != null) stopIf(mine, "rpc-server did not start") // the servers this phone stopped for the loan come back now
            return StartResult.Ok(false)
        }
        synchronized(this) {
            if (loan !== mine) {
                // the loan was ended (a stop from its driver, or the banner) while rpc-server was starting
                env.stopRpc()
                return StartResult.Refused("the loan was ended while rpc-server was starting")
            }
            val now = env.nowMs() // wall clock, only for the "since" the screen shows
            leaseUntilMs = env.elapsedMs() + LEASE_MS
            val since = if (sameBind) _lending.value?.sinceMs ?: now else now
            _lending.value = Lending(fromNode, driverName, env.selfName(), bind, where, since, LEASE_MS)
        }
        env.setActivity(ACTIVITY_OWNER, "Lending memory to $driverName.") { stop("stopped from the banner") }
        if (!sameBind) env.audit("memcloud.rpc.start", "driver" to (fromNode ?: "?"), "bind" to bind, "link" to where)
        return StartResult.Ok(true, stopped, mine.token)
    }

    /**
     * The driver extends the loan; only the phone that started it may. [token] is the one /rpc/start returned; a driver on the older
     * build sends none, which is accepted because the caller is the loan's owner.
     */
    fun renew(fromNode: String?, token: String? = null): RenewResult {
        val cur = loan ?: return RenewResult.Refused("this phone is not lending memory")
        if (cur.driverId != fromNode) return RenewResult.Refused("the loan belongs to ${cur.driverName}")
        if (token != null && token != cur.token) return RenewResult.Refused("that loan has ended; this phone is lending under a newer one")
        if (cur.starting) return RenewResult.Ok(LEASE_MS) // the lease begins when the start is over
        if (!env.rpcReady()) {
            stopIf(cur, "rpc-server is not running")
            return RenewResult.Refused("rpc-server is not running")
        }
        leaseUntilMs = env.elapsedMs() + LEASE_MS
        return RenewResult.Ok(LEASE_MS)
    }

    /**
     * The driver ends the loan. Checked and done in one step under the monitor, so a stop from an earlier loan or another phone cannot
     * end a newer loan. With no loan at all it still cleans up (stops rpc-server, restores what is listed), as before.
     */
    fun stopFrom(fromNode: String?, token: String?, reason: String): StopResult {
        val ended = synchronized(this) {
            val cur = loan
            if (cur != null) {
                if (cur.driverId != fromNode) return StopResult.Refused("the loan belongs to ${cur.driverName}")
                if (token != null && token != cur.token) return StopResult.Refused("that loan has ended; this phone is lending under a newer one")
            }
            endLoan()
        }
        giveBack(ended, reason)
        return StopResult.Ok
    }

    /** What ending a loan leaves to do: the loan that was running (null when none) and the model servers to bring back. */
    private class Ended(val had: Lending?, val items: List<RestoreItem>) {
        /** Something is still to be done after the monitor is left: models to bring back, or a running loan whose cache files are to be recorded. */
        val closes: Boolean get() = items.isNotEmpty() || had != null
    }

    /** Loans that have ended and whose models are not yet dispatched ([giveBack] still waiting or loading): a new [start] waits while this is above 0. */
    private val closing = AtomicInteger(0)

    /** The loan ends and rpc-server is told to stop. Changes state only; call under the monitor, then [giveBack] outside it. */
    private fun endLoan(): Ended {
        val had = _lending.value
        env.stopRpc()
        loan = null
        _lending.value = null
        env.clearActivity(ACTIVITY_OWNER)
        val items = restoreItems
        restoreItems = emptyList()
        return Ended(had, items).also { if (it.closes) closing.incrementAndGet() } // before the monitor is left, so no new loan can slip in between
    }

    /**
     * Brings back the model servers the driver stopped to make room, once rpc-server's process is really gone: it still holds the
     * split's weights for a moment after the stop, and a chat model loaded then is refused for want of memory. The wait is bounded
     * ([RPC_GONE_WAIT_MS]) and happens outside the monitor, so a new start is not held up by it.
     */
    private fun giveBack(ended: Ended, reason: String) {
        try {
            if (ended.items.isNotEmpty()) env.awaitRpcGone(RPC_GONE_WAIT_MS)
            for (i in ended.items) {
                val role = Role.parse(i.role) ?: continue
                if (env.modelFileExists(i.file)) env.restoreLocal(role, i.file)
            }
            // the files this loan's rpc-server cached are recorded for its model once the process is gone (a new loan waits for this, see [closing])
            if (ended.had != null) env.cacheLoanEnded()
        } finally {
            if (ended.closes) closing.decrementAndGet()
        }
        if (ended.had != null) env.audit("memcloud.rpc.stop", "reason" to reason, "restored" to ended.items.size.toString())
    }

    /** Stops rpc-server and brings back the model servers the driver stopped to make room. Safe to call when not lending. */
    fun stop(reason: String) {
        giveBack(synchronized(this) { endLoan() }, reason)
    }

    /** [stop] only if [which] is still the loan: the lease timer and a failed start must not end a newer loan. */
    private fun stopIf(which: Loan, reason: String) {
        val ended = synchronized(this) { if (loan === which) endLoan() else null }
        if (ended != null) giveBack(ended, reason)
    }

    /** Call about once a second: ends the loan when the lease has run out, or when rpc-server died on its own. */
    fun tick() {
        val cur = loan ?: return
        if (cur.starting) return
        if (!env.rpcReady()) {
            stopIf(cur, "rpc-server stopped")
            return
        }
        if (env.elapsedMs() >= leaseUntilMs) stopIf(cur, "lease of ${LEASE_MS / 1000} s expired: ${cur.driverName} did not renew it")
    }
}
