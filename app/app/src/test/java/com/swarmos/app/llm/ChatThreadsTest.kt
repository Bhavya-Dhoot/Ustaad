package com.swarmos.app.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatThreadsTest {
    private fun threads(args: List<String>) = args[args.indexOf("-t") + 1].toInt()

    @Test fun theCpuBuildLeavesOneAllowedCoreFreeUpToSixThreadsAndNeverFewerThanTwo() {
        assertEquals(6, LlamaArgs.cpuChatThreads(8))
        assertEquals(6, LlamaArgs.cpuChatThreads(7))
        assertEquals(5, LlamaArgs.cpuChatThreads(6)) // an app in the background may use cores 0-5 only
        assertEquals(3, LlamaArgs.cpuChatThreads(4))
        assertEquals(2, LlamaArgs.cpuChatThreads(3))
        assertEquals(2, LlamaArgs.cpuChatThreads(2))
        assertEquals(2, LlamaArgs.cpuChatThreads(1))
        assertEquals(6, LlamaArgs.cpuChatThreads(16))
    }

    @Test fun theCpuChatServerGetsTheThreadsForItsAllowedCores() {
        assertEquals(5, threads(LlamaArgs.build(Role.CHAT, "/m/q.gguf", 8080, allowedCores = 6)))
        assertEquals(6, threads(LlamaArgs.build(Role.CHAT, "/m/q.gguf", 8080, allowedCores = 8)))
    }

    @Test fun withoutACoreCountTheOldSixThreadsStay() {
        assertEquals(6, threads(LlamaArgs.build(Role.CHAT, "/m/q.gguf", 8080)))
    }

    @Test fun theGpuBuildAndTheEmbedderKeepTheirThreadCounts() {
        assertEquals(4, threads(LlamaArgs.build(Role.CHAT, "/m/q.gguf", 8080, backend = Backend.GPU, allowedCores = 3)))
        assertEquals(2, threads(LlamaArgs.build(Role.EMBED, "/m/e.gguf", 8081, allowedCores = 3)))
        assertEquals(2, threads(LlamaArgs.build(Role.EMBED, "/m/e.gguf", 8081, allowedCores = 8)))
    }

    @Test fun threadsFromACallerStillWin() {
        val a = LlamaArgs.build(Role.CHAT, "/m/q.gguf", 8080, extra = listOf("-t", "3"), allowedCores = 8)
        assertEquals(3, a[a.lastIndexOf("-t") + 1].toInt()) // llama.cpp: the last occurrence of a flag wins
    }
}
