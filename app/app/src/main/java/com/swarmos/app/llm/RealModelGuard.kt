package com.swarmos.app.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** No real model answers right now (none is loaded, or its server died while the call waited). */
class ModelNotLoadedException(message: String) : LlamaServerException(message, 503)

/**
 * A call on this phone's model is answered by the real model or fails with [ModelNotLoadedException]: never by the placeholder
 * [FakeLlmClient] ("[FAKE] ..."). [current] is read at the moment of every call, not when the guard is made, so a call that
 * waited for a slot while the server died fails instead of getting a canned answer from whatever replaced the server. The
 * same guard the agent endpoint uses for other apps (agent/AgentBackend.kt RealModelOnly), for the app's own features.
 */
class RealModelGuard(private val current: () -> LlmClient, private val whenMissing: String) : LlmClient {
    private fun real(): LlmClient = current().takeUnless { it is FakeLlmClient } ?: throw ModelNotLoadedException(whenMissing)

    override val modelName: String get() = current().modelName

    override suspend fun ready(): Boolean = current().let { it !is FakeLlmClient && it.ready() }

    override fun chatStream(req: ChatRequest): Flow<ChatChunk> = flow { emitAll(real().chatStream(req)) }

    override suspend fun chat(req: ChatRequest): ChatResult = real().chat(req)

    override suspend fun embed(texts: List<String>): List<FloatArray> = real().embed(texts)

    companion object {
        const val NO_CHAT = "No chat model is loaded on this phone"
        const val NO_EMBEDDER = "No embedding model is loaded on this phone"
    }
}
