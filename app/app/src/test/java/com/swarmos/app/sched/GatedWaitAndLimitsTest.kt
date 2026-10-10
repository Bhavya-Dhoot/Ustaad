package com.swarmos.app.sched

import com.swarmos.app.core.SwarmJson
import com.swarmos.app.link.NodeClient
import com.swarmos.app.link.NodeHttpException
import io.ktor.http.HttpMethod
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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import java.net.ServerSocket

class GatedWaitAndLimitsTest {
    companion object {
        @JvmStatic @BeforeClass fun quietLogging() {
            runCatching { org.slf4j.LoggerFactory.getLogger("sched-limits-test") }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val servers = mutableListOf<EmbeddedServer<*, *>>()

    @After fun tearDown() {
        servers.forEach { it.stop(100, 500) }
        scope.cancel()
    }

    // ---- bounded wait for a slot -----------------------------------------------------------------------------------

    @Test fun aCallThatCannotGetASlotInTimeFailsWithATypedErrorAndLeavesTheQueue() = runBlocking {
        val phone = FakePhone("p", TestLlm(delayMs = 600), scope, capacity = 1)
        val gated = GatedLlmClient({ phone.llm }, phone.node, maxWaitMs = 150)
        val hold = async { phone.node.chat("hold", req("hold")) }
        withTimeout(3_000) { while (phone.node.chatExec.running == 0) delay(2) }
        val started = System.nanoTime()
        try {
            gated.chat(req("late"))
            fail("expected ModelBusyTimeoutException")
        } catch (e: ModelBusyTimeoutException) {
            val waited = (System.nanoTime() - started) / 1_000_000
            assertTrue("waited $waited ms", waited in 140..500)
            assertEquals(1, e.slots)
            assertTrue(e.message!!, e.message!!.startsWith("Waiting for the model: all 1 slot stayed busy for "))
            assertEquals("30 s wait reads as whole seconds", "Waiting for the model: all 2 slots stayed busy for 30 s.", ModelBusyTimeoutException(30_000, 2).message)
        }
        assertEquals("the timed-out call left the line", 0, phone.node.chatExec.queued)
        assertEquals("T:hold", hold.await().text)
        assertEquals(listOf("hold"), phone.llm.executed.toList())
    }

    @Test fun aStreamThatCannotGetASlotInTimeFailsTheSameWay() = runBlocking {
        val phone = FakePhone("p", TestLlm(delayMs = 500), scope, capacity = 1)
        val gated = GatedLlmClient({ phone.llm }, phone.node, maxWaitMs = 100)
        val hold = async { phone.node.chat("hold", req("hold")) }
        withTimeout(3_000) { while (phone.node.chatExec.running == 0) delay(2) }
        try {
            gated.chatStream(req("late")).toList()
            fail("expected ModelBusyTimeoutException")
        } catch (e: ModelBusyTimeoutException) {
        }
        assertEquals(0, phone.node.chatExec.queued)
        hold.await()
        Unit
    }

    @Test fun aCallThatGetsASlotWithinTheLimitRuns() = runBlocking {
        val phone = FakePhone("p", TestLlm(delayMs = 80), scope, capacity = 1)
        val gated = GatedLlmClient({ phone.llm }, phone.node, maxWaitMs = 2_000)
        val a = async { gated.chat(req("a")) }
        withTimeout(3_000) { while (phone.node.chatExec.running == 0) delay(2) }
        assertEquals("T:b", gated.chat(req("b")).text)
        assertEquals("T:a", a.await().text)
    }

    // ---- what a peer may ask for --------------------------------------------------------------------------------------

    private fun expect413(limit: Int, call: () -> Unit) {
        try {
            call()
            fail("expected HTTP 413")
        } catch (e: NodeHttpException) {
            assertEquals(413, e.status)
            val body = SwarmJson.decodeFromString<LimitBody>(e.body)
            assertEquals(limit, body.limit)
            assertTrue(body.error, body.error.contains(limit.toString()))
        }
    }

    private class Served(val host: String, val node: SchedNode, val client: NodeClient)

    private fun serve(): Served {
        val port = ServerSocket(0).use { it.localPort }
        val me = FakePhone("me", TestLlm(delayMs = 20), scope, isLocal = true)
        val swarm = SwarmLlm({ listOf(me) }, scope)
        servers += embeddedServer(CIO, port = port, host = "127.0.0.1") {
            install(ContentNegotiation) { json(SwarmJson) }
            routing {
                route("/v1/sched") {
                    schedNodeRoutes(me.node, { "secret" })
                    schedBatchRoutes(BatchStore(scope, { swarm }, { swarm }))
                }
            }
        }.also { it.start(wait = false) }
        return Served("127.0.0.1:$port", me.node, NodeClient({ "secret" }, { "peer" }))
    }

    private suspend fun Served.post(path: String, body: String) = client.raw(HttpMethod.Post, host, path, body)

    private fun chatBody(request: ChatRequestDto) = SwarmJson.encodeToString(ChatJobRequest("job-${request.hashCode()}", request))

    private fun dto(maxTokens: Int = 16, messages: List<MessageDto> = listOf(MessageDto("user", "hi")), schema: String? = null, stop: List<String> = emptyList()) =
        ChatRequestDto(messages, maxTokens, 0.0, schema, stop)

    @Test fun chatRefusesWhatIsBeyondTheLimitsWith413AndNamesTheLimit() = runBlocking {
        val s = serve()
        expect413(SchedLimits.MAX_TOKENS) { runBlocking { s.post("/sched/chat", chatBody(dto(maxTokens = SchedLimits.MAX_TOKENS + 1))) } }
        expect413(SchedLimits.MAX_TOKENS) { runBlocking { s.post("/sched/chat", chatBody(dto(maxTokens = 0))) } }
        expect413(SchedLimits.MAX_MESSAGES) { runBlocking { s.post("/sched/chat", chatBody(dto(messages = List(SchedLimits.MAX_MESSAGES + 1) { MessageDto("user", "x") }))) } }
        expect413(SchedLimits.MAX_MESSAGE_CHARS) { runBlocking { s.post("/sched/chat", chatBody(dto(messages = listOf(MessageDto("user", "x".repeat(SchedLimits.MAX_MESSAGE_CHARS + 1)))))) } }
        expect413(SchedLimits.MAX_PROMPT_CHARS) {
            runBlocking { s.post("/sched/chat", chatBody(dto(messages = List(5) { MessageDto("user", "x".repeat(SchedLimits.MAX_MESSAGE_CHARS)) }))) }
        }
        expect413(SchedLimits.MAX_SCHEMA_CHARS) { runBlocking { s.post("/sched/chat", chatBody(dto(schema = "x".repeat(SchedLimits.MAX_SCHEMA_CHARS + 1)))) } }
        expect413(SchedLimits.MAX_STOPS) { runBlocking { s.post("/sched/chat", chatBody(dto(stop = List(SchedLimits.MAX_STOPS + 1) { "s$it" }))) } }
        assertEquals("a refused request never took a slot", 0, s.node.chatExec.running)
        assertEquals(0, s.node.chatExec.remembered)
    }

    @Test fun requestsAtTheLimitsAreAccepted() = runBlocking {
        val s = serve()
        val atLimits = dto(
            maxTokens = SchedLimits.MAX_TOKENS,
            messages = List(4) { MessageDto("user", "x".repeat(SchedLimits.MAX_MESSAGE_CHARS)) },
            schema = "x".repeat(SchedLimits.MAX_SCHEMA_CHARS),
        )
        val answer = s.post("/sched/chat", chatBody(atLimits))
        assertTrue(answer, answer.contains("\"nodeId\":\"me\""))
    }

    @Test fun chatStreamRefusesOversizedRequestsBeforeTakingASlot() = runBlocking {
        val s = serve()
        expect413(SchedLimits.MAX_MESSAGES) {
            runBlocking { s.post("/sched/chat-stream", chatBody(dto(messages = List(SchedLimits.MAX_MESSAGES + 1) { MessageDto("user", "x") }))) }
        }
        assertEquals(0, s.node.chatExec.running)
    }

    @Test fun embedRefusesTooManyTextsAndTooLongTexts() = runBlocking {
        val s = serve()
        expect413(SchedLimits.MAX_TEXTS) {
            runBlocking { s.post("/sched/embed", SwarmJson.encodeToString(EmbedJobRequest("e1", List(SchedLimits.MAX_TEXTS + 1) { "t" }))) }
        }
        expect413(SchedLimits.MAX_TEXT_CHARS) {
            runBlocking { s.post("/sched/embed", SwarmJson.encodeToString(EmbedJobRequest("e2", listOf("x".repeat(SchedLimits.MAX_TEXT_CHARS + 1))))) }
        }
        val ok = s.post("/sched/embed", SwarmJson.encodeToString(EmbedJobRequest("e3", List(SchedLimits.MAX_TEXTS) { "t" })))
        assertTrue(ok.contains("\"vectors\""))
    }

    @Test fun batchRefusesTooManyRequestsAndOversizedOnesInsideIt() = runBlocking {
        val s = serve()
        expect413(SchedLimits.MAX_BATCH) {
            runBlocking { s.post("/sched/batch", SwarmJson.encodeToString(BatchStartRequest(requests = List(SchedLimits.MAX_BATCH + 1) { dto() }))) }
        }
        expect413(SchedLimits.MAX_TOKENS) {
            runBlocking { s.post("/sched/batch", SwarmJson.encodeToString(BatchStartRequest(requests = listOf(dto(), dto(maxTokens = 100_000))))) }
        }
        val ok = s.post("/sched/batch", SwarmJson.encodeToString(BatchStartRequest(builtin = Bench.BENCH32)))
        assertTrue(ok, ok.contains("\"total\":32"))
    }

    @Test fun theLimitCheckIsPureAndStatesTheLimit() {
        try {
            dto(maxTokens = 99_999).checkLimits()
            fail("expected RequestLimitException")
        } catch (e: RequestLimitException) {
            assertEquals(SchedLimits.MAX_TOKENS, e.limit)
            assertTrue(e.message!!, e.message!!.contains("4096"))
        }
        dto(maxTokens = SchedLimits.MAX_TOKENS).checkLimits()
    }
}
