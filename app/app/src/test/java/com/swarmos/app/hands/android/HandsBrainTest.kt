package com.swarmos.app.hands.android

import com.swarmos.app.hands.Tier
import com.swarmos.app.llm.ChatChunk
import com.swarmos.app.llm.ChatRequest
import com.swarmos.app.llm.ChatResult
import com.swarmos.app.llm.ChatStats
import com.swarmos.app.llm.LlmClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

private class FakeLlm(override val modelName: String = "fake", var reply: suspend (ChatRequest) -> String = { "{}" }) : LlmClient {
    val requests = CopyOnWriteArrayList<ChatRequest>()
    override suspend fun ready() = true
    override fun chatStream(req: ChatRequest): Flow<ChatChunk> = throw UnsupportedOperationException()
    override suspend fun chat(req: ChatRequest): ChatResult {
        requests += req
        return ChatResult(reply(req), ChatStats(0, 0, 0.0, 0.0))
    }

    override suspend fun embed(texts: List<String>): List<FloatArray> = throw UnsupportedOperationException()
}

private class FakeSwarm(var models: List<String> = emptyList(), var answer: String = "swarm says hi") : SwarmSide {
    val chats = CopyOnWriteArrayList<Pair<ChatRequest, String?>>()
    val refreshes = AtomicInteger()
    var refreshFails = false
    override fun peerModels() = models
    override suspend fun refresh() {
        refreshes.incrementAndGet()
        if (refreshFails) throw IllegalStateException("peer did not answer")
    }

    override suspend fun chat(req: ChatRequest, pinModel: String?): String {
        chats += req to pinModel
        return answer
    }
}

