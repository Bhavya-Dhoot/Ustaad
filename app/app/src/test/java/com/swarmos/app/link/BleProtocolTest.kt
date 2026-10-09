package com.swarmos.app.link

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class BleFrameTest {
    private fun roundTrip(sizes: List<Int>, mtu: Int): List<ByteArray> {
        val got = mutableListOf<ByteArray>()
        val r = BleReassembler { got += it }
        for (n in sizes) for (frag in BleFrame.split(ByteArray(n) { (it * 7).toByte() }, mtu)) r.feed(frag)
        return got
    }

    @Test fun messagesComeBackWholeWhateverTheFragmentSize() {
        for (mtu in listOf(1, 3, 20, 244, 512)) {
            val out = roundTrip(listOf(0, 1, 18, 19, 20, 21, 243, 244, 245, 1000, 5000), mtu)
            assertEquals("mtu $mtu", listOf(0, 1, 18, 19, 20, 21, 243, 244, 245, 1000, 5000), out.map { it.size })
            out.forEach { m -> assertArrayEquals(ByteArray(m.size) { (it * 7).toByte() }, m) }
        }
    }

    @Test fun fragmentsAreAtMostTheRequestedSize() {
        val frags = BleFrame.split(ByteArray(1000), 100)
        assertTrue(frags.all { it.size <= 100 })
        assertEquals(1002, frags.sumOf { it.size }) // 2 length bytes
    }

    @Test fun twoMessagesInOneFragmentAreSplitApart() {
        val a = BleFrame.split("hello".toByteArray(), 100).single()
        val b = BleFrame.split("world!".toByteArray(), 100).single()
        val got = mutableListOf<String>()
        BleReassembler { got += String(it) }.feed(a + b)
        assertEquals(listOf("hello", "world!"), got)
    }

    @Test fun anOversizedMessageIsRefused() {
        try { BleFrame.split(ByteArray(BleFrame.MAX_MESSAGE + 1), 20); fail() } catch (_: IllegalArgumentException) {}
    }
}

class BleRpcTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Two ends joined by in-memory pipes that fragment every message to [mtu] bytes, as the radio does. */
    private class Wire(scope: CoroutineScope, mtu: Int, handlerA: suspend (BleMsg) -> BleMsg, handlerB: suspend (BleMsg) -> BleMsg) {
        lateinit var a: BleRpc
        lateinit var b: BleRpc
        val reA = BleReassembler { a.onMessage(it) }
        val reB = BleReassembler { b.onMessage(it) }
        init {
            // one writer at a time per direction, like the channel's send lock, so fragments of two messages never interleave
            val lockAB = kotlinx.coroutines.sync.Mutex(); val lockBA = kotlinx.coroutines.sync.Mutex()
            a = BleRpc(scope, send = { bytes -> lockAB.withLock { BleFrame.split(bytes, mtu).forEach { reB.feed(it) } } }, handler = handlerA)
            b = BleRpc(scope, send = { bytes -> lockBA.withLock { BleFrame.split(bytes, mtu).forEach { reA.feed(it) } } }, handler = handlerB)
        }
    }

    private val echo: suspend (BleMsg) -> BleMsg = { BleMsg(t = "rsp", s = 200, b = "echo:" + (it.p ?: "") + ":" + (it.b ?: "")) }

    @Test fun aRequestGetsItsResponseAcrossFragments() = runBlocking {
        val p = Wire(scope, 20, echo, echo)
        val body = "x".repeat(900)
        val rsp = p.a.call(BleMsg(t = "req", m = "POST", p = "/v1/link/echo", b = body), 5_000)
        assertEquals(200, rsp.s)
        assertEquals("echo:/v1/link/echo:$body", rsp.b)
    }

    @Test fun manyCallsInFlightKeepTheirOwnAnswersBothWays() = runBlocking {
        val p = Wire(scope, 23, echo, echo)
        val results = (1..40).map { i ->
            async { (if (i % 2 == 0) p.a else p.b).call(BleMsg(t = "req", m = "GET", p = "/n/$i"), 5_000).b }
        }.awaitAll()
        assertEquals((1..40).map { "echo:/n/$it:" }, results)
    }

    @Test fun aHandlerThatThrowsAnswers500() = runBlocking {
        val p = Wire(scope, 100, { throw IllegalStateException("boom") }, echo)
        val rsp = p.b.call(BleMsg(t = "req", m = "GET", p = "/x"), 5_000)
        assertEquals(500, rsp.s)
        assertTrue(rsp.b!!.contains("boom"))
    }

    @Test fun aCallTimesOutWhenNobodyAnswers() = runBlocking {
        val silent: suspend (BleMsg) -> BleMsg = { delay(5_000); BleMsg(t = "rsp", s = 200) }
        val p = Wire(scope, 100, silent, silent)
        try { p.a.call(BleMsg(t = "req", m = "GET", p = "/slow"), 150); fail("expected a timeout") } catch (e: IOException) { assertTrue(e.message!!.contains("timed out")) }
    }

    @Test fun closingFailsTheCallsStillWaiting() = runBlocking {
        val silent: suspend (BleMsg) -> BleMsg = { delay(5_000); BleMsg(t = "rsp", s = 200) }
        val p = Wire(scope, 100, silent, silent)
        val pending = async { runCatching { p.a.call(BleMsg(t = "req", m = "GET", p = "/slow"), 4_000) } }
        delay(100)
        p.a.close()
        assertTrue(pending.await().exceptionOrNull() is IOException)
    }

    @Test fun identityMessagesReachTheOtherSide() = runBlocking {
        var seen: BleMsg? = null
        lateinit var a: BleRpc
        lateinit var b: BleRpc
        val reB = BleReassembler { b.onMessage(it) }
        a = BleRpc(scope, send = { BleFrame.split(it, 20).forEach(reB::feed) }, handler = echo)
        b = BleRpc(scope, send = {}, handler = echo, onIdentity = { seen = it })
        a.sendIdentity("c6f72247", "Phone A")
        assertEquals("c6f72247", seen?.n)
        assertEquals("Phone A", seen?.nm)
    }
}

