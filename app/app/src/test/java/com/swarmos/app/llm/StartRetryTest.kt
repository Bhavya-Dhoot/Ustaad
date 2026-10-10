package com.swarmos.app.llm

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A start that the app made by itself and a gate refused (too hot, not enough free memory) is tried again every 30 s, 20 times at most,
 * and the role says so while it waits. A start the owner asked for is not. The decision is [StartRetry.decide]; the loop is
 * [retryRefusedStart] on a real [StatusBoard], with the wait and the new start injected, as the runtime wires them.
 */
class StartRetryTest {
    private val role = Role.CHAT
    private val model = "/models/Qwen3.5-4B-Q4_K_M.gguf"
    private val gib = 1024L * 1024 * 1024
    private val memory = StartRefusal.Memory((4.8 * gib).toLong(), (3.1 * gib).toLong())

    // ---- the decision ----

    @Test fun anAutomaticStartRefusedForHeatIsRetriedAndSaysWhy() {
        val d = StartRetry.decide(StartRefusal.Hot, byOwner = false, triesSoFar = 0)
        assertEquals(StartRetry.Decision.Retry(30_000, "Not loaded: the phone is too hot. Ustaad tries again every 30 seconds."), d)
    }

    @Test fun anAutomaticStartRefusedForMemoryIsRetriedWithTheNumbersOfTheRefusal() {
        val d = StartRetry.decide(memory, byOwner = false, triesSoFar = 3)
        assertEquals(
            StartRetry.Decision.Retry(30_000, "Not loaded: not enough free memory (needs 4.8 GB, 3.1 GB free). Ustaad tries again every 30 seconds."),
            d,
        )
    }

    @Test fun aLoadTheOwnerAskedForIsNeverRetried() {
        assertEquals(StartRetry.Decision.NoRetry, StartRetry.decide(StartRefusal.Hot, byOwner = true, triesSoFar = 0))
        assertEquals(StartRetry.Decision.NoRetry, StartRetry.decide(memory, byOwner = true, triesSoFar = 0))
    }

    @Test fun itGivesUpAfterTwentyTriesAndTellsTheOwnerToTapLoad() {
        assertTrue(StartRetry.decide(StartRefusal.Hot, false, StartRetry.MAX_TRIES - 1) is StartRetry.Decision.Retry)
        val last = StartRetry.decide(StartRefusal.Hot, false, StartRetry.MAX_TRIES) as StartRetry.Decision.GiveUp
        assertEquals("Not loaded after 10 minutes of trying: the phone is too hot. Tap Load to try again.", last.text)
        val mem = StartRetry.decide(memory, false, StartRetry.MAX_TRIES) as StartRetry.Decision.GiveUp
        assertEquals("Not loaded after 10 minutes of trying: not enough free memory (needs 4.8 GB, 3.1 GB free). Tap Load to try again.", mem.text)
        assertEquals("twenty tries, 30 s apart", 10 * 60_000L, StartRetry.MAX_TRIES * StartRetry.INTERVAL_MS)
    }

    @Test fun theTextsArePlainEnglishWithoutDashesOrJargon() {
        val all = listOf(StartRefusal.Hot, memory).flatMap { r ->
            listOf(StartRetry.decide(r, false, 0), StartRetry.decide(r, false, StartRetry.MAX_TRIES)).map {
                when (it) { is StartRetry.Decision.Retry -> it.text; is StartRetry.Decision.GiveUp -> it.text; else -> "" }
            }
        } + StartRetry.fileGone("a.gguf")
        for (t in all) {
            assertFalse(t, t.contains('—') || t.contains('–'))
            assertFalse(t, t.contains("thermal") || t.contains("MemAvailable") || t.contains("epoch"))
            assertTrue(t, t.startsWith("Not loaded"))
        }
    }

    // ---- the loop ----

    /** A board with [role] FAILED under a fresh epoch, the way a refused start leaves it. */
    private fun refusedBoard(reason: String = "refused"): Pair<StatusBoard, Int> {
        val b = StatusBoard()
        val epoch = b.newEpoch(role)
        assertTrue(b.setIfCurrent(role, epoch) { ServerStatus(role, ServerState.FAILED, reason, model, role.defaultPort, restarts = 1, notice = "The automatic restart failed. Tap Load to start it again.") })
        return b to epoch
    }

    private class Harness {
        val waits = mutableListOf<Long>()
        var attempts = 0
        var serviceUp = true
        var fileExists = true
    }

    private fun StatusBoard.runLoop(
        epoch: Int, h: Harness, first: StartRefusal = StartRefusal.Hot, byOwner: Boolean = false,
        wait: suspend (Long) -> Unit = { h.waits += it },
        attempt: suspend () -> StartRefusal?,
    ) = runBlocking {
        retryRefusedStart(role, epoch, first, byOwner, "Qwen3.5-4B-Q4_K_M.gguf", { h.serviceUp }, { h.fileExists }, wait) { h.attempts++; attempt() }
    }

    @Test fun aRefusedAutomaticStartWaitsThirtySecondsSaysSoAndStartsAgain() {
        val (b, epoch) = refusedBoard()
        val h = Harness()
        var reasonWhileWaiting: String? = null
        var noticeWhileWaiting: String? = "unset"
        b.runLoop(epoch, h, wait = { h.waits += it; reasonWhileWaiting = b.statusOf(role).reason; noticeWhileWaiting = b.statusOf(role).notice }) {
            assertTrue(b.setIfCurrent(role, epoch) { it.copy(state = ServerState.READY, reason = null) })
            null
        }
        assertEquals(listOf(30_000L), h.waits)
        assertEquals("Not loaded: the phone is too hot. Ustaad tries again every 30 seconds.", reasonWhileWaiting)
        assertEquals("the old 'tap Load' banner does not contradict it", null, noticeWhileWaiting)
        assertEquals(1, h.attempts)
        assertEquals(ServerState.READY, b.statusOf(role).state)
    }

