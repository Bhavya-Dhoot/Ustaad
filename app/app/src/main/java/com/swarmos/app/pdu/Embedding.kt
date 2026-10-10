package com.swarmos.app.pdu

import com.swarmos.app.llm.LlmClient

/** The embedding server went away (or was swapped for the placeholder client) mid-run. Nothing from the file in hand was stored. */
class EmbedderStopped : RuntimeException("Embedder stopped")

/**
 * Embeds in batches with one client for the whole run. Before every batch the client is checked again: it must still be
 * the phone's current embedder, a real model (never the placeholder client, whose hash vectors would be stored under the
 * real key) and answering. If not, [EmbedderStopped] is thrown and nothing is returned, so the caller stores nothing partial.
 */
object BatchEmbedder {
    const val BATCH = 16

    suspend fun usable(client: LlmClient, current: LlmClient): Boolean = client === current && IndexGuard.usable(client)

    suspend fun embedAll(client: LlmClient, current: () -> LlmClient, texts: List<String>): List<FloatArray> {
        val out = ArrayList<FloatArray>(texts.size)
        for (batch in texts.chunked(BATCH)) {
            if (!usable(client, current())) throw EmbedderStopped()
            out += client.embed(batch)
        }
        return out
    }
}

/**
 * One index run. The embedder is captured once at the start. An owner-forced re-index ([replacing]) leaves the old index
 * alone until the first new vectors exist: [beforeStore] then swaps it out, right before that file is stored.
 */
class IndexRun(
    private val backing: IndexBacking,
    val client: LlmClient,
    private val current: () -> LlmClient,
    private val documentPrefix: String,
    private val replacing: Boolean,
) {
    private var replaced = false

    /** True for the whole of an owner-forced re-index: every file is read again, not only changed ones. */
    val rereadAll: Boolean get() = replacing

    suspend fun embed(texts: List<String>): List<FloatArray> = BatchEmbedder.embedAll(client, current, texts)

    /** Call right before a file is stored. [embedded]: this file produced new vectors (a file without text proves nothing). */
    fun beforeStore(embedded: Boolean) {
        if (replacing && !replaced && embedded) {
            IndexGuard.commitReplace(backing, client.modelName, documentPrefix)
            replaced = true
        }
    }
}
