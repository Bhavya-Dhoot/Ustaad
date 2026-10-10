package com.swarmos.app.agent

import com.swarmos.app.AppGraph
import com.swarmos.app.core.SwarmJson
import com.swarmos.app.pdu.DeviceHits
import com.swarmos.app.pdu.DeviceMerge
import com.swarmos.app.pdu.LocalSearch
import com.swarmos.app.pdu.MergedHit
import com.swarmos.app.pdu.PduEngine
import com.swarmos.app.pdu.PduQuery
import com.swarmos.app.pdu.PduResponse
import com.swarmos.app.pdu.PeerResponder
import com.swarmos.app.pdu.Snippets
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant
import java.time.ZoneId

enum class FileScope(val id: String) {
    PHONE("phone"),
    SWARM("swarm"),
    ;

    companion object {
        fun of(id: String): FileScope? = entries.firstOrNull { it.id == id }
    }
}

/** A validated `POST /v1/files/search`. */
class FileSearchCall(val query: String, val scope: FileScope, val maxResults: Int)

/** What one paired phone answered, as the route sees it: [status] is ok, offline or error; [response] only when ok. [asked] is false for a phone that was not called at all (marked away). */
class PeerFiles(val name: String, val status: String, val response: PduResponse?, val asked: Boolean = true)

/**
 * What the file search route needs from the Data Union. The route never reads the index itself: [searchLocal] gives candidates,
 * and [AgentFiles.search] passes them through [PeerResponder.respond], the call that answers a paired phone.
 */
class FileSource(
    /** Why this phone cannot be searched now (503 `index_unavailable`), or null. An index with nothing in it is not a reason: that is an empty list. */
    val unavailable: suspend () -> String?,
    val searchLocal: suspend (String) -> LocalSearch,
    val indexedChunks: () -> Int,
    val selfName: () -> String,
    /** The paired phones' answers to [q]; an offline phone is an entry with status offline, not a missing one. */
    val askPeers: suspend (PduQuery) -> List<PeerFiles>,
) {
    companion object {
        const val NO_EMBEDDER = "No embedding model is loaded on this phone, so files cannot be searched."
        const val REINDEX = "The file index was made by another embedding model; re-index in Ustaad (Data Union) first."

        /** [dense]: a real embedder is ready and the vectors were made by it; [embedderReady]: a real embedder is ready (the index is the other one's). */
        fun whyUnavailable(dense: Boolean, embedderReady: Boolean): String? = when {
            dense -> null
            embedderReady -> REINDEX
            else -> NO_EMBEDDER
        }

        /** The app's own Data Union. */
        fun app(): FileSource = FileSource(
            unavailable = {
                val dense = PduEngine.denseUsable()
                whyUnavailable(dense, PduEngine.embedder.value.ready)
            },
            searchLocal = { PduEngine.searchLocal(it) },
            indexedChunks = { PduEngine.store.chunkCount },
            selfName = { AppGraph.registry.self.value.name },
            // the same signed query Ask sends, one call per online paired phone; a phone the health monitor marked away is not called
            askPeers = { q ->
                coroutineScope {
                    AppGraph.registry.peers.value.map { p ->
                        async {
                            if (p.away) PeerFiles(p.info.name, AgentFiles.OFFLINE, null, asked = false)
                            else PduEngine.queryPeer(p, q, q.deadlineMs).let { PeerFiles(p.info.name, AgentFiles.publicStatus(it.status), it.response) }
                        }
                    }.awaitAll()
                }
            },
        )
    }
}

/** How one phone fared in a search, for the audit log: the phone's name, its status and how many of the answers came from it. */
class PhoneOutcome(val name: String, val status: String, val results: Int)

/** The answer body and what the audit entry records: [asked] paired phones were sent the query, [sentBytes] bytes of it in all. */
class FilesFound(val body: JsonObject, val results: Int, val phones: List<PhoneOutcome>, val asked: Int = 0, val sentBytes: Int = 0)

