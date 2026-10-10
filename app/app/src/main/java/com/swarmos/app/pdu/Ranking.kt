package com.swarmos.app.pdu

import com.swarmos.app.llm.ChatMessage
import com.swarmos.app.llm.ChatRequest
import java.time.Instant
import java.time.ZoneId
import java.util.PriorityQueue

data class Scored(val row: Int, val score: Float)

/** Brute-force cosine over a row-major float matrix. Rows and the query are already unit length, so cosine is a dot product. */
object Cosine {
    fun topK(matrix: FloatArray, dim: Int, rows: Int, query: FloatArray, n: Int): List<Scored> {
        require(query.size == dim) { "query has ${query.size} dims, store has $dim" }
        if (rows == 0 || n <= 0) return emptyList()
        val heap = PriorityQueue<Scored>(n + 1, compareBy<Scored> { it.score }) // min-heap, smallest of the kept on top
        for (r in 0 until rows) {
            var dot = 0f
            val base = r * dim
            for (d in 0 until dim) dot += matrix[base + d] * query[d]
            if (heap.size < n) heap.add(Scored(r, dot))
            else if (dot > (heap.peek()?.score ?: Float.NEGATIVE_INFINITY)) { heap.poll(); heap.add(Scored(r, dot)) }
        }
        return heap.sortedByDescending { it.score }
    }
}

/** Reciprocal rank fusion. A list contributes 1 / (k + rank), rank starting at 1. */
object Rrf {
    const val K = 60

    fun <T> fuse(lists: List<List<T>>, k: Int = K): List<Pair<T, Double>> {
        val score = LinkedHashMap<T, Double>()
        for (list in lists) list.forEachIndexed { i, item -> score[item] = (score[item] ?: 0.0) + 1.0 / (k + i + 1) }
        return score.entries.map { it.key to it.value }.sortedByDescending { it.second } // stable: ties keep first appearance
    }
}

data class Fused(val chunkId: Long, val score: Double, val cosine: Float, val inBm25: Boolean)

/** Dense + BM25 on one phone, fused with RRF. */
object HybridRanker {
    /**
     * A dense match must be at least this similar to count. Measured on Phone A with EmbeddingGemma-300M Q8_0 (first 256 dims,
     * renormalised, no task prefixes) over 11 demo files and 6 questions: relevant files 0.43 to 0.61, other files 0.15 to 0.39
     * (a typical file sits near 0.30, so 0.30 let unrelated files through). Small sample: re-measure with POST /v1/pdu/debug/scores
     * whenever the model or the prefixes change.
     */
    const val DENSE_MIN_COSINE = 0.40f

    /**
     * [dense] is the cosine top-N as (chunkId, cosine), best first; [bm25] the BM25 top-N chunk ids, best first.
     * Only dense matches that reach [DENSE_MIN_COSINE] take part, so with a meaningless embedder (or an unrelated
     * question) the ranking is pure BM25 and a dense-only noise match cannot appear. Score is RRF normalised to 0..1
     * (1 = first in every list that has entries).
     */
    fun fuse(dense: List<Pair<Long, Float>>, bm25: List<Long>): List<Fused> {
        val cos = dense.toMap()
        val inBm25 = bm25.toHashSet()
        val lists = listOf(dense.filter { it.second >= DENSE_MIN_COSINE }.map { it.first }, bm25).filter { it.isNotEmpty() }
        if (lists.isEmpty()) return emptyList()
        val max = lists.size.toDouble() / (Rrf.K + 1)
        return Rrf.fuse(lists).map { (id, s) -> Fused(id, s / max, cos[id] ?: 0f, id in inBm25) }
    }

    /** A lexical-only hit must contain at least this share of the query words, or a one-word overlap ("bill") would count as an answer. */
    const val MIN_COVERAGE = 0.5

    /** Relevant when it is semantically close, or lexically covers enough of the question. */
    fun passes(cosine: Float, coverage: Double): Boolean = cosine >= DENSE_MIN_COSINE || coverage >= MIN_COVERAGE

