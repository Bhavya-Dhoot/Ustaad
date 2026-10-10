package com.swarmos.app.llm

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A GPU chat server that keeps dying ends up on the CPU build, and a GPU failure is remembered per install. */
class GpuFallbackTest {
    private fun decide(role: Role = Role.CHAT, used: Int, aliveMs: Long = 40_000, backend: Backend? = Backend.GPU, args: List<String> = emptyList()) =
        RestartPolicy.decide(role, wasReady = true, serviceUp = true, restartsUsed = used, aliveMs = aliveMs, extraArgs = args, backend = backend)

    @Test fun theFirstGpuDeathIsRestartedOnTheGpuAsBefore() {
        assertEquals(RestartPolicy.Decision.Restart(1, 2_000), decide(used = 0))
    }

    @Test fun theSecondGpuDeathInsideTheWindowFallsBackToTheCpuBuildInsteadOfGivingUp() {
        assertEquals(RestartPolicy.Decision.CpuFallback(2_000), decide(used = 1))
    }

    @Test fun aGpuServerThatRanLongEnoughStartsAFreshEpisodeOnTheGpu() {
        assertEquals(RestartPolicy.Decision.Restart(1, 2_000), decide(used = 1, aliveMs = RestartPolicy.STABLE_MS))
    }

    @Test fun onlyAChatServerOnTheGpuFallsBack() {
        assertEquals(RestartPolicy.Decision.GiveUp, decide(used = 1, backend = Backend.CPU)) // the CPU build died twice: nowhere left to go
        assertEquals(RestartPolicy.Decision.GiveUp, decide(used = 1, backend = null))
        assertEquals(RestartPolicy.Decision.GiveUp, decide(role = Role.EMBED, used = 1))
        assertEquals(RestartPolicy.Decision.Ignore, decide(used = 1, args = listOf("--rpc", "10.0.0.2:50052"))) // MemoryCloud's to re-plan
    }

    private fun readyOnGpu(restarts: Int): Pair<StatusBoard, Int> {
        val b = StatusBoard()
        val epoch = b.newEpoch(Role.CHAT)
        b.setIfCurrent(Role.CHAT, epoch) {
            ServerStatus(Role.CHAT, ServerState.READY, null, "/m/q.gguf", Role.CHAT.defaultPort, 7L, emptyList(), 1L, 9_000L, null, restarts, Backend.GPU)
        }
        return b to epoch
    }

    private suspend fun StatusBoard.gpuDeath(epoch: Int, restart: (Boolean) -> Unit, wait: suspend (Long) -> Unit = {}) = reportUnexpectedExit(
        Role.CHAT, epoch, wasReady = true, serviceUp = { true }, aliveMs = 40_000, extraArgs = emptyList(), what = "killed by signal 9",
        failedStatus = { it.copy(state = ServerState.FAILED, reason = "killed") }, wait = wait, restart = { restart(it) }, backend = Backend.GPU,
    )

    @Test fun theBoardShowsTheFallbackBannerAndStartsTheCpuBuildOnce() = runBlocking {
        val (b, epoch) = readyOnGpu(restarts = 1)
        val calls = ArrayList<Boolean>()
        var during: ServerStatus? = null
        b.gpuDeath(epoch, restart = { calls += it }, wait = { during = b.statusOf(Role.CHAT) })
        assertEquals(listOf(true), calls)
        assertEquals(ServerState.STARTING, during!!.state)
        assertEquals(BackendPlan.fallbackNote("killed by signal 9"), during!!.notice)
    }

    @Test fun theFirstDeathStillRestartsWithoutTheCpuFlag() = runBlocking {
        val (b, epoch) = readyOnGpu(restarts = 0)
        val calls = ArrayList<Boolean>()
        b.gpuDeath(epoch, restart = { calls += it })
        assertEquals(listOf(false), calls)
    }

    @Test fun aStopDuringTheFallbackBackoffCancelsIt() = runBlocking {
        val (b, epoch) = readyOnGpu(restarts = 1)
        val calls = ArrayList<Boolean>()
        b.gpuDeath(epoch, restart = { calls += it }, wait = { b.stop(Role.CHAT) { null } })
        assertTrue(calls.isEmpty())
        assertEquals(ServerState.STOPPED, b.statusOf(Role.CHAT).state)
    }

    @Test fun theFallbackBannerIsTheOneAStartTimeFallbackShows() {
        assertEquals("The GPU server failed (not ready after 120 s). Running on the CPU build.", BackendPlan.fallbackNote("not ready after 120 s"))
        assertTrue(BackendPlan.fallbackNote(null).contains("unknown reason"))
        assertTrue(BackendPlan.fallbackNote("x".repeat(500)).length < 260)
    }

    @Test fun aRememberedGpuFailureSkipsTheGpuOnlyOnTheSameInstall() {
        assertTrue(GpuFailureMemory.skipGpu("0.1.0/1/1790000000000", "0.1.0/1/1790000000000"))
        assertFalse(GpuFailureMemory.skipGpu("0.1.0/1/1790000000000", "0.1.0/1/1790000999999")) // reinstalled: try the GPU again
        assertFalse(GpuFailureMemory.skipGpu("0.1.0/1/1", "0.2.0/2/1"))                        // another version
        assertFalse(GpuFailureMemory.skipGpu(null, "0.1.0/1/1"))                                // nothing remembered
    }

    @Test fun theSkippedNoteTellsTheOwnerHowToTryTheGpuAgain() {
        assertTrue(GpuFailureMemory.SKIPPED_NOTE.contains("Use GPU for chat"))
    }
}
