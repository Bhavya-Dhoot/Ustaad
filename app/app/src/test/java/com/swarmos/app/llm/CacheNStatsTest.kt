package com.swarmos.app.llm

import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** The server's `timings.cache_n` (prompt tokens reused from its cache) reaches [ChatStats.cachedTokens]. */
class CacheNStatsTest {
    @Test fun cacheNIsCarriedIntoTheStatsAndNotIntoThePromptTokens() {
        val p = SseChatParser()
        p.feed("""data: {"choices":[{"finish_reason":"stop","index":0,"delta":{}}],"timings":{"cache_n":14,"prompt_n":6,"prompt_ms":345.6,"predicted_n":6,"predicted_ms":385.3}}""")
        p.feed("data: [DONE]")
        val s = p.finalStats(1.0, 1.0)
        assertEquals(14, s.cachedTokens)
        assertEquals(6, s.promptTokens)
    }

    @Test fun cacheNIsZeroWhenTheServerSaysNothing() {
        val t = LlamaJson.json.parseToJsonElement("""{"prompt_n":20,"prompt_ms":100.0,"predicted_n":5,"predicted_ms":250.0}""").jsonObject
        assertEquals(0, LlamaJson.statsFromTimings(t).cachedTokens)
        assertEquals(0, ChatStats(1, 2, 3.0, 4.0).cachedTokens) // existing four-argument constructions are unchanged
        assertEquals(0, LlamaJson.statsFromUsage(LlamaJson.json.parseToJsonElement("""{"prompt_tokens":3,"completion_tokens":2}""").jsonObject).cachedTokens)
    }

    @Test fun cacheNComesThroughTheNonStreamingParseToo() {
        val r = LlamaJson.parseChatResponse(
            """{"choices":[{"finish_reason":"stop","index":0,"message":{"role":"assistant","content":"x"}}],"timings":{"cache_n":9,"prompt_n":3,"prompt_ms":10.0,"predicted_n":1,"predicted_ms":5.0}}""",
        )
        assertEquals(9, r.stats.cachedTokens)
    }
}