    /** One chunk per document, keeping the best-ranked one and the order. */
    fun collapseByDoc(cands: List<Candidate>): List<Candidate> {
        val seen = HashSet<String>()
        return cands.filter { seen.add(it.docId) }
    }
}

/** Share of the query words that a text contains (stem-tolerant). Comparable across phones, unlike BM25 values. */
object Coverage {
    fun of(terms: List<String>, text: String): Double {
        if (terms.isEmpty()) return 0.0
        val tokens = Regex("[\\p{L}\\p{N}\\p{M}]+").findAll(text.lowercase()).map { it.value }.toSet()
        return terms.count { t -> tokens.any { matches(t, it) } }.toDouble() / terms.size
    }

    fun matches(term: String, token: String): Boolean {
        if (term == token) return true
        var p = 0
        while (p < term.length && p < token.length && term[p] == token[p]) p++
        return p >= 4 && p >= 0.75 * minOf(term.length, token.length)
    }
}

object SharePolicy {
    /** Every folder starts private: nothing is answered to other phones until the owner shares the folder. */
    fun startsPrivate(@Suppress("UNUSED_PARAMETER") folder: String): Boolean = true

    /** A folder with a segment named Private (any case) can never be shared, whatever the switch says. */
    fun mustStayPrivate(folder: String): Boolean =
        folder.split('/', '\\').any { it.trim().equals("private", ignoreCase = true) }

    /** The privacy a chunk gets: the folder's stored choice, forced to private for a Private folder. */
    fun effectivePrivate(folder: String, stored: Boolean): Boolean = stored || mustStayPrivate(folder)

    /**
     * The folders among [publicChunkFolders] (folders that still hold public chunks) whose chunks must be forced private: the folder's
     * stored choice is private, it has no stored choice (a new folder starts private), or its path has a Private segment.
     */
    fun foldersToForce(publicChunkFolders: Collection<String>, storedPrivate: Map<String, Boolean>): List<String> =
        publicChunkFolders.distinct().filter { effectivePrivate(it, storedPrivate[it] ?: startsPrivate(it)) }

    /**
     * The privacy a document gets when it lands in [folder] without being read again (a photo moved in the gallery): the folder's stored
     * choice, private when this is the first time the folder is seen, and always private for a Private folder.
     */
    fun privacyAfterMove(folder: String, storedChoice: Boolean?): Boolean = effectivePrivate(folder, storedChoice ?: startsPrivate(folder))

    fun isShareable(c: Candidate): Boolean = !c.isPrivate && !mustStayPrivate(c.folder)

    class PeerSelection(val shared: List<Candidate>, val withheldPrivate: Int)

    /** What a peer may see: the first [k] public candidates; private ones are only counted, by document. */
    fun forPeer(candidates: List<Candidate>, k: Int): PeerSelection = PeerSelection(
        shared = candidates.filter { isShareable(it) }.take(k),
        withheldPrivate = candidates.filter { !isShareable(it) }.map { it.docId }.distinct().size,
    )
}

/** Builds the only thing that leaves a phone for a query. */
object PeerResponder {
    fun respond(
        qid: String, candidates: List<Candidate>, terms: List<String>, k: Int, indexedChunks: Int, tookMs: Long,
    ): PduResponse {
        val sel = SharePolicy.forPeer(candidates, k)
        val hits = sel.shared.mapIndexed { i, c -> hit(c, terms, i + 1) }
        return PduResponse(qid, hits, indexedChunks, tookMs, sel.withheldPrivate)
    }

    /** Same shape for the owner's own screen (private items allowed there). */
    fun hit(c: Candidate, terms: List<String>, rank: Int) = PduHit(
        title = c.title,
        uriHash = c.docId,
        kind = c.kind,
        dateMs = c.dateMs,
        snippet = Snippets.best(c.text, terms),
        score = c.score,
        rank = rank,
    )
}

object DeviceMerge {
    const val LIMIT = 6
    private const val DUPLICATE_JACCARD = 0.8

