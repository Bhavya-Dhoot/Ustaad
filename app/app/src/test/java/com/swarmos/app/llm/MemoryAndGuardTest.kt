package com.swarmos.app.llm

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MemoryAndGuardTest {
    private val req = ChatRequest(listOf(ChatMessage("user", "hi")))

    // ---- memory pressure ----

    @Test fun onlyTheExactCriticalLevelCounts() {
        assertTrue(MemoryPressure.isCritical(15))
        for (level in listOf(5, 10, 20, 40, 60, 80)) assertFalse("level $level", MemoryPressure.isCritical(level))
    }

    @Test fun theEmbedderIsStoppedOnlyWhenNothingUsesIt() {
        val idleMs = MemoryPressure.EMBED_IDLE_MS
        assertTrue(MemoryPressure.embedderIdle(running = true, callInFlight = false, msSinceLastCall = idleMs, indexRunning = false))
        assertTrue(MemoryPressure.embedderIdle(true, false, Long.MAX_VALUE, false)) // never used since it started
        assertFalse(MemoryPressure.embedderIdle(running = false, callInFlight = false, msSinceLastCall = idleMs, indexRunning = false))
        assertFalse(MemoryPressure.embedderIdle(true, callInFlight = true, msSinceLastCall = idleMs, indexRunning = false)) // a search is in flight
        assertFalse(MemoryPressure.embedderIdle(true, false, msSinceLastCall = idleMs - 1, indexRunning = false)) // the gap inside an index run
        assertFalse(MemoryPressure.embedderIdle(true, false, idleMs, indexRunning = true)) // an index run is announced
    }

    // ---- the stand-in for an embedder stopped for memory ----

    private class CountingEmbedder : LlmClient {
        var calls = 0
        override val modelName = "emb"
        override suspend fun ready() = true
        override fun chatStream(req: ChatRequest) = throw UnsupportedOperationException()
        override suspend fun chat(req: ChatRequest): ChatResult = throw UnsupportedOperationException()
        override suspend fun embed(texts: List<String>): List<FloatArray> = texts.map { floatArrayOf(calls++.toFloat()) }
    }

    @Test fun theStandInStartsTheServerOnTheFirstEmbedAndAnswersFromIt() = runBlocking {
        val real = CountingEmbedder()
        var revived = 0
        val standIn = DroppedEmbedder("embeddinggemma", "/m/e.gguf", { revived++; real })
        assertEquals("embeddinggemma", standIn.modelName) // looks like the real embedder to the features that check the name
        assertTrue(standIn.ready())
        assertEquals(0, revived) // nothing starts until something is embedded
        val v = standIn.embed(listOf("a", "b"))
        assertEquals(2, v.size)
        assertEquals(1, revived)
        assertEquals(2, real.calls)
    }

    @Test fun aServerThatCannotBeStartedAgainFailsTheCallInsteadOfReturningPlaceholders() = runBlocking {
        val standIn = DroppedEmbedder("embeddinggemma", "/m/e.gguf", { null })
        try {
            standIn.embed(listOf("a"))
            fail("expected LlamaServerException")
        } catch (e: LlamaServerException) {
            assertEquals(DroppedEmbedder.NOT_RESTARTED, e.message)
        }
    }

    @Test fun theStandInDoesNotChat() = runBlocking {
        val standIn = DroppedEmbedder("embeddinggemma", "/m/e.gguf", { null })
        try {
            standIn.chat(req)
            fail("expected LlamaServerException")
        } catch (e: LlamaServerException) {
            assertEquals(DroppedEmbedder.EMBEDDINGS_ONLY, e.message)
        }
    }

    // ---- the real-model guard for the app's own callers ----

    private class Answering : LlmClient {
        override val modelName = "Qwen3.5-4B-Q4_K_M"
        override suspend fun ready() = true
        override fun chatStream(req: ChatRequest) = kotlinx.coroutines.flow.flowOf(ChatChunk("real", done = true, stats = ChatStats(1, 1, 1.0, 1.0)))
        override suspend fun chat(req: ChatRequest) = ChatResult("real", ChatStats(1, 1, 1.0, 1.0))
        override suspend fun embed(texts: List<String>) = texts.map { floatArrayOf(1f) }
    }

    @Test fun theRealModelAnswersThroughTheGuard() = runBlocking {
        val guard = RealModelGuard({ Answering() }, RealModelGuard.NO_CHAT)
        assertEquals("real", guard.chat(req).text)
        assertEquals("real", guard.chatStream(req).toList().first().delta)
        assertEquals(1, guard.embed(listOf("a")).size)
        assertTrue(guard.ready())
        assertEquals("Qwen3.5-4B-Q4_K_M", guard.modelName)
    }

    @Test fun thePlaceholderNeverAnswersAGuardedCall() = runBlocking {
        val guard = RealModelGuard({ FakeLlmClient() }, RealModelGuard.NO_CHAT)
        assertFalse(guard.ready())
        for (call in listOf<suspend () -> Unit>({ guard.chat(req) }, { guard.chatStream(req).toList() }, { guard.embed(listOf("a")) })) {
            try {
                call()
                fail("the placeholder answered")
            } catch (e: ModelNotLoadedException) {
                assertEquals(RealModelGuard.NO_CHAT, e.message)
                assertEquals(503, e.status)
            }
        }
    }

    @Test fun aServerThatDiesWhileTheCallWaitsIsCaughtBecauseTheClientIsReadAtTheMomentOfTheCall() = runBlocking {
        var installed: LlmClient = Answering()
        val guard = RealModelGuard({ installed }, RealModelGuard.NO_CHAT) // made while the real model was installed, like AppGraph.gatedLlm's inner
        assertEquals("real", guard.chat(req).text)
        installed = FakeLlmClient() // the server died; the runtime installs the placeholder
        try {
            guard.chat(req)
            fail("a canned answer reached a caller that was queued for the dead server")
        } catch (e: ModelNotLoadedException) {
            val asServerError: LlamaServerException = e // callers that already handle server errors handle this too
            assertEquals(503, asServerError.status)
        }
    }
}
