package com.swarmos.app.llm

import java.util.Locale

/** Why a model start was refused before any server process was launched: the two gates in front of a load. */
internal sealed interface StartRefusal {
    /** The heat gate: the phone is at thermal status 4 or more. */
    data object Hot : StartRefusal

    /** The memory check: [haveBytes] of free memory against the [needBytes] the load asks for. */
    data class Memory(val needBytes: Long, val haveBytes: Long) : StartRefusal
}

/**
 * A model start that the app made by itself (the restart after a server died, the restore after a MemoryCloud split or loan, the load at
 * app start) and that a gate refused leaves the role FAILED, and nothing would ever start it again: the phone has no model and leaves the
 * swarm until the owner taps Load. This is the rule for trying again, as pure functions. What the heat gate and the memory check decide
 * is not touched here. A start the owner asked for is never retried: they get the message and decide.
 */
internal object StartRetry {
    /** A cause that passes quickly (a model killed a moment ago, a load that just collided with another) is caught within half a minute. */
    const val INTERVAL_MS = 30_000L

    /** The first tries are 30 s apart: 20 of them, ten minutes. */
    const val FAST_TRIES = 20

    /** After that, a phone that is still too hot or too full is looked at again this often, for as long as the loop runs: a heat or memory cause can outlast any cap (seen: a phone still at thermal status 4 when ten minutes were over). */
    const val SLOW_INTERVAL_MS = 5 * 60_000L

    sealed interface Decision {
        /** The owner asked, or the role has no model to load: the refusal stands as it is. */
        data object NoRetry : Decision

        /** Try again after [afterMs]; [text] is the status of the role while it waits. */
        data class Retry(val afterMs: Long, val text: String) : Decision
    }

    /** [triesSoFar]: how many retries have been made already (0 for the first refusal). There is no last try: only the end conditions of [retryRefusedStart] stop the loop. */
    fun decide(refusal: StartRefusal, byOwner: Boolean, triesSoFar: Int): Decision = when {
        byOwner -> Decision.NoRetry
        triesSoFar >= FAST_TRIES -> Decision.Retry(SLOW_INTERVAL_MS, "Not loaded: ${cause(refusal)}. Ustaad tries again every ${SLOW_INTERVAL_MS / 60_000} minutes.")
        else -> Decision.Retry(INTERVAL_MS, "Not loaded: ${cause(refusal)}. Ustaad tries again every ${INTERVAL_MS / 1000} seconds.")
    }

    fun fileGone(fileName: String): String = "Not loaded: the model file $fileName is gone. Download it again, then tap Load."

    private fun cause(r: StartRefusal): String = when (r) {
        StartRefusal.Hot -> "the phone is too hot"
        is StartRefusal.Memory -> "not enough free memory (needs ${gb(r.needBytes)}, ${gb(r.haveBytes)} free)"
    }

    private fun gb(bytes: Long): String = String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
}

/**
 * The wait-and-try-again loop of one refused automatic start of [role], all under [epoch] (the epoch the start was made under). It ends
 * when a start is not refused again, or when anything changed: the owner stopped or loaded the role (the epoch moved), the role is no
 * longer FAILED, the service is down, or the file is gone. There is no cap: after the 20th try they slow down to every 5 minutes
 * ([StartRetry.decide]). The status of the role says what it is waiting for.
 * @param attempt starts the model again; returns why it was refused again, or null when it went ahead (loading, ready or failed for another reason)
 * @param wait the sleep between tries (injected so tests can interleave a stop with it)
 */
internal suspend fun StatusBoard.retryRefusedStart(
    role: Role, epoch: Int, first: StartRefusal, byOwner: Boolean, fileName: String,
    serviceUp: () -> Boolean, fileExists: () -> Boolean,
    wait: suspend (Long) -> Unit,
    attempt: suspend () -> StartRefusal?,
) {
    var tries = 0
    var refusal = first
    while (true) {
        when (val d = StartRetry.decide(refusal, byOwner, tries)) {
            StartRetry.Decision.NoRetry -> return
            is StartRetry.Decision.Retry -> {
                if (statusOf(role).state != ServerState.FAILED) return
                if (!setIfCurrent(role, epoch) { it.copy(reason = d.text, notice = null) }) return
                wait(d.afterMs)
                if (!serviceUp() || !isCurrent(role, epoch) || statusOf(role).state != ServerState.FAILED) return
                if (!fileExists()) {
                    setIfCurrent(role, epoch) { it.copy(reason = StartRetry.fileGone(fileName), notice = null) }
                    return
                }
                tries++
                refusal = attempt() ?: return
            }
        }
    }
}
