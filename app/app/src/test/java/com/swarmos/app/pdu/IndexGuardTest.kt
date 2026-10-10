package com.swarmos.app.pdu

import com.swarmos.app.llm.ChatChunk
import com.swarmos.app.llm.ChatRequest
import com.swarmos.app.llm.ChatResult
import com.swarmos.app.llm.FakeLlmClient
import com.swarmos.app.llm.LlmClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** An in-memory store that counts what the guard does to it. */
private class MemoryBacking(var chunks: Int = 0, var pending: Int = 0) : IndexBacking {
    val meta = HashMap<String, String>()
    var wipes = 0
    override val chunkCount: Int get() = chunks
    override val pendingCount: Int get() = pending
    override fun meta(key: String) = meta[key]
    override fun setMeta(key: String, value: String) { meta[key] = value }
    override fun wipe() { wipes++; if (chunks > 0) pending = 5; chunks = 0 } // the real store keeps the document list, marked "read again"
}

/** A real-looking embedder whose server can be switched off. */
private class StubEmbedder(override val modelName: String = "embeddinggemma-300M-Q8_0") : LlmClient {
    @Volatile var up = true
    var calls = 0
    override suspend fun ready() = up
    override fun chatStream(req: ChatRequest): Flow<ChatChunk> = emptyFlow()
    override suspend fun chat(req: ChatRequest): ChatResult = error("not used")
    override suspend fun embed(texts: List<String>): List<FloatArray> { calls++; return texts.map { FloatArray(256) { 0.0625f } } }
}

class IndexGuardTest {
    private val real = "embeddinggemma-300M-Q8_0"

    @After fun reset() {
        PduEmbedConfig.queryPrefix = ""
        PduEmbedConfig.documentPrefix = ""
    }

    private fun realIndex() = MemoryBacking(chunks = 40).apply {
        meta["embedModel"] = real
        meta["embedKey"] = PduEmbedConfig.indexKey(real, "")
    }

    @Test fun aFakeEmbedderNeverTouchesAnExistingRealIndex() {
        val b = realIndex()
        for (force in listOf(false, true)) {
            val out = IndexGuard.prepare(b, IndexGuard.FAKE, embedReady = true, documentPrefix = "", ownerForce = force)
            assertEquals("force=$force", IndexGuard.Outcome.Refused(IndexGuard.NOT_LOADED, reindexNeeded = true), out)
        }
        assertEquals(0, b.wipes)
        assertEquals(40, b.chunks)
        assertEquals(PduEmbedConfig.indexKey(real, ""), b.meta["embedKey"]) // the key is never changed to a fake one
        assertEquals(real, b.meta["embedModel"])
    }

    @Test fun anEmbedderThatIsNotReadyNeverTouchesTheIndex() {
        val b = realIndex()
        val out = IndexGuard.prepare(b, real, embedReady = false, documentPrefix = "", ownerForce = true)
        assertTrue(out is IndexGuard.Outcome.Refused)
        assertEquals(IndexGuard.NOT_LOADED, (out as IndexGuard.Outcome.Refused).reason)
        assertEquals(0, b.wipes)
        assertEquals(40, b.chunks)
    }

    @Test fun aFakeEmbedderDoesNotInitialiseAnEmptyStoreEither() {
        val b = MemoryBacking()
        IndexGuard.prepare(b, IndexGuard.FAKE, embedReady = true, documentPrefix = "", ownerForce = false)
        assertTrue(b.meta.isEmpty())
    }

    @Test fun aChangedEmbedderIsReportedNotWiped() {
        val b = realIndex()
        val out = IndexGuard.prepare(b, "qwen3-embedding-0.6B", embedReady = true, documentPrefix = "", ownerForce = false)
        assertEquals(IndexGuard.Outcome.Refused(IndexGuard.REINDEX_NEEDED, reindexNeeded = true), out)
        assertEquals(0, b.wipes)
        assertEquals(40, b.chunks)
        assertEquals(PduEmbedConfig.indexKey(real, ""), b.meta["embedKey"])
    }

