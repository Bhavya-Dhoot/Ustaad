package com.swarmos.app.llm

import kotlinx.coroutines.flow.Flow

data class ChatMessage(val role: String, val content: String)   // "system" | "user" | "assistant"

data class ChatRequest(
    val messages: List<ChatMessage>,
    val maxTokens: Int = 256,
    val temperature: Double = 0.7,
    val jsonSchema: String? = null,      // JSON Schema text; server constrains output to it
    val stop: List<String> = emptyList(),
    val seed: Int? = null,
)

data class ChatChunk(val delta: String, val done: Boolean = false, val stats: ChatStats? = null)

/**
 * Timings of one generation. [cachedTokens] is the server's `timings.cache_n`: prompt tokens reused from its cache instead of
 * evaluated again (0 when the server did not say). It is NOT part of [promptTokens], which counts only the tokens evaluated.
 * Local only: no wire format carries it.
 */
data class ChatStats(val promptTokens: Int, val completionTokens: Int, val promptMs: Double, val genMs: Double, val cachedTokens: Int = 0) {
    val decodeTokPerSec get() = if (genMs > 0) completionTokens * 1000.0 / genMs else 0.0
}

data class ChatResult(val text: String, val stats: ChatStats)

interface LlmClient {
    val modelName: String
    suspend fun ready(): Boolean
    fun chatStream(req: ChatRequest): Flow<ChatChunk>
    suspend fun chat(req: ChatRequest): ChatResult
    suspend fun embed(texts: List<String>): List<FloatArray>
}