class HandsBrainTest {
    private val schema: JsonObject = buildJsonObject {
        put("type", "object")
        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("answer"))))
    }

    private val sizes = mapOf("small" to 2_000L, "big" to 9_000L, "mid" to 5_000L)

    private class Rig(
        val local: FakeLlm = FakeLlm("small", { "local reply" }),
        val swarm: FakeSwarm = FakeSwarm(),
        var localReady: Boolean = true,
        var share: Boolean = false,
        var test: FakeLlm? = null,
        sizes: Map<String, Long>,
        timeoutMs: Long = HandsBrain.BASE_TIMEOUT_MS,
    ) {
        val brain = HandsBrain(
            local = { local },
            localReady = { localReady },
            localModel = { local.modelName },
            swarm = swarm,
            shareScreens = { share },
            testClient = { test },
            modelBytes = { sizes[it] ?: 0L },
            timeoutBaseMs = timeoutMs,
        )

        fun complete(tier: Tier, onPhoneOnly: Boolean, schema: JsonObject = buildJsonObject { put("type", "object") }) = runBlocking {
            brain.complete(tier, "SYS", "USER", schema, 123, onPhoneOnly)
        }
    }

    private fun rig(
        local: FakeLlm = FakeLlm("small", { "local reply" }),
        swarm: FakeSwarm = FakeSwarm(),
        localReady: Boolean = true,
        share: Boolean = false,
        test: FakeLlm? = null,
        timeoutMs: Long = HandsBrain.BASE_TIMEOUT_MS,
    ) = Rig(local, swarm, localReady, share, test, sizes, timeoutMs)

    // ------------------------------------------------------------ the request

    @Test
    fun theRequestHasBothMessagesTheLimitsAndTheSchemaAsText() {
        val r = rig()
        assertEquals("local reply", r.complete(Tier.FAST, onPhoneOnly = true, schema = schema))
        val req = r.local.requests.single()
        assertEquals(listOf("system" to "SYS", "user" to "USER"), req.messages.map { it.role to it.content })
        assertEquals(123, req.maxTokens)
        assertEquals(0.2, req.temperature, 0.0)
        assertTrue(req.cachePrompt)
        assertEquals(schema, Json.parseToJsonElement(req.jsonSchema!!))
    }

    @Test
    fun theReplyIsTrimmed() {
        val r = rig(local = FakeLlm("small", { "  {\"a\":1}\n" }))
        assertEquals("{\"a\":1}", r.complete(Tier.FAST, onPhoneOnly = true))
    }

    // ------------------------------------------------------------ where it goes

    @Test
    fun aScreenPromptWithSharingOffStaysOnThisPhoneAndNeverAsksTheSwarm() {
        val r = rig(swarm = FakeSwarm(listOf("big")))
        r.complete(Tier.STRONG, onPhoneOnly = true)
        r.complete(Tier.FAST, onPhoneOnly = true)
        assertEquals(2, r.local.requests.size)
        assertTrue(r.swarm.chats.isEmpty())
        assertEquals("no swarm report is fetched for a call that cannot use the swarm", 0, r.swarm.refreshes.get())
    }

    @Test
    fun withNoModelOfItsOwnAScreenPromptFailsWithAMessageForThePerson() {
        val r = rig(swarm = FakeSwarm(listOf("big")), localReady = false)
        try {
            r.complete(Tier.FAST, onPhoneOnly = true)
            fail("expected NoModelException")
        } catch (e: NoModelException) {
            assertEquals(
                "This phone has no model of its own for this step. Load a model, or turn on sharing the screen with your other devices in Hands.",
                e.message,
            )
        }
        assertTrue(r.swarm.chats.isEmpty())
        assertTrue(r.local.requests.isEmpty())
    }

    @Test
    fun withNoModelAnywhereAPlanningPromptSaysSharingWouldNotHelp() {
        val r = rig(localReady = false)
        try {
            r.complete(Tier.STRONG, onPhoneOnly = false)
            fail("expected NoModelException")
        } catch (e: NoModelException) {
            assertEquals(HandsBrain.NO_MODEL_ANYWHERE, e.message)
        }
    }

    @Test
    fun withSharingOnAScreenPromptMayGoToAPeerWhenThisPhoneHasNoModel() {
        val r = rig(swarm = FakeSwarm(listOf("mid")), localReady = false, share = true)
        assertEquals("swarm says hi", r.complete(Tier.FAST, onPhoneOnly = true))
        assertEquals("mid", r.swarm.chats.single().second)
        assertTrue(r.local.requests.isEmpty())
    }

    @Test
    fun planningGoesToAStrongerPeerPinnedToItsModel() {
        val r = rig(swarm = FakeSwarm(listOf("mid", "big")))
        assertEquals("swarm says hi", r.complete(Tier.STRONG, onPhoneOnly = false))
        assertEquals("big", r.swarm.chats.single().second)
        assertEquals("the request is the same as for a local call", 123, r.swarm.chats.single().first.maxTokens)
        assertTrue(r.local.requests.isEmpty())
    }

    @Test
    fun planningStaysHereWhenThePeerIsNotLarger() {
        val r = rig(swarm = FakeSwarm(listOf("small")))
        assertEquals("local reply", r.complete(Tier.STRONG, onPhoneOnly = false))
        assertTrue(r.swarm.chats.isEmpty())
    }

    @Test
    fun aPeerWhoseModelSizeIsUnknownIsNeverCalledStronger() {
        val r = rig(swarm = FakeSwarm(listOf("some-model-not-in-the-catalogue")))
        assertEquals("local reply", r.complete(Tier.STRONG, onPhoneOnly = false))
        assertTrue(r.swarm.chats.isEmpty())
    }

    @Test
    fun whenThisPhonesOwnModelSizeIsUnknownNoPeerIsCalledStronger() {
        val r = rig(local = FakeLlm("custom-local", { "local reply" }), swarm = FakeSwarm(listOf("big")))
        assertEquals("local reply", r.complete(Tier.STRONG, onPhoneOnly = false))
    }

    @Test
    fun planningUsesAPeerOfUnknownSizeWhenThisPhoneHasNoModel() {
        val r = rig(swarm = FakeSwarm(listOf("some-model-not-in-the-catalogue")), localReady = false)
        assertEquals("swarm says hi", r.complete(Tier.STRONG, onPhoneOnly = false))
        assertEquals("some-model-not-in-the-catalogue", r.swarm.chats.single().second)
    }

    @Test
    fun theSwarmReportsAreRefreshedBeforeACallThatCouldUseThem() {
        val r = rig(swarm = FakeSwarm(listOf("big")))
        r.complete(Tier.STRONG, onPhoneOnly = false)
        assertEquals(1, r.swarm.refreshes.get())
    }

    @Test
    fun aPeerThatDoesNotAnswerTheRefreshDoesNotFailTheCall() {
        val swarm = FakeSwarm(listOf("big")).also { it.refreshFails = true }
        val r = rig(swarm = swarm)
        assertEquals("swarm says hi", r.complete(Tier.STRONG, onPhoneOnly = false))
    }

    // ------------------------------------------------------------ the test brain

    @Test
    fun theTestBrainTakesBothTiersWhateverElseIsLoaded() {
        val test = FakeLlm("test", { "test reply" })
        val r = rig(swarm = FakeSwarm(listOf("big")), localReady = false, share = false, test = test)
        assertEquals("test reply", r.complete(Tier.FAST, onPhoneOnly = true))
        assertEquals("test reply", r.complete(Tier.STRONG, onPhoneOnly = false))
        assertEquals(2, test.requests.size)
        assertTrue(r.swarm.chats.isEmpty())
        assertTrue(r.local.requests.isEmpty())
        assertTrue(r.brain.has(Tier.FAST))
        assertTrue(r.brain.has(Tier.STRONG))
    }

    // ------------------------------------------------------------ has

    @Test
    fun hasFastIsAScreenPromptNotBeingNone() {
        assertTrue(rig().brain.has(Tier.FAST))
        assertFalse("a peer does not give FAST while sharing is off", rig(localReady = false, swarm = FakeSwarm(listOf("big"))).brain.has(Tier.FAST))
        assertTrue("a peer gives FAST once sharing is on", rig(localReady = false, share = true, swarm = FakeSwarm(listOf("big"))).brain.has(Tier.FAST))
        assertFalse(rig(localReady = false, share = true).brain.has(Tier.FAST))
    }

    @Test
    fun hasStrongIsAPlanningPromptNotBeingNone() {
        assertTrue(rig().brain.has(Tier.STRONG))
        assertTrue("a peer is enough for planning, whatever the switch says", rig(localReady = false, swarm = FakeSwarm(listOf("big"))).brain.has(Tier.STRONG))
        assertFalse(rig(localReady = false).brain.has(Tier.STRONG))
    }

    @Test
    fun hasFollowsTheSwitchAtOnce() {
        val r = rig(localReady = false, swarm = FakeSwarm(listOf("big")))
        assertFalse(r.brain.has(Tier.FAST))
        r.share = true
        assertTrue(r.brain.has(Tier.FAST))
        r.share = false
        assertFalse(r.brain.has(Tier.FAST))
    }

    // ------------------------------------------------------------ failures

    @Test
    fun aModelThatStaysSilentEndsAsAnOrdinaryFailureNotAsACancellation() {
        val hang = FakeLlm("small") { delay(60_000); "never" }
        val r = rig(local = hang, timeoutMs = 50)
        try {
            runBlocking { r.brain.complete(Tier.FAST, "S", "U", schema, 0, onPhoneOnly = true) }
            fail("expected a failure")
        } catch (e: CancellationException) {
            fail("a time limit must not look like the run being stopped: $e")
        } catch (e: IllegalStateException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun aCancelledCallStaysCancelled() {
        val started = CompletableDeferred<Unit>()
        val hang = FakeLlm("small") { started.complete(Unit); delay(60_000); "never" }
        val r = rig(local = hang)
        val result = runBlocking {
            val job = async { r.brain.complete(Tier.FAST, "S", "U", schema, 10, onPhoneOnly = true) }
            started.await()
            job.cancel()
            runCatching { job.await() }.exceptionOrNull()
        }
        assertTrue(result is CancellationException)
    }

    @Test
    fun aModelErrorPassesThrough() {
        val r = rig(local = FakeLlm("small") { throw java.io.IOException("server gone") })
        try {
            r.complete(Tier.FAST, onPhoneOnly = true)
            fail("expected IOException")
        } catch (e: java.io.IOException) {
            assertEquals("server gone", e.message)
        }
    }
}
