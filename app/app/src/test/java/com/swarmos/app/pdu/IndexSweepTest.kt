package com.swarmos.app.pdu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException

/** Findings 3, 4 and 6: what a finished run removes, what a moved file becomes, what a peer's answer is cut to. */
class IndexSweepTest {
    private fun doc(uri: String, folder: String = "F", kind: String = "photo") = StoredDoc("id-$uri", uri, kind, folder)

    // ---- finding 3: files that are gone ---------------------------------------------------------------------

    @Test fun aStoredFileThatTheRunDidNotSeeIsStale() {
        val stored = listOf(doc("a"), doc("b"), doc("c"))
        assertEquals(listOf("b"), IndexSweep.stale(stored, setOf("a", "c", "zzz")).map { it.uri })
    }

    @Test fun aRunThatSawEverythingRemovesNothing() {
        assertTrue(IndexSweep.stale(listOf(doc("a"), doc("b")), setOf("a", "b")).isEmpty())
    }

    @Test fun aFileThatMovedToAnotherPathIsStaleAtItsOldPlace() {
        // a file moved on the file system has a new uri: the old row (in the shared folder) is not in the new listing and goes
        val stored = listOf(doc("file:///Documents/bill.pdf", "Documents"))
        val seenAfterTheMove = setOf("file:///Documents/Private/bill.pdf")
        assertEquals(listOf("file:///Documents/bill.pdf"), IndexSweep.stale(stored, seenAfterTheMove).map { it.uri })
    }

    @Test fun aFileThatCanNoLongerBeOpenedIsGone() {
        assertTrue(PduIndexer.isGone(FileNotFoundException("No item at content://media/1")))
        assertTrue(PduIndexer.isGone(SecurityException("permission revoked")))
        assertTrue(PduIndexer.isGone(IllegalStateException("cannot open content://x")))
        assertTrue(PduIndexer.isGone(RuntimeException("decode", FileNotFoundException("x"))))
        assertFalse(PduIndexer.isGone(IOException("broken pipe")))
        assertFalse(PduIndexer.isGone(FileTimedOut(90_000)))
        assertFalse(PduIndexer.isGone(IllegalStateException("something else")))
    }

    // ---- a file that moved into a private folder must not stay shared -------------------------------------------

    @Test fun aFileThatMovedIntoAFolderNamedPrivateIsPrivateWhateverThatFoldersChoice() {
        assertTrue(SharePolicy.privacyAfterMove("Pictures/Private", storedChoice = false))
        assertTrue(SharePolicy.privacyAfterMove("Documents/private/tax", storedChoice = false))
    }

    @Test fun aFileThatMovedIntoAFolderTheOwnerKeepsPrivateIsPrivate() {
        assertTrue(SharePolicy.privacyAfterMove("Pictures/Family", storedChoice = true))
    }

    @Test fun aFileThatMovedIntoAFolderSeenForTheFirstTimeStartsPrivate() {
        assertTrue(SharePolicy.privacyAfterMove("Pictures/New", storedChoice = null))
    }

    @Test fun aFileThatMovedIntoASharedFolderIsShared() {
        assertFalse(SharePolicy.privacyAfterMove("Pictures/Shared", storedChoice = false))
    }

    @Test fun theMovedFilesOldPublicFlagDoesNotSurviveAPeerSearch() {
        // the candidate as the store would now return it: folder and flag of the new place
        val c = Candidate(1, "d", "bill", "photo", 0, "Pictures/Private", SharePolicy.privacyAfterMove("Pictures/Private", false), "text", 1.0)
        val sel = SharePolicy.forPeer(listOf(c), 5)
        assertTrue(sel.shared.isEmpty())
        assertEquals(1, sel.withheldPrivate)
    }

    // ---- finding 6: a peer's answer is not trusted for size ---------------------------------------------------------

    private fun hit(i: Int, snippet: String = "s", title: String = "t") = PduHit(title, "h$i", "photo", 0, snippet, 1.0, i)

    @Test fun aResponseIsCutToTenHitsAndFourHundredCharacterSnippets() {
        val r = PduResponse("q", (1..25).map { hit(it, snippet = "x".repeat(5_000), title = "T".repeat(900)) }, 10, 5, 0).capped()
        assertEquals(10, r.hits.size)
        assertTrue(r.hits.all { it.snippet.length == 400 })
        assertTrue(r.hits.all { it.title.length == 200 })
        assertEquals(listOf(1, 2, 3), r.hits.take(3).map { it.rank })
    }

    @Test fun anHonestResponseIsNotChanged() {
        val honest = PduResponse("q", (1..5).map { hit(it, snippet = Snippets.best("word ".repeat(300), listOf("word"))) }, 10, 5, 2)
        assertTrue(honest.hits.all { it.snippet.length <= Snippets.MAX })
        assertEquals(honest, honest.capped())
    }

    // ---- finding 4: a file is read up to its cap and no further ------------------------------------------------------

    @Test fun aStreamIsReadUpToTheCapOnly() {
        val src = object : java.io.InputStream() {
            var served = 0L
            override fun read(): Int { served++; return 'a'.code }
            override fun read(b: ByteArray, off: Int, len: Int): Int { served += len; java.util.Arrays.fill(b, off, off + len, 'a'.code.toByte()); return len }
        }
        val bytes = CappedRead.bytes(src, 100_000)
        assertEquals(100_000, bytes.size)
        assertTrue("read ${src.served} bytes of an endless stream", src.served <= 100_000 + 16 * 1024)
    }

    @Test fun aShortStreamIsReadWhole() {
        assertEquals("hello", String(CappedRead.bytes(ByteArrayInputStream("hello".toByteArray()), 1_000)))
    }
}
