package com.swarmos.app.sched

import com.swarmos.app.core.NodeInfo
import com.swarmos.app.core.SwarmJson
import com.swarmos.app.link.NodeClient
import com.swarmos.app.link.NodeHttpException
import com.swarmos.app.llm.ChatChunk
import com.swarmos.app.llm.ChatRequest
import com.swarmos.app.llm.ChatResult
import com.swarmos.app.llm.LlmClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.UUID

fun newJobId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

/**
 * A model on another phone, behind the same [LlmClient] as the local one. Calls the peer's signed
 * /v1/sched routes, so the peer's slot limit and its 10-minute result memory apply.
 * [chatStream] is not streamed: the whole reply arrives as one chunk followed by the done chunk.
 */
class RemoteLlmClient(val peer: NodeInfo, private val client: NodeClient) : LlmClient, PhoneEndpoint {
    private val host get() = peer.ip

    @Volatile
    private var seenModel: String? = null

    override val nodeId: String get() = peer.nodeId
    override val modelName: String get() = seenModel ?: peer.chatModel ?: "unknown"

    override suspend fun ready(): Boolean = runCatching { caps() }.isSuccess

    override suspend fun caps(): Caps = mapErrors {
        client.get<Caps>(host, "/sched/caps").also { seenModel = it.modelName }
    }

    override suspend fun runChat(jobId: String, req: ChatRequest, pinModel: String?): ChatJobResult = mapErrors {
        client.post<ChatJobResult, ChatJobRequest>(host, "/sched/chat", ChatJobRequest(jobId, req.toDto(), pinModel))
            .also { seenModel = it.modelName }
    }

    override suspend fun runEmbed(jobId: String, texts: List<String>): EmbedJobResult = mapErrors {
        client.post<EmbedJobResult, EmbedJobRequest>(host, "/sched/embed", EmbedJobRequest(jobId, texts))
    }

    override suspend fun chat(req: ChatRequest): ChatResult {
        val r = runChat(newJobId(), req, null)
        return ChatResult(r.text, r.stats.toStats())
    }

    override fun chatStream(req: ChatRequest): Flow<ChatChunk> = flow {
        val r = runChat(newJobId(), req, null)
        if (r.text.isNotEmpty()) emit(ChatChunk(r.text))
        emit(ChatChunk("", done = true, stats = r.stats.toStats()))
    }

    override suspend fun embed(texts: List<String>): List<FloatArray> =
        runEmbed(newJobId(), texts).vectors.map { it.toFloatArray() }

    /** 429 becomes [BusyException], 409 becomes [ModelMismatchException]; anything else passes through. */
    private inline fun <T> mapErrors(block: () -> T): T = try {
        block()
    } catch (e: NodeHttpException) {
        throw when (e.status) {
            429 -> runCatching { SwarmJson.decodeFromString<BusyBody>(e.body) }
                .map { BusyException(it.running, it.capacity, it.queued) }
                .getOrElse { BusyException(0, 0, 0) }
            409 -> ModelMismatchException(runCatching { SwarmJson.decodeFromString<MismatchBody>(e.body).model }.getOrDefault("?"))
            else -> e
        }
    }
}
