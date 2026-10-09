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

data class ChatStats(val promptTokens: Int, val completionTokens: Int, val promptMs: Double, val genMs: Double) {
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
