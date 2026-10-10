package com.swarmos.app.llm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger

/** True when this server answers requests with a real model: READY and a model file behind it (RPC has none). */
internal fun ServerStatus.isRealReady(): Boolean = state == ServerState.READY && modelPath != null

/**
 * The status of every [Role] together with its epoch. No Android classes, so the interleavings are unit-tested.
 *
 * The epoch of a role is bumped by every start request ([newEpoch]) and by every [stop]. Whatever acts for a server
 * (the start that launched it, the thread that waits for its exit, a pending automatic restart) remembers the epoch it
 * was started under and only writes the status through [setIfCurrent]. A stop or a newer start in between makes that
 * write a no-op, so a stale STARTING or FAILED can never overwrite the STOPPED a stop() just published.
 * [setIfCurrent] and [stop] share one lock: the epoch check and the write cannot be separated by a stop.
 */
internal class StatusBoard {
    private val epochs = Role.entries.associateWith { AtomicInteger(0) }
    private val lock = Any()
    private val _status = MutableStateFlow(Role.entries.associateWith { ServerStatus(it) })

    val status: StateFlow<Map<Role, ServerStatus>> = _status.asStateFlow()

    fun statusOf(role: Role): ServerStatus = _status.value.getValue(role)

    fun epochOf(role: Role): Int = epochs.getValue(role).get()

    fun isCurrent(role: Role, epoch: Int): Boolean = epochOf(role) == epoch

    /** A start request: supersedes every earlier start, pending automatic restart and exit report of [role]. */
    fun newEpoch(role: Role): Int = epochs.getValue(role).incrementAndGet()

    /** Unconditional write, for code that holds the role lock and has just checked its epoch. */
    fun set(role: Role, f: (ServerStatus) -> ServerStatus) {
        synchronized(lock) { _status.update { it + (role to f(it.getValue(role))) } }
    }

    /** Applies [f] only while [epoch] is still the current epoch of [role]; false (nothing written) after a stop or a newer start. */
    fun setIfCurrent(role: Role, epoch: Int, f: (ServerStatus) -> ServerStatus): Boolean = synchronized(lock) {
        if (epochOf(role) != epoch) return false
        _status.update { it + (role to f(it.getValue(role))) }
        true
    }

    /**
     * Stop: ends every start, restart and exit report of [role] that is still on the old epoch and publishes STOPPED.
     * [detach] runs under the lock and returns what the caller must kill (the running record it removed).
     */
    fun <T> stop(role: Role, detach: () -> T): T = synchronized(lock) {
        epochs.getValue(role).incrementAndGet()
        val detached = detach()
        _status.update { it + (role to ServerStatus(role, port = it.getValue(role).port)) }
        detached
    }

    /**
     * What happens after a server that was READY died without being asked to, all under [epoch] (the epoch it was started
     * under, stored in its running record): publish FAILED, then let [RestartPolicy] decide. A [stop] or a newer start at
     * any point (including during the backoff) makes every remaining step a no-op, so no stale FAILED or STARTING appears
     * and no restart follows a stop.
     * @param failedStatus builds the FAILED status from the status it replaces
     * @param wait the backoff sleep (injected so tests can interleave a stop with it)
     * @param restart starts the server again; called at most once
     */
    suspend fun reportUnexpectedExit(
        role: Role, epoch: Int, wasReady: Boolean, serviceUp: () -> Boolean, aliveMs: Long, extraArgs: List<String>, what: String,
        failedStatus: (ServerStatus) -> ServerStatus,
        wait: suspend (Long) -> Unit,
        restart: suspend () -> Unit,
    ) {
        var restartsUsed = 0
        if (!setIfCurrent(role, epoch) { restartsUsed = it.restarts; failedStatus(it) }) return
        when (val d = RestartPolicy.decide(role, wasReady, serviceUp(), restartsUsed, aliveMs, extraArgs)) {
            RestartPolicy.Decision.Ignore -> {}
            RestartPolicy.Decision.GiveUp -> setIfCurrent(role, epoch) {
                it.copy(notice = "The server was killed again ($what), so it is not restarted. Tap Load to start it.")
            }
            is RestartPolicy.Decision.Restart -> {
                val announced = setIfCurrent(role, epoch) {
                    it.copy(
                        state = ServerState.STARTING,
                        notice = "The server was killed ($what). Restarting in ${d.delayMs / 1000} s.",
                        restarts = d.attempt,
                    )
                }
                if (!announced) return
                wait(d.delayMs)
                if (serviceUp() && isCurrent(role, epoch)) restart()
            }
        }
    }
}
