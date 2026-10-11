package com.swarmos.app.pdu

import com.swarmos.app.sched.JobExecutor
import kotlinx.coroutines.withTimeoutOrNull

/** What the answer pane says instead of model text. Model text only ever comes from a real, loaded chat model. */
object AnswerNotes {
    const val NO_CHAT_MODEL = "No chat model is loaded on this phone, showing sources only"
    const val WAITING = "Waiting for the model"
    const val GAVE_UP = "Gave up waiting for the model after ${ModelWait.MAX_WAIT_MS / 1000} s, showing sources only"
    const val NOTHING_FOUND = "Nothing relevant found on any phone."

    const val STOPPED = "Stopped, showing what was found so far"

    /** The stream gave no token for [idleMs]: a frozen model server, not a slow model. */
    fun quiet(idleMs: Long) = "The model went quiet for ${idleMs / 1000} s, showing what it wrote so far"

    fun stopped(reason: String) = "The model stopped before it finished ($reason), showing what it wrote so far"
}

/** The wait for a free chat slot (another job may be running) has an upper bound; the sources are shown either way. */
object ModelWait {
    const val MAX_WAIT_MS = 45_000L

    sealed interface Result {
        /** A slot was taken (give it back with release), or, with [taken] false, the model has no gate and the caller just runs. */
        data class Slot(val taken: Boolean) : Result
        data object TimedOut : Result
    }

    /** Waits at most [waitMs] for a slot. Giving up leaves no place in line and no slot held. */
    suspend fun acquire(exec: JobExecutor, waitMs: Long = MAX_WAIT_MS): Result {
        // the timeout can fire, or the caller be cancelled, in the instant acquire() has returned true; withTimeoutOrNull then drops
        // the block's value (or throws), so the block keeps it, and the slot is given back on every exit but a normal return
        var taken: Boolean? = null
        val got = try {
            withTimeoutOrNull(waitMs) { exec.acquire().also { taken = it } }
        } catch (e: Throwable) {
            if (taken == true) exec.release()
            throw e
        }
        if (got == null) {
            if (taken == true) exec.release()
            return Result.TimedOut
        }
        return Result.Slot(got)
    }
}
