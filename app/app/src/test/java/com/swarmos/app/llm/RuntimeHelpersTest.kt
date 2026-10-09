package com.swarmos.app.llm

import org.junit.Assert.assertEquals
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
        assertTrue(Bench.blockReasonFor(3)!!.contains("SEVERE"))
        assertTrue(Bench.blockReasonFor(4)!!.contains("CRITICAL"))
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
