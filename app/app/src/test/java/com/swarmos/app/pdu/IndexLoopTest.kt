package com.swarmos.app.pdu

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.util.Collections

/** Finding 4: a file that hangs is marked failed and the run goes on; the run can be stopped; the screen state is refreshed every 25 files. */
class IndexLoopTest {
    private fun refs(n: Int) = (0 until n).map { DocRef("u$it", "t$it", "text", "F", 1, 1) }
    private fun idx(r: DocRef) = r.uri.drop(1).toInt()

    private class Recorder {
        val commits = Collections.synchronizedList(ArrayList<Int>())
        val failures = Collections.synchronizedList(ArrayList<Pair<Int, Throwable>>())
        @Volatile var refreshes = 0
    }

    private suspend fun run(
        n: Int, rec: Recorder, control: IndexControl = IndexControl(), timeoutMs: Long = 300,
        prepare: suspend (DocRef) -> Int = { idx(it) },
        commit: suspend (DocRef, Int) -> Unit = { r, _ -> rec.commits += idx(r) },
        onProgress: (Int, DocRef) -> Unit = { _, _ -> },
        commitBudget: (Int) -> Long = { timeoutMs },
    ) = IndexLoop.run(
        refs(n), control, timeoutMs, prepare, commit, onProgress,
        onFailure = { r, t -> rec.failures += idx(r) to t }, refresh = { rec.refreshes++ }, commitBudgetMs = commitBudget,
    )

    @Test fun aFileThatNeverFinishesIsMarkedFailedAndTheRunGoesOn() = runBlocking {
        val rec = Recorder()
        val res = run(5, rec, prepare = { if (idx(it) == 2) awaitCancellation() else idx(it) })
        assertEquals(LoopEnd.COMPLETE, res.end)
        assertEquals(5, res.done)
        assertEquals(listOf(0, 1, 3, 4), rec.commits.toList())
        assertEquals(listOf(2), rec.failures.map { it.first })
        assertTrue(rec.failures[0].second is FileTimedOut)
    }

    @Test fun aReadThatBlocksAThreadAndIgnoresCancellationCannotHoldTheRun() = runBlocking {
        val rec = Recorder()
        val t0 = System.currentTimeMillis()
        val res = run(3, rec, timeoutMs = 300, prepare = {
            if (idx(it) == 1) withContext(Dispatchers.IO + NonCancellable) { Thread.sleep(4_000); 1 } else idx(it)
        })
        assertTrue("took ${System.currentTimeMillis() - t0} ms, the read lasts 4000 ms", System.currentTimeMillis() - t0 < 2_000)
        assertEquals(LoopEnd.COMPLETE, res.end)
        assertEquals(listOf(0, 2), rec.commits.toList())
        assertTrue(rec.failures.single().second is FileTimedOut)
    }

    @Test fun aFileThatCannotBeReadIsReportedWithItsErrorAndTheOthersGoOn() = runBlocking {
        val rec = Recorder()
        val res = run(3, rec, prepare = { if (idx(it) == 1) throw FileNotFoundException("gone") else idx(it) })
        assertEquals(LoopEnd.COMPLETE, res.end)
        assertEquals(listOf(0, 2), rec.commits.toList())
        assertTrue(PduIndexer.isGone(rec.failures.single().second))
    }

    @Test fun anErrorWhileStoringOneFileDoesNotEndTheRun() = runBlocking {
        val rec = Recorder()
        val res = run(4, rec, commit = { r, _ -> if (idx(r) == 1) throw IllegalStateException("disk") else rec.commits += idx(r) })
        assertEquals(LoopEnd.COMPLETE, res.end)
        assertEquals(listOf(0, 2, 3), rec.commits.toList())
        assertEquals(listOf(1), rec.failures.map { it.first })
    }

    @Test fun filesAreCommittedInTheOrderOfTheListingWhateverOrderTheyAreReadIn() = runBlocking {
        val rec = Recorder()
        val res = run(8, rec, timeoutMs = 5_000, prepare = { delay((8 - idx(it)) * 15L); idx(it) })
        assertEquals(LoopEnd.COMPLETE, res.end)
        assertEquals((0 until 8).toList(), rec.commits.toList())
    }

    @Test fun theNextFileIsReadWhileTheCurrentOneIsStoredAndNeverFurtherAhead() = runBlocking {
        val rec = Recorder()
        val prepStart = Collections.synchronizedMap(HashMap<Int, Long>())
        val commitEnd = Collections.synchronizedMap(HashMap<Int, Long>())
        val finishedWhenStarted = Collections.synchronizedMap(HashMap<Int, Int>())
        val res = run(6, rec, timeoutMs = 5_000,
            prepare = { prepStart[idx(it)] = System.nanoTime(); finishedWhenStarted[idx(it)] = rec.commits.size; idx(it) },
            commit = { r, _ -> delay(80); rec.commits += idx(r); commitEnd[idx(r)] = System.nanoTime() })
        assertEquals(LoopEnd.COMPLETE, res.end)
        for (i in 1 until 6) assertTrue("file $i was read before file ${i - 1} was stored", prepStart[i]!! < commitEnd[i - 1]!!)
        // file i is only read once file i-2 is stored: at most one file ahead
        for (i in 0 until 6) assertTrue("file $i started with ${finishedWhenStarted[i]} stored", i - finishedWhenStarted[i]!! <= 1)
    }

