package com.swarmos.app.sched

import com.swarmos.app.core.NodeInfo
import com.swarmos.app.core.SwarmJson
import com.swarmos.app.link.NodeClient
import com.swarmos.app.link.NodeHttpException
import com.swarmos.app.llm.ChatChunk
import com.swarmos.app.llm.ChatRequest
import io.ktor.http.HttpMethod
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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/** Timeouts, wake-ups, stream breaks, thermal steering, re-sending a job to the phone that may have finished it. */
class SwarmHardeningTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        /** The app bundles an Android slf4j binding that cannot start on the JVM; trigger and swallow its failure once. */
        @JvmStatic @BeforeClass fun quietLogging() {
            runCatching { org.slf4j.LoggerFactory.getLogger("swarm-hardening-test") }
        }
    }

    @After fun tearDown() = scope.cancel()

    private fun phone(id: String, llm: TestLlm = TestLlm(), capacity: Int = 1, local: Boolean = false, tps: Double? = null, thermal: Int = 0) =
        FakePhone(id, llm, scope, capacity, local, tps, thermal)

    private fun swarm(phones: List<PhoneEndpoint>, attemptTimeoutMs: Long = 5_000, msPerToken: Long = 0, heartbeatMs: Long = 1_000) =
        SwarmLlm(
            { phones }, scope, attemptTimeoutMs = attemptTimeoutMs, msPerToken = msPerToken, capsMaxAgeMs = 50,
            busyBackoffMs = 20, probeEveryMs = 100, heartbeatMs = heartbeatMs, capsTimeoutMs = 300,
        )

    // ---- items 1 and 9: the time a job may take scales with the reply length --------------------------------------

    @Test fun theGenerationBudgetIsThirtySecondsPlusFiveTokensAPerSecond() {
        assertEquals(30_000L, GenBudget.forTokens(0))
        assertEquals(30_000L + 1024 * 200, GenBudget.forTokens(1024))
        assertTrue("a 1024-token answer at the measured hot speed of 6.8 tokens/s (about 150 s) fits", GenBudget.forTokens(1024) > 150_000)
        assertEquals(30_000L, GenBudget.forTokens(-5))
    }

    @Test fun anAttemptGetsTimePerRequestedTokenSoALongAnswerIsNotCutOff() = runBlocking {
        val slow = phone("slow", TestLlm(delayMs = 500), tps = 99.0)
        val other = phone("other", tps = 1.0)
        // base 100 ms + 10 ms a token: a 100-token reply may take 1.1 s, a 0-token one 100 ms
        val llm = swarm(listOf(slow, other), attemptTimeoutMs = 100, msPerToken = 10)
        val r = llm.chatJob("long", req("hello", maxTokens = 100))
        assertEquals("the slow phone had time to finish", "slow", r.nodeId)
        assertEquals(listOf("long"), slow.jobIds.toList())
        assertTrue("and nothing was re-run elsewhere", other.jobIds.isEmpty())
        val cut = llm.chatJob("short", req("hello", maxTokens = 0))
        assertEquals("with no allowance per token the same phone is too slow and the job moves on", "other", cut.nodeId)
    }

    @Test fun aSlowJobDoesNotMarkThePhoneDown() = runBlocking {
        val slow = phone("slow", TestLlm(delayMs = 400), tps = 99.0)
        val other = phone("other", tps = 1.0)
        val llm = swarm(listOf(slow, other), attemptTimeoutMs = 100)
        llm.chatJob("a", req("x"))
        assertTrue("only pings decide that a phone is down", llm.phoneViews().first { it.nodeId == "slow" }.up)
    }

    @Test fun aRequestThatRunsOutOfTimeIsAPhoneTimeoutNotALostConnection() {
        val port = ServerSocket(0).use { it.localPort }
        val phone = phone("peer", TestLlm(delayMs = 700))
        // started outside runBlocking: a server started inside it would keep runBlocking from returning
        val server = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            install(ContentNegotiation) { json(SwarmJson) }
            routing { route("/v1/sched") { schedNodeRoutes(phone.node, { "secret" }) } }
        }.also { it.start(wait = false) }
        try {
            val remote = RemoteLlmClient(
                NodeInfo("peer", "Phone B", "x", "127.0.0.1:$port", "t", "T", null),
                NodeClient({ "secret" }, { "me" }),
                budgetMs = { 150L },
            )
            runBlocking {
                try {
                    remote.runChat("j", req("x"), null)
                    fail("expected PhoneTimeoutException")
                } catch (e: PhoneTimeoutException) {
                    assertEquals("peer", e.nodeId)
                    assertEquals(150L, e.afterMs)
                }
            }
        } finally {
            server.stop(100, 500)
        }
    }

    @Test fun aCallThatCannotGetASlotGivesUpAfterTwiceItsBudget() = runBlocking {
        val me = phone("me", TestLlm(delayMs = 3_000), local = true)
        val llm = swarm(listOf(me), attemptTimeoutMs = 200)
        val hold = async { me.node.chat("hold", req("hold")) }
        while (me.node.chatExec.running == 0) delay(2)
        val t0 = System.nanoTime()
        try {
            llm.chatJob("late", req("x"))
            fail("expected SwarmWaitTimeoutException")
        } catch (e: SwarmWaitTimeoutException) {
            assertTrue(e.message!!, e.message!!.contains("free slot"))
        }
        val waitedMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue("gave up after about 400 ms, not at once and not never ($waitedMs ms)", waitedMs in 350..2_500)
        hold.cancel()
    }

    // ---- item 2: waiting dispatchers do not wake each other ------------------------------------------------------

    @Test fun dispatchersWaitingForASlotDoNotSpin() = runBlocking {
        val me = phone("me", TestLlm(delayMs = 1_200), local = true)
        val llm = swarm(listOf(me))
        val hold = async { me.node.chat("hold", req("hold")) }
        while (me.node.chatExec.running == 0) delay(2)
        val waiting = (0 until 3).map { i -> async { llm.chatJob("w$i", req("w$i")) } }
        delay(100)
        val before = me.capsCalls.get()
        delay(600)
        val during = me.capsCalls.get() - before
        assertTrue("three waiting dispatchers asked the phone for its report $during times in 600 ms", during < 150)
        hold.await()
        assertEquals(3, waiting.awaitAll().size)
    }

    // ---- item 3: a slow ping does not stall dispatch ------------------------------------------------------------

    @Test fun aPhoneThatAnswersItsPingSlowlyDoesNotHoldUpDispatch() = runBlocking {
        val me = phone("me", TestLlm(delayMs = 5), local = true, tps = 50.0)
        val slowPing = phone("far", TestLlm(delayMs = 5), tps = 1.0)
        val llm = SwarmLlm({ listOf(me, slowPing) }, scope, capsMaxAgeMs = 50, capsTimeoutMs = 1_500, probeEveryMs = 100, msPerToken = 0)
        llm.chatJob("warm", req("x"))      // reports are cached now
        slowPing.capsDelayMs = 4_000
        delay(120)                         // every cached report is stale
        val t0 = System.nanoTime()
        repeat(3) { llm.chatJob("j$it", req("x")) }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("three dispatches took $ms ms: they used the cached reports instead of waiting for the slow ping", ms < 1_000)
    }

    // ---- item 4: a stream that already delivered tokens is not restarted elsewhere -----------------------------

    /** A phone whose stream sends "par" and then does [failure]. */
    private class BreakingStreamer(override val nodeId: String, scope: CoroutineScope, private val failure: suspend (BreakingStreamer) -> Unit) : PhoneEndpoint {
        private val llm = TestLlm()
        val node = SchedNode(scope, { nodeId }, { llm }, { llm }, { null })
        @Volatile var capsDown = false
        val streams = AtomicInteger()

        override suspend fun caps(): Caps {
            if (capsDown) throw IOException("$nodeId stopped answering")
            return node.caps().copy(lastDecodeTokPerSec = 99.0)
        }

        override suspend fun runChat(jobId: String, req: ChatRequest, pinModel: String?) = node.chat(jobId, req, pinModel)
        override suspend fun runEmbed(jobId: String, texts: List<String>) = node.embed(jobId, texts)

        override suspend fun streamIfFree(req: ChatRequest, pinModel: String?, out: FlowCollector<ChatChunk>): Boolean {
            streams.incrementAndGet()
            out.emit(ChatChunk("par"))
            failure(this)
            return true
        }
    }

    private suspend fun collectUntilItBreaks(llm: SwarmLlm): Pair<String, Throwable?> {
        val got = StringBuilder()
        var error: Throwable? = null
        try {
            llm.chatStream(req("hi")).collect { c -> got.append(c.delta) }
        } catch (e: Exception) {
            error = e
        }
        return got.toString() to error
    }

    @Test fun aStreamThatBreaksAfterTokensSurfacesTheBreakAndIsNotRestarted() = runBlocking {
        val breaker = BreakingStreamer("breaker", scope) { throw IOException("connection reset") }
        val ok = phone("ok", tps = 1.0)
        val (text, error) = collectUntilItBreaks(swarm(listOf(breaker, ok)))
        assertEquals("only what the first phone sent", "par", text)
        assertTrue("the break is surfaced: $error", error is IOException)
        assertTrue("the other phone was not asked to start the reply again", ok.jobIds.isEmpty())
        assertEquals(1, breaker.streams.get())
    }

    @Test fun aStreamWhosePhoneStopsAnsweringPingsAfterTokensIsNotRestartedEither() = runBlocking {
        val breaker = BreakingStreamer("breaker", scope) { it.capsDown = true; awaitCancellation() }
        val ok = phone("ok", tps = 1.0)
        val (text, error) = withTimeout(10_000) { collectUntilItBreaks(swarm(listOf(breaker, ok), heartbeatMs = 80)) }
        assertEquals("par", text)
        assertTrue("the abort is surfaced: $error", error is PhoneAbortedException)
        assertTrue("the other phone never started the reply", ok.jobIds.isEmpty())
    }

    @Test fun aStreamThatFailsBeforeAnyTokenStillMovesToAnotherPhone() = runBlocking {
        val early = object : PhoneEndpoint {
            override val nodeId = "early"
            val node = SchedNode(scope, { nodeId }, { TestLlm() }, { TestLlm() }, { null })
            override suspend fun caps() = node.caps().copy(lastDecodeTokPerSec = 99.0)
            override suspend fun runChat(jobId: String, req: ChatRequest, pinModel: String?): ChatJobResult = throw IOException("refused")
            override suspend fun runEmbed(jobId: String, texts: List<String>) = node.embed(jobId, texts)
            override suspend fun streamIfFree(req: ChatRequest, pinModel: String?, out: FlowCollector<ChatChunk>): Boolean = throw IOException("refused")
        }
        val ok = phone("ok", tps = 1.0)
        val (text, error) = collectUntilItBreaks(swarm(listOf(early, ok)))
        assertNull(error)
        assertEquals("T:hi", text)
    }

    // ---- items 5 and 14: thermal steering ------------------------------------------------------------------------

    @Test fun aLocalStreamAtStatusFourGoesToAnotherPhoneThatCanTakeIt() = runBlocking {
        val me = phone("me", local = true, thermal = 4)
        val other = phone("other", tps = 1.0)
        val (text, error) = collectUntilItBreaks(swarm(listOf(me, other)))
        assertNull(error)
        assertEquals("T:hi", text)
        assertEquals("this phone did not stream", 0, me.llm.streamCalls.get())
        assertEquals(listOf(1), listOf(other.jobIds.size))
    }

    @Test fun aLocalStreamAtStatusFourStillRunsWhenNoOtherPhoneIsEligible() = runBlocking {
        val me = phone("me", local = true, thermal = 4)
        val other = phone("other", tps = 1.0).also { it.dead = true }
        val (text, error) = collectUntilItBreaks(swarm(listOf(me, other)))
        assertNull(error)
        assertEquals("stream:hi", text)
        assertEquals(1, me.llm.streamCalls.get())
    }

    @Test fun aLocalStreamAtStatusThreeIsUntouched() = runBlocking {
        val me = phone("me", local = true, thermal = 3)
        val other = phone("other", tps = 99.0)
        val (text, _) = collectUntilItBreaks(swarm(listOf(me, other)))
        assertEquals("stream:hi", text)
        assertEquals(1, me.llm.streamCalls.get())
    }

    // ---- item 10: a lost reply is asked for again from the same phone before another one runs the job -------------

    @Test fun aLostReplyIsAskedForAgainFromThePhoneThatFinishedAndNobodyElseRunsTheJob() = runBlocking {
        val first = phone("first", tps = 99.0).also { it.loseReplyOnce = true }
        val second = phone("second", tps = 1.0)
        val llm = swarm(listOf(first, second))
        val r = llm.chatJob("job", req("hello"))
        assertEquals("first", r.nodeId)
        assertEquals("T:hello", r.text)
        assertEquals("the model ran the job once", 1, first.llm.executed.size)
        assertTrue("the second phone was not asked", second.jobIds.isEmpty())
        assertEquals(listOf("job", "job"), first.jobIds.toList())
    }

    @Test fun aPhoneThatStoppedAnsweringPingsGetsNoSecondTry() = runBlocking {
        val calls = AtomicInteger()
        val first = object : PhoneEndpoint {
            override val nodeId = "first"
            val node = SchedNode(scope, { nodeId }, { TestLlm() }, { TestLlm() }, { null })
            @Volatile var silent = false
            override suspend fun caps(): Caps {
                if (silent) throw IOException("silent")
                return node.caps().copy(lastDecodeTokPerSec = 99.0)
            }

            override suspend fun runChat(jobId: String, req: ChatRequest, pinModel: String?): ChatJobResult {
                calls.incrementAndGet()
                silent = true                      // the connection drops and the phone stops answering its pings
                throw IOException("connection lost")
            }

            override suspend fun runEmbed(jobId: String, texts: List<String>) = node.embed(jobId, texts)
        }
        val second = phone("second", tps = 1.0)
        val r = swarm(listOf(first, second)).chatJob("job", req("hello"))
        assertEquals("second", r.nodeId)
        assertEquals("one try on the silent phone, none after it stopped answering", 1, calls.get())
    }
}

