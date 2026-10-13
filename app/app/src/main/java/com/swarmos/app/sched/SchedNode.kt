package com.swarmos.app.sched

import com.swarmos.app.core.DeviceProfiles
import com.swarmos.app.core.DeviceState
import com.swarmos.app.core.NodeHealth
import com.swarmos.app.core.ThermalGate as CoreThermalGate
import com.swarmos.app.core.WorkKind
import com.swarmos.app.llm.ChatChunk
import com.swarmos.app.llm.ChatRequest
import com.swarmos.app.llm.ChatStats
import com.swarmos.app.llm.LlmClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.atomic.AtomicBoolean

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

    /**
     * Null when this phone can take model jobs and streams; else a plain sentence for the phone list. A phone reachable only
     * over Bluetooth is up for control messages but is not a target for model work.
     */
    val unusableReason: String? get() = null
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
    /** This phone's device profile and plan (core/DeviceProfile.kt); null until the first device check has run. */
    private val device: () -> DeviceState? = { DeviceProfiles.current },
    /** Free storage right now in MB; null when unknown (the profile's figure from the last check is reported). */
    private val freeStorageMb: () -> Long? = { DeviceProfiles.freeStorageMb?.invoke() },
    /** Receives a decode speed worth keeping across restarts: the model it was measured on and tokens/s. */
    private val rememberSpeed: (model: String, tokPerSec: Double) -> Unit = { m, t -> DeviceProfiles.noteSpeed(m, t) },
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
        // a short reply says little about speed, and the placeholder client's figures are not the phone's
        if (tps > 0.0 && stats.completionTokens >= MIN_SPEED_TOKENS) llm().modelName.takeIf { it != "FAKE" }?.let { rememberSpeed(it, tps) }
    }

    /** What other phones see. Filler work ("Keep busy") is not in `running` or `queued`: the executor leaves it out. */
    fun caps(): Caps {
        val h = health()
        val model = llm().modelName
        val d = device()
        return Caps(
            nodeId = selfId(),
            modelName = model,
            capacity = chatExec.capacity,
            running = chatExec.running,
            queued = chatExec.queued,
            lastDecodeTokPerSec = lastTokPerSec ?: h?.lastDecodeTokPerSec,
            thermalStatus = h?.thermalStatus ?: 0,
            batteryPct = h?.batteryPct ?: -1,
            charging = h?.charging ?: true,
            ramAvailMb = h?.ramAvailMb ?: 0,
            deviceClass = d?.plan?.deviceClass?.name,
            totalRamMb = d?.profile?.totalRamMb ?: 0,
            backend = d?.plan?.let { if (it.useGpu) "GPU" else "CPU" },
            // a speed belongs to the model it was measured on
            profileTokPerSec = d?.profile?.takeIf { it.chatTokPerSecModel == model }?.chatTokPerSec,
            storageOfferMb = d?.plan?.storageOfferMb ?: 0,
            freeStorageMb = freeStorageMb() ?: d?.profile?.freeStorageMb ?: 0,
        )
    }

    private companion object {
        /** Replies shorter than this are not used for the speed kept across restarts. */
        const val MIN_SPEED_TOKENS = 16
    }

    /**
     * Runs a chat job. [fromPeer] is true for a job that arrived through the node API from another phone (the default: that is
     * every caller but [LocalEndpoint]); such a job is refused when this phone is too hot, see [refuseWhenTooHot]. The owner's
     * own request ([fromPeer] false) is never refused for heat.
     */
    suspend fun chat(jobId: String, req: ChatRequest, pinModel: String? = null, fromPeer: Boolean = true): ChatJobResult {
        val model = llm().modelName
        if (pinModel != null && pinModel != model) throw ModelMismatchException(model)
        // heat is asked only for a job id this phone does not know yet: a resend of a running or finished job gets its result
        return chatExec.submit(jobId, newWork = { if (fromPeer) refuseWhenTooHot(chatExec) }) {
            val client = llm()
            val r = client.chat(req)
            noteStats(r.stats)
            ChatJobResult(jobId, r.text, r.stats.toDto(), selfId(), client.modelName)
        }
    }

    /**
     * A slot held for a stream that a peer asked for. Throws [BusyException] when no slot is free (streams are not queued)
     * and [ModelMismatchException] when [pinModel] is another model. The caller must [StreamSlot.close] it.
     * Suspends for a moment when only "Keep busy" filler holds the slot: the filler is stopped and the stream takes its place.
     */
    suspend fun openStream(pinModel: String?): StreamSlot {
        refuseWhenTooHot(chatExec)   // only another phone opens a stream here; this phone's own streams take the slot in [LocalEndpoint]
        val client = llm()
        if (pinModel != null && pinModel != client.modelName) throw ModelMismatchException(client.modelName)
        if (!chatExec.tryAcquire()) throw BusyException(chatExec.running, chatExec.capacity, chatExec.queued)
        return StreamSlot(client)
    }

    inner class StreamSlot internal constructor(private val client: LlmClient) {
        private val closed = AtomicBoolean(false)
        val modelName: String get() = client.modelName
        val nodeId: String get() = selfId()

        fun chunks(req: ChatRequest): Flow<ChatChunk> = client.chatStream(req).onEach { c -> c.stats?.let(::noteStats) }

        /** Frees the slot; safe to call twice. */
        fun close() {
            if (closed.compareAndSet(false, true)) chatExec.release()
        }
    }

    suspend fun embed(jobId: String, texts: List<String>): EmbedJobResult {
        // only another phone comes here ([LocalEndpoint] embeds with the embedder directly); as for chat, only a new job id is refused for heat
        return embedExec.submit(jobId, newWork = { refuseWhenTooHot(embedExec) }) {
            val client = embedder()
            EmbedJobResult(jobId, client.embed(texts).map { it.toList() }, selfId(), client.modelName)
        }
    }

    /**
     * A phone at thermal status 4 or more takes on no new job from another phone ([WorkKind.REMOTE_JOB] in the shared
     * [CoreThermalGate]). It answers as a full phone does, with [BusyException], which the sender already handles by going
     * elsewhere. The status is this phone's latest reading, the one [caps] reports. Jobs already running are not touched, and a
     * repeat of a job id this phone already has gets that job's result.
     */
    private fun refuseWhenTooHot(exec: JobExecutor) {
        val status = health()?.thermalStatus ?: 0
        if (!CoreThermalGate.decide(status, WorkKind.REMOTE_JOB).allowed) throw BusyException(exec.running, exec.capacity, exec.queued)
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
        node.chat(jobId, req, pinModel, fromPeer = false)

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
