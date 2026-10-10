package com.swarmos.app.llm

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * The interleavings between a server's death, a stop() and the automatic restart. The runtime keeps the epoch a server was
 * started under in its running record and writes status only through [StatusBoard.setIfCurrent]; these tests drive the
 * same board API in the orders the runtime's threads can reach.
 */
class StatusBoardTest {
    private val role = Role.CHAT
    private val model = "/models/Qwen3.5-4B-Q4_K_M.gguf"

    private fun ready(restarts: Int = 0) = ServerStatus(
        role, ServerState.READY, null, model, role.defaultPort, 4242L, listOf("-t", "4"), 1L, 9_000L, null, restarts, Backend.GPU,
    )

    private fun failedFrom(prior: ServerStatus) =
        ServerStatus(role, ServerState.FAILED, "killed", model, role.defaultPort, null, prior.args, prior.startedAtMs, null, null, prior.restarts, prior.backend)

    /** A board whose CHAT server is READY under a fresh epoch, the state a start leaves behind. */
    private fun readyBoard(restarts: Int = 0): Pair<StatusBoard, Int> {
        val b = StatusBoard()
        val epoch = b.newEpoch(role)
        assertTrue(b.setIfCurrent(role, epoch) { ready(restarts) })
        return b to epoch
    }

    private suspend fun StatusBoard.death(
        epoch: Int, restart: () -> Unit, wait: suspend (Long) -> Unit = {}, serviceUp: Boolean = true, aliveMs: Long = 30_000,
    ) = reportUnexpectedExit(
        role, epoch, wasReady = true, serviceUp = { serviceUp }, aliveMs = aliveMs, extraArgs = emptyList(), what = "killed by signal 9",
        failedStatus = { failedFrom(it) }, wait = wait, restart = { restart() },
    )

    @Test fun staleWritesAfterAStopAreIgnored() {
        val b = StatusBoard()
        val epoch = b.newEpoch(role)
        assertTrue(b.setIfCurrent(role, epoch) { it.copy(state = ServerState.STARTING, modelPath = model) })
        b.stop(role) { null }
        assertFalse(b.setIfCurrent(role, epoch) { it.copy(state = ServerState.STARTING) })
        assertFalse(b.setIfCurrent(role, epoch) { failedFrom(it) })
        assertFalse(b.setIfCurrent(role, epoch) { it.copy(state = ServerState.READY) })
        assertEquals(ServerState.STOPPED, b.statusOf(role).state)
        assertNull(b.statusOf(role).modelPath)
    }

    @Test fun aNewerStartSupersedesTheOlderOnesWrites() {
        val b = StatusBoard()
        val old = b.newEpoch(role)
        val newer = b.newEpoch(role)
        assertFalse(b.setIfCurrent(role, old) { it.copy(state = ServerState.READY) })
        assertTrue(b.setIfCurrent(role, newer) { it.copy(state = ServerState.STARTING) })
        assertEquals(ServerState.STARTING, b.statusOf(role).state)
    }

    @Test fun rolesDoNotShareAnEpoch() {
        val b = StatusBoard()
        val chat = b.newEpoch(Role.CHAT)
        b.stop(Role.EMBED) { null }
        assertTrue(b.setIfCurrent(Role.CHAT, chat) { it.copy(state = ServerState.STARTING) })
    }

    @Test fun stopDetachesTheRunningRecordUnderTheLock() {
        val b = StatusBoard()
        val detached = b.stop(role) { "the running record" }
        assertEquals("the running record", detached)
    }

    @Test fun deathThenRestartWhenNobodyStopsIt() = runBlocking {
        val (b, epoch) = readyBoard()
        var restarts = 0
        var seenDuringWait: ServerStatus? = null
        b.death(epoch, restart = { restarts++ }, wait = { seenDuringWait = b.statusOf(role) })
        assertEquals(1, restarts)
        val during = seenDuringWait!!
        assertEquals(ServerState.STARTING, during.state)
        assertEquals(1, during.restarts)
        assertTrue(during.notice!!.contains("Restarting"))
    }

