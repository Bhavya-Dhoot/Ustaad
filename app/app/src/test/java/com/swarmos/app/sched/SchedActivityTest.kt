package com.swarmos.app.sched

import com.swarmos.app.core.NodeInfo
import com.swarmos.app.core.PhoneActivity
import com.swarmos.app.core.SwarmJson
import com.swarmos.app.link.NodeClient
import com.swarmos.app.llm.ChatChunk
import com.swarmos.app.llm.ChatRequest
import com.swarmos.app.llm.ChatResult
import com.swarmos.app.llm.LlmClient
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.net.ServerSocket

/** The live state line of the scheduler: shown while jobs run on this phone, gone when they end or fail. */
class SchedActivityTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var server: EmbeddedServer<*, *>? = null

    companion object {
        @JvmStatic @BeforeClass fun quietLogging() {
            runCatching { org.slf4j.LoggerFactory.getLogger("sched-activity-test") }
        }
    }

    @Before fun clean() = PhoneActivity.clearAll()

    @After fun tearDown() {
        server?.stop(100, 500)
        scope.cancel()
        PhoneActivity.clearAll()
    }

    /** Waits (up to 3 s) until the line satisfies [ok]; the server clears it just after it has sent the answer. */
    private suspend fun waitFor(what: String, ok: (String?) -> Boolean) {
        repeat(300) {
            if (ok(PhoneActivity.current.value?.text)) return
            delay(10)
        }
        fail("$what, line is ${PhoneActivity.current.value?.text}")
    }

    private fun serve(node: SchedNode): RemoteLlmClient {
        val port = ServerSocket(0).use { it.localPort }
        server = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            install(ContentNegotiation) { json(SwarmJson) }
            // the test client signs as "me", whom this phone knows as Phone C
            routing { route("/v1/sched") { schedNodeRoutes(node, { "secret" }, peerName = { id -> if (id == "me") "Phone C" else null }) } }
        }.also { it.start(wait = false) }
        return RemoteLlmClient(NodeInfo("peer", "Phone B", "x", "127.0.0.1:$port", "t", "T", null), NodeClient({ "secret" }, { "me" }))
    }

    @Test fun theLinesReadInPlainWordsWithRealNumbers() {
        assertEquals("Running 32 jobs across 2 phones (12 done).", SchedActivity.batch(32, 12, 2))
        assertEquals("Running 32 jobs on this phone (0 done).", SchedActivity.batch(32, 0, 1))
        assertEquals("Running 1 job on this phone (1 done).", SchedActivity.batch(1, 1, 1))
        assertEquals("Running a job for Phone C.", SchedActivity.servingJob("Phone C"))
        assertEquals("Running a job for another phone.", SchedActivity.servingJob(null))
    }

    @Test fun aJobForAnotherPhoneShowsItsNameWhileItRunsAndClearsAfter() = runBlocking {
        val phone = FakePhone("peer", TestLlm(delayMs = 400), scope)
        val remote = serve(phone.node)
        val job = async { remote.runChat("j1", req("hello"), null) }
        waitFor("never showed the job") { it == "Running a job for Phone C." }
        assertEquals("sched", PhoneActivity.current.value?.owner)
        assertEquals("T:hello", job.await().text)
        waitFor("never cleared") { it == null }
    }

    @Test fun theLineStaysUntilTheLastOfSeveralJobsEnds() = runBlocking {
        val first = launch { SchedActivity.whileServing("Phone C") { delay(100) } }
        val second = launch { SchedActivity.whileServing("Phone C") { delay(400) } }
        first.join()
        // the first job has ended, the second is still running: the phone is still busy for Phone C
        assertEquals("Running a job for Phone C.", PhoneActivity.current.value?.text)
        second.join()
        assertNull(PhoneActivity.current.value)
    }

    @Test fun theLineIsGoneAfterTheJobFails() = runBlocking {
        val failing = object : LlmClient {
            override val modelName = "T"
            override suspend fun ready() = true
            override fun chatStream(req: ChatRequest): Flow<ChatChunk> = emptyFlow()
            override suspend fun chat(req: ChatRequest): ChatResult {
                delay(150)
                throw IllegalStateException("model died")
            }
            override suspend fun embed(texts: List<String>): List<FloatArray> = emptyList()
        }
        val node = SchedNode(scope, { "peer" }, { failing }, { failing }, { null }).also { it.capacity = 1 }
        val remote = serve(node)
        val call = async { runCatching { remote.runChat("j", req("x"), null) } }
        waitFor("never showed the job") { it == "Running a job for Phone C." }
        assertTrue("the failure reached the caller", call.await().isFailure)
        waitFor("never cleared") { it == null }
    }

    @Test fun aBatchShowsRealCountsAcrossThePhonesAndClearsWhenDone() = runBlocking {
        val me = FakePhone("me", TestLlm(delayMs = 80), scope, isLocal = true)
        val other = FakePhone("other", TestLlm(delayMs = 80), scope)
        val swarm = SwarmLlm({ listOf(me, other) }, scope, attemptTimeoutMs = 5_000, capsMaxAgeMs = 50, probeEveryMs = 100)
        val store = BatchStore(scope, { swarm }, { swarm })
        val seen = mutableListOf<String>()
        val watcher = launch(Dispatchers.Default) {
            while (true) {
                PhoneActivity.current.value?.text?.let { if (seen.lastOrNull() != it) seen += it }
                delay(5)
            }
        }
        val id = store.start((0 until 6).map { req("p$it") }, BatchStore.MODE_SWARM)
        while (store.status(id)!!.value.state != "done") delay(10)
        waitFor("never cleared") { it == null }
        watcher.cancel()
        val shape = Regex("""Running 6 jobs across 2 phones \(\d done\)\.""")
        assertTrue("every line shown was the batch across both phones: $seen", seen.isNotEmpty() && seen.all { shape.matches(it) })
        assertTrue("the count moved while the batch ran: $seen", seen.size >= 2)
        assertNull(PhoneActivity.current.value)
    }

    @Test fun whileServingClearsOnACancel() = runBlocking {
        val job = launch { SchedActivity.whileServing("Phone C") { delay(60_000) } }
        waitFor("never showed the job") { it == "Running a job for Phone C." }
        job.cancel()
        job.join()
        assertNull(PhoneActivity.current.value)
    }
}
