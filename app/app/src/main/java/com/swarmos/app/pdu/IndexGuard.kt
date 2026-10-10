package com.swarmos.app.pdu

import com.swarmos.app.llm.FakeLlmClient
import com.swarmos.app.llm.LlmClient

/** What the guard needs from the store. [PduStore] implements it; tests use an in-memory copy. */
interface IndexBacking {
    val chunkCount: Int

    /** Files whose chunks were removed by [wipe] and that have not been read again yet. */
    val pendingCount: Int
    fun meta(key: String): String?
    fun setMeta(key: String, value: String)

    /** Remove every chunk. The document list stays, marked as waiting to be read again, so a killed re-index can resume. Folder privacy stays. */
    fun wipe()
}

/**
 * The only code that may wipe the index, and the rule for when. Vectors from different embedders cannot be compared, so:
 * a fake or not-ready embedder never indexes, scans or wipes; a changed embedder or document prefix is reported as
 * "re-index needed" and waits for an explicit owner action; a fresh, empty store simply adopts the current key.
 * Even the owner's re-index does not empty anything up front: see [Outcome.ProceedReplacing] and [commitReplace].
 */
object IndexGuard {
    const val FAKE = "FAKE"
    const val NOT_LOADED = "Embedder not loaded"
    const val REINDEX_NEEDED = "Re-index needed"

    sealed interface Outcome {
        data object Proceed : Outcome

        /** Owner-forced re-index: nothing was changed yet. Call [commitReplace] when the first new vectors exist, then store. */
        data object ProceedReplacing : Outcome
        data class Refused(val reason: String, val reindexNeeded: Boolean = false) : Outcome
    }

    fun isReal(modelName: String): Boolean = modelName != FAKE && modelName.isNotBlank()

    /** A real model (never the placeholder client) whose server answers right now. */
    suspend fun usable(client: LlmClient): Boolean =
        client !is FakeLlmClient && isReal(client.modelName) && runCatching { client.ready() }.getOrDefault(false)

    /** True when chunks exist that were embedded under another embedder or document prefix. */
    fun reindexNeeded(b: IndexBacking, embedName: String, documentPrefix: String): Boolean =
        b.chunkCount > 0 && b.meta(KEY) != PduEmbedConfig.indexKey(embedName, documentPrefix)

    /** Call before every index or scan run. [ownerForce] is an explicit owner action (the Re-index button, a debug force). */
    fun prepare(b: IndexBacking, embedName: String, embedReady: Boolean, documentPrefix: String, ownerForce: Boolean): Outcome {
        if (!embedReady || !isReal(embedName)) return Outcome.Refused(NOT_LOADED, reindexNeeded(b, embedName, documentPrefix))
        val key = PduEmbedConfig.indexKey(embedName, documentPrefix)
        val stored = b.meta(KEY)
        return when {
            // a re-index that was cut short under this same key: carry on with the files still waiting, wipe nothing
            ownerForce && stored == key && b.pendingCount > 0 -> Outcome.Proceed
            ownerForce -> Outcome.ProceedReplacing
            stored == key -> Outcome.Proceed
            b.chunkCount == 0 -> { b.setMeta("embedModel", embedName); b.setMeta(KEY, key); Outcome.Proceed }
            else -> Outcome.Refused(REINDEX_NEEDED, reindexNeeded = true)
        }
    }

    /** The first new vectors of an owner-forced re-index exist: drop the old chunks, adopt the new key. */
    fun commitReplace(b: IndexBacking, embedName: String, documentPrefix: String) {
        b.wipe()
        b.setMeta("embedModel", embedName)
        b.setMeta(KEY, PduEmbedConfig.indexKey(embedName, documentPrefix))
    }

    private const val KEY = "embedKey"
}
