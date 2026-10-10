package com.swarmos.app.pdu

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Follow-up 3a: 64 query vectors, least recently used out first, emptied when the embedder changes. */
class QueryVectorCacheTest {
    private fun v(x: Float) = FloatArray(4) { x }

    @Test fun aSecondAskWithTheSameTextGetsTheSameVector() {
        val c = QueryVectorCache()
        val a = v(1f)
        c.put("gemma", "", "where is my bill", a)
        assertSame(a, c.get("gemma", "", "where is my bill"))
    }

    @Test fun theKeyIsEmbedderPrefixAndText() {
        val c = QueryVectorCache()
        c.put("gemma", "", "q", v(1f))
        assertNull(c.get("gemma", "task: ", "q"))
        assertNull(c.get("gemma", "", "other"))
    }

    @Test fun aDifferentEmbedderEmptiesTheCache() {
        val c = QueryVectorCache()
        c.put("gemma", "", "q", v(1f))
        assertNull(c.get("other-model", "", "q"))
        assertEquals(0, c.size)
        assertNull("the old model's vector must not come back", c.get("gemma", "", "q"))
    }

    @Test fun onlyTheLast64AreKeptAndTheLeastRecentlyUsedGoesFirst() {
        val c = QueryVectorCache(64)
        for (i in 0 until 64) c.put("m", "", "q$i", v(i.toFloat()))
        c.get("m", "", "q0") // q0 is used again, so q1 is now the oldest
        c.put("m", "", "q64", v(64f))
        assertEquals(64, c.size)
        assertNull(c.get("m", "", "q1"))
        assertArrayEquals(v(0f), c.get("m", "", "q0"), 0f)
        assertArrayEquals(v(64f), c.get("m", "", "q64"), 0f)
    }
}
