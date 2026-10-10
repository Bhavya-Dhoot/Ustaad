package com.swarmos.app.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Publishes a value in memory and writes it to storage through ONE writer, so the value that is stored last is the value that
 * was published last. Used for the pairing secret: two overlapping changes used to publish at once but launch independent write
 * jobs, so an older write could finish after a newer one and the wrong secret came back after a restart.
 *
 * [set] publishes and queues the value under one lock (two threads cannot interleave "publish A, publish B, queue B, queue A"),
 * and a single consumer coroutine persists the queue in order. A write that throws is skipped; the writer keeps running.
 */
class OrderedWriter<T>(
    scope: CoroutineScope,
    private val publish: (T) -> Unit,
    private val persist: suspend (T) -> Unit,
) {
    private val queue = Channel<T>(Channel.UNLIMITED)
    private val lock = Any()

    init {
        scope.launch {
            for (value in queue) {
                try {
                    persist(value)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // the next value is written on top of this one anyway
                }
            }
        }
    }

    /** Makes [value] the current value now and queues it for storage after every earlier value. */
    fun set(value: T) {
        synchronized(lock) {
            publish(value)
            queue.trySend(value)
        }
    }
}
