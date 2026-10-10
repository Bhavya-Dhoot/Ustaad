package com.swarmos.app.pdu

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** An in-memory store that counts what the guard does to it. */
private class MemoryBacking(var chunks: Int = 0) : IndexBacking {
    val meta = HashMap<String, String>()
    var wipes = 0
    override val chunkCount: Int get() = chunks
    override fun meta(key: String) = meta[key]
    override fun setMeta(key: String, value: String) { meta[key] = value }
    override fun wipe() { wipes++; chunks = 0 }
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

    @Test fun anExplicitOwnerActionWithARealEmbedderWipesAndAdoptsTheKey() {
        val b = realIndex()
        val out = IndexGuard.prepare(b, "qwen3-embedding-0.6B", embedReady = true, documentPrefix = "", ownerForce = true)
        assertEquals(IndexGuard.Outcome.Proceed, out)
        assertEquals(1, b.wipes)
        assertEquals(0, b.chunks)
        assertEquals(PduEmbedConfig.indexKey("qwen3-embedding-0.6B", ""), b.meta["embedKey"])
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
