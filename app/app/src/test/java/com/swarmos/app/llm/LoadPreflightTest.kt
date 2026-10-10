package com.swarmos.app.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadPreflightTest {
    private val gib = 1024L * 1024 * 1024
    private val smallModel = 2_740_937_888L      // Qwen3.5-4B Q4_K_M, docs/models.md
    private val bigModel = 18_556_686_752L       // Qwen3-30B-A3B Q4_K_M, docs/models.md

    @Test fun theRuleIsOnePointTwoTimesTheFilePlusOnePointFiveGb() {
        assertEquals(1.2, LoadPreflight.NEED_FILE_FACTOR, 0.0)
        assertEquals(gib * 3 / 2, LoadPreflight.NEED_MARGIN_BYTES)
        assertEquals((10 * gib * 1.2).toLong() + gib * 3 / 2, LoadPreflight.neededBytes(10 * gib))
    }

    @Test fun meminfoIsParsed() {
        val text = "MemTotal:       11800000 kB\nMemFree:          300000 kB\nMemAvailable:    8716032 kB\nBuffers:           12000 kB\n"
        assertEquals(8_716_032L, LoadPreflight.parseMemAvailableKb(text))
        assertNull(LoadPreflight.parseMemAvailableKb("MemTotal: 1 kB\n"))
        assertNull(LoadPreflight.parseMemAvailableKb(""))
    }

    @Test fun theBigModelIsRefusedOnAPhoneWithAboutNineGbAvailable() {
        val why = LoadPreflight.refusal("Qwen3-30B-A3B-Instruct-2507-Q4_K_M.gguf", bigModel, 8_716_032L)
        assertNotNull(why)
        assertTrue(why!!.startsWith("Not enough free memory to load Qwen3-30B-A3B"))
        assertTrue(why.contains("8.3 GB available")) // 8716032 kB
        assertTrue(why.contains("the file is 17.3 GB"))
    }

    @Test fun theDefaultChatModelFitsAnIdlePhone() {
        assertNull(LoadPreflight.refusal("Qwen3.5-4B-Q4_K_M.gguf", smallModel, 8_716_032L))
    }

    @Test fun theLimitIsInclusive() {
        val needKb = (LoadPreflight.neededBytes(smallModel) + 1023) / 1024 // the smallest MemAvailable (kB) that covers it
        assertNull(LoadPreflight.refusal("m.gguf", smallModel, needKb))
        assertNotNull(LoadPreflight.refusal("m.gguf", smallModel, needKb - 1))
    }

    @Test fun anUnreadableMeminfoNeverBlocksALoad() {
        assertNull(LoadPreflight.refusal("m.gguf", bigModel, null))
    }

    @Test fun onlyAPlainLocalLoadIsChecked() {
        assertTrue(LoadPreflight.applies(Role.CHAT, emptyList()))
        assertTrue(LoadPreflight.applies(Role.EMBED, emptyList()))
        assertFalse(LoadPreflight.applies(Role.RPC, emptyList()))
    }

    @Test fun theSplitDriverAndTheOnePhoneBaselineAreExempt() {
        // the argument lists SplitCoordinator hands to LlamaRuntime.startAsync (memcloud/SplitCoordinator.kt runBaseline and the split plan)
        val baseline = listOf("-ngl", "0", "-c", "4096", "-np", "1", "-fit", "off")
        val split = listOf("--rpc", "192.168.242.89:50052,192.168.242.76:50052", "-ngl", "99", "-c", "4096", "-np", "1")
        assertFalse(LoadPreflight.applies(Role.CHAT, baseline))
        assertFalse(LoadPreflight.applies(Role.CHAT, split))
        // so the baseline can still be started on the 17 GB model that does not fit: that failure is what it shows
        assertNull(if (LoadPreflight.applies(Role.CHAT, baseline)) LoadPreflight.refusal("big.gguf", bigModel, 8_716_032L) else null)
    }
}
