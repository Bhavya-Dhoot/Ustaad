package com.swarmos.app.pdu

import kotlinx.serialization.Serializable

/** Wire format of POST /v1/pdu/query. Query text only: never embeddings. */
@Serializable
data class PduQuery(val qid: String, val text: String, val k: Int = 5, val deadlineMs: Int = 1500)

/** One snippet a phone is willing to hand to a peer. No path, no EXIF, no embedding. */
@Serializable
data class PduHit(
    val title: String,
    val uriHash: String,
    val kind: String,
    val dateMs: Long,
    val snippet: String,
    val score: Double,
    val rank: Int,
)

@Serializable
data class PduResponse(
    val qid: String,
    val hits: List<PduHit>,
    val indexedChunks: Int,
    val tookMs: Long,
    val withheldPrivate: Int,
    /** The sender's index version when it was asked ([IndexVersion]); null from a build that does not send it. */
    val indexVersion: Long? = null,
)

/** Answer of GET /v1/pdu/version. */
@Serializable
data class PduVersion(val indexVersion: Long)

/**
 * A phone is not trusted for the size of what it sends back. A response is cut to what a phone may send: at most [MAX_HITS] hits
 * (the query asks for at most that many), snippets of at most [Snippets.MAX] characters, and short title, id and kind fields.
 * An honest peer is never changed by it; only the layout of the screen and the prompt are protected from an oversized one.
 */
object PduWireLimits {
    const val MAX_HITS = 10
    const val MAX_TITLE = 200
    const val MAX_ID = 64
    const val MAX_KIND = 16
}

fun PduResponse.capped(): PduResponse = copy(
    hits = hits.take(PduWireLimits.MAX_HITS).map {
        it.copy(
            title = it.title.take(PduWireLimits.MAX_TITLE), uriHash = it.uriHash.take(PduWireLimits.MAX_ID),
            kind = it.kind.take(PduWireLimits.MAX_KIND), snippet = it.snippet.take(Snippets.MAX),
        )
    },
)

@Serializable
data class PduOpenRequest(val uriHash: String)

@Serializable
data class PduOpenResult(val opened: Boolean, val via: String, val title: String? = null, val error: String? = null)

/** A retrieved chunk on the owning phone. Never leaves the phone as-is; see [PeerResponder]. */
data class Candidate(
    val chunkId: Long,
    val docId: String,
    val title: String,
    val kind: String,
    val dateMs: Long,
    val folder: String,
    val isPrivate: Boolean,
    val text: String,
    val score: Double,
)

/** Hits from one device in the order that device ranked them. */
data class DeviceHits(val deviceId: String, val deviceName: String, val local: Boolean, val hits: List<PduHit>)

data class MergedHit(
    val deviceId: String,
    val deviceName: String,
    val local: Boolean,
    val hit: PduHit,
    val rrf: Double,
)
