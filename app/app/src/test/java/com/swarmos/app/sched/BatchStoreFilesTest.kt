package com.swarmos.app.sched

import com.swarmos.app.core.SwarmJson
import com.swarmos.app.llm.ChatRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** This phone, whose chat call ignores cancellation until [release] is completed: a batch on it is slow to wind up. */
private class StuckPhone : PhoneEndpoint {
    val release = CompletableDeferred<Unit>()
    val started = CompletableDeferred<Unit>()
    override val nodeId = "stuck"
    override val isLocal = true
    override suspend fun caps() = Caps("stuck", "T", 1, 0, 0, 10.0, 0, 80, false, 4096)
    override suspend fun runChat(jobId: String, req: ChatRequest, pinModel: String?): ChatJobResult {
        started.complete(Unit)
        withContext(NonCancellable) { release.await() }
        throw CancellationException("released")
    }
    override suspend fun runEmbed(jobId: String, texts: List<String>): EmbedJobResult = throw UnsupportedOperationException()
}

/** The report files of [BatchStore]: written before the id is handed out, one writer at a time, and the end of a stopped batch. */
class BatchStoreFilesTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dirs = mutableListOf<File>()

    @After fun tearDown() {
        scope.cancel()
        dirs.forEach { it.deleteRecursively() }
    }

    private fun tempDir(): File = Files.createTempDirectory("batches").toFile().also { dirs += it }

    private fun phone(id: String, delayMs: Long = 20, capacity: Int = 1) = FakePhone(id, TestLlm(delayMs = delayMs), scope, capacity)

    private fun swarm(p: FakePhone) =
        SwarmLlm({ listOf(p) }, scope, capsMaxAgeMs = 50, busyBackoffMs = 20, probeEveryMs = 100, msPerToken = 0)

    private suspend fun until(ms: Long = 5_000, check: () -> Boolean) = withTimeout(ms) { while (!check()) delay(5) }

    private fun read(f: File) = SwarmJson.decodeFromString<BatchReport>(f.readText())

    // ---- the first report ---------------------------------------------------------------------------------------

    @Test fun theFirstReportIsOnDiskBeforeTheIdIsReturnedSoARestartAnswersInterrupted() {
        val dir = tempDir()
        val llm = swarm(phone("me"))
        val manual = ManualDispatcher()   // the batch coroutine does not get to run a line: only what start() itself did is on disk
        val store = BatchStore(CoroutineScope(SupervisorJob() + manual), { llm }, { llm }, dir = { dir })
        val id = store.start((0 until 6).map { req("p$it") }, BatchStore.MODE_LOCAL)
        val file = File(dir, "batch-$id.json")
        assertTrue("the file exists when start() returns", file.isFile)
        val first = read(file)
        assertEquals("running", first.state)
        assertEquals(6, first.total)
        assertEquals(0, first.done)
        val reborn = BatchStore(scope, { llm }, { llm }, dir = { dir })      // the coordinator was killed in that moment
        val report = reborn.status(id)!!.value
        assertEquals("interrupted", report.state)
        assertEquals(6, report.total)
    }

    // ---- one writer at a time -----------------------------------------------------------------------------------

    @Test fun reportsAreWrittenOneAtATimeAndNoTemporaryFileIsLeft() = runBlocking {
        val real = tempDir()
        val inWrite = AtomicInteger()
        val mostInWrite = AtomicInteger()
        val writes = AtomicInteger()
        // mkdirs() is the first call of every write: it shows how many writes are inside at once
        val dir = object : File(real.path) {
            override fun mkdirs(): Boolean {
                mostInWrite.accumulateAndGet(inWrite.incrementAndGet()) { a, b -> maxOf(a, b) }
                writes.incrementAndGet()
                try {
                    Thread.sleep(15)
                    return super.mkdirs()
                } finally {
                    inWrite.decrementAndGet()
                }
            }
        }
        val now = AtomicLong(1_000)
        val llm = swarm(phone("me", delayMs = 20, capacity = 4))
        // every call of the clock is 2 s later, so no update is held back by the once-a-second throttle
        val store = BatchStore(scope, { llm }, { llm }, dir = { dir }, clock = { now.addAndGet(2_000) })
        val id = store.start((0 until 24).map { req("p$it") }, BatchStore.MODE_LOCAL)
        val file = File(real, "batch-$id.json")
        until { store.status(id)?.value?.state == "done" }
        // no reading of the file while it is being replaced (Windows refuses the replacement then): wait until the writes stop
        var seen = -1
        while (writes.get() != seen) {
            seen = writes.get()
            delay(300)
        }
        assertEquals("one writer at a time", 1, mostInWrite.get())
        assertEquals("done", read(file).state)
        assertEquals("the last report is the full one", 24, read(file).done)
        assertEquals("no temporary file is left", emptyList<String>(), real.list()!!.filter { it.endsWith(".tmp") })
    }

    @Test fun whenTheStatusSaysDoneTheFileSaysDoneEvenWithASlowWriter() = runBlocking {
        val real = tempDir()
        // every write takes 40 ms, much longer than an item: the writer is always busy when the last report arrives
        val dir = object : File(real.path) {
            override fun mkdirs(): Boolean {
                Thread.sleep(40)
                return super.mkdirs()
            }
        }
        val now = AtomicLong(1_000)
        val llm = swarm(phone("me", delayMs = 5))
        val store = BatchStore(scope, { llm }, { llm }, dir = { dir }, clock = { now.addAndGet(2_000) })
        val id = store.start((0 until 12).map { req("p$it") }, BatchStore.MODE_LOCAL)
        until { store.status(id)?.value?.state == "done" }
        // read at once, with no waiting: a restart at this moment must find the final report
        val report = read(File(real, "batch-$id.json"))
        assertEquals("done", report.state)
        assertEquals(12, report.done)
        val reborn = BatchStore(scope, { llm }, { llm }, dir = { real })
        assertEquals("done", reborn.status(id)!!.value.state)
    }

    // ---- which report is the newest ---------------------------------------------------------------------------

    private fun report(done: Int, state: String = "running") = BatchRunner.buildReport(
        "b1", "local", state, 4, (0 until done).map { BatchItem(it, "b1-$it", "me", "T", "answer $it", 5, 9, 100.0) }, 100.0,
    )

    @Test fun anOlderSnapshotArrivingAfterANewerOneWasWrittenDoesNotReplaceIt() {
        val dir = tempDir()
        var now = 10_000L
        val llm = swarm(phone("me"))
        val store = BatchStore(scope, { llm }, { llm }, dir = { dir }, clock = { now })
        val file = File(dir, "batch-b1.json")
        store.persist(report(2), force = false)   // worker B: the newer snapshot is written, nothing is queued any more
        assertEquals(2, read(file).done)
        now += 2_000                               // worker A arrives with the older one it held, after the one-second throttle
        store.persist(report(1), force = false)
        assertEquals("the newer report stays", 2, read(file).done)
        now += 2_000
        store.persist(report(3), force = false)
        assertEquals("a report that is further on still replaces it", 3, read(file).done)
    }

    @Test fun aReportThatSaysStoppedIsNotReplacedByARunningOneWithTheSameItems() {
        val dir = tempDir()
        val llm = swarm(phone("me"))
        val store = BatchStore(scope, { llm }, { llm }, dir = { dir })
        val file = File(dir, "batch-b1.json")
        store.persist(report(1, BatchStore.INTERRUPTED), force = true)
        store.persist(report(1), force = true)     // the first report, late
        assertEquals(BatchStore.INTERRUPTED, read(file).state)
    }

    // ---- stopping ---------------------------------------------------------------------------------------------

    @Test fun stopAllEndsEveryBatchEvenWhenTheFirstIsSlowToWindUp() = runBlocking {
        val stuck = StuckPhone()
        val stuckSwarm = SwarmLlm({ listOf(stuck) }, scope, capsMaxAgeMs = 50, busyBackoffMs = 20, probeEveryMs = 100, msPerToken = 0)
        val p = phone("me", delayMs = 30_000)
        val store = BatchStore(scope, { swarm(p) }, { stuckSwarm }, dir = { tempDir() })
        val first = store.start(listOf(req("a")), BatchStore.MODE_LOCAL)       // on the phone that does not let go
        withTimeout(5_000) { stuck.started.await() }
        val second = store.start(listOf(req("b")), BatchStore.MODE_SWARM)
        until { p.llm.concurrent.get() == 1 }
        store.stopAll()                                                          // the first one is still winding up when the 3 s are over
        assertEquals("the second was told to stop as well", "interrupted", store.status(second)!!.value.state)
        until { p.llm.concurrent.get() == 0 }
        assertEquals("the first is still holding on", "running", store.status(first)!!.value.state)
        stuck.release.complete(Unit)
        until { !store.isRunning() }
        assertEquals("interrupted", store.status(first)!!.value.state)
    }

    @Test fun stopAllEndsARunningBatchSoItSendsNoMoreJobsAndItsReportSaysInterrupted() = runBlocking {
        val dir = tempDir()
        val p = phone("me", delayMs = 30_000)
        val llm = swarm(p)
        val store = BatchStore(scope, { llm }, { llm }, dir = { dir })
        val id = store.start((0 until 6).map { req("p$it") }, BatchStore.MODE_LOCAL)
        until { p.llm.concurrent.get() == 1 }
        assertTrue(store.isRunning())
        store.stopAll()
        until { !store.isRunning() }
        assertEquals("interrupted", store.status(id)!!.value.state)
        until { p.llm.concurrent.get() == 0 }            // the model call in flight was cancelled
        val sent = p.llm.requests.size
        delay(300)
        assertEquals("no job is sent after the stop", sent, p.llm.requests.size)
        assertEquals("interrupted", read(File(dir, "batch-$id.json")).state)   // read once, after the write: Windows refuses to replace a file being read
        assertNotNull("a new batch may start", store.tryStart(listOf(req("again")), BatchStore.MODE_LOCAL))
        store.stopAll()
    }

    @Test fun stopAllWithNothingRunningReturnsAtOnce() {
        val llm = swarm(phone("me"))
        val store = BatchStore(scope, { llm }, { llm })
        val t0 = System.nanoTime()
        store.stopAll()
        assertTrue("took ${(System.nanoTime() - t0) / 1_000_000} ms", (System.nanoTime() - t0) / 1_000_000 < 500)
        assertNull(store.status("none"))
    }

    @Test fun aBatchWhoseScopeEndsBeforeItRanALineIsNotLeftRunning() {
        val dir = tempDir()
        val llm = swarm(phone("me"))
        val manual = ManualDispatcher()
        val ownScope = CoroutineScope(SupervisorJob() + manual)
        val store = BatchStore(ownScope, { llm }, { llm }, dir = { dir })
        val id = store.start((0 until 3).map { req("p$it") }, BatchStore.MODE_LOCAL)
        ownScope.cancel()
        manual.runPending()
        assertEquals("interrupted", store.status(id)!!.value.state)
        assertEquals("interrupted", read(File(dir, "batch-$id.json")).state)
        assertTrue(!store.isRunning())
    }
}
