package com.swarmos.app.pdu

import kotlinx.serialization.encodeToString
import com.swarmos.app.core.SwarmJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun hit(title: String, rank: Int, score: Double = 0.5, snippet: String = "snippet of $title") =
    PduHit(title, "h-$title", "photo", 0L, snippet, score, rank)

private fun cand(id: Long, doc: String, priv: Boolean, text: String = "text of $doc", title: String = "$doc.txt") =
    Candidate(id, doc, title, "text", 0L, if (priv) "Documents/Private" else "Documents", priv, text, 0.5)

class CosineTest {
    // four unit vectors in 3 dims
    private val dim = 3
    private val m = floatArrayOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0.6f, 0.8f, 0f,
        0f, 0f, 1f,
    )

    @Test fun topKIsSortedAndCorrect() {
        val top = Cosine.topK(m, dim, 4, floatArrayOf(0f, 1f, 0f), 2)
        assertEquals(listOf(1, 2), top.map { it.row })
        assertEquals(1.0f, top[0].score, 1e-6f)
        assertEquals(0.8f, top[1].score, 1e-6f)
    }

    @Test fun nLargerThanRowsReturnsAll() {
        assertEquals(4, Cosine.topK(m, dim, 4, floatArrayOf(1f, 0f, 0f), 10).size)
    }

    @Test fun emptyStoreIsEmpty() {
        assertTrue(Cosine.topK(FloatArray(0), dim, 0, floatArrayOf(1f, 0f, 0f), 5).isEmpty())
    }
}

class RrfTest {
    @Test fun itemInBothListsWins() {
        val fused = Rrf.fuse(listOf(listOf("a", "b", "c"), listOf("b", "c", "d")))
        assertEquals(listOf("b", "c", "a", "d"), fused.map { it.first })
        assertEquals(1.0 / 62 + 1.0 / 61, fused[0].second, 1e-12)
    }

    @Test fun hybridDropsDenseNoiseButKeepsBm25() {
        val dense = listOf(10L to 0.12f, 11L to 0.10f, 12L to 0.05f) // nothing similar enough
        val bm25 = listOf(12L)
        val fused = HybridRanker.fuse(dense, bm25)
        assertEquals(listOf(12L), fused.map { it.chunkId })
        assertTrue(fused[0].inBm25)
    }

    @Test fun hybridKeepsStrongDenseOnlyMatch() {
        val fused = HybridRanker.fuse(listOf(7L to 0.62f, 8L to 0.2f), emptyList())
        assertEquals(listOf(7L), fused.map { it.chunkId })
    }

    @Test fun denseNoiseDoesNotReorderBm25() {
        // chunk 2 is first in the (meaningless) dense list but far below the cosine floor
        val fused = HybridRanker.fuse(listOf(2L to 0.08f, 1L to 0.02f), listOf(1L, 2L))
        assertEquals(listOf(1L, 2L), fused.map { it.chunkId })
        assertEquals(1.0, fused[0].score, 1e-12)
    }

    @Test fun lexicalOnlyHitsNeedHalfTheQuestionWords() {
        assertFalse(HybridRanker.passes(0.02f, 1.0 / 3)) // "bill" alone in a three-word question
        assertTrue(HybridRanker.passes(0.02f, 2.0 / 3))
        assertTrue(HybridRanker.passes(0.55f, 0.0)) // semantically close, no shared words
    }

    @Test fun nothingAtAllGivesNothing() {
        assertTrue(HybridRanker.fuse(listOf(1L to 0.1f), emptyList()).isEmpty())
    }

    @Test fun normalisedScoreIsOneForFirstInBothLists() {
        val fused = HybridRanker.fuse(listOf(1L to 0.9f), listOf(1L))
        assertEquals(1.0, fused[0].score, 1e-12)
    }

    @Test fun collapseKeepsBestChunkPerDocument() {
        val c = listOf(cand(1, "d1", false), cand(2, "d2", false), cand(3, "d1", false))
        assertEquals(listOf(1L, 2L), HybridRanker.collapseByDoc(c).map { it.chunkId })
    }
}

class DeviceMergeTest {
    @Test fun ranksAreFusedAcrossDevicesAndTiesBrokenByScore() {
        val a = DeviceHits("A", "Phone A", true, listOf(hit("cert", 1, 0.9), hit("a2", 2)))
        val b = DeviceHits("B", "Phone B", false, listOf(hit("receipt", 1, 0.95), hit("b2", 2)))
        val c = DeviceHits("C", "Phone C", false, listOf(hit("power-bill", 1, 0.4)))
        val m = DeviceMerge.merge(listOf(a, b, c))
        // three rank-1 items tie on RRF, so score decides; rank-2 items follow
        assertEquals(listOf("receipt", "cert", "power-bill", "a2", "b2"), m.map { it.hit.title })
        assertEquals("Phone B", m[0].deviceName)
        assertEquals("Phone A", m[1].deviceName)
    }

    @Test fun nearIdenticalSnippetsAreDroppedKeepingTheBetterRanked() {
        val text = "Laptop warranty valid until 13 March 2028 policy EW-2025-004417"
        val a = DeviceHits("A", "Phone A", true, listOf(hit("cert.pdf", 1, 0.9, text)))
        val c = DeviceHits("C", "Phone C", false, listOf(hit("copy.pdf", 1, 0.8, "$text.")))
        val m = DeviceMerge.merge(listOf(a, c))
        assertEquals(listOf("cert.pdf"), m.map { it.hit.title })
    }

