package com.swarmos.app.core

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Request signing, contract section 6:
 * sig = hex HMAC-SHA256(pairingSecret, "$method\n$path\n$ts\n$body"), ts = epoch ms, +-30 s accepted.
 * `path` is the URL path without query string; `body` is the exact request body text ("" if none).
 */
object Signer {
    const val HEADER_NODE = "X-Swarm-Node"
    const val HEADER_TS = "X-Swarm-Ts"
    const val HEADER_SIG = "X-Swarm-Sig"
    const val MAX_SKEW_MS = 30_000L

    fun hmacSha256Hex(secret: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8)).toHex()
    }

    fun sign(secret: String, method: String, path: String, ts: Long, body: String): String =
        hmacSha256Hex(secret, "${method.uppercase()}\n$path\n$ts\n$body")

    fun verify(
        secret: String, method: String, path: String, ts: Long, body: String, sig: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (abs(nowMs - ts) > MAX_SKEW_MS) return false
        val expected = sign(secret, method, path, ts, body)
        return MessageDigest.isEqual(expected.toByteArray(), sig.lowercase().toByteArray())
    }
}

internal fun ByteArray.toHex(): String {
    val hex = "0123456789abcdef"
    val sb = StringBuilder(size * 2)
    for (b in this) {
        sb.append(hex[(b.toInt() shr 4) and 0xF]).append(hex[b.toInt() and 0xF])
    }
    return sb.toString()
}
