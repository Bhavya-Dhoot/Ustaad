package com.swarmos.app.llm

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bounded self-recoveries: the GPU is tried once more after the chat server fell back to the CPU build by itself. The decision is
 * [SelfRecovery.gpuWatch]; the loop runs on a real [StatusBoard] with the clock, the wait and the start injected, as the runtime wires them.
 */
class SelfRecoveryTest {
    private val min = 60_000L

    // ---- the GPU try: the decision ----

    /** Chat READY on the CPU build since 0 (started at 0, ready after 0 ms). */
    private fun cpuChat(state: ServerState = ServerState.READY, backend: Backend? = Backend.CPU) =
        ServerStatus(Role.CHAT, state, null, "/models/q.gguf", Role.CHAT.defaultPort, 1L, emptyList(), 0L, 0L, "The GPU server failed (x). Running on the CPU build.", 0, backend)

    private fun watch(
        epochCurrent: Boolean = true, chat: ServerStatus = cpuChat(), nowMs: Long = 10 * min, o: SelfRecovery.Outside = SelfRecovery.Outside(),
    ) = SelfRecovery.gpuWatch(epochCurrent, chat, nowMs, o)

    @Test fun whenEverythingHoldsTheGpuIsTried() {
        assertEquals(SelfRecovery.Watch.Go, watch())
        assertEquals("exactly ten minutes is enough", SelfRecovery.Watch.Go, watch(nowMs = 10 * min))
        assertEquals("exactly 60 s without real work is enough", SelfRecovery.Watch.Go, watch(o = SelfRecovery.Outside(realIdleMs = 60_000)))
    }

    @Test fun lessThanTenMinutesOnTheCpuWaits() {
        assertEquals(SelfRecovery.Watch.Wait, watch(nowMs = 10 * min - 1))
        assertEquals(SelfRecovery.Watch.Wait, watch(nowMs = 30_000))
        // counted from the moment the CPU server was READY, not from when it was started
        assertEquals(SelfRecovery.Watch.Wait, watch(chat = cpuChat().copy(startedAtMs = 5 * min, readyAfterMs = 60_000), nowMs = 12 * min))
        assertEquals(SelfRecovery.Watch.Go, watch(chat = cpuChat().copy(startedAtMs = 5 * min, readyAfterMs = 60_000), nowMs = 16 * min))
    }

    @Test fun anAppNotInFrontWaits() {
        assertEquals(SelfRecovery.Watch.Wait, watch(o = SelfRecovery.Outside(inFront = false)))
    }

    @Test fun realModelWorkInTheLastMinuteWaits() {
        assertEquals(SelfRecovery.Watch.Wait, watch(o = SelfRecovery.Outside(realIdleMs = 59_999)))
        assertEquals(SelfRecovery.Watch.Wait, watch(o = SelfRecovery.Outside(realIdleMs = 0))) // 0 while a real job runs
    }

    @Test fun aPhoneThatIsTooHotForAModelLoadWaits() {
        assertEquals(SelfRecovery.Watch.Wait, watch(o = SelfRecovery.Outside(heatAllows = false)))
    }

    @Test fun theOneTryUsedUpEndsTheWatch() {
        assertEquals(SelfRecovery.Watch.End, watch(o = SelfRecovery.Outside(retryUsed = true)))
    }

    @Test fun aCpuServerTheOwnerChoseIsNeverTouched() {
        // the GPU switch is off, or the owner turned it off and on (the failure memory is gone): not the watcher's business
        assertEquals(SelfRecovery.Watch.End, watch(o = SelfRecovery.Outside(gpuAllowed = false)))
    }

    @Test fun aMovedEpochEndsTheWatch() {
        assertEquals("Stop, a new load, the GPU switch", SelfRecovery.Watch.End, watch(epochCurrent = false))
    }

    @Test fun theServiceBeingDownEndsTheWatch() {
        assertEquals(SelfRecovery.Watch.End, watch(o = SelfRecovery.Outside(serviceUp = false)))
    }

    @Test fun aChatServerThatIsNotReadyOnTheCpuEndsTheWatch() {
        assertEquals("already on the GPU", SelfRecovery.Watch.End, watch(chat = cpuChat(backend = Backend.GPU)))
        assertEquals("loading again", SelfRecovery.Watch.End, watch(chat = cpuChat(state = ServerState.STARTING)))
        assertEquals(SelfRecovery.Watch.End, watch(chat = cpuChat(state = ServerState.FAILED)))
        assertEquals(SelfRecovery.Watch.End, watch(chat = cpuChat(state = ServerState.STOPPED, backend = null)))
    }

