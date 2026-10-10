package com.swarmos.app.link

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.CallFailed
import io.ktor.server.application.hooks.ResponseBodyReadyForSend
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/** A finished response kept so a duplicate request gets the same answer without running the handler again. */
class StoredResponse(val status: Int, val contentType: String?, val body: ByteArray)

/**
 * Server-side request dedup (X-Swarm-Req). The first request with an id runs; a second one with the same id
 * (the client retried on another path after a transport error) waits for the first to finish and gets the stored
 * response instead of running the handler twice. Entries live [ttlMs] after completion; the cache holds at most
 * [maxEntries] and [maxBytes] of response bodies. A handler that failed (no response stored) is forgotten so a
 * retry may run again.
 */
class RequestDedup(
    private val ttlMs: Long = 5 * 60_000L,
    private val maxEntries: Int = 256,
    private val maxBytes: Long = 32L * 1024 * 1024,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed interface Begin {
        data object Run : Begin
        class Replay(val stored: StoredResponse) : Begin
    }

    private class Entry {
        val done = CompletableDeferred<StoredResponse?>()
        @Volatile var stored: StoredResponse? = null
        @Volatile var finishedAt = 0L
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    val size: Int get() = entries.size

    /** [Begin.Run] when the caller should execute the request, [Begin.Replay] when the stored answer should be sent. */
    suspend fun begin(id: String): Begin {
        while (true) {
            evict()
            val mine = Entry()
            val prev = entries.putIfAbsent(id, mine) ?: return Begin.Run
            val st = prev.done.await()
            if (st != null) return Begin.Replay(st)
            // the first run failed and was forgotten: try to become the runner ourselves
            entries.remove(id, prev)
        }
    }

    fun complete(id: String, response: StoredResponse) {
        val e = entries[id] ?: return
        e.stored = response
        e.finishedAt = clock()
        e.done.complete(response)
    }

    fun abandon(id: String) {
        val e = entries.remove(id) ?: return
        e.done.complete(null)
    }

    private fun evict() {
        val now = clock()
        var bytes = 0L
        var finished = 0
        for ((k, e) in entries) {
            val fa = e.finishedAt
            if (fa > 0 && now - fa > ttlMs) { entries.remove(k, e); continue }
            if (fa > 0) { finished++; bytes += e.stored?.body?.size ?: 0 }
        }
        if (finished > maxEntries || bytes > maxBytes) {
            entries.entries.filter { it.value.finishedAt > 0 }.sortedBy { it.value.finishedAt }.forEach { (k, e) ->
                if (finished > maxEntries * 3 / 4 || bytes > maxBytes * 3 / 4) {
                    entries.remove(k, e)
                    finished--
                    bytes -= e.stored?.body?.size ?: 0
                }
            }
        }
    }
}

/** The one cache instance the node server uses. */
object NodeDedup {
    val cache = RequestDedup()
    val ID = AttributeKey<String>("swarm.req.id")
}

/**
 * Captures the response of every request that [NodeDedup] registered (attribute [NodeDedup.ID]) and files it in
 * the cache. Streaming bodies cannot be captured; those requests are forgotten instead of stored.
 */
val RequestDedupPlugin = createApplicationPlugin("SwarmRequestDedup") {
    on(ResponseBodyReadyForSend) { call, content ->
        val bytes: ByteArray? = when (content) {
            is TextContent -> content.text.toByteArray(Charsets.UTF_8)
            is OutgoingContent.ByteArrayContent -> content.bytes()
            is OutgoingContent.NoContent -> ByteArray(0)
            else -> null
        }
        val status = (content.status ?: call.response.status() ?: HttpStatusCode.OK).value
        // authenticate the answer: HMAC over status, the request's id and the body (see ResponseAuth)
        if (bytes != null) {
            call.response.headers.append(
                HEADER_RSP_SIG, ResponseAuth.sign(com.swarmos.app.AppGraph.pairingSecret, status, call.request.headers[HEADER_REQ] ?: "", String(bytes, Charsets.UTF_8)),
            )
        }
        val id = call.attributes.getOrNull(NodeDedup.ID) ?: return@on
        call.attributes.remove(NodeDedup.ID)
        if (bytes == null) NodeDedup.cache.abandon(id)
        else NodeDedup.cache.complete(id, StoredResponse(status, content.contentType?.toString(), bytes))
    }
    on(ResponseSent) { call -> call.attributes.getOrNull(NodeDedup.ID)?.let { NodeDedup.cache.abandon(it) } }
    on(CallFailed) { call, _ -> call.attributes.getOrNull(NodeDedup.ID)?.let { NodeDedup.cache.abandon(it) } }
}

/** ContentType for a stored response (JSON when the original did not say). */
fun StoredResponse.contentTypeOrJson(): ContentType =
    contentType?.let { runCatching { ContentType.parse(it) }.getOrNull() } ?: ContentType.Application.Json
