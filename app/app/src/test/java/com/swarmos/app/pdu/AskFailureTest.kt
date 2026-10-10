package com.swarmos.app.pdu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an ask looks like once it is closed after a stop or an error nobody planned for. PduEngine itself needs the app graph, so the
 * closing step is a plain function. A failed ask must end "done": a query left "searching" or "answering" keeps Ask disabled for good.
 */
class AskFailureTest {
    private val running = QueryUi("q1", "what is the warranty?", "answering", answer = "The warranty is", model = "Qwen", timings = Timings(embedMs = 40))

    @Test fun aFailedAskEndsDoneWithAPlainNoteKeepingWhatWasWritten() {
        val closed = closedAsk(running, ASK_FAILED_NOTE, totalMs = 1234)
        assertEquals("done", closed.phase)
        assertEquals(ASK_FAILED_NOTE, closed.note)
        assertEquals("The warranty is", closed.answer)
        assertEquals(1234L, closed.timings.totalMs)
        assertEquals("the other timings stay", 40L, closed.timings.embedMs)
    }

    @Test fun theNoteIsPlainEnglishWithoutJargonOrDashes() {
        assertEquals("Something went wrong while answering. Ask again.", ASK_FAILED_NOTE)
        assertFalse(ASK_FAILED_NOTE.contains('—'))
        assertFalse(ASK_FAILED_NOTE.contains("Exception"))
    }

    @Test fun aStoppedAskKeepsTheStoppedNoteAndANoteAlreadySetWins() {
        assertEquals(AnswerNotes.STOPPED, closedAsk(running, AnswerNotes.STOPPED, 5).note)
        val withNote = running.copy(note = AnswerNotes.NO_CHAT_MODEL)
        assertEquals(AnswerNotes.NO_CHAT_MODEL, closedAsk(withNote, ASK_FAILED_NOTE, 5).note)
    }

    @Test fun anAskThatIsAlreadyDoneIsLeftAsItIs() {
        val done = running.copy(phase = "done", note = null, timings = Timings(totalMs = 900))
        assertSame(done, closedAsk(done, ASK_FAILED_NOTE, 5000))
        assertTrue(closedAsk(running.copy(phase = "searching"), ASK_FAILED_NOTE, 1).phase == "done")
    }
}