    @Test fun stopEndsTheRunAndCancelsTheFileInHand() = runBlocking {
        val rec = Recorder()
        val control = IndexControl()
        launch(Dispatchers.Default) { delay(200); control.stop() }
        val t0 = System.currentTimeMillis()
        val res = run(6, rec, control, timeoutMs = 10_000, commit = { r, _ -> if (idx(r) == 2) awaitCancellation() else rec.commits += idx(r) })
        assertEquals(LoopEnd.STOPPED, res.end)
        assertEquals(2, res.done)
        assertEquals(listOf(0, 1), rec.commits.toList())
        assertTrue("took ${System.currentTimeMillis() - t0} ms", System.currentTimeMillis() - t0 < 3_000)
        assertTrue(rec.failures.isEmpty())
    }

    @Test fun stopWhileAFileIsBeingReadEndsTheRunToo() = runBlocking {
        val rec = Recorder()
        val control = IndexControl()
        launch(Dispatchers.Default) { delay(150); control.stop() }
        val res = run(4, rec, control, timeoutMs = 10_000, prepare = { if (idx(it) == 1) awaitCancellation() else idx(it) })
        assertEquals(LoopEnd.STOPPED, res.end)
        assertEquals(1, res.done)
        assertEquals(listOf(0), rec.commits.toList())
    }

    @Test fun stopBetweenFilesEndsAfterTheOneInHand() = runBlocking {
        val rec = Recorder()
        val control = IndexControl()
        // stop is pressed while file 2 is announced but nothing of it is running yet: that file is finished, the next is not started
        val res = run(6, rec, control, onProgress = { i, _ -> if (i == 2) control.stop() })
        assertEquals(LoopEnd.STOPPED, res.end)
        assertEquals(3, res.done)
        assertEquals(listOf(0, 1, 2), rec.commits.toList())
    }

    @Test fun theScreenStateIsRefreshedEvery25FilesNotAfterEachOne() = runBlocking {
        val rec = Recorder()
        run(60, rec, timeoutMs = 5_000)
        assertEquals(2, rec.refreshes)
        val few = Recorder()
        run(24, few, timeoutMs = 5_000)
        assertEquals(0, few.refreshes)
        assertEquals(25, IndexLoop.REFRESH_EVERY)
    }

    @Test fun anEmbedderThatWentAwayEndsTheRunAtThatFile() = runBlocking {
        val rec = Recorder()
        val res = run(6, rec, commit = { r, _ -> if (idx(r) == 3) throw EmbedderStopped() else rec.commits += idx(r) })
        assertEquals(LoopEnd.EMBEDDER_STOPPED, res.end)
        assertEquals(3, res.done)
        assertEquals(listOf(0, 1, 2), rec.commits.toList())
        assertTrue(rec.failures.isEmpty())
    }

    @Test fun aFullIndexEndsTheRunAtThatFile() = runBlocking {
        val rec = Recorder()
        val res = run(6, rec, commit = { r, _ -> if (idx(r) == 2) throw IndexFull() else rec.commits += idx(r) })
        assertEquals(LoopEnd.FULL, res.end)
        assertEquals(2, res.done)
    }

    @Test fun twoStoresInARowThatTimeOutMeanTheEmbedderIsStuck() = runBlocking {
        val rec = Recorder()
        val res = run(6, rec, timeoutMs = 5_000, commitBudget = { 150 }, commit = { r, _ -> if (idx(r) >= 1) awaitCancellation() else rec.commits += idx(r) })
        assertEquals(LoopEnd.EMBEDDER_STOPPED, res.end)
        assertEquals(2, res.done)
        assertEquals(listOf(1, 2), rec.failures.map { it.first })
    }

    @Test fun oneSlowStoreIsAFailedFileNotAStoppedEmbedder() = runBlocking {
        val rec = Recorder()
        val res = run(4, rec, timeoutMs = 5_000, commitBudget = { 150 }, commit = { r, _ -> if (idx(r) == 1) awaitCancellation() else rec.commits += idx(r) })
        assertEquals(LoopEnd.COMPLETE, res.end)
        assertEquals(listOf(0, 2, 3), rec.commits.toList())
        assertEquals(listOf(1), rec.failures.map { it.first })
    }

    @Test fun theFileBudgetIs90Seconds() {
        assertEquals(90_000L, IndexLoop.FILE_TIMEOUT_MS)
    }
}
