package com.swarmos.app.link

import com.swarmos.app.core.Ports
import com.swarmos.app.core.Signer
import com.swarmos.app.core.SwarmJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.encodeToString

class NodeHttpException(val status: Int, val body: String) : RuntimeException("HTTP $status: $body")

/**
 * Signed HTTP to peers. `host` is "ip" (port 8787 + offset) or "ip:port"; `path` is relative to /v1,
 * e.g. get<NodeHealth>("192.168.242.23", "/health").
 */
class NodeClient(private val secret: () -> String, private val selfId: () -> String) {
    @PublishedApi internal val json = SwarmJson

    private val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(SwarmJson) }
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 60_000
        }
    }

    @PublishedApi
    internal suspend fun call(method: HttpMethod, host: String, path: String, body: String): String {
        val fullPath = "/v1$path"
        val ts = System.currentTimeMillis()
        val hostPort = if (host.contains(':')) host else "$host:${Ports.NODE}"
        val resp = http.request {
            this.method = method
            url("http://$hostPort$fullPath")
            header(Signer.HEADER_NODE, selfId())
            header(Signer.HEADER_TS, ts.toString())
            header(Signer.HEADER_SIG, Signer.sign(secret(), method.value, fullPath, ts, body))
            if (body.isNotEmpty()) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        val text = resp.bodyAsText()
        if (!resp.status.isSuccess()) throw NodeHttpException(resp.status.value, text)
        return text
    }

    suspend inline fun <reified T> get(host: String, path: String): T =
        json.decodeFromString<T>(call(HttpMethod.Get, host, path, ""))

    suspend inline fun <reified T> post(host: String, path: String): T =
        json.decodeFromString<T>(call(HttpMethod.Post, host, path, ""))

    suspend inline fun <reified T, reified B> post(host: String, path: String, body: B): T =
        json.decodeFromString<T>(call(HttpMethod.Post, host, path, json.encodeToString(body)))
}
