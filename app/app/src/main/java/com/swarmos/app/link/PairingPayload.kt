package com.swarmos.app.link

import com.swarmos.app.core.SwarmJson
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.util.Base64

/** Wi-Fi Direct group credentials carried in the pairing payload. */
@Serializable
data class P2pCreds(val ssid: String, val pass: String)

/**
 * CONTRACTS 9.3 pairing payload: {name, nodeId, ips[], port, secret, p2p{ssid, pass}?}.
 * Shown as a QR and accepted through `swarmos://pair?d=<base64url(json)>`.
 */
@Serializable
data class PairingPayload(
    val name: String,
    val nodeId: String,
    val ips: List<String>,
    val port: Int,
    val secret: String,
    val p2p: P2pCreds? = null,
)

object Pairing {
    const val SCHEME = "swarmos"
    const val HOST = "pair"

    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()

    fun encode(p: PairingPayload): String =
        enc.encodeToString(SwarmJson.encodeToString(p).toByteArray(Charsets.UTF_8))

    fun toLink(p: PairingPayload): String = "$SCHEME://$HOST?d=${encode(p)}"

    /** Decode the bare `d=` value (url-safe base64, padding optional). Null when it is not a payload. */
    fun decode(d: String): PairingPayload? = runCatching {
        val json = String(dec.decode(d.trim()), Charsets.UTF_8)
        SwarmJson.decodeFromString<PairingPayload>(json).takeIf { it.secret.isNotEmpty() && it.port in 1..65535 }
    }.getOrNull()

    /** Accepts a deep link, a bare base64url blob or raw JSON (what a person might paste or a QR might hold). */
    fun parse(text: String): PairingPayload? {
        val t = text.trim()
        if (t.startsWith("{")) {
            return runCatching { SwarmJson.decodeFromString<PairingPayload>(t) }.getOrNull()
                ?.takeIf { it.secret.isNotEmpty() && it.port in 1..65535 }
        }
        if (t.startsWith("$SCHEME://", ignoreCase = true)) {
            val query = t.substringAfter('?', "")
            val d = query.split('&').firstOrNull { it.startsWith("d=") }?.substring(2) ?: return null
            return decode(d)
        }
        return decode(t)
    }

    /** 32 random bytes as 64 hex chars. */
    fun randomSecret(): String {
        val b = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return b.joinToString("") { "%02x".format(it) }
    }

    /** Wi-Fi Direct passphrase: 12 alphanumerics, no look-alike characters. */
    fun randomPassphrase(): String {
        val alphabet = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val r = SecureRandom()
        return (1..12).map { alphabet[r.nextInt(alphabet.length)] }.joinToString("")
    }

    /** `DIRECT-xy-<name>` as required by WifiP2pConfig.Builder; at most 32 bytes. */
    fun directSsid(nodeId: String): String = "DIRECT-sw-${nodeId.take(8)}"

    /** First 8 hex characters of SHA-256(secret): short enough to read out, used to confirm the right host and to compare "same secret" without showing it. */
    fun fingerprint(secret: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8))
            .take(4).joinToString("") { "%02x".format(it) }

    /** The pairing code a person reads or types: the fingerprint in two groups of four ("4146 a9c0"). */
    fun code(secret: String): String = fingerprint(secret).chunked(4).joinToString(" ")
}
