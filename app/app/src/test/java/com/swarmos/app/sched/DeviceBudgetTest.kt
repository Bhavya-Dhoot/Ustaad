package com.swarmos.app.sched

import com.swarmos.app.core.NodeInfo
import com.swarmos.app.core.SwarmJson
import com.swarmos.app.link.NodeClient
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import java.net.ServerSocket

/** Each attempt, and the HTTP request behind it, gets the time budget of the device it goes to. */
class DeviceBudgetTest {
    companion object {
        @JvmStatic @BeforeClass fun quietLogging() {
            runCatching { org.slf4j.LoggerFactory.getLogger("sched-budget-test") }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val servers = mutableListOf<EmbeddedServer<*, *>>()

    @After fun tearDown() {
        servers.forEach { it.stop(100, 500) }
        scope.cancel()
    }

    // ---- the allowance per token of one device -------------------------------------------------------------------

    private fun caps(live: Double? = null, profile: Double? = null) =
        Caps("n", "T", 1, 0, 0, live, 0, 80, false, 4096, profileTokPerSec = profile)

    @Test fun aDevicesOwnSpeedSetsItsAllowance() {
        assertEquals("a slow device: 2.5 / 2 tok/s", 1_250L, GenBudget.msPerTokenOf(caps(live = 2.0)))
        assertEquals("the profile speed when nothing was measured this run", 1_250L, GenBudget.msPerTokenOf(caps(profile = 2.0)))
        assertEquals("the slower of the two: a profile slower than the live reading", 1_250L, GenBudget.msPerTokenOf(caps(live = 10.0, profile = 2.0)))
        assertEquals("and a live reading slower than the profile (a hot phone)", 1_250L, GenBudget.msPerTokenOf(caps(live = 2.0, profile = 40.0)))
        assertEquals("both fast", 200L, GenBudget.msPerTokenOf(caps(live = 40.0, profile = 20.0)))
        assertEquals("a fast device stays on the floor", 200L, GenBudget.msPerTokenOf(caps(live = 40.0)))
        assertEquals("unknown: today's 200 ms", 200L, GenBudget.msPerTokenOf(caps()))
        assertEquals("no report at all", 200L, GenBudget.msPerTokenOf(null))
        assertEquals("a speed that is not positive counts as unknown", 200L, GenBudget.msPerTokenOf(caps(live = 0.0)))
    }

    @Test fun theMeasuredPhonesKeepTodaysBudget() {
        // decode speeds recorded in docs/benchmarks (2026-10-10 e2e2 swarm throughput, evening checks, agent endpoint): all at or
        // above 12.5 tokens/s, where the allowance stays on the 200 ms floor, so a request to these phones is budgeted as before
        for (tps in listOf(15.8, 13.8, 13.3, 13.9, 13.6, 13.4, 18.1, 18.3, 12.9, 14.2)) {
            assertEquals("$tps tok/s", 200L, GenBudget.msPerTokenOf(caps(live = tps)))
            assertEquals(GenBudget.forTokens(256), GenBudget.forTokens(256, msPerToken = GenBudget.msPerTokenOf(caps(profile = tps))))
        }
        assertEquals(30_000L + 256 * 200L, GenBudget.forTokens(256, msPerToken = GenBudget.msPerTokenOf(caps())))
    }

    // ---- the scheduler's attempt ---------------------------------------------------------------------------------

    private fun swarm(p: FakePhone) =
        SwarmLlm({ listOf(p) }, scope, attemptTimeoutMs = 10, capsMaxAgeMs = 50, busyBackoffMs = 20, probeEveryMs = 100, capsTimeoutMs = 300)

    /** The attempt budget the scheduler gave a phone that never answers: the figure in the timeout it ends with. */
    private suspend fun attemptBudgetOf(p: FakePhone): Long {
        try {
            swarm(p).chatJob("job", req("x", maxTokens = 1))
            fail("expected a timeout")
        } catch (e: PhoneTimeoutException) {
            assertEquals("the same job id was sent twice, to the same phone, with the same budget", listOf("job", "job"), p.jobIds.toList())
            return e.afterMs
        }
        return -1
    }

    @Test fun aSlowDeviceGetsALongerAttemptThanAFastOneForTheSameRequest() = runBlocking {
        val slow = FakePhone("slow", TestLlm(), scope, tokPerSec = 2.0).also { it.hang = true }
        val fast = FakePhone("fast", TestLlm(), scope, tokPerSec = 40.0).also { it.hang = true }
        val unknown = FakePhone("unknown", TestLlm(), scope).also { it.hang = true }
        val slowMs = attemptBudgetOf(slow)
        val fastMs = attemptBudgetOf(fast)
        val unknownMs = attemptBudgetOf(unknown)
        assertEquals("base 10 ms + 1 token at 1250 ms", 1_260L, slowMs)
        assertEquals("base 10 ms + 1 token at today's 200 ms", 210L, fastMs)
        assertEquals("nothing known: today's figure", 210L, unknownMs)
        assertTrue(slowMs > fastMs)
    }

    // ---- the HTTP request ----------------------------------------------------------------------------------------

    private fun peerReporting(live: Double?, profile: Double?): RemoteLlmClient {
        val port = ServerSocket(0).use { it.localPort }
        val body = SwarmJson.encodeToString(caps(live, profile))
        servers += embeddedServer(CIO, port = port, host = "127.0.0.1") {
            install(ContentNegotiation) { json(SwarmJson) }
            routing { route("/v1/sched") { get("/caps") { call.respondText(body, ContentType.Application.Json) } } }
        }.also { it.start(wait = false) }
        return RemoteLlmClient(NodeInfo("peer", "Phone peer", "x", "127.0.0.1:$port", "t", "T", null), NodeClient({ "secret" }, { "me" }))
    }

    @Test fun theHttpBudgetFollowsTheSpeedTheDeviceReports() = runBlocking {
        val slow = peerReporting(live = 2.0, profile = null)
        val fast = peerReporting(live = 40.0, profile = null)
        val profileOnly = peerReporting(live = null, profile = 2.0)
        val unknown = peerReporting(live = null, profile = null)
        assertEquals("before any report: today's figure", 30_000L + 100 * 200, slow.budgetFor(100))
        listOf(slow, fast, profileOnly, unknown).forEach { it.caps() }
        assertEquals(30_000L + 100 * 1_250, slow.budgetFor(100))
        assertEquals(30_000L + 100 * 1_250, profileOnly.budgetFor(100))
        assertEquals(30_000L + 100 * 200, fast.budgetFor(100))
        assertEquals("unknown speed: today's figure", 30_000L + 100 * 200, unknown.budgetFor(100))
        assertEquals(GenBudget.forTokens(100), unknown.budgetFor(100))
        assertTrue(slow.budgetFor(100) > fast.budgetFor(100))
    }

    @Test fun anExplicitBudgetStillWins() {
        val port = ServerSocket(0).use { it.localPort }
        val c = RemoteLlmClient(NodeInfo("peer", "Phone peer", "x", "127.0.0.1:$port", "t", "T", null), NodeClient({ "secret" }, { "me" }), budgetMs = { 150L })
        assertEquals(150L, c.budgetFor(5_000))
    }
}
