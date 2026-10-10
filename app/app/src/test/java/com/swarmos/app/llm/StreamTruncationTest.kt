package com.swarmos.app.llm

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * A stand-in llama-server that sends a canned response and then closes the connection, as a killed server or a dropped
 * connection does. A stream that ends before the server said "finished" must fail the call (with the text so far kept for
 * callers that show partial text); a stream that did finish must still succeed.
 */
class StreamTruncationTest {
    private val servers = ArrayList<ServerSocket>()

    @After fun tearDown() = servers.forEach { runCatching { it.close() } }

    /** Answers every request with [head] + [body] (no Content-Length, Connection: close) and closes. Returns the port. */
    private fun standIn(body: String, head: String = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n"): Int {
        val s = ServerSocket(0, 5, InetAddress.getLoopbackAddress()).also { servers += it }
        thread(isDaemon = true) {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: Exception) { break }
                try {
                    val ins = c.getInputStream()
                    val request = StringBuilder()
                    while (!request.endsWith("\r\n\r\n")) request.append(ins.read().also { if (it < 0) error("closed") }.toChar())
                    val length = Regex("(?i)content-length:\\s*(\\d+)").find(request)?.groupValues?.get(1)?.toInt() ?: 0
                    repeat(length) { ins.read() }
                    c.getOutputStream().apply { write((head + body).toByteArray()); flush() }
                } catch (_: Exception) {
                }
                runCatching { c.close() }
            }
        }
        return s.localPort
    }

    private fun data(json: String) = "data: $json\n\n"
    private fun delta(text: String) = data("""{"choices":[{"finish_reason":null,"index":0,"delta":{"content":"$text"}}]}""")
    private val finish = data("""{"choices":[{"finish_reason":"stop","index":0,"delta":{}}]}""")
    private val usage = data("""{"choices":[],"timings":{"cache_n":0,"prompt_n":20,"prompt_ms":100.0,"predicted_n":6,"predicted_ms":300.0}}""")
    private val done = "data: [DONE]\n\n"

    private val req = ChatRequest(listOf(ChatMessage("user", "hi")))
    private fun client(port: Int) = LlamaServerClient(Role.CHAT, "m", port)

    private fun truncation(block: suspend () -> Unit): ModelStoppedMidAnswerException {
        try {
            runBlocking { withTimeout(10_000) { block() } }
        } catch (e: ModelStoppedMidAnswerException) {
            return e
        }
        fail("expected ModelStoppedMidAnswerException")
        throw AssertionError()
    }

    @Test fun aStreamCutBeforeTheDoneMarkerFailsAndKeepsTheTextSoFar() {
        val port = standIn(delta("Hel") + delta("lo wor"))
        val chunks = ArrayList<String>()
        val e = truncation { client(port).chatStream(req).collect { chunks += it.delta } }
        assertEquals("Hello wor", e.partialText)
        assertEquals(2, e.chunks)
        assertEquals(ModelStoppedMidAnswerException.MESSAGE, e.message)
        assertEquals(listOf("Hel", "lo wor"), chunks) // the streaming caller saw the same text, and never a final chunk
    }

    @Test fun theNonStreamingCallFailsTheSameWayAndCarriesThePartialText() {
        val port = standIn(delta("The answer is 4") )
        val e = truncation { client(port).chat(req) }
        assertEquals("The answer is 4", e.partialText)
    }

    @Test fun aStreamCutBeforeAnyTextFailsWithAnEmptyPartial() {
        val e = truncation { client(standIn("")).chat(req) }
        assertEquals("", e.partialText)
        assertEquals(0, e.chunks)
    }

    @Test fun aStreamThatEndedWithADoneMarkerSucceeds() = runBlocking {
        val port = standIn(delta("Hel") + delta("lo") + finish + usage + done)
        val r = withTimeout(10_000) { client(port).chat(req) }
        assertEquals("Hello", r.text)
        assertEquals(6, r.stats.completionTokens)
    }

    @Test fun aStreamWithAFinishReasonButNoDoneMarkerIsComplete() = runBlocking {
        val port = standIn(delta("Hel") + delta("lo") + finish) // cut after the last text chunk: nothing is missing but the usage numbers
        val chunks = withTimeout(10_000) { client(port).chatStream(req).toList() }
        assertEquals("Hello", chunks.joinToString("") { it.delta })
        assertTrue(chunks.last().done)
    }

    @Test fun anErrorInsideTheStreamStillSurfacesAsAServerError() {
        val port = standIn(delta("Hel") + data("""{"error":{"code":500,"message":"slot busy"}}"""))
        try {
            runBlocking { withTimeout(10_000) { client(port).chat(req) } }
            fail("expected LlamaServerException")
        } catch (e: ModelStoppedMidAnswerException) {
            fail("an error event is not a silent cut-off")
        } catch (e: LlamaServerException) {
            assertEquals("slot busy", e.message)
        }
    }

    @Test fun theParserCallsAStreamCompleteOnlyAfterDoneOrAFinishReason() {
        val p = SseChatParser()
        p.feed("""data: {"choices":[{"finish_reason":null,"index":0,"delta":{"content":"a"}}]}""")
        assertFalse(p.complete)
        p.feed("""data: {"choices":[{"finish_reason":"length","index":0,"delta":{}}]}""")
        assertTrue(p.complete)
        val q = SseChatParser()
        q.feed("data: [DONE]")
        assertTrue(q.complete)
    }

    @Test fun aBodyShorterThanItsContentLengthIsNotAnAnswer() {
        val port = standIn("""{"choices":[{"fini""", head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 400\r\nConnection: close\r\n\r\n")
        val c = LlamaHttp.post("http://127.0.0.1:$port/v1/chat/completions", "{}", 5_000)
        try {
            LlamaHttp.readBody(c)
            fail("a cut-off body must not be returned as complete")
        } catch (_: IOException) {
            // either the socket layer or the length check noticed
        } finally {
            c.disconnect()
        }
    }

    @Test fun aWholeBodyIsReturned() {
        val body = """{"ok":true}"""
        val port = standIn(body, head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n")
        val c = LlamaHttp.post("http://127.0.0.1:$port/v1/chat/completions", "{}", 5_000)
        try {
            assertEquals(body, LlamaHttp.readBody(c))
        } finally {
            c.disconnect()
        }
    }

    @Test fun theLengthCheckOnlyFiresWhenALengthWasDeclared() {
        LlamaHttp.checkNotShort(10, -1)
        LlamaHttp.checkNotShort(10, 10)
        try {
            LlamaHttp.checkNotShort(9, 10)
            fail("9 of 10 bytes is a short body")
        } catch (_: IOException) {
        }
    }
}
