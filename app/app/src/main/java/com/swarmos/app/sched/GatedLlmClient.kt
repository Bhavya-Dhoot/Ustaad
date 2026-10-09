package com.swarmos.app.sched

import com.swarmos.app.llm.ChatChunk
import com.swarmos.app.llm.ChatRequest
import com.swarmos.app.llm.ChatResult
import com.swarmos.app.llm.LlmClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The chat model on this phone with every call passing the node's slot gate, so Agent Choir and Data Union count
 * against the same slots as jobs that arrive from other phones (a phone with capacity 1 never runs two at once).
 * A call waits for a free slot; a stream holds its slot until it ends or is cancelled. With no model server (capacity 0)
 * calls run straight through to the placeholder client. Embeddings are not gated: they use another server.
 *
 * [inner] is read at every call so it follows `AppGraph.llm` when the runtime swaps the client.
 */
class GatedLlmClient(private val inner: () -> LlmClient, private val node: SchedNode) : LlmClient {
    override val modelName: String get() = inner().modelName

    override suspend fun ready(): Boolean = inner().ready()

    override suspend fun chat(req: ChatRequest): ChatResult {
        val slot = node.chatExec.acquire()
        try {
            return inner().chat(req).also { node.noteStats(it.stats) }
        } finally {
            if (slot) node.chatExec.release()
        }
    }

    override fun chatStream(req: ChatRequest): Flow<ChatChunk> = flow {
        val slot = node.chatExec.acquire()
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
}
