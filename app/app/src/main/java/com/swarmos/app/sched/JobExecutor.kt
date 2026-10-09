package com.swarmos.app.sched

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** No free slot on this phone. Becomes HTTP 429 on the wire. */
class BusyException(val running: Int, val capacity: Int, val queued: Int) :
    RuntimeException("busy: $running/$capacity running, $queued queued")

/** The caller pinned a model this phone does not run. Becomes HTTP 409 on the wire. */
class ModelMismatchException(val model: String) : RuntimeException("phone runs $model")

/**
 * Admission control and exactly-once bookkeeping for one kind of job on this phone.
 *
 * - At most [capacity] jobs run at once. When all slots are taken and fewer than [maxQueue] callers wait,
 *   a new jobId is refused with [BusyException].
 * - A jobId is run once. A repeat while it runs shares the running job; a repeat after it finished (within
 *   [ttlMs], 10 minutes) gets the stored result. A job that failed or was cancelled is forgotten so a retry runs it.
 * - Jobs run in [scope], not in the caller, so one caller going away does not leave a half-done job
 *   behind: when the last interested caller is cancelled the job is cancelled and its slot freed.
 */
class JobExecutor(
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = RESULT_TTL_MS,
) {
    /** Somebody waiting for a slot. `counted` says whether the slot that woke it was counted as running for it. */
    private class Waiter {
        val signal = CompletableDeferred<Unit>()
        @Volatile var counted = false
    }

    private class Entry(val waiter: Waiter?) {
        val result = CompletableDeferred<Any>()
        var doneAt = 0L            // 0 until the job finished successfully
        var interest = 0           // callers currently waiting for the result
        var job: Job? = null
    }

    private val lock = Any()
    private val entries = HashMap<String, Entry>()
    private val waiting = ArrayDeque<Waiter>()
    private var runningCount = 0

    /**
     * Slots. Follows the model server's parallel slots; change at any time. 0 means there is no model server:
     * jobs are refused, and local callers of [acquire] run without a slot.
     */
    @Volatile
    var capacity: Int = 1
        set(value) {
            field = value.coerceAtLeast(0)
            drain()
        }

    /** Callers allowed to wait for a slot instead of being refused. 0 = refuse at once. */
    @Volatile
    var maxQueue: Int = 0

    val running: Int get() = synchronized(lock) { runningCount }
    val queued: Int get() = synchronized(lock) { waiting.size }

    /** Number of jobIds currently remembered (running plus finished and not yet expired). */
    val remembered: Int get() = synchronized(lock) { entries.size }

    suspend fun <T : Any> submit(jobId: String, block: suspend () -> T): T {
        var created = false
        val entry = synchronized(lock) {
            purgeLocked()
            val existing = entries[jobId]
            if (existing != null) {
                existing.interest++
                return@synchronized existing
            }
            val immediate = runningCount < capacity && waiting.isEmpty()
            if (!immediate && waiting.size >= maxQueue) throw BusyException(runningCount, capacity, waiting.size)
            val waiter = if (immediate) {
                runningCount++
                null
            } else {
                Waiter().also { waiting.addLast(it) }
            }
            created = true
            Entry(waiter).also {
                it.interest = 1
                it.job = scope.launch(start = CoroutineStart.LAZY) { runJob(jobId, it, block) }
                entries[jobId] = it
            }
        }
        if (created) entry.job!!.start()
        try {
            @Suppress("UNCHECKED_CAST")
            return entry.result.await() as T
        } finally {
            val abandoned = !currentCoroutineContext().isActive
            synchronized(lock) {
                entry.interest--
                if (abandoned && entry.interest == 0 && !entry.result.isCompleted) entry.job?.cancel()
            }
        }
    }

    /** Takes a slot for work that is not a deduplicated job (a local stream). Pair with [release]. */
    fun tryAcquire(): Boolean = synchronized(lock) {
        if (runningCount < capacity && waiting.isEmpty()) {
            runningCount++
            true
        } else {
            false
        }
    }

    /**
     * Waits for a slot for a local caller (Agent Choir, Data Union, a stream). Returns true when a slot was taken (give it
     * back with [release]); false when capacity is 0, i.e. there is no model server, and the caller should just run.
     * Cancelling the wait gives up the place in line, or the slot if one was just handed over.
     */
    suspend fun acquire(): Boolean {
        val waiter = synchronized(lock) {
            if (capacity == 0) return false
            if (runningCount < capacity && waiting.isEmpty()) {
                runningCount++
                return true
            }
            Waiter().also { waiting.addLast(it) }
        }
        try {
            waiter.signal.await()
        } catch (e: CancellationException) {
            val handedOver = synchronized(lock) { !waiting.remove(waiter) && waiter.counted }
            if (handedOver) release()
            throw e
        }
        return waiter.counted
    }

    fun release() {
        synchronized(lock) { runningCount-- }
        drain()
    }

    private fun drain() {
        synchronized(lock) {
            if (capacity == 0) {
                // no model server any more: nobody will get a slot, wake everybody up so nobody waits forever
                while (waiting.isNotEmpty()) waiting.removeFirst().signal.complete(Unit)
                return
            }
            while (runningCount < capacity && waiting.isNotEmpty()) {
                runningCount++
                val w = waiting.removeFirst()
                w.counted = true
                w.signal.complete(Unit)
            }
        }
    }

    private suspend fun <T : Any> runJob(jobId: String, entry: Entry, block: suspend () -> T) {
        var owned = entry.waiter == null   // true while this job holds a slot that it must hand back
        try {
            if (entry.waiter != null) {
                entry.waiter.signal.await()
                owned = entry.waiter.counted
                if (!owned) throw BusyException(running, capacity, queued)
            }
            val value = block()
            owned = false
            release()                       // free the slot before the caller can see the result
            synchronized(lock) { entry.doneAt = clock() }
            entry.result.complete(value)
        } catch (t: Throwable) {
            synchronized(lock) {
                if (entries[jobId] === entry) entries.remove(jobId)
                // cancelled while queued: either still in the queue (no slot) or the slot was already handed over
                if (!owned && entry.waiter != null && !waiting.remove(entry.waiter)) owned = entry.waiter.counted
            }
            if (t is CancellationException) entry.result.cancel(t) else entry.result.completeExceptionally(t)
            if (owned) release()
            if (t is CancellationException) throw t
        }
    }

    private fun purgeLocked() {
        val now = clock()
        entries.values.removeAll { it.doneAt != 0L && now - it.doneAt > ttlMs }
    }

    companion object {
        const val RESULT_TTL_MS = 10 * 60 * 1000L
    }
}
