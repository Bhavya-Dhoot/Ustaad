package com.swarmos.app.sched

import com.swarmos.app.llm.ChatChunk
import com.swarmos.app.llm.ChatMessage
import com.swarmos.app.llm.ChatRequest
import com.swarmos.app.llm.ChatResult
import com.swarmos.app.llm.ChatStats
import com.swarmos.app.llm.LlmClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** A model that takes [delayMs] per reply and records how many replies ran at once. */
class TestLlm(
    override val modelName: String = "T",
    private val delayMs: Long = 30,
    private val tokens: Int = 10,
) : LlmClient {
    val concurrent = AtomicInteger()
    val maxConcurrent = AtomicInteger()
    val executed = ConcurrentLinkedQueue<String>()

    override suspend fun ready() = true

    override suspend fun chat(req: ChatRequest): ChatResult {
        val c = concurrent.incrementAndGet()
        maxConcurrent.updateAndGet { maxOf(it, c) }
        try {
            delay(delayMs)
            val prompt = req.messages.last().content
            executed += prompt
            return ChatResult("$modelName:$prompt", ChatStats(5, tokens, 1.0, delayMs.toDouble()))
        } finally {
            concurrent.decrementAndGet()
        }
    }

    override fun chatStream(req: ChatRequest): Flow<ChatChunk> = flow {
        emit(ChatChunk("stream:"))
        emit(ChatChunk(req.messages.last().content))
        emit(ChatChunk("", done = true, stats = ChatStats(5, tokens, 1.0, delayMs.toDouble())))
    }

    override suspend fun embed(texts: List<String>): List<FloatArray> = texts.map { floatArrayOf(it.length.toFloat()) }
}

/**
 * A phone in the swarm: a real [SchedNode] (slots, 10-minute memory, caps) behind an endpoint that can be killed,
 * made to hang or reported hot, the way a real peer can.
 */
class FakePhone(
    override val nodeId: String,
    val llm: TestLlm = TestLlm(),
    scope: CoroutineScope,
    capacity: Int = 1,
    override val isLocal: Boolean = false,
    @Volatile var tokPerSec: Double? = null,
    @Volatile var thermal: Int = 0,
) : PhoneEndpoint {
    val node = SchedNode(scope, { nodeId }, { llm }, { llm }, { null }).also { it.capacity = capacity }
    private val localEndpoint = LocalEndpoint(node, { llm }, { llm }, { nodeId })

    @Volatile var dead = false
    @Volatile var hang = false
    @Volatile var dieAfter = Int.MAX_VALUE
    val calls = AtomicInteger()
    val jobIds = ConcurrentLinkedQueue<String>()

    override suspend fun caps(): Caps {
        if (dead) throw IOException("$nodeId is down")
        return node.caps().copy(lastDecodeTokPerSec = tokPerSec ?: node.caps().lastDecodeTokPerSec, thermalStatus = thermal)
    }

    override suspend fun runChat(jobId: String, req: ChatRequest, pinModel: String?): ChatJobResult {
        if (dead) throw IOException("$nodeId is down")
        if (calls.incrementAndGet() > dieAfter) {
            dead = true
            throw IOException("$nodeId went away")
        }
        jobIds += jobId
        if (hang) awaitCancellation()
        return node.chat(jobId, req, pinModel)
    }

    override suspend fun runEmbed(jobId: String, texts: List<String>): EmbedJobResult {
        if (dead) throw IOException("$nodeId is down")
        return node.embed(jobId, texts)
    }

    override suspend fun streamIfFree(req: ChatRequest, pinModel: String?, out: kotlinx.coroutines.flow.FlowCollector<ChatChunk>): Boolean =
        if (isLocal) localEndpoint.streamIfFree(req, pinModel, out) else false
}

fun req(text: String, maxTokens: Int = 16) = ChatRequest(listOf(ChatMessage("user", text)), maxTokens = maxTokens)

fun caps(
    tps: Double? = 10.0,
    thermal: Int = 0,
    battery: Int = 80,
    charging: Boolean = false,
    model: String = "M",
    queued: Int = 0,
) = Caps("n", model, 1, 0, queued, tps, thermal, battery, charging, 4096)
