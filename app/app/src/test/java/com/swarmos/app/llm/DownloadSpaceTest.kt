package com.swarmos.app.llm

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadSpaceTest {
    private val gib = 1024L * 1024 * 1024
    private val big = 18_556_686_752L // Qwen3-30B-A3B Q4_K_M, docs/models.md

    @Test fun aDownloadThatNeedsMoreThanTheFreeSpaceMinusOneGbIsRefusedPlainly() {
        val why = Downloader.spaceRefusal("Qwen3-30B-A3B-Instruct-2507-Q4_K_M.gguf", big, 0, 12 * gib)
        assertNotNull(why)
        assertTrue(why!!.startsWith("Not enough free space to download Qwen3-30B-A3B"))
        assertTrue(why.contains("17.3 GB more plus 1.0 GB to spare"))
        assertTrue(why.contains("12.0 GB free"))
    }

    @Test fun theLimitIsTheFileSizePlusOneGbAndItIsInclusive() {
        assertNull(Downloader.spaceRefusal("m.gguf", 4 * gib, 0, 5 * gib))
        assertNotNull(Downloader.spaceRefusal("m.gguf", 4 * gib, 0, 5 * gib - 1))
    }

    @Test fun aResumedDownloadOnlyNeedsTheRest() {
        assertNull(Downloader.spaceRefusal("m.gguf", 4 * gib, 3 * gib, 2 * gib)) // 1 GB left plus 1 GB spare
        assertNotNull(Downloader.spaceRefusal("m.gguf", 4 * gib, 3 * gib, 2 * gib - 1))
        assertNull(Downloader.spaceRefusal("m.gguf", 4 * gib, 5 * gib, gib)) // a part file larger than the pinned size needs nothing more
    }

    @Test fun anUnknownSizeOrAnUnreadableFreeSpaceNeverBlocks() {
        assertNull(Downloader.spaceRefusal("m.gguf", 0, 0, 0))
        assertNull(Downloader.spaceRefusal("m.gguf", big, 0, null))
    }
}
