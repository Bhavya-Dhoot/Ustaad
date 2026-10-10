package com.swarmos.app.pdu

import kotlinx.serialization.Serializable

/**
 * Text that goes in front of what is embedded, in ONE place. EmbeddingGemma documents task prefixes
 * ("task: search result | query: ..." for queries, "title: ... | text: ..." for documents), but docs/models.md pins
 * none and docs/llama-server-api-notes.md does not exist, so both default to empty. Change them at runtime with the
 * debug route POST /v1/pdu/debug/prefixes; a changed document prefix re-embeds everything on the next index run.
 */
object PduEmbedConfig {
    @Volatile var queryPrefix: String = ""
    @Volatile var documentPrefix: String = ""

    /** What the query embedder receives. */
    fun query(text: String): String = queryPrefix + text

    /** What the document embedder receives: prefix, title line, chunk body. */
    fun document(title: String, body: String): String = documentPrefix + Chunker.withTitle(title, body)

    /** Stored with the index: vectors made under another embedder or document prefix are not comparable. */
    fun indexKey(modelName: String, prefix: String = documentPrefix): String = "$modelName|$prefix"

    fun load(store: PduStore) {
        queryPrefix = store.meta("queryPrefix") ?: ""
        documentPrefix = store.meta("documentPrefix") ?: ""
    }

    fun save(store: PduStore) {
        store.setMeta("queryPrefix", queryPrefix)
        store.setMeta("documentPrefix", documentPrefix)
    }
}

@Serializable
data class PrefixRequest(val query: String? = null, val document: String? = null)

@Serializable
data class PrefixReport(val query: String, val document: String, val reindexNeeded: Boolean)

/** One chunk in the calibration report. Titles of private files are replaced, so the route cannot name them. */
@Serializable
data class ScoreRow(
    val title: String,
    val kind: String,
    val isPrivate: Boolean,
    val cosine: Double,
    val denseRank: Int?,
    val bm25: Double?,
    val bm25Rank: Int?,
    val coverage: Double,
    val rrf: Double,
    val passes: Boolean,
)

@Serializable
data class CosineSpread(val min: Double, val median: Double, val p90: Double, val max: Double)

@Serializable
data class ScoreReport(
    val question: String,
    val embedModel: String,
    val queryPrefix: String,
    val terms: List<String>,
    val denseMinCosine: Double,
    val minCoverage: Double,
    val chunks: Int,
    val cosineSpread: CosineSpread?,
    val rows: List<ScoreRow>,
)