    @Test fun aChangedDocumentPrefixIsReportedNotWiped() {
        val b = realIndex()
        val out = IndexGuard.prepare(b, real, embedReady = true, documentPrefix = "title: none | text: ", ownerForce = false)
        assertTrue(out is IndexGuard.Outcome.Refused && out.reindexNeeded)
        assertEquals(0, b.wipes)
        assertTrue(IndexGuard.reindexNeeded(b, real, "title: none | text: "))
        assertFalse(IndexGuard.reindexNeeded(b, real, ""))
    }

    @Test fun anOldIndexWithoutAKeyWaitsForTheOwner() {
        val b = MemoryBacking(chunks = 12) // made before keys existed, so possibly by the fake embedder
        val out = IndexGuard.prepare(b, real, embedReady = true, documentPrefix = "", ownerForce = false)
        assertTrue(out is IndexGuard.Outcome.Refused)
        assertEquals(0, b.wipes)
    }

    @Test fun anOwnerReindexChangesNothingUntilTheNewDataExists() {
        val b = realIndex()
        val out = IndexGuard.prepare(b, "qwen3-embedding-0.6B", embedReady = true, documentPrefix = "", ownerForce = true)
        assertEquals(IndexGuard.Outcome.ProceedReplacing, out)
        assertEquals(0, b.wipes)
        assertEquals(40, b.chunks)
        assertEquals(PduEmbedConfig.indexKey(real, ""), b.meta["embedKey"]) // still the old key: old vectors still match it
    }

    @Test fun committingTheReplacementWipesAndAdoptsTheNewKey() {
        val b = realIndex()
        IndexGuard.commitReplace(b, "qwen3-embedding-0.6B", "")
        assertEquals(1, b.wipes)
        assertEquals(0, b.chunks)
        assertEquals(PduEmbedConfig.indexKey("qwen3-embedding-0.6B", ""), b.meta["embedKey"])
        assertEquals("qwen3-embedding-0.6B", b.meta["embedModel"])
    }

    @Test fun aCutShortReindexResumesWithoutWipingAgain() {
        val b = MemoryBacking(chunks = 7, pending = 33).apply { meta["embedKey"] = PduEmbedConfig.indexKey(real, "") }
        val out = IndexGuard.prepare(b, real, embedReady = true, documentPrefix = "", ownerForce = true)
        assertEquals(IndexGuard.Outcome.Proceed, out)
        assertEquals(0, b.wipes)
        assertEquals(7, b.chunks) // what was already re-embedded stays
    }

    @Test fun theSameKeyJustProceeds() {
        val b = realIndex()
        assertEquals(IndexGuard.Outcome.Proceed, IndexGuard.prepare(b, real, embedReady = true, documentPrefix = "", ownerForce = false))
        assertEquals(0, b.wipes)
        assertEquals(40, b.chunks)
    }

    @Test fun anEmptyStoreAdoptsTheFirstRealKey() {
        val b = MemoryBacking()
        assertEquals(IndexGuard.Outcome.Proceed, IndexGuard.prepare(b, real, embedReady = true, documentPrefix = "", ownerForce = false))
        assertEquals(PduEmbedConfig.indexKey(real, ""), b.meta["embedKey"])
        assertEquals(0, b.wipes)
    }
}

class BatchEmbedderTest {
    private val texts = List(BatchEmbedder.BATCH * 3) { "chunk $it" }

    @Test fun aRealEmbedderEmbedsEverythingInBatches() = runBlocking {
        val e = StubEmbedder()
        val out = BatchEmbedder.embedAll(e, { e }, texts)
        assertEquals(texts.size, out.size)
        assertEquals(3, e.calls)
    }

    @Test fun whenTheEmbedderTurnsFakeAfterTheFirstBatchTheRunStopsAndReturnsNothing() = runBlocking {
        val e = StubEmbedder()
        val fake = FakeLlmClient()
        // the phone's current embedder: the real client for one batch, then the placeholder (the server was stopped)
        var asked = 0
        val current = { if (asked++ == 0) e else fake }
        try {
            BatchEmbedder.embedAll(e, current, texts)
            fail("expected EmbedderStopped")
        } catch (_: EmbedderStopped) {
        }
        assertEquals(1, e.calls) // the second batch was never sent, and the fake's hash vectors never reached the caller
    }

