package com.swarmos.app.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendPlanTest {
    private fun plan(role: Role, extra: List<String> = emptyList(), gpuOn: Boolean = true, gpuBinary: Boolean = true, cpuOnly: Boolean = false) =
        BackendPlan.choose(role, extra, gpuOn, gpuBinary, cpuOnly)

    @Test fun chatTriesTheGpuBuildFirstThenTheCpuBuild() {
        assertEquals(listOf(Backend.GPU, Backend.CPU), plan(Role.CHAT))
    }

    @Test fun theSettingOrAMissingBinaryKeepsChatOnTheCpu() {
        assertEquals(listOf(Backend.CPU), plan(Role.CHAT, gpuOn = false))
        assertEquals(listOf(Backend.CPU), plan(Role.CHAT, gpuBinary = false))
    }

    @Test fun aRestartOfAServerThatRanOnTheCpuStaysThere() {
        assertEquals(listOf(Backend.CPU), plan(Role.CHAT, cpuOnly = true))
    }

    @Test fun embedderAndRpcAlwaysUseTheCpuBuild() {
        assertEquals(listOf(Backend.CPU), plan(Role.EMBED))
        assertEquals(listOf(Backend.CPU), plan(Role.RPC))
    }

    @Test fun theMemoryCloudSplitKeepsTheCpuBuild() {
        assertEquals(listOf(Backend.CPU), plan(Role.CHAT, extra = listOf("--rpc", "192.168.1.5:50052", "-ngl", "20")))
    }

    @Test fun gpuChatArgsOffloadEveryLayerWithFourThreadsAndKeepTwoSlots() {
        val a = LlamaArgs.build(Role.CHAT, "/m/q.gguf", 8080, backend = Backend.GPU)
        assertEquals("99", a[a.indexOf("-ngl") + 1])
        assertEquals("4", a[a.indexOf("-t") + 1])
        assertEquals("2", a[a.indexOf("-np") + 1])
        assertEquals("off", a[a.indexOf("--reasoning") + 1])
    }

    @Test fun cpuChatArgsHaveNoGpuOffloadAndSixThreads() {
        val a = LlamaArgs.build(Role.CHAT, "/m/q.gguf", 8080)
        assertFalse("-ngl" in a)
        assertEquals("6", a[a.indexOf("-t") + 1])
        assertEquals("2", a[a.indexOf("-np") + 1])
    }

    @Test fun theEmbedderIgnoresTheBackend() {
        assertEquals(LlamaArgs.build(Role.EMBED, "/m/e.gguf", 8081), LlamaArgs.build(Role.EMBED, "/m/e.gguf", 8081, backend = Backend.GPU))
    }

    @Test fun gpuBinaryIsOnlyUsedForTheChatServer() {
        assertEquals("libllama-server-gpu.so", LlamaArgs.binaryFor(Role.CHAT, Backend.GPU))
        assertEquals("libllama-server.so", LlamaArgs.binaryFor(Role.CHAT, Backend.CPU))
        assertEquals("libllama-server.so", LlamaArgs.binaryFor(Role.EMBED, Backend.GPU))
        assertEquals("librpc-server.so", LlamaArgs.binaryFor(Role.RPC, Backend.GPU))
    }

    @Test fun schedulerGetsOneJobAtATimeWhileTheServerKeepsTwoSlots() {
        assertEquals(1, LlamaArgs.SCHED_CAPACITY)
        assertTrue(LlamaArgs.CHAT_SLOTS >= 2)
    }
}
