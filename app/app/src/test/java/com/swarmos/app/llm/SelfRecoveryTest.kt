package com.swarmos.app.llm

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/**
 * The two bounded self-recoveries: the GPU is tried once more after the chat server fell back to the CPU build by itself, and a server whose
 * automatic restarts ran out is started again, three times at most. The decisions are [SelfRecovery.gpuWatch] and [SelfRecovery.revival]; the loops
 * run on a real [StatusBoard] with the clock, the wait and the start injected, as the runtime wires them.
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

    // ---- the GPU try: who is watched ----

    @Test fun aCpuServerRestartedAfterADeathIsWatchedAgainThoughItCarriesNoFallbackNote() {
        val restarted = cpuChat().copy(notice = null) // the restart after a death clears the note and keeps the server on the CPU
        assertTrue(SelfRecovery.shouldWatch(gpuWanted = true, chat = restarted, gpuFailureRemembered = true, retryUsed = false))
        assertTrue(SelfRecovery.shouldWatch(gpuWanted = true, chat = cpuChat(), gpuFailureRemembered = true, retryUsed = false))
    }

    @Test fun noWatcherForACpuServerTheOwnerChoseOrWhenNoGpuFailureIsRemembered() {
        assertFalse("GPU switch off, no GPU build, or a split", SelfRecovery.shouldWatch(gpuWanted = false, chat = cpuChat(), gpuFailureRemembered = true, retryUsed = false))
        assertFalse("the owner cleared the failure with the switch", SelfRecovery.shouldWatch(gpuWanted = true, chat = cpuChat(), gpuFailureRemembered = false, retryUsed = false))
    }

    @Test fun noWatcherOnceTheOneTryOfThisEpochIsSpentOrWhenChatIsNotReadyOnTheCpu() {
        assertFalse(SelfRecovery.shouldWatch(gpuWanted = true, chat = cpuChat(), gpuFailureRemembered = true, retryUsed = true))
        assertFalse(SelfRecovery.shouldWatch(gpuWanted = true, chat = cpuChat(backend = Backend.GPU), gpuFailureRemembered = true, retryUsed = false))
        assertFalse(SelfRecovery.shouldWatch(gpuWanted = true, chat = cpuChat(state = ServerState.FAILED), gpuFailureRemembered = true, retryUsed = false))
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

    // ---- the revival: the decision ----

    @Test fun aServerWhoseRestartsRanOutIsStartedAgainFiveMinutesLaterThreeTimes() {
        val first = SelfRecovery.revival(0, 0, ZoneOffset.UTC) as SelfRecovery.Revival.Wait
        assertEquals(5 * min, first.afterMs)
        assertEquals("Not loaded: the server stopped and could not be restarted. Ustaad starts it again at 00:05 (try 1 of 3).", first.text)
        assertTrue((SelfRecovery.revival(2, 12 * min, ZoneOffset.UTC) as SelfRecovery.Revival.Wait).text.endsWith("at 00:17 (try 3 of 3)."))
    }

    @Test fun afterTheThirdTryTheStatusTellsTheOwnerToTapLoad() {
        val last = SelfRecovery.revival(3, 0, ZoneOffset.UTC) as SelfRecovery.Revival.GiveUp
        assertEquals("Not loaded: the server stopped and 3 tries to start it again failed. Tap Load to start it.", last.text)
        assertTrue(SelfRecovery.revival(7, 0) is SelfRecovery.Revival.GiveUp)
    }

    // ---- the revival: the loop ----

    private val role = Role.CHAT
    private val model = "/models/q.gguf"

    /** The role FAILED under a fresh epoch, the way a death with no restarts left leaves it. */
    private fun failedBoard(): Pair<StatusBoard, Int> {
        val b = StatusBoard()
        val epoch = b.newEpoch(role)
        assertTrue(b.setIfCurrent(role, epoch) { ServerStatus(role, ServerState.FAILED, "killed", model, role.defaultPort, restarts = 1) })
        return b to epoch
    }

    private class Revive {
        val waits = mutableListOf<Long>()
        val attempts = mutableListOf<Int>()
        var serviceUp = true
        var fileExists = true
    }

    /** [result]: what the start ended as; null reads the board after [attempt], as a start that fails does. */
    private fun StatusBoard.runRevive(
        epoch: Int, r: Revive, so: Int = 0, result: ServerState? = null, wait: suspend (Long) -> Unit = { r.waits += it }, attempt: suspend (Int) -> Unit,
    ) = runBlocking {
        reviveFailed(role, epoch, so, "q.gguf", { r.serviceUp }, { r.fileExists }, { 0L }, wait) { n -> r.attempts += n; attempt(n); result ?: statusOf(role).state }
    }

    @Test fun threeFailedTriesEndWithATapLoadMessageAndTheStatusSaysWhenTheNextOneIs() {
        val (b, epoch) = failedBoard()
        val r = Revive()
        val seen = mutableListOf<String?>()
        b.runRevive(epoch, r, wait = { r.waits += it; seen += b.statusOf(role).reason }) { /* the start fails again: still FAILED */ }
        assertEquals(listOf(5 * min, 5 * min, 5 * min), r.waits)
        assertEquals(listOf(1, 2, 3), r.attempts)
        assertTrue(seen[0]!!.contains("(try 1 of 3)") && seen[2]!!.contains("(try 3 of 3)"))
        assertEquals(ServerState.FAILED, b.statusOf(role).state)
        assertEquals("Not loaded: the server stopped and 3 tries to start it again failed. Tap Load to start it.", b.statusOf(role).reason)
        assertNull(b.statusOf(role).notice)
    }

    @Test fun aTryThatWorksEndsTheLoop() {
        val (b, epoch) = failedBoard()
        val r = Revive()
        b.runRevive(epoch, r) { n -> if (n == 2) b.setIfCurrent(role, epoch) { it.copy(state = ServerState.READY, reason = null) } }
        assertEquals(listOf(1, 2), r.attempts)
        assertEquals(2, r.waits.size)
        assertEquals(ServerState.READY, b.statusOf(role).state)
    }

    @Test fun aRevivedServerKilledBeforeTheLoopLooksIsLeftToTheLoopOfItsOwnDeath() {
        val (b, epoch) = failedBoard()
        val r = Revive()
        // the start ended READY, the process was killed at once, and the board already says FAILED when the loop looks again: that death has
        // its own loop (watchExit), so this one must end, not wait and start a second server under the same epoch
        b.runRevive(epoch, r, result = ServerState.READY) { }
        assertEquals(listOf(1), r.attempts)
        assertEquals(1, r.waits.size)
        assertEquals(ServerState.FAILED, b.statusOf(role).state)
    }

    @Test fun revivalsAlreadyMadeCount() {
        val (b, epoch) = failedBoard()
        val r = Revive()
        b.runRevive(epoch, r, so = 2) { }
        assertEquals("only the third try is left", listOf(3), r.attempts)
    }

    @Test fun theOwnersStopEndsTheLoopWithoutATry() {
        val (b, epoch) = failedBoard()
        val r = Revive()
        b.runRevive(epoch, r, wait = { r.waits += it; b.stop(role) { null } }) { }
        assertTrue(r.attempts.isEmpty())
        assertEquals(ServerState.STOPPED, b.statusOf(role).state)
    }

    @Test fun aNewLoadByTheOwnerEndsTheLoopWithoutATry() {
        val (b, epoch) = failedBoard()
        val r = Revive()
        b.runRevive(epoch, r, wait = { r.waits += it; b.newEpoch(role) }) { }
        assertTrue(r.attempts.isEmpty())
    }

    @Test fun theLoopEndsWhenTheServiceStops() {
        val (b, epoch) = failedBoard()
        val r = Revive()
        b.runRevive(epoch, r, wait = { r.waits += it; r.serviceUp = false }) { }
        assertTrue(r.attempts.isEmpty())
    }

    @Test fun theLoopEndsWhenTheRoleIsNoLongerFailed() {
        val (b, epoch) = failedBoard()
        val r = Revive()
        b.runRevive(epoch, r, wait = { r.waits += it; b.setIfCurrent(role, epoch) { s -> s.copy(state = ServerState.READY) } }) { }
        assertTrue(r.attempts.isEmpty())
    }

    @Test fun theLoopEndsWhenTheFileIsGoneAndTheStatusSaysSo() {
        val (b, epoch) = failedBoard()
        val r = Revive()
        b.runRevive(epoch, r, wait = { r.waits += it; r.fileExists = false }) { }
        assertTrue(r.attempts.isEmpty())
        assertEquals(StartRetry.fileGone("q.gguf"), b.statusOf(role).reason)
    }

    // ---- the death report: no "tap Load" while a revival is coming ----

    private fun ready() = ServerStatus(role, ServerState.READY, null, model, role.defaultPort, 4242L, listOf("-t", "4"), 1L, 9_000L, null, 1, Backend.CPU)

    private fun StatusBoard.death(reviveLater: Boolean) = runBlocking {
        val epoch = newEpoch(role)
        setIfCurrent(role, epoch) { ready() }
        reportUnexpectedExit(
            role, epoch, wasReady = true, serviceUp = { true }, aliveMs = 30_000, extraArgs = emptyList(), what = "killed by signal 9",
            failedStatus = { it.copy(state = ServerState.FAILED, reason = "killed", pid = null) }, wait = {}, restart = {}, backend = Backend.CPU, reviveLater = reviveLater,
        )
    }

    @Test fun whenARevivalFollowsTheGiveUpWritesNoTapLoadNoticeAndSaysItGaveUp() {
        val b = StatusBoard()
        assertEquals(RestartPolicy.Decision.GiveUp, b.death(reviveLater = true))
        assertNull(b.statusOf(role).notice)
        val c = StatusBoard()
        assertEquals(RestartPolicy.Decision.GiveUp, c.death(reviveLater = false))
        assertTrue(c.statusOf(role).notice!!.endsWith("Tap Load to start it."))
        assertFalse(c.statusOf(role).notice!!.contains("tries"))
    }
}