/** File search for other apps. Pure apart from [FileSource]: what may be shown is decided by [PeerResponder], never here. */
object AgentFiles {
    const val MAX_QUERY = 1000
    const val MAX_RESULTS = 10
    const val DEFAULT_RESULTS = 5
    const val MAX_TITLE = 200
    const val MAX_KIND = 32
    const val MAX_NAME = 64

    /** How long a paired phone has to answer; Ask uses 1500 ms, an agent can wait a little longer for a complete list. */
    const val PEER_DEADLINE_MS = 3_000

    const val OK = "ok"
    const val OFFLINE = "offline"
    const val ERROR = "error"

    /** A paired phone's status in the Data Union's words (ok, unreachable, error) in the endpoint's: ok, offline, error. */
    fun publicStatus(pduStatus: String): String = when (pduStatus) {
        "ok" -> OK
        "unreachable", "offline" -> OFFLINE
        else -> ERROR
    }

    /**
     * Searches this phone, and with scope swarm the online paired phones too. This phone's part is exactly what a paired phone would
     * get from it; the paired phones answer under their own rule. Hits are merged the way Ask merges them and cut to [FileSearchCall.maxResults].
     */
    suspend fun search(call: FileSearchCall, source: FileSource, qid: String, zone: ZoneId = ZoneId.systemDefault(), clock: () -> Long = System::currentTimeMillis): FilesFound =
        coroutineScope {
            val t0 = clock()
            val query = PduQuery(qid, call.query, call.maxResults, PEER_DEADLINE_MS)
            val peers = if (call.scope == FileScope.SWARM) async { source.askPeers(query) } else null
            val ls = source.searchLocal(call.query)
            val mine = PeerResponder.respond(qid, ls.candidates, ls.terms, call.maxResults, source.indexedChunks(), clock() - t0)
            val others = peers?.await().orEmpty()

            val selfName = source.selfName().take(MAX_NAME)
            val devices = listOf(DeviceHits("0", selfName, true, mine.hits)) +
                others.mapIndexedNotNull { i, p -> p.response?.let { DeviceHits("${i + 1}", p.name.take(MAX_NAME), false, it.hits) } }
            val merged = DeviceMerge.merge(devices, call.maxResults)

            val phones = listOf(PhoneOutcome(selfName, OK, merged.count { it.deviceId == "0" })) +
                others.mapIndexed { i, p -> PhoneOutcome(p.name.take(MAX_NAME), p.status, merged.count { it.deviceId == "${i + 1}" }) }
            val asked = others.count { it.asked }
            val sentBytes = SwarmJson.encodeToString(query).toByteArray().size * asked
            val body = buildJsonObject {
                put("object", "list")
                put("data", JsonArray(merged.map { publicHit(it, zone) }))
                put("scope", call.scope.id)
                put("incomplete", others.any { it.status != OK })
                putJsonArray("phones") {
                    for (p in phones) add(buildJsonObject {
                        put("name", p.name)
                        put("status", p.status)
                    })
                }
            }
            FilesFound(body, merged.size, phones, asked, sentBytes)
        }

    /**
     * One hit as another app sees it: the phone, the title, the date, the kind and a short snippet; the list is in order of relevance.
     * No score (a phone ranks over all its chunks, private ones included, so its number says a little about them), no path, no uri or
     * hash of it, no rank, nothing about files that were held back. Lengths are cut here too, because paired phones' answers are text
     * we do not control.
     */
    fun publicHit(m: MergedHit, zone: ZoneId): JsonObject = buildJsonObject {
        put("phone", m.deviceName.take(MAX_NAME))
        put("title", m.hit.title.take(MAX_TITLE))
        put("date", dateOf(m.hit.dateMs, zone)?.let { JsonPrimitive(it) } ?: JsonNull)
        put("kind", m.hit.kind.take(MAX_KIND))
        put("snippet", m.hit.snippet.take(Snippets.MAX))
    }

    /** `YYYY-MM-DD` in [zone], or null for a file with no date. */
    fun dateOf(ms: Long, zone: ZoneId): String? = if (ms <= 0) null else Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toString()
}
