package com.swarmos.app.llm

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Two bounded self-recoveries, so a phone does not stay worse off until somebody notices. Both are decided here by pure functions;
 * [watchCpuFallback] and [reviveFailed] are the loops around them, on a [StatusBoard] so they stop when its epoch moves.
 *  - The chat server fell back to the CPU build by itself: the GPU is tried once more when the phone is quiet.
 *  - A server whose automatic restarts ran out is started again, a few times, after a long pause.
 * A server the owner chose to run on the CPU (GPU switch off), and a start the owner asked for, are never touched.
 */
internal object SelfRecovery {
    /** The CPU build must have served this long: a GPU that failed minutes ago is not worth another 120 s load. */
    const val GPU_RETRY_AFTER_CPU_READY_MS = 10 * 60_000L

    /** No real model work for this long: the retry takes the chat server down for the GPU load and must not cut into a conversation. */
    const val GPU_RETRY_IDLE_MS = 60_000L

    /** How often the conditions are looked at again while they do not hold. */
    const val GPU_RETRY_RECHECK_MS = 30_000L

    /** Whatever killed a server twice (memory pressure, heat) may have passed after this long. */
    const val REVIVE_AFTER_MS = 5 * 60_000L

    /** Three starts, then the owner decides. */
    const val MAX_REVIVALS = 3

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

    /**
     * Whether a watcher is armed after a chat start ended as [chat]: it runs on the CPU build while the GPU is wanted ([gpuWanted]: the switch is
     * on, the GPU build is there, no split) and a GPU failure is remembered, and the one try of this epoch is not spent. Judged from what is
     * true, not from the fallback note: a CPU server restarted after a death carries no note, and must be watched all the same.
     */
    fun shouldWatch(gpuWanted: Boolean, chat: ServerStatus, gpuFailureRemembered: Boolean, retryUsed: Boolean): Boolean =
        gpuWanted && gpuFailureRemembered && !retryUsed && chat.state == ServerState.READY && chat.backend == Backend.CPU

    /** [chat] is the chat status now; [epochCurrent] false when a stop, a load or the like moved the epoch the CPU server was started under. */
    fun gpuWatch(epochCurrent: Boolean, chat: ServerStatus, nowMs: Long, o: Outside): Watch {
        val onCpuFallback = chat.state == ServerState.READY && chat.backend == Backend.CPU
        if (!epochCurrent || !o.serviceUp || !onCpuFallback || !o.gpuAllowed || o.retryUsed) return Watch.End
        val cpuReadyForMs = nowMs - (chat.startedAtMs + (chat.readyAfterMs ?: 0L))
        return if (cpuReadyForMs < GPU_RETRY_AFTER_CPU_READY_MS || !o.inFront || o.realIdleMs < GPU_RETRY_IDLE_MS || !o.heatAllows) Watch.Wait else Watch.Go
    }

    sealed interface Revival {
        data class Wait(val afterMs: Long, val text: String) : Revival
        data class GiveUp(val text: String) : Revival
    }

    /** [revivalsSoFar]: starts already made after the restarts ran out. [zone] is for the time the text names. */
    fun revival(revivalsSoFar: Int, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Revival {
        if (revivalsSoFar >= MAX_REVIVALS) return Revival.GiveUp("Not loaded: the server stopped and $MAX_REVIVALS tries to start it again failed. Tap Load to start it.")
        val at = LocalTime.ofInstant(Instant.ofEpochMilli(nowMs + REVIVE_AFTER_MS), zone).format(DateTimeFormatter.ofPattern("HH:mm"))
        return Revival.Wait(REVIVE_AFTER_MS, "Not loaded: the server stopped and could not be restarted. Ustaad starts it again at $at (try ${revivalsSoFar + 1} of $MAX_REVIVALS).")
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

/**
 * [role] is FAILED because its automatic restarts ran out: starts it again by [attempt] (told which try it is, and returning what the start ended
 * as), [SelfRecovery.REVIVE_AFTER_MS] apart, at most [SelfRecovery.MAX_REVIVALS] times. The status says when the next try is, and after the last
 * one to tap Load. Ends when a try does not end FAILED (a server that got READY and is killed again is a new death, with a loop of its own: the
 * status is not read again here), or when anything changed: the epoch moved (Stop, a new load), the role is no longer FAILED, the service is
 * down, or the file is gone.
 */
internal suspend fun StatusBoard.reviveFailed(
    role: Role, epoch: Int, revivalsSoFar: Int, fileName: String,
    serviceUp: () -> Boolean, fileExists: () -> Boolean, nowMs: () -> Long,
    wait: suspend (Long) -> Unit, attempt: suspend (revival: Int) -> ServerState,
) {
    var n = revivalsSoFar
    while (statusOf(role).state == ServerState.FAILED) {
        when (val d = SelfRecovery.revival(n, nowMs())) {
            is SelfRecovery.Revival.GiveUp -> { setIfCurrent(role, epoch) { it.copy(reason = d.text, notice = null) }; return }
            is SelfRecovery.Revival.Wait -> {
                if (!setIfCurrent(role, epoch) { it.copy(reason = d.text, notice = null) }) return
                wait(d.afterMs)
                if (!serviceUp() || !isCurrent(role, epoch) || statusOf(role).state != ServerState.FAILED) return
                if (!fileExists()) { setIfCurrent(role, epoch) { it.copy(reason = StartRetry.fileGone(fileName), notice = null) }; return }
                if (attempt(++n) != ServerState.FAILED) return
            }
        }
    }
}