    @Test fun aServerThatStopsAnsweringStopsTheRunToo() = runBlocking {
        val e = StubEmbedder()
        val current = { if (e.calls >= 1) e.also { it.up = false } else e }
        try {
            BatchEmbedder.embedAll(e, current, texts)
            fail("expected EmbedderStopped")
        } catch (_: EmbedderStopped) {
        }
        assertEquals(1, e.calls)
    }

    @Test fun theCapturedClientIsNeverTradedForAnotherRealOne() = runBlocking {
        val first = StubEmbedder("embeddinggemma-300M-Q8_0")
        val other = StubEmbedder("qwen3-embedding-0.6B")
        try {
            BatchEmbedder.embedAll(first, { other }, texts)
            fail("expected EmbedderStopped")
        } catch (_: EmbedderStopped) {
        }
        assertEquals(0, first.calls)
        assertEquals(0, other.calls)
    }

    @Test fun theFakeClientIsNeverUsableEvenAsTheCapturedOne() = runBlocking {
        val fake = FakeLlmClient()
        assertFalse(BatchEmbedder.usable(fake, fake))
    }
}

class IndexRunTest {
    private val real = "embeddinggemma-300M-Q8_0"
    private val oldIndex = MemoryBacking(chunks = 40).apply { meta["embedKey"] = PduEmbedConfig.indexKey("old-model", "") }

    @Test fun anEmbedderThatStopsBeforeTheFirstNewVectorsLeavesTheOldIndexWhole() = runBlocking {
        val e = StubEmbedder()
        val fake = FakeLlmClient()
        val run = IndexRun(oldIndex, e, { fake }, "", replacing = true)
        try {
            run.embed(listOf("a", "b"))
            fail("expected EmbedderStopped")
        } catch (_: EmbedderStopped) {
        }
        assertEquals(0, oldIndex.wipes)
        assertEquals(40, oldIndex.chunks)
        assertEquals(PduEmbedConfig.indexKey("old-model", ""), oldIndex.meta["embedKey"])
    }

    @Test fun theOldIndexIsReplacedOnlyWhenTheFirstEmbeddedFileIsAboutToBeStored() = runBlocking {
        val e = StubEmbedder()
        val run = IndexRun(oldIndex, e, { e }, "", replacing = true)
        run.beforeStore(embedded = false) // a file without text proves nothing about the new embedder
        assertEquals(0, oldIndex.wipes)
        run.embed(listOf("a"))
        assertEquals(0, oldIndex.wipes) // embedding alone changes nothing
        run.beforeStore(embedded = true)
        assertEquals(1, oldIndex.wipes)
        assertEquals(PduEmbedConfig.indexKey(real, ""), oldIndex.meta["embedKey"])
        run.beforeStore(embedded = true) // only once per run
        assertEquals(1, oldIndex.wipes)
    }

    @Test fun anOrdinaryRunNeverWipes() = runBlocking {
        val e = StubEmbedder()
        val run = IndexRun(oldIndex, e, { e }, "", replacing = false)
        run.embed(listOf("a"))
        run.beforeStore(embedded = true)
        assertEquals(0, oldIndex.wipes)
        assertFalse(run.rereadAll)
    }
}

class RateLimiterTest {
    @Test fun threeInAMinuteThenRefusedUntilTheWindowPasses() {
        var t = 1_000L
        val r = RateLimiter(max = 3, windowMs = 60_000) { t }
        assertTrue(r.tryAcquire("B")); t += 1_000
        assertTrue(r.tryAcquire("B")); t += 1_000
        assertTrue(r.tryAcquire("B")); t += 1_000
        assertFalse(r.tryAcquire("B"))
        t += 58_000 // first request is now 61 s old
        assertTrue(r.tryAcquire("B"))
    }

    @Test fun eachPeerHasItsOwnBudget() {
        val r = RateLimiter(max = 1, windowMs = 60_000) { 5L }
        assertTrue(r.tryAcquire("B"))
        assertFalse(r.tryAcquire("B"))
        assertTrue(r.tryAcquire("C"))
    }
}
