package com.swarmos.app.cloud

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.AlgorithmParameterSpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The only primitives the cloud uses, all from the JDK / Android platform: SHA-256, HMAC-SHA256, HKDF-SHA256 (RFC 5869,
 * written here on Mac), ECDSA P-256 with SHA-256 (64-byte r||s on the wire), ECDH P-256, AES-256-GCM.
 */
object Crypto {
    private val rng = SecureRandom()

    fun random(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) md.update(p)
        return md.digest()
    }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        for (p in parts) mac.update(p)
        return mac.doFinal()
    }

    /** Constant time for equal lengths; a length difference is not secret here (all compared values have a fixed size). */
    fun equal(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun wipe(vararg arrays: ByteArray) { for (a in arrays) a.fill(0) }

    // ---- HKDF-SHA256, RFC 5869 -------------------------------------------------------------------------------

    /** An empty salt means HashLen zero bytes (RFC 5869 section 2.2). */
    fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray = hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)

    fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * 32) { "HKDF output length" }
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var i = 1
        while (pos < length) {
            t = hmac(prk, t, info, byteArrayOf(i.toByte()))
            val n = minOf(32, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            i++
        }
        return out
    }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray =
        hkdfExpand(hkdfExtract(salt, ikm), info, length)

    // ---- identities and keys ---------------------------------------------------------------------------------

    private val curve: ECParameterSpec = AlgorithmParameters.getInstance("EC").run {
        init(ECGenParameterSpec("secp256r1"))
        getParameterSpec(ECParameterSpec::class.java)
    }
    private val fieldP: BigInteger = (curve.curve.field as ECFieldFp).p

    const val PUB_BYTES = 65
    const val SIG_BYTES = 64

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"), rng)
        generateKeyPair()
    }

    /** `fp(pub) = H(lp("ustaad/v1/key") || pub)`. */
    fun fingerprint(rawPublic: ByteArray): ByteArray = sha256(lbl("key"), rawPublic)

    private fun fixed32(x: BigInteger): ByteArray {
        val b = x.toByteArray()
        val start = if (b.size > 32 && b[0] == 0.toByte()) 1 else 0
        if (b.size - start > 32) malformed("coordinate")
        val out = ByteArray(32)
        System.arraycopy(b, start, out, 32 - (b.size - start), b.size - start)
        return out
    }

    /** 0x04 || X || Y. */
    fun rawPublic(key: PublicKey): ByteArray {
        val w = (key as ECPublicKey).w
        return concat(byteArrayOf(4), fixed32(w.affineX), fixed32(w.affineY))
    }

    /**
     * True when [raw] is an uncompressed P-256 point that lies on the curve. Checked before every use of a key that came
     * from outside (an invalid-curve point would leak the private key through ECDH).
     */
    fun isValidPoint(raw: ByteArray): Boolean {
        if (raw.size != PUB_BYTES || raw[0] != 4.toByte()) return false
        val x = BigInteger(1, raw.copyOfRange(1, 33))
        val y = BigInteger(1, raw.copyOfRange(33, 65))
        if (x >= fieldP || y >= fieldP) return false
        val lhs = y.multiply(y).mod(fieldP)
        val rhs = x.multiply(x).multiply(x).add(curve.curve.a.multiply(x)).add(curve.curve.b).mod(fieldP)
        return lhs == rhs
    }

    fun publicFromRaw(raw: ByteArray): PublicKey {
        if (!isValidPoint(raw)) malformed("public key")
        val point = ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65)))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, curve))
    }

    /** ECDH with an ephemeral private key; the secret is the 32-byte X coordinate. */
    fun ecdh(priv: PrivateKey, peerRaw: ByteArray): ByteArray = KeyAgreement.getInstance("ECDH").run {
        init(priv)
        doPhase(publicFromRaw(peerRaw), true)
        generateSecret()
    }

    // ---- ECDSA: 64-byte r||s on the wire, DER inside java.security.Signature ----------------------------------

    fun ecdsaSign(priv: PrivateKey, message: ByteArray): ByteArray {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(priv)
        s.update(message)
        return p1363FromDer(s.sign())
    }

    fun ecdsaVerify(rawPublic: ByteArray, message: ByteArray, sig: ByteArray): Boolean {
        if (sig.size != SIG_BYTES || !isValidPoint(rawPublic)) return false
        return try {
            val s = Signature.getInstance("SHA256withECDSA")
            s.initVerify(publicFromRaw(rawPublic))
            s.update(message)
            s.verify(derFromP1363(sig))
        } catch (e: GeneralSecurityException) {
            false
        } catch (e: ProtocolError) {
            false
        }
    }

    /** r||s (32 + 32 bytes) to DER SEQUENCE { INTEGER r, INTEGER s }. A P-256 signature is always under 128 bytes. */
    internal fun derFromP1363(sig: ByteArray): ByteArray {
        require(sig.size == SIG_BYTES)
        fun int(off: Int): ByteArray {
            var s = off
            while (s < off + 31 && sig[s] == 0.toByte()) s++ // minimal form: strip leading zero bytes, keep one
            val len = off + 32 - s
            val pad = if (sig[s] < 0) 1 else 0 // a set top bit would read as negative
            val v = ByteArray(len + pad)
            System.arraycopy(sig, s, v, pad, len)
            return byteArrayOf(2, v.size.toByte()) + v
        }
        val r = int(0)
        val s = int(32)
        return byteArrayOf(0x30, (r.size + s.size).toByte()) + r + s
    }

    internal fun p1363FromDer(der: ByteArray): ByteArray {
        var p = 0
        fun next(): Int { if (p >= der.size) malformed("signature"); return der[p++].toInt() and 0xFF }
        if (next() != 0x30) malformed("signature")
        if (next() != der.size - 2) malformed("signature")
        fun int(): ByteArray {
            if (next() != 2) malformed("signature")
            val n = next()
            if (n == 0 || n > 33 || p + n > der.size) malformed("signature")
            var v = der.copyOfRange(p, p + n)
            p += n
            if (v.size == 33) { if (v[0] != 0.toByte()) malformed("signature"); v = v.copyOfRange(1, 33) }
            return ByteArray(32 - v.size) + v
        }
        val r = int()
        val s = int()
        if (p != der.size) malformed("signature")
        return r + s
    }

    // ---- AEAD: AES-256-GCM and ChaCha20-Poly1305, 12-byte nonce, 16-byte tag -----------------------------------

    /** A fresh Cipher per call: the JDK refuses to reuse a key and nonce on one Cipher object. */
    fun seal(aead: Aead, key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(aead.transformation)
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, aead.keyAlgorithm), aead.params(nonce))
        c.updateAAD(aad)
        return c.doFinal(plain)
    }

    /** Plaintext, or null when the tag does not verify (wrong key, nonce, associated data or any changed byte). */
    fun open(aead: Aead, key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? = try {
        val c = Cipher.getInstance(aead.transformation)
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, aead.keyAlgorithm), aead.params(nonce))
        c.updateAAD(aad)
        c.doFinal(sealed)
    } catch (e: GeneralSecurityException) {
        null
    }

    fun gcmSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray = seal(Aead.AES_256_GCM, key, nonce, aad, plain)
    fun gcmOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? = open(Aead.AES_256_GCM, key, nonce, aad, sealed)
}

/**
 * The ciphers a session may negotiate (docs/CLOUD-PROTOCOL.md 11.1). The id is part of every stream key. The transformation
 * names are the JDK's; the Android name for ChaCha20-Poly1305 must be checked on a phone (open point 20).
 */
enum class Aead(val id: Int, internal val transformation: String, internal val keyAlgorithm: String) {
    AES_256_GCM(1, "AES/GCM/NoPadding", "AES"),
    CHACHA20_POLY1305(2, "ChaCha20-Poly1305", "ChaCha20");

    internal fun params(nonce: ByteArray): AlgorithmParameterSpec =
        if (this == AES_256_GCM) GCMParameterSpec(128, nonce) else IvParameterSpec(nonce)
}
