package com.swarmos.app.busy

import com.swarmos.app.llm.ChatMessage
import com.swarmos.app.llm.ChatRequest

/** The prompts the filler loop rotates through: Choir problems and the benchmark prompts, short and deterministic. */
object BusyPrompts {
    /** Same as the benchmark prompts: no sampling noise, a fixed seed per prompt. */
    const val TEMPERATURE = 0.0

    /**
     * One Choir problem as a short chat request: the question (with its options) and a one-sentence instruction, cut at
     * [BusyPolicy.MAX_TOKENS]. Nothing is checked afterwards; the reply is thrown away.
     */
    fun fromProblem(text: String, seed: Int): ChatRequest = ChatRequest(
        messages = listOf(ChatMessage("system", "Answer in one short sentence."), ChatMessage("user", text)),
        maxTokens = BusyPolicy.MAX_TOKENS,
        temperature = TEMPERATURE,
        seed = seed,
    )

    /**
     * Choir problems and benchmark prompts taken in turn (one of each, then the next of each) until both lists are used up.
     * The rotation in the loop then starts again at the first. Every request is capped at [BusyPolicy.MAX_TOKENS].
     */
    fun rotation(choirProblems: List<String>, benchPrompts: List<ChatRequest>): List<ChatRequest> {
        val choir = choirProblems.mapIndexed { i, t -> fromProblem(t, i) }
        val bench = benchPrompts.map { it.copy(maxTokens = minOf(it.maxTokens, BusyPolicy.MAX_TOKENS), temperature = TEMPERATURE) }
        return buildList {
            for (i in 0 until maxOf(choir.size, bench.size)) {
                choir.getOrNull(i)?.let(::add)
                bench.getOrNull(i)?.let(::add)
            }
        }
    }
}
