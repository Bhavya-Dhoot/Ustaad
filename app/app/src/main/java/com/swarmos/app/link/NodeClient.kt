package com.swarmos.app.link

import com.swarmos.app.core.Ports
import com.swarmos.app.core.Signer
import com.swarmos.app.core.SwarmJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.IOException

/** Unique id per logical request (a retry on another path reuses it) so a receiver can drop duplicates. */
const val HEADER_REQ = "X-Swarm-Req"

/** Largest request body and response the Bluetooth control path will carry. */
const val BT_MAX_BODY = 1024

class NodeHttpException(val status: Int, val body: String) : RuntimeException("HTTP $status: $body")

/** A transport for small signed requests that is not HTTP over IP (the Bluetooth control path). */
interface SmallTransport {
    /** Carry one signed request to the peer behind [path] and return the response body; throw IOException on transport failure. */
    suspend fun send(
        path: PeerStatus, method: String, fullPath: String, body: String, headers: Map<String, String>, timeoutMs: Long,
    ): String
}

/**
 * Signed HTTP to peers. `host` is "ip" (port 8787 + offset) or "ip:port"; `path` is relative to /v1,
 * e.g. get<NodeHealth>("192.168.242.23", "/health"). Every call may pass a per-request `timeoutMs` (the default
 * is 60 s; long model calls pass minutes).
 *
 * When `host` is the address of a peer that has more than one usable path (LAN, DIRECT, Bluetooth) the request is
 * sent on the best path and, on a transport failure, retried on the next with the same X-Swarm-Req id; the
 * receiver replays the stored answer if it had already run the request, so it executes once.
 */
