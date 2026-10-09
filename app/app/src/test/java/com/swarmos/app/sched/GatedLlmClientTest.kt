package com.swarmos.app.sched

import com.swarmos.app.llm.ChatChunk
import com.swarmos.app.llm.ChatRequest
import com.swarmos.app.llm.ChatResult
import com.swarmos.app.llm.ChatStats
import com.swarmos.app.llm.LlmClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A model whose stream stays open until [finish] is completed. */
private class OpenStreamLlm : LlmClient {
    override val modelName = "T"
    val finish = CompletableDeferred<Unit>()
    val started = CompletableDeferred<Unit>()
    override suspend fun ready() = true
    override suspend fun chat(req: ChatRequest) = ChatResult("x", ChatStats(1, 1, 1.0, 1.0))
    override fun chatStream(req: ChatRequest): Flow<ChatChunk> = flow {
        emit(ChatChunk("a"))
        started.complete(Unit)
        finish.await()
        emit(ChatChunk("", done = true, stats = ChatStats(1, 1, 1.0, 1.0)))
    }
    override suspend fun embed(texts: List<String>) = emptyList<FloatArray>()
}

class GatedLlmClientTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() = scope.cancel()

    private suspend fun waitUntil(what: String, cond: () -> Boolean) {
        withTimeout(3_000) { while (!cond()) delay(2) }
    }

    @Test fun aChoirStyleDirectCallAndRemoteJobsNeverOverlapOnACapacityOnePhone() = runBlocking {
        val phone = FakePhone("p", TestLlm(delayMs = 40), scope, capacity = 1)
        val gated = GatedLlmClient({ phone.llm }, phone.node)
        val direct = (0 until 4).map { i -> async(Dispatchers.Default) { gated.chat(req("direct$i")).text } }
        val remote = (0 until 4).map { i ->
            async(Dispatchers.Default) {
                // what a peer does: ask, and ask again when it is told the phone is busy
                while (true) {
                    try { return@async phone.node.chat("remote$i", req("remote$i")).text } catch (e: BusyException) { delay(5) }
                }
                @Suppress("UNREACHABLE_CODE") ""
            }
        }
        val texts = (direct + remote).awaitAll()
        assertEquals(8, texts.size)
        assertEquals("never two at once", 1, phone.llm.maxConcurrent.get())
        assertEquals(8, phone.llm.executed.size)
        assertEquals(0, phone.node.chatExec.running)
    }

    @Test fun directCallsWaitForASlotInsteadOfBeingRefused() = runBlocking {
        val phone = FakePhone("p", TestLlm(delayMs = 30), scope, capacity = 1)
        val gated = GatedLlmClient({ phone.llm }, phone.node)
        val results = (0 until 5).map { i -> async(Dispatchers.Default) { gated.chat(req("q$i")).text } }.awaitAll()
        assertEquals((0 until 5).map { "T:q$it" }.toSet(), results.toSet())
        assertEquals(1, phone.llm.maxConcurrent.get())
    }

    @Test fun twoSlotsAllowTwoAtOnce() = runBlocking {
        val phone = FakePhone("p", TestLlm(delayMs = 60), scope, capacity = 2)
        val gated = GatedLlmClient({ phone.llm }, phone.node)
        (0 until 6).map { i -> async(Dispatchers.Default) { gated.chat(req("q$i")) } }.awaitAll()
        assertEquals(2, phone.llm.maxConcurrent.get())
    }

    @Test fun aStreamHoldsItsSlotUntilItEnds() = runBlocking {
        val llm = OpenStreamLlm()
        val phone = FakePhone("p", TestLlm(), scope, capacity = 1)
        val gated = GatedLlmClient({ llm }, phone.node)
        val chunks = async { gated.chatStream(req("x")).toList() }
        llm.started.await()
        assertEquals(1, phone.node.chatExec.running)
        try {
            phone.node.chat("remote", req("y"))
            fail("a remote job must be refused while a local stream holds the only slot")
        } catch (e: BusyException) {
            assertEquals(1, e.running)
        }
        llm.finish.complete(Unit)
        assertTrue(chunks.await().last().done)
        waitUntil("slot free") { phone.node.chatExec.running == 0 }
        assertEquals("T:y", phone.node.chat("remote", req("y")).text)
    }

    @Test fun cancellingAStreamFreesItsSlot() = runBlocking {
        val llm = OpenStreamLlm()
        val phone = FakePhone("p", TestLlm(), scope, capacity = 1)
        val gated = GatedLlmClient({ llm }, phone.node)
        val collector = launch { gated.chatStream(req("x")).collect { } }
        llm.started.await()
        assertEquals(1, phone.node.chatExec.running)
        collector.cancel()
        waitUntil("slot free") { phone.node.chatExec.running == 0 }
    }

    @Test fun cancellingAWaitingCallGivesUpItsPlaceInLine() = runBlocking {
        val phone = FakePhone("p", TestLlm(delayMs = 150), scope, capacity = 1)
        val gated = GatedLlmClient({ phone.llm }, phone.node)
        val first = async { gated.chat(req("first")) }
        waitUntil("first running") { phone.node.chatExec.running == 1 }
        val waiter = launch { gated.chat(req("second")) }
        waitUntil("second waiting") { phone.node.chatExec.queued == 1 }
        waiter.cancel()
        waitUntil("queue empty") { phone.node.chatExec.queued == 0 }
        assertEquals("T:first", first.await().text)
        assertEquals(0, phone.node.chatExec.running)
        assertEquals(listOf("first"), phone.llm.executed.toList())
    }

    // ---- capacity follows the chat server ----------------------------------------------------------------------

    @Test fun capacityZeroMeansUnavailableToOthersButLocalCallsStillRun() = runBlocking {
        val phone = FakePhone("p", TestLlm(), scope, capacity = 0)
        val gated = GatedLlmClient({ phone.llm }, phone.node)
        assertEquals("T:x", gated.chat(req("x")).text)
        assertEquals(0, phone.node.chatExec.running)
        assertEquals(0, phone.node.caps().capacity)
        try {
            phone.node.chat("remote", req("y"))
            fail("a phone without a model server must refuse remote jobs")
        } catch (e: BusyException) {
            assertEquals(0, e.capacity)
        }
    }

    @Test fun theSwarmSkipsAPhoneWithCapacityZeroAndFailsFastWhenNoPhoneHasASlot() = runBlocking {
        val off = FakePhone("off", TestLlm(), scope, capacity = 0, tokPerSec = 99.0)
        val on = FakePhone("on", TestLlm(), scope, capacity = 1, tokPerSec = 1.0)
        val llm = SwarmLlm({ listOf(off, on) }, scope, capsMaxAgeMs = 50)
        assertEquals("on", llm.chatJob("a", req("a")).nodeId)
        try {
            SwarmLlm({ listOf(off) }, scope, capsMaxAgeMs = 50).chatJob("b", req("b"))
            fail("expected NoPhoneAvailableException")
        } catch (e: NoPhoneAvailableException) {
            // fails at once instead of waiting for a server that is not running
        }
    }

    @Test fun whenTheServerStopsWaitingCallersAreReleasedToRunWithoutASlot() = runBlocking {
        val phone = FakePhone("p", TestLlm(delayMs = 200), scope, capacity = 1)
        val gated = GatedLlmClient({ phone.llm }, phone.node)
        val first = async { gated.chat(req("first")) }
        waitUntil("first running") { phone.node.chatExec.running == 1 }
        val second = async { gated.chat(req("second")) }
        waitUntil("second waiting") { phone.node.chatExec.queued == 1 }
        phone.node.capacity = 0
        assertEquals("T:second", second.await().text)
        assertEquals("T:first", first.await().text)
        waitUntil("slots back to zero") { phone.node.chatExec.running == 0 }
    }

    @Test fun aCapacityRiseLetsWaitingCallersIn() = runBlocking {
        val phone = FakePhone("p", TestLlm(delayMs = 150), scope, capacity = 1)
        val gated = GatedLlmClient({ phone.llm }, phone.node)
        val a = async { gated.chat(req("a")) }
        waitUntil("a running") { phone.node.chatExec.running == 1 }
        val b = async { gated.chat(req("b")) }
        waitUntil("b waiting") { phone.node.chatExec.queued == 1 }
        phone.node.capacity = 2
        waitUntil("b running beside a") { phone.llm.concurrent.get() == 2 }
        listOf(a, b).awaitAll()
        assertEquals(0, phone.node.chatExec.running)
    }
}
