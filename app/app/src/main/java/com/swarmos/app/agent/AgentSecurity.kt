package com.swarmos.app.agent

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** The key an app on this phone presents to the agent endpoint. Pure functions; the listener only calls them. */
object AgentKey {
    const val BYTES = 32

    /** 32 random bytes, base64url without padding (43 characters). */
    fun generate(random: SecureRandom = SecureRandom()): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(BYTES).also(random::nextBytes))

    /** The token of an `Authorization: Bearer <token>` header; null for a missing header, another scheme or an empty token. */
    fun bearerToken(header: String?): String? {
        val h = header?.trim() ?: return null
        if (!h.startsWith(BEARER, ignoreCase = true)) return null
        return h.substring(BEARER.length).trim().ifEmpty { null }
    }

    /**
     * True when [presented] equals [expected]. Both sides are hashed first, so the comparison takes the same time whatever
     * the presented length and wherever the first differing byte is. No stored key (access never switched on) matches nothing.
     */
    fun matches(expected: String?, presented: String?): Boolean {
        if (expected.isNullOrEmpty() || presented == null) return false
        return MessageDigest.isEqual(sha256(expected), sha256(presented))
    }

    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))

    private const val BEARER = "Bearer "
}

/** Whether a peer address is this phone itself. Takes the textual address of the socket, never a host name. */
object AgentLoopback {
    fun isLoopback(address: String?): Boolean {
        val a = address?.trim()?.removeSurrounding("[", "]")?.substringBefore('%') ?: return false
        if (a.isEmpty()) return false
        ipv4(a)?.let { return it[0] == 127 }
        val g = ipv6(a) ?: return false
        val v6Loopback = g.take(7).all { it == 0 } && g[7] == 1
        val mappedV4Loopback = g.take(5).all { it == 0 } && g[5] == 0xFFFF && (g[6] shr 8) == 127
        return v6Loopback || mappedV4Loopback
    }

    private fun ipv4(s: String): IntArray? {
        val parts = s.split('.')
        if (parts.size != 4) return null
        val out = IntArray(4)
        for ((i, p) in parts.withIndex()) {
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' }) return null
            out[i] = p.toInt().takeIf { it <= 255 } ?: return null
        }
        return out
    }

    /** Eight 16-bit groups of an IPv6 literal (with "::" and a trailing dotted IPv4 allowed), or null. */
    private fun ipv6(s: String): IntArray? {
        if (':' !in s) return null
        var text = s
        if ('.' in text) {
            val cut = text.lastIndexOf(':') + 1
            val v4 = ipv4(text.substring(cut)) ?: return null
            text = text.substring(0, cut) + "%x:%x".format(v4[0] * 256 + v4[1], v4[2] * 256 + v4[3])
        }
        val halves = text.split("::")
        if (halves.size > 2) return null
        fun groups(part: String): List<Int>? {
            if (part.isEmpty()) return emptyList()
            return part.split(':').map { g ->
                if (g.isEmpty() || g.length > 4 || !g.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                g.toInt(16)
            }
        }
        val head = groups(halves[0]) ?: return null
        if (halves.size == 1) return if (head.size == 8) head.toIntArray() else null
        val tail = groups(halves[1]) ?: return null
        val gap = 8 - head.size - tail.size
        if (gap < 1) return null
        return (head + List(gap) { 0 } + tail).toIntArray()
    }
}

/** A request the endpoint answers with an error and nothing else. [status] is the HTTP status. */
class AgentRefusal(val status: Int, message: String, val code: String, val type: String = TYPE_INVALID_REQUEST) : RuntimeException(message) {
    companion object {
        const val TYPE_INVALID_REQUEST = "invalid_request_error"
        const val TYPE_SERVER = "server_error"
        const val TYPE_RATE_LIMIT = "rate_limit_error"
    }
}

/** The checks every request passes before anything is read from it: where it came from, how it was addressed, and the key. */
object AgentGate {
    /**
     * [host] is the request's Host header. A web page that reaches this port through DNS rebinding sends its own host name
     * there, so only the three spellings of this phone's own address with this listener's [port] are served.
     */
    fun hostAllowed(host: String?, port: Int): Boolean {
        val h = host?.trim()?.lowercase() ?: return false
        return h == "127.0.0.1:$port" || h == "localhost:$port" || h == "[::1]:$port"
    }

    fun check(remoteAddress: String?, host: String?, port: Int, authorization: String?, key: String?): AgentRefusal? {
        if (!AgentLoopback.isLoopback(remoteAddress) || !hostAllowed(host, port)) {
            return AgentRefusal(403, "This endpoint only answers requests from this phone, addressed as 127.0.0.1:$port.", "not_local")
        }
        if (!AgentKey.matches(key, AgentKey.bearerToken(authorization))) {
            return AgentRefusal(401, "Missing or wrong API key. Send the key from Ustaad Settings as 'Authorization: Bearer <key>'.", "invalid_api_key")
        }
        return null
    }
}
