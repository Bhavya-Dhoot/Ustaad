package com.swarmos.app.link

import com.swarmos.app.core.NodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

private const val SECRET = "test-secret"

/** A server that runs a handler at most once per request id, the way NodeServer does with [RequestDedup]. */
private class FakeServer {
    val dedup = RequestDedup()
    val executions = AtomicInteger(0)

    /** Runs the request; the response is stored. Returns the body the client would read. */
    suspend fun handle(id: String): String = when (val b = dedup.begin(id)) {
        RequestDedup.Begin.Run -> {
            val n = executions.incrementAndGet()
            delay(20)
            val body = "result-of-run-$n"
            dedup.complete(id, StoredResponse(200, "application/json", body.toByteArray()))
            body
        }
        is RequestDedup.Begin.Replay -> String(b.stored.body)
    }
}

class RequestDedupTest {
    @Test fun secondRequestWithTheSameIdReplaysInsteadOfRunning() = runBlocking {
        val s = FakeServer()
        val first = s.handle("a")
        val second = s.handle("a")
        assertEquals("result-of-run-1", first)
        assertEquals(first, second)
        assertEquals(1, s.executions.get())
        assertEquals("a different id runs", "result-of-run-2", s.handle("b"))
    }

    @Test fun aDuplicateArrivingWhileTheFirstIsRunningWaitsAndGetsTheSameAnswer() = runBlocking {
        val s = FakeServer()
        val both = listOf(async { s.handle("x") }, async { delay(5); s.handle("x") }).awaitAll()
        assertEquals(both[0], both[1])
        assertEquals(1, s.executions.get())
    }

    @Test fun anAbandonedRunLetsTheRetryRunAgain() = runBlocking {
        val d = RequestDedup()
        assertTrue(d.begin("k") === RequestDedup.Begin.Run)
        val waiter = async { d.begin("k") }
        delay(10)
        d.abandon("k") // the handler failed without a response
        assertTrue("the waiter becomes the runner", waiter.await() === RequestDedup.Begin.Run)
    }

    @Test fun entriesExpireAndTheCacheIsBounded() = runBlocking {
        var now = 0L
        val d = RequestDedup(ttlMs = 1_000, maxEntries = 4, clock = { now })
        for (i in 1..8) { assertTrue(d.begin("id$i") === RequestDedup.Begin.Run); d.complete("id$i", StoredResponse(200, null, ByteArray(10))); now += 10 }
        d.begin("probe") // triggers eviction
        assertTrue("bounded, was ${d.size}", d.size <= 6)
        now += 5_000
        assertTrue("expired entry runs again", d.begin("id8") === RequestDedup.Begin.Run)
    }
}

class FailoverTest {
    private val ioFail = IOException("connection reset")

    @Test fun pathOneFailsBeforeTheServerSeesItPathTwoRunsOnce() = runBlocking {
        val server = FakeServer()
        val tried = mutableListOf<String>()
        val r = sendWithFailover(
            "req-1",
            listOf(
                PathAttempt("DIRECT") { tried += "DIRECT"; throw ioFail },
                PathAttempt("LAN") { id -> tried += "LAN"; server.handle(id) },
            ),
        )
        assertEquals("result-of-run-1", r)
        assertEquals(listOf("DIRECT", "LAN"), tried)
        assertEquals(1, server.executions.get())
    }

    @Test fun pathOneRunsTheRequestButTheAnswerIsLostPathTwoGetsTheStoredAnswerNotASecondRun() = runBlocking {
        val server = FakeServer()
        val failures = mutableListOf<String>()
        val r = sendWithFailover(
            "req-2",
            listOf(
                PathAttempt("DIRECT") { id -> server.handle(id); throw IOException("read timed out") }, // executed, response lost
                PathAttempt("LAN") { id -> server.handle(id) },
            ),
            onFailure = { k, _ -> failures += k },
        )
        assertEquals("result-of-run-1", r)
        assertEquals("executed exactly once", 1, server.executions.get())
        assertEquals(listOf("DIRECT"), failures)
    }

    @Test fun anApplicationErrorIsFinalAndDoesNotTryAnotherPath() = runBlocking {
        var second = false
        try {
            sendWithFailover("req-3", listOf(
                PathAttempt("DIRECT") { throw NodeHttpException(409, "model mismatch") },
                PathAttempt("LAN") { second = true; "x" },
            ))
            fail("expected NodeHttpException")
        } catch (e: NodeHttpException) {
            assertEquals(409, e.status)
        }
        assertFalse(second)
    }

