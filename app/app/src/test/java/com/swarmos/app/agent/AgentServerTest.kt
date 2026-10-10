package com.swarmos.app.agent

import com.swarmos.app.llm.ChatChunk
import com.swarmos.app.llm.ChatRequest
import com.swarmos.app.llm.ChatResult
import com.swarmos.app.llm.ChatStats
import com.swarmos.app.llm.FakeLlmClient
import com.swarmos.app.llm.LlamaServerException
import com.swarmos.app.llm.LlmClient
import com.swarmos.app.sched.FakePhone
import com.swarmos.app.sched.GatedLlmClient
import com.swarmos.app.sched.SchedNode
import com.swarmos.app.sched.SwarmLlm
import com.swarmos.app.sched.TestLlm
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeNotNull
import org.junit.BeforeClass
import org.junit.Test
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A model that streams one chunk and then waits forever, like a long generation, and notes when it is cancelled. */
private class HangingLlm : LlmClient {
    override val modelName = "H"
    val started = AtomicInteger()
    val cancelled = AtomicInteger()

    override suspend fun ready() = true

    override fun chatStream(req: ChatRequest): Flow<ChatChunk> = flow {
        started.incrementAndGet()
        try {
            emit(ChatChunk("hello"))
            awaitCancellation()
        } catch (e: CancellationException) {
            cancelled.incrementAndGet()
            throw e
        }
    }

    override suspend fun chat(req: ChatRequest): ChatResult {
        started.incrementAndGet()
        try {
            awaitCancellation()
        } catch (e: CancellationException) {
            cancelled.incrementAndGet()
            throw e
        }
    }

    override suspend fun embed(texts: List<String>): List<FloatArray> = emptyList()
}

/** A model that writes far more than any socket buffer holds, as fast as it is allowed to, and notes when it is cancelled. */
private class FloodLlm : LlmClient {
    override val modelName = "F"
    val cancelled = AtomicInteger()

    override suspend fun ready() = true

    override fun chatStream(req: ChatRequest): Flow<ChatChunk> = flow {
        try {
            val big = "x".repeat(64 * 1024)
            repeat(4_000) { emit(ChatChunk(big)) }
            emit(ChatChunk("", done = true, stats = ChatStats(1, 1, 1.0, 1.0)))
        } catch (e: CancellationException) {
            cancelled.incrementAndGet()
            throw e
        }
    }

    override suspend fun chat(req: ChatRequest): ChatResult = ChatResult("", ChatStats(1, 1, 1.0, 1.0))

    override suspend fun embed(texts: List<String>): List<FloatArray> = emptyList()
}

/** A model that fails the way a broken one does: with a message that must never reach the caller. */
private class FailingLlm(private val error: () -> Throwable) : LlmClient {
    override val modelName = "X"

    override suspend fun ready() = true

    override fun chatStream(req: ChatRequest): Flow<ChatChunk> = flow { throw error() }

    override suspend fun chat(req: ChatRequest): ChatResult = throw error()

    override suspend fun embed(texts: List<String>): List<FloatArray> = throw error()
}

/** An embedder that holds every call until [release] is completed. */
private class BlockingEmbedder : LlmClient {
    override val modelName = "B"
    val entered = AtomicInteger()
    val release = kotlinx.coroutines.CompletableDeferred<Unit>()

    override suspend fun ready() = true

    override fun chatStream(req: ChatRequest): Flow<ChatChunk> = flow { }

    override suspend fun chat(req: ChatRequest): ChatResult = ChatResult("", ChatStats(0, 0, 0.0, 0.0))

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        entered.incrementAndGet()
        release.await()
        return texts.map { floatArrayOf(1f) }
    }
}

/** A model that answers fast and keeps the request it was given. */
private class RecordingLlm : LlmClient {
    override val modelName = "R"
    val requests = CopyOnWriteArrayList<ChatRequest>()

    override suspend fun ready() = true

    override fun chatStream(req: ChatRequest): Flow<ChatChunk> = flow {
        requests += req
        emit(ChatChunk("ok"))
        emit(ChatChunk("", done = true, stats = ChatStats(3, 1, 1.0, 1.0)))
    }

    override suspend fun chat(req: ChatRequest): ChatResult {
        requests += req
        return ChatResult("ok", ChatStats(3, 1, 1.0, 1.0))
    }

    override suspend fun embed(texts: List<String>): List<FloatArray> = emptyList()
}

/** A model that always writes [text] (in two pieces when streamed) or fails with [error], and keeps the requests it was given. */
private class ScriptedLlm(private val text: String = "", private val error: (() -> Throwable)? = null) : LlmClient {
    override val modelName = "S"
    val requests = CopyOnWriteArrayList<ChatRequest>()

    override suspend fun ready() = true

    override fun chatStream(req: ChatRequest): Flow<ChatChunk> = flow {
        requests += req
        error?.let { throw it() }
        val cut = text.length / 2
        if (cut > 0) emit(ChatChunk(text.substring(0, cut)))
        emit(ChatChunk(text.substring(cut)))
        emit(ChatChunk("", done = true, stats = ChatStats(7, 9, 1.0, 1.0)))
    }

    override suspend fun chat(req: ChatRequest): ChatResult {
        requests += req
        error?.let { throw it() }
        return ChatResult(text, ChatStats(7, 9, 1.0, 1.0))
    }

    override suspend fun embed(texts: List<String>): List<FloatArray> = emptyList()
}

/** The real engine, gate, scheduler and request path over loopback HTTP; only the models are test doubles. */
class AgentServerTest {
    companion object {
        /** The app bundles an Android slf4j binding that cannot start on the JVM; trigger and swallow its failure once. */
        @JvmStatic @BeforeClass fun quietLogging() {
            runCatching { org.slf4j.LoggerFactory.getLogger("agent-test") }
        }
    }

    private class Resp(val status: Int, private val headers: Map<String, List<String>>, val body: String) {
        val json: JsonObject get() = Json.parseToJsonElement(body).jsonObject
        fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()
        fun hasHeader(prefix: String) = headers.keys.any { it.startsWith(prefix.lowercase()) }
        val errorCode: String get() = json.getValue("error").jsonObject.getValue("code").jsonPrimitive.content
        val errorMessage: String get() = json.getValue("error").jsonObject.getValue("message").jsonPrimitive.content
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(java.time.Duration.ofSeconds(5)).build()
    private val key = "test-key-0123456789"
    private val audit = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    private val served = AtomicInteger()
    private val refused = AtomicInteger()
    private var port = 0
    private var server: AgentServer? = null

    private lateinit var node: SchedNode
    @Volatile private var chat: LlmClient = TestLlm("T", delayMs = 10)
    @Volatile private var chatReady = true
    @Volatile private var swarmReady = true
    @Volatile private var embedReady = true
    @Volatile private var filesOn = false
    private val embedder = TestLlm("E")
    @Volatile private var embedderClient: LlmClient = embedder

    @After fun tearDown() {
        server?.stop()
        scope.cancel()
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }

    /** Starts the listener. [maxWaitMs] is the gate's wait; [phones] are the swarm. */
    private fun start(
        maxWaitMs: Long = 400,
        phones: List<FakePhone> = listOf(FakePhone("b", TestLlm("M"), scope, 1, isLocal = false, tokPerSec = 10.0)),
        writeTimeoutMs: Long = AgentServer.WRITE_TIMEOUT_MS,
        files: FileSource? = null,
    ) {
        node = SchedNode(scope, { "a" }, { chat }, { embedder }, { null })
        val backend = AgentBackend(
            local = AgentBackend.gate({ chat }, node, maxWaitMs),
            localReady = { chatReady },
            swarm = SwarmLlm({ phones }, scope, capsMaxAgeMs = 50, busyBackoffMs = 20, probeEveryMs = 100),
            swarmReady = { swarmReady },
            embedder = { RealModelOnly({ embedderClient }, AgentBackend.NO_EMBEDDER) },
            embedderReady = { embedReady },
            selfName = { "Phone A" },
            nameOf = { id -> if (id == "b") "Phone B" else id },
        )
        port = freePort()
        server = AgentServer(
            port, { key }, backend,
            files = files, filesEnabled = { filesOn },
            audit = { kind, payload -> audit += kind to payload },
            onServed = { served.incrementAndGet() },
            onRefused = { refused.incrementAndGet() },
            pingMs = 100,
            writeTimeoutMs = writeTimeoutMs,
        )
        assertNull(server!!.start())
    }

