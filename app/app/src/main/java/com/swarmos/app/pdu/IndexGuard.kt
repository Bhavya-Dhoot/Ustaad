package com.swarmos.app.pdu

/** What the guard needs from the store. [PduStore] implements it; tests use an in-memory copy. */
interface IndexBacking {
    val chunkCount: Int
    fun meta(key: String): String?
    fun setMeta(key: String, value: String)

    /** Forget every document and chunk. Folder privacy choices stay. */
    fun wipe()
}

/**
 * The only code that may wipe the index, and the rule for when. Vectors from different embedders cannot be compared, so:
 * a fake or not-ready embedder never indexes, scans or wipes; a changed embedder or document prefix is reported as
 * "re-index needed" and waits for an explicit owner action; a fresh, empty store simply adopts the current key.
 */
object IndexGuard {
    const val FAKE = "FAKE"
    const val NOT_LOADED = "Embedder not loaded"
    const val REINDEX_NEEDED = "Re-index needed"

    sealed interface Outcome {
        data object Proceed : Outcome
        data class Refused(val reason: String, val reindexNeeded: Boolean = false) : Outcome
    }

    fun isReal(modelName: String): Boolean = modelName != FAKE && modelName.isNotBlank()

    /** True when chunks exist that were embedded under another embedder or document prefix. */
    fun reindexNeeded(b: IndexBacking, embedName: String, documentPrefix: String): Boolean =
        b.chunkCount > 0 && b.meta(KEY) != PduEmbedConfig.indexKey(embedName, documentPrefix)

    /** Call before every index or scan run. [ownerForce] is an explicit owner action (the Re-index button, a debug force). */
    fun prepare(b: IndexBacking, embedName: String, embedReady: Boolean, documentPrefix: String, ownerForce: Boolean): Outcome {
        if (!embedReady || !isReal(embedName)) return Outcome.Refused(NOT_LOADED, reindexNeeded(b, embedName, documentPrefix))
        val key = PduEmbedConfig.indexKey(embedName, documentPrefix)
        val stored = b.meta(KEY)
        return when {
            ownerForce -> { b.wipe(); b.setMeta("embedModel", embedName); b.setMeta(KEY, key); Outcome.Proceed }
            stored == key -> Outcome.Proceed
            b.chunkCount == 0 -> { b.setMeta("embedModel", embedName); b.setMeta(KEY, key); Outcome.Proceed }
            else -> Outcome.Refused(REINDEX_NEEDED, reindexNeeded = true)
        }
    }

    private const val KEY = "embedKey"
}
