package com.swarmos.app.llm

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeLlmClientTest {
    private val req = ChatRequest(listOf(ChatMessage("user", "hello there")), maxTokens = 64)

    @Test fun theVisibleAnswerSaysNoModelIsLoaded() = runBlocking {
        val r = FakeLlmClient().chat(req)
        assertEquals("[FAKE] No chat model is loaded on this phone.", r.text)
        assertFalse(r.text.contains("wired"))
        assertTrue(r.text.startsWith("[FAKE]"))
    }

    @Test fun theAnswerIsDeterministic() = runBlocking {
        val a = FakeLlmClient().chat(req)
        val b = FakeLlmClient().chat(req)
        assertEquals(a.text, b.text)
        assertEquals(a.stats, b.stats)
        assertEquals("FAKE", FakeLlmClient().modelName)
    }

    @Test fun aSchemaRequestStillGetsAnEmptyObject() = runBlocking {
        assertEquals("{}", FakeLlmClient().chat(req.copy(jsonSchema = """{"type":"object"}""")).text)
    }
}
