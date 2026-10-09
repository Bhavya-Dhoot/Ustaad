package com.swarmos.app.llm

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A llama-server that accepts the request and never answers. A caller that times out must get control back at once
 * AND the socket must be closed: that is what makes the real server abort the generation and free its slot.
 */
class ClientCancelTest {
    private lateinit var server: ServerSocket
    private val accepted = AtomicInteger(0)
    private var closed = CountDownLatch(1)

    @Before fun setUp() {
        server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: Exception) { break }
                accepted.incrementAndGet()
                thread(isDaemon = true) {
                    try {
                        val ins = s.getInputStream()
                        while (ins.read() != -1) { /* swallow the request, never answer */ }
                    } catch (_: IOException) {
                    }
                    closed.countDown() // EOF or reset: the client dropped the connection
                }
            }
        }
    }

    @After fun tearDown() {
        server.close()
    }

    private val req = ChatRequest(listOf(ChatMessage("user", "hi")))
    private fun chatClient() = LlamaServerClient(Role.CHAT, "m", server.localPort)
    private fun embedClient() = LlamaServerClient(Role.EMBED, "e", server.localPort)

    private fun assertTimesOutPromptlyAndClosesSocket(timeoutMs: Long, call: suspend () -> Unit) {
        val t0 = System.nanoTime()
        try {
            runBlocking { withTimeout(timeoutMs) { call() } }
            fail("expected a timeout")
        } catch (_: TimeoutCancellationException) {
        }
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue("returned after $elapsedMs ms, wanted about $timeoutMs", elapsedMs < timeoutMs + 2_000)
        assertEquals(1, accepted.get())
        assertTrue("the server never saw the connection close", closed.await(3, TimeUnit.SECONDS))
    }

    @Test fun chatStopsAtTheCallersTimeoutAndDropsTheConnection() =
        assertTimesOutPromptlyAndClosesSocket(600) { chatClient().chat(req) }

    @Test fun streamingChatIsCancelledToo() =
        assertTimesOutPromptlyAndClosesSocket(600) { chatClient().chatStream(req).collect { } }

    @Test fun embedStopsAtTheCallersTimeoutAndDropsTheConnection() =
        assertTimesOutPromptlyAndClosesSocket(600) { embedClient().embed(listOf("a", "b")) }

    @Test fun withChatBudgetEnforcesItsDeadline() =
        assertTimesOutPromptlyAndClosesSocket(5_000) { withChatBudget(600) { chatClient().chat(req) } }

    @Test fun socketReadTimeoutFollowsTheRemainingBudgetEvenWithoutCancellation() {
        // A budget element with no withTimeout around it: the socket read timeout alone must end the call.
        val t0 = System.nanoTime()
        try {
            runBlocking { withContext(ChatBudget(System.nanoTime() + 1_200_000_000L)) { chatClient().chat(req) } }
            fail("expected an IOException")
        } catch (e: IOException) {
            // java.net.SocketTimeoutException: Read timed out
        }
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue("took $elapsedMs ms", elapsedMs < 4_000)
    }

    @Test fun remainingBudgetIsNeverNegative() {
        val b = ChatBudget(System.nanoTime() - 5_000_000_000L)
        assertEquals(0L, b.remainingMs())
    }
}
