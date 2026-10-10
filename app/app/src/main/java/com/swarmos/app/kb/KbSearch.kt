package com.swarmos.app.kb

import com.swarmos.app.pdu.Coverage
import com.swarmos.app.pdu.HybridRanker
import com.swarmos.app.pdu.PduHit
import com.swarmos.app.pdu.QueryUi

/** A card that matched a question. [cosine] is null when this phone has no usable embedding for the card or the question. */
data class KbHit(val entry: KbEntry, val cosine: Float?, val coverage: Double, val score: Double)

/** Cards are few (at most [KbLimits.MAX_CARDS]), so they are scored in memory: query-word coverage, plus cosine when both vectors exist. */
object KbSearch {
    const val MAX_HITS = 3

    /**
     * A card answers the question by itself (the answer view shows it at once) when it is close in meaning and also covers the
     * question's words, or, with no vectors, covers at least [KEYWORD_ANSWER_COVERAGE] of them. Stated floors, not yet measured on
     * a phone: re-measure with real saved answers before relying on them. Anything below only joins the prompt as one source.
     */
    const val ANSWER_COSINE = 0.60f
    const val KEYWORD_ANSWER_COVERAGE = 0.80

    /** Revoked cards are never returned. Vectors are unit length, 256 dims, made by the embedder that made [queryVec]. */
    fun rank(entries: List<KbEntry>, vectors: Map<String, FloatArray>, queryVec: FloatArray?, terms: List<String>): List<KbHit> =
        entries.asSequence().filter { it.usable }.mapNotNull { e ->
            val coverage = Coverage.of(terms, e.card.question + " " + e.card.answer)
            val v = vectors[e.card.id]
            val cos = if (queryVec != null && v != null && v.size == queryVec.size) dot(v, queryVec) else null
            if (!HybridRanker.passes(cos ?: 0f, coverage)) return@mapNotNull null
            KbHit(e, cos, coverage, if (cos != null) 0.5 * cos.coerceAtLeast(0f) + 0.5 * coverage else coverage)
        }.sortedWith(compareByDescending<KbHit> { it.score }.thenByDescending { it.entry.card.createdMs }).take(MAX_HITS).toList()

    fun answersByItself(h: KbHit): Boolean =
        if (h.cosine != null) h.cosine >= ANSWER_COSINE && h.coverage >= HybridRanker.MIN_COVERAGE else h.coverage >= KEYWORD_ANSWER_COVERAGE

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    /** The card as one more source for the prompt, listed among the others and not marked as more reliable. */
    fun asHit(h: KbHit, rank: Int) = PduHit(
        title = "Saved answer: " + h.entry.card.question.take(80),
        uriHash = "kb:" + h.entry.card.id,
        kind = "kb",
        dateMs = h.entry.card.createdMs,
        snippet = h.entry.card.answer,
        score = h.score,
        rank = rank,
    )

    /** "just now", "5 min", "3 h", "2 days": how old a saved answer is. */
    fun age(createdMs: Long, nowMs: Long): String {
        val s = ((nowMs - createdMs) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60} min"
            s < 86_400 -> "${s / 3600} h"
            s < 2 * 86_400 -> "1 day"
            else -> "${s / 86_400} days"
        }
    }
}

/** When "Save to knowledge bank" is offered, and what the card would contain. */
object KbDraft {
    private val Marker = Regex("""\[(\d{1,2})]""")

    /** What the owner sees before saving. [clipped]: the answer was longer than a card holds and was cut. */
    data class Draft(val question: String, val answer: String, val sources: List<KbSource>, val clipped: Boolean)

    /**
     * A finished answer that a real chat model wrote, whole, and that cites at least one of the sources shown. The sources of the
     * card are the cited ones. Not offered for a saved answer shown from the bank, or when the model stopped early.
     */
    fun of(q: QueryUi): Draft? {
        if (q.phase != "done" || q.model.isEmpty() || q.note != null || q.savedAnswer != null || q.answer.isBlank()) return null
        val cited = cited(q.answer, q.sources.size)
        if (cited.isEmpty()) return null
        val sources = cited.map { n -> q.sources[n - 1].let { KbSource(it.deviceName, it.hit.title, it.hit.dateMs, priv = it.localPrivate) } }
        return Draft(q.text.trim().take(KbLimits.MAX_QUESTION), clip(q.answer), sources.take(KbLimits.MAX_SOURCES), q.answer.trim().length > KbLimits.MAX_ANSWER)
    }

    /** The source numbers the answer cites, within the sources actually shown. */
    fun cited(answer: String, sourceCount: Int): List<Int> =
        Marker.findAll(answer).map { it.groupValues[1].toInt() }.filter { it in 1..sourceCount }.distinct().sorted().toList()

    /** Answer text for the card: at most [KbLimits.MAX_ANSWER] characters, cut with an ellipsis when longer. */
    fun clip(answer: String): String {
        val a = answer.trim()
        return if (a.length <= KbLimits.MAX_ANSWER) a else a.take(KbLimits.MAX_ANSWER - 1).trimEnd() + "…"
    }
}
