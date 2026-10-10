package com.swarmos.app.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeHelpersTest {
    @Test fun vmRssParsesFromProcStatus() {
        val status = "Name:\tlibllama-server.so\nVmPeak:\t 9999 kB\nVmHWM:\t 2900000 kB\nVmRSS:\t 2811264 kB\nThreads:\t9\n"
        assertEquals(2_811_264L, ProcInfo.statusKb(status, "VmRSS"))
        assertEquals(2_900_000L, ProcInfo.statusKb(status, "VmHWM"))
        assertNull(ProcInfo.statusKb(status, "VmSwap"))
    }

    @Test fun exitCodesAreDescribed() {
        assertTrue(ProcInfo.describeExit(137).contains("SIGKILL"))
        assertTrue(ProcInfo.describeExit(139).contains("SIGSEGV"))
        assertEquals("exited normally", ProcInfo.describeExit(0))
        assertEquals("exited with code 2", ProcInfo.describeExit(2))
    }

    @Test fun logRingKeepsTheNewestLines() {
        val r = LogRing(3)
        for (i in 1..5) r.add("line $i")
        assertEquals(listOf("line 4", "line 5"), r.tail(2))
        assertEquals(3, r.size())
    }

    @Test fun benchIsRefusedFromSevereUpOnly() {
        assertNull(Bench.blockReasonFor(0))
        assertNull(Bench.blockReasonFor(2))
        // the reason uses the shared words (Cool, Warm, Hot, Severe), never the raw enum names
        assertTrue(Bench.blockReasonFor(3)!!.startsWith("Phone is Severe."))
        assertTrue(Bench.blockReasonFor(4)!!.startsWith("Phone is Severe."))
        assertTrue(!Bench.blockReasonFor(3)!!.contains("SEVERE") && !Bench.blockReasonFor(3)!!.contains("MODERATE"))
    }

    @Test fun slotsComeFromTheLastParallelFlag() {
        assertEquals(LlamaArgs.CHAT_SLOTS, LlamaArgs.slots(LlamaArgs.build(Role.CHAT, "/m/x.gguf", 8080)))
        assertEquals(4, LlamaArgs.slots(LlamaArgs.build(Role.CHAT, "/m/x.gguf", 8080, listOf("-np", "4"))))
        assertEquals(3, LlamaArgs.slots(listOf("--parallel", "3")))
        assertEquals(LlamaArgs.CHAT_SLOTS, LlamaArgs.slots(emptyList()))
        assertEquals(LlamaArgs.CHAT_SLOTS, LlamaArgs.slots(listOf("-np", "zero")))
    }

    @Test fun gemmaIsChatOnlyAndQwenAcceptsSchemas() {
        assertFalse(ModelCatalog.allowsSchema("gemma-4-E4B_q4_0-it"))
        assertFalse(ModelCatalog.allowsSchema("gemma-4-E4B_q4_0-it.gguf"))
        assertTrue(ModelCatalog.allowsSchema("Qwen3.5-4B-Q4_K_M"))
        assertTrue(ModelCatalog.allowsSchema("some-model-not-in-the-catalog"))
        assertEquals("chat only, not for structured output", ModelCatalog.CHAT_ONLY_NOTE)
    }

    @Test fun slowdownNoteNamesTheFirstMinuteBelowNinetyPercent() {
        fun pts(vararg v: Double) = v.mapIndexed { i, d -> SustainedPoint(i + 1, d, "NONE") }
        assertEquals("slower from minute 4", slowdownNote(pts(10.0, 9.8, 9.5, 8.0, 7.0)))
        assertNull(slowdownNote(pts(10.0, 9.8, 9.6, 9.5)))
        assertNull(slowdownNote(pts(10.0, 5.0))) // too few minutes to call it a trend
    }

    @Test fun aKilledServerIsRestartedOnceThenLeftFailed() {
        val ready = RestartPolicy.decide(Role.CHAT, wasReady = true, serviceUp = true, restartsUsed = 0, aliveMs = 40_000, extraArgs = emptyList())
        assertEquals(RestartPolicy.Decision.Restart(1, 2_000), ready)
        val again = RestartPolicy.decide(Role.CHAT, wasReady = true, serviceUp = true, restartsUsed = 1, aliveMs = 40_000, extraArgs = emptyList())
        assertEquals(RestartPolicy.Decision.GiveUp, again)
        // a restarted server that then ran for two minutes starts a fresh episode
        val stable = RestartPolicy.decide(Role.EMBED, wasReady = true, serviceUp = true, restartsUsed = 1, aliveMs = 130_000, extraArgs = emptyList())
        assertEquals(RestartPolicy.Decision.Restart(1, 2_000), stable)
    }

    @Test fun deathsThatAreNotOursToRepairAreIgnored() {
        fun d(role: Role = Role.CHAT, wasReady: Boolean = true, up: Boolean = true, args: List<String> = emptyList()) =
            RestartPolicy.decide(role, wasReady, up, 0, 10_000, args)
        assertEquals(RestartPolicy.Decision.Ignore, d(wasReady = false)) // never got ready: bad model or flags
        assertEquals(RestartPolicy.Decision.Ignore, d(up = false))        // the service is going down
        assertEquals(RestartPolicy.Decision.Ignore, d(role = Role.RPC))
        assertEquals(RestartPolicy.Decision.Ignore, d(args = listOf("--rpc", "10.0.0.2:50052"))) // a split server is MemoryCloud's to re-plan
    }

    @Test fun zombieProcessesCountAsGone() {
        assertTrue(ProcInfo.isZombie("Name:\tlibllama-server.so\nState:\tZ (zombie)\nPid:\t123\n"))
        assertFalse(ProcInfo.isZombie("Name:\tlibllama-server.so\nState:\tS (sleeping)\nPid:\t123\n"))
        assertFalse(ProcInfo.isZombie("no state line"))
    }

    @Test fun serviceStartLoadsCatalogDefaultsOnlyNeverTheLastLargeModel() {
        val big = "Qwen3-30B-A3B-Instruct-2507-Q4_K_M.gguf"
        val present = listOf(
            PresentModel(big, 18_556_686_752L, true),
            PresentModel("gemma-4-E4B_q4_0-it.gguf", 5_154_941_280L, true),
            PresentModel("Qwen3.5-4B-Q4_K_M.gguf", 2_740_937_888L, true),
            PresentModel("embeddinggemma-300M-Q8_0.gguf", 333_590_944L, true),
        )
        assertEquals("Qwen3.5-4B-Q4_K_M.gguf", ModelStore.autoLoadChoice(Role.CHAT, present))
        assertEquals("embeddinggemma-300M-Q8_0.gguf", ModelStore.autoLoadChoice(Role.EMBED, present))
        // default missing or still downloading: load nothing for that role rather than another model
        assertNull(ModelStore.autoLoadChoice(Role.CHAT, present.filter { it.file == big }))
        assertNull(ModelStore.autoLoadChoice(Role.CHAT, listOf(PresentModel("Qwen3.5-4B-Q4_K_M.gguf", 1_000, false))))
    }

    // a model whose load failed holds nothing: Delete is allowed, while a loading or running server still blocks it
    @Test fun aModelIsInUseOnlyWhileItsServerIsLoadingOrRunning() {
        fun status(state: ServerState, path: String?) = ServerStatus(Role.CHAT, state, modelPath = path)
        val file = "Qwen3.5-4B-Q4_K_M.gguf"
        val path = "/data/user/0/app/files/models/$file"
        assertTrue(ModelStore.inUse(listOf(status(ServerState.READY, path)), file))
        assertTrue(ModelStore.inUse(listOf(status(ServerState.STARTING, path)), file))
        assertFalse(ModelStore.inUse(listOf(status(ServerState.FAILED, path)), file))
        assertFalse(ModelStore.inUse(listOf(status(ServerState.STOPPED, path)), file))
        assertFalse(ModelStore.inUse(listOf(status(ServerState.READY, "/data/models/other.gguf")), file))
        assertFalse(ModelStore.inUse(emptyList(), file))
    }

    @Test fun everyCatalogEntryHasAPinnedSizeAndSha256() {
        for (e in ModelCatalog.entries) {
            assertEquals("${e.file} sha256", 64, e.sha256.length)
            assertTrue("${e.file} bytes", e.bytes > 0)
        }
        assertEquals(
            "6c997b8af17debdfb01d890214400ccbab00db6acc0ba8da5de1cc906c4774d0",
            ModelCatalog.byFile("Qwen3-30B-A3B-Instruct-2507-Q4_K_M.gguf")!!.sha256,
        )
    }

    @Test fun rolesParseByTag() {
        assertEquals(Role.CHAT, Role.parse("chat"))
        assertEquals(Role.EMBED, Role.parse(" EMBED "))
        assertNull(Role.parse("bogus"))
    }

    @Test fun extraArgsComeLastSoTheyOverrideDefaults() {
        val args = LlamaArgs.build(Role.CHAT, "/m/x.gguf", 8080, listOf("-t", "4", "--rpc", "10.0.0.2:50052"))
        assertEquals("/m/x.gguf", args[args.indexOf("-m") + 1])
        assertEquals("4", args[args.lastIndexOf("-t") + 1])
        assertTrue(args.indexOf("--rpc") > args.indexOf("--port"))
        val embed = LlamaArgs.build(Role.EMBED, "/m/e.gguf", 8081)
        assertTrue("--embedding" in embed)
        assertEquals("8081", embed[embed.indexOf("--port") + 1])
        assertEquals(listOf("-H", "127.0.0.1", "-p", "50052"), LlamaArgs.build(Role.RPC, null, 50052))
    }
}
