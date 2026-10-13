package com.swarmos.app.cloud

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class FramesTest {
    private val dirKey = Crypto.random(32)
    private val ctx = "req-1".toByteArray()

    private fun sealer(stream: Int = 7, context: ByteArray = ctx, type: Int = FrameType.EVENT, key: ByteArray = dirKey, aead: Aead = Aead.AES_256_GCM) =
        FrameSealer(key, stream, context, type, aead)

    private fun opener(stream: Int = 7, context: ByteArray = ctx, type: Int = FrameType.EVENT, key: ByteArray = dirKey, aead: Aead = Aead.AES_256_GCM) =
        FrameOpener(key, stream, context, type, aead)

    private fun flip(b: ByteArray, at: Int) = b.copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() }

    private fun threeFrames(s: FrameSealer = sealer()) =
        listOf(s.seal("one".toByteArray()), s.seal("two".toByteArray()), s.seal("three".toByteArray(), final = true))

    // ---- format and interop with the JDK ------------------------------------------------------------------------------

    @Test fun headerLayoutIsTypeFlagsStreamPosition() {
        val f = sealer(stream = 0x01020304).seal(byteArrayOf(9), final = true)
        assertEquals(14 + 1 + 16, f.size)
        assertEquals(FrameType.EVENT, f[0].toInt())
        assertEquals(1, f[1].toInt()) // final
        assertEquals("01020304", Hex.of(f.copyOfRange(2, 6)))
        assertEquals("0000000000000000", Hex.of(f.copyOfRange(6, 14))) // counter 0
        val h = FrameHeader.parse(f)
        assertEquals(0x01020304, h.stream); assertEquals(0L, h.position); assertTrue(h.final)
        assertEquals(1, FrameHeader.parse(sealer().also { it.seal(byteArrayOf(1)) }.seal(byteArrayOf(2))).position.toInt())
    }

    @Test fun ciphertextMatchesAnIndependentJdkComputationWithTheDocumentedLayout() {
        val stream = 5
        val f = sealer(stream = stream).seal("hello".toByteArray(), final = true)
        // streamKey, nonce and associated data exactly as written in docs/CLOUD-PROTOCOL.md section 10
        val info = concat(lbl("frame/stream"), Writer().u8(1).u32(stream).lp(ctx).bytes())
        val key = Crypto.hkdfExpand(dirKey, info, 32)
        val nonce = ByteArray(12) // 4 zero bytes then the 64-bit counter 0
        val header = f.copyOfRange(0, 14)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(concat(Writer().str("ustaad/v1/frame").bytes(), header))
        assertArrayEquals(c.doFinal("hello".toByteArray()), f.copyOfRange(14, f.size))
    }

    @Test fun chacha20Poly1305FramesWorkAndMatchTheJdk() {
        val s = sealer(aead = Aead.CHACHA20_POLY1305)
        val frames = threeFrames(s)
        val o = opener(aead = Aead.CHACHA20_POLY1305)
        assertEquals("one", String(o.open(frames[0]))); assertEquals("two", String(o.open(frames[1]))); assertEquals("three", String(o.open(frames[2])))
        o.end()
        // an independent computation
        val info = concat(lbl("frame/stream"), Writer().u8(2).u32(7).lp(ctx).bytes())
        val key = Crypto.hkdfExpand(dirKey, info, 32)
        val c = Cipher.getInstance("ChaCha20-Poly1305")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(ByteArray(12)))
        c.updateAAD(concat(Writer().str("ustaad/v1/frame").bytes(), frames[0].copyOfRange(0, 14)))
        assertEquals("one", String(c.doFinal(frames[0].copyOfRange(14, frames[0].size))))
        // the two ciphers do not open each other's frames
        assertError(CloudError.BAD_FRAME) { opener(aead = Aead.AES_256_GCM).open(frames[0]) }
    }

    // ---- ordered streams -------------------------------------------------------------------------------------------

    @Test fun aStreamOfFramesRoundTripsAndEndsComplete() {
        val frames = threeFrames()
        val o = opener()
        assertEquals("one", String(o.open(frames[0])))
        assertFalse(o.finished)
        assertEquals("two", String(o.open(frames[1])))
        assertEquals("three", String(o.open(frames[2])))
        assertTrue(o.finished)
        o.end()
    }

    @Test fun anEmptyFinalFrameIsAValidBody() {
        val f = sealer().seal(ByteArray(0), final = true)
        assertEquals(0, opener().open(f).size)
    }

    @Test fun aReplayedFrameIsRefusedAndKillsTheStream() {
        val frames = threeFrames()
        val o = opener()
        o.open(frames[0])
        assertError(CloudError.OUT_OF_ORDER) { o.open(frames[0]) }
        assertError(CloudError.BAD_FRAME) { o.open(frames[1]) } // dead after the failure
    }

    @Test fun reorderedAndDroppedFramesAreRefused() {
        val frames = threeFrames()
        assertError(CloudError.OUT_OF_ORDER) { opener().open(frames[1]) } // second first
        val o = opener()
        o.open(frames[0])
        assertError(CloudError.OUT_OF_ORDER) { o.open(frames[2]) } // the middle one was deleted on the way
    }

    @Test fun aStreamThatEndsWithoutItsFinalFrameIsTruncated() {
        val frames = threeFrames()
        val o = opener()
        o.open(frames[0]); o.open(frames[1])
        assertError(CloudError.TRUNCATED) { o.end() }
        // nothing at all arrived
        assertError(CloudError.TRUNCATED) { opener().end() }
        // a failed stream is not complete either
        val bad = opener()
        assertError(CloudError.BAD_FRAME) { bad.open(flip(frames[0], 20)) }
        assertError(CloudError.TRUNCATED) { bad.end() }
    }

    @Test fun nothingFollowsTheFinalFrame() {
        val s = sealer()
        val last = s.seal("x".toByteArray(), final = true)
        var refused = false
        try { s.seal("y".toByteArray()) } catch (e: IllegalStateException) { refused = true }
        assertTrue(refused)
        val o = opener()
        o.open(last)
        assertError(CloudError.BAD_FRAME) { o.open(last) }
    }

    @Test fun aTamperedTagCiphertextOrHeaderIsRefused() {
        val f = sealer().seal("payload".toByteArray(), final = false)
        for (at in listOf(f.size - 1, f.size - 17, 14, 1, 2, 5)) { // tag, last ciphertext byte, first ciphertext byte, flags, stream
            assertError(CloudError.BAD_FRAME) { opener().open(flip(f, at)) }
        }
        // the final flag cannot be added or removed
        val nonFinal = sealer().seal("a".toByteArray(), final = false)
        assertError(CloudError.BAD_FRAME) { opener().open(nonFinal.copyOf().also { it[1] = 1 }) }
        // a counter changed to the one expected next is caught by the tag, a different one by the sequence rule
        assertError(CloudError.OUT_OF_ORDER) { opener().open(f.copyOf().also { it[13] = 5 }) }
        assertError(CloudError.BAD_FRAME) { opener().open(f.copyOf(20)) }
        assertError(CloudError.BAD_FRAME) { opener().open(ByteArray(3)) }
    }

    @Test fun aFrameMovedToAnotherStreamContextTypeOrDirectionIsRefused() {
        val f = sealer().seal("secret".toByteArray(), final = true)
        assertEquals("secret", String(opener().open(f)))
        assertError(CloudError.BAD_FRAME) { opener(stream = 8).open(f) } // another stream of the same request
        assertError(CloudError.BAD_FRAME) { opener(context = "req-2".toByteArray()).open(f) } // another request
        assertError(CloudError.BAD_FRAME) { opener(type = FrameType.RESPONSE).open(f) } // another type
        assertError(CloudError.BAD_FRAME) { opener(key = Crypto.random(32)).open(f) } // another direction or session
        // a response sealed for request A does not open as the answer to request B
        val answer = sealer(stream = 1, context = "A".toByteArray(), type = FrameType.RESPONSE).seal("42".toByteArray(), final = true)
        assertError(CloudError.BAD_FRAME) { opener(stream = 1, context = "B".toByteArray(), type = FrameType.RESPONSE).open(answer) }
    }

    @Test fun oneShotBodiesAreASingleFinalFrame() {
        val key = Crypto.random(32)
        val f = Frames.sealOnce(key, 3, ctx, FrameType.REQUEST, "body".toByteArray())
        assertEquals("body", String(Frames.openOnce(key, 3, ctx, FrameType.REQUEST, f)))
        val notFinal = FrameSealer(key, 3, ctx, FrameType.REQUEST).seal("body".toByteArray(), final = false)
        assertError(CloudError.TRUNCATED) { Frames.openOnce(key, 3, ctx, FrameType.REQUEST, notFinal) }
    }

    @Test fun largeBodiesAndTheSizeLimitsHold() {
        val big = Crypto.random(1_000_000)
        val f = sealer().seal(big, final = true)
        assertArrayEquals(big, opener().open(f))
        var refused = false
        try { FrameSealer(dirKey, 1, ctx, FrameType.EVENT, maxPlain = 100).seal(ByteArray(101)) } catch (e: IllegalArgumentException) { refused = true }
        assertTrue(refused)
        val limited = FrameOpener(dirKey, 7, ctx, FrameType.EVENT, maxPlain = 100)
        assertError(CloudError.BAD_FRAME) { limited.open(sealer().seal(ByteArray(200))) }
    }

    @Test fun eachStreamHasItsOwnKeySoNonceZeroIsNeverReused() {
        val a = sealer(stream = 1).seal("same".toByteArray())
        val b = sealer(stream = 2).seal("same".toByteArray())
        val c = sealer(stream = 1, context = "other".toByteArray()).seal("same".toByteArray())
        assertNotEquals(Hex.of(a.copyOfRange(14, a.size)), Hex.of(b.copyOfRange(14, b.size)))
        assertNotEquals(Hex.of(a.copyOfRange(14, a.size)), Hex.of(c.copyOfRange(14, c.size)))
        // many frames of one stream: every counter and every ciphertext differs
        val s = sealer()
        val seen = HashSet<String>()
        repeat(200) { assertTrue(seen.add(Hex.of(s.seal("same".toByteArray())))) }
    }

    // ---- positional (bulk) streams ---------------------------------------------------------------------------------------

    private fun bulkPair(stream: Int = 1, context: ByteArray = "bulk-1".toByteArray(), aead: Aead = Aead.AES_256_GCM) =
        BulkSealer(dirKey, stream, context, aead) to BulkOpener(dirKey, stream, context, aead)

    @Test fun bulkChunksArriveInAnyOrderAndTheResumePointIsTheFirstGap() {
        val (s, o) = bulkPair()
        val data = Crypto.random(10)
        val c0 = s.seal(0, data.copyOfRange(0, 4))
        val c1 = s.seal(4, data.copyOfRange(4, 8))
        val c2 = s.seal(8, data.copyOfRange(8, 10), final = true)
        val out = ByteArray(10)
        for (f in listOf(c2, c0)) { val c = o.open(f)!!; System.arraycopy(c.data, 0, out, c.offset.toInt(), c.data.size) }
        assertEquals(4, o.firstMissing()) // c0 is held, c1 is missing: a resume starts at 4
        assertFalse(o.complete())
        val last = o.open(c1)!!
        System.arraycopy(last.data, 0, out, 4, 4)
        assertTrue(o.complete())
        assertArrayEquals(data, out)
        assertEquals(10, o.firstMissing())
    }

    @Test fun aDuplicateBulkChunkIsIgnoredNotAnError() {
        val (s, o) = bulkPair()
        val f = s.seal(0, byteArrayOf(1, 2, 3))
        assertEquals(0L, o.open(f)!!.offset)
        assertNull(o.open(f))
    }

    @Test fun aBulkChunkIsBoundToItsOffsetStreamAndTransfer() {
        val (s, o) = bulkPair()
        val f = s.seal(1000, byteArrayOf(1, 2, 3))
        // moved to another offset: the header says 1001 but the tag was made for 1000
        assertError(CloudError.BAD_FRAME) { o.open(f.copyOf().also { it[13] = (it[13] + 1).toByte() }) }
        // moved to another stream or transfer
        assertError(CloudError.BAD_FRAME) { BulkOpener(dirKey, 2, "bulk-1".toByteArray()).open(f) }
        assertError(CloudError.BAD_FRAME) { BulkOpener(dirKey, 1, "bulk-2".toByteArray()).open(f) }
        // an ordered-stream frame is not a bulk chunk
        assertError(CloudError.BAD_FRAME) { o.open(sealer(stream = 1, context = "bulk-1".toByteArray()).seal(byteArrayOf(1))) }
        assertEquals(1000L, bulkPair().second.open(f)!!.offset)
    }

    @Test fun overlappingChunksAndChunksPastTheEndAreRefused() {
        val (s, o) = bulkPair()
        o.open(s.seal(0, ByteArray(10)))
        val overlap = BulkSealer(dirKey, 1, "bulk-1".toByteArray()).seal(5, ByteArray(10)) // starts inside the held chunk
        assertError(CloudError.BAD_FRAME) { o.open(overlap) }
        val (s2, o2) = bulkPair()
        o2.open(s2.seal(100, ByteArray(10), final = true)) // the payload ends at 110
        assertError(CloudError.BAD_FRAME) { o2.open(s2.seal(105, ByteArray(10))) }
        assertError(CloudError.BAD_FRAME) { o2.open(s2.seal(200, ByteArray(10))) }
    }

    @Test fun aSenderNeverSealsTheSameOffsetTwice() {
        val (s, _) = bulkPair()
        s.seal(0, byteArrayOf(1))
        var refused = false
        try { s.seal(0, byteArrayOf(2)) } catch (e: IllegalStateException) { refused = true }
        assertTrue(refused)
    }

    // ---- Codex Frames.kt:130, :117 and the response envelope (stage 1b) ----------------------------------------------------

    @Test fun aTruncatedStreamStaysFailedEvenIfTheMissingFinalFrameArrivesLater() { // Codex Frames.kt:130
        val frames = threeFrames()
        val o = opener()
        o.open(frames[0]); o.open(frames[1])
        assertError(CloudError.TRUNCATED) { o.end() }
        assertError(CloudError.BAD_FRAME) { o.open(frames[2]) } // the final frame is too late
        assertFalse(o.finished)
        assertError(CloudError.TRUNCATED) { o.end() } // and the stream never reports success
    }

    @Test fun aBulkStreamIsDeadAfterAnyError() { // Codex Frames.kt:130
        val (s, o) = bulkPair()
        val good = s.seal(0, byteArrayOf(1, 2, 3))
        assertError(CloudError.BAD_FRAME) { o.open(good.copyOf().also { it[good.size - 1] = (it[good.size - 1].toInt() xor 1).toByte() }) }
        assertError(CloudError.BAD_FRAME) { o.open(good) } // a valid chunk is refused afterwards
        assertFalse(o.complete())
    }

    @Test fun anAnswerStreamMayStartWithResponseAndContinueWithEvents() { // Codex Frames.kt:117
        val s = FrameSealer(dirKey, 9, ctx, FrameType.RESPONSE, thenType = FrameType.EVENT)
        val frames = listOf(s.seal("head".toByteArray()), s.seal("tok1".toByteArray()), s.seal("tok2".toByteArray(), final = true))
        assertEquals(FrameType.RESPONSE, frames[0][0].toInt()); assertEquals(FrameType.EVENT, frames[1][0].toInt()); assertEquals(FrameType.EVENT, frames[2][0].toInt())
        val o = FrameOpener(dirKey, 9, ctx, FrameType.RESPONSE, thenType = FrameType.EVENT)
        assertEquals("head", String(o.open(frames[0]))); assertEquals("tok1", String(o.open(frames[1]))); assertEquals("tok2", String(o.open(frames[2])))
        o.end()
        // the order is enforced: an event first, or a response where an event belongs, is refused
        assertError(CloudError.BAD_FRAME) { FrameOpener(dirKey, 9, ctx, FrameType.RESPONSE, thenType = FrameType.EVENT).open(FrameSealer(dirKey, 9, ctx, FrameType.EVENT).seal(byteArrayOf(1))) }
        val o2 = FrameOpener(dirKey, 9, ctx, FrameType.RESPONSE, thenType = FrameType.EVENT)
        o2.open(frames[0])
        val second = FrameSealer(dirKey, 9, ctx, FrameType.RESPONSE).also { it.seal(byteArrayOf(1)) }.seal(byteArrayOf(2)) // type 2 at counter 1
        assertError(CloudError.BAD_FRAME) { o2.open(second) }
        // an opener told only one type refuses the transition
        assertError(CloudError.BAD_FRAME) { FrameOpener(dirKey, 9, ctx, FrameType.RESPONSE).also { it.open(frames[0]) }.open(frames[1]) }
    }

    @Test fun theResponseEnvelopeCarriesTheStatusAndContentTypeInsideTheSealedFrame() { // sections 6, 7, 10
        val env = ResponseEnvelope(404, "application/json", """{"error":"nope"}""".toByteArray())
        val frame = FrameSealer(dirKey, 3, ctx, FrameType.RESPONSE, thenType = FrameType.EVENT).seal(env.encode(), final = true)
        val back = ResponseEnvelope.parse(FrameOpener(dirKey, 3, ctx, FrameType.RESPONSE, thenType = FrameType.EVENT).open(frame))
        assertEquals(404, back.status); assertEquals("application/json", back.contentType); assertEquals("""{"error":"nope"}""", String(back.body))
        // an attacker who changes the carrier's status cannot change this one; changing the sealed bytes breaks the tag
        assertError(CloudError.BAD_FRAME) { FrameOpener(dirKey, 3, ctx, FrameType.RESPONSE, thenType = FrameType.EVENT).open(flip(frame, 14)) }
        assertError(CloudError.MALFORMED) { ResponseEnvelope.parse(byteArrayOf(0, 5, 0, 0, 0, 0)) } // status 5 is not a status
        assertError(CloudError.MALFORMED) { ResponseEnvelope.parse(byteArrayOf(1)) }
    }

    @Test fun bulkWorksWithChaChaAndMegabyteChunks() {
        val (s, o) = bulkPair(aead = Aead.CHACHA20_POLY1305)
        val big = Crypto.random(1024 * 1024)
        assertArrayEquals(big, o.open(s.seal(0, big, final = true))!!.data)
        assertTrue(o.complete())
        var refused = false
        try { s.seal(5, ByteArray(1024 * 1024 + 1)) } catch (e: IllegalArgumentException) { refused = true }
        assertTrue(refused)
    }
}
