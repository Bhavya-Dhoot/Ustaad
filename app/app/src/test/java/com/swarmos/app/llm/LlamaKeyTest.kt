package com.swarmos.app.llm

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The llama-servers listen on loopback, open to every app on the phone. Each is started with `--api-key`, and every call
 * the app makes to one carries the same key.
 */
class LlamaKeyTest {
    private val key = "test-llama-key-0123456789abcdef0123456789"

    // ---- the key --------------------------------------------------------------------------------------------

    @Test fun theProcessKeyIsRandomAndStable() {
        assertEquals(43, LlamaKey.value.length)
        assertEquals(LlamaKey.value, LlamaKey.value)
        assertNotEquals(LlamaKey.generate(), LlamaKey.generate())
        assertEquals("Bearer $key", LlamaKey.bearer(key))
        assertEquals("Bearer ${LlamaKey.value}", LlamaKey.bearer())
    }

    // ---- the argument builder -------------------------------------------------------------------------------

    private fun keyOf(args: List<String>): List<String> =
        args.withIndex().filter { it.value == "--api-key" }.map { args[it.index + 1] }

    @Test fun theChatServerGetsTheKeyOnce() {
        val cpu = LlamaArgs.build(Role.CHAT, "/m/q.gguf", 8080, apiKey = key)
        val gpu = LlamaArgs.build(Role.CHAT, "/m/q.gguf", 8080, backend = Backend.GPU, apiKey = key)
        assertEquals(listOf(key), keyOf(cpu))
        assertEquals(listOf(key), keyOf(gpu))
    }

    @Test fun theEmbeddingServerGetsTheKeyOnce() {
        assertEquals(listOf(key), keyOf(LlamaArgs.build(Role.EMBED, "/m/e.gguf", 8081, apiKey = key)))
    }

    @Test fun theSplitDriverServerKeepsTheKeyAlongsideItsRpcFlags() {
        // MemoryCloud starts the chat server again with --rpc and its own flags; it goes through the same builder
        val split = LlamaArgs.build(Role.CHAT, "/m/big.gguf", 8080, listOf("--rpc", "10.0.0.2:50052", "-ngl", "20", "-np", "1"), apiKey = key)
        assertEquals(listOf(key), keyOf(split))
        assertTrue(split.indexOf("--api-key") < split.indexOf("--rpc"))
        assertEquals("10.0.0.2:50052", split[split.indexOf("--rpc") + 1])
    }

    @Test fun rpcServerTakesNoKey() {
        assertEquals(listOf("-H", "127.0.0.1", "-p", "50052"), LlamaArgs.build(Role.RPC, null, 50052, apiKey = key))
    }

    @Test fun byDefaultTheProcessKeyIsUsed() {
        assertEquals(listOf(LlamaKey.value), keyOf(LlamaArgs.build(Role.CHAT, "/m/q.gguf", 8080)))
        assertEquals(listOf(LlamaKey.value), keyOf(LlamaArgs.build(Role.EMBED, "/m/e.gguf", 8081)))
    }

    @Test fun theKeyIsHiddenInWhatTheStatusShows() {
        val shown = LlamaArgs.redacted(LlamaArgs.build(Role.CHAT, "/m/q.gguf", 8080, apiKey = key))
        assertFalse(shown.joinToString(" ").contains(key))
        assertEquals("***", shown[shown.indexOf("--api-key") + 1])
        assertEquals(LlamaArgs.build(Role.CHAT, "/m/q.gguf", 8080, apiKey = key).size, shown.size)
        assertEquals(6, shown[shown.indexOf("-t") + 1].toInt()) // the rest is untouched
        assertEquals(listOf("-H", "127.0.0.1"), LlamaArgs.redacted(listOf("-H", "127.0.0.1")))
    }

    // ---- the header on the client ---------------------------------------------------------------------------

