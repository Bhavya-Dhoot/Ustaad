package com.swarmos.app.sched

import com.swarmos.app.core.NodeHealth
import com.swarmos.app.llm.ChatChunk
import com.swarmos.app.llm.ChatRequest
import com.swarmos.app.llm.ChatStats
import com.swarmos.app.llm.LlmClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.FlowCollector

/** One phone as the scheduler sees it, local or remote. Anything that implements this can join the swarm. */
interface PhoneEndpoint {
    val nodeId: String
    val isLocal: Boolean get() = false

    /** Throws when the phone cannot be reached. */
    suspend fun caps(): Caps

    /** Runs the chat job; the phone deduplicates by [jobId]. Throws [BusyException] when it has no free slot. */
    suspend fun runChat(jobId: String, req: ChatRequest, pinModel: String?): ChatJobResult

    suspend fun runEmbed(jobId: String, texts: List<String>): EmbedJobResult

    /** Local phone only: stream [req] into [out] if a slot is free right now and return true; otherwise return false untouched. */
    suspend fun streamIfFree(req: ChatRequest, pinModel: String?, out: FlowCollector<ChatChunk>): Boolean = false
}

/**
 * Server side of the scheduler on one phone: the slots, the 10-minute result memory and the capability report.
 * Everything arrives as an [LlmClient] / [NodeHealth] provider so it can follow `AppGraph.llm` when the real runtime replaces the fake.
 */
class SchedNode(
    scope: CoroutineScope,
    private val selfId: () -> String,
    private val llm: () -> LlmClient,
    private val embedder: () -> LlmClient,
    private val health: () -> NodeHealth?,
    clock: () -> Long = System::currentTimeMillis,
) {
    val chatExec = JobExecutor(scope, clock)
    val embedExec = JobExecutor(scope, clock)

    @Volatile
    var lastTokPerSec: Double? = null
        private set

    /** Parallel chat jobs this phone accepts. Default 1; set it to the model server's slot count. */
    var capacity: Int
        get() = chatExec.capacity
        set(value) {
            chatExec.capacity = value
        }

    fun noteStats(stats: ChatStats) {
        val tps = stats.decodeTokPerSec
        if (tps > 0.0) lastTokPerSec = tps
    }

    fun caps(): Caps {
        val h = health()
        return Caps(
            nodeId = selfId(),
            modelName = llm().modelName,
            capacity = chatExec.capacity,
            running = chatExec.running,
            queued = chatExec.queued,
            lastDecodeTokPerSec = lastTokPerSec ?: h?.lastDecodeTokPerSec,
            thermalStatus = h?.thermalStatus ?: 0,
            batteryPct = h?.batteryPct ?: -1,
            charging = h?.charging ?: true,
            ramAvailMb = h?.ramAvailMb ?: 0,
        )
    }

    suspend fun chat(jobId: String, req: ChatRequest, pinModel: String? = null): ChatJobResult {
        val model = llm().modelName
        if (pinModel != null && pinModel != model) throw ModelMismatchException(model)
        return chatExec.submit(jobId) {
            val client = llm()
            val r = client.chat(req)
            noteStats(r.stats)
            ChatJobResult(jobId, r.text, r.stats.toDto(), selfId(), client.modelName)
        }
    }

    suspend fun embed(jobId: String, texts: List<String>): EmbedJobResult =
        embedExec.submit(jobId) {
            val client = embedder()
            EmbedJobResult(jobId, client.embed(texts).map { it.toList() }, selfId(), client.modelName)
        }
}

/** This phone as a [PhoneEndpoint]. Chat shares the slots with jobs that arrive over HTTP; embeddings never leave the phone. */
class LocalEndpoint(
    private val node: SchedNode,
    private val llm: () -> LlmClient,
    private val embedder: () -> LlmClient,
    private val selfId: () -> String,
) : PhoneEndpoint {
    override val nodeId: String get() = selfId()
    override val isLocal: Boolean get() = true

    override suspend fun caps(): Caps = node.caps()

    override suspend fun runChat(jobId: String, req: ChatRequest, pinModel: String?): ChatJobResult =
        node.chat(jobId, req, pinModel)

    override suspend fun runEmbed(jobId: String, texts: List<String>): EmbedJobResult {
        val client = embedder()
        return EmbedJobResult(jobId, client.embed(texts).map { it.toList() }, selfId(), client.modelName)
    }

    override suspend fun streamIfFree(req: ChatRequest, pinModel: String?, out: FlowCollector<ChatChunk>): Boolean {
        val client = llm()
        if (pinModel != null && pinModel != client.modelName) return false
        if (!node.chatExec.tryAcquire()) return false
        try {
            client.chatStream(req).collect { chunk ->
                chunk.stats?.let(node::noteStats)
                out.emit(chunk)
            }
        } finally {
            node.chatExec.release()
        }
        return true
    }
}
