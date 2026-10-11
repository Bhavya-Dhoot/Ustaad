package com.swarmos.app.sched

import com.swarmos.app.llm.ChatChunk
import com.swarmos.app.llm.ChatRequest
import com.swarmos.app.llm.ChatResult
import com.swarmos.app.llm.LlmClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * No slot became free within the wait limit: the model is busy with other jobs (a long job from another phone, say).
 * [message] is fit to show: "Waiting for the model: all 1 slot stayed busy for 30 s."
 */
class ModelBusyTimeoutException(val waitedMs: Long, val slots: Int) :
    RuntimeException("Waiting for the model: all $slots ${if (slots == 1) "slot" else "slots"} stayed busy for ${seconds(waitedMs)} s.") {
    private companion object {
        fun seconds(ms: Long): String = (ms / 1000.0).let { if (it % 1.0 == 0.0) it.toLong().toString() else "%.1f".format(it) }
    }
}

/**
 * The chat model on this phone with every call passing the node's slot gate, so Agent Choir and Data Union count
 * against the same slots as jobs that arrive from other phones (a phone with capacity 1 never runs two at once).
 * A call waits for a free slot for at most [maxWaitMs], then fails with [ModelBusyTimeoutException]; a stream holds its
 * slot until it ends or is cancelled. With no model server (capacity 0) calls run straight through to the placeholder
 * client. Embeddings are not gated: they use another server.
 *
 * [inner] is read at every call so it follows `AppGraph.llm` when the runtime swaps the client.
 */
class GatedLlmClient(
    private val inner: () -> LlmClient,
    private val node: SchedNode,
    private val maxWaitMs: Long = DEFAULT_MAX_WAIT_MS,
) : LlmClient {
    override val modelName: String get() = inner().modelName

    override suspend fun ready(): Boolean = inner().ready()

    /** True when a slot was taken (give it back); false when there is no model server and the call runs without one. */
    private suspend fun enter(): Boolean {
        // the timeout can fire, or the caller be cancelled, in the instant acquire() has returned true; withTimeoutOrNull then drops
        // the block's value (or throws), so the block keeps it, and the slot is given back on every exit but a normal return
        var taken: Boolean? = null
        val got = try {
            withTimeoutOrNull(maxWaitMs) { node.chatExec.acquire().also { taken = it } }
        } catch (e: Throwable) {
            if (taken == true) node.chatExec.release()
            throw e
        }
        if (got == null) {
            if (taken == true) node.chatExec.release()
            throw ModelBusyTimeoutException(maxWaitMs, node.chatExec.capacity)
        }
        return got
    }

    override suspend fun chat(req: ChatRequest): ChatResult {
        val slot = enter()
        try {
            return inner().chat(req).also { node.noteStats(it.stats) }
        } finally {
            if (slot) node.chatExec.release()
        }
    }

    override fun chatStream(req: ChatRequest): Flow<ChatChunk> = flow {
        val slot = enter()
        try {
            inner().chatStream(req).collect { chunk ->
                chunk.stats?.let(node::noteStats)
                emit(chunk)
            }
        } finally {
            if (slot) node.chatExec.release()
        }
    }

    override suspend fun embed(texts: List<String>): List<FloatArray> = inner().embed(texts)

    companion object {
        const val DEFAULT_MAX_WAIT_MS = 30_000L
    }
}
