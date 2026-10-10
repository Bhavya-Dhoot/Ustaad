package com.swarmos.app.sched

import com.swarmos.app.core.NodeInfo
import com.swarmos.app.core.SwarmJson
import com.swarmos.app.link.NodeClient
import com.swarmos.app.llm.ChatChunk
import com.swarmos.app.llm.ChatRequest
import com.swarmos.app.llm.ChatResult
import com.swarmos.app.llm.ChatStats
import com.swarmos.app.llm.LlmClient
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
import org.junit.BeforeClass
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

/** A model that streams "a" and then fails. */
private class BreakingStreamLlm : LlmClient {
    val streams = AtomicInteger()
    override val modelName = "T"
    override suspend fun ready() = true
    override suspend fun chat(req: ChatRequest) = ChatResult("x", ChatStats(1, 1, 1.0, 1.0))
    override fun chatStream(req: ChatRequest): Flow<ChatChunk> = flow {
        streams.incrementAndGet()
        emit(ChatChunk("a"))
        error("model crashed")
    }
    override suspend fun embed(texts: List<String>) = emptyList<FloatArray>()
}

/** Streaming from another phone over server-sent events, over loopback HTTP. */
class SchedStreamTest {
    companion object {
        @JvmStatic @BeforeClass fun quietLogging() {
            runCatching { org.slf4j.LoggerFactory.getLogger("sched-stream-test") }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val servers = mutableListOf<EmbeddedServer<*, *>>()

    @After fun tearDown() {
        servers.forEach { it.stop(100, 500) }
        scope.cancel()
    }

    private class Peer(val node: SchedNode, val remote: RemoteLlmClient, val llm: LlmClient)

    /** A phone serving the real routes (pings every 100 ms) and a client that streams from it. */
    private fun peer(llm: LlmClient, id: String = "peer"): Peer {
        val port = ServerSocket(0).use { it.localPort }
        val node = SchedNode(scope, { id }, { llm }, { llm }, { null })
        servers += embeddedServer(CIO, port = port, host = "127.0.0.1") {
            install(ContentNegotiation) { json(SwarmJson) }
            routing { route("/v1/sched") { schedNodeRoutes(node, { "secret" }, streamPingMs = 100) } }
        }.also { it.start(wait = false) }
        val info = NodeInfo(id, "Phone $id", "x", "127.0.0.1:$port", "t", llm.modelName, null)
        return Peer(node, RemoteLlmClient(info, NodeClient({ "secret" }, { "me" }), StreamClient({ "secret" }, { "me" })), llm)
    }

    private suspend fun waitUntil(cond: () -> Boolean) = withTimeout(3_000) { while (!cond()) delay(5) }

    @Test fun tokensArriveOverServerSentEventsAndEndWithStats() = runBlocking {
        val p = peer(TestLlm())
        val chunks = p.remote.chatStream(req("hello")).toList()
        assertEquals("stream:hello", chunks.filter { !it.done }.joinToString("") { it.delta })
        assertEquals("tokens were not one lump", 2, chunks.count { !it.done })
        assertEquals(10, chunks.last().stats!!.completionTokens)
        assertTrue(chunks.last().done)
        assertEquals("the slot is free again", 0, p.node.chatExec.running)
    }

    @Test fun aFullPhoneAnswers429BeforeTheFirstToken() = runBlocking {
        val llm = OpenStreamLlm()
        val p = peer(llm)
        val holder = launch { p.remote.chatStream(req("x")).collect { } }
        llm.started.await()
        val seen = mutableListOf<ChatChunk>()
        try {
            p.remote.chatStream(req("y")).collect { seen += it }
            fail("expected BusyException")
        } catch (e: BusyException) {
            assertEquals(1, e.capacity)
        }
        assertTrue(seen.isEmpty())
        holder.cancel()
    }

    @Test fun cancellingTheStreamClosesItAndFreesTheRemoteSlot() = runBlocking {
        val llm = OpenStreamLlm()
        val p = peer(llm)
        val collector = launch { p.remote.chatStream(req("x")).collect { } }
        llm.started.await()
        assertEquals(1, p.node.chatExec.running)
        collector.cancel()
        waitUntil { p.node.chatExec.running == 0 }
    }

    @Test fun aPinnedModelThePeerDoesNotRunAnswers409() = runBlocking {
        val p = peer(TestLlm(modelName = "A"))
        try {
            p.remote.streamIfFree(req("x"), "B", kotlinx.coroutines.flow.FlowCollector { })
            fail("expected ModelMismatchException")
        } catch (e: ModelMismatchException) {
            assertEquals("A", e.model)
        }
    }

    @Test fun theSwarmStreamsFromAPeerWhenThisPhoneIsBusy() = runBlocking {
        val me = FakePhone("me", TestLlm(delayMs = 300), scope, isLocal = true)
        val p = peer(TestLlm())
        val llm = SwarmLlm({ listOf(me, p.remote) }, scope, capsMaxAgeMs = 50)
        val hold = async { me.node.chat("hold", req("hold")) }
        waitUntil { me.node.chatExec.running == 1 }
        val chunks = llm.chatStream(req("hi")).toList()
        assertEquals("stream:hi", chunks.filter { !it.done }.joinToString("") { it.delta })
        assertEquals("streamed from the peer, not one lump", 2, chunks.count { !it.done })
        assertEquals("T:hold", hold.await().text)
    }

    @Test fun aStreamThatBreaksAfterTokensIsNotRetriedOnAnotherPhone() = runBlocking {
        val me = FakePhone("me", TestLlm(), scope, isLocal = true)
        val brokenLlm = BreakingStreamLlm()
        val broken = peer(brokenLlm, "broken")
        val otherLlm = TestLlm()
        val other = peer(otherLlm, "other")
        // reports are not refreshed in the background during the test: nothing changes who is eligible while the stream is routed
        val llm = SwarmLlm({ listOf(me, broken.remote, other.remote) }, scope, capsMaxAgeMs = 60_000)
        // no timing: this phone's one slot is taken by hand and stays taken until the end, so the stream cannot be served here
        assertTrue(me.node.chatExec.acquire())
        // and every phone's report is in before the stream is routed, so the choice is made by the rules (equal speed: lower node id,
        // "broken") and not by whose HTTP reply arrived first
        withTimeout(30_000) { while (llm.phoneViews().count { it.up } < 3) { llm.refreshCaps(force = true); delay(20) } }
        val seen = mutableListOf<String>()
        try {
            llm.chatStream(req("hi")).collect { seen += it.delta }
            fail("expected the stream to fail; seen=$seen; streams started: me=${me.llm.streamCalls.get()} broken=${brokenLlm.streams.get()} other=${otherLlm.streamCalls.get()}")
        } catch (e: IOException) {
            assertTrue(e.message!!, e.message!!.contains("model crashed"))
        }
        assertEquals(listOf("a"), seen)
        assertEquals("the broken phone was the one asked", 1, brokenLlm.streams.get())
        assertEquals("the other phone was not asked to continue", 0, otherLlm.streamCalls.get())
        me.node.chatExec.release()
        assertEquals(0, me.node.chatExec.running)
    }
}
