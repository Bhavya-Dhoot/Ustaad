package com.swarmos.app.busy

import android.os.PowerManager
import com.swarmos.app.AppGraph
import com.swarmos.app.choir.ChoirController
import com.swarmos.app.llm.ChatChunk
import com.swarmos.app.llm.ChatRequest
import com.swarmos.app.llm.DownloadState
import com.swarmos.app.llm.LlamaArgs
import com.swarmos.app.llm.LlamaRuntime
import com.swarmos.app.llm.LlamaServerClient
import com.swarmos.app.llm.ModelStore
import com.swarmos.app.llm.Role
import com.swarmos.app.llm.Selftest
import com.swarmos.app.llm.ServerState
import com.swarmos.app.memcloud.MemCloud
import com.swarmos.app.memcloud.MemCloudLogic
import com.swarmos.app.memcloud.RpcWorker
import com.swarmos.app.pdu.PduEngine
import com.swarmos.app.sched.Sched
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.swarmos.app.llm.Bench as ModelBench
import com.swarmos.app.sched.Bench as BenchPrompts

/** Connects the filler loop to this phone: its chat model and slots, its health, the Choir problems and the benchmark prompts. */
object BusyApp {
    private val prompts: List<ChatRequest> by lazy {
        BusyPrompts.rotation(
            runCatching { ChoirController.problems.map { it.prompt() } }.getOrDefault(emptyList()),
            BenchPrompts.bench32(),
        )
    }

    fun loop(): BusyLoop = BusyLoop(
        exec = Sched.node.chatExec,
        stream = ::fillerStream,
        prompts = { prompts },
        health = { AppGraph.health.state.value },
        chat = ::chatServer,
        enabled = { BusyMode.enabled.value },
        counters = BusyMode.counters,
        publish = BusyMode::publish,
        appInFront = { AppGraph.foreground.value },
        batteryOptimizationIgnored = {
            AppGraph.appContext.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(AppGraph.appContext.packageName) == true
        },
        externalBusy = ::otherWorkRunning,
        protect = {
            // a finished stream records its decode speed in two shared places; filler must not feed them
            val tps = AppGraph.health.lastDecodeTokPerSec
            val stats = LlamaRuntime.lastStats.value
            val restore: () -> Unit = {
                AppGraph.health.lastDecodeTokPerSec = tps
                LlamaRuntime.lastStats.value = stats
            }
            restore
        },
    )

    /**
     * The filler request. On the real server it is sent with `cache_prompt` off and, when the server has two or more slots
     * (it has two), pinned to the last slot with `id_slot`, so it does not take the slot that holds a real conversation's
     * prompt cache. Anything else (the placeholder) gets the normal call.
     */
    private fun fillerStream(req: ChatRequest): Flow<ChatChunk> {
        val client = AppGraph.llm
        if (client !is LlamaServerClient) return client.chatStream(req)
        val slots = LlamaArgs.slots(LlamaRuntime.statusOf(Role.CHAT).args)
        val pin = if (slots >= 2) buildJsonObject { put("id_slot", slots - 1) } else null
        return client.chatStreamRaw(req, cachePrompt = false, extra = pin)
    }

    /** The chat server while a real model is READY; its start time tells the loop when it was (re)started. */
    private fun chatServer(): ChatServer? {
        if (!LlamaRuntime.realChatReady.value) return null
        val st = LlamaRuntime.statusOf(Role.CHAT)
        if (st.state != ServerState.READY) return null
        val readySince = st.startedAtMs + (st.readyAfterMs ?: 0L)
        return ChatServer(st.startedAtMs, (System.currentTimeMillis() - readySince).coerceAtLeast(0L))
    }

    /** Work that holds the model or the phone's memory without taking a chat slot: it counts as real work for the loop. */
    private fun otherWorkRunning(): Boolean =
        ModelBench.running.value || Selftest.running.value ||
            MemCloudLogic.holdsPhone(MemCloud.state.value.phase) || RpcWorker.lending.value != null ||
            ChoirController.busy.value || PduEngine.progress.value.running ||
            ModelStore.downloads.value.values.any { it is DownloadState.Downloading || it is DownloadState.Verifying }
}
