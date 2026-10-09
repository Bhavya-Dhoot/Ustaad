package com.swarmos.app.sched

import com.swarmos.app.core.NodeInfo
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
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.net.ServerSocket

/** The real routes and RemoteLlmClient over loopback HTTP (no signature check; that belongs to NodeServer). */
class SchedRoutesTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var server: EmbeddedServer<*, *>
    private lateinit var phone: FakePhone
    private lateinit var remote: RemoteLlmClient
    private var port = 0

    companion object {
        /** The app bundles an Android slf4j binding that cannot start on the JVM; trigger and swallow its failure once. */
        @JvmStatic @BeforeClass fun quietLogging() {
            runCatching { org.slf4j.LoggerFactory.getLogger("sched-test") }
        }
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }

    private fun client(port: Int) = RemoteLlmClient(
        NodeInfo("peer", "Phone B", "x", "127.0.0.1:$port", "t", "T", null),
        NodeClient({ "secret" }, { "me" }),
    )

    @Before fun setUp() {
        port = freePort()
        phone = FakePhone("peer", TestLlm(delayMs = 200), scope)
        server = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            install(ContentNegotiation) { json(SwarmJson) }
            routing { route("/v1/sched") { schedNodeRoutes(phone.node) } }
        }.also { it.start(wait = false) }
        remote = client(port)
    }

    @After fun tearDown() {
        server.stop(100, 500)
        scope.cancel()
    }

    @Test fun capsComeBackAsJson() = runBlocking {
        val c = remote.caps()
        assertEquals("peer", c.nodeId)
        assertEquals("T", c.modelName)
        assertEquals(1, c.capacity)
        assertEquals(0, c.running)
    }

    @Test fun chatRoundTripAndRepeatedJobIdRunsOnce() = runBlocking {
        val a = remote.runChat("job-1", req("hello"), null)
        val b = remote.runChat("job-1", req("hello"), null)
        assertEquals("T:hello", a.text)
        assertEquals(10, a.stats.completionTokens)
        assertEquals("peer", a.nodeId)
        assertEquals(a, b)
        assertEquals(1, phone.llm.executed.size)
    }

    @Test fun aFullPhoneAnswers429WhichBecomesBusyException() = runBlocking {
        val first = async { remote.runChat("a", req("one"), null) }
        while (phone.node.chatExec.running == 0) delay(5)
        try {
            remote.runChat("b", req("two"), null)
            fail("expected BusyException")
        } catch (e: BusyException) {
            assertEquals(1, e.running)
            assertEquals(1, e.capacity)
            assertEquals(0, e.queued)
        }
        assertEquals("T:one", first.await().text)
    }

    @Test fun aPinnedModelThePhoneDoesNotRunAnswers409() = runBlocking {
        try {
            remote.runChat("a", req("one"), "other")
            fail("expected ModelMismatchException")
        } catch (e: ModelMismatchException) {
            assertEquals("T", e.model)
        }
    }

    @Test fun embedRoundTrip() = runBlocking {
        val v = remote.embed(listOf("abc", "de"))
        assertEquals(listOf(3f, 2f), v.map { it[0] })
    }

    @Test fun malformedBodyIsA400NotACrash() = runBlocking {
        val http = NodeClient({ "secret" }, { "me" })
        try {
            http.post<ChatJobResult, EmbedJobRequest>("127.0.0.1:$port", "/sched/chat", EmbedJobRequest("x", listOf("y")))
            fail("expected an HTTP error")
        } catch (e: com.swarmos.app.link.NodeHttpException) {
            assertEquals(400, e.status)
        }
    }

    @Test fun swarmFallsBackToThisPhoneWhenAPeerIsUnreachable() = runBlocking {
        val me = FakePhone("me", TestLlm(delayMs = 20), scope, isLocal = true)
        val unreachable = client(freePort())               // nothing listens there: connection refused
        val llm = SwarmLlm({ listOf(unreachable, me) }, scope, attemptTimeoutMs = 3_000, capsMaxAgeMs = 50, probeEveryMs = 100)
        val r = llm.chatJob("j", req("x"))
        assertEquals("me", r.nodeId)
        assertEquals("T:x", r.text)
    }

    @Test fun swarmSpreadsJobsOverThisPhoneAndAPeerOverHttp() = runBlocking {
        val me = FakePhone("me", TestLlm(delayMs = 150), scope, isLocal = true)
        val llm = SwarmLlm({ listOf(me, remote) }, scope, attemptTimeoutMs = 5_000, capsMaxAgeMs = 50, probeEveryMs = 100)
        val report = BatchRunner(llm).run("t", "swarm", (0 until 8).map { req("p$it") })
        assertEquals(0, report.failed)
        assertEquals((0 until 8).toList(), report.items.map { it.index })
        val nodes = report.perPhone.associate { it.nodeId to it.jobs }
        assertTrue("both phones took jobs: $nodes", nodes["me"]!! > 0 && nodes["peer"]!! > 0)
        assertEquals(8, nodes.values.sum())
        assertEquals(8, me.llm.executed.size + phone.llm.executed.size)
    }
}
