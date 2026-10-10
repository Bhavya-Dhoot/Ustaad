package com.swarmos.app.sched

import com.swarmos.app.busy.BusyReport
import com.swarmos.app.core.SwarmJson
import com.swarmos.app.link.NodeClient
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
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
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.net.ServerSocket

/** GET /v1/sched/busy over loopback HTTP, next to the other scheduler routes. */
class BusyRouteTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var server: EmbeddedServer<*, *>
    private var port = 0
    @Volatile private var report = BusyReport(true, "running", 12, 3, 1, 540, 38.5, 0.62)

    companion object {
        @JvmStatic @BeforeClass fun quietLogging() {
            runCatching { org.slf4j.LoggerFactory.getLogger("busy-route-test") }
        }
    }

    @Before fun setUp() {
        port = ServerSocket(0).use { it.localPort }
        val phone = FakePhone("peer", TestLlm(), scope)
        server = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            install(ContentNegotiation) { json(SwarmJson) }
            routing { route("/v1/sched") { schedNodeRoutes(phone.node, { "secret" }, busy = { report }) } }
        }.also { it.start(wait = false) }
    }

    @After fun tearDown() {
        server.stop(100, 500)
        scope.cancel()
    }

    @Test fun theBusyRouteAnswersTheSwitchTheStateAndTheCounters() = runBlocking {
        val got = NodeClient({ "secret" }, { "me" }).get<BusyReport>("127.0.0.1:$port", "/sched/busy")
        assertEquals(report, got)
        assertEquals("running", got.state)
        assertTrue(got.enabled)
        assertEquals(0.62, got.share60s, 1e-9)
    }

    @Test fun theOldCapsRouteIsUnchanged() = runBlocking {
        val caps = NodeClient({ "secret" }, { "me" }).get<Caps>("127.0.0.1:$port", "/sched/caps")
        assertEquals("peer", caps.nodeId)
        assertEquals(0, caps.running)
    }
}
