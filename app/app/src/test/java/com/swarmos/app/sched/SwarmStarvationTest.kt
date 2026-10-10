package com.swarmos.app.sched

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A ready phone must not sit idle while the batch runs (the end-to-end run left one phone with 12 of 32 jobs and idle
 * most of the time). These use the shipped timing defaults of [SwarmLlm]: a fix that only works with tiny test timers
 * is no fix.
 */
class SwarmStarvationTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() = scope.cancel()

    private fun phone(id: String, local: Boolean = false) = FakePhone(id, TestLlm(delayMs = 100), scope, isLocal = local, tokPerSec = 10.0)

    private fun ms(from: Long, to: Long) = (to - from) / 1_000_000

    @Test fun aPhoneThatMissesOnePingIsStillGivenWork() = runBlocking {
        val a = phone("a", local = true)
        val c = phone("c").also { it.capsFailures.set(1) }
        val report = BatchRunner(SwarmLlm({ listOf(a, c) }, scope)).run("t", "swarm", (0 until 24).map { req("p$it") })
        assertEquals(0, report.failed)
        assertTrue("c took ${c.jobIds.size} of 24 jobs after missing one ping", c.jobIds.size >= 6)
    }

    @Test fun aPhoneIsUsedAgainRightAfterAForeignJobLeavesIt() = runBlocking {
        val a = phone("a", local = true)
        val c = phone("c")
        val slot = c.node.chatExec.acquire()          // a job from another phone (or Choir) holds c's only slot at the start
        val freedAt = LongArray(1)
        launch {
            delay(400)
            freedAt[0] = System.nanoTime()
            c.node.chatExec.release()
        }
        val report = BatchRunner(SwarmLlm({ listOf(a, c) }, scope)).run("t", "swarm", (0 until 40).map { req("p$it") })
        assertTrue(slot)
        assertEquals(0, report.failed)
        val firstOnC = c.startedAt.firstOrNull() ?: error("c never got a job")
        val idleAfterFree = ms(freedAt[0], firstOnC)
        assertTrue("c waited $idleAfterFree ms after it was free; one job takes 100 ms", idleAfterFree <= 700)
    }

    @Test fun aPhoneThatAnswers429OnceIsUsedAgainWithinAJob() = runBlocking {
        val a = phone("a", local = true)
        val c = phone("c").also { it.busyOnce = true }
        val report = BatchRunner(SwarmLlm({ listOf(a, c) }, scope)).run("t", "swarm", (0 until 24).map { req("p$it") })
        assertEquals(0, report.failed)
        val starts = c.startedAt.toList()
        assertTrue("c was asked ${starts.size} times", starts.size >= 6)
        val gap = ms(starts[0], starts[1])
        assertTrue("c was left alone for $gap ms after its 429", gap <= 700)
    }

    @Test fun aPhoneMarkedDownByAFailedJobComesBackWhenItAnswersAgain() = runBlocking {
        val a = phone("a", local = true)
        val c = phone("c").also { it.dead = true }
        val llm = SwarmLlm({ listOf(a, c) }, scope)
        val batch = launch { BatchRunner(llm).run("t", "swarm", (0 until 30).map { req("p$it") }) }
        delay(300)
        c.dead = false                                  // the phone is back
        val backAt = System.nanoTime()
        batch.join()
        val firstOnC = c.startedAt.filter { it > backAt }.minOrNull() ?: error("c was never used after it came back")
        val wait = ms(backAt, firstOnC)
        assertTrue("c was used again only after $wait ms", wait <= 1_500)
    }
}
