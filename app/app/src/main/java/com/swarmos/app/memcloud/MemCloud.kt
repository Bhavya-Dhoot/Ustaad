package com.swarmos.app.memcloud

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The one MemoryCloud coordinator of this app, wired to the real phone ([AndroidSplitEnv]). The state machine itself is
 * [SplitCoordinator]; this object only gives the screen and the routes a single place to call.
 *
 * Nothing here touches storage until [onAppStart] (or the first start or stop) runs it on an IO thread.
 */
object MemCloud {
    const val SPLIT_CTX = SplitCoordinator.SPLIT_CTX

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val coordinator: SplitCoordinator by lazy { SplitCoordinator(AndroidSplitEnv, scope) }

    val state: StateFlow<MemCloudState> get() = coordinator.state

    /** The one line for AppGraph.init: `MemCloud.onAppStart()`. Cleans up what a killed session left (workers stopped, servers restored) off the main thread. */
    fun onAppStart() {
        scope.launch { coordinator.onAppStart() }
    }

    fun header(file: String): GgufInfo = coordinator.header(file)
    fun onlinePeers(selection: List<String> = emptyList()): List<PeerInfo> = coordinator.onlinePeers(selection)
    fun previewPlan(file: String, peers: List<String>, mode: SplitMode, manual: List<Int>? = null, emptyMeansNone: Boolean = false): SplitPlan = coordinator.previewPlan(file, peers, mode, manual, emptyMeansNone)
    suspend fun refreshPeers() = coordinator.refreshPeers()
    fun start(req: StartRequest): String? = coordinator.start(req)
    fun replan(peers: List<String> = emptyList()): String? = coordinator.replan(peers)
    fun stop() = coordinator.stop()
    fun ask(prompt: String, maxTokens: Int = 200): String? = coordinator.ask(prompt, maxTokens)
    fun cancelGeneration() = coordinator.cancelGeneration()
    fun baseline(timeoutSec: Int = 120, file: String? = null): String? = coordinator.baseline(timeoutSec, file)
}
