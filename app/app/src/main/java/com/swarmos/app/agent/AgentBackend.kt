package com.swarmos.app.agent

import com.swarmos.app.AppGraph
import com.swarmos.app.llm.ChatChunk
import com.swarmos.app.llm.ChatResult
import com.swarmos.app.llm.ChatRequest
import com.swarmos.app.llm.ChatStats
import com.swarmos.app.llm.FakeLlmClient
import com.swarmos.app.llm.LlamaRuntime
import com.swarmos.app.llm.LlmClient
import com.swarmos.app.sched.GatedLlmClient
import com.swarmos.app.sched.SchedNode
import com.swarmos.app.sched.Scheduler
import com.swarmos.app.sched.Sched
import com.swarmos.app.sched.SwarmLlm
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.util.UUID

/** What answered a non-streamed chat call, and which phone ran it. */
class AgentReply(val text: String, val stats: ChatStats?, val phone: String)

/**
 * The models behind the endpoint. [local] must be a gated client ([gate]): the gate lets one call run at a time and
 * refuses after its wait, and it is the only thing between an app's request and the model slot. [swarm] is the scheduler
 * over every online phone. The ready checks refuse early; [RealModelOnly] refuses at the moment of the call, so the
 * placeholder model never answers another app even when the model goes away while a request waits.
 */
class AgentBackend(
    private val local: LlmClient,
    private val localReady: () -> Boolean,
    private val swarm: SwarmLlm,
    private val swarmReady: suspend () -> Boolean,
    private val embedder: () -> LlmClient,
    private val embedderReady: () -> Boolean,
    /** This phone's name. */
    private val selfName: () -> String,
    /** The name of the phone with this node id; the id itself when unknown. */
    private val nameOf: (String) -> String,
) {
    /** The 503 message when [model] cannot answer now, else null. */
    suspend fun chatUnavailable(model: AgentModel): String? = when (model) {
        AgentModel.LOCAL -> if (localReady()) null else NO_MODEL
        AgentModel.SWARM -> if (swarmReady()) null else NO_MODEL_SWARM
    }

    suspend fun chat(model: AgentModel, req: ChatRequest): AgentReply = when (model) {
        AgentModel.LOCAL -> local.chat(req).let { AgentReply(it.text, it.stats, selfName()) }
        AgentModel.SWARM -> swarm.chatJob(UUID.randomUUID().toString(), req).let { AgentReply(it.text, it.stats.toStats(), nameOf(it.nodeId)) }
    }

    fun chatStream(model: AgentModel, req: ChatRequest): Flow<ChatChunk> = when (model) {
        AgentModel.LOCAL -> local.chatStream(req)
        AgentModel.SWARM -> swarm.chatStream(req)
    }

    /**
     * The phone named in `system_fingerprint` of a streamed answer. The swarm does not say which phone streamed until
     * the stream is over (SwarmLlm.chatStream returns a bare flow), so a streamed swarm answer carries "swarm".
     */
    fun streamPhone(model: AgentModel): String = when (model) {
        AgentModel.LOCAL -> selfName()
        AgentModel.SWARM -> "swarm"
    }

    fun embedUnavailable(): String? = if (embedderReady()) null else NO_EMBEDDER

    suspend fun embed(texts: List<String>): List<FloatArray> = embedder().embed(texts)

    companion object {
        const val NO_MODEL = "No chat model is loaded on this phone"
        const val NO_MODEL_SWARM = "No chat model is loaded on any phone in the swarm"
        const val NO_EMBEDDER = "No embedding model is loaded on this phone"

        /** This phone's chat model for other apps: the slots of [node] (the same ones `AppGraph.gatedLlm` uses), real model only. */
        fun gate(llm: () -> LlmClient, node: SchedNode, maxWaitMs: Long = GatedLlmClient.DEFAULT_MAX_WAIT_MS): LlmClient =
            GatedLlmClient({ RealModelOnly(llm) }, node, maxWaitMs)

        /** The app's own models: this phone through its gate, the swarm through the scheduler. */
        fun app(): AgentBackend = AgentBackend(
            local = gate({ AppGraph.llm }, Sched.node),
            localReady = { LlamaRuntime.realChatReady.value },
            swarm = Sched.swarm,
            swarmReady = {
                Sched.swarm.refreshCaps()
                Sched.swarm.phoneViews().any { v -> v.up && v.caps != null && Scheduler.eligible(v.caps, null) && v.caps.modelName != "FAKE" }
            },
            embedder = { RealModelOnly({ AppGraph.embedder }, NO_EMBEDDER) },
            embedderReady = { LlamaRuntime.realEmbedderReady.value },
            selfName = { AppGraph.registry.self.value.name },
            nameOf = { id ->
                if (id == AppGraph.registry.self.value.nodeId) AppGraph.registry.self.value.name
                else AppGraph.registry.peers.value.firstOrNull { it.info.nodeId == id }?.info?.name ?: id
            },
        )
    }
}

/** Another app's call, answered by the real model or refused with 503: never by the placeholder, whenever it was installed. */
class RealModelOnly(private val current: () -> LlmClient, private val whenMissing: String = AgentBackend.NO_MODEL) : LlmClient {
    private fun real(): LlmClient = current().takeUnless { it is FakeLlmClient } ?: throw AgentRefusal(503, whenMissing, "model_not_loaded", AgentRefusal.TYPE_SERVER)

    override val modelName: String get() = current().modelName

    override suspend fun ready(): Boolean = current().let { it !is FakeLlmClient && it.ready() }

    override fun chatStream(req: ChatRequest): Flow<ChatChunk> = flow { emitAll(real().chatStream(req)) }

    override suspend fun chat(req: ChatRequest): ChatResult = real().chat(req)

    override suspend fun embed(texts: List<String>): List<FloatArray> = real().embed(texts)
}
