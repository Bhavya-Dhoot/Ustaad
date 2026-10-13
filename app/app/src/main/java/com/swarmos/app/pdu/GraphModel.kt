package com.swarmos.app.pdu

import kotlinx.serialization.Serializable

@Serializable
data class EntityRef(val type: String, val key: String)

/**
 * Wire format of POST /v1/pdu/graph. [op]: `match` the entities that match [text]; `neighbours` the entities that share a chunk with [a];
 * `connect` the documents that hold both [a] and [b]; `linked` the documents that hold the entities named in [text] (a question).
 */
@Serializable
data class GraphQuery(val qid: String, val op: String, val text: String = "", val a: EntityRef? = null, val b: EntityRef? = null, val limit: Int = 10)

/** A document in a graph answer: the title and hashed uri a query hit carries, and a snippet of at most [Snippets.MAX] characters. Nothing else of the file. */
@Serializable
data class GraphDocOut(val title: String, val uriHash: String, val kind: String, val dateMs: Long, val snippet: String, val count: Int, val priv: Boolean = false)

@Serializable
data class GraphEntityOut(val type: String, val key: String, val display: String, val count: Int, val docs: List<GraphDocOut>)

@Serializable
data class GraphResponse(
    val qid: String,
    val op: String,
    val entities: List<GraphEntityOut> = emptyList(),
    val docs: List<GraphDocOut> = emptyList(),
    /** Private documents that took part in the question and were left out of this answer, counted once each. */
    val withheldPrivate: Int = 0,
    val indexVersion: Long? = null,
)

object GraphWire {
    val OPS = setOf("match", "neighbours", "connect", "linked")
    const val MAX_ENTITIES = 20
    const val MAX_DOCS = 5
    const val MAX_TEXT = 500

    fun valid(q: GraphQuery): Boolean =
        q.op in OPS && q.text.length <= MAX_TEXT && (q.op != "neighbours" || q.a != null) && (q.op != "connect" || (q.a != null && q.b != null))
}

/** A phone is not trusted for the size of what it sends back: a graph answer is cut to what a phone may send. */
fun GraphResponse.capped(): GraphResponse {
    fun GraphDocOut.cut() = copy(
        title = title.take(PduWireLimits.MAX_TITLE), uriHash = uriHash.take(PduWireLimits.MAX_ID), kind = kind.take(PduWireLimits.MAX_KIND),
        snippet = snippet.take(Snippets.MAX),
    )
    return copy(
        entities = entities.take(GraphWire.MAX_ENTITIES).map {
            it.copy(type = it.type.take(16), key = it.key.take(120), display = it.display.take(120), docs = it.docs.take(GraphWire.MAX_DOCS).map { d -> d.cut() })
        },
        docs = docs.take(GraphWire.MAX_ENTITIES).map { it.cut() },
    )
}

// ---- what the store can say -----------------------------------------------------------------------------------

class EntityRow(val id: Long, val type: String, val key: String, val display: String)

/** One document as the graph tables see it: [count] is mentions, or shared chunks, depending on the question. */
class DocRow(
    val docId: String, val title: String, val kind: String, val dateMs: Long, val folder: String, val storedPrivate: Boolean,
    val chunkId: Long, val count: Int,
) {
    /** The same rule that decides which chunks a peer's query may return. */
    val isPrivate: Boolean get() = SharePolicy.effectivePrivate(folder, storedPrivate)
}

class LinkRow(val other: EntityRow, val doc: DocRow)

/** Facts out of the graph tables. Who may see them is decided in [GraphAnswers], never here. */
interface GraphRows {
    fun entity(type: String, key: String): EntityRow?
    fun entitiesLike(tokens: List<String>, limit: Int): List<EntityRow>
    /** One row per document that mentions the entity. */
    fun mentionDocs(entityId: Long): List<DocRow>
    /** One row per (entity that shares a chunk with this one, document). */
    fun linkDocs(entityId: Long): List<LinkRow>
    /** The documents that mention both entities; the count is the chunks in which they appear together (0 when apart). */
    fun commonDocs(a: Long, b: Long): List<DocRow>
    fun chunkText(chunkId: Long): String?
}

/**
 * Answers a graph question from this phone's rows. With [includePrivate] false (every answer for another device) a document in a
 * private folder contributes nothing: no entity, no mention, no link, no snippet. It is only counted, once, in `withheldPrivate`.
 * An entity that only private documents mention is not in the answer at all, and the text shown for an entity is made from its key
 * alone, so no wording of a private file reaches another device through a shared entity.
 */
