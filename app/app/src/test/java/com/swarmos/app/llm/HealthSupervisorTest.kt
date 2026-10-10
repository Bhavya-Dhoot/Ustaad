package com.swarmos.app.llm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The ping loop against a stand-in llama-server on a real socket. "Frozen" is what `kill -STOP` does to the real one: the kernel
 * still completes the TCP handshake from the backlog, the server never reads the request and never answers.
 */
class HealthSupervisorTest {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val servers = CopyOnWriteArrayList<ServerSocket>()

    @After fun tearDown() {
        scope.cancel()
        sockets.forEach { runCatching { it.close() } }
        servers.forEach { runCatching { it.close() } }
    }

    /** Accepts connections, keeps them open and never answers (a frozen server). Returns the port. */
    private fun frozenServer(): Int {
        val s = ServerSocket(0, 50, InetAddress.getLoopbackAddress()).also { servers += it }
        thread(isDaemon = true) {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: Exception) { break }
                sockets += c // held open, never read from, never written to
            }
        }
        return s.localPort
    }

    /** Answers every request with 200 {"status":"ok"} and closes. */
    private fun healthyServer(hits: AtomicInteger): Int {
        val s = ServerSocket(0, 50, InetAddress.getLoopbackAddress()).also { servers += it }
        thread(isDaemon = true) {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: Exception) { break }
                hits.incrementAndGet()
                try {
                    val ins = c.getInputStream()
                    val request = StringBuilder()
                    while (!request.endsWith("\r\n\r\n")) request.append(ins.read().also { if (it < 0) error("closed") }.toChar())
                    val body = """{"status":"ok"}"""
                    c.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray()); flush() }
                } catch (_: Exception) {
                }
                runCatching { c.close() }
            }
        }
        return s.localPort
    }

    private val period = 200L

    /** The loop with the real probe (short timeouts) against [port]; [log] collects what it says. */
    private fun supervisor(
        port: Int, killed: CountDownLatch, log: MutableList<String> = CopyOnWriteArrayList(),
        busy: () -> Boolean = { false }, endedBecause: () -> String? = { null },
        probe: () -> Boolean = { LlamaHttp.healthOk(port, connectTimeoutMs = 200, readTimeoutMs = 300) },
    ) = HealthSupervisor(period, probe, busy, endedBecause, kill = { killed.countDown() }, log = { log += it })

    @Test fun aFrozenServerThatStillAcceptsConnectionsIsKilledAfterAboutThreePingPeriods() {
        val port = frozenServer()
        val killed = CountDownLatch(1)
        val log = CopyOnWriteArrayList<String>()
        val t0 = System.nanoTime()
        scope.launch { supervisor(port, killed, log).run() }
        assertTrue("never killed; log: $log", killed.await(10, TimeUnit.SECONDS))
        val ms = (System.nanoTime() - t0) / 1_000_000
        // three periods of 200 ms plus three probes that each wait for the 300 ms read timeout, with generous slack for a slow machine
        assertTrue("killed after $ms ms", ms in 1_200..6_000)
        assertEquals(1, log.count { it.startsWith("health ping missed (1 of 3") })
        assertEquals(1, log.count { it.startsWith("health ping missed (2 of 3") })
        assertEquals(1, log.count { it.startsWith("health ping missed (3 of 3") })
        assertTrue(log.toString(), log.last().contains("killing the server"))
    }

    @Test fun aProbeOnAFrozenServerGivesUpAtItsReadTimeoutInsteadOfHangingForever() {
        val port = frozenServer()
        val t0 = System.nanoTime()
        assertFalse(LlamaHttp.healthOk(port, connectTimeoutMs = 200, readTimeoutMs = 300))
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("the probe took $ms ms", ms < 3_000)
    }

    @Test fun theProductionTimeoutsCountAMissWithinTheirOwnLimit() {
        val port = frozenServer()
        val t0 = System.nanoTime()
        assertFalse(LlamaHttp.healthOk(port)) // 2 s connect, 3 s read, as the runtime pings
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("the probe took $ms ms", ms in 2_500..8_000)
    }

    @Test fun aHealthyServerIsNeverKilled() {
        val hits = AtomicInteger()
        val port = healthyServer(hits)
        val killed = CountDownLatch(1)
        scope.launch { supervisor(port, killed).run() }
        assertFalse(killed.await(2, TimeUnit.SECONDS))
        assertTrue("the server was pinged ${hits.get()} times", hits.get() >= 4)
    }

    @Test fun aFrozenServerWithAYoungCallInFlightIsNotKilledYet() {
        val port = frozenServer()
        val killed = CountDownLatch(1)
        scope.launch { supervisor(port, killed, busy = { true }).run() }
        assertFalse(killed.await(2, TimeUnit.SECONDS))
    }

    @Test fun theLoopEndsWithoutKillingWhenTheServerWasStoppedOrReplaced() {
        val port = frozenServer()
        val killed = CountDownLatch(1)
        val done = CountDownLatch(1)
        val log = CopyOnWriteArrayList<String>()
        scope.launch { supervisor(port, killed, log, endedBecause = { "a newer start or a stop took over" }).run(); done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(1, killed.count)
        assertTrue(log.toString(), log.last().contains("a newer start or a stop took over"))
    }

    @Test fun aServerThatRecoversBetweenPingsStartsTheCountAgain() {
        val killed = CountDownLatch(1)
        val answers = ArrayDeque(listOf(false, false, true, false, false, true, true, true, true, true))
        val log = CopyOnWriteArrayList<String>()
        scope.launch { supervisor(0, killed, log, probe = { answers.removeFirstOrNull() ?: true }).run() }
        assertFalse(killed.await(2, TimeUnit.SECONDS))
        assertTrue(log.toString(), log.count { it.startsWith("health ping missed (2 of 3") } == 2) // never a third in a row
    }

    @Test fun aTickThatComesMuchLaterThanItsPeriodIsLoggedAsSleepOrFreeze() {
        val killed = CountDownLatch(1)
        val log = CopyOnWriteArrayList<String>()
        var wall = 0L
        val s = HealthSupervisor(
            period, probe = { false }, busy = { false }, endedBecause = { null }, kill = { killed.countDown() }, log = { log += it },
            wallClockMs = { wall += 5_000; wall }, // every tick looks 5 s late against a 200 ms period
        )
        scope.launch { s.run() }
        assertTrue(killed.await(5, TimeUnit.SECONDS))
        assertTrue(log.toString(), log.any { it.contains("the phone slept or the app was frozen") })
    }

    @Test fun aCallExcusesSilenceOnlyForALimitedTimeWhateverItsOwnTimeoutIs() {
        val port = 59_020
        val t0 = 1_000_000_000L
        val token = InFlight.begin(port, timeoutMs = 600_000, nowNs = t0) // a stream: ten minutes of read timeout
        try {
            assertTrue(InFlight.busyWithinTimeout(port, nowNs = t0 + 59_000_000_000L))
            assertFalse(InFlight.busyWithinTimeout(port, nowNs = t0 + 61_000_000_000L)) // still in flight and inside its timeout, no longer an excuse
            assertTrue(InFlight.active(port))
        } finally {
            InFlight.end(token, nowNs = t0 + 62_000_000_000L)
        }
    }
}
