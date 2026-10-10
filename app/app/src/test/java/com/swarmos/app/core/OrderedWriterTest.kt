package com.swarmos.app.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class OrderedWriterTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() = scope.cancel()

    @Test fun aSlowOlderWriteCannotFinishAfterANewerOne() {
        val published = AtomicReference("")
        val stored = CopyOnWriteArrayList<String>()
        val done = CountDownLatch(2)
        val w = OrderedWriter<String>(scope, { published.set(it) }, { v ->
            if (v == "old") delay(150) // the older write is the slow one: with independent jobs it would land last
            stored += v
            done.countDown()
        })
        w.set("old")
        w.set("new")
        assertEquals("new", published.get()) // published at once, before anything is stored
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("old", "new"), stored.toList())
        assertEquals("new", stored.last())
    }

    @Test fun whateverTheInterleavingOfThreadsTheLastStoredValueIsTheLastPublishedOne() {
        repeat(20) { round ->
            val published = AtomicReference("")
            val stored = CopyOnWriteArrayList<String>()
            val threads = 6
            val perThread = 40
            val done = CountDownLatch(threads * perThread)
            val w = OrderedWriter<String>(scope, { published.set(it) }, { v ->
                if (v.hashCode() % 7 == 0) delay(1) // uneven write times
                stored += v
                done.countDown()
            })
            val start = CountDownLatch(1)
            val workers = List(threads) { t ->
                thread {
                    start.await()
                    repeat(perThread) { i -> w.set("r$round-t$t-$i") }
                }
            }
            start.countDown()
            workers.forEach { it.join() }
            assertTrue(done.await(10, TimeUnit.SECONDS))
            assertEquals("round $round: stored ${stored.size}", threads * perThread, stored.size)
            assertEquals("round $round", published.get(), stored.last())
        }
    }

    @Test fun aWriteThatFailsDoesNotStopTheWriter() {
        val stored = CopyOnWriteArrayList<String>()
        val done = CountDownLatch(1)
        val w = OrderedWriter<String>(scope, {}, { v ->
            if (v == "bad") throw java.io.IOException("disk full")
            stored += v
            done.countDown()
        })
        w.set("bad")
        w.set("good")
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("good"), stored.toList())
    }
}