    @Test fun aStopDuringTheBackoffCancelsTheRestartAndStaysStopped() = runBlocking {
        val (b, epoch) = readyBoard()
        var restarts = 0
        b.death(epoch, restart = { restarts++ }, wait = { b.stop(role) { null } })
        assertEquals(0, restarts)
        assertEquals(ServerState.STOPPED, b.statusOf(role).state)
        assertNull(b.statusOf(role).notice)
    }

    @Test fun aStopBetweenTheDeathAndTheFailureReportLeavesNoStaleFailed() = runBlocking {
        // the exit thread has seen the process die; stop() runs before it publishes anything
        val (b, epoch) = readyBoard()
        b.stop(role) { null }
        var restarts = 0
        b.death(epoch, restart = { restarts++ })
        assertEquals(0, restarts)
        assertEquals(ServerState.STOPPED, b.statusOf(role).state)
        assertNull(b.statusOf(role).reason)
    }

    @Test fun aNewStartDuringTheBackoffSupersedesTheAutomaticRestart() = runBlocking {
        val (b, epoch) = readyBoard()
        var restarts = 0
        b.death(epoch, restart = { restarts++ }, wait = { b.newEpoch(role) })
        assertEquals(0, restarts)
    }

    @Test fun theSecondDeathInOneEpisodeGivesUpWithABanner() = runBlocking {
        val (b, epoch) = readyBoard(restarts = 1)
        var restarts = 0
        b.death(epoch, restart = { restarts++ })
        assertEquals(0, restarts)
        val s = b.statusOf(role)
        assertEquals(ServerState.FAILED, s.state)
        assertTrue(s.notice!!.contains("not restarted"))
    }

    @Test fun noRestartWhenTheServiceIsGone() = runBlocking {
        val (b, epoch) = readyBoard()
        var restarts = 0
        b.death(epoch, restart = { restarts++ }, serviceUp = false)
        assertEquals(0, restarts)
        assertEquals(ServerState.FAILED, b.statusOf(role).state)
    }

    @Test fun aServiceStopDuringTheBackoffCancelsTheRestart() = runBlocking {
        val (b, epoch) = readyBoard()
        var up = true
        var restarts = 0
        b.reportUnexpectedExit(
            role, epoch, true, { up }, 30_000, emptyList(), "killed by signal 9", { failedFrom(it) },
            wait = { up = false }, restart = { restarts++ },
        )
        assertEquals(0, restarts)
    }

    @Test fun raceBetweenStopAndLateWritesAlwaysEndsStopped() {
        repeat(300) {
            val b = StatusBoard()
            val epoch = b.newEpoch(role)
            val go = CountDownLatch(1)
            val writer = thread {
                go.await()
                repeat(50) {
                    b.setIfCurrent(role, epoch) { it.copy(state = ServerState.STARTING, modelPath = model) }
                    b.setIfCurrent(role, epoch) { failedFrom(it) }
                    b.setIfCurrent(role, epoch) { it.copy(state = ServerState.READY) }
                }
            }
            go.countDown()
            b.stop(role) { null }
            writer.join()
            // whatever the writer managed before the stop was overwritten by it; nothing got in after
            assertEquals(ServerState.STOPPED, b.statusOf(role).state)
            assertNull(b.statusOf(role).modelPath)
        }
    }

    @Test fun aDeathReportRacingAStopNeverLeavesAStaleStatus() {
        repeat(200) {
            val (b, epoch) = readyBoard()
            val go = CountDownLatch(1)
            val reporter = thread {
                go.await()
                runBlocking { b.death(epoch, restart = { }, wait = { Thread.yield() }) }
            }
            go.countDown()
            b.stop(role) { null }
            reporter.join()
            val s = b.statusOf(role)
            assertEquals("stale ${s.state} after a stop", ServerState.STOPPED, s.state)
            assertNull(s.notice)
        }
    }

    @Test fun realReadyNeedsAReadyServerWithAModelFile() {
        assertTrue(ready().isRealReady())
        assertFalse(ready().copy(state = ServerState.STARTING).isRealReady())
        assertFalse(ready().copy(state = ServerState.FAILED).isRealReady())
        assertFalse(ServerStatus(Role.CHAT).isRealReady())
        assertFalse(ready().copy(modelPath = null).isRealReady()) // rpc-server has no model
    }
}
