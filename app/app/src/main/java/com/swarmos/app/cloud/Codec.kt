package com.swarmos.app.cloud

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64

/**
 * The closed cloud's canonical byte encoding (docs/CLOUD-PROTOCOL.md section 1.1) and the error table (section 7).
 * Pure JVM: no Android imports anywhere in this package, only java.security and javax.crypto.
 */

/** Every protocol failure. [code] is the string of the error table; [http] is 0 for failures that never reach HTTP. */
enum class CloudError(val http: Int) {
    MALFORMED(400), UNKNOWN_SESSION(401), UNKNOWN_KEY(401), SENDER_MISMATCH(401), BAD_MAC(401), SKEW(401),
    NOT_MEMBER(403), FORBIDDEN(403), JOIN_REFUSED(403), REPLAYED(409), REQUEST_ID_REUSED(422), TOO_MANY_RESENDS(429),
    BUSY(503), BAD_FRAME(0), OUT_OF_ORDER(0), TRUNCATED(0);

    val code: String get() = name.lowercase()
}

/** [detail] never carries a key, a code or a MAC: it is shown to people and written to logs. */
class ProtocolError(val error: CloudError, detail: String = error.code) : Exception(detail)

internal fun malformed(why: String = "malformed"): Nothing = throw ProtocolError(CloudError.MALFORMED, why)

/** `lp(label)` of a domain-separation label: every signed, MACed or hashed structure starts with one. */
internal fun lbl(name: String): ByteArray = Writer().str("ustaad/v1/$name").bytes()

internal fun concat(vararg parts: ByteArray): ByteArray {
    val out = ByteArray(parts.sumOf { it.size })
    var p = 0
    for (a in parts) { System.arraycopy(a, 0, out, p, a.size); p += a.size }
    return out
}

internal class Writer {
    private val out = ByteArrayOutputStream()

    fun u8(v: Int): Writer { require(v in 0..255); out.write(v); return this }
    fun u32(v: Int): Writer { require(v >= 0); for (s in 24 downTo 0 step 8) out.write((v ushr s) and 0xFF); return this }
    fun u64(v: Long): Writer { for (s in 56 downTo 0 step 8) out.write(((v ushr s) and 0xFF).toInt()); return this }

    /** A field of exactly [n] bytes. */
    fun fixed(b: ByteArray, n: Int): Writer { require(b.size == n) { "field must be $n bytes" }; out.write(b); return this }
    fun raw(b: ByteArray): Writer { out.write(b); return this }
    fun lp(b: ByteArray): Writer { u32(b.size); out.write(b); return this }
    fun str(s: String): Writer = lp(s.toByteArray(Charsets.UTF_8))
    fun bytes(): ByteArray = out.toByteArray()
}

/** Strict reader: every shortfall, over-long field and invalid string is [CloudError.MALFORMED]. */
internal class Reader(private val b: ByteArray) {
    private var p = 0

    private fun need(n: Int) { if (n < 0 || n > b.size - p) malformed("short input") }

    fun u8(): Int { need(1); return b[p++].toInt() and 0xFF }
    fun u32(): Int {
        need(4)
        var v = 0
        repeat(4) { v = (v shl 8) or (b[p++].toInt() and 0xFF) }
        if (v < 0) malformed("length")
        return v
    }
    fun u64(): Long {
        need(8)
        var v = 0L
        repeat(8) { v = (v shl 8) or (b[p++].toLong() and 0xFF) }
        return v
    }
    fun fixed(n: Int): ByteArray { need(n); val r = b.copyOfRange(p, p + n); p += n; return r }
    fun lp(max: Int): ByteArray { val n = u32(); if (n > max) malformed("field over limit"); return fixed(n) }
    fun str(max: Int): String = Shape.text(lp(max))
    fun rest(): ByteArray = fixed(b.size - p)
    fun remaining(): Int = b.size - p
    fun end() { if (p != b.size) malformed("trailing bytes") }
}

/** Shapes the protocol accepts for names and ids. */
internal object Shape {
    const val MAX_NAME_BYTES = 64

    /** Strict UTF-8, no control characters. */
    fun text(bytes: ByteArray): String {
        val s = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            malformed("not UTF-8")
        }
        if (s.any { it.code < 0x20 || it.code == 0x7f }) malformed("control character")
        return s
    }

    /** The existing 8-hex node id. */
    fun isNodeId(s: String): Boolean = s.length == 8 && s.all { it in '0'..'9' || it in 'a'..'f' }
    fun isName(s: String): Boolean = s.toByteArray(Charsets.UTF_8).size in 1..MAX_NAME_BYTES
    fun isRequestId(s: String): Boolean = s.length in 1..64 && s.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }
    fun requireNodeId(s: String): String { if (!isNodeId(s)) malformed("node id"); return s }
    fun requireName(s: String): String { if (!isName(s)) malformed("name"); return s }
}

internal object Hex {
    private const val DIGITS = "0123456789abcdef"

    fun of(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append(DIGITS[(x.toInt() shr 4) and 0xF]).append(DIGITS[x.toInt() and 0xF])
        return sb.toString()
    }

    fun parse(s: String): ByteArray {
        if (s.length % 2 != 0) malformed("hex")
        return ByteArray(s.length / 2) {
            val hi = DIGITS.indexOf(s[2 * it]); val lo = DIGITS.indexOf(s[2 * it + 1])
            if (hi < 0 || lo < 0) malformed("hex")
            ((hi shl 4) or lo).toByte()
        }
    }
}

internal object B64 {
    fun enc(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    fun dec(s: String): ByteArray = try { Base64.getUrlDecoder().decode(s) } catch (e: IllegalArgumentException) { malformed("base64") }
}
