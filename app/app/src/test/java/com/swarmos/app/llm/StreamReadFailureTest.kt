package com.swarmos.app.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * A stand-in llama-server that dies while its answer is being read: the socket is closed abruptly, or reset, after the
 * headers and part of a chunked body. The read then fails with an I/O error, not a clean end of file; callers must still get
 * [ModelStoppedMidAnswerException] with the text so far, not the bare name of the I/O exception. A failure to connect, an HTTP
 * error status and the caller's own cancellation keep their own meaning.
 */
class StreamReadFailureTest {
    private val servers = ArrayList<ServerSocket>()

    @After fun tearDown() = servers.forEach { runCatching { it.close() } }

    private val streamHead = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"

    /** Same stream without a length or chunking: it ends when the server closes. */
    private val plainHead = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n"

    /** One chunk of a chunked body; the terminating `0` chunk is never sent, so a close after it ends the body early. */
    private fun chunk(text: String) = Integer.toHexString(text.toByteArray().size) + "\r\n" + text + "\r\n"

    private fun delta(text: String) = "data: {\"choices\":[{\"finish_reason\":null,\"index\":0,\"delta\":{\"content\":\"$text\"}}]}\n\n"

    /** Reads one request, runs [respond] on the connection, then closes it. [reset] closes with a TCP reset instead of a FIN. Returns the port. */
    private fun standIn(reset: Boolean = false, respond: (Socket) -> Unit): Int {
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
                    respond(c)
                    if (reset) c.setSoLinger(true, 0)
                } catch (_: Exception) {
                }
                runCatching { c.close() }
            }
        }
        return s.localPort
    }

    private fun send(c: Socket, text: String) = c.getOutputStream().apply { write(text.toByteArray()); flush() }

    private val req = ChatRequest(listOf(ChatMessage("user", "hi")))
    private fun client(port: Int) = LlamaServerClient(Role.CHAT, "m", port)

    private fun stopped(block: suspend () -> Unit): ModelStoppedMidAnswerException {
        try {
            runBlocking { withTimeout(10_000) { block() } }
        } catch (e: ModelStoppedMidAnswerException) {
            return e
        }
        fail("expected ModelStoppedMidAnswerException")
        throw AssertionError()
    }

    @Test fun aBodyCutShortAfterPartOfTheAnswerKeepsTheTextSoFar() {
        val port = standIn { send(it, streamHead + chunk(delta("Hel") + delta("lo wor"))) }
        val seen = ArrayList<String>()
        val e = stopped { client(port).chatStream(req).collect { seen += it.delta } }
        assertEquals("Hello wor", e.partialText)
        assertEquals(2, e.chunks)
        assertEquals(ModelStoppedMidAnswerException.MESSAGE, e.message)
        assertEquals(listOf("Hel", "lo wor"), seen)
        assertTrue("the I/O failure is kept as the cause", e.cause is IOException)
    }

    @Test fun theNonStreamingCallFailsTheSameWay() {
        val port = standIn { send(it, streamHead + chunk(delta("The answer is 4"))) }
        assertEquals("The answer is 4", stopped { client(port).chat(req) }.partialText)
    }

    @Test fun aConnectionResetAfterTheHeadersFailsWithAnEmptyPartial() {
        val port = standIn(reset = true) { send(it, streamHead) }
        val e = stopped { client(port).chat(req) }
        assertEquals("", e.partialText)
        assertEquals(0, e.chunks)
        assertEquals(ModelStoppedMidAnswerException.MESSAGE, e.message)
    }

    @Test fun aServerThatDiesBeforeAnyHeaderFailsTheSameWay() {
        val port = standIn { /* read the request, answer nothing, close */ }
        assertEquals("", stopped { client(port).chat(req) }.partialText)
    }

    @Test fun nothingListeningKeepsItsOwnFailure() {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort } // a port that was free a moment ago
        try {
            runBlocking { withTimeout(10_000) { client(port).chat(req) } }
            fail("expected a connection failure")
        } catch (e: ModelStoppedMidAnswerException) {
            fail("a server that never answered did not stop mid-answer: $e")
        } catch (e: IOException) {
            assertTrue("expected a ConnectException, got $e", e is ConnectException)
        }
    }

    @Test fun anHttpErrorStatusStillSurfacesWithItsStatus() {
        val body = """{"error":{"message":"loading model"}}"""
        val port = standIn { send(it, "HTTP/1.1 503 Service Unavailable\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body") }
        try {
            runBlocking { withTimeout(10_000) { client(port).chat(req) } }
            fail("expected LlamaServerException")
        } catch (e: ModelStoppedMidAnswerException) {
            fail("an HTTP error status is not a silent cut-off")
        } catch (e: LlamaServerException) {
            assertEquals(503, e.status)
        }
    }

    @Test fun aReadTimeoutOfASilentServerIsNotRelabelled() {
        val port = standIn { stayOpen(it) } // accepts, reads the request, never answers within the budget
        try {
            runBlocking { withContext(ChatBudget(System.nanoTime() + 1_200_000_000L)) { client(port).chat(req) } }
            fail("expected a read timeout")
        } catch (e: ModelStoppedMidAnswerException) {
            fail("a silent server is not a dead one: $e")
        } catch (e: SocketTimeoutException) {
            // a server that is alive but slow keeps its read timeout
        }
    }

    @Test fun theCallersCancellationStaysACancellation() {
        val port = standIn { send(it, plainHead + delta("Hel")); stayOpen(it) } // one piece of the answer, then silence
        val seen = ArrayList<String>()
        try {
            runBlocking { withTimeout(1_500) { client(port).chatStream(req).collect { seen += it.delta } } }
            fail("expected a timeout")
        } catch (e: ModelStoppedMidAnswerException) {
            fail("the caller's own timeout must not look like a dead model: $e")
        } catch (e: CancellationException) {
            assertEquals(listOf("Hel"), seen)
        }
    }

    /** Holds the connection open until the client drops it (or 5 s pass). */
    private fun stayOpen(c: Socket) {
        c.soTimeout = 5_000
        runCatching { c.getInputStream().read() }
    }
}
