package com.swarmos.app.llm

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.security.MessageDigest
import kotlin.math.sqrt

/**
 * What AppGraph.llm / AppGraph.embedder hold while no real model is loaded (see LlamaRuntime.realChatReady). Output is
 * canned and deterministic, and every number is fake. Any screen showing output from this client must label it FAKE.
 */
class FakeLlmClient : LlmClient {
    override val modelName = "FAKE"

    override suspend fun ready() = true

    override fun chatStream(req: ChatRequest): Flow<ChatChunk> = flow {
        val words = if (req.jsonSchema != null) listOf("{}") else ANSWER.split(' ')
        val out = words.take(req.maxTokens.coerceAtLeast(1))
        val promptTokens = req.messages.sumOf { it.content.split(' ').size }
        delay(PROMPT_MS_PER_TOKEN.toLong() * promptTokens.coerceAtMost(20))
        for ((i, w) in out.withIndex()) {
            delay(WORD_DELAY_MS)
            emit(ChatChunk(if (i == 0) w else " $w"))
        }
        val stats = ChatStats(
            promptTokens = promptTokens,
            completionTokens = out.size,
            promptMs = promptTokens * PROMPT_MS_PER_TOKEN,
            genMs = out.size * WORD_DELAY_MS.toDouble(),
        )
        emit(ChatChunk("", done = true, stats = stats))
    }

    override suspend fun chat(req: ChatRequest): ChatResult {
        val sb = StringBuilder()
        var stats: ChatStats? = null
        chatStream(req).collect { c ->
            sb.append(c.delta)
            if (c.done) stats = c.stats
        }
        return ChatResult(sb.toString(), stats ?: ChatStats(0, 0, 0.0, 0.0))
    }

    /** Deterministic 256-dim unit vectors derived from SHA-256 of the text. */
    override suspend fun embed(texts: List<String>): List<FloatArray> = texts.map { text ->
        val md = MessageDigest.getInstance("SHA-256")
        val v = FloatArray(DIM)
        for (block in 0 until DIM / 32) {
            val bytes = md.digest("$block:$text".toByteArray(Charsets.UTF_8))
            for (j in 0 until 32) v[block * 32 + j] = bytes[j] / 127.5f
        }
        val norm = sqrt(v.fold(0.0) { a, x -> a + x * x }).toFloat()
        if (norm > 0f) for (i in v.indices) v[i] /= norm
        v
    }

    companion object {
        const val DIM = 256
        private const val WORD_DELAY_MS = 30L
        private const val PROMPT_MS_PER_TOKEN = 4.0
        /** The only text this client ever answers (the word delays are fixed too, so tests are deterministic). */
        const val ANSWER =
            "[FAKE] No chat model is loaded on this phone."
    }
}
