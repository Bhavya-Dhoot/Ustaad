package com.swarmos.app.sched

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

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
 * - Filler (the "Keep busy" mode) may borrow a free slot with [tryAcquireFiller] while no real work runs or waits. Real work
 *   never sees it: it is never refused because of filler, [running] and [queued] do not count it, and the first real caller
 *   that arrives tells the filler to stop; the real work then waits for the slot (even with [maxQueue] 0) until the filler gives it back.
 *   A job or a stream that waits only for filler gives up after [fillerGraceMs] (3 s) from the filler being told to stop and is
 *   refused as it was before filler existed; local callers of [acquire] have their own time limit and wait as long as they are given.
 */
class JobExecutor(
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = RESULT_TTL_MS,
    private val fillerGraceMs: Long = FILLER_GRACE_MS,
) {
    /**
     * Somebody waiting for a slot. `counted` says whether the slot that woke it was counted as running for it.
     * A [deadlineMs] above 0 (executor clock) is set for a waiter that waits only for filler to leave.
     */
    private class Waiter(val deadlineMs: Long = 0L) {
        val signal = CompletableDeferred<Unit>()
        @Volatile var counted = false
    }

    private class Entry(val waiter: Waiter?) {
        val result = CompletableDeferred<Any>()
        var doneAt = 0L            // 0 until the job finished successfully
        var interest = 0           // callers currently waiting for the result
        var job: Job? = null
    }

    /**
     * A slot lent to filler work. The owner runs its work, calls [release] when that work has really ended (the slot is
     * then handed to real work that waits), and stops early when [onPreempt] is called: real work has arrived.
     */
    inner class FillerSlot internal constructor(private val onPreempt: () -> Unit) {
        private val preempted = AtomicBoolean(false)
        private val released = AtomicBoolean(false)

        /** Executor clock time at which real work first asked for this slot (0 = not yet). Guarded by the executor's lock. */
        internal var askedAtMs = 0L

        /** True once real work asked for this slot. */
        val isPreempted: Boolean get() = preempted.get()

        internal fun preempt() {
            if (!preempted.compareAndSet(false, true)) return
            try {
                onPreempt()
            } catch (_: Exception) {
                // the owner's cancel hook must not break the real caller that triggered it
            }
        }

        /** Gives the slot back; safe to call twice. */
        fun release() {
            if (!released.compareAndSet(false, true)) return
            synchronized(lock) { if (filler === this) filler = null }
            drain()
        }
    }

    private val lock = Any()
    private val entries = HashMap<String, Entry>()
    private val waiting = ArrayDeque<Waiter>()
    private var runningCount = 0
    private var filler: FillerSlot? = null
    private var lastRealMs = clock()   // last time real work arrived or ended

    /**
     * Slots. Follows the model server's parallel slots; change at any time. 0 means there is no model server:
     * jobs are refused, and local callers of [acquire] run without a slot.
     */
    @Volatile
    var capacity: Int = 1
        set(value) {
            field = value.coerceAtLeast(0)
            drain()
            if (field == 0) synchronized(lock) { filler }?.preempt() // no model server: no filler either
        }

    /** Callers allowed to wait for a slot instead of being refused. 0 = refuse at once. */
    @Volatile
    var maxQueue: Int = 0

    /** Real jobs holding a slot. Filler is not counted; a real caller waiting only for filler to give its slot back counts as running. */
    val running: Int get() = synchronized(lock) { runningLocked() }

    /** Real callers waiting for a slot. Filler is not counted, and neither is a caller that waits only for filler to give its slot back. */
    val queued: Int get() = synchronized(lock) { waiting.size - shadowedLocked() }

    /** Number of jobIds currently remembered (running plus finished and not yet expired). */
    val remembered: Int get() = synchronized(lock) { entries.size }

    suspend fun <T : Any> submit(jobId: String, block: suspend () -> T): T {
        var created = false
        var lendingFiller: FillerSlot? = null
        val entry = synchronized(lock) {
            purgeLocked()
            val existing = entries[jobId]
            if (existing != null) {
                existing.interest++
                return@synchronized existing
            }
            val immediate = freeNowLocked()
            if (!immediate && refusedLocked(maxQueue)) throw BusyException(runningLocked(), capacity, waiting.size - shadowedLocked())
            lendingFiller = arrivalLocked()
            val waiter = if (immediate) {
                runningCount++
                null
            } else {
                Waiter(fillerDeadlineLocked()).also { waiting.addLast(it) }
            }
            created = true
            Entry(waiter).also {
                it.interest = 1
                it.job = scope.launch(start = CoroutineStart.LAZY) { runJob(jobId, it, block) }
                entries[jobId] = it
            }
        }
        if (created) {
            lendingFiller?.preempt()   // real work is here: filler stops, whatever the free slots
            entry.job!!.start()
        }
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

    /**
     * Takes a slot for work that is not a deduplicated job (a local stream) if one is free right now. Pair with [release].
     * False when real work holds or waits for every slot. When only filler is in the way the filler is told to stop and this
     * waits for it to give the slot back (a moment), then returns true: filler never makes a caller go elsewhere.
     */
    suspend fun tryAcquire(): Boolean {
        var lending: FillerSlot? = null
        val waiter: Waiter? = synchronized(lock) {
            if (capacity == 0) return false
            if (freeNowLocked()) {
                runningCount++
                lending = arrivalLocked()
                null
            } else if (filler != null && runningCount + waiting.size < capacity) {
                lending = arrivalLocked()
                Waiter(fillerDeadlineLocked()).also { waiting.addLast(it) }   // only filler is in the way
            } else {
                return false                                                  // real work holds or waits for every slot
            }
        }
        lending?.preempt()
        return if (waiter == null) true else await(waiter)
    }

    /**
     * Waits for a slot for a local caller (Agent Choir, Data Union, a stream). Returns true when a slot was taken (give it
     * back with [release]); false when capacity is 0, i.e. there is no model server, and the caller should just run.
     * Cancelling the wait gives up the place in line, or the slot if one was just handed over.
     */
    suspend fun acquire(): Boolean {
        var lending: FillerSlot? = null
        val waiter: Waiter? = synchronized(lock) {
            if (capacity == 0) return false
            lending = arrivalLocked()
            if (freeNowLocked()) {
                runningCount++
                null
            } else {
                Waiter().also { waiting.addLast(it) }
            }
        }
        lending?.preempt()
        return if (waiter == null) true else await(waiter)
    }

    private suspend fun await(waiter: Waiter): Boolean {
        try {
            return awaitSlot(waiter)
        } catch (e: CancellationException) {
            val handedOver = synchronized(lock) { !waiting.remove(waiter) && waiter.counted }
            if (handedOver) release()
            throw e
        }
    }

    /**
     * Waits until [w] was handed a slot (true). False when no slot comes: capacity went to 0, or [w] waits only for filler and
     * the filler did not leave in time, in which case [w] has left the queue.
     */
    private suspend fun awaitSlot(w: Waiter): Boolean {
        if (w.deadlineMs > 0L) {
            val left = w.deadlineMs - clock()
            val got = left > 0 && withTimeoutOrNull(left) { w.signal.await() } != null
            if (!got && !w.signal.isCompleted && synchronized(lock) { waiting.remove(w) }) return false
        }
        w.signal.await()
        return w.counted
    }

    /** Real work arrives: remember it for the idle clock and note when the filler was first asked to stop. Returns the filler, if any. */
    private fun arrivalLocked(): FillerSlot? {
        val now = clock()
        lastRealMs = now
        return filler?.also { if (it.askedAtMs == 0L) it.askedAtMs = now }
    }

    /** Deadline for a waiter that waits only for filler to leave, else 0 (no deadline). Call before adding the waiter and after [arrivalLocked]. */
    private fun fillerDeadlineLocked(): Long {
        val f = filler ?: return 0L
        return if (runningCount + waiting.size < capacity) f.askedAtMs + fillerGraceMs else 0L
    }

    fun release() {
        synchronized(lock) {
            runningCount--
            lastRealMs = clock()
        }
        drain()
    }

    /**
     * Borrows a free slot for filler work, or returns null: filler is taken only while no real work runs or waits, only one
     * at a time, and never when there is no model server (capacity 0). The caller gives [onPreempt] a way to stop its work; it
     * is called (once, from the thread of the real caller) as soon as real work arrives. The caller must [FillerSlot.release]
     * the slot once its work has really stopped.
     */
    fun tryAcquireFiller(onPreempt: () -> Unit): FillerSlot? = synchronized(lock) {
        if (capacity <= 0 || filler != null || runningCount > 0 || waiting.isNotEmpty()) return null
        FillerSlot(onPreempt).also { filler = it }
    }

    /** Milliseconds since real work last arrived or ended; 0 while real work runs or waits. Filler does not count. */
    fun realIdleMs(): Long = synchronized(lock) {
        if (runningCount > 0 || waiting.isNotEmpty()) 0L else (clock() - lastRealMs).coerceAtLeast(0L)
    }

    /** A slot is free for real work right now, filler counted as occupying one. */
    private fun freeNowLocked(): Boolean = runningCount + (if (filler != null) 1 else 0) < capacity && waiting.isEmpty()

    /**
     * Whether a new real caller must be refused when it cannot start now and [limit] callers may wait. Filler is never the
     * reason: if the slots would take the caller without the filler, it waits for the filler to leave.
     */
    private fun refusedLocked(limit: Int): Boolean = when {
        filler == null -> waiting.size >= limit
        runningCount + waiting.size < capacity -> false
        else -> runningCount + waiting.size - capacity >= limit
    }

    /** Waiters that wait only because filler holds a slot (they would be running if it did not). */
    private fun shadowedLocked(): Int = if (filler == null) 0 else minOf(waiting.size, (capacity - runningCount).coerceAtLeast(0))

    private fun runningLocked(): Int = runningCount + shadowedLocked()

    private fun drain() {
        synchronized(lock) {
            if (capacity == 0) {
                // no model server any more: nobody will get a slot, wake everybody up so nobody waits forever
                while (waiting.isNotEmpty()) waiting.removeFirst().signal.complete(Unit)
                return
            }
            while (runningCount + (if (filler != null) 1 else 0) < capacity && waiting.isNotEmpty()) {
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
                owned = awaitSlot(entry.waiter)
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

        /** How long a job that waits only for filler to give up its slot is held before it is refused as usual. */
        const val FILLER_GRACE_MS = 3_000L
    }
}