    @Test fun aRetryThatSucceedsEndsTheLoop() {
        val (b, epoch) = refusedBoard()
        val h = Harness()
        b.runLoop(epoch, h, first = memory) {
            if (h.attempts < 3) memory
            else { b.setIfCurrent(role, epoch) { it.copy(state = ServerState.STARTING, reason = null) }; null }
        }
        assertEquals("three waits, three tries, then nothing more", listOf(30_000L, 30_000L, 30_000L), h.waits)
        assertEquals(3, h.attempts)
        assertEquals(ServerState.STARTING, b.statusOf(role).state)
    }

    @Test fun itGivesUpAfterTwentyTriesWithAFinalMessageThatTellsTheOwnerToTapLoad() {
        val (b, epoch) = refusedBoard()
        val h = Harness()
        b.runLoop(epoch, h) { StartRefusal.Hot }
        assertEquals(StartRetry.MAX_TRIES, h.attempts)
        assertEquals(StartRetry.MAX_TRIES, h.waits.size)
        val st = b.statusOf(role)
        assertEquals(ServerState.FAILED, st.state)
        assertEquals("Not loaded after 10 minutes of trying: the phone is too hot. Tap Load to try again.", st.reason)
    }

    @Test fun theNumbersInTheMessageFollowTheLatestRefusal() {
        val (b, epoch) = refusedBoard()
        val h = Harness()
        val seen = mutableListOf<String?>()
        b.runLoop(epoch, h, first = memory, wait = { h.waits += it; seen += b.statusOf(role).reason }) {
            if (h.attempts < 2) StartRefusal.Memory((4.8 * gib).toLong(), (4.0 * gib).toLong()) else null
        }
        assertTrue(seen[0]!!.contains("3.1 GB free"))
        assertTrue(seen[1]!!.contains("4.0 GB free"))
    }

    @Test fun anOwnersLoadIsNotRetriedAndItsMessageIsLeftAlone() {
        val (b, epoch) = refusedBoard("This phone is too hot (thermal status critical). Loading a model is off until it cools down.")
        val h = Harness()
        b.runLoop(epoch, h, byOwner = true) { StartRefusal.Hot }
        assertEquals(0, h.waits.size)
        assertEquals(0, h.attempts)
        assertTrue(b.statusOf(role).reason!!.startsWith("This phone is too hot"))
    }

    @Test fun theOwnersStopCancelsTheLoopAndNothingIsStartedOrWritten() {
        val (b, epoch) = refusedBoard()
        val h = Harness()
        b.runLoop(epoch, h, wait = { h.waits += it; b.stop(role) { null } }) { StartRefusal.Hot }
        assertEquals(0, h.attempts)
        assertEquals(ServerState.STOPPED, b.statusOf(role).state)
        assertEquals(null, b.statusOf(role).reason)
    }

    @Test fun aDifferentModelOrANewLoadByTheOwnerCancelsTheLoop() {
        val (b, epoch) = refusedBoard()
        val h = Harness()
        var newer = 0
        b.runLoop(epoch, h, wait = { h.waits += it; newer = b.newEpoch(role) }) { StartRefusal.Hot }
        assertEquals(0, h.attempts)
        assertTrue("the newer start owns the status", b.isCurrent(role, newer))
        assertFalse("and the loop can no longer write to it", b.isCurrent(role, epoch))
    }

    @Test fun theLoopEndsWhenTheRoleLeavesFailedTheServiceStopsOrTheFileIsGone() {
        // left FAILED while it waited (something else loaded it)
        val (b1, e1) = refusedBoard()
        val h1 = Harness()
        b1.runLoop(e1, h1, wait = { h1.waits += it; b1.setIfCurrent(role, e1) { s -> s.copy(state = ServerState.READY, reason = null) } }) { StartRefusal.Hot }
        assertEquals(0, h1.attempts)
        assertEquals(ServerState.READY, b1.statusOf(role).state)
        // the service stopped
        val (b2, e2) = refusedBoard()
        val h2 = Harness()
        b2.runLoop(e2, h2, wait = { h2.waits += it; h2.serviceUp = false }) { StartRefusal.Hot }
        assertEquals(0, h2.attempts)
        // the file was deleted: the status says so and no further try is made
        val (b3, e3) = refusedBoard()
        val h3 = Harness()
        b3.runLoop(e3, h3, wait = { h3.waits += it; h3.fileExists = false }) { StartRefusal.Hot }
        assertEquals(0, h3.attempts)
        assertEquals(ServerState.FAILED, b3.statusOf(role).state)
        assertEquals(StartRetry.fileGone("Qwen3.5-4B-Q4_K_M.gguf"), b3.statusOf(role).reason)
        assertNotNull(b3.statusOf(role).reason)
    }

    @Test fun aRetryThatFailsForAnotherReasonEndsTheLoop() {
        val (b, epoch) = refusedBoard()
        val h = Harness()
        b.runLoop(epoch, h) {
            b.setIfCurrent(role, epoch) { it.copy(reason = "model file not found: $model") }
            null // not a heat or memory refusal
        }
        assertEquals(1, h.attempts)
        assertEquals("model file not found: $model", b.statusOf(role).reason)
    }
}