    @Test fun allPathsFailingReportsEveryCause() = runBlocking {
        try {
            sendWithFailover("req-4", listOf(PathAttempt("DIRECT") { throw ioFail }, PathAttempt("LAN") { throw IOException("refused") }))
            fail("expected AllPathsFailed")
        } catch (e: AllPathsFailed) {
            assertEquals(2, e.causes.size)
            assertTrue(e.message!!.contains("DIRECT") && e.message!!.contains("LAN"))
        }
    }

    @Test fun aPathDeclaredAwayMidRequestMovesTheRequestToTheNextPath() = runBlocking {
        val server = FakeServer()
        val r = sendWithFailover("req-5", listOf(
            PathAttempt("DIRECT") { delay(5); throw PathLostCancellation("x/DIRECT") }, // the path was declared away while the request was in flight
            PathAttempt("LAN") { id -> server.handle(id) },
        ))
        assertEquals("result-of-run-1", r)
    }
}

class MeasuredPolicyTest {
    private fun path(link: LinkType, rtt: Double?, state: LinkState = LinkState.ONLINE, mbps: Double? = null, node: String = "n1") =
        PeerStatus(
            nodeId = node, name = "Phone B", ip = "10.0.0.1", port = 8797, link = link, state = state, rttMs = rtt, lastSeenMs = 0, misses = 0,
            health = null, info = NodeInfo(node, "Phone B", "x", "10.0.0.1", "0", null, null), ewmaRttMs = rtt, lastMbps = mbps,
        )

    @Test fun smallRequestsFollowTheLowerRttButNotOnNoise() {
        var now = 0L
        val p = MeasuredPolicy(clock = { now })
        assertEquals(LinkType.LAN, p.choose(listOf(path(LinkType.LAN, 10.0), path(LinkType.DIRECT, 12.0)))?.link)
        now += 10_000
        // DIRECT is 10 % better: inside the 25 % margin, no switch
        assertEquals(LinkType.LAN, p.choose(listOf(path(LinkType.LAN, 10.0), path(LinkType.DIRECT, 9.0)))?.link)
        // DIRECT is 2x better: switch
        assertEquals(LinkType.DIRECT, p.choose(listOf(path(LinkType.LAN, 10.0), path(LinkType.DIRECT, 5.0)))?.link)
        // and LAN is now only slightly better: stay on DIRECT
        assertEquals(LinkType.DIRECT, p.choose(listOf(path(LinkType.LAN, 4.5), path(LinkType.DIRECT, 5.0)))?.link)
    }

    @Test fun noSwitchBeforeTheMinimumHoldTime() {
        var now = 0L
        val p = MeasuredPolicy(minHoldMs = 5_000, clock = { now })
        p.choose(listOf(path(LinkType.LAN, 10.0), path(LinkType.DIRECT, 20.0)))
        now += 1_000
        assertEquals(LinkType.LAN, p.choose(listOf(path(LinkType.LAN, 40.0), path(LinkType.DIRECT, 5.0)))?.link)
        now += 5_000
        assertEquals(LinkType.DIRECT, p.choose(listOf(path(LinkType.LAN, 40.0), path(LinkType.DIRECT, 5.0)))?.link)
    }

    @Test fun aPathThatIsAwayIsReplacedImmediately() {
        val p = MeasuredPolicy(clock = { 0 })
        p.choose(listOf(path(LinkType.DIRECT, 5.0), path(LinkType.LAN, 20.0)))
        val after = p.choose(listOf(path(LinkType.DIRECT, 5.0, LinkState.AWAY), path(LinkType.LAN, 20.0)))
        assertEquals(LinkType.LAN, after?.link)
    }

    @Test fun bulkFollowsMeasuredMbpsAndNeverUsesBluetooth() {
        val p = MeasuredPolicy(clock = { 0 })
        val paths = listOf(path(LinkType.LAN, 8.0, mbps = 100.0), path(LinkType.DIRECT, 20.0, mbps = 400.0), path(LinkType.BT, 30.0))
        assertEquals(LinkType.DIRECT, p.choose(paths, Use.BULK)?.link)
        assertEquals(listOf(LinkType.DIRECT, LinkType.LAN), p.order(paths, Use.BULK).map { it.link })
    }

