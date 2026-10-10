package com.swarmos.app.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuLaunchTest {
    private val nativeDir = "/data/app/~~x/com.swarmos.app-y/lib/arm64"

    @Test fun gpuChatChildGetsTheVendorLibraryDirectories() {
        assertEquals(
            "$nativeDir:/vendor/lib64:/system/vendor/lib64",
            LlamaArgs.ldLibraryPath(nativeDir, Role.CHAT, Backend.GPU),
        )
    }

    @Test fun everyOtherChildKeepsTheNativeDirectoryOnly() {
        assertEquals(nativeDir, LlamaArgs.ldLibraryPath(nativeDir, Role.CHAT, Backend.CPU))
        assertEquals(nativeDir, LlamaArgs.ldLibraryPath(nativeDir, Role.EMBED, Backend.CPU))
        assertEquals(nativeDir, LlamaArgs.ldLibraryPath(nativeDir, Role.RPC, Backend.CPU))
    }

    @Test fun errorDetailKeepsTheNewestWholeLinesWithin2Kb() {
        val lines = (1..200).map { "line $it " + "x".repeat(40) }
        val d = ProcInfo.errorDetail(lines)
        assertTrue(d.length <= ProcInfo.ERROR_DETAIL_MAX)
        assertTrue(d.endsWith(lines.last()))
        assertTrue(d.lines().all { it in lines }) // whole lines only
        assertTrue(d.lines().size > 20)
    }

    @Test fun errorDetailKeepsTheEndOfOneVeryLongLine() {
        val long = "a".repeat(5000) + "THE-END"
        val d = ProcInfo.errorDetail(listOf(long))
        assertEquals(ProcInfo.ERROR_DETAIL_MAX, d.length)
        assertTrue(d.endsWith("THE-END"))
    }

    @Test fun errorDetailSkipsBlankLinesAndHandlesEmpty() {
        assertEquals("", ProcInfo.errorDetail(emptyList()))
        assertEquals("a\nb", ProcInfo.errorDetail(listOf("a", "", "  ", "b")))
    }

    @Test fun linkerFailureTextSurvivesIntact() {
        val msg = "CANNOT LINK EXECUTABLE \"$nativeDir/libllama-server-gpu.so\": library \"libOpenCL.so\" not found: needed by main executable"
        assertEquals(msg, ProcInfo.errorDetail(listOf(msg)))
    }
}
