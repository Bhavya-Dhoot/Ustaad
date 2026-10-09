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
import java.util.UUID

class SseEvent(val event: String, val data: String)

/**
 * Signed POST whose answer is a server-sent event stream, read line by line as it arrives (NodeClient reads whole
 * bodies). Cancelling the collector closes the connection; the server notices on its next write and frees its slot.
 * A stream with no event for [idleTimeoutMs] fails; the server sends a comment line every couple of seconds.
 */
class StreamClient(
    private val secret: () -> String,
    private val selfId: () -> String,
    idleTimeoutMs: Long = 30_000,
) {
    private val http = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = idleTimeoutMs
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        }
    }

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
            header(HttpHeaders.Accept, ContentType.Text.EventStream.toString())
            contentType(ContentType.Application.Json)
            setBody(body)
        }.execute { resp ->
            if (!resp.status.isSuccess()) throw NodeHttpException(resp.status.value, resp.bodyAsText())
            val channel = resp.bodyAsChannel()
            var event = "message"
            val data = StringBuilder()
            while (true) {
                val line = channel.readUTF8Line() ?: break
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
}
