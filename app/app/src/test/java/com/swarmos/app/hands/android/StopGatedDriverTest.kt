package com.swarmos.app.hands.android

import com.swarmos.app.hands.FakeFoodDriver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopGatedDriverTest {
    private val fake = FakeFoodDriver()

    /** [surfaceAfterPauses]: the surface appears after that many pauses (-1: never). The pauses are counted, not slept. */
    private class Gate(var surfaceAfterPauses: Int) {
        var pauses = 0
        val waited = ArrayList<Long>()
        fun surface(): Boolean = surfaceAfterPauses in 0..pauses
        suspend fun pause(ms: Long) {
            pauses++
            waited += ms
        }
    }

    private fun driver(g: Gate, maxWaitMs: Long = 1_000, pollMs: Long = 250) = StopGatedDriver(fake, g::surface, g::pause, maxWaitMs, pollMs)

    @Test
    fun withASurfaceEveryActionGoesThroughAtOnce() = runBlocking {
        val g = Gate(0)
        val d = driver(g)
        assertTrue(d.enter())
        assertTrue(d.back())
        assertTrue(d.scroll(null, true))
        assertTrue(d.tap("cart", 0, 0))
        assertEquals(0, g.pauses)
        assertEquals(listOf("enter", "back", "scroll:-:true", "tap:cart"), fake.actions)
    }

    @Test
    fun withoutASurfaceNothingActsAndTheWaitIsBounded() = runBlocking {
        val g = Gate(-1)
        val d = driver(g, maxWaitMs = 1_000, pollMs = 250)
        assertFalse(d.tap("cart", 0, 0))
        assertFalse(d.setText("search", 0, 0, "biryani"))
        assertFalse(d.enter())
        assertFalse(d.scroll(null, true))
        assertFalse(d.back())
        assertFalse(d.home())
        assertFalse(d.openApp("com.fake.food"))
        assertFalse(d.openLink("https://example.com", "com.fake.food"))
        assertTrue("the fake app saw none of it", fake.actions.isEmpty())
        assertEquals("1000 ms in steps of 250 for each of the eight calls", 32, g.pauses)
        assertTrue(g.waited.all { it == 250L })
    }

    @Test
    fun aSurfaceThatComesBackLetsTheActionGoOn() = runBlocking {
        val g = Gate(2) // the chip is put back after two polls
        val d = driver(g)
        assertTrue(d.tap("cart", 0, 0))
        assertEquals(2, g.pauses)
        assertEquals(listOf("tap:cart"), fake.actions)
    }

    @Test
    fun readingAndListingAppsAreNeverHeldBack() = runBlocking {
        val g = Gate(-1)
        val d = driver(g)
        assertEquals("com.fake.food", d.read().pkg)
        assertTrue(d.apps().isNotEmpty())
        assertEquals(0, g.pauses)
        assertEquals("fake", d.name)
        assertTrue(d.ready())
    }
}
