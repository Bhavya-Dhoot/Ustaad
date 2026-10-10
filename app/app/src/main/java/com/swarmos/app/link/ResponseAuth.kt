package com.swarmos.app.link

import com.swarmos.app.core.Signer
import java.io.IOException
import java.security.MessageDigest

/** Response header carrying the HMAC of the answer, see [ResponseAuth]. */
const val HEADER_RSP_SIG = "X-Swarm-Rsp"

/** The answer did not carry a valid [HEADER_RSP_SIG]: whoever replied does not hold our pairing secret. */
class UntrustedResponse(msg: String) : IOException(msg)

/**
 * Authenticated responses: every answer from a node carries `X-Swarm-Rsp` = hex HMAC-SHA256(secret,
 * "rsp\n$status\n$reqId\n$body") where reqId is the request's X-Swarm-Req. The caller checks it, so a stranger on the
 * LAN that merely answers 200 with JSON is not a peer, and a recorded answer cannot be replayed for another request
 * (the request id is inside the MAC). An error answer (401, 413...) is authenticated the same way.
 */
object ResponseAuth {
    fun sign(secret: String, status: Int, reqId: String, body: String): String =
        Signer.hmacSha256Hex(secret, "rsp\n$status\n$reqId\n$body")

    fun verify(secret: String, status: Int, reqId: String, body: String, sig: String?): Boolean {
        if (sig == null) return false
        return MessageDigest.isEqual(sign(secret, status, reqId, body).toByteArray(), sig.lowercase().toByteArray())
    }

    /**
     * Streamed answers (server-sent events) cannot carry a header with a MAC of a body that is not complete yet, so the
     * server puts the MAC in the data of its last event: `signStream(secret, reqId, finalEventData)`, where finalEventData
     * is that event's own payload without the MAC field. The caller checks it with [verifyStream] and treats a stream
     * whose last event fails, or that ends without one, as untrusted. Domain-separated from [sign].
     */
    fun signStream(secret: String, reqId: String, finalEventData: String): String =
        Signer.hmacSha256Hex(secret, "stream\n$reqId\n$finalEventData")

    fun verifyStream(secret: String, reqId: String, finalEventData: String, mac: String?): Boolean {
        if (mac == null) return false
        return MessageDigest.isEqual(signStream(secret, reqId, finalEventData).toByteArray(), mac.lowercase().toByteArray())
    }
}
