package com.swarmos.app.sched

import com.swarmos.app.llm.ChatMessage
import com.swarmos.app.llm.ChatRequest
import com.swarmos.app.llm.ChatStats
import kotlinx.serialization.Serializable

/*
 * Wire types for the routes under /v1/sched. ChatRequest and ChatStats live in llm/ and are not @Serializable, so these
 * mirrors carry exactly the same field names and defaults.
 */

@Serializable
data class MessageDto(val role: String, val content: String)

@Serializable
data class ChatRequestDto(
    val messages: List<MessageDto>,
    val maxTokens: Int = 256,
    val temperature: Double = 0.7,
    val jsonSchema: String? = null,
    val stop: List<String> = emptyList(),
    val seed: Int? = null,
)

@Serializable
data class StatsDto(val promptTokens: Int, val completionTokens: Int, val promptMs: Double, val genMs: Double) {
    val decodeTokPerSec: Double get() = if (genMs > 0) completionTokens * 1000.0 / genMs else 0.0
    fun toStats() = ChatStats(promptTokens, completionTokens, promptMs, genMs)
}

fun ChatRequest.toDto() = ChatRequestDto(messages.map { MessageDto(it.role, it.content) }, maxTokens, temperature, jsonSchema, stop, seed)

fun ChatRequestDto.toRequest() = ChatRequest(messages.map { ChatMessage(it.role, it.content) }, maxTokens, temperature, jsonSchema, stop, seed)

fun ChatStats.toDto() = StatsDto(promptTokens, completionTokens, promptMs, genMs)

/** POST /v1/sched/chat body. `model`, when set, makes the phone refuse (409) if it runs a different model. */
@Serializable
data class ChatJobRequest(val jobId: String, val request: ChatRequestDto, val model: String? = null)

@Serializable
data class ChatJobResult(val jobId: String, val text: String, val stats: StatsDto, val nodeId: String, val modelName: String)

@Serializable
data class EmbedJobRequest(val jobId: String, val texts: List<String>)

@Serializable
data class EmbedJobResult(val jobId: String, val vectors: List<List<Float>>, val nodeId: String, val modelName: String)

/** GET /v1/sched/caps. */
@Serializable
data class Caps(
    val nodeId: String,
    val modelName: String,
    val capacity: Int,
    val running: Int,
    val queued: Int,
    val lastDecodeTokPerSec: Double?,
    val thermalStatus: Int,
    val batteryPct: Int,
    val charging: Boolean,
    val ramAvailMb: Long,
)

/** Body of the 429 answer: the phone has no free slot. */
@Serializable
data class BusyBody(val error: String = "busy", val running: Int, val capacity: Int, val queued: Int)

/** Body of the 409 answer: the caller pinned a model this phone does not run. */
@Serializable
data class MismatchBody(val error: String = "model mismatch", val model: String)

@Serializable
data class PeersRequest(val ips: List<String>)

@Serializable
data class PeerResult(val ip: String, val ok: Boolean, val nodeId: String? = null, val name: String? = null, val error: String? = null)

@Serializable
data class PeersResponse(val peers: List<PeerResult>)

/** POST /v1/sched/batch body: give `requests` or name a `builtin` set. `mode` is "swarm" (default) or "local". */
@Serializable
data class BatchStartRequest(val requests: List<ChatRequestDto>? = null, val builtin: String? = null, val mode: String = "swarm")

@Serializable
data class BatchStarted(val id: String, val total: Int)

@Serializable
data class BatchItem(
    val index: Int,
    val jobId: String,
    val nodeId: String? = null,
    val modelName: String? = null,
    val text: String? = null,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val genMs: Double = 0.0,
    val error: String? = null,
)

@Serializable
data class PhoneShare(
    val nodeId: String,
    val jobs: Int,
    val tokens: Int,
    /** This phone's tokens divided by the batch wall-clock, so the rows add up to the aggregate. */
    val tokPerSec: Double,
    /** Tokens divided by the time the phone spent generating them (its own decode speed). */
    val decodeTokPerSec: Double,
)

@Serializable
data class BatchReport(
    val id: String,
    val mode: String,
    val state: String,            // "running" | "done"
    val total: Int,
    val done: Int,
    val failed: Int,
    val wallMs: Double,
    val totalTokens: Int,
    val aggTokPerSec: Double,
    val fake: Boolean,
    val models: List<String>,
    val perPhone: List<PhoneShare>,
    val items: List<BatchItem>,   // finished items, in input order
)
