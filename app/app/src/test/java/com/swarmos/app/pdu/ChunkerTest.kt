package com.swarmos.app.pdu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkerTest {
    private fun sentences(n: Int) = (1..n).joinToString(" ") { "Sentence number $it talks about warranty and billing." }

    @Test fun emptyTextGivesNoChunks() {
        assertTrue(Chunker.split("   \n\n  ").isEmpty())
    }

    @Test fun shortTextIsOneChunk() {
        assertEquals(listOf("Laptop warranty bill"), Chunker.split("Laptop   warranty\tbill"))
    }

    @Test fun chunksStayWithinTheWindow() {
        val chunks = Chunker.split(sentences(120))
        assertTrue(chunks.size > 3)
        assertTrue("longest ${chunks.maxOf { it.length }}", chunks.all { it.length <= Chunker.CHUNK_CHARS })
    }

    @Test fun consecutiveChunksOverlapAboutFifteenPercent() {
        val chunks = Chunker.split(sentences(120))
        for ((a, b) in chunks.zipWithNext()) {
            // the head of the next chunk is repeated inside the tail of the previous one
            val head = b.take(25)
            val at = a.lastIndexOf(head)
            assertTrue("no overlap between chunks", at >= 0)
            val overlap = a.length - at
            assertTrue("overlap $overlap chars", overlap in 40..260)
        }
    }

    @Test fun nothingIsLost() {
        val text = sentences(80)
        val joined = Chunker.split(text).joinToString(" ")
        for (i in 1..80) assertTrue("sentence $i missing", joined.contains("Sentence number $i talks"))
    }

    @Test fun prefersParagraphBreaks() {
        val para = "A".repeat(700) + " end.\n\n" + "B".repeat(700)
        val chunks = Chunker.split(para)
        assertTrue(chunks.first().endsWith("end."))
        assertTrue(chunks[1].startsWith("B") || chunks[1].contains("B"))
    }

    @Test fun titleIsPrefixed() {
        assertEquals("IMG_2231.jpg\nbody text", Chunker.withTitle("IMG_2231.jpg", "body text"))
    }
}
