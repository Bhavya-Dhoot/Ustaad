package com.swarmos.app.sched

import kotlinx.serialization.Serializable

/**
 * What one peer may ask this phone to do in a single request. Anything beyond is refused with 413 and the limit is named,
 * so a paired but misbehaving (or compromised) phone cannot pin the model or the heap with one oversized call.
 * Generous for real use: a prompt of this size is about 30k tokens.
 */
object SchedLimits {
    const val MAX_TOKENS = 4096
    const val MAX_MESSAGES = 64
    const val MAX_MESSAGE_CHARS = 32_000
    const val MAX_PROMPT_CHARS = 128_000
    const val MAX_SCHEMA_CHARS = 16_000
    const val MAX_STOPS = 8
    const val MAX_STOP_CHARS = 256
    const val MAX_TEXTS = 256
    const val MAX_TEXT_CHARS = 16_000
    const val MAX_BATCH = 256
}

/** [what] names the limit that was exceeded, e.g. "maxTokens (1 to 4096)"; [limit] is its value. */
class RequestLimitException(val what: String, val limit: Int) : RuntimeException("$what: limit is $limit")

/** Body of the 413 answer. */
@Serializable
data class LimitBody(val error: String, val limit: Int)

private fun over(what: String, limit: Int): Nothing = throw RequestLimitException(what, limit)

fun ChatRequestDto.checkLimits() {
    if (maxTokens !in 1..SchedLimits.MAX_TOKENS) over("maxTokens must be 1 to ${SchedLimits.MAX_TOKENS}", SchedLimits.MAX_TOKENS)
    if (messages.isEmpty() || messages.size > SchedLimits.MAX_MESSAGES) over("messages must be 1 to ${SchedLimits.MAX_MESSAGES}", SchedLimits.MAX_MESSAGES)
    var total = 0
    for (m in messages) {
        if (m.content.length > SchedLimits.MAX_MESSAGE_CHARS) over("message length in characters", SchedLimits.MAX_MESSAGE_CHARS)
        total += m.content.length
    }
    if (total > SchedLimits.MAX_PROMPT_CHARS) over("total prompt length in characters", SchedLimits.MAX_PROMPT_CHARS)
    if ((jsonSchema?.length ?: 0) > SchedLimits.MAX_SCHEMA_CHARS) over("jsonSchema length in characters", SchedLimits.MAX_SCHEMA_CHARS)
    if (stop.size > SchedLimits.MAX_STOPS) over("number of stop sequences", SchedLimits.MAX_STOPS)
    if (stop.any { it.length > SchedLimits.MAX_STOP_CHARS }) over("stop sequence length in characters", SchedLimits.MAX_STOP_CHARS)
}

fun EmbedJobRequest.checkLimits() {
    if (texts.size > SchedLimits.MAX_TEXTS) over("number of texts", SchedLimits.MAX_TEXTS)
    if (texts.any { it.length > SchedLimits.MAX_TEXT_CHARS }) over("text length in characters", SchedLimits.MAX_TEXT_CHARS)
}

fun BatchStartRequest.checkLimits() {
    val n = requests?.size ?: return
    if (n > SchedLimits.MAX_BATCH) over("number of requests in a batch", SchedLimits.MAX_BATCH)
    requests.forEach { it.checkLimits() }
}