object GraphAnswers {
    fun answer(q: GraphQuery, rows: GraphRows, includePrivate: Boolean, versions: VersionCounters? = null): GraphResponse {
        val limit = q.limit.coerceIn(1, GraphWire.MAX_ENTITIES)
        val withheld = HashSet<String>()
        fun visible(d: DocRow): Boolean = (includePrivate || !d.isPrivate).also { if (!it) withheld += d.docId }
        fun doc(d: DocRow, about: List<EntityRow>): GraphDocOut {
            val terms = about.flatMap { e -> e.display.lowercase().split(' ') }.filter { it.length > 1 }
            val snippet = rows.chunkText(d.chunkId)?.let { Snippets.best(it, terms) }.orEmpty()
            return GraphDocOut(d.title, d.docId, d.kind, d.dateMs, snippet, d.count, priv = d.isPrivate)
        }
        fun entity(ref: EntityRef?) = ref?.let { rows.entity(it.type, it.key) }
        val a = entity(q.a)
        val b = entity(q.b)
        var entities = emptyList<GraphEntityOut>()
        var docs = emptyList<GraphDocOut>()
        when (q.op) {
            "match" -> entities = GraphLookup.candidates(q.text, rows).mapNotNull { e ->
                val seen = rows.mentionDocs(e.id).filter(::visible).sortedByDescending { it.count }
                if (seen.isEmpty()) null
                else GraphEntityOut(e.type, e.key, e.display, seen.sumOf { it.count }, seen.take(GraphWire.MAX_DOCS).map { doc(it, listOf(e)) })
            }.sortedByDescending { it.count }.take(limit)
            "neighbours" -> if (a != null) entities = rows.linkDocs(a.id).filter { visible(it.doc) }.groupBy { it.other.id }.values.map { ls ->
                val o = ls.first().other
                val seen = ls.sortedByDescending { it.doc.count }
                GraphEntityOut(o.type, o.key, o.display, seen.sumOf { it.doc.count }, seen.take(GraphWire.MAX_DOCS).map { doc(it.doc, listOf(a, o)) })
            }.sortedByDescending { it.count }.take(limit)
            "connect" -> if (a != null && b != null) docs = rows.commonDocs(a.id, b.id).filter(::visible).sortedByDescending { it.count }.take(limit).map { doc(it, listOf(a, b)) }
            "linked" -> {
                val named = GraphRetrieval.named(q.text, rows)
                docs = GraphRetrieval.linked(named, rows, ::visible).take(limit).map { (d, about) -> doc(d, about) }
            }
        }
        return GraphResponse(q.qid, q.op, entities, docs, withheld.size, versions?.forPeers)
    }
}

object GraphLookup {
    /** Entities a text names (read with the extractor, so "Rs 1,200" finds INR 1200) and entities whose key holds all its words. */
    fun candidates(text: String, rows: GraphRows): List<EntityRow> {
        val named = GraphExtract.entities(text).mapNotNull { rows.entity(it.type, it.key) }
        val words = QueryTerms.terms(text).filter { it.length >= 3 }.take(4)
        val like = if (words.isEmpty()) emptyList() else rows.entitiesLike(words, 50)
        return (named + like).distinctBy { it.id }
    }
}

/**
 * Documents linked to the entities a question names. Only the entities the extractor recognises in the question are used (capitalised
 * names, dates, amounts, ids, e-mail addresses, phone numbers): a question in lower case names none. A document that holds more of
 * the named entities comes first, then one that mentions them more often.
 */
object GraphRetrieval {
    /**
     * OFF. The answer path calls this only when it is true. It is switched on after a measurement on phones: whether the extra sources
     * make answers better or only longer, and what the extra question to every phone costs in time. Until then no answer changes.
     */
    const val ENABLED = false
    const val MAX_DOCS = 3

    fun named(question: String, rows: GraphRows): List<EntityRow> =
        GraphExtract.entities(question).mapNotNull { rows.entity(it.type, it.key) }.distinctBy { it.id }

    fun linked(named: List<EntityRow>, rows: GraphRows, visible: (DocRow) -> Boolean): List<Pair<DocRow, List<EntityRow>>> {
        val byDoc = LinkedHashMap<String, Pair<DocRow, MutableList<EntityRow>>>()
        for (e in named) for (d in rows.mentionDocs(e.id)) if (visible(d)) byDoc.getOrPut(d.docId) { d to ArrayList() }.second += e
        return byDoc.values.sortedWith(
            compareByDescending<Pair<DocRow, MutableList<EntityRow>>> { it.second.size }.thenByDescending { it.first.count }.thenBy { it.first.title },
        )
            .map { it.first to it.second.toList() }
    }
}

// ---- the asking side ----------------------------------------------------------------------------------------------

/** One device's answer to a graph question: this phone's own, or a peer's after [capped]. */
class DeviceGraph(val deviceId: String, val deviceName: String, val local: Boolean, val response: GraphResponse)

@Serializable
data class MergedGraphDoc(val deviceId: String, val deviceName: String, val local: Boolean, val doc: GraphDocOut)

@Serializable
data class MergedEntity(val type: String, val key: String, val display: String, val count: Int, val devices: List<String>, val docs: List<MergedGraphDoc>)

@Serializable
data class MergedGraph(val entities: List<MergedEntity>, val docs: List<MergedGraphDoc>, val withheldPrivate: Int)

object GraphMerge {
    /** The same thing found on several devices is one entity (same type and normalised key): counts add up, documents are listed by device. */
    fun merge(answers: List<DeviceGraph>, limit: Int = GraphWire.MAX_ENTITIES): MergedGraph {
        val byKey = LinkedHashMap<Mention, MutableList<Pair<DeviceGraph, GraphEntityOut>>>()
        for (a in answers) for (e in a.response.entities) byKey.getOrPut(Mention(e.type, e.key)) { ArrayList() } += a to e
        val entities = byKey.map { (m, found) ->
            MergedEntity(
                m.type, m.key, found.first().second.display, found.sumOf { it.second.count }, found.map { it.first.deviceName }.distinct(),
                found.flatMap { (dev, e) -> e.docs.map { MergedGraphDoc(dev.deviceId, dev.deviceName, dev.local, it) } }
                    .distinctBy { it.deviceId to it.doc.uriHash }.sortedByDescending { it.doc.count }.take(GraphWire.MAX_DOCS),
            )
        }.sortedWith(compareByDescending<MergedEntity> { it.devices.size }.thenByDescending { it.count }).take(limit)
        val docs = answers.flatMap { dev -> dev.response.docs.map { MergedGraphDoc(dev.deviceId, dev.deviceName, dev.local, it) } }
            .distinctBy { it.deviceId to it.doc.uriHash }.sortedByDescending { it.doc.count }.take(limit)
        return MergedGraph(entities, docs, answers.sumOf { it.response.withheldPrivate })
    }
}
