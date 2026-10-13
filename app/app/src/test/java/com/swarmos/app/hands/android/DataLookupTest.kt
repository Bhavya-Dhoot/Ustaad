package com.swarmos.app.hands.android

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class DataLookupTest {
    private class Rig(var share: Boolean, var busy: Boolean = false) {
        val federatedCalls = AtomicInteger()
        val localCalls = AtomicInteger()
        val queries = ArrayList<String>()
        val lookup = DataLookup(
            shareScreens = { share },
            askInProgress = { busy },
            federated = { q -> federatedCalls.incrementAndGet(); queries += "federated:$q"; "from the cluster" },
            local = { q -> localCalls.incrementAndGet(); queries += "local:$q"; "from this phone" },
        )

        fun ask(q: String = "flight time") = runBlocking { lookup.answer(q) }
    }

    @Test
    fun pathFollowsTheSwitchAndTheBusyAsk() {
        assertEquals(DataPath.LOCAL, DataLookup.pathFor(shareScreens = false, askInProgress = false))
        assertEquals(DataPath.FEDERATED, DataLookup.pathFor(shareScreens = true, askInProgress = false))
        assertEquals(DataPath.NONE, DataLookup.pathFor(shareScreens = false, askInProgress = true))
        assertEquals(DataPath.NONE, DataLookup.pathFor(shareScreens = true, askInProgress = true))
    }

    @Test
    fun sharingOffNeverReachesTheFederatedCall() {
        val r = Rig(share = false)
        assertEquals("from this phone", r.ask("my passport number is on this screen"))
        assertEquals(0, r.federatedCalls.get())
        assertEquals(listOf("local:my passport number is on this screen"), r.queries)
    }

    @Test
    fun sharingOffNeverReachesTheFederatedCallWhateverElseChanges() {
        for (busy in listOf(false, true)) {
            val r = Rig(share = false, busy = busy)
            repeat(5) { r.ask("q$it") }
            assertEquals("busy=$busy", 0, r.federatedCalls.get())
        }
    }

    @Test
    fun sharingOnKeepsTheFederatedAsk() {
        val r = Rig(share = true)
        assertEquals("from the cluster", r.ask())
        assertEquals(1, r.federatedCalls.get())
        assertEquals(0, r.localCalls.get())
    }

    @Test
    fun aQuestionThePersonIsAskingIsNeverTouched() {
        for (share in listOf(false, true)) {
            val r = Rig(share = share, busy = true)
            assertNull("share=$share", r.ask())
            assertEquals(0, r.federatedCalls.get())
            assertEquals(0, r.localCalls.get())
        }
    }

    @Test
    fun theSwitchIsReadAtEveryQuestion() {
        val r = Rig(share = false)
        assertEquals("from this phone", r.ask())
        r.share = true
        assertEquals("from the cluster", r.ask())
        r.share = false
        assertEquals("from this phone", r.ask())
        assertEquals(1, r.federatedCalls.get())
    }

    // ------------------------------------------------------------ the passages of the own index

    private fun p(title: String, text: String, score: Double) = DataPassages.Passage(title, text, score)

    @Test
    fun noPassageGivesNull() {
        assertNull(DataPassages.text(emptyList()))
        assertNull(DataPassages.text(listOf(p("a", "   ", 1.0))))
    }

    @Test
    fun thePassagesComeBestFirstWithTheirSourcesAndOnlyThreeOfThem() {
        val t = DataPassages.text(listOf(p("low", "four", 0.1), p("best", "one", 0.9), p("mid", "two", 0.5), p("next", "three", 0.4)))!!
        assertEquals(listOf("[best] one", "[mid] two", "[next] three"), t.lines())
    }

    @Test
    fun theTextIsNeverLongerThanSixHundredCharacters() {
        for (n in 1..5) {
            val many = (1..n).map { p("document number $it " + "t".repeat(100), "word ".repeat(400), 1.0 - it / 10.0) }
            val t = DataPassages.text(many)!!
            assertTrue("n=$n len=${t.length}", t.length <= DataPassages.MAX_CHARS)
            assertTrue(t.lines().all { it.startsWith("[") })
        }
        assertTrue("a long title is cut too", DataPassages.text(listOf(p("x".repeat(500), "body", 1.0)))!!.length < 100)
    }

    @Test
    fun aShortPassageIsKeptWhole() {
        assertEquals("[bill.pdf] Total due 1,240 rupees by 5 Nov.", DataPassages.text(listOf(p("bill.pdf", "Total due 1,240\n rupees   by 5 Nov.", 1.0))))
    }

    @Test
    fun aMissingTitleIsNamedAndTheCutEndsWithAnEllipsis() {
        val t = DataPassages.text(listOf(p("", "w".repeat(1000), 1.0)))!!
        assertTrue(t.startsWith("[untitled] "))
        assertTrue(t.endsWith("…"))
        assertEquals(DataPassages.MAX_CHARS, t.length)
    }
}
