package com.swarmos.app.llm

/**
 * When a llama-server that was READY dies without being asked to (lmkd, the phantom-process killer, a crash), the
 * runtime restarts it once with a backoff. Everything about that decision is here so it can be unit-tested.
 */
object RestartPolicy {
    /** At most this many automatic restarts per failure episode. */
    const val MAX_AUTO_RESTARTS = 1

    /** Wait before restart n (1-based): 2 s, 4 s, ... */
    const val BACKOFF_MS = 2_000L

    /** A server that ran this long before dying starts a fresh episode (the earlier restarts no longer count). */
    const val STABLE_MS = 120_000L

    sealed interface Decision {
        /** Do nothing: the death is not ours to repair. */
        data object Ignore : Decision

        /** Restart attempt [attempt] after [delayMs]. */
        data class Restart(val attempt: Int, val delayMs: Long) : Decision

        /** The one allowed restart was already used: leave the server FAILED and tell the user. */
        data object GiveUp : Decision
    }

    /**
     * @param wasReady the dead server had reached READY (a server that never got ready has a bad model or flags: retrying is pointless)
     * @param restartsUsed automatic restarts already spent in this episode
     * @param aliveMs how long the dead process lived
     * @param extraArgs the extra arguments it was started with: a split chat server (`--rpc`) is MemoryCloud's to re-plan
     */
    fun decide(role: Role, wasReady: Boolean, serviceUp: Boolean, restartsUsed: Int, aliveMs: Long, extraArgs: List<String>): Decision {
        if (!wasReady || !serviceUp) return Decision.Ignore
        if (role == Role.RPC) return Decision.Ignore
        if ("--rpc" in extraArgs || "-rpc" in extraArgs) return Decision.Ignore
        val used = if (aliveMs >= STABLE_MS) 0 else restartsUsed
        if (used >= MAX_AUTO_RESTARTS) return Decision.GiveUp
        val attempt = used + 1
        return Decision.Restart(attempt, BACKOFF_MS * attempt)
    }
}