    @Test fun bluetoothCarriesSmallRequestsOnlyWhenNoWifiPathIsUp() {
        val p = MeasuredPolicy(clock = { 0 })
        val both = listOf(path(LinkType.LAN, 50.0), path(LinkType.BT, 30.0))
        assertEquals(LinkType.LAN, p.choose(both)?.link)
        val btOnly = listOf(path(LinkType.LAN, 50.0, LinkState.AWAY), path(LinkType.BT, 30.0))
        assertEquals(LinkType.BT, p.choose(btOnly)?.link)
    }

    @Test fun ewmaSeedsThenSmooths() {
        assertEquals(10.0, PeerLinks.ewma(null, 10.0), 1e-9)
        assertEquals(13.0, PeerLinks.ewma(10.0, 20.0), 1e-9)
    }
}

class BulkTest {
    private fun loopback(server: TputServer): Int = server.start()

    @Test fun reassemblerDeliversInOrderWhateverTheArrivalOrder() {
        val seen = mutableListOf<Int>()
        val r = Reassembler(6) { i, _ -> seen += i }
        for (i in listOf(2, 0, 1, 5, 3, 4)) r.accept(i, ByteArray(1))
        assertEquals(listOf(0, 1, 2, 3, 4, 5), seen)
        assertEquals(6, r.delivered)
    }

    @Test fun reassemblerRejectsDuplicatesAndStrays() {
        val r = Reassembler(3) { _, _ -> }
        r.accept(0, ByteArray(1))
        for (bad in listOf(0, 7, -2)) {
            try { r.accept(bad, ByteArray(1)); fail("accepted $bad") } catch (_: IOException) {}
        }
    }

    @Test fun stripedTransferOverFourStreamsArrivesCompleteAndChecked() {
        val server = TputServer(0, { SECRET })
        val port = loopback(server)
        try {
            var progressed = 0L
            val sender = StripedSender({ SECRET }, listOf({ Socket("127.0.0.1", port) }), listOf("LAN"))
            val r = sender.send(megabytes = 16, streams = 4) { progressed = maxOf(progressed, it) }
            assertEquals(16L * 1024 * 1024, r.bytes)
            assertEquals("no CRC failures", 0, r.badChunks)
            assertEquals("every chunk delivered in order", 0, r.missing)
            assertTrue(r.mbpsReceiver > 0)
            assertEquals(16L * 1024 * 1024, progressed)
            assertEquals(1, r.perPath.size)
            assertEquals(16L * 1024 * 1024, r.perPath[0].bytes)
        } finally { server.stop() }
    }

    @Test fun twoPathsShareOneTransferAndTheShareIsReported() {
        val server = TputServer(0, { SECRET })
        val port = loopback(server)
        try {
            val sender = StripedSender(
                { SECRET },
                listOf({ Socket("127.0.0.1", port) }, { Socket("127.0.0.1", port) }), listOf("LAN", "DIRECT"),
            )
            val r = sender.send(megabytes = 8, streams = 4)
            assertEquals(8L * 1024 * 1024, r.bytes)
            assertEquals(0, r.missing)
            assertEquals(8L * 1024 * 1024, r.perPath.sumOf { it.bytes })
            assertEquals(listOf(2, 2), r.perPath.map { it.streams })
        } finally { server.stop() }
    }

    @Test fun aCorruptedChunkIsCounted() {
        val server = TputServer(0, { SECRET })
        val port = loopback(server)
        try {
            Socket("127.0.0.1", port).use { s ->
                val out = DataOutputStream(s.getOutputStream())
                out.write(TputAuth.token(SECRET, Striped.MODE))
                Striped.writeHeader(out, Striped.Header(java.util.UUID.randomUUID(), 1, 0, 1, 1024, 1024))
                out.writeInt(0); out.writeInt(1024); out.writeInt(0x12345678) // wrong CRC
                out.write(ByteArray(1024))
                out.writeInt(Striped.END); out.flush()
                val inp = DataInputStream(s.getInputStream())
                val bytes = inp.readLong(); inp.readLong(); val bad = inp.readInt(); val missing = inp.readInt()
                assertEquals(1024L, bytes); assertEquals(1, bad); assertEquals(0, missing)
            }
        } finally { server.stop() }
    }
}