class BleAdvTest {
    private val secret = "swarmos-dev-secret"
    private val now = 1_800_000_000_000L

    @Test fun nodeIdBytesRoundTrip() {
        assertEquals("c6f72247", BleAdv.nodeIdHex(BleAdv.nodeIdBytes("c6f72247")))
    }

    @Test fun aPairedPhoneIsRecognisedWithItsFlags() {
        val payload = BleAdv.encode(secret, "477684c1", BleAdv.FLAG_DIRECT_HOST or BleAdv.FLAG_FOREGROUND, now)
        assertEquals(BleAdv.PAYLOAD, payload.size)
        val s = BleAdv.decode(secret, payload, now + 10_000)!!
        assertEquals("477684c1", s.nodeId)
        assertEquals(3, s.flags)
    }

    @Test fun aPhoneWithAnotherSecretIsIgnored() {
        val payload = BleAdv.encode("someone-elses-secret", "477684c1", 0, now)
        assertNull(BleAdv.decode(secret, payload, now))
    }

    @Test fun aTamperedNodeIdFailsTheTag() {
        val payload = BleAdv.encode(secret, "477684c1", 0, now)
        payload[0] = (payload[0] + 1).toByte()
        assertNull(BleAdv.decode(secret, payload, now))
    }

    @Test fun theTagRotatesEveryMinuteWithOneMinuteOfSkewAllowed() {
        val payload = BleAdv.encode(secret, "477684c1", 0, now)
        assertNotNull(BleAdv.decode(secret, payload, now + 60_000))
        assertNotNull(BleAdv.decode(secret, payload, now - 60_000))
        assertNull(BleAdv.decode(secret, payload, now + 180_000))
    }

    @Test fun shortPayloadIsRejected() {
        assertNull(BleAdv.decode(secret, ByteArray(5), now))
    }
}

class SecretBoxTest {
    private val creds = """{"ssid":"DIRECT-sw-c6f72247","pass":"hPzyjAfcdSQm"}""".toByteArray()

    @Test fun roundTripWithTheSameSecretAndOwner() {
        val blob = SecretBox.seal("s3", creds, "c6f72247")
        assertArrayEquals(creds, SecretBox.open("s3", blob, "c6f72247"))
    }

    @Test fun wrongSecretOrWrongOwnerCannotOpenIt() {
        val blob = SecretBox.seal("s3", creds, "c6f72247")
        assertNull(SecretBox.open("other", blob, "c6f72247"))
        assertNull("bound to its owner", SecretBox.open("s3", blob, "477684c1"))
        assertNull(SecretBox.open("s3", "not base64 !!", "c6f72247"))
    }

    @Test fun everySealIsDifferentAndHidesThePassphrase() {
        val a = SecretBox.seal("s3", creds, "n")
        val b = SecretBox.seal("s3", creds, "n")
        assertFalse(a == b)
        assertFalse(String(java.util.Base64.getDecoder().decode(a)).contains("hPzyjAfcdSQm"))
    }
}