/** A phone that is reachable over Bluetooth only (address "bt:<node id>") is a control-path peer: no model jobs, no streams. */
class BluetoothOnlyTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        @JvmStatic @BeforeClass fun quietLogging() {
            runCatching { org.slf4j.LoggerFactory.getLogger("bt-only-test") }
        }
    }

    @After fun tearDown() = scope.cancel()

    private class BtOnly(val inner: FakePhone) : PhoneEndpoint by inner {
        override val unusableReason: String? = BLUETOOTH_ONLY
    }

    private fun remote(address: String) = RemoteLlmClient(
        NodeInfo("peer", "Phone B", "x", "10.0.0.2", "t", "T", null),
        NodeClient({ "secret" }, { "me" }),
        address = { address },
    )

    @Test fun aBluetoothAddressIsRefusedForEveryKindOfModelWork() = runBlocking {
        val bt = remote("bt:abcd1234")
        assertEquals(BLUETOOTH_ONLY, bt.unusableReason)
        assertNull("a Wi-Fi address is fine", remote("192.168.242.23").unusableReason)
        assertNull(remote("192.168.242.23:8787").unusableReason)
        for (call in listOf<suspend () -> Unit>(
            { bt.runChat("j", req("x"), null) },
            { bt.runEmbed("j", listOf("x")) },
            { bt.streamIfFree(req("x"), null, FlowCollector { }) },
        )) {
            try {
                call()
                fail("expected PhoneUnusableException")
            } catch (e: PhoneUnusableException) {
                assertTrue(e.message!!, e.message!!.contains("Bluetooth"))
            }
        }
    }

    @Test fun theStreamClientBuildsNoUrlFromABluetoothAddress() = runBlocking {
        val sc = StreamClient({ "secret" }, { "me" })
        try {
            sc.post("bt:abcd1234", "/sched/chat-stream", "{}").collect { }
            fail("expected PhoneUnusableException")
        } catch (e: PhoneUnusableException) {
            assertEquals(BLUETOOTH_ONLY, e.message)
        }
    }

    @Test fun theSchedulerSendsNothingToItAndSaysSoInThePhoneList() = runBlocking {
        val bt = FakePhone("bt", TestLlm(), scope, tokPerSec = 99.0)
        val ok = FakePhone("ok", TestLlm(), scope, tokPerSec = 1.0)
        val llm = SwarmLlm({ listOf(BtOnly(bt), ok) }, scope, capsMaxAgeMs = 50, probeEveryMs = 100, msPerToken = 0)
        assertEquals("ok", llm.chatJob("a", req("x")).nodeId)
        assertTrue("nothing was sent to the Bluetooth phone", bt.jobIds.isEmpty())
        val views = llm.phoneViews().associateBy { it.nodeId }
        assertEquals(false, views.getValue("bt").up)
        assertEquals(BLUETOOTH_ONLY, views.getValue("bt").note)
        assertEquals(true, views.getValue("ok").up)
        assertNull(views.getValue("ok").note)
        assertEquals("its slots are not counted", 1, llm.slotCount())
        assertEquals("a stream goes to the Wi-Fi phone too", "T:hi", llm.chatStream(req("hi")).toList().filter { !it.done }.joinToString("") { it.delta })
        assertTrue(bt.jobIds.isEmpty())
    }

    @Test fun whenItIsTheOnlyPhoneTheCallerGetsAPlainNoPhoneError() = runBlocking {
        val bt = FakePhone("bt", TestLlm(), scope)
        val llm = SwarmLlm({ listOf(BtOnly(bt)) }, scope, capsMaxAgeMs = 50, msPerToken = 0)
        try {
            llm.chatJob("a", req("x"))
            fail("expected NoPhoneAvailableException")
        } catch (e: NoPhoneAvailableException) {
            assertTrue(e.message!!, e.message!!.contains("no phone"))
        }
        assertTrue(bt.jobIds.isEmpty())
    }
}

