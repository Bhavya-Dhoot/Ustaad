package com.swarmos.app.sched

import com.swarmos.app.llm.ChatChunk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SwarmLlmTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() = scope.cancel()

    private fun phone(id: String, llm: TestLlm = TestLlm(), capacity: Int = 1, local: Boolean = false, tps: Double? = null, thermal: Int = 0) =
        FakePhone(id, llm, scope, capacity, local, tps, thermal)

    private fun swarm(phones: List<FakePhone>, attemptTimeoutMs: Long = 5_000) =
        SwarmLlm({ phones }, scope, attemptTimeoutMs = attemptTimeoutMs, capsMaxAgeMs = 50, busyBackoffMs = 20, probeEveryMs = 100, msPerToken = 0)

    private fun batch(n: Int) = (0 until n).map { req("p$it") }

    // ---- routing by capability and health ---------------------------------------------------------------------

    @Test fun oneAtATimeGoesToTheFastestPhone() = runBlocking {
        val slow = phone("slow", tps = 5.0)
        val fast = phone("fast", tps = 40.0)
        val mid = phone("mid", tps = 20.0)
        val llm = swarm(listOf(slow, fast, mid))
        repeat(4) { assertEquals("fast", llm.chatJob("j$it", req("x$it")).nodeId) }
    }

    @Test fun aPhoneAtStatusThreeTakesNoJobWhileACoolerPhoneIsUpEvenWhenThatOneIsBusy() = runBlocking {
        val hot = phone("hot", TestLlm(delayMs = 120), tps = 90.0, thermal = 3)
        val cool = phone("cool", TestLlm(delayMs = 120), tps = 3.0)
        val llm = swarm(listOf(hot, cool))
        assertEquals("cool", llm.chatJob("a", req("a")).nodeId)
        val both = listOf(async { llm.chatJob("b", req("b")) }, async { llm.chatJob("c", req("c")) }).awaitAll()
        assertEquals("both wait for the cool phone", setOf("cool"), both.map { it.nodeId }.toSet())
        assertTrue("the hot phone ran nothing", hot.llm.executed.isEmpty())
    }

    @Test fun aPhoneAtStatusThreeIsUsedWhenItIsTheOnlyOneUp() = runBlocking {
        val hot = phone("hot", tps = 90.0, thermal = 3)
        val gone = phone("gone", tps = 3.0).also { it.dead = true }
        val llm = swarm(listOf(hot, gone))
        assertEquals("hot", llm.chatJob("a", req("a")).nodeId)
    }

    @Test fun aPhoneAtStatusFourOrMoreTakesNoJobEvenWhenItIsTheOnlyOne() = runBlocking {
        val burning = phone("burning", tps = 90.0, thermal = 4)
        val llm = swarm(listOf(burning))
        try {
            llm.chatJob("a", req("a"))
            fail("expected NoPhoneAvailableException")
        } catch (e: NoPhoneAvailableException) {
            assertTrue("a plain heat message: ${e.message}", e.message!!.contains("too hot"))
        }
        assertTrue(burning.llm.executed.isEmpty())
    }

    @Test fun aPhoneThatCooledFromFourIsNotAdmittedAgainBeforeTwentySecondsOfReadingLower() = runBlocking {
        var now = 1_000_000L
        val p = phone("p", tps = 50.0, thermal = 4)
        val other = phone("other", tps = 1.0)
        val llm = SwarmLlm({ listOf(p, other) }, scope, clock = { now }, capsMaxAgeMs = 0, busyBackoffMs = 20, probeEveryMs = 100, msPerToken = 0)
        llm.refreshCaps(force = true)
        assertEquals("excluded at 4", "other", llm.chatJob("a", req("a")).nodeId)
        p.thermal = 3
        now += 10_000
        llm.refreshCaps(force = true)
        assertEquals("one level lower, but not for 20 s yet", "other", llm.chatJob("b", req("b")).nodeId)
        now += 20_000
        llm.refreshCaps(force = true)
        assertEquals("20 s at status 3: back as a status 3 phone, which still waits while a cooler phone is up", "other", llm.chatJob("c", req("c")).nodeId)
        p.thermal = 1
        now += 1_000
        llm.refreshCaps(force = true)
        assertEquals("reading 1 for under 20 s: still at level 3", "other", llm.chatJob("d", req("d")).nodeId)
        now += 20_000
        llm.refreshCaps(force = true)
        assertEquals("fully admitted", "p", llm.chatJob("e", req("e")).nodeId)
    }

    @Test fun pinnedModelSkipsPhonesRunningAnotherModel() = runBlocking {
        val a = phone("a", TestLlm(modelName = "alpha"), tps = 50.0)
        val b = phone("b", TestLlm(modelName = "beta"), tps = 5.0)
        val llm = swarm(listOf(a, b))
        repeat(3) { assertEquals("b", llm.chatJob("j$it", req("x"), pinModel = "beta").nodeId) }
        try {
            llm.chatJob("none", req("x"), pinModel = "gamma")
            fail("expected NoPhoneAvailableException")
        } catch (e: NoPhoneAvailableException) {
            assertTrue(e.message!!.contains("gamma"))
        }
    }

    // ---- exactly once ------------------------------------------------------------------------------------------

    @Test fun repeatedJobIdIsRunOnceOnThePhone() = runBlocking {
        val a = phone("a", tps = 10.0)
        val llm = swarm(listOf(a, phone("b", tps = 10.0)))
        val first = llm.chatJob("same", req("hello"))
        val second = llm.chatJob("same", req("hello"))
        assertEquals(first.text, second.text)
        assertEquals(first.nodeId, second.nodeId)
        assertEquals("a", first.nodeId)
        assertEquals(1, a.llm.executed.size)
    }

    @Test fun batchReturnsEveryResultOnceInInputOrder() = runBlocking {
        val phones = listOf(
            phone("a", TestLlm(delayMs = 20), tps = 10.0),
            phone("b", TestLlm(delayMs = 60), tps = 5.0),
            phone("c", TestLlm(delayMs = 35), tps = 8.0),
        )
        val report = BatchRunner(swarm(phones)).run("t", "swarm", batch(24))
        assertEquals("done", report.state)
        assertEquals((0 until 24).toList(), report.items.map { it.index })
        report.items.forEach { assertEquals("T:p${it.index}", it.text) }
        assertEquals("every job ran exactly once", 24, phones.sumOf { it.llm.executed.size })
        assertEquals(24, report.done)
        assertEquals(0, report.failed)
    }

    @Test fun jobsSpreadOverAllPhonesAndTheSharesAddUp() = runBlocking {
        val phones = listOf(phone("a"), phone("b"), phone("c"))
        val report = BatchRunner(swarm(phones)).run("t", "swarm", batch(18))
        assertEquals(setOf("a", "b", "c"), report.perPhone.map { it.nodeId }.toSet())
        assertEquals(18, report.perPhone.sumOf { it.jobs })
        assertEquals(report.totalTokens, report.perPhone.sumOf { it.tokens })
        assertEquals(report.aggTokPerSec, report.perPhone.sumOf { it.tokPerSec }, 1e-6)
        assertEquals(18 * 10, report.totalTokens)
        assertEquals(report.totalTokens * 1000.0 / report.wallMs, report.aggTokPerSec, 1e-6)
        phones.forEach { assertTrue("${it.nodeId} did work", it.llm.executed.size >= 3) }
    }

    // ---- failover ------------------------------------------------------------------------------------------------

    @Test fun batchSurvivesAPhoneDyingMidBatch() = runBlocking {
        val a = phone("a", TestLlm(delayMs = 25), tps = 10.0)
        val b = phone("b", TestLlm(delayMs = 25), tps = 10.0).also { it.dieAfter = 3 }
        val c = phone("c", TestLlm(delayMs = 25), tps = 10.0)
        val report = BatchRunner(swarm(listOf(a, b, c))).run("t", "swarm", batch(21))
        assertEquals("b is gone after its third job", true, b.dead)
        assertEquals(0, report.failed)
        assertEquals((0 until 21).toList(), report.items.map { it.index })
        report.items.forEach { assertEquals("T:p${it.index}", it.text) }
        assertEquals("exactly one execution per job across the surviving phones and the dead one's early jobs", 21, listOf(a, b, c).sumOf { it.llm.executed.size })
        assertEquals(21, report.perPhone.sumOf { it.jobs })
    }

    @Test fun aSilentPhoneIsTimedOutAndTheSameJobIdRunsOnTheNextPhone() = runBlocking {
        val silent = phone("silent", tps = 99.0).also { it.hang = true }
        val ok = phone("ok", tps = 1.0)
        val llm = swarm(listOf(silent, ok), attemptTimeoutMs = 150)
        val r = llm.chatJob("job-1", req("hello"))
        assertEquals("ok", r.nodeId)
        // the silent phone still answers its pings, so the same job id is sent to it a second time before the job moves on
        assertEquals(listOf("job-1", "job-1"), silent.jobIds.toList())
        assertEquals(listOf("job-1"), ok.jobIds.toList())
        assertEquals("T:hello", r.text)
        assertTrue("a slow job does not mark the phone down: only pings do", llm.phoneViews().first { it.nodeId == "silent" }.up)
    }

    @Test fun aPhoneThatFreezesMidJobIsDetectedByPingsLongBeforeTheAttemptTimeout() = runBlocking {
        val frozen = phone("frozen", tps = 99.0).also { it.hang = true }
        val ok = phone("ok", tps = 1.0)
        val llm = SwarmLlm({ listOf(frozen, ok) }, scope, attemptTimeoutMs = 30_000, capsMaxAgeMs = 50, probeEveryMs = 100, heartbeatMs = 80, capsTimeoutMs = 100, msPerToken = 0)
        val started = System.nanoTime()
        val job = async { llm.chatJob("job-1", req("hello")) }
        while (frozen.jobIds.isEmpty()) kotlinx.coroutines.delay(5)
        frozen.capsDown = true                      // from now on the phone does not answer pings
        val r = job.await()
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertEquals("ok", r.nodeId)
        assertTrue("moved on after about two missed pings, took $tookMs ms", tookMs < 5_000)
    }

    @Test fun slotCountIncludesAPhoneThatIsDownSoItCanRejoinMidBatch() = runBlocking {
        val a = phone("a")
        val b = phone("b", capacity = 3)
        val llm = swarm(listOf(a, b))
        assertEquals(4, llm.slotCount())
        b.dead = true
        llm.refreshCaps(force = true)   // one missed ping is not a verdict
        llm.refreshCaps(force = true)
        assertTrue("b is down", llm.phoneViews().none { it.nodeId == "b" && it.up })
        assertEquals("a down phone keeps its last known slots", 4, llm.slotCount())
    }

    @Test fun whenEveryPhoneFailsTheErrorIsReportedPerItemNotForTheWholeBatch() = runBlocking {
        val a = phone("a").also { it.dead = true }
        val report = BatchRunner(swarm(listOf(a))).run("t", "swarm", batch(3))
        assertEquals(3, report.failed)
        assertEquals(0, report.done)
        assertEquals(listOf(0, 1, 2), report.items.map { it.index })
        assertTrue(report.items.all { it.error != null })
    }

    // ---- backpressure ----------------------------------------------------------------------------------------------

    @Test fun capacityOnePhonesNeverRunTwoJobsAtOnce() = runBlocking {
        val a = phone("a", TestLlm(delayMs = 40))
        val b = phone("b", TestLlm(delayMs = 40))
        val llm = swarm(listOf(a, b))
        val results = (0 until 12).map { i -> async(Dispatchers.Default) { llm.chatJob("j$i", req("q$i")) } }.awaitAll()
        assertEquals(12, results.size)
        assertEquals(1, a.llm.maxConcurrent.get())
        assertEquals(1, b.llm.maxConcurrent.get())
        assertEquals(12, a.llm.executed.size + b.llm.executed.size)
    }

    @Test fun phonesWithMoreSlotsTakeMoreAtOnce() = runBlocking {
        val a = phone("a", TestLlm(delayMs = 40), capacity = 3)
        val llm = swarm(listOf(a))
        assertEquals(3, llm.slotCount())
        val report = BatchRunner(llm).run("t", "swarm", batch(9))
        assertEquals(3, a.llm.maxConcurrent.get())
        assertEquals(9, report.done)
    }

    // ---- LlmClient surface -------------------------------------------------------------------------------------------

    @Test fun streamingRunsOnThisPhoneWhenItHasAFreeSlot() = runBlocking {
        val local = phone("me", local = true)
        val remote = phone("peer", tps = 99.0)
        val chunks: List<ChatChunk> = swarm(listOf(local, remote)).chatStream(req("hi")).toList()
        assertEquals("stream:hi", chunks.filter { !it.done }.joinToString("") { it.delta })
        assertTrue(chunks.last().done)
        assertTrue("the remote phone was not used", remote.jobIds.isEmpty())
    }

    @Test fun streamingFallsBackToANonStreamedRemoteCallWhenThisPhoneIsBusy() = runBlocking {
        val local = phone("me", TestLlm(delayMs = 300), local = true)
        val remote = phone("peer", TestLlm(delayMs = 20))
        val llm = swarm(listOf(local, remote))
        val busy = async { local.node.chat("hold", req("hold")) }
        while (local.node.chatExec.running == 0) kotlinx.coroutines.delay(2)
        val chunks = llm.chatStream(req("hi")).toList()
        assertEquals("T:hi", chunks.filter { !it.done }.joinToString("") { it.delta })
        assertTrue(chunks.last().done && chunks.last().stats != null)
        assertEquals(listOf(1), listOf(remote.jobIds.size))
        assertEquals("T:hold", busy.await().text)
    }

    @Test fun embeddingsStayOnThisPhoneWhileItWorks() = runBlocking {
        val local = phone("me", local = true)
        val remote = phone("peer", tps = 99.0)
        val v = swarm(listOf(remote, local)).embed(listOf("abc", "de"))
        assertEquals(listOf(3f, 2f), v.map { it[0] })
        assertEquals(0, remote.llm.embedCalls.get())
        assertEquals(1, local.llm.embedCalls.get())
    }

    @Test fun embeddingsMoveToAnotherPhoneIfThisOneFails() = runBlocking {
        val local = phone("me", local = true).also { it.dead = true }
        val remote = phone("peer")
        val v = swarm(listOf(local, remote)).embed(listOf("abcd"))
        assertEquals(4f, v.single()[0])
    }

    @Test fun modelNameReportsFakeWhenEveryPhoneRunsTheFakeModel() = runBlocking {
        val llm = swarm(listOf(phone("a", TestLlm(modelName = "FAKE")), phone("b", TestLlm(modelName = "FAKE"))))
        llm.refreshCaps(force = true)
        assertEquals("FAKE", llm.modelName)
        val report = BatchRunner(llm).run("t", "swarm", batch(4))
        assertTrue(report.fake)
        assertEquals(listOf("FAKE"), report.models)
    }
}
