package com.swarmos.app.link

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class BleReassemblerResyncTest {
    @Test fun aDeclaredLengthAboveSixteenKiBIsRejectedAndTheBufferCleared() {
        val got = mutableListOf<ByteArray>()
        val rejected = mutableListOf<Int>()
        val r = BleReassembler(onReject = { rejected += it }) { got += it }
        r.feed(byteArrayOf(0x40, 0x01, 1, 2, 3)) // 0x4001 = 16385 bytes announced
        assertEquals(listOf(16385), rejected)
        assertTrue(got.isEmpty())
        // the junk is gone: a well formed frame now parses from a clean start
        BleFrame.split("ok".toByteArray(), 20).forEach(r::feed)
        assertEquals(listOf("ok"), got.map { String(it) })
    }

    @Test fun theLargestMessageTheSenderMayFrameIsStillAccepted() {
        val got = mutableListOf<ByteArray>()
        val r = BleReassembler(onReject = { fail("rejected a valid length $it") }) { got += it }
        BleFrame.split(ByteArray(BleFrame.MAX_MESSAGE), 244).forEach(r::feed)
        assertEquals(1, got.size)
        assertEquals(16 * 1024, got[0].size)
    }

    @Test fun oneLostFragmentIsDetectedBecauseTheShiftedStreamSoonShowsAnImpossibleLength() {
        val delivered = mutableListOf<ByteArray>()
        var rejected = 0
        val r = BleReassembler(onReject = { rejected++ }) { delivered += it }
        val a = BleFrame.split(ByteArray(100) { 'x'.code.toByte() }, 20).toMutableList()
        val b = BleFrame.split(ByteArray(100) { 0x7F }, 20)
        a.removeAt(2) // the radio lost one fragment of the first message
        for (f in a + b) {
            if (rejected > 0) break // the owner drops the channel at the first rejection: no more fragments arrive
            r.feed(f)
        }
        assertEquals("the shifted stream ends in a length no sender produces", 1, rejected)
        assertEquals("the one garbled message that was cut from the shifted stream is the only thing delivered", 1, delivered.size)
    }
}

class BleStallTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val echo: suspend (BleMsg) -> BleMsg = { BleMsg(t = "rsp", s = 200, b = "ok") }

    @Test fun twoRequestsInARowWithoutAnswerStallTheChannel() = runBlocking {
        var stalled = 0
        val rpc = BleRpc(scope, send = {}, handler = echo, onStalled = { stalled++ })
        runCatching { rpc.call(BleMsg(t = "req", m = "GET", p = "/a"), 60) }
        assertEquals("one timeout is bad luck", 0, stalled)
        runCatching { rpc.call(BleMsg(t = "req", m = "GET", p = "/a"), 60) }
        assertEquals("the second in a row stalls it", 1, stalled)
        assertEquals(2, rpc.consecutiveUnanswered)
        assertEquals(2, BleRpc.STALL_AFTER)
    }

    @Test fun anAnswerInBetweenStartsTheCountOver() = runBlocking {
        var stalled = 0
        lateinit var a: BleRpc
        lateinit var b: BleRpc
        val reA = BleReassembler { a.onMessage(it) }
        val reB = BleReassembler { b.onMessage(it) }
        val lockAB = Mutex(); val lockBA = Mutex()
        val slowOrFast: suspend (BleMsg) -> BleMsg = { if (it.p == "/slow") { delay(5_000); BleMsg(t = "rsp", s = 200) } else BleMsg(t = "rsp", s = 200, b = "ok") }
        a = BleRpc(scope, send = { bytes -> lockAB.withLock { BleFrame.split(bytes, 100).forEach { reB.feed(it) } } }, handler = echo, onStalled = { stalled++ })
        b = BleRpc(scope, send = { bytes -> lockBA.withLock { BleFrame.split(bytes, 100).forEach { reA.feed(it) } } }, handler = slowOrFast)
        runCatching { a.call(BleMsg(t = "req", m = "GET", p = "/slow"), 80) }
        assertEquals(1, a.consecutiveUnanswered)
        assertEquals("ok", a.call(BleMsg(t = "req", m = "GET", p = "/fast"), 2_000).b)
        assertEquals("a response resets the count", 0, a.consecutiveUnanswered)
        runCatching { a.call(BleMsg(t = "req", m = "GET", p = "/slow"), 80) }
        assertEquals("one timeout after the answer is not a stall", 0, stalled)
        runCatching { a.call(BleMsg(t = "req", m = "GET", p = "/slow"), 80) }
        assertEquals(1, stalled)
    }

    @Test fun aWriteThatCannotBeSentCountsAsUnanswered() = runBlocking {
        var stalled = 0
        val rpc = BleRpc(scope, send = { throw IOException("bluetooth write timed out") }, handler = echo, onStalled = { stalled++ })
        repeat(2) { runCatching { rpc.call(BleMsg(t = "req", m = "GET", p = "/a"), 1_000) } }
        assertEquals(1, stalled)
    }
}

class BlePolicyTest {
    @Test fun theHandshakeIsRetriedEveryFiveSecondsUntilItWorks() = runBlocking {
        val results = ArrayDeque(listOf(false, false, true))
        val pauses = mutableListOf<Long>()
        var gaveUp = 0
        val ok = BlePolicy.handshake(isUp = { true }, attempt = { results.removeFirst() }, onGiveUp = { gaveUp++ }, pause = { pauses += it })
        assertTrue(ok)
        assertEquals(listOf(5_000L, 5_000L), pauses)
        assertEquals(0, gaveUp)
    }

    @Test fun afterThreeFailuresTheChannelIsGivenUp() = runBlocking {
        var attempts = 0
        var gaveUp = 0
        val pauses = mutableListOf<Long>()
        val ok = BlePolicy.handshake(isUp = { true }, attempt = { attempts++; false }, onGiveUp = { gaveUp++ }, pause = { pauses += it })
        assertFalse(ok)
        assertEquals(3, attempts)
        assertEquals(1, gaveUp)
        assertEquals("waits between tries, not after the last", 2, pauses.size)
    }

    @Test fun aChannelThatWentDownStopsTheRetriesWithoutGivingUp() = runBlocking {
        var up = true
        var attempts = 0
        var gaveUp = 0
        val ok = BlePolicy.handshake(isUp = { up }, attempt = { attempts++; up = false; false }, onGiveUp = { gaveUp++ }, pause = {})
        assertFalse(ok)
        assertEquals(1, attempts)
        assertEquals(0, gaveUp)
    }

    @Test fun scanIsRestartedEveryTenMinutes() {
        assertFalse(BlePolicy.scanRestartDue(0, 10 * 60_000L - 1))
        assertTrue(BlePolicy.scanRestartDue(0, 10 * 60_000L))
    }
}

class DialBackoffTest {
    @Test fun redialsBackOffFromTwoSecondsToThirtySeconds() {
        val b = DialBackoff()
        var now = 1_000_000L
        val waits = mutableListOf<Long>()
        repeat(7) {
            assertTrue(b.due(now))
            b.onDial(now)
            waits += b.waitMs(now)
            assertFalse("not due again straight away", b.due(now))
            now += b.waitMs(now)
        }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L), waits)
    }

    @Test fun aGoodHandshakeStartsTheBackoffOver() {
        val b = DialBackoff()
        b.onDial(0); b.onDial(10_000); b.onDial(30_000)
        assertEquals(8_000L, b.waitMs(30_000))
        b.onSuccess()
        assertTrue(b.due(30_001))
        b.onDial(40_000)
        assertEquals(2_000L, b.waitMs(40_000))
    }
}
