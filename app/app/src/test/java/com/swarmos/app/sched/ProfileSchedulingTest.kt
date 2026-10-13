package com.swarmos.app.sched

import com.swarmos.app.core.DeviceFixtures
import com.swarmos.app.core.DeviceState
import com.swarmos.app.core.ProfilePlan
import com.swarmos.app.core.SwarmJson
import com.swarmos.app.llm.ChatStats
import com.swarmos.app.llm.ModelCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Caps as the build before the device check defined its fields. */
@Serializable
private data class OldCaps(
    val nodeId: String, val modelName: String, val capacity: Int, val running: Int, val queued: Int,
    val lastDecodeTokPerSec: Double?, val thermalStatus: Int, val batteryPct: Int, val charging: Boolean, val ramAvailMb: Long,
)

/** A phone that reports a stored (profile) speed, whatever its live one. */
private class WithProfileSpeed(private val inner: FakePhone, private val profile: Double?) : PhoneEndpoint by inner {
    override suspend fun caps(): Caps = inner.caps().copy(profileTokPerSec = profile)
}

class ProfileSchedulingTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun stop() = scope.cancel()

    // ---- the scheduler's score ----------------------------------------------------------------------------------------

    @Test fun aPhoneWithoutALiveSpeedIsScoredByItsProfileSpeedAndOnlyWithoutThatByTheGuess() {
        assertEquals(12.0, Scheduler.score(caps(tps = null).copy(profileTokPerSec = 12.0)), 1e-9)
        assertEquals(Scheduler.UNKNOWN_TOK_PER_SEC, Scheduler.score(caps(tps = null)), 1e-9)
        assertEquals("a profile speed of zero is no speed", Scheduler.UNKNOWN_TOK_PER_SEC, Scheduler.score(caps(tps = null).copy(profileTokPerSec = 0.0)), 1e-9)
    }

    @Test fun aLiveSpeedBeatsTheProfileSpeedAndQueuedJobsStillDivideIt() {
        assertEquals(30.0, Scheduler.score(caps(tps = 30.0).copy(profileTokPerSec = 12.0)), 1e-9)
        assertEquals(6.0, Scheduler.score(caps(tps = null, queued = 1).copy(profileTokPerSec = 12.0)), 1e-9)
    }

    @Test fun afterARestartAPhoneKeepsItsRankAgainstAPhoneThatHasNoFigure() {
        val restartedFast = caps(tps = null).copy(profileTokPerSec = 13.5)
        val neverSeen = caps(tps = null)
        assertTrue(Scheduler.score(restartedFast) > Scheduler.score(neverSeen))
    }

    // ---- the time budget per token ------------------------------------------------------------------------------------

    @Test fun theBudgetPerTokenIsTwoAndAHalfTimesTheMeasuredTimeWithAFloorOfTwoHundred() {
        assertEquals(200L, GenBudget.msPerToken(null))
        assertEquals(200L, GenBudget.msPerToken(0.0))
        assertEquals("the iQOO 15's measured 4B decode", 200L, GenBudget.msPerToken(13.5))
        assertEquals(200L, GenBudget.msPerToken(12.5))
        assertEquals(209L, GenBudget.msPerToken(12.0))
        assertEquals(500L, GenBudget.msPerToken(5.0))
        assertEquals("a nonsense speed is capped", 5_000L, GenBudget.msPerToken(0.01))
        assertEquals(125L, GenBudget.msPerToken(20.0, floor = 0))
        assertEquals(10L, GenBudget.msPerToken(null, floor = 10))
    }

    @Test fun anAttemptThatMayLandOnSeveralPhonesGetsTheSlowestOnesBudget() {
        val fast = caps().copy(profileTokPerSec = 20.0)
        val slow = caps().copy(profileTokPerSec = 4.0)
        val unknown = caps()
        assertEquals(625L, GenBudget.slowestMsPerToken(listOf(fast, slow, unknown)))
        assertEquals(200L, GenBudget.slowestMsPerToken(listOf(fast, unknown)))
        assertEquals(200L, GenBudget.slowestMsPerToken(emptyList()))
        assertEquals(7L, GenBudget.slowestMsPerToken(listOf(unknown), floor = 7))
    }

    @Test fun aSlowPhonesProfileSpeedLengthensTheAttemptBudgetSoItsLongAnswerIsNotCutOff() = runBlocking {
        fun slow() = FakePhone("slow", TestLlm(delayMs = 400), scope, tokPerSec = 99.0)
        fun other() = FakePhone("other", TestLlm(), scope, tokPerSec = 1.0)
        fun swarm(vararg phones: PhoneEndpoint) =
            SwarmLlm({ phones.toList() }, scope, attemptTimeoutMs = 100, msPerToken = 0, capsMaxAgeMs = 50, busyBackoffMs = 20, probeEveryMs = 100)
        // base 100 ms and no allowance of its own: a 400 ms reply is too slow and the job moves on
        assertEquals("other", swarm(slow(), other()).chatJob("a", req("x", maxTokens = 1)).nodeId)
        // the same phone, reporting 2 tokens/s: 100 ms + 1 token x 1,250 ms is enough (and nothing was asked first, so the very first job reads the report too)
        assertEquals("slow", swarm(WithProfileSpeed(slow(), 2.0), other()).chatJob("b", req("x", maxTokens = 1)).nodeId)
    }

    // ---- what a phone reports --------------------------------------------------------------------------------------

    private fun node(
        state: DeviceState?, model: String = "M", free: Long? = null, remembered: MutableList<Pair<String, Double>> = mutableListOf(),
    ) = SchedNode(scope, { "n" }, { TestLlm(modelName = model) }, { TestLlm() }, { null }, device = { state }, freeStorageMb = { free }, rememberSpeed = { m, t -> remembered += m to t })

    private val iqoo = DeviceState(
        DeviceFixtures.iqoo15.copy(chatTokPerSec = 13.5, chatTokPerSecModel = "M"),
        ProfilePlan.plan(DeviceFixtures.iqoo15, ModelCatalog.entries),
    )

    @Test fun theCapsCarryWhatTheDeviceCheckFound() {
        val c = node(iqoo, free = 150_000).caps()
        assertEquals("STANDARD", c.deviceClass)
        assertEquals(15_218L, c.totalRamMb)
        assertEquals("GPU", c.backend)
        assertEquals(13.5, c.profileTokPerSec!!, 1e-9)
        assertEquals(20_000L, c.storageOfferMb)
        assertEquals("the live figure, not the one of the last check", 150_000L, c.freeStorageMb)
    }

    @Test fun withoutALiveStorageReadingTheLastChecksFigureIsReportedAndWithoutACheckTheDefaultsStay() {
        assertEquals(200_000L, node(iqoo, free = null).caps().freeStorageMb)
        val none = node(null).caps()
        assertNull(none.deviceClass)
        assertEquals(0L, none.totalRamMb)
        assertNull(none.backend)
        assertNull(none.profileTokPerSec)
        assertEquals(0L, none.storageOfferMb)
        assertEquals(0L, none.freeStorageMb)
    }

    @Test fun aSpeedMeasuredOnAnotherModelIsNotReported() {
        assertNull(node(iqoo, model = "Other-model").caps().profileTokPerSec)
    }

    @Test fun aRealRepliesSpeedIsRememberedButAShortOneOrThePlaceholdersIsNot() {
        val seen = mutableListOf<Pair<String, Double>>()
        val real = node(iqoo, model = "M", remembered = seen)
        real.noteStats(ChatStats(5, 20, 1.0, 1_000.0))
        real.noteStats(ChatStats(5, 15, 1.0, 1_000.0))
        assertEquals(listOf("M" to 20.0), seen)
        assertEquals("the live figure still follows every reply", 15.0, real.lastTokPerSec!!, 1e-9)
        node(iqoo, model = "FAKE", remembered = seen).noteStats(ChatStats(5, 50, 1.0, 1_000.0))
        assertEquals(1, seen.size)
    }

    // ---- the wire --------------------------------------------------------------------------------------------------

    @Test fun aReportFromAnOlderBuildReadsWithTheNewFieldsAtTheirDefaults() {
        val old = """{"nodeId":"a","modelName":"M","capacity":1,"running":0,"queued":0,"lastDecodeTokPerSec":13.5,"thermalStatus":0,"batteryPct":80,"charging":false,"ramAvailMb":4096}"""
        val c = SwarmJson.decodeFromString<Caps>(old)
        assertEquals("a", c.nodeId)
        assertNull(c.deviceClass)
        assertEquals(0L, c.totalRamMb)
        assertNull(c.backend)
        assertNull(c.profileTokPerSec)
        assertEquals(0L, c.storageOfferMb)
        assertEquals(0L, c.freeStorageMb)
    }

    @Test fun anOlderBuildReadsTheNewReportAndIgnoresTheNewKeys() {
        val wire = SwarmJson.encodeToString(Caps.serializer(), node(iqoo, free = 1).caps())
        assertTrue(wire, wire.contains("\"deviceClass\":\"STANDARD\""))
        val seenByOld = SwarmJson.decodeFromString<OldCaps>(wire)
        assertEquals("n", seenByOld.nodeId)
        assertEquals("M", seenByOld.modelName)
    }

    @Test fun theNewFieldsSurviveTheRoundTrip() {
        val c = node(iqoo, free = 150_000).caps()
        assertEquals(c, SwarmJson.decodeFromString<Caps>(SwarmJson.encodeToString(Caps.serializer(), c)))
    }
}