class ThermalGateTest {
    @Test fun aHotterOrEqualReadingIsTakenAtOnce() {
        val g = ThermalGate()
        assertEquals(1, g.update(1, 0))
        assertEquals(3, g.update(3, 100))
        assertEquals(4, g.update(4, 200))
        assertEquals(4, g.update(4, 300))
    }

    @Test fun belowTheExclusionLineTheReadingIsTheLevel() {
        val g = ThermalGate()
        g.update(2, 0)
        assertEquals(1, g.update(1, 10))
        assertEquals(0, g.update(0, 20))
    }

    @Test fun aPhoneExcludedAtFourComesBackToThreeAfterTwentySecondsOfReadingThreeOrLess() {
        val g = ThermalGate()
        g.update(4, 0)
        assertEquals(4, g.update(3, 1_000))
        assertEquals(4, g.update(3, 20_999))
        assertEquals(3, g.update(3, 21_000))
    }

    @Test fun thenAnotherTwentySecondsOfTwoOrLessAreNeededToLeaveStatusThree() {
        val g = ThermalGate()
        g.update(4, 0)
        g.update(3, 1_000)
        g.update(3, 21_000)                       // level 3
        assertEquals(3, g.update(2, 22_000))
        assertEquals(3, g.update(2, 41_999))
        assertEquals(2, g.update(2, 42_000))
    }

