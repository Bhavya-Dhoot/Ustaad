package com.swarmos.app.pdu

import com.swarmos.app.AppGraph
import com.swarmos.app.core.PeerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/** Graph questions: answering a peer, asking the phones, and the documents linked to what a question names. */
object PduGraph {
    /** A peer's question, answered from this phone's graph and never with a private document. Logs counts only. */
    suspend fun serve(q: GraphQuery, fromNode: String): GraphResponse = withContext(Dispatchers.IO) {
        val store = PduEngine.store
        val r = GraphAnswers.answer(q, store.graph, includePrivate = false, versions = store.versions())
        AppGraph.audit.append(
            "pdu.graph_served",
            JsonObject(mapOf(
                "qid" to JsonPrimitive(q.qid), "peer" to JsonPrimitive(fromNode), "op" to JsonPrimitive(q.op),
                "n" to JsonPrimitive(r.entities.size + r.docs.size), "withheld" to JsonPrimitive(r.withheldPrivate),
            )),
        )
        r
    }

    /**
     * Asks this phone and every phone that is online, and merges the answers by normalised key. [ownPrivate]: this phone's own answer
     * may hold its own private documents (for the owner's screen); the answers of the others never do.
     */
    suspend fun ask(q: GraphQuery, deadlineMs: Int = 1500, ownPrivate: Boolean): MergedGraph = coroutineScope {
        val self = AppGraph.registry.self.value
        val local = async(Dispatchers.IO) {
            DeviceGraph(self.nodeId, self.name, true, GraphAnswers.answer(q, PduEngine.store.graph, ownPrivate, PduEngine.store.versions()))
        }
        val remote = AppGraph.registry.peers.value.filter { !it.away }.map { p -> async { askPeer(p, q, deadlineMs) } }
        GraphMerge.merge(listOf(local.await()) + remote.mapNotNull { it.await() })
    }

    /** One paired phone's answer, cut to the sizes a phone may send; null when it did not answer in time. Only cancellation is thrown. */
    private suspend fun askPeer(p: PeerState, q: GraphQuery, deadlineMs: Int): DeviceGraph? = try {
        withTimeoutOrNull(deadlineMs.toLong()) { AppGraph.nodeClient.post<GraphResponse, GraphQuery>(p.info.ip, "/pdu/graph", q) }
            ?.let { DeviceGraph(p.info.nodeId, p.info.name, false, it.capped()) }
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        null
    }

    /**
     * The documents linked to the entities the question names, on this phone and the others, as extra sources for the answer, numbered
     * after the [found] ones and without any that is already among them. Never throws: an answer without them is the answer as before.
     */
    suspend fun linkedSources(question: String, found: List<SourceUi>): List<SourceUi> = try {
        val have = found.mapTo(HashSet()) { it.hit.uriHash }
        ask(GraphQuery(UUID.randomUUID().toString().take(8), "linked", question, limit = GraphRetrieval.MAX_DOCS), ownPrivate = true).docs
            .filter { it.doc.uriHash !in have }.take(GraphRetrieval.MAX_DOCS).mapIndexed { i, d ->
                // no score of its own: a linked document is not a ranked hit, so it gets a middle one
                val hit = PduHit(d.doc.title, d.doc.uriHash, d.doc.kind, d.doc.dateMs, d.doc.snippet, 0.5, found.size + i + 1)
                SourceUi(found.size + i + 1, d.deviceId, d.deviceName, d.local, hit, d.local && d.doc.priv)
            }
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        emptyList()
    }
}
