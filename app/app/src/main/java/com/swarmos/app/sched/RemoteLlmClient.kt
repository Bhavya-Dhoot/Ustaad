package com.swarmos.app.sched

import com.swarmos.app.core.NodeInfo
import com.swarmos.app.core.SwarmJson
import com.swarmos.app.link.ErrorBody
import com.swarmos.app.link.NodeClient
import com.swarmos.app.link.NodeHttpException
import com.swarmos.app.llm.ChatChunk
import com.swarmos.app.llm.ChatRequest
import com.swarmos.app.llm.ChatResult
import com.swarmos.app.llm.LlmClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import java.io.IOException
import java.util.UUID

fun newJobId(): String = UUID.randomUUID().toString().replace("-", "").take(16)

/**
 * A model on another phone, behind the same [LlmClient] as the local one. Calls the peer's signed
 * /v1/sched routes, so the peer's slot limit and its 10-minute result memory apply.
 *
 * [chatStream] streams tokens from the peer over server-sent events (POST /v1/sched/chat-stream) when a [streams]
 * client is given; cancelling the collector closes the stream and frees the peer's slot. Without [streams] the whole reply
 * arrives as one chunk followed by the done chunk. A full peer fails the stream with [BusyException] before any chunk.
 */
class RemoteLlmClient(
    val peer: NodeInfo,
    private val client: NodeClient,
    private val streams: StreamClient? = null,
    /** Which "ip" or "ip:port" reaches [peer] right now. Asked at every call, so it follows the link module's path choice. */
    private val address: (NodeInfo) -> String = { it.ip },
) : LlmClient, PhoneEndpoint {
    private val host get() = address(peer)

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

    override fun chatStream(req: ChatRequest): Flow<ChatChunk> = streams?.let { remoteStream(it, req, null) } ?: flow {
        val r = runChat(newJobId(), req, null)
        if (r.text.isNotEmpty()) emit(ChatChunk(r.text))
        emit(ChatChunk("", done = true, stats = r.stats.toStats()))
    }

    /** Streams when this client can; false (nothing emitted) otherwise so the caller falls back to a whole-reply job. */
    override suspend fun streamIfFree(req: ChatRequest, pinModel: String?, out: FlowCollector<ChatChunk>): Boolean {
        val sc = streams ?: return false
        remoteStream(sc, req, pinModel).collect { out.emit(it) }
        return true
    }

    override suspend fun embed(texts: List<String>): List<FloatArray> =
        runEmbed(newJobId(), texts).vectors.map { it.toFloatArray() }

    /**
     * Tokens are passed on as they arrive; the answer is only genuine once the final event verifies. When that event is
     * missing or its signature does not match, the flow ends with [StreamIntegrityException] and the caller must drop
     * everything it received from this stream.
     */
    private fun remoteStream(sc: StreamClient, req: ChatRequest, pinModel: String?): Flow<ChatChunk> = flow {
        var finished = false
        val jobId = newJobId()
        val text = StringBuilder()
        val body = SwarmJson.encodeToString(ChatJobRequest(jobId, req.toDto(), pinModel))
        sc.post(host, "/sched/chat-stream", body).collect { ev ->
            when (ev.event) {
                "chunk" -> {
                    val delta = SwarmJson.decodeFromString<ChunkDto>(ev.data).delta
                    text.append(delta)
                    emit(ChatChunk(delta))
                }
                "done" -> {
                    val d = SwarmJson.decodeFromString<StreamDone>(ev.data)
                    if (!StreamMac.matches(sc.secretNow(), jobId, text.toString(), d.mac)) {
                        val why = if (d.mac == null) "is not signed" else "does not match its signature"
                        throw StreamIntegrityException("the answer from ${peer.name} $why: discard it")
                    }
                    seenModel = d.modelName
                    finished = true
                    emit(ChatChunk("", done = true, stats = d.stats?.toStats()))
                }
                "error" -> throw IOException("${peer.name} failed the stream: ${SwarmJson.decodeFromString<ErrorBody>(ev.data).error}")
            }
        }
        if (!finished) throw StreamIntegrityException("the stream from ${peer.name} ended without its signature: discard the answer")
    }.catch { e -> throw if (e is NodeHttpException) e.asSchedError() else e }

    /** 429 becomes [BusyException], 409 becomes [ModelMismatchException]; anything else passes through. */
    private inline fun <T> mapErrors(block: () -> T): T = try {
        block()
    } catch (e: NodeHttpException) {
        throw e.asSchedError()
    }

    private fun NodeHttpException.asSchedError(): Exception = when (status) {
        429 -> runCatching { SwarmJson.decodeFromString<BusyBody>(body) }
            .map { BusyException(it.running, it.capacity, it.queued) }
            .getOrElse { BusyException(0, 0, 0) }
        409 -> ModelMismatchException(runCatching { SwarmJson.decodeFromString<MismatchBody>(body).model }.getOrDefault("?"))
        else -> this
    }
}