    /**
     * RRF across devices on per-device ranks (each device list is already in that device's rank order),
     * within-device score as tie-breaker, then near-identical snippets are dropped and the top [limit] kept.
     */
    fun merge(devices: List<DeviceHits>, limit: Int = LIMIT): List<MergedHit> {
        val all = devices.flatMapIndexed { di, d ->
            d.hits.sortedBy { it.rank }.mapIndexed { i, h ->
                Triple(di, MergedHit(d.deviceId, d.deviceName, d.local, h, 1.0 / (Rrf.K + i + 1)), i)
            }
        }
        val ordered = all.sortedWith(
            compareByDescending<Triple<Int, MergedHit, Int>> { it.second.rrf }
                .thenByDescending { it.second.hit.score }
                .thenBy { it.first }
                .thenBy { it.third },
        ).map { it.second }

        val kept = ArrayList<MergedHit>()
        val keptWords = ArrayList<Set<String>>()
        for (m in ordered) {
            val w = words(m.hit.snippet)
            if (keptWords.any { jaccard(it, w) >= DUPLICATE_JACCARD }) continue
            kept += m
            keptWords += w
            if (kept.size == limit) break
        }
        return kept
    }

    fun words(s: String): Set<String> =
        Regex("[\\p{L}\\p{N}]+").findAll(s.lowercase()).map { it.value }.toSet()

    fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val inter = a.count { it in b }
        return inter.toDouble() / (a.size + b.size - inter)
    }
}

object PromptBuilder {
    const val SYSTEM =
        "You answer a question about the user's own files using only the numbered sources below. " +
            "Answer in at most 80 words. Put the source number in square brackets, like [1], after each fact. " +
            "If the sources do not contain the answer, say that you found nothing relevant. Do not invent anything."

    fun sourceLine(n: Int, deviceName: String, hit: PduHit): String =
        "[$n] $deviceName · ${hit.title} · ${day(hit.dateMs)}: ${hit.snippet.replace(Regex("\\s+"), " ")}"

    /** Lean sources: a source stays only if its score is at least this share of the best score. */
    const val LEAN_SCORE_SHARE = 0.6

    /** Lean sources: at most this many, saved answers (knowledge bank cards) included. */
    const val LEAN_MAX_SOURCES = 4

    /**
     * Which of [sources] the model is given when lean sources are on, with each one's place in [sources] (its [IndexedValue.index];
     * the number printed for the model stays index + 1, so the citations still match the list on the screen). A source with no
     * snippet (the owner's private-file placeholder) is left out, a source whose score is under [LEAN_SCORE_SHARE] of the best score
     * among the others is left out, at most [LEAN_MAX_SOURCES] are kept in their original order, and a saved answer's text is cut
     * to the snippet maximum like every other source.
     */
    fun leanSelect(sources: List<MergedHit>): List<IndexedValue<MergedHit>> {
        val usable = sources.withIndex().filter { it.value.hit.snippet.isNotBlank() }
        val top = usable.maxOfOrNull { it.value.hit.score } ?: return emptyList()
        return usable.filter { it.value.hit.score >= LEAN_SCORE_SHARE * top }.take(LEAN_MAX_SOURCES).map { iv ->
            val h = iv.value.hit
            if (h.kind == "kb" && h.snippet.length > Snippets.MAX) {
                IndexedValue(iv.index, iv.value.copy(hit = h.copy(snippet = h.snippet.take(Snippets.MAX - 1).trimEnd() + "\u2026")))
            } else iv
        }
    }

    fun build(question: String, sources: List<MergedHit>, lean: Boolean = false): ChatRequest {
        val chosen = if (lean) leanSelect(sources) else sources.withIndex().toList()
        val body = chosen.joinToString("\n") { sourceLine(it.index + 1, it.value.deviceName, it.value.hit) }
        return ChatRequest(
            messages = listOf(
                ChatMessage("system", SYSTEM),
                ChatMessage("user", "Sources:\n$body\n\nQuestion: $question"),
            ),
            maxTokens = 160,
            temperature = 0.2,
        )
    }

    fun day(ms: Long): String =
        if (ms <= 0) "undated" else Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toString()
}
