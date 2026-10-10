package com.swarmos.app.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * What the app does when Android says the phone is out of memory (Application.onTrimMemory / onLowMemory): at
 * RUNNING_CRITICAL it stops the embedding server if nothing is using it, and starts it again on the next use. The chat server
 * is never touched: it is what the phone is for. Pure decisions here, the wiring is in [LlamaRuntime].
 */
object MemoryPressure {
    /** ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL: the app is in the foreground list and the system is almost out of memory. */
    const val RUNNING_CRITICAL = 15

    /** An embedding call that ended less than this ago may be the gap inside an index run or a search: the embedder is not idle yet. */
    const val EMBED_IDLE_MS = 30_000L

    const val DROPPED_NOTICE = "Stopped to free memory (Android reported the phone almost out of memory). It starts again when something needs it."

    /** Only the exact RUNNING_CRITICAL level counts; the higher levels (UI hidden, background list) are about the app's place in the list, not about RAM. */
    fun isCritical(level: Int): Boolean = level == RUNNING_CRITICAL

    /**
     * True when the embedder may be stopped: it is running, no embedding call is in flight, none ended in the last
     * [EMBED_IDLE_MS], and no index run is announced ([indexRunning]).
     */
    fun embedderIdle(running: Boolean, callInFlight: Boolean, msSinceLastCall: Long, indexRunning: Boolean): Boolean =
        running && !callInFlight && msSinceLastCall >= EMBED_IDLE_MS && !indexRunning
}

/**
 * The embedding client while the embedder is stopped for memory. It looks like the real one (same model name, so the
 * Knowledge Bank, the Private Data Union and the agent endpoint see an embedder that is there) and starts the server on the
 * first [embed]; [revive] returns the real client once the server is READY, or null when it could not be started.
 */
internal class DroppedEmbedder(
    override val modelName: String,
    val modelPath: String,
    private val revive: suspend () -> LlmClient?,
) : LlmClient {
    override suspend fun ready(): Boolean = true

    override fun chatStream(req: ChatRequest): Flow<ChatChunk> = flow { throw LlamaServerException(EMBEDDINGS_ONLY) }

    override suspend fun chat(req: ChatRequest): ChatResult = throw LlamaServerException(EMBEDDINGS_ONLY)

    override suspend fun embed(texts: List<String>): List<FloatArray> =
        (revive() ?: throw LlamaServerException(NOT_RESTARTED)).embed(texts)

    companion object {
        const val EMBEDDINGS_ONLY = "this is the embedding model; it does not chat"
        const val NOT_RESTARTED = "the embedding model was stopped to free memory and could not be started again"
    }
}
