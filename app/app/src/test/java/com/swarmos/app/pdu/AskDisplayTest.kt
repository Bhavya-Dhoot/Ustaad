package com.swarmos.app.pdu

import com.swarmos.app.kb.KbCanon
import com.swarmos.app.kb.KbEntry
import com.swarmos.app.kb.KbHit
import com.swarmos.app.kb.KbSearch
import com.swarmos.app.kb.KbSource
import com.swarmos.app.kb.KbTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AskDisplayTest {
    private fun src(n: Int) = SourceUi(n, "d$n", "Phone $n", false, PduHit("File$n", "h$n", "txt", 0, "snippet", 0.5, n), false)

    @Test fun theAnswerAreaNamesThePhonesThatDidNotAnswer() {
        fun dev(name: String, status: String, local: Boolean = false) = DeviceResult("id-$name", name, local, status)
        assertNull(MissingPhones.line(listOf(dev("Phone C", "ok"), dev("Phone B", "waiting"), dev("This", "error", local = true))))
        assertEquals("Phone C did not answer", MissingPhones.line(listOf(dev("Phone C", "unreachable"), dev("Phone B", "ok"))))
        assertEquals("Phone A and Phone C did not answer", MissingPhones.line(listOf(dev("Phone A", "offline"), dev("Phone C", "error"))))
        assertEquals("Phone A, Phone B and Phone C did not answer", MissingPhones.line(listOf(dev("Phone A", "offline"), dev("Phone B", "error"), dev("Phone C", "unreachable"))))
    }

    @Test fun aFinishedAnswerListsWhatItCitesFirstAndTheRestAsAlsoSearched() {
        val all = listOf(src(1), src(2), src(3), src(4))
        val done = QueryUi("q", "?", "done", sources = all, answer = "Yes [3]. Also [1].", model = "Qwen")
        val s = SourceSplit.of(done)
        assertEquals(listOf(1, 3), s.cited.map { it.n })      // numbers keep their original value, so the markers still match
        assertEquals(listOf(2, 4), s.rest.map { it.n })
        // while the answer is still being written, and when it cites nothing, nothing is promoted or demoted wrongly
        assertEquals(4, SourceSplit.of(done.copy(phase = "answering")).cited.size)
        assertEquals(listOf(1, 2, 3, 4), SourceSplit.of(done.copy(answer = "Nothing relevant found.")).rest.map { it.n })
    }

    @Test fun aSavedAnswerReportCarriesWhoSavedItWhenAndTheNumberedSources() {
        val card = KbCanon.make("s", "How long?", "Three years [1] and a note [2].", listOf(KbSource("Phone B", "IMG_2231.jpg", 7_000, false), KbSource("Phone A", "Cert.pdf", 8_000, false)), "node-b", "Phone B", 9_000, "h")
        val hit = KbHit(KbEntry(card, KbTier.SWARM, null, true), null, 1.0, 1.0)
        val r = QueryUi("q", "How long?", "done", sources = KbSearch.sourcesOf(card), savedAnswer = hit, cards = listOf(hit)).toReport()
        assertEquals(card.id, r.fromCard)
        assertEquals("Phone B", r.savedBy)
        assertEquals(9_000L, r.savedAtMs)
        assertEquals("Three years [1] and a note [2].", r.answer)
        assertEquals(listOf(1, 2), r.sources.map { it.n })                       // the same AskSource shape as live sources
        assertEquals(listOf("Phone B", "Phone A"), r.sources.map { it.phone })
        assertEquals(listOf("IMG_2231.jpg", "Cert.pdf"), r.sources.map { it.title })
        assertEquals(listOf(7_000L, 8_000L), r.sources.map { it.dateMs })
    }

    private fun peer(status: String, rtt: Long = 0) = DeviceResult("n", "Phone A", false, status, rttMs = rtt)

    @Test fun aPeerThatDidNotAnswerIsAwayOrNoReplyNeverZeroMilliseconds() {
        assertEquals("away", PeerTiming.text(peer("offline")))
        assertEquals("no reply", PeerTiming.text(peer("unreachable")))
        assertEquals("no reply", PeerTiming.text(peer("error")))
        assertEquals("-", PeerTiming.text(peer("waiting")))
    }

    @Test fun aPeerThatAnsweredShowsItsRoundTrip() {
        assertEquals("614 ms", PeerTiming.text(peer("ok", 614)))
        assertEquals("0 ms", PeerTiming.text(peer("ok", 0))) // a real, instant answer is still a number
    }
}