    // ---- the GPU try: the loop ----

    private class Clock {
        var now = 0L
        val waits = mutableListOf<Long>()
    }

    private fun chatBoard(): Pair<StatusBoard, Int> {
        val b = StatusBoard()
        val epoch = b.newEpoch(Role.CHAT)
        assertTrue(b.setIfCurrent(Role.CHAT, epoch) { cpuChat() })
        return b to epoch
    }

    private fun StatusBoard.runWatch(
        epoch: Int, c: Clock, outside: () -> SelfRecovery.Outside = { SelfRecovery.Outside() }, wait: suspend (Long) -> Unit = { c.waits += it; c.now += it },
        retry: suspend () -> Unit,
    ) = runBlocking { watchCpuFallback(epoch, outside, { c.now }, wait, retry) }

    @Test fun theWatchRechecksEveryThirtySecondsAndTriesTheGpuOnceAfterTenMinutes() {
        val (b, epoch) = chatBoard()
        val c = Clock()
        var tries = 0
        b.runWatch(epoch, c) { tries++ }
        assertEquals(1, tries)
        assertTrue(c.waits.all { it == 30_000L })
        assertEquals("twenty rechecks of 30 s: the tenth minute", 20, c.waits.size)
    }

    @Test fun theWatchWaitsWhileThePhoneIsBusyAndTriesWhenItIsQuiet() {
        val (b, epoch) = chatBoard()
        val c = Clock()
        var tries = 0
        // real work for the first 15 minutes, then quiet: the try is made only once a full minute of quiet has passed
        b.runWatch(epoch, c, outside = { SelfRecovery.Outside(realIdleMs = if (c.now < 15 * min) 0 else c.now - 15 * min) }) { tries++ }
        assertEquals(1, tries)
        assertEquals(15 * min + 60_000, c.now)
    }

    @Test fun theOwnersStopEndsTheWatchWithoutATry() {
        val (b, epoch) = chatBoard()
        val c = Clock()
        var tries = 0
        b.runWatch(epoch, c, wait = { c.waits += it; c.now += it; b.stop(Role.CHAT) { null } }) { tries++ }
        assertEquals(0, tries)
        assertEquals(1, c.waits.size)
    }

    @Test fun aNewLoadByTheOwnerEndsTheWatchWithoutATry() {
        val (b, epoch) = chatBoard()
        val c = Clock()
        var tries = 0
        b.runWatch(epoch, c, wait = { c.waits += it; c.now += it; b.newEpoch(Role.CHAT) }) { tries++ }
        assertEquals(0, tries)
        assertEquals(1, c.waits.size)
    }

    @Test fun theWatchEndsWhenTheServiceStops() {
        val (b, epoch) = chatBoard()
        val c = Clock()
        var tries = 0
        b.runWatch(epoch, c, outside = { SelfRecovery.Outside(serviceUp = c.now < 2 * min) }) { tries++ }
        assertEquals(0, tries)
        assertEquals(4, c.waits.size)
    }

    @Test fun theWatchEndsWhenTheOwnerTurnsTheGpuSwitchOff() {
        val (b, epoch) = chatBoard()
        val c = Clock()
        var tries = 0
        b.runWatch(epoch, c, outside = { SelfRecovery.Outside(gpuAllowed = c.now < 2 * min) }) { tries++ }
        assertEquals(0, tries)
    }

    @Test fun theWatchEndsWhenTheRoleLeavesTheCpuFallback() {
        val (b, epoch) = chatBoard()
        val c = Clock()
        var tries = 0
        b.runWatch(epoch, c, wait = { c.waits += it; c.now += it; b.setIfCurrent(Role.CHAT, epoch) { s -> s.copy(backend = Backend.GPU) } }) { tries++ }
        assertEquals(0, tries)
    }

    @Test fun aWatchWhoseTryWasUsedUpMakesNone() {
        val (b, epoch) = chatBoard()
        val c = Clock()
        var tries = 0
        b.runWatch(epoch, c, outside = { SelfRecovery.Outside(retryUsed = true) }) { tries++ }
        assertEquals(0, tries)
    }
}
