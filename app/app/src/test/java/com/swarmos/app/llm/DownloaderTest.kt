package com.swarmos.app.llm

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger

/** Resume, progress and hash verification against a local HTTP server that honours Range. */
class DownloaderTest {
    private val data = ByteArray(3 * 1024 * 1024 + 123).also { Random(42).nextBytes(it) }
    private val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    private lateinit var server: HttpServer
    private lateinit var dir: File
    private val ranged = AtomicInteger(0)
    private var honourRange = true

    @Before fun setUp() {
        dir = Files.createTempDirectory("dl").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/m.gguf") { ex -> serve(ex) }
        server.start()
    }

    @After fun tearDown() {
        server.stop(0)
        dir.deleteRecursively()
    }

    private fun serve(ex: HttpExchange) {
        val range = ex.requestHeaders.getFirst("Range")
        if (range != null && honourRange) {
            ranged.incrementAndGet()
            val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
            ex.responseHeaders.add("Content-Range", "bytes $from-${data.size - 1}/${data.size}")
            ex.sendResponseHeaders(206, (data.size - from).toLong())
            ex.responseBody.use { it.write(data, from, data.size - from) }
        } else {
            ex.sendResponseHeaders(200, data.size.toLong())
            ex.responseBody.use { it.write(data) }
        }
    }

    private val url get() = "http://127.0.0.1:${server.address.port}/m.gguf"

    private class Counter : Downloader.Listener {
        var net = 0L
        var lastDone = 0L
        var verified = false
        override fun onNetworkBytes(n: Long) { net += n }
        override fun onProgress(done: Long, total: Long) { lastDone = done }
        override fun onVerifying() { verified = true }
    }

    @Test fun fullDownloadVerifiesAndCountsEveryNetworkByte() = runBlocking {
        val dest = File(dir, "m.gguf")
        val c = Counter()
        Downloader().download(url, dest, data.size.toLong(), sha, c)
        assertEquals(data.size.toLong(), dest.length())
        assertEquals(data.size.toLong(), c.net)
        assertTrue(c.verified)
        assertFalse(File(dir, "m.gguf.part").exists())
    }

    @Test fun partialFileIsResumedWithARangeRequest() = runBlocking {
        val dest = File(dir, "m.gguf")
        val cut = 1_000_000
        File(dir, "m.gguf.part").writeBytes(data.copyOf(cut))
        val c = Counter()
        Downloader().download(url, dest, data.size.toLong(), sha, c)
        assertEquals(1, ranged.get())
        assertEquals((data.size - cut).toLong(), c.net) // only the missing tail crossed the "WAN"
        assertEquals(sha, Downloader.sha256Hex(dest))
    }

    @Test fun serverIgnoringRangeRestartsFromZero() = runBlocking {
        honourRange = false
        val dest = File(dir, "m.gguf")
        File(dir, "m.gguf.part").writeBytes(ByteArray(500_000) { 9 }) // garbage prefix
        Downloader().download(url, dest, data.size.toLong(), sha, Counter())
        assertEquals(sha, Downloader.sha256Hex(dest))
    }

    @Test fun wrongHashFailsAndDeletesTheDownload() = runBlocking {
        val dest = File(dir, "m.gguf")
        try {
            Downloader().download(url, dest, data.size.toLong(), "0".repeat(64), Counter())
            fail("expected Sha256MismatchException")
        } catch (e: Sha256MismatchException) {
            assertEquals(sha, e.actual)
        }
        assertFalse(dest.exists())
        assertFalse(File(dir, "m.gguf.part").exists())
    }

    @Test fun contentRangeTotalParses() {
        assertEquals(1234L, Downloader.contentRangeTotal("bytes 100-199/1234"))
        assertEquals(null, Downloader.contentRangeTotal(null))
    }
}
