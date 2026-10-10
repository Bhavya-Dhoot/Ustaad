package com.swarmos.app.llm

/**
 * Bounded self-recoveries, so a phone does not stay worse off until somebody notices. Each is decided here by pure functions;
 * [watchCpuFallback] is the loop around the first, on a [StatusBoard] so it stops when its epoch moves.
 *  - The chat server fell back to the CPU build by itself: the GPU is tried once more when the phone is quiet.
 * A server the owner chose to run on the CPU (GPU switch off), and a start the owner asked for, are never touched.
 */
internal object SelfRecovery {
    /** The CPU build must have served this long: a GPU that failed minutes ago is not worth another 120 s load. */
    const val GPU_RETRY_AFTER_CPU_READY_MS = 10 * 60_000L

    /** No real model work for this long: the retry takes the chat server down for the GPU load and must not cut into a conversation. */
    const val GPU_RETRY_IDLE_MS = 60_000L

    /** How often the conditions are looked at again while they do not hold. */
    const val GPU_RETRY_RECHECK_MS = 30_000L

    /** What the watcher cannot read from the board. [retryUsed]: the one GPU try of this epoch was made. [gpuAllowed]: the GPU switch is on and the owner has not cleared the failure memory (the switch does). */
    data class Outside(
        val serviceUp: Boolean = true, val gpuAllowed: Boolean = true, val retryUsed: Boolean = false,
        val inFront: Boolean = true, val realIdleMs: Long = Long.MAX_VALUE, val heatAllows: Boolean = true,
    )

    sealed interface Watch {
        /** Nothing more to do: stop watching. */
        data object End : Watch

        /** Not yet: look again later. */
        data object Wait : Watch

        /** Start chat on the GPU now. */
        data object Go : Watch
    }

    /** [chat] is the chat status now; [epochCurrent] false when a stop, a load or the like moved the epoch the CPU server was started under. */
    fun gpuWatch(epochCurrent: Boolean, chat: ServerStatus, nowMs: Long, o: Outside): Watch {
        val onCpuFallback = chat.state == ServerState.READY && chat.backend == Backend.CPU
        if (!epochCurrent || !o.serviceUp || !onCpuFallback || !o.gpuAllowed || o.retryUsed) return Watch.End
        val cpuReadyForMs = nowMs - (chat.startedAtMs + (chat.readyAfterMs ?: 0L))
        return if (cpuReadyForMs < GPU_RETRY_AFTER_CPU_READY_MS || !o.inFront || o.realIdleMs < GPU_RETRY_IDLE_MS || !o.heatAllows) Watch.Wait else Watch.Go
    }
}

/**
 * Watches the chat server of [epoch], which runs on the CPU build after an automatic fallback, and calls [retry] once when
 * [SelfRecovery.gpuWatch] says go. Ends when it says End, or after [retry].
 */
internal suspend fun StatusBoard.watchCpuFallback(
    epoch: Int, outside: () -> SelfRecovery.Outside, nowMs: () -> Long,
    wait: suspend (Long) -> Unit, retry: suspend () -> Unit,
) {
    while (true) {
        wait(SelfRecovery.GPU_RETRY_RECHECK_MS)
        when (SelfRecovery.gpuWatch(isCurrent(Role.CHAT, epoch), statusOf(Role.CHAT), nowMs(), outside())) {
            SelfRecovery.Watch.End -> return
            SelfRecovery.Watch.Wait -> {}
            SelfRecovery.Watch.Go -> { retry(); return }
        }
    }
}
