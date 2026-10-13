package com.swarmos.app.sched

import com.swarmos.app.link.NodeHttpException
import com.swarmos.app.llm.ChatRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/** A batch item that no device could take is tried again after a pause, a limited number of times, and only for the right reasons. */
class BatchRetryTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() = scope.cancel()

    private fun swarm(vararg phones: PhoneEndpoint) =
        SwarmLlm({ phones.toList() }, scope, capsMaxAgeMs = 50, busyBackoffMs = 20, probeEveryMs = 100, msPerToken = 0)

    /** A phone that answers its report and fails every chat call the way [fail] says. */
    private class FailingPhone(override val nodeId: String, val fail: () -> Exception) : PhoneEndpoint {
        val calls = AtomicInteger()
        override suspend fun caps() = Caps(nodeId, "T", 1, 0, 0, 10.0, 0, 80, false, 4096)
        override suspend fun runChat(jobId: String, req: ChatRequest, pinModel: String?): ChatJobResult {
            calls.incrementAndGet()
            throw fail()
        }
        override suspend fun runEmbed(jobId: String, texts: List<String>): EmbedJobResult = throw UnsupportedOperationException()
    }

    private suspend fun until(ms: Long = 5_000, check: () -> Boolean) = withTimeout(ms) { while (!check()) delay(5) }

    @Test fun anItemThatFindsNoDeviceUpIsTriedAgainWhenOneComesUp() = runBlocking {
        val a = FakePhone("a", TestLlm(delayMs = 5), scope).also { it.dead = true }
        val retries = AtomicInteger()
        val run = async { BatchRunner(swarm(a), retryPauseMs = 600, onRetry = { retries.incrementAndGet() }).run("t", "swarm", listOf(req("x"))) }
        until { a.capsCalls.get() >= 1 }      // the first try found nobody
        a.dead = false                        // a device comes up during the pause
        val report = withTimeout(10_000) { run.await() }
        assertEquals(1, report.done)
        assertEquals(0, report.failed)
        assertEquals("T:x", report.items[0].text)
        assertEquals("it was run again once", 1, retries.get())
        assertTrue("after the pause (${report.wallMs} ms)", report.wallMs >= 550)
    }

    @Test fun twoRetriesAreAllAnItemGetsThenItIsAnErrorRow() = runBlocking {
        val p = FailingPhone("p") { PhoneTimeoutException("p", 100) }
        val retries = AtomicInteger()
        val report = BatchRunner(swarm(p), retryPauseMs = 50, onRetry = { retries.incrementAndGet() }).run("t", "swarm", listOf(req("x"), req("y")))
        assertEquals("two items, two more tries each", 4, retries.get())
        assertEquals(2, report.failed)
        assertTrue(report.items.all { it.error != null })
        assertEquals(listOf(0, 1), report.items.map { it.index })
    }

    @Test fun aLostConnectionIsRetriedLikeADeviceThatLeft() = runBlocking {
        val p = FailingPhone("p") { IOException("connection lost") }
        val retries = AtomicInteger()
        BatchRunner(swarm(p), retryPauseMs = 200, onRetry = { retries.incrementAndGet() }).run("t", "swarm", listOf(req("x")))
        assertTrue("run again at least once (got ${retries.get()})", retries.get() in 1..2)
    }

    @Test fun aRefusalByTheModelIsNotRetried() = runBlocking {
        val p = FailingPhone("p") { NodeHttpException(500, "{\"error\":\"the model refused\"}") }
        val retries = AtomicInteger()
        // a long pause: if the item waited for a retry this would not finish
        val report = withTimeout(5_000) { BatchRunner(swarm(p), retryPauseMs = 60_000, onRetry = { retries.incrementAndGet() }).run("t", "swarm", listOf(req("x"))) }
        assertEquals(0, retries.get())
        assertEquals(1, p.calls.get())
        assertEquals(1, report.failed)
        assertTrue(report.items[0].error!!.contains("refused"))
    }

    @Test fun whichFailuresAreWorthTryingAgain() {
        assertTrue(BatchRunner.retryable(NoPhoneAvailableException(null)))
        assertTrue(BatchRunner.retryable(NoPhoneAvailableException(null, tooHot = true)))
        assertTrue(BatchRunner.retryable(SwarmWaitTimeoutException(30_000)))
        assertTrue(BatchRunner.retryable(PhoneTimeoutException("p", 1_000)))
        assertTrue(BatchRunner.retryable(PhoneAbortedException("p")))
        assertTrue(BatchRunner.retryable(IOException("reset")))
        assertFalse(BatchRunner.retryable(NodeHttpException(500, "model failed")))
        assertFalse(BatchRunner.retryable(NodeHttpException(400, "bad request")))
        assertFalse(BatchRunner.retryable(ModelMismatchException("M")))
        assertFalse(BatchRunner.retryable(IllegalArgumentException("bad")))
        assertEquals(5_000L, BatchRunner.RETRY_PAUSE_MS)
        assertEquals(2, BatchRunner.MAX_RETRIES)
    }

    @Test fun stopAllDuringThePauseEndsTheBatchAsBefore() = runBlocking {
        val a = FakePhone("a", TestLlm(delayMs = 5), scope).also { it.dead = true }
        val llm = swarm(a)
        val dir = Files.createTempDirectory("batches").toFile()
        try {
            val store = BatchStore(scope, { llm }, { llm }, dir = { dir }, retryPauseMs = 30_000)
            val id = store.start(listOf(req("x")), BatchStore.MODE_LOCAL)
            until { a.capsCalls.get() >= 1 }
            delay(200)                            // the first try has failed; the item waits for its retry
            assertTrue(store.isRunning())
            val t0 = System.nanoTime()
            store.stopAll()
            assertTrue("stopped at once, not after the pause (${(System.nanoTime() - t0) / 1_000_000} ms)", (System.nanoTime() - t0) / 1_000_000 < 2_500)
            assertEquals("interrupted", store.status(id)!!.value.state)
            assertFalse(store.isRunning())
        } finally {
            dir.deleteRecursively()
        }
    }
}