class NodeClient(
    private val secret: () -> String,
    private val selfId: () -> String,
    /**
     * Check the X-Swarm-Rsp of every answer. Always on in the app; JVM unit tests that serve a bare Ktor route with no node
     * plugins have no signer, and the stub android.jar reports SDK_INT 0, so the check is skipped only there.
     */
    private val verifyResponses: Boolean = android.os.Build.VERSION.SDK_INT != 0,
) {
    @PublishedApi internal val json = SwarmJson

    private val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(SwarmJson) }
        install(HttpTimeout) {
            connectTimeoutMillis = 5_000
            requestTimeoutMillis = 60_000
        }
    }

    @PublishedApi
    internal suspend fun call(
        method: HttpMethod, host: String, path: String, body: String, timeoutMs: Long? = null, reqId: String? = null,
        pinned: Boolean = false,
    ): String {
        val id = reqId ?: java.util.UUID.randomUUID().toString()
        val hostPort = if (host.contains(':')) host else "$host:${Ports.NODE}"
        val nodeId = if (pinned) null else PeerLinks.nodeAt(host.substringBefore(':'))
        val cands = nodeId?.let { PeerLinks.candidates(it, Use.SMALL) }.orEmpty()
            .filter { it.link != LinkType.BT || body.length <= BT_MAX_BODY }
        if (cands.size < 2 && cands.firstOrNull()?.link != LinkType.BT) return httpOnce(hostPort, method, path, body, timeoutMs, id, null)

        val attempts = cands.mapIndexed { i, p ->
            val last = i == cands.lastIndex
            PathAttempt(p.key) { rid ->
                if (p.link == LinkType.BT) {
                    val bt = PeerLinks.btTransport ?: throw IOException("no Bluetooth transport")
                    bt.send(p, method.value, "/v1$path", body, signedHeaders(method, "/v1$path", body, rid), timeoutMs ?: 10_000)
                } else {
                    tracked(p.key) { httpOnce(p.hostPort, method, path, body, timeoutMs, rid, if (last) null else FAILOVER_CONNECT_MS) }
                }
            }
        }
        return sendWithFailover(id, attempts) { key, _ -> PeerLinks.noteFailure(key) }
    }

    private suspend fun <T> tracked(key: String, block: suspend () -> T): T = coroutineScope {
        val d = async { block() }
        val untrack = PeerLinks.trackInflight(key, d)
        try { d.await() } finally { untrack() }
    }

    internal fun signedHeaders(method: HttpMethod, fullPath: String, body: String, reqId: String): Map<String, String> {
        val ts = System.currentTimeMillis()
        return mapOf(
            Signer.HEADER_NODE to selfId(),
            Signer.HEADER_TS to ts.toString(),
            Signer.HEADER_SIG to Signer.sign(secret(), method.value, fullPath, ts, body),
            HEADER_REQ to reqId,
        )
    }

    private suspend fun httpOnce(
        hostPort: String, method: HttpMethod, path: String, body: String, timeoutMs: Long?, reqId: String, connectMs: Long?,
    ): String {
        val fullPath = "/v1$path"
        val headers = signedHeaders(method, fullPath, body, reqId)
        val resp = http.request {
            this.method = method
            url("http://$hostPort$fullPath")
            if (timeoutMs != null || connectMs != null) {
                timeout {
                    if (timeoutMs != null) {
                        requestTimeoutMillis = timeoutMs
                        socketTimeoutMillis = timeoutMs
                    }
                    // a long request budget (a 1000-token answer) must not also let a connect to a dead address hang that long
                    connectTimeoutMillis = connectMs ?: timeoutMs?.coerceAtMost(CONNECT_CAP_MS)
                }
            }
            headers.forEach { (k, v) -> header(k, v) }
            if (body.isNotEmpty()) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        val text = resp.bodyAsText()
        // only a holder of the pairing secret can produce this header; anything else is not a peer
        if (verifyResponses && !ResponseAuth.verify(secret(), resp.status.value, reqId, text, resp.headers[HEADER_RSP_SIG])) {
            throw UntrustedResponse("the answer from $hostPort is not authenticated (missing or wrong $HEADER_RSP_SIG)")
        }
        val node = resp.headers[Signer.HEADER_NODE]
        val fg = resp.headers[HEADER_FG]
        if (node != null && fg != null) PeerLinks.noteForeground(node, fg == "1")
        if (!resp.status.isSuccess()) throw NodeHttpException(resp.status.value, text)
        return text
    }

    suspend inline fun <reified T> get(host: String, path: String, timeoutMs: Long? = null): T =
        json.decodeFromString<T>(call(HttpMethod.Get, host, path, "", timeoutMs))

    /**
     * The `host` string other modules should pass to this client (or to a streaming client) to reach [nodeId] on the best
     * path for [use]: "ip" or "ip:port", or "bt:<node id>" when Bluetooth is all that is up (a plain request then goes over
     * Bluetooth, a streaming client cannot). Null when no path is up. Unlike the registry's `info.ip` it is evaluated now,
     * with the path policy's hysteresis, so it follows a path switch.
     */
    fun hostFor(nodeId: String, use: Use = Use.SMALL): String? =
        PeerLinks.candidates(nodeId, use).firstOrNull()?.let { if (it.link == LinkType.BT || it.port == Ports.NODE) it.ip else it.hostPort }

    /** GET on exactly this address, no path selection or failover (heartbeat, hello, per-path probes). */
    suspend inline fun <reified T> getPinned(host: String, path: String, timeoutMs: Long? = null): T =
        json.decodeFromString<T>(call(HttpMethod.Get, host, path, "", timeoutMs, null, pinned = true))

    /** Signed request with a raw JSON body; returns the response body text. Used by link routes. */
    suspend fun raw(
        method: HttpMethod, host: String, path: String, body: String = "", timeoutMs: Long? = null, reqId: String? = null,
        pinned: Boolean = false,
    ): String = call(method, host, path, body, timeoutMs, reqId, pinned)

    suspend inline fun <reified T> post(host: String, path: String, timeoutMs: Long? = null): T =
        json.decodeFromString<T>(call(HttpMethod.Post, host, path, "", timeoutMs))

    suspend inline fun <reified T, reified B> post(host: String, path: String, body: B, timeoutMs: Long? = null): T =
        json.decodeFromString<T>(call(HttpMethod.Post, host, path, json.encodeToString(body), timeoutMs))

    companion object {
        /** Connect timeout for a path that has another path behind it, so a dead path fails over quickly. */
        const val FAILOVER_CONNECT_MS = 2_000L

        /** Longest connect wait of a request that passes its own, longer, request timeout (the client's default connect timeout is 5 s). */
        const val CONNECT_CAP_MS = 5_000L
    }
}
