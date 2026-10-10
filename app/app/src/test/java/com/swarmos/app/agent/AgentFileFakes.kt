package com.swarmos.app.agent

import com.swarmos.app.pdu.Candidate
import com.swarmos.app.pdu.LocalSearch
import com.swarmos.app.pdu.PduHit
import com.swarmos.app.pdu.PduQuery
import com.swarmos.app.pdu.PduResponse
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Stand-ins for the Data Union behind the file search route: candidates the way the index returns them, and paired phones that answer or do not. */
internal object FileFakes {
    val SEPT_3: Long = Instant.parse("2026-09-03T12:00:00Z").toEpochMilli()

    /** A chunk as the index returns it: [isPrivate] is the folder's stored choice, [folder] may also be named Private. */
    fun candidate(
        id: Long, hash: String, title: String, text: String, folder: String = "Pictures/Shared", isPrivate: Boolean = false,
        kind: String = "photo", dateMs: Long = SEPT_3, score: Double = 0.9,
    ) = Candidate(id, hash, title, kind, dateMs, folder, isPrivate, text, score)

    fun peerHit(title: String, snippet: String, rank: Int = 1, hash: String = "peer-hash-$rank", kind: String = "document", dateMs: Long = SEPT_3, score: Double = 0.8) =
        PduHit(title, hash, kind, dateMs, snippet, score, rank)

    fun peer(name: String, vararg hits: PduHit, withheld: Int = 0) =
        PeerFiles(name, AgentFiles.OK, PduResponse("q", hits.toList(), indexedChunks = 99, tookMs = 5, withheldPrivate = withheld))

    fun offline(name: String) = PeerFiles(name, AgentFiles.OFFLINE, null)

    /** What the route and the search did, for the tests to read. */
    class Calls {
        val local = AtomicInteger()
        val peers = AtomicInteger()
        val queries = CopyOnWriteArrayList<PduQuery>()
    }

    fun source(
        candidates: List<Candidate>,
        calls: Calls = Calls(),
        peers: List<PeerFiles> = emptyList(),
        unavailable: String? = null,
        name: String = "Phone A",
        chunks: Int = 42,
        beforeSearch: suspend () -> Unit = {},
    ) = FileSource(
        unavailable = { unavailable },
        searchLocal = { text ->
            calls.local.incrementAndGet()
            beforeSearch()
            LocalSearch(candidates, text.lowercase().split(' '), embedMs = 1, searchMs = 1)
        },
        indexedChunks = { chunks },
        selfName = { name },
        askPeers = { q ->
            calls.peers.incrementAndGet()
            calls.queries += q
            peers
        },
    )
}