    @Test fun onlyLoopbackServerUrlsGetTheKey() {
        assertEquals("Bearer $key", LlamaHttp.authHeaderFor("http://127.0.0.1:8080/v1/chat/completions", key))
        assertEquals("Bearer $key", LlamaHttp.authHeaderFor("http://127.0.0.1:8081/health", key))
        assertEquals("Bearer ${LlamaKey.value}", LlamaHttp.authHeaderFor("http://127.0.0.1:8080/x"))
        assertNull(LlamaHttp.authHeaderFor("http://192.168.1.5:8080/v1/chat/completions", key))
        assertNull(LlamaHttp.authHeaderFor("https://example.com/", key))
        assertNull(LlamaHttp.authHeaderFor("http://127.0.0.1.example.com:8080/", key))
        assertNull(LlamaHttp.authHeaderFor("http://localhost:8080/", key))
    }

    /** A llama-server stand-in that answers 401 to anything that is not its key, as `--api-key` does (except /health). */
    private class FakeLlama(val key: String) {
        val seen = CopyOnWriteArrayList<Pair<String, String?>>()
        val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0).apply {
            createContext("/") { ex ->
                val auth = ex.requestHeaders.getFirst("Authorization")
                seen += ex.requestURI.path to auth
                val public = ex.requestURI.path == "/health"
                val (code, body) = when {
                    !public && auth != "Bearer $key" -> 401 to """{"error":{"message":"Invalid API Key","type":"authentication_error","code":401}}"""
                    ex.requestURI.path == "/v1/embeddings" -> 200 to """{"data":[{"index":0,"embedding":[1.0,0.0]}]}"""
                    ex.requestURI.path == "/health" -> 200 to """{"status":"ok"}"""
                    else -> 200 to "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                }
                val bytes = body.toByteArray()
                ex.sendResponseHeaders(code, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            start()
        }
        val port: Int get() = server.address.port
    }

    private var fake: FakeLlama? = null

    @Before fun noServer() { fake = null }

    @After fun stopServer() { fake?.server?.stop(0) }

    private fun llama(): FakeLlama = FakeLlama(LlamaKey.value).also { fake = it }

    @Test fun chatAndEmbeddingsCallsPassTheServersKeyCheck() = runBlocking {
        val s = llama()
        // the client records speed in AppGraph / LlamaRuntime after the answer, which needs an Android context: only the
        // request matters here, and it is on the wire before that
        runCatching { LlamaServerClient(Role.CHAT, "m", s.port).chat(ChatRequest(listOf(ChatMessage("user", "hi")))) }
        runCatching { LlamaServerClient(Role.EMBED, "e", s.port).embed(listOf("a")) }
        assertEquals(listOf("/v1/chat/completions", "/v1/embeddings"), s.seen.map { it.first }.sorted())
        assertTrue(s.seen.all { it.second == "Bearer ${LlamaKey.value}" })
    }

    @Test fun aChatCallWithTheWrongKeyFailsWith401() = runBlocking {
        val s = FakeLlama("another-key").also { fake = it }
        try {
            LlamaServerClient(Role.CHAT, "m", s.port).chat(ChatRequest(listOf(ChatMessage("user", "hi"))))
            org.junit.Assert.fail("the server should have refused the call")
        } catch (e: LlamaServerException) {
            assertEquals(401, e.status)
        }
    }

    @Test fun theReadinessPollGetsAnAnswerAndSendsTheKeyToo() {
        val s = llama()
        assertTrue(LlamaHttp.healthOk(s.port))
        assertEquals("/health" to "Bearer ${LlamaKey.value}", s.seen.single())
    }

    @Test fun theWarmUpAndSelftestHelpersAreAuthorizedToo() {
        val s = llama()
        val c = LlamaHttp.post("http://127.0.0.1:${s.port}/v1/chat/completions", "{}", 5_000)
        assertEquals(200, c.responseCode)
        c.disconnect()
    }

    @Test fun aCallWithoutTheKeyIsRefusedByTheServer() {
        val s = llama()
        val c = LlamaHttp.newPost("http://127.0.0.1:${s.port}/v1/chat/completions", 5_000, key = "not-the-key")
        LlamaHttp.send(c, "{}")
        assertEquals(401, c.responseCode)
        c.disconnect()
    }
}
