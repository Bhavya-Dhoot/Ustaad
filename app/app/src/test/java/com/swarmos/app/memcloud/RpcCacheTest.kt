package com.swarmos.app.memcloud

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class RpcCacheTest {
    private lateinit var dir: File

    @Before fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "rpc-cache-test-${System.nanoTime()}").also { it.mkdirs() }
        File(dir, "a").writeBytes(ByteArray(1000))
        File(dir, "b").writeBytes(ByteArray(250))
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    @Test fun theSizeIsReportedWithoutTouchingAnything() {
        assertEquals(RpcCache.Stats(1250, 2), RpcCache.stats(dir))
        assertEquals(RpcCache.Stats(0, 0), RpcCache.stats(File(dir, "missing")))
        assertEquals(2, dir.listFiles()!!.size)
    }

    @Test fun clearingRemovesTheFilesAndSaysHowMuchWentAndHowMuchIsLeft() {
        val r = RpcCache.clear(dir, busy = null) as RpcCache.ClearResult.Cleared
        assertEquals(RpcCache.Stats(1250, 2), r.removed)
        assertEquals(RpcCache.Stats(0, 0), r.remaining)
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun whileRpcServerRunsTheCacheIsKept() {
        val r = RpcCache.clear(dir, busy = "rpc-server is running; stop it first")
        assertEquals(RpcCache.ClearResult.Refused("rpc-server is running; stop it first"), r)
        assertEquals(2, dir.listFiles()!!.size)
    }

    @Test fun aMissingFolderIsAnEmptyCache() {
        val r = RpcCache.clear(File(dir, "missing"), busy = null) as RpcCache.ClearResult.Cleared
        assertEquals(RpcCache.Stats(0, 0), r.removed)
    }
}