    @Test fun differentSnippetsAreKept() {
        val a = DeviceHits("A", "Phone A", true, listOf(hit("x", 1, 0.9, "alpha beta gamma delta")))
        val b = DeviceHits("B", "Phone B", false, listOf(hit("y", 1, 0.8, "one two three four")))
        assertEquals(2, DeviceMerge.merge(listOf(a, b)).size)
    }

    @Test fun keepsAtMostSix() {
        val devs = (1..3).map { d ->
            DeviceHits("D$d", "Phone $d", d == 1, (1..5).map { r -> hit("d${d}r$r", r, 0.5, "unique words $d $r ${"w".repeat(r)}$d") })
        }
        assertEquals(6, DeviceMerge.merge(devs).size)
    }

    @Test fun emptyDevicesGiveNothing() {
        assertTrue(DeviceMerge.merge(listOf(DeviceHits("A", "Phone A", true, emptyList()))).isEmpty())
    }
}

class SharePolicyTest {
    private val secret = "Net pay 91,250 salary slip SECRET-PAYROLL-4471"

    @Test fun privateFolderNamesAreDetected() {
        assertTrue(SharePolicy.mustStayPrivate("Documents/UstaadDemo/Private"))
        assertTrue(SharePolicy.mustStayPrivate("Pictures/private"))
        assertTrue(SharePolicy.mustStayPrivate("PRIVATE/Taxes"))
        assertFalse(SharePolicy.mustStayPrivate("Documents/UstaadDemo"))
        assertFalse(SharePolicy.mustStayPrivate("Documents/Privately"))
    }

    @Test fun everyNewFolderStartsPrivate() {
        for (f in listOf("Documents/UstaadDemo", "Pictures/UstaadScans", "Downloads", "Pictures/Camera")) assertTrue(f, SharePolicy.startsPrivate(f))
    }

    @Test fun aFolderNamedPrivateStaysPrivateEvenIfTheSwitchSaysShared() {
        assertTrue(SharePolicy.effectivePrivate("Documents/Private", stored = false))
        assertFalse(SharePolicy.effectivePrivate("Documents/Taxes", stored = false))
        assertTrue(SharePolicy.effectivePrivate("Documents/Taxes", stored = true))
    }

    @Test fun aChunkInAPrivateNamedFolderIsWithheldEvenWhenFlaggedShared() {
        val shared = cand(1, "a", false).copy(folder = "Documents/Private")
        val open = cand(2, "b", false)
        val resp = PeerResponder.respond("q", listOf(shared, open), emptyList(), 5, 2, 1)
        assertEquals(listOf("b.txt"), resp.hits.map { it.title })
        assertEquals(1, resp.withheldPrivate)
    }

    @Test fun privateChunkNeverAppearsInAPeerResponse() {
        val cands = listOf(
            cand(1, "slip", true, text = secret, title = "salary_slip.pdf"),
            cand(2, "receipt", false, text = "Laptop warranty bill"),
            cand(3, "slip2", true, text = secret, title = "salary_slip_old.pdf"),
        )
        val resp = PeerResponder.respond("q1", cands, listOf("salary"), k = 5, indexedChunks = 9, tookMs = 7)
        val wire = SwarmJson.encodeToString(resp)
        assertEquals(listOf("receipt.txt"), resp.hits.map { it.title })
        assertEquals(1, resp.hits.size)
        assertEquals(2, resp.withheldPrivate)
        assertFalse(wire.contains("SECRET-PAYROLL"))
        assertFalse(wire.contains("salary_slip"))
        assertFalse(wire.contains("slip")) // not even the document id
    }

    @Test fun allPrivateGivesNoHitsButACount() {
        val resp = PeerResponder.respond("q", listOf(cand(1, "a", true), cand(2, "b", true)), emptyList(), 5, 2, 1)
        assertTrue(resp.hits.isEmpty())
        assertEquals(2, resp.withheldPrivate)
    }

    @Test fun twoChunksOfOnePrivateDocumentCountOnce() {
        val sel = SharePolicy.forPeer(listOf(cand(1, "a", true), cand(2, "a", true)), 5)
        assertEquals(1, sel.withheldPrivate)
    }

    @Test fun responseIsCappedAtKAndSnippetAt400() {
        val long = "warranty ".repeat(200)
        val cands = (1..9).map { cand(it.toLong(), "d$it", false, text = long) }
        val resp = PeerResponder.respond("q", cands, listOf("warranty"), k = 5, indexedChunks = 9, tookMs = 1)
        assertEquals(5, resp.hits.size)
        assertTrue(resp.hits.all { it.snippet.length <= 400 })
        assertEquals((1..5).toList(), resp.hits.map { it.rank })
    }

    @Test fun responseCarriesNoEmbeddingOrPathFields() {
        val resp = PeerResponder.respond("q", listOf(cand(1, "d", false)), emptyList(), 5, 1, 1)
        val wire = SwarmJson.encodeToString(resp)
        for (forbidden in listOf("emb", "path", "uri\"", "folder", "exif", "Documents/")) assertFalse(forbidden, wire.contains(forbidden))
    }
}

class PromptBuilderTest {
    @Test fun sourcesAreNumberedWithPhoneFileAndDate() {
        val m = DeviceMerge.merge(listOf(DeviceHits("B", "Phone B", false, listOf(hit("IMG_2231.jpg", 1, 0.9, "Laptop warranty bill")))))
        val req = PromptBuilder.build("Where is the warranty bill?", m)
        val user = req.messages.last().content
        assertTrue(user.contains("[1] Phone B · IMG_2231.jpg · undated: Laptop warranty bill"))
        assertTrue(user.endsWith("Question: Where is the warranty bill?"))
        assertTrue(req.messages.first().content.contains("at most 80 words"))
    }
}
