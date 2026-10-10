package com.swarmos.app.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The flags and environment the chat server is started with: prompt-cache caps and the OpenCL program cache directory. */
class ServerFlagsTest {
    private fun value(args: List<String>, flag: String) = args[args.lastIndexOf(flag) + 1]

    // ---- B: prompt-cache caps ----

    @Test fun bothChatBuildsCapTheContextCheckpointsAndTheHostPromptCache() {
        for (backend in Backend.entries) {
            val a = LlamaArgs.build(Role.CHAT, "/m/q.gguf", 8080, backend = backend)
            assertEquals("6", value(a, "-ctxcp"))
            assertEquals("1024", value(a, "-cram"))
        }
    }

    @Test fun theCapsAreNeverZeroBecauseZeroSwitchesTheCacheOff() {
        assertTrue(LlamaArgs.CHAT_CTX_CHECKPOINTS > 0)
        assertTrue(LlamaArgs.CHAT_CACHE_RAM_MIB > 0)
    }

    @Test fun theEmbedderAndRpcDoNotGetTheChatCaps() {
        assertFalse("-ctxcp" in LlamaArgs.build(Role.EMBED, "/m/e.gguf", 8081))
        assertFalse("-cram" in LlamaArgs.build(Role.EMBED, "/m/e.gguf", 8081))
        assertFalse("-ctxcp" in LlamaArgs.build(Role.RPC, null, 50052))
    }

    @Test fun theOtherChatDefaultsAreUntouched() {
        val a = LlamaArgs.build(Role.CHAT, "/m/q.gguf", 8080, backend = Backend.GPU)
        assertEquals("99", value(a, "-ngl"))
        assertEquals("4", value(a, "-t"))
        assertEquals("2", value(a, "-np"))
        assertEquals("8192", value(a, "-c"))
        assertEquals("off", value(a, "--reasoning"))
        assertFalse("-ub" in a)
        assertFalse("--spec-type" in a)
        assertEquals(1, LlamaArgs.SCHED_CAPACITY)
    }

    @Test fun aCallersFlagStillOverridesTheCap() {
        val a = LlamaArgs.build(Role.CHAT, "/m/q.gguf", 8080, extra = listOf("-ctxcp", "2"))
        assertEquals("2", value(a, "-ctxcp")) // the last occurrence wins
    }

    // ---- A: OpenCL program cache ----

    @Test fun theGpuChatBuildGetsTheKernelCacheDirectory() {
        assertEquals(mapOf("GGML_OPENCL_KERNEL_CACHE_DIR" to "/data/user/0/p/files/cl-cache"), LlamaArgs.kernelCacheEnv(Role.CHAT, Backend.GPU, "/data/user/0/p/files/cl-cache"))
    }

    @Test fun noOtherBuildGetsItAndNoDirectoryMeansNoVariable() {
        assertTrue(LlamaArgs.kernelCacheEnv(Role.CHAT, Backend.CPU, "/d").isEmpty())
        assertTrue(LlamaArgs.kernelCacheEnv(Role.EMBED, Backend.GPU, "/d").isEmpty())
        assertTrue(LlamaArgs.kernelCacheEnv(Role.CHAT, Backend.GPU, null).isEmpty()) // the directory could not be made: start without the cache
        assertTrue(LlamaArgs.kernelCacheEnv(Role.CHAT, Backend.GPU, " ").isEmpty())
    }
}
