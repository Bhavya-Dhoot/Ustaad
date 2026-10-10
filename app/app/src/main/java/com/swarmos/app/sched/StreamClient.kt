package com.swarmos.app.sched

import com.swarmos.app.core.Ports
import com.swarmos.app.core.Signer
import com.swarmos.app.link.HEADER_REQ
import com.swarmos.app.link.NodeHttpException
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

class SseEvent(val event: String, val data: String)

/** A streamed answer failed its check (final signature missing or wrong): the whole answer must be dropped. */
class StreamIntegrityException(message: String) : IOException(message)

/** A peer sent a line or a stream longer than this phone accepts. */
class StreamTooLargeException(message: String) : IOException(message)

/**
 * The signature that ends every streamed answer: HMAC-SHA256 with the pairing secret over the request id and the
 * full concatenated text, so a host on the LAN that is not paired cannot add, drop or change tokens unnoticed.
 */
object StreamMac {
    fun compute(secret: String, requestId: String, text: String): String =
        Signer.hmacSha256Hex(secret, "sched-stream\n$requestId\n$text")

    fun matches(secret: String, requestId: String, text: String, mac: String?): Boolean =
        mac != null && MessageDigest.isEqual(compute(secret, requestId, text).toByteArray(), mac.lowercase().toByteArray())
}

/**
 * Signed POST whose answer is a server-sent event stream, read line by line as it arrives (NodeClient reads whole
 * bodies). Cancelling the collector closes the connection; the server notices on its next write and frees its slot.
 * A stream with no event for [idleTimeoutMs] fails; the server sends a comment line every couple of seconds.
 * A line longer than [maxLineChars] or more than [maxTotalChars] in all fails with [StreamTooLargeException].
 */
class StreamClient(
    private val secret: () -> String,
    private val selfId: () -> String,
    idleTimeoutMs: Long = 30_000,
    private val maxLineChars: Int = MAX_LINE_CHARS,
    private val maxTotalChars: Int = MAX_TOTAL_CHARS,
) {
    private val http = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = idleTimeoutMs
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        }
    }

    /** The pairing secret, for checking the signature that ends a stream. */
    internal fun secretNow(): String = secret()

    /** [host] is "ip" (default node port) or "ip:port"; [path] is relative to /v1. A non-2xx answer throws [NodeHttpException]. */
    fun post(host: String, path: String, body: String): Flow<SseEvent> = flow {
        val fullPath = "/v1$path"
        val ts = System.currentTimeMillis()
        val hostPort = if (host.contains(':')) host else "$host:${Ports.NODE}"
        http.preparePost("http://$hostPort$fullPath") {
            header(Signer.HEADER_NODE, selfId())
            header(Signer.HEADER_TS, ts.toString())
            header(Signer.HEADER_SIG, Signer.sign(secret(), "POST", fullPath, ts, body))
            header(HEADER_REQ, UUID.randomUUID().toString())
            // JSON stays acceptable so a 429 or 409 body is not turned into a 406 by content negotiation
            header(HttpHeaders.Accept, "${ContentType.Text.EventStream}, ${ContentType.Application.Json}")
            contentType(ContentType.Application.Json)
            setBody(body)
        }.execute { resp ->
            if (!resp.status.isSuccess()) throw NodeHttpException(resp.status.value, resp.bodyAsText())
            val channel = resp.bodyAsChannel()
            var event = "message"
            val data = StringBuilder()
            var total = 0
            while (true) {
                val line = try {
                    channel.readUTF8Line(maxLineChars)
                } catch (e: Exception) {
                    // Ktor throws its TooLongLineException when the line passes the limit; anything else is a normal failure
                    if (e.javaClass.simpleName != "TooLongLineException") throw e
                    throw StreamTooLargeException("a line of the stream is longer than $maxLineChars characters")
                } ?: break
                total += line.length + 1
                if (total > maxTotalChars) throw StreamTooLargeException("the stream is longer than $maxTotalChars characters")
                when {
                    line.isEmpty() -> {
                        if (data.isNotEmpty()) emit(SseEvent(event, data.toString()))
                        event = "message"
                        data.clear()
                    }
                    line.startsWith(":") -> Unit                       // comment line: keep-alive
                    line.startsWith("event:") -> event = line.substring(6).trim()
                    line.startsWith("data:") -> {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.substring(5).removePrefix(" "))
                    }
                }
            }
        }
    }

    companion object {
        /** A token is a few bytes; 64k characters in one line is already absurd. */
        const val MAX_LINE_CHARS = 64 * 1024

        /** At most [SchedLimits.MAX_TOKENS] tokens are asked for, so a real answer is far below this. */
        const val MAX_TOTAL_CHARS = 1024 * 1024
    }
}