    @Test fun aHotterReadingInsideTheWindowStartsTheTwentySecondsAgain() {
        val g = ThermalGate()
        g.update(3, 0)
        g.update(2, 5_000)
        assertEquals("back to 3 at once", 3, g.update(3, 15_000))
        assertEquals(3, g.update(2, 20_000))      // a new window starts here
        assertEquals(3, g.update(2, 39_999))
        assertEquals(2, g.update(2, 40_000))
    }

    @Test fun theLevelAfterAWindowIsTheHighestReadingSeenInIt() {
        val g = ThermalGate()
        g.update(4, 0)
        g.update(3, 1_000)
        g.update(0, 15_000)
        assertEquals("a reading of 3 in the window keeps the phone at 3", 3, g.update(0, 21_000))
    }
}

/** POST /batch admission, the batch store (eviction, the file written as items finish, interrupted batches) and the worker count. */
class BatchHardeningTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val servers = mutableListOf<EmbeddedServer<*, *>>()
    private val dirs = mutableListOf<File>()

    companion object {
        @JvmStatic @BeforeClass fun quietLogging() {
            runCatching { org.slf4j.LoggerFactory.getLogger("batch-hardening-test") }
        }
    }

    @After fun tearDown() {
        servers.forEach { it.stop(100, 500) }
        scope.cancel()
        dirs.forEach { it.deleteRecursively() }
    }

    private fun tempDir(): File = Files.createTempDirectory("batches").toFile().also { dirs += it }

    private fun phone(id: String, delayMs: Long = 20, capacity: Int = 1, local: Boolean = false) =
        FakePhone(id, TestLlm(delayMs = delayMs), scope, capacity, local)

    private fun swarm(vararg phones: FakePhone) =
        SwarmLlm({ phones.toList() }, scope, capsMaxAgeMs = 50, busyBackoffMs = 20, probeEveryMs = 100, msPerToken = 0)

    private suspend fun until(ms: Long = 5_000, check: () -> Boolean) = withTimeout(ms) { while (!check()) delay(5) }

    // ---- item 6 -------------------------------------------------------------------------------------------------

    @Test fun postBatchAnswers409WhileOneIsRunningAndAcceptsAgainWhenItIsDone() {
        val me = phone("me", delayMs = 60, local = true)
        val llm = swarm(me)
        val store = BatchStore(scope, { llm }, { llm })
        // the server is started outside runBlocking: started inside, it becomes a child of the test coroutine and runBlocking never returns
        val port = ServerSocket(0).use { it.localPort }
        servers += embeddedServer(CIO, port = port, host = "127.0.0.1") {
            install(ContentNegotiation) { json(SwarmJson) }
            routing { route("/v1/sched") { schedBatchRoutes(store) } }
        }.also { it.start(wait = false) }
        val client = NodeClient({ "secret" }, { "peer" })
        val host = "127.0.0.1:$port"
        val body = SwarmJson.encodeToString(BatchStartRequest(requests = (0 until 6).map { req("p$it").toDto() }, mode = BatchStore.MODE_LOCAL))
        runBlocking {
            suspend fun post() = client.raw(HttpMethod.Post, host, "/sched/batch", body)
            val first = SwarmJson.decodeFromString<BatchStarted>(post())
            try {
                post()
                fail("expected 409 while the first batch runs")
            } catch (e: NodeHttpException) {
                assertEquals(409, e.status)
                assertTrue(e.body, e.body.contains("already running"))
            }
            until { store.status(first.id)?.value?.state == "done" }
            val second = SwarmJson.decodeFromString<BatchStarted>(post())
            assertTrue(second.id != first.id)
            until { store.status(second.id)?.value?.state == "done" }
        }
    }

    @Test fun tryStartIsOneStepSoTwoCallersCannotBothStart() = runBlocking {
        val llm = swarm(phone("me", delayMs = 80, local = true))
        val store = BatchStore(scope, { llm }, { llm })
        val ids = (0 until 6).map { async(Dispatchers.Default) { store.tryStart((0 until 3).map { req("p$it") }, BatchStore.MODE_LOCAL) } }.awaitAll()
        assertEquals("exactly one caller started a batch", 1, ids.count { it != null })
        until { !store.isRunning() }
    }

    // ---- item 11 ------------------------------------------------------------------------------------------------

    @Test fun startingMoreBatchesNeverForgetsOneThatIsStillRunning() = runBlocking {
        val slow = phone("slow", delayMs = 500, local = true)
        val fast = phone("fast", delayMs = 5, local = true)
        val store = BatchStore(scope, { swarm(slow) }, { swarm(fast) })
        val running = store.start((0 until 4).map { req("s$it") }, BatchStore.MODE_SWARM)
        val finished = mutableListOf<String>()
        repeat(9) {
            val id = store.start(listOf(req("f$it")), BatchStore.MODE_LOCAL)
            until { store.status(id)?.value?.state == "done" }
            finished += id
        }
        assertEquals("the running batch is still known", "running", store.status(running)?.value?.state)
        assertNull("the oldest finished batch was forgotten instead", store.status(finished.first()))
        assertNotNull(store.status(finished.last()))
        until(10_000) { store.status(running)?.value?.state == "done" }
        assertEquals(4, store.status(running)!!.value.done)
    }

    // ---- item 12 ------------------------------------------------------------------------------------------------

    @Test fun aFinishedBatchIsWrittenToDiskAndAnswersAfterARestart() = runBlocking {
        val dir = tempDir()
        val llm = swarm(phone("me", local = true))
        val first = BatchStore(scope, { llm }, { llm }, dir = { dir })
        val id = first.start((0 until 4).map { req("p$it") }, BatchStore.MODE_LOCAL)
        until { first.status(id)?.value?.state == "done" }
        until { File(dir, "batch-$id.json").isFile }
        val reborn = BatchStore(scope, { llm }, { llm }, dir = { dir })      // the app was restarted: nothing in memory
        val report = reborn.status(id)!!.value
        assertEquals("done", report.state)
        assertEquals(4, report.done)
        assertEquals((0 until 4).toList(), report.items.map { it.index })
    }

    @Test fun aBatchThatWasRunningWhenTheAppDiedAnswersInterruptedWithWhatFinished() = runBlocking {
        val dir = tempDir()
        val partial = BatchRunner.buildReport(
            "dead1", "swarm", "running", 10,
            (0 until 3).map { BatchItem(it, "dead1-$it", "me", "T", "answer $it", 5, 9, 100.0) }, 1_500.0,
        )
        dir.mkdirs()
        File(dir, "batch-dead1.json").writeText(SwarmJson.encodeToString(partial))
        val llm = swarm(phone("me", local = true))
        val store = BatchStore(scope, { llm }, { llm }, dir = { dir })
        val report = store.status("dead1")!!.value
        assertEquals("interrupted", report.state)
        assertEquals(3, report.done)
        assertEquals(10, report.total)
        assertEquals(listOf("answer 0", "answer 1", "answer 2"), report.items.map { it.text })
    }

    @Test fun theWrittenReportIsKeptCurrentWhileTheBatchRunsAndTheIdIsCheckedBeforeItBecomesAFileName() = runBlocking {
        val dir = tempDir()
        val llm = swarm(phone("me", delayMs = 250, local = true))
        var now = 10_000L
        val store = BatchStore(scope, { llm }, { llm }, dir = { dir }, clock = { now })
        val id = store.start((0 until 4).map { req("p$it") }, BatchStore.MODE_LOCAL)
        val file = File(dir, "batch-$id.json")
        until { file.isFile }
        val midway = SwarmJson.decodeFromString<BatchReport>(file.readText())
        assertEquals("running", midway.state)
        assertNull("path tricks in an id find nothing", store.status("../batch-$id"))
        assertNull(store.status("no/such"))
        until { store.status(id)?.value?.state == "done" }
        until { SwarmJson.decodeFromString<BatchReport>(file.readText()).state == "done" }   // the last write is never throttled away
        assertTrue("old files are pruned", (dir.listFiles()?.size ?: 0) <= 16)
    }

    // ---- item 13 ------------------------------------------------------------------------------------------------

    @Test fun workersAreOnePerLiveSlotNeverMoreThanTheItemsLeftAndAtLeastOne() {
        assertEquals(4, BatchRunner.workersFor(4, 30))
        assertEquals(2, BatchRunner.workersFor(4, 2))
        assertEquals(1, BatchRunner.workersFor(0, 30))
        assertEquals(1, BatchRunner.workersFor(3, 0))
    }

    @Test fun aPhoneThatBecomesReadyMidBatchGetsWorkAndAPhoneThatDiesStopsTakingAny() = runBlocking {
        val a = phone("a", delayMs = 80)
        val b = phone("b", delayMs = 80, capacity = 3).also { it.dead = true }
        val llm = SwarmLlm({ listOf(a, b) }, scope, capsMaxAgeMs = 50, busyBackoffMs = 20, probeEveryMs = 100, msPerToken = 0)
        val run = async { BatchRunner(llm, rescanMs = 100).run("t", "swarm", (0 until 40).map { req("p$it") }) }
        delay(500)
        b.dead = false                             // b comes up after the batch started
        val report = run.await()
        assertEquals(40, report.done)
        assertTrue("b took jobs after it came up (${b.llm.executed.size})", b.llm.executed.size >= 5)
        assertTrue("and ran several at once, which needs more workers than at the start (${b.llm.maxConcurrent.get()})", b.llm.maxConcurrent.get() >= 2)
    }

    // ---- item 8 -------------------------------------------------------------------------------------------------

    @Test fun everyBatchItemCarriesPromptMillisecondsTheWaitBeforeDispatchAndItsEndTime() = runBlocking {
        val a = phone("a", delayMs = 40)
        val llm = swarm(a)
        val held = a.node.chatExec.acquire()                    // a job from elsewhere holds the only slot for a while
        scope.launch { delay(300); a.node.chatExec.release() }
        val report = BatchRunner(llm).run("t", "swarm", (0 until 6).map { req("p$it") })
        assertTrue(held)
        assertTrue(report.items.all { it.promptMs == 1.0 })     // TestLlm reports 1 ms of prompt time
        val ends = report.items.map { it.endMs }
        assertTrue("end times are set and grow with each item on one slot: $ends", ends.all { it > 0.0 } && ends == ends.sorted())
        assertTrue("the last item ended before the batch did", ends.last() <= report.wallMs + 1.0)
        assertTrue("the first item waited for the slot (${report.items.first().waitedMs} ms)", report.items.first().waitedMs >= 150.0)
        assertTrue("once the slot was free the next ones did not wait long", report.items.drop(1).all { it.waitedMs < 150.0 })
    }

    @Test fun theNewFieldsAreOptionalOnTheWireInBothDirections() {
        val old = """{"index":3,"jobId":"x-3","nodeId":"n","text":"hi","completionTokens":7,"genMs":12.5}"""
        val item = SwarmJson.decodeFromString<BatchItem>(old)
        assertEquals(0.0, item.promptMs, 0.0)
        assertEquals(0.0, item.waitedMs, 0.0)
        assertEquals(0.0, item.endMs, 0.0)
        // an older phone's parser reads our answer: it has no such fields, and the shared Json ignores unknown keys
        val newer = SwarmJson.encodeToString(item.copy(promptMs = 4.0, waitedMs = 9.0, endMs = 100.0))
        val legacy = SwarmJson.decodeFromString<LegacyItem>(newer)
        assertEquals(3, legacy.index)
        assertEquals("hi", legacy.text)
    }

    @kotlinx.serialization.Serializable
    private data class LegacyItem(val index: Int, val jobId: String, val nodeId: String? = null, val text: String? = null)

    // ---- the batch error is logged ----------------------------------------------------------------------------

    @Test fun aBatchThatEndsWithAnErrorLeavesOneAuditEntryWithTheClassName() = runBlocking {
        val audited = java.util.concurrent.CopyOnWriteArrayList<Pair<String, kotlinx.serialization.json.JsonObject>>()
        val llm = SwarmLlm({ error("secret detail that must not be logged") }, scope)   // the batch itself fails: listing the phones throws
        val store = BatchStore(scope, { llm }, { llm }, audit = { k, p -> audited += k to p })
        val id = store.start(listOf(req("x"), req("y")), BatchStore.MODE_LOCAL)
        until { store.status(id)?.value?.state == "done" }
        until { audited.isNotEmpty() }
        assertEquals(1, audited.size)
        assertEquals("batch.failed", audited[0].first)
        assertEquals("IllegalStateException", audited[0].second["error"]?.toString()?.trim('"'))
        assertFalse(audited[0].second.toString().contains("secret detail"))
        assertEquals("both requests are reported as failed", 2, store.status(id)!!.value.failed)
    }
}
