package com.swarmos.app.pdu

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

/** An answer stream produced no token for its idle budget. */
class StreamIdle(val idleMs: Long) : RuntimeException("no token for ${idleMs / 1000} s")

/**
 * The chat stream read timeout is minutes long, so a frozen model server would hold the single chat slot for that long. The budget
 * here is per token, not for the whole answer: a slow model that keeps writing is never cut off.
 */
object IdleStream {
    const val ANSWER_IDLE_MS = 45_000L

    private val producers = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Collects [flow] and hands each item to [onEach]. Throws [StreamIdle] when [idleMs] pass without an item; what was handed over
     * before stays handed over. The producer runs on its own scope and is cancelled, never waited for, so even a read that ignores
     * cancellation cannot hold the caller.
     */
    suspend fun <T> collect(flow: Flow<T>, idleMs: Long, onEach: suspend (T) -> Unit) {
        val channel = flow.produceIn(producers)
        try {
            while (true) {
                val r = withTimeoutOrNull(idleMs) { channel.receiveCatching() } ?: throw StreamIdle(idleMs)
                if (r.isClosed) {
                    r.exceptionOrNull()?.let { throw it }
                    return
                }
                onEach(r.getOrThrow())
            }
        } finally {
            channel.cancel()
        }
    }
}

/** A lookup with a time budget and a cheaper fallback (the keyword-only path) for when the budget runs out. */
object Bounded {
    const val LOOKUP_BUDGET_MS = 3_000L

    suspend fun <T : Any> withFallback(budgetMs: Long, primary: suspend () -> T, fallback: suspend () -> T): T =
        withTimeoutOrNull(budgetMs) { primary() } ?: fallback()
}

/**
 * One ask at a time. A new ask cancels the one before it (and waits until it has cleaned up), and [stop] cancels the one running.
 * [run] returns null for an ask that was stopped or replaced, so the caller can report what it had; the caller's own cancellation
 * is still thrown.
 */
class AskSlot {
    private val lock = Any()
    private var current: Job? = null

    val running: Boolean get() = synchronized(lock) { current?.isActive == true }

    suspend fun <R : Any> run(block: suspend CoroutineScope.() -> R): R? = coroutineScope {
        val job = async(start = CoroutineStart.LAZY, block = block)
        val previous = synchronized(lock) { current.also { current = job } }
        previous?.cancelAndJoin()
        job.start()
        try {
            job.await()
        } catch (e: CancellationException) {
            if (!isActive) throw e // the caller itself was cancelled
            null
        } finally {
            synchronized(lock) { if (current === job) current = null }
        }
    }

    /** True when an ask was running and has been told to stop. */
    fun stop(): Boolean = synchronized(lock) { current }?.let { it.cancel(); true } ?: false
}

/** A step of a background loop that must not end the loop, or the process: the error is handed to [onError] and the caller goes on. */
object StepGuard {
    suspend fun run(onError: (Throwable) -> Unit, block: suspend () -> Unit): Boolean {
        try {
            block()
            return true
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            onError(t)
            return false
        }
    }

    fun describe(what: String, t: Throwable): String = "$what: ${t.javaClass.simpleName} ${t.message}".take(160)
}