    private fun call(
        method: String, path: String, body: String? = null, key: String? = this.key, headers: Map<String, String> = emptyMap(),
    ): Resp {
        val b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).timeout(java.time.Duration.ofSeconds(20))
        if (key != null) b.header("Authorization", "Bearer $key")
        if (body != null) b.header("Content-Type", "application/json")
        for ((k, v) in headers) b.header(k, v)
        b.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
        return Resp(r.statusCode(), r.headers().map(), r.body())
    }

    private fun post(path: String, body: String, key: String? = this.key, headers: Map<String, String> = emptyMap()) = call("POST", path, body, key, headers)

    private fun chatBody(extra: String = "", model: String = "ustaad-local", user: String = "hello") =
        """{"model":"$model","messages":[{"role":"user","content":"$user"}]${if (extra.isEmpty()) "" else ",$extra"}}"""

    private fun waitUntil(what: String, ms: Long = 5_000, cond: () -> Boolean) {
        val end = System.nanoTime() + ms * 1_000_000
        while (!cond()) {
            if (System.nanoTime() > end) fail("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    private fun events(body: String): List<String> = body.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith(":") }

    // ---- auth ----------------------------------------------------------------------------------------------

    @Test fun noKeyIs401() {
        start()
        val r = call("GET", "/v1/models", key = null)
        assertEquals(401, r.status)
        assertEquals("invalid_api_key", r.errorCode)
        assertEquals("Bearer", r.header("WWW-Authenticate"))
        assertEquals("application/json", r.header("Content-Type")!!.substringBefore(';'))
    }

    @Test fun aWrongKeyIs401AndTheModelIsNeverCalled() {
        start()
        val llm = chat as TestLlm
        assertEquals(401, call("GET", "/v1/models", key = "wrong").status)
        assertEquals(401, post("/v1/chat/completions", chatBody(), key = "wrong").status)
        assertEquals(401, post("/v1/chat/completions", chatBody(), key = key.dropLast(1)).status)
        assertEquals(401, post("/v1/chat/completions", chatBody(), key = key + "x").status)
        assertEquals(401, post("/v1/chat/completions", chatBody(), key = null, headers = mapOf("Authorization" to "Basic $key")).status)
        assertEquals(0, llm.executed.size)
        assertEquals(0, llm.streamCalls.get())
    }

    @Test fun theRightKeyIs200() {
        start()
        val r = call("GET", "/v1/models")
        assertEquals(200, r.status)
        assertEquals(listOf("ustaad-local", "ustaad-swarm"), r.json.getValue("data").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
    }

    @Test fun everyRouteNeedsTheKeyEvenUnknownOnes() {
        start()
        for ((m, p) in listOf("GET" to "/v1/models", "POST" to "/v1/chat/completions", "POST" to "/v1/embeddings", "GET" to "/nothing", "GET" to "/", "DELETE" to "/v1/models", "OPTIONS" to "/v1/models")) {
            val r = call(m, p, body = if (m == "POST") "{}" else null, key = null)
            assertEquals("$m $p", 401, r.status)
        }
    }

    @Test fun anUnauthenticatedRequestIsRefusedBeforeItsBodyIsConsidered() {
        start()
        val s = rawRequest("POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nContent-Length: 99999999\r\nConnection: close\r\n\r\n")
        assertTrue(s, s.startsWith("HTTP/1.1 401"))
    }

    @Test fun noCorsHeadersAreEverSent() {
        start()
        val ok = call("GET", "/v1/models", headers = mapOf("Origin" to "https://example.com"))
        assertFalse(ok.hasHeader("access-control-"))
        val denied = call("GET", "/v1/models", key = null, headers = mapOf("Origin" to "https://example.com"))
        assertFalse(denied.hasHeader("access-control-"))
        val preflight = call("OPTIONS", "/v1/chat/completions", key = null, headers = mapOf("Origin" to "https://example.com", "Access-Control-Request-Method" to "POST", "Access-Control-Request-Headers" to "authorization"))
        assertEquals(401, preflight.status)
        assertFalse(preflight.hasHeader("access-control-"))
    }

    @Test fun thePortIsNotOpenOnAnyOtherAddress() {
        start()
        val other = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp }.flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
        assumeNotNull(other)
        try {
            Socket().use { it.connect(InetSocketAddress(other, port), 2_000) }
            fail("connected to the agent port on ${other!!.hostAddress}")
        } catch (e: java.io.IOException) {
            // refused or unreachable: the listener is on 127.0.0.1 only
        }
        assertEquals(200, call("GET", "/v1/models").status)
    }

    // ---- chat, not streamed ---------------------------------------------------------------------------------

    @Test fun aChatAnswerHasTheOpenAiShapeAndNamesThePhone() {
        start()
        val r = post("/v1/chat/completions", chatBody())
        assertEquals(200, r.status)
        assertEquals("application/json", r.header("Content-Type")!!.substringBefore(';'))
        val j = r.json
        assertTrue(j.getValue("id").jsonPrimitive.content.startsWith("chatcmpl-"))
        assertEquals("chat.completion", j.getValue("object").jsonPrimitive.content)
        assertEquals("ustaad-local", j.getValue("model").jsonPrimitive.content)
        assertEquals("ustaad:Phone A", j.getValue("system_fingerprint").jsonPrimitive.content)
        val choice = j.getValue("choices").jsonArray.single().jsonObject
        assertEquals("T:hello", choice.getValue("message").jsonObject.getValue("content").jsonPrimitive.content)
        assertEquals("assistant", choice.getValue("message").jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("stop", choice.getValue("finish_reason").jsonPrimitive.content)
        val u = j.getValue("usage").jsonObject
        assertEquals(5, u.getValue("prompt_tokens").jsonPrimitive.int)
        assertEquals(10, u.getValue("completion_tokens").jsonPrimitive.int)
        assertEquals(15, u.getValue("total_tokens").jsonPrimitive.int)
    }

    @Test fun anAnswerThatUsedItsWholeBudgetSaysLength() {
        start()
        val r = post("/v1/chat/completions", chatBody(""""max_tokens":10"""))
        assertEquals("length", r.json.getValue("choices").jsonArray.single().jsonObject.getValue("finish_reason").jsonPrimitive.content)
    }

    @Test fun theRequestReachesTheModelAsAChatRequest() {
        val rec = RecordingLlm()
        start()
        chat = rec
        val schema = """{"type":"object","properties":{"a":{"type":"integer"}}}"""
        val r = post(
            "/v1/chat/completions",
            """{"model":"ustaad-local","messages":[{"role":"system","content":"be brief"},{"role":"user","content":[{"type":"text","text":"a"},{"type":"text","text":"b"}]}],""" +
                """"max_completion_tokens":5000,"temperature":0.1,"stop":["X","Y"],"seed":7,"response_format":{"type":"json_schema","json_schema":{"name":"n","schema":$schema}}}""",
        )
        assertEquals(r.body, 200, r.status)
        val q = rec.requests.single()
        assertEquals(listOf("system" to "be brief", "user" to "a\nb"), q.messages.map { it.role to it.content })
        assertEquals(1024, q.maxTokens)
        assertEquals(0.1, q.temperature, 0.0)
        assertEquals(listOf("X", "Y"), q.stop)
        assertEquals(7, q.seed)
        assertEquals(schema, q.jsonSchema)
    }

    @Test fun theSwarmModelNamesTheNodeThatRanIt() {
        start()
        val r = post("/v1/chat/completions", chatBody(model = "ustaad-swarm"))
        assertEquals(r.body, 200, r.status)
        assertEquals("ustaad-swarm", r.json.getValue("model").jsonPrimitive.content)
        assertEquals("ustaad:Phone B", r.json.getValue("system_fingerprint").jsonPrimitive.content)
        assertEquals("M:hello", r.json.getValue("choices").jsonArray.single().jsonObject.getValue("message").jsonObject.getValue("content").jsonPrimitive.content)
    }

    @Test fun theLocalModelDoesNotTouchTheSwarm() {
        val b = FakePhone("b", TestLlm("M"), CoroutineScope(SupervisorJob()), 1, isLocal = false, tokPerSec = 10.0)
        start(phones = listOf(b))
        post("/v1/chat/completions", chatBody())
        assertEquals(0, b.calls.get())
        assertEquals(1, (chat as TestLlm).executed.size)
    }

    // ---- chat, streamed -------------------------------------------------------------------------------------

    @Test fun aStreamIsEventsEndingWithDone() {
        start()
        val r = post("/v1/chat/completions", chatBody(""""stream":true"""), headers = mapOf("Accept" to "text/event-stream"))
        assertEquals(r.body, 200, r.status)
        assertEquals("text/event-stream", r.header("Content-Type")!!.substringBefore(';'))
        val ev = events(r.body)
        assertEquals("data: [DONE]", ev.last())
        assertTrue(ev.dropLast(1).all { it.startsWith("data: {") })
        val chunks = ev.dropLast(1).map { Json.parseToJsonElement(it.removePrefix("data: ")).jsonObject }
        assertEquals("one id for the whole answer", 1, chunks.map { it.getValue("id").jsonPrimitive.content }.toSet().size)
        assertTrue(chunks.all { it.getValue("object").jsonPrimitive.content == "chat.completion.chunk" })
        assertTrue(chunks.all { it.getValue("system_fingerprint").jsonPrimitive.content == "ustaad:Phone A" })
        val deltas = chunks.map { it.getValue("choices").jsonArray.single().jsonObject.getValue("delta").jsonObject }
        assertEquals("assistant", deltas.first().getValue("role").jsonPrimitive.content)
        assertEquals("stream:hello", deltas.mapNotNull { it["content"]?.jsonPrimitive?.content }.joinToString(""))
        val last = chunks.last()
        assertEquals("stop", last.getValue("choices").jsonArray.single().jsonObject.getValue("finish_reason").jsonPrimitive.content)
        assertEquals(15, last.getValue("usage").jsonObject.getValue("total_tokens").jsonPrimitive.int)
        assertTrue(chunks.dropLast(1).none { "usage" in it })
    }

    @Test fun aStreamedSwarmAnswerIsFingerprintedSwarm() {
        val local = FakePhone("a", TestLlm("M"), scope, 1, isLocal = true, tokPerSec = 10.0)
        start(phones = listOf(local))
        val r = post("/v1/chat/completions", chatBody(""""stream":true""", model = "ustaad-swarm"))
        assertEquals(r.body, 200, r.status)
        val first = Json.parseToJsonElement(events(r.body).first().removePrefix("data: ")).jsonObject
        assertEquals("ustaad:swarm", first.getValue("system_fingerprint").jsonPrimitive.content)
        assertEquals("data: [DONE]", events(r.body).last())
        assertEquals(1, local.llm.streamCalls.get())
    }

    @Test fun aClientThatHangsUpMidStreamFreesTheModelSlot() {
        val hang = HangingLlm()
        start()
        chat = hang
        val s = Socket("127.0.0.1", port)
        s.soTimeout = 5_000
        val body = chatBody(""""stream":true""")
        val head = "POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAuthorization: Bearer $key\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n"
        s.getOutputStream().write(head.toByteArray() + body.toByteArray())
        s.getOutputStream().flush()
        val seen = StringBuilder()
        val buf = ByteArray(4096)
        while (!seen.contains("\"content\":\"hello\"")) {
            val n = s.getInputStream().read(buf)
            if (n < 0) fail("stream ended early: $seen")
            seen.append(String(buf, 0, n))
        }
        assertEquals("the stream holds the slot while it runs", 1, node.chatExec.running)
        s.close()
        waitUntil("the slot to be freed") { node.chatExec.running == 0 }
        waitUntil("the model call to be cancelled") { hang.cancelled.get() == 1 }
        waitUntil("the audit entry of the unfinished stream") { audit.any { it.first == "agent.chat" && it.second["aborted"] != null } }
        assertEquals("a stream the client abandoned is not a served request", 0, served.get())
        chat = TestLlm("T", delayMs = 10)
        assertEquals("a new request gets the slot", 200, post("/v1/chat/completions", chatBody()).status)
    }

    @Test fun aStreamThatCannotGetTheModelIs503NotAnEmpty200() {
        val hang = HangingLlm()
        start(maxWaitMs = 300)
        chat = hang
        val holder = Socket("127.0.0.1", port)
        val body = chatBody(""""stream":true""")
        holder.getOutputStream().write(("POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAuthorization: Bearer $key\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body").toByteArray())
        waitUntil("the first stream to hold the slot") { node.chatExec.running == 1 }
        val r = post("/v1/chat/completions", chatBody(""""stream":true"""))
        assertEquals(r.body, 503, r.status)
        assertEquals("model_busy", r.errorCode)
        holder.close()
        waitUntil("the slot to be freed") { node.chatExec.running == 0 }
    }

    // ---- one at a time --------------------------------------------------------------------------------------

    @Test fun twoRequestsAtOnceRunOneAfterTheOther() {
        val slow = TestLlm("T", delayMs = 300)
        start(maxWaitMs = 10_000)
        chat = slow
        val t0 = System.nanoTime()
        val results = (1..2).map { i -> java.util.concurrent.CompletableFuture.supplyAsync { post("/v1/chat/completions", chatBody(user = "q$i")) } }
        val responses = results.map { it.get(20, TimeUnit.SECONDS) }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(listOf(200, 200), responses.map { it.status })
        assertEquals("never two model calls at once", 1, slow.maxConcurrent.get())
        assertTrue("the second one waited for the first ($ms ms)", ms >= 550)
    }

    @Test fun aRequestThatWaitsTooLongForTheModelIs503() {
        val hang = HangingLlm()
        start(maxWaitMs = 300)
        chat = hang
        val first = java.util.concurrent.CompletableFuture.supplyAsync { post("/v1/chat/completions", chatBody()) }
        waitUntil("the first request to hold the slot") { node.chatExec.running == 1 }
        val r = post("/v1/chat/completions", chatBody())
        assertEquals(r.body, 503, r.status)
        assertEquals("model_busy", r.errorCode)
        assertEquals("server_error", r.json.getValue("error").jsonObject.getValue("type").jsonPrimitive.content)
        first.cancel(true)
    }

    // ---- file search ------------------------------------------------------------------------------------------

    private val billC = FileFakes.candidate(1, "hash-bill", "Electricity bill", "Electricity bill for September 1,240 rupees")
    private val salaryC = FileFakes.candidate(2, "hash-salary", "Salary slip", "Salary slip net pay 85,000", folder = "Documents/Salary", isPrivate = true, kind = "document")
    private val taxC = FileFakes.candidate(3, "hash-tax", "Tax return", "Tax return PAN ABCDE1234F", folder = "Documents/Private", kind = "document")

    private fun search(body: String = """{"query":"electricity bill"}""") = post("/v1/files/search", body)

    private fun phonesOf(r: Resp) = r.json.getValue("phones").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content to it.jsonObject.getValue("status").jsonPrimitive.content }

    @Test fun fileSearchIsOffUntilTheOwnerSwitchesItOn() {
        val calls = FileFakes.Calls()
        start(files = FileFakes.source(listOf(billC), calls))
        val r = search()
        assertEquals(r.body, 403, r.status)
        assertEquals("file_search_off", r.errorCode)
        assertEquals("invalid_request_error", r.json.getValue("error").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("the index is not touched", 0, calls.local.get())
        assertEquals(1, refused.get())
        assertTrue(audit.none { it.first == "agent.files" })
        filesOn = true
        assertEquals(200, search().status)
        filesOn = false
        assertEquals("switching it off again takes effect at once", 403, search().status)
        assertEquals(1, calls.local.get())
    }

    @Test fun withoutAnyFileSourceTheRouteIs403Too() {
        start()
        filesOn = true
        assertEquals("file_search_off", search().errorCode)
    }

    @Test fun theFileRouteAsksForTheKeyBeforeAnythingElse() {
        val calls = FileFakes.Calls()
        start(files = FileFakes.source(listOf(billC), calls))
        // off or on, a request without the right key is a 401, not a hint about the switch
        assertEquals(401, post("/v1/files/search", """{"query":"x"}""", key = null).status)
        assertEquals(401, post("/v1/files/search", """{"query":"x"}""", key = "wrong").status)
        filesOn = true
        assertEquals(401, post("/v1/files/search", """{"query":"x"}""", key = null).status)
        assertEquals(0, calls.local.get())
        assertEquals(404, call("GET", "/v1/files/search").status)
    }

    @Test fun privateFilesNeverAppearInTheRawAnswer() {
        start(files = FileFakes.source(listOf(salaryC, billC, taxC)))
        filesOn = true
        val r = search()
        assertEquals(r.body, 200, r.status)
        assertEquals(setOf("object", "data", "scope", "incomplete", "phones"), r.json.keys)
        val data = r.json.getValue("data").jsonArray.map { it.jsonObject }
        assertEquals(listOf("Electricity bill"), data.map { it.getValue("title").jsonPrimitive.content })
        assertEquals(setOf("phone", "title", "date", "kind", "score", "snippet"), data.single().keys)
        assertEquals("Phone A", data.single().getValue("phone").jsonPrimitive.content)
        // the raw text: nothing of the private files, no hash of any file, no path, no count of what was held back
        for (secret in listOf("Salary", "85,000", "Tax return", "ABCDE", "hash-", "uriHash", "Documents", "Pictures", "Private", "withheld", "chunk")) {
            assertFalse("'$secret' must not appear in ${r.body}", r.body.contains(secret, ignoreCase = true))
        }
        assertEquals(1, served.get())
    }

    @Test fun aSearchWithOnlyPrivateMatchesIsAnEmptyListNotAnError() {
        start(files = FileFakes.source(listOf(salaryC, taxC)))
        filesOn = true
        val r = search("""{"query":"salary"}""")
        assertEquals(r.body, 200, r.status)
        assertEquals(0, r.json.getValue("data").jsonArray.size)
        assertFalse(r.body.contains("withheld"))
    }

    @Test fun scopePhoneDoesNotContactPairedPhonesAndSwarmDoes() {
        val calls = FileFakes.Calls()
        val b = FileFakes.peer("Phone B", FileFakes.peerHit("Course certificate", "Certificate of completion"))
        start(files = FileFakes.source(listOf(billC), calls, peers = listOf(b)))
        filesOn = true
        val phone = search("""{"query":"electricity bill"}""")
        assertEquals(phone.body, 200, phone.status)
        assertEquals(0, calls.peers.get())
        assertEquals(listOf("Phone A" to "ok"), phonesOf(phone))
        assertEquals("phone", phone.json.getValue("scope").jsonPrimitive.content)

        val swarm = search("""{"query":"electricity bill","scope":"swarm","max_results":3}""")
        assertEquals(swarm.body, 200, swarm.status)
        assertEquals(1, calls.peers.get())
        assertEquals(3, calls.queries.single().k)
        assertEquals("electricity bill", calls.queries.single().text)
        assertEquals(setOf("Phone A", "Phone B"), swarm.json.getValue("data").jsonArray.map { it.jsonObject.getValue("phone").jsonPrimitive.content }.toSet())
        assertEquals(listOf("Phone A" to "ok", "Phone B" to "ok"), phonesOf(swarm))
        assertEquals("false", swarm.json.getValue("incomplete").jsonPrimitive.content)
    }

    @Test fun aSwarmSearchReportsAnOfflinePhoneAndStaysUsable() {
        val b = FileFakes.peer("Phone B", FileFakes.peerHit("Course certificate", "Certificate of completion", hash = "b-hash"), withheld = 4)
        start(files = FileFakes.source(listOf(salaryC, billC), peers = listOf(b, FileFakes.offline("Phone C"))))
        filesOn = true
        val r = search("""{"query":"certificate","scope":"swarm"}""")
        assertEquals(r.body, 200, r.status)
        assertEquals(listOf("Phone A" to "ok", "Phone B" to "ok", "Phone C" to "offline"), phonesOf(r))
        assertEquals("true", r.json.getValue("incomplete").jsonPrimitive.content)
        assertEquals(setOf("Electricity bill", "Course certificate"), r.json.getValue("data").jsonArray.map { it.jsonObject.getValue("title").jsonPrimitive.content }.toSet())
        for (secret in listOf("Salary", "b-hash", "withheld", "uriHash")) assertFalse(secret, r.body.contains(secret))
    }

    @Test fun badSearchRequestsAre400AndNeverReachTheIndex() {
        val calls = FileFakes.Calls()
        start(files = FileFakes.source(listOf(billC), calls))
        filesOn = true
        val cases = listOf(
            "{}" to "query", """{"query":""}""" to "query", """{"query":"${"q".repeat(1001)}"}""" to "query",
            """{"query":"x","scope":"world"}""" to "scope", """{"query":"x","max_results":0}""" to "max_results",
            """{"query":"x","max_results":11}""" to "max_results", "nope" to "JSON",
        )
        for ((body, mention) in cases) {
            val r = search(body)
            assertEquals(body.take(60), 400, r.status)
            assertTrue("${r.errorMessage} should name $mention", r.errorMessage.contains(mention))
        }
        assertEquals(0, calls.local.get())
        assertEquals(200, search("""{"query":"${"q".repeat(1000)}","max_results":10}""").status)
        assertEquals(413, search("""{"query":"x","pad":"${"p".repeat(AgentRequests.MAX_BODY_BYTES.toInt())}"}""").status)
    }

    @Test fun aSearchThatCannotRunIs503IndexUnavailable() {
        val calls = FileFakes.Calls()
        start(files = FileFakes.source(listOf(billC), calls, unavailable = "No embedding model is loaded on this phone, so files cannot be searched."))
        filesOn = true
        val r = search()
        assertEquals(r.body, 503, r.status)
        assertEquals("index_unavailable", r.errorCode)
        assertEquals("server_error", r.json.getValue("error").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(0, calls.local.get())
        val entry = audit.single { it.first == "agent.files" }.second
        assertEquals(503, entry.getValue("status").jsonPrimitive.int)
        assertFalse(entry.toString().contains("electricity"))
    }

    @Test fun aFailingIndexIsA500WithoutItsText() {
        val failing = FileFakes.source(listOf(billC), beforeSearch = { throw IllegalStateException("database at /data/user/0/secret is locked") })
        start(files = failing)
        filesOn = true
        val r = search()
        assertEquals(500, r.status)
        assertEquals("internal_error", r.errorCode)
        assertFalse(r.body.contains("secret"))
    }

    @Test fun onlyTwoSearchesRunAtOnce() {
        val calls = FileFakes.Calls()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        start(files = FileFakes.source(listOf(billC), calls, beforeSearch = { release.await() }))
        filesOn = true
        val first = java.util.concurrent.CompletableFuture.supplyAsync { search() }
        val second = java.util.concurrent.CompletableFuture.supplyAsync { search() }
        waitUntil("two searches inside the index") { calls.local.get() == 2 }
        val third = search()
        assertEquals(third.body, 429, third.status)
        assertEquals("rate_limited", third.errorCode)
        release.complete(Unit)
        assertEquals(200, first.get(10, TimeUnit.SECONDS).status)
        assertEquals(200, second.get(10, TimeUnit.SECONDS).status)
        assertEquals("a place is free again", 200, search().status)
    }

    @Test fun theAuditEntryOfASearchCarriesNoQueryTitleOrSnippet() {
        val b = FileFakes.peer("Phone B", FileFakes.peerHit("Course certificate", "Certificate of completion rupees"))
        start(files = FileFakes.source(listOf(billC, salaryC), peers = listOf(b, FileFakes.offline("Phone C"))))
        filesOn = true
        val r = search("""{"query":"electricity bill","scope":"swarm"}""")
        assertEquals(r.body, 200, r.status)
        val entry = audit.single { it.first == "agent.files" }.second
        assertEquals(setOf("scope", "queryChars", "status", "results", "phones", "ms"), entry.keys)
        assertEquals("swarm", entry.getValue("scope").jsonPrimitive.content)
        assertEquals(16, entry.getValue("queryChars").jsonPrimitive.int)
        assertEquals(2, entry.getValue("results").jsonPrimitive.int)
        val phones = entry.getValue("phones").jsonArray.map { it.jsonObject }
        assertEquals(listOf("Phone A", "Phone B", "Phone C"), phones.map { it.getValue("name").jsonPrimitive.content })
        assertEquals(listOf("ok", "ok", "offline"), phones.map { it.getValue("status").jsonPrimitive.content })
        assertEquals(listOf(1, 1, 0), phones.map { it.getValue("results").jsonPrimitive.int })
        val text = entry.toString()
        for (leak in listOf("electricity", "bill", "Electricity", "rupees", "Certificate", "Salary", "snippet", "title", "hash")) {
            assertFalse("'$leak' must not be in the audit entry $text", text.contains(leak))
        }
    }

    // ---- refusals over the wire ------------------------------------------------------------------------------

    @Test fun refusalsAreOpenAiErrorsAndTheModelIsNeverCalled() {
        start()
        val llm = chat as TestLlm
        val cases = listOf(
            chatBody(""""functions":[{"name":"f"}]""") to "functions",
            chatBody(""""function_call":"auto"""") to "function_call",
            chatBody(""""n":2""") to "`n`",
            """{"model":"ustaad-local","messages":[{"role":"user","content":[{"type":"image_url","image_url":{"url":"http://x"}}]}]}""" to "content[0]",
            """{"model":"ustaad-local","messages":[{"role":"user","content":[{"type":"input_audio","input_audio":{"data":"AA","format":"wav"}}]}]}""" to "content[0]",
            """{"model":"ustaad-local","messages":[${List(65) { """{"role":"user","content":"m"}""" }.joinToString(",")}]}""" to "messages",
            "not json" to "JSON",
        )
        for ((body, mention) in cases) {
            val r = post("/v1/chat/completions", body)
            assertEquals(body.take(80), 400, r.status)
            assertTrue("${r.errorMessage} should mention $mention", r.errorMessage.contains(mention))
            assertEquals("invalid_request_error", r.json.getValue("error").jsonObject.getValue("type").jsonPrimitive.content)
        }
        assertEquals(0, llm.executed.size + llm.streamCalls.get())
    }

    @Test fun anUnknownModelIs404() {
        start()
        val r = post("/v1/chat/completions", chatBody(model = "gpt-4o"))
        assertEquals(404, r.status)
        assertEquals("model_not_found", r.errorCode)
    }

    @Test fun aBodyOverOneMiBIs413() {
        start()
        val declared = rawRequest("POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAuthorization: Bearer $key\r\nContent-Length: ${(1 shl 20) + 1}\r\nConnection: close\r\n\r\n")
        assertTrue(declared, declared.startsWith("HTTP/1.1 413"))
        assertTrue(declared.contains("request_too_large"))
    }

    @Test fun aChunkedBodyOverOneMiBIs413TooAndIsNeverBufferedWhole() {
        start()
        val big = "a".repeat((1 shl 20) + 1)
        val req = "POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAuthorization: Bearer $key\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n" +
            Integer.toHexString(big.length) + "\r\n" + big + "\r\n0\r\n\r\n"
        val resp = rawRequest(req)
        assertTrue(resp, resp.startsWith("HTTP/1.1 413"))
    }

    @Test fun aBodyOfExactlyOneMiBIsAccepted() {
        start()
        val prefix = """{"model":"ustaad-local","messages":[{"role":"user","content":""""
        val suffix = """"}]}"""
        val pad = (1 shl 20) - prefix.length - suffix.length
        val body = prefix + "a".repeat(pad) + suffix
        assertEquals(1 shl 20, body.toByteArray().size)
        assertEquals(200, post("/v1/chat/completions", body).status)
    }

    @Test fun noRealModelIs503AndThePlaceholderNeverAnswers() {
        start()
        chatReady = false
        val llm = chat as TestLlm
        val r = post("/v1/chat/completions", chatBody())
        assertEquals(503, r.status)
        assertEquals("No chat model is loaded on this phone", r.errorMessage)
        assertEquals("model_not_loaded", r.errorCode)
        val s = post("/v1/chat/completions", chatBody(""""stream":true"""))
        assertEquals(503, s.status)
        assertEquals(0, llm.executed.size + llm.streamCalls.get())
        swarmReady = false
        val w = post("/v1/chat/completions", chatBody(model = "ustaad-swarm"))
        assertEquals(503, w.status)
        assertEquals("No chat model is loaded on any phone in the swarm", w.errorMessage)
    }

    @Test fun thePlaceholderNeverAnswersEvenIfItIsSwappedInAfterTheReadyCheck() {
        start()
        chat = FakeLlmClient() // the model went away while the request was on its way: the ready flag still said yes
        val plain = post("/v1/chat/completions", chatBody())
        assertEquals(503, plain.status)
        assertEquals("No chat model is loaded on this phone", plain.errorMessage)
        val streamed = post("/v1/chat/completions", chatBody(""""stream":true"""))
        assertEquals(503, streamed.status)
        assertFalse(streamed.body.contains("FAKE"))
        assertEquals("the slot is given back", 0, node.chatExec.running)
        embedderClient = FakeLlmClient()
        val e = post("/v1/embeddings", """{"model":"ustaad-local","input":"x"}""")
        assertEquals(503, e.status)
        assertEquals("No embedding model is loaded on this phone", e.errorMessage)
    }

    @Test fun errorsStayJsonWhateverTheClientAccepts() {
        start()
        val r = post("/v1/chat/completions", chatBody(""""functions":[{"name":"f"}]"""), headers = mapOf("Accept" to "text/event-stream"))
        assertEquals(400, r.status)
        assertEquals("unsupported_parameter", r.errorCode)
    }

    @Test fun unknownRoutesAndMethodsAre404WithAnErrorBody() {
        start()
        val a = call("GET", "/v1/nothing")
        assertEquals(404, a.status)
        assertEquals("unknown_route", a.errorCode)
        assertEquals(404, call("GET", "/v1/chat/completions").status)
        assertEquals(404, call("GET", "/").status)
    }

    // ---- embeddings -----------------------------------------------------------------------------------------

    @Test fun embeddingsAnswerAStringAndAList() {
        start()
        val one = post("/v1/embeddings", """{"model":"ustaad-local","input":"abc"}""")
        assertEquals(one.body, 200, one.status)
        assertEquals("list", one.json.getValue("object").jsonPrimitive.content)
        assertEquals("ustaad-local", one.json.getValue("model").jsonPrimitive.content)
        val d = one.json.getValue("data").jsonArray.single().jsonObject
        assertEquals(0, d.getValue("index").jsonPrimitive.int)
        assertEquals(listOf(3f), (d.getValue("embedding") as JsonArray).map { it.jsonPrimitive.content.toFloat() })

        val many = post("/v1/embeddings", """{"model":"ustaad-local","input":["a","bb","cccc"]}""")
        assertEquals(listOf(1f, 2f, 4f), many.json.getValue("data").jsonArray.map { (it.jsonObject.getValue("embedding") as JsonArray)[0].jsonPrimitive.content.toFloat() })
        assertEquals(listOf(0, 1, 2), many.json.getValue("data").jsonArray.map { it.jsonObject.getValue("index").jsonPrimitive.int })
        assertEquals(2, embedder.embedCalls.get())
    }

    @Test fun embeddingsCanComeBackAsBase64() {
        start()
        val r = post("/v1/embeddings", """{"model":"ustaad-local","input":"abc","encoding_format":"base64"}""")
        val text = r.json.getValue("data").jsonArray.single().jsonObject.getValue("embedding").jsonPrimitive
        assertTrue(text.isString)
        assertEquals(java.util.Base64.getEncoder().encodeToString(byteArrayOf(0, 0, 0x40, 0x40)), text.content) // 3.0f little-endian
    }

    @Test fun embeddingsRefuseWhatChatRefusesAndNeedARealEmbedder() {
        start()
        assertEquals(400, post("/v1/embeddings", """{"model":"ustaad-local","input":[[1,2]]}""").status)
        assertEquals(404, post("/v1/embeddings", """{"model":"nope","input":"x"}""").status)
        embedReady = false
        val r = post("/v1/embeddings", """{"model":"ustaad-local","input":"x"}""")
        assertEquals(503, r.status)
        assertEquals("No embedding model is loaded on this phone", r.errorMessage)
        assertEquals(0, embedder.embedCalls.get())
    }

    // ---- audit and counters ------------------------------------------------------------------------------------

    @Test fun everyRequestIsAuditedWithCountsAndNeverWithText() {
        start()
        post("/v1/chat/completions", chatBody(user = "my secret prompt"))
        post("/v1/chat/completions", chatBody(""""stream":true""", user = "another secret prompt"))
        post("/v1/embeddings", """{"model":"ustaad-local","input":["private text one","private text two"]}""")
        post("/v1/chat/completions", chatBody(""""n":3""", user = "refused secret"))
        val kinds = audit.map { it.first }
        assertEquals("the 400 is counted, not audited", listOf("agent.start", "agent.chat", "agent.chat", "agent.embed"), kinds)
        assertEquals(1, refused.get())

        val plain = audit[1].second
        assertEquals("ustaad-local", plain.getValue("model").jsonPrimitive.content)
        assertEquals(200, plain.getValue("status").jsonPrimitive.int)
        assertEquals("my secret prompt".length, plain.getValue("promptChars").jsonPrimitive.int)
        assertEquals("T:my secret prompt".length, plain.getValue("completionChars").jsonPrimitive.int)
        assertEquals(10, plain.getValue("completionTokens").jsonPrimitive.int)
        assertNotNull(plain["ms"])

        val streamed = audit[2].second
        assertEquals("true", streamed.getValue("stream").jsonPrimitive.content)
        assertEquals("stream:another secret prompt".length, streamed.getValue("completionChars").jsonPrimitive.int)

        val embed = audit[3].second
        assertEquals(2, embed.getValue("inputs").jsonPrimitive.int)
        assertEquals("private text one".length * 2, embed.getValue("chars").jsonPrimitive.int)

        val all = audit.joinToString("\n") { it.second.toString() }
        for (secret in listOf("secret prompt", "private text", "refused secret", "T:", "stream:")) assertFalse("audit must not hold '$secret'", all.contains(secret))
    }

    @Test fun onlyAnsweredRequestsCountAsServed() {
        start()
        call("GET", "/v1/models")
        post("/v1/chat/completions", chatBody())
        post("/v1/chat/completions", chatBody(""""stream":true"""))
        post("/v1/embeddings", """{"model":"ustaad-local","input":"x"}""")
        assertEquals(4, served.get())
        call("GET", "/v1/models", key = "wrong")
        post("/v1/chat/completions", chatBody(""""n":2"""))
        post("/v1/chat/completions", chatBody(model = "nope"))
        call("GET", "/v1/nothing")
        assertEquals(4, served.get())
        assertEquals("every 4xx is counted: key, field, model, route", 4, refused.get())
    }

    @Test fun aServerErrorIsAuditedButARefusalIsNot() {
        start()
        chat = FailingLlm { RuntimeException("secret detail from /data/user/0/com.swarmos.app") }
        val r = post("/v1/chat/completions", chatBody(user = "hello"))
        assertEquals(500, r.status)
        assertEquals("internal_error", r.errorCode)
        assertFalse(r.body.contains("secret detail"))
        assertFalse(r.body.contains("/data/"))
        assertEquals(listOf("agent.start", "agent.chat"), audit.map { it.first })
        assertEquals(500, audit.last().second.getValue("status").jsonPrimitive.int)
        assertEquals(0, refused.get())
        assertEquals(0, served.get())
    }

    @Test fun noServerErrorEverCarriesAnExceptionMessage() {
        start()
        val secret = "secret detail 127.0.0.1:8080 /data/x"
        chat = FailingLlm { RuntimeException(secret) }
        val streamed = post("/v1/chat/completions", chatBody(""""stream":true"""))
        assertEquals(500, streamed.status)
        assertFalse(streamed.body.contains("secret"))
        chat = FailingLlm { java.io.IOException(secret) }
        val lost = post("/v1/chat/completions", chatBody())
        assertEquals(503, lost.status)
        assertEquals("model_unavailable", lost.errorCode)
        assertFalse(lost.body.contains("secret"))
        embedderClient = FailingLlm { IllegalStateException(secret) }
        val e = post("/v1/embeddings", """{"model":"ustaad-local","input":"x"}""")
        assertEquals(500, e.status)
        assertFalse(e.body.contains("secret"))
    }

    @Test fun aSwarmThatCannotRunTheJobIs503() {
        val dead = FakePhone("b", TestLlm("M"), scope, 1, isLocal = false, tokPerSec = 10.0)
        dead.dead = true
        start(phones = listOf(dead))
        val r = post("/v1/chat/completions", chatBody(model = "ustaad-swarm"))
        assertEquals(r.body, 503, r.status)
        assertEquals("swarm_unavailable", r.errorCode)
        assertEquals("server_error", r.json.getValue("error").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(503, audit.last().second.getValue("status").jsonPrimitive.int)
    }

    // ---- host name, limits, stalled clients ----------------------------------------------------------------

    @Test fun aRequestAddressedByAnotherHostNameIsRefusedEvenWithTheRightKey() {
        start()
        fun get(host: String) = rawRequest("GET /v1/models HTTP/1.1\r\nHost: $host\r\nAuthorization: Bearer $key\r\nConnection: close\r\n\r\n")
        for (ok in listOf("127.0.0.1:$port", "localhost:$port", "[::1]:$port")) assertTrue(ok, get(ok).startsWith("HTTP/1.1 200"))
        for (bad in listOf("evil.example:$port", "127.0.0.1", "localhost:80", "127.0.0.1.evil.example:$port")) {
            val r = get(bad)
            assertTrue("$bad: $r", r.startsWith("HTTP/1.1 403"))
            assertTrue(r.contains("not_local"))
        }
        assertEquals(4, refused.get())
        assertEquals(3, served.get())
    }

    @Test fun moreThanFourRequestsReadingAtOnceGetTooManyRequests() {
        start()
        val holders = List(AgentServer.MAX_PARSING) {
            Socket("127.0.0.1", port).also { s ->
                s.getOutputStream().write("POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAuthorization: Bearer $key\r\nContent-Length: 500\r\n\r\n".toByteArray())
                s.getOutputStream().flush()
            }
        }
        try {
            // the holders are inside the handler once a fifth request is turned away
            waitUntil("the fifth request to be refused") { post("/v1/chat/completions", chatBody()).status == 429 }
            val r = post("/v1/chat/completions", chatBody())
            assertEquals(429, r.status)
            assertEquals("rate_limited", r.errorCode)
            assertEquals("rate_limit_error", r.json.getValue("error").jsonObject.getValue("type").jsonPrimitive.content)
            assertEquals("the key is still checked first", 401, post("/v1/chat/completions", chatBody(), key = "wrong").status)
        } finally {
            holders.forEach { it.close() }
        }
        waitUntil("the places to come free") { post("/v1/chat/completions", chatBody()).status == 200 }
    }

    @Test fun moreThanTwoEmbeddingCallsAtOnceGetTooManyRequests() {
        val blocking = BlockingEmbedder()
        start()
        embedderClient = blocking
        val body = """{"model":"ustaad-local","input":"x"}"""
        val running = List(AgentServer.MAX_EMBEDDING) { java.util.concurrent.CompletableFuture.supplyAsync { post("/v1/embeddings", body) } }
        waitUntil("two embedding calls to be running") { blocking.entered.get() == AgentServer.MAX_EMBEDDING }
        val third = post("/v1/embeddings", body)
        assertEquals(third.body, 429, third.status)
        assertEquals("rate_limited", third.errorCode)
        assertEquals("chat is not limited by the embedding places", 200, post("/v1/chat/completions", chatBody()).status)
        blocking.release.complete(Unit)
        assertEquals(listOf(200, 200), running.map { it.get(10, TimeUnit.SECONDS).status })
        assertEquals(200, post("/v1/embeddings", body).status)
    }

    @Test fun aClientThatStopsReadingEndsTheStreamAndFreesTheModel() {
        val flood = FloodLlm()
        start(writeTimeoutMs = 500)
        chat = flood
        val s = Socket()
        s.receiveBufferSize = 1024 // before connect, so the window stays small
        s.connect(InetSocketAddress("127.0.0.1", port))
        val body = chatBody(""""stream":true""")
        s.getOutputStream().write(
            ("POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAuthorization: Bearer $key\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body").toByteArray(),
        )
        s.getOutputStream().flush()
        // the socket is never read from: the server's writes fill the buffers and then block
        waitUntil("the stalled stream to be ended and the slot freed", ms = 20_000) { node.chatExec.running == 0 && flood.cancelled.get() == 1 }
        waitUntil("the audit entry of the unfinished stream") { audit.any { it.first == "agent.chat" && it.second["aborted"] != null } }
        s.close()
        chat = TestLlm("T", delayMs = 10)
        assertEquals("the slot is usable again", 200, post("/v1/chat/completions", chatBody()).status)
    }

    // ---- lifecycle ------------------------------------------------------------------------------------------

    @Test fun stoppingClosesThePortAndStartingOpensItAgain() {
        start()
        assertEquals(200, call("GET", "/v1/models").status)
        server!!.stop()
        try {
            Socket("127.0.0.1", port).close()
            fail("the port is still open")
        } catch (e: java.net.ConnectException) {
            // closed
        }
        assertEquals(listOf("agent.start", "agent.stop"), audit.map { it.first })
        assertNull(server!!.start())
        assertEquals(200, call("GET", "/v1/models").status)
    }

    @Test fun aPortThatIsTakenIsReportedNotThrown() {
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { taken ->
            val s = AgentServer(
                taken.localPort, { key }, AgentBackend(TestLlmGate.gated(scope), { true }, SwarmLlm({ emptyList() }, scope), { true }, { TestLlm() }, { true }, { "A" }, { it }),
                audit = { kind, payload -> audit += kind to payload },
            )
            val why = s.start()
            assertNotNull(why)
            assertTrue(why!!, why.contains("${taken.localPort}"))
            assertEquals("agent.error", audit.last().first)
            s.stop()
        }
    }

    @Test fun anAuditFailureDoesNotBreakTheAnswer() {
        val failing = AgentServer(
            freePort().also { port = it }, { key },
            AgentBackend(TestLlmGate.gated(scope), { true }, SwarmLlm({ emptyList() }, scope), { true }, { TestLlm() }, { true }, { "A" }, { it }),
            audit = { _, _ -> error("disk full") },
        )
        server = failing
        assertNull(failing.start())
        assertEquals(200, post("/v1/chat/completions", chatBody()).status)
    }

    // ---- tools ----------------------------------------------------------------------------------------------

    private val searchTool = """{"type":"function","function":{"name":"search_files","description":"Search the owner's files","parameters":{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}}}"""
    private val callText = """{"tool_calls":[{"name":"search_files","arguments":{"query":"electricity bill"}}]}"""

    private fun toolBody(extra: String = "", model: String = "ustaad-local", messages: String = """[{"role":"user","content":"find my electricity bill"}]""") =
        """{"model":"$model","messages":$messages,"tools":[$searchTool]${if (extra.isEmpty()) "" else ",$extra"}}"""

    private fun firstChoice(r: Resp) = r.json.getValue("choices").jsonArray.single().jsonObject

    @Test fun aToolCallComesBackAsToolCalls() {
        val llm = ScriptedLlm(callText)
        start()
        chat = llm
        val r = post("/v1/chat/completions", toolBody())
        assertEquals(r.body, 200, r.status)
        val choice = firstChoice(r)
        assertEquals("tool_calls", choice.getValue("finish_reason").jsonPrimitive.content)
        val message = choice.getValue("message").jsonObject
        assertEquals(kotlinx.serialization.json.JsonNull, message.getValue("content"))
        val call = message.getValue("tool_calls").jsonArray.single().jsonObject
        assertTrue(call.getValue("id").jsonPrimitive.content.startsWith("call_"))
        assertEquals("function", call.getValue("type").jsonPrimitive.content)
        assertEquals("search_files", call.getValue("function").jsonObject.getValue("name").jsonPrimitive.content)
        val args = Json.parseToJsonElement(call.getValue("function").jsonObject.getValue("arguments").jsonPrimitive.content).jsonObject
        assertEquals("electricity bill", args.getValue("query").jsonPrimitive.content)
        assertEquals(16, r.json.getValue("usage").jsonObject.getValue("total_tokens").jsonPrimitive.int)
        // what the model was given: the tools in a system message and an output held to the two shapes
        val q = llm.requests.single()
        assertEquals("system", q.messages.first().role)
        assertTrue(q.messages.first().content.contains("search_files"))
        assertTrue(q.jsonSchema!!.contains("\"tool_calls\"") && q.jsonSchema!!.contains("\"content\""))
    }

    @Test fun theAuditEntryOfAToolCallCarriesNoToolTextOrArguments() {
        start()
        chat = ScriptedLlm(callText)
        post("/v1/chat/completions", toolBody())
        val entry = audit.single { it.first == "agent.chat" }.second.toString()
        assertFalse(entry, entry.contains("search_files") || entry.contains("electricity"))
    }

    @Test fun anAnswerInWordsComesBackAsContent() {
        start()
        chat = ScriptedLlm("""{"content":"Hello there."}""")
        val r = post("/v1/chat/completions", toolBody())
        val choice = firstChoice(r)
        assertEquals("stop", choice.getValue("finish_reason").jsonPrimitive.content)
        assertEquals("Hello there.", choice.getValue("message").jsonObject.getValue("content").jsonPrimitive.content)
        assertTrue("tool_calls" !in choice.getValue("message").jsonObject)
    }

    @Test fun anOutputThatIsNeitherShapeIsReturnedAsContent() {
        start()
        chat = ScriptedLlm("I would rather chat.")
        val r = post("/v1/chat/completions", toolBody())
        assertEquals(r.body, 200, r.status)
        assertEquals("I would rather chat.", firstChoice(r).getValue("message").jsonObject.getValue("content").jsonPrimitive.content)
    }

    @Test fun toolChoiceNoneSendsNoSchemaAndNoToolText() {
        val llm = ScriptedLlm("plain answer")
        start()
        chat = llm
        val r = post("/v1/chat/completions", toolBody(""""tool_choice":"none""""))
        assertEquals(r.body, 200, r.status)
        assertEquals("plain answer", firstChoice(r).getValue("message").jsonObject.getValue("content").jsonPrimitive.content)
        val q = llm.requests.single()
        assertNull(q.jsonSchema)
        assertEquals(listOf("user"), q.messages.map { it.role })
        assertFalse(q.messages.any { it.content.contains("search_files") })
    }

    @Test fun requiredAndNamedChoicesNarrowTheSchemaTheModelGets() {
        val llm = ScriptedLlm(callText)
        start()
        chat = llm
        assertEquals(200, post("/v1/chat/completions", toolBody(""""tool_choice":"required"""")).status)
        assertEquals(200, post("/v1/chat/completions", toolBody(""""tool_choice":{"type":"function","function":{"name":"search_files"}}""")).status)
        val (required, named) = llm.requests.toList()
        assertFalse(required.jsonSchema!!.contains("\"content\""))
        assertTrue(required.messages.first().content.contains("You must call a tool."))
        assertFalse(named.jsonSchema!!.contains("anyOf"))
        assertTrue(named.messages.first().content.contains("You must call `search_files`."))
    }

    @Test fun anEarlierCallAndItsResultReachTheModelInOrder() {
        val llm = ScriptedLlm("""{"content":"The bill is on 3 September."}""")
        start()
        chat = llm
        val messages = """[{"role":"system","content":"be brief"},{"role":"user","content":"find my electricity bill"},
            {"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":{"name":"search_files","arguments":"{\"query\":\"electricity bill\"}"}}]},
            {"role":"tool","tool_call_id":"call_1","content":"Electricity bill, 2026-09-03: 1,240 rupees"}]"""
        val r = post("/v1/chat/completions", toolBody(messages = messages))
        assertEquals(r.body, 200, r.status)
        val sent = llm.requests.single().messages
        assertEquals(listOf("system", "user", "assistant", "user"), sent.map { it.role })
        assertTrue(sent[0].content.startsWith("be brief\n\n") && sent[0].content.contains("search_files"))
        assertEquals("""{"tool_calls":[{"name":"search_files","arguments":{"query":"electricity bill"}}]}""", sent[2].content)
        assertTrue(sent[3].content.contains("call call_1") && sent[3].content.contains("1,240 rupees"))
        assertEquals("The bill is on 3 September.", firstChoice(r).getValue("message").jsonObject.getValue("content").jsonPrimitive.content)
    }

    @Test fun toolsWorkOnTheSwarmModelToo() {
        start()
        // the stand-in phone answers with text that is neither shape: it comes back as words, from that phone
        val r = post("/v1/chat/completions", toolBody(model = "ustaad-swarm"))
        assertEquals(r.body, 200, r.status)
        assertEquals("ustaad:Phone B", r.json.getValue("system_fingerprint").jsonPrimitive.content)
        assertTrue(firstChoice(r).getValue("message").jsonObject.getValue("content").jsonPrimitive.content.startsWith("M:"))
    }

    @Test fun aStreamWithToolsIsHeldBackAndSentAsToolCallEvents() {
        start()
        chat = ScriptedLlm(callText)
        val r = post("/v1/chat/completions", toolBody(""""stream":true"""), headers = mapOf("Accept" to "text/event-stream"))
        assertEquals(r.body, 200, r.status)
        val ev = events(r.body)
        assertEquals("data: [DONE]", ev.last())
        val chunks = ev.dropLast(1).map { Json.parseToJsonElement(it.removePrefix("data: ")).jsonObject }
        assertEquals("the call in one chunk and the closing chunk, nothing of the half-written JSON", 2, chunks.size)
        val delta = chunks[0].getValue("choices").jsonArray.single().jsonObject.getValue("delta").jsonObject
        assertEquals("assistant", delta.getValue("role").jsonPrimitive.content)
        val call = delta.getValue("tool_calls").jsonArray.single().jsonObject
        assertEquals(0, call.getValue("index").jsonPrimitive.int)
        assertTrue(call.getValue("id").jsonPrimitive.content.startsWith("call_"))
        assertEquals("function", call.getValue("type").jsonPrimitive.content)
        assertEquals("search_files", call.getValue("function").jsonObject.getValue("name").jsonPrimitive.content)
        val args = Json.parseToJsonElement(call.getValue("function").jsonObject.getValue("arguments").jsonPrimitive.content).jsonObject
        assertEquals("electricity bill", args.getValue("query").jsonPrimitive.content)
        val last = chunks[1]
        assertEquals("tool_calls", last.getValue("choices").jsonArray.single().jsonObject.getValue("finish_reason").jsonPrimitive.content)
        assertEquals(16, last.getValue("usage").jsonObject.getValue("total_tokens").jsonPrimitive.int)
        assertEquals("one id for the whole answer", 1, chunks.map { it.getValue("id").jsonPrimitive.content }.toSet().size)
    }

    @Test fun aStreamWithToolsThatAnswersInWordsSendsTheText() {
        start()
        chat = ScriptedLlm("""{"content":"Hello there."}""")
        val r = post("/v1/chat/completions", toolBody(""""stream":true"""), headers = mapOf("Accept" to "text/event-stream"))
        val chunks = events(r.body).dropLast(1).map { Json.parseToJsonElement(it.removePrefix("data: ")).jsonObject }
        val deltas = chunks.map { it.getValue("choices").jsonArray.single().jsonObject.getValue("delta").jsonObject }
        assertEquals("assistant", deltas.first().getValue("role").jsonPrimitive.content)
        assertEquals("Hello there.", deltas.mapNotNull { it["content"]?.jsonPrimitive?.content }.joinToString(""))
        assertEquals("stop", chunks.last().getValue("choices").jsonArray.single().jsonObject.getValue("finish_reason").jsonPrimitive.content)
    }

    @Test fun aStreamWithToolsKeepsPingingWhileTheAnswerIsHeldBack() {
        // pings go out every 100 ms in this test; a slow model must not leave the connection silent
        start()
        chat = object : LlmClient {
            override val modelName = "slow"
            override suspend fun ready() = true
            override fun chatStream(req: ChatRequest): Flow<ChatChunk> = flow {
                emit(ChatChunk("""{"tool_calls":[{"name":"search_files","""))
                kotlinx.coroutines.delay(500)
                emit(ChatChunk(""""arguments":{"query":"x"}}]}"""))
                emit(ChatChunk("", done = true, stats = ChatStats(1, 1, 1.0, 1.0)))
            }
            override suspend fun chat(req: ChatRequest): ChatResult = ChatResult("", ChatStats(0, 0, 0.0, 0.0))
            override suspend fun embed(texts: List<String>): List<FloatArray> = emptyList()
        }
        val r = post("/v1/chat/completions", toolBody(""""stream":true"""))
        assertEquals(r.body, 200, r.status)
        assertTrue("pings while holding back", r.body.contains(": ping"))
        assertTrue(r.body.contains("\"tool_calls\""))
    }

    @Test fun aSchemaTheModelServerRefusesIs400UnsupportedSchema() {
        start()
        val secret = "Unrecognized schema: /home/secret/path"
        chat = ScriptedLlm(error = { LlamaServerException("""{"error":{"code":400,"message":"$secret"}}""", 400) })
        for (extra in listOf("", """"stream":true""")) {
            val r = post("/v1/chat/completions", toolBody(extra))
            assertEquals(extra + r.body, 400, r.status)
            assertEquals("unsupported_schema", r.errorCode)
            assertEquals("invalid_request_error", r.json.getValue("error").jsonObject.getValue("type").jsonPrimitive.content)
            assertTrue(r.errorMessage.contains("schema"))
            assertFalse("the server's own text does not travel", r.body.contains("secret"))
        }
        // a refusal is counted, not audited, and the slot is given back
        assertTrue(audit.none { it.first == "agent.chat" })
        assertEquals(0, node.chatExec.running)
    }

    @Test fun aServerErrorThatIsNotAboutTheSchemaStaysA500() {
        start()
        chat = ScriptedLlm(error = { LlamaServerException("out of memory at /data/secret", 500) })
        val r = post("/v1/chat/completions", toolBody())
        assertEquals(500, r.status)
        assertEquals("internal_error", r.errorCode)
        assertFalse(r.body.contains("secret"))
    }

    @Test fun aSchemaRejectionInAResponseFormatCallIs400Too() {
        start()
        chat = ScriptedLlm(error = { LlamaServerException("JSON schema conversion failed", 500) })
        val r = post("/v1/chat/completions", chatBody(""""response_format":{"type":"json_schema","json_schema":{"name":"n","schema":{"type":"object"}}}"""))
        assertEquals(r.body, 400, r.status)
        assertEquals("unsupported_schema", r.errorCode)
    }

    @Test fun aServerErrorWithoutAnySchemaInTheCallStaysA500() {
        start()
        chat = ScriptedLlm(error = { LlamaServerException("""{"error":{"code":400,"message":"bad"}}""", 400) })
        val r = post("/v1/chat/completions", chatBody())
        assertEquals(500, r.status)
        assertEquals("internal_error", r.errorCode)
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    /** Sends [raw] on a plain socket and returns everything the server answers (status line, headers, body). */
    private fun rawRequest(raw: String): String {
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 1_500
            try {
                s.getOutputStream().write(raw.toByteArray())
                s.getOutputStream().flush()
            } catch (e: java.io.IOException) {
                // the server may answer and close before the whole body is sent; its answer is still readable
            }
            val out = StringBuilder()
            val buf = ByteArray(4096)
            try {
                while (true) {
                    val n = s.getInputStream().read(buf)
                    if (n < 0) break
                    out.append(String(buf, 0, n))
                }
            } catch (e: java.io.IOException) {
                if (out.isEmpty()) throw e
            }
            return out.toString()
        }
    }

    private object TestLlmGate {
        fun gated(scope: CoroutineScope): LlmClient {
            val llm = TestLlm("T", delayMs = 10)
            return GatedLlmClient({ llm }, SchedNode(scope, { "a" }, { llm }, { llm }, { null }))
        }
    }
}
