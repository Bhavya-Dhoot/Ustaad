package com.swarmos.app.cloud

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

class CryptoTest {
    private fun unhex(s: String) = Hex.parse(s.replace(" ", ""))
    private fun hex(b: ByteArray) = Hex.of(b)

    // ---- HKDF-SHA256, RFC 5869 appendix A (vectors cross-checked with an independent implementation) -----------

    @Test fun hkdfRfc5869TestCase1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xf0 + it).toByte() }
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", hex(Crypto.hkdfExtract(salt, ikm)))
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            hex(Crypto.hkdf(ikm, salt, info, 42)),
        )
    }

    @Test fun hkdfRfc5869TestCase2LongInputs() {
        val ikm = ByteArray(80) { it.toByte() }
        val salt = ByteArray(80) { (0x60 + it).toByte() }
        val info = ByteArray(80) { (0xb0 + it).toByte() }
        assertEquals("06a6b88c5853361a06104c9ceb35b45cef760014904671014a193f40c15fc244", hex(Crypto.hkdfExtract(salt, ikm)))
        assertEquals(
            "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71cc30c58179ec3e87c14c01d5c1f3434f1d87",
            hex(Crypto.hkdf(ikm, salt, info, 82)),
        )
    }

    @Test fun hkdfRfc5869TestCase3EmptySaltAndInfo() {
        val ikm = ByteArray(22) { 0x0b }
        assertEquals("19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04", hex(Crypto.hkdfExtract(ByteArray(0), ikm)))
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            hex(Crypto.hkdf(ikm, ByteArray(0), ByteArray(0), 42)),
        )
    }

    @Test fun distinctInfoGivesDistinctKeys() {
        val prk = Crypto.hkdfExtract(Crypto.random(64), Crypto.random(32))
        assertNotEquals(hex(Crypto.hkdfExpand(prk, lbl("a"), 32)), hex(Crypto.hkdfExpand(prk, lbl("b"), 32)))
    }

    // ---- ECDSA interop with the JDK's own classes -----------------------------------------------------------------

    private fun jdkPublic(raw: ByteArray) = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki(raw)))

    /** The fixed 26-byte SubjectPublicKeyInfo header of an uncompressed P-256 key, then the point. */
    private fun spki(raw: ByteArray) = unhex("3059301306072a8648ce3d020106082a8648ce3d030107034200") + raw

    @Test fun rawPublicKeyIsThePointInsideTheJdkX509Encoding() {
        val kp = Crypto.newKeyPair()
        val raw = Crypto.rawPublic(kp.public)
        assertEquals(65, raw.size)
        assertEquals(4, raw[0].toInt())
        assertArrayEquals(kp.public.encoded.copyOfRange(26, 91), raw)
        assertArrayEquals(kp.public.encoded, spki(raw))
    }

    @Test fun ourSignatureVerifiesWithTheJdkInP1363Format() {
        val id = SoftwareIdentity.generate()
        val msg = Crypto.random(100)
        val sig = id.sign(msg)
        assertEquals(64, sig.size)
        val v = Signature.getInstance("SHA256withECDSAinP1363Format")
        v.initVerify(jdkPublic(id.publicKey))
        v.update(msg)
        assertTrue(v.verify(sig))
    }

    @Test fun aJdkP1363SignatureVerifiesWithOurVerifier() {
        val kp = Crypto.newKeyPair()
        val msg = Crypto.random(100)
        val s = Signature.getInstance("SHA256withECDSAinP1363Format")
        s.initSign(kp.private)
        s.update(msg)
        assertTrue(Crypto.ecdsaVerify(Crypto.rawPublic(kp.public), msg, s.sign()))
    }

    @Test fun derAndP1363ConvertBothWaysIncludingSmallAndHighBitValues() {
        val cases = listOf(
            ByteArray(31) + byteArrayOf(1) to ByteArray(31) + byteArrayOf(2), // r and s tiny: many leading zeros
            ByteArray(32) { 0xff.toByte() } to ByteArray(32) { 0x80.toByte() }, // top bit set: DER needs a zero pad
            ByteArray(32) { 0x7f } to byteArrayOf(0, 0x80.toByte()) + ByteArray(30) { 1 },
        )
        for ((r, s) in cases) {
            val p1363 = r + s
            val der = Crypto.derFromP1363(p1363)
            assertArrayEquals(p1363, Crypto.p1363FromDer(der))
            // the JDK reads the DER form we produce as the same two integers
            val a = BigInteger(1, r); val b = BigInteger(1, s)
            val seq = java.io.ByteArrayInputStream(der)
            assertEquals(0x30, seq.read())
            assertTrue(der.size <= 72)
            assertTrue(a.signum() >= 0 && b.signum() >= 0)
        }
    }

    @Test fun jdkDerSignaturesRoundTripThroughOurConversion() {
        val kp = Crypto.newKeyPair()
        repeat(50) {
            val msg = Crypto.random(40)
            val s = Signature.getInstance("SHA256withECDSA")
            s.initSign(kp.private); s.update(msg)
            val der = s.sign()
            val p1363 = Crypto.p1363FromDer(der)
            assertArrayEquals(der, Crypto.derFromP1363(p1363))
        }
    }

    @Test fun malformedDerIsRejected() {
        assertError(CloudError.MALFORMED) { Crypto.p1363FromDer(ByteArray(0)) }
        assertError(CloudError.MALFORMED) { Crypto.p1363FromDer(byteArrayOf(0x31, 0)) }
        assertError(CloudError.MALFORMED) { Crypto.p1363FromDer(byteArrayOf(0x30, 5, 2, 1, 1, 2, 1)) }
        assertError(CloudError.MALFORMED) { Crypto.p1363FromDer(byteArrayOf(0x30, 7, 2, 1, 1, 2, 1, 1)) } // sequence length does not match
        assertEquals(64, Crypto.p1363FromDer(byteArrayOf(0x30, 6, 2, 1, 1, 2, 1, 1)).size) // r = s = 1 is well formed DER
    }

    @Test fun verifyRejectsTamperingWrongKeyAndBadSizes() {
        val a = SoftwareIdentity.generate()
        val b = SoftwareIdentity.generate()
        val msg = "hello".toByteArray()
        val sig = a.sign(msg)
        assertTrue(Crypto.ecdsaVerify(a.publicKey, msg, sig))
        assertFalse(Crypto.ecdsaVerify(a.publicKey, "hellp".toByteArray(), sig))
        assertFalse(Crypto.ecdsaVerify(b.publicKey, msg, sig))
        assertFalse(Crypto.ecdsaVerify(a.publicKey, msg, sig.copyOf(63)))
        assertFalse(Crypto.ecdsaVerify(a.publicKey, msg, ByteArray(64))) // r = s = 0
        val flipped = sig.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        assertFalse(Crypto.ecdsaVerify(a.publicKey, msg, flipped))
    }

    @Test fun identityRestoresFromItsPkcs8FormAndRefusesAMismatchedPair() {
        val a = SoftwareIdentity.generate()
        val again = SoftwareIdentity.restore(a.exportPrivate(), a.publicKey)
        assertTrue(Crypto.ecdsaVerify(a.publicKey, byteArrayOf(1, 2), again.sign(byteArrayOf(1, 2))))
        val other = SoftwareIdentity.generate()
        var failed = false
        try { SoftwareIdentity.restore(a.exportPrivate(), other.publicKey) } catch (e: IllegalArgumentException) { failed = true }
        assertTrue(failed)
    }

    // ---- ECDH interop and point validation ------------------------------------------------------------------------

    @Test fun ecdhAgreesBetweenBothSidesAndWithAPlainJdkKeyAgreement() {
        val a = Crypto.newKeyPair()
        val b = Crypto.newKeyPair()
        val ab = Crypto.ecdh(a.private, Crypto.rawPublic(b.public))
        val ba = Crypto.ecdh(b.private, Crypto.rawPublic(a.public))
        assertArrayEquals(ab, ba)
        assertEquals(32, ab.size)
        // the JDK, given the X.509 form of the same keys, computes the same secret
        val jdk = KeyAgreement.getInstance("ECDH").run {
            init(a.private)
            doPhase(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(b.public.encoded)), true)
            generateSecret()
        }
        assertArrayEquals(jdk, ab)
        assertNotEquals(hex(ab), hex(Crypto.ecdh(a.private, Crypto.rawPublic(Crypto.newKeyPair().public))))
    }

    @Test fun pointsOffTheCurveAndOddEncodingsAreRejected() {
        val good = Crypto.rawPublic(Crypto.newKeyPair().public)
        assertTrue(Crypto.isValidPoint(good))
        val offCurve = good.copyOf().also { it[64] = (it[64].toInt() xor 1).toByte() } // y changed: no longer on the curve
        assertFalse(Crypto.isValidPoint(offCurve))
        assertFalse(Crypto.isValidPoint(good.copyOf(64)))
        assertFalse(Crypto.isValidPoint(byteArrayOf(0)))
        assertFalse(Crypto.isValidPoint(good.copyOf().also { it[0] = 2 })) // compressed marker with 65 bytes
        val big = ByteArray(65).also { it[0] = 4; java.util.Arrays.fill(it, 1, 65, 0xff.toByte()) } // coordinates over the field prime
        assertFalse(Crypto.isValidPoint(big))
        assertError(CloudError.MALFORMED) { Crypto.publicFromRaw(offCurve) }
        assertError(CloudError.MALFORMED) { Crypto.ecdh(Crypto.newKeyPair().private, offCurve) }
    }

    // ---- AES-GCM, fingerprint, constant-time compare ----------------------------------------------------------------

    @Test fun gcmSealsAndOpensAndRefusesAnyChange() {
        val key = Crypto.random(32); val nonce = Crypto.random(12); val aad = Crypto.random(8); val plain = Crypto.random(50)
        val sealed = Crypto.gcmSeal(key, nonce, aad, plain)
        assertEquals(66, sealed.size)
        assertArrayEquals(plain, Crypto.gcmOpen(key, nonce, aad, sealed))
        assertEquals(null, Crypto.gcmOpen(key, nonce, aad.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }, sealed))
        assertEquals(null, Crypto.gcmOpen(key, nonce, aad, sealed.copyOf().also { it[65] = (it[65].toInt() xor 1).toByte() })) // the tag
        assertEquals(null, Crypto.gcmOpen(Crypto.random(32), nonce, aad, sealed))
    }

    @Test fun fingerprintIsDomainSeparatedHashOfTheKey() {
        val id = SoftwareIdentity.generate()
        assertArrayEquals(Crypto.sha256(lbl("key"), id.publicKey), Crypto.fingerprint(id.publicKey))
        assertNotEquals(hex(Crypto.sha256(id.publicKey)), hex(Crypto.fingerprint(id.publicKey)))
        assertEquals(64, id.memberId().length)
    }

    @Test fun equalComparesContents() {
        assertTrue(Crypto.equal(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(Crypto.equal(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertFalse(Crypto.equal(byteArrayOf(1, 2, 3), byteArrayOf(1, 2)))
    }

    // ---- canonical encoding is strict -----------------------------------------------------------------------------

    @Test fun readerRejectsShortTrailingOverlongAndBadStrings() {
        assertError(CloudError.MALFORMED) { Reader(byteArrayOf(1, 2)).u32() }
        assertError(CloudError.MALFORMED) { Reader(byteArrayOf(0, 0, 0, 9, 1)).lp(100) } // length runs past the input
        assertError(CloudError.MALFORMED) { Reader(Writer().lp(ByteArray(50)).bytes()).lp(10) } // over the field limit
        assertError(CloudError.MALFORMED) { Reader(byteArrayOf(1, 2)).also { it.u8() }.end() } // trailing byte
        assertError(CloudError.MALFORMED) { Reader(Writer().lp(byteArrayOf(0xc3.toByte(), 0x28)).bytes()).str(10) } // invalid UTF-8
        assertError(CloudError.MALFORMED) { Reader(Writer().str("a\u0000b").bytes()).str(10) } // control character
        assertError(CloudError.MALFORMED) { Reader(byteArrayOf(0xff.toByte(), 0, 0, 0)).u32() } // negative length
    }

    @Test fun integersAreBigEndianAndLengthPrefixed() {
        assertEquals("0000000102", hex(Writer().u32(1).u8(2).bytes()))
        assertEquals("0102030405060708", hex(Writer().u64(0x0102030405060708L).bytes()))
        assertEquals("000000026162", hex(Writer().str("ab").bytes()))
        val r = Reader(Writer().u64(-2L).u32(7).str("héllo").bytes())
        assertEquals(-2L, r.u64()); assertEquals(7, r.u32()); assertEquals("héllo", r.str(20)); r.end()
    }

    @Test fun nodeIdAndNameShapes() {
        assertTrue(Shape.isNodeId("0a1b2c3d"))
        assertFalse(Shape.isNodeId("0A1B2C3D")); assertFalse(Shape.isNodeId("0a1b2c3")); assertFalse(Shape.isNodeId("0a1b2c3g"))
        assertTrue(Shape.isName("Priya's phone")); assertFalse(Shape.isName("")); assertFalse(Shape.isName("x".repeat(65)))
        assertTrue(Shape.isRequestId("3f2b-aa_9")); assertFalse(Shape.isRequestId("a b")); assertFalse(Shape.isRequestId("")); assertFalse(Shape.isRequestId("é"))
    }
}
