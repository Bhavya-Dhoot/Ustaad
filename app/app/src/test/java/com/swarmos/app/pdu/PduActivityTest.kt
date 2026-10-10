package com.swarmos.app.pdu

import com.swarmos.app.core.PhoneActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class PduActivityTest {
    @After fun reset() = PhoneActivity.clearAll()

    @Test fun theLineReadsInPlainWordsWithRealNumbers() {
        assertEquals("Indexing 12 files (3 done).", PduActivity.indexing(12, 3))
        assertEquals("Indexing 1 file (0 done).", PduActivity.indexing(1, 0))
        assertEquals("Searching this phone and 1 other.", PduActivity.searching(1))
        assertEquals("Searching this phone and 2 others.", PduActivity.searching(2))
        assertEquals("Searching this phone.", PduActivity.searching(0))
        assertEquals("Writing an answer from 5 sources.", PduActivity.answering(5))
        assertEquals("Searching this phone for Phone A.", PduActivity.servingPeer("Phone A"))
        assertEquals("Searching this phone for another phone.", PduActivity.servingPeer(null))
    }

    @Test fun theLineIsShownWhileThePeerIsServedAndGoneAfterIt() = runBlocking {
        val seen = PduActivity.during(PduActivity.servingPeer("Phone A")) {
            delay(5)
            PhoneActivity.current.value
        }
        assertEquals("pdu", seen?.owner)
        assertEquals("Searching this phone for Phone A.", seen?.text)
        assertNull(PhoneActivity.current.value)
    }

    @Test fun theLineIsGoneAfterTheWorkThrows() = runBlocking {
        try {
            PduActivity.during(PduActivity.servingPeer("Phone A")) {
                assertEquals("Searching this phone for Phone A.", PhoneActivity.current.value?.text)
                throw IllegalStateException("embedder down")
            }
            fail("expected the exception to come through")
        } catch (e: IllegalStateException) {
            assertEquals("embedder down", e.message)
        }
        assertNull(PhoneActivity.current.value)
    }

    @Test fun indexProgressReplacesTheCountInPlaceUntilCleared() {
        PduActivity.set(PduActivity.listing())
        for (i in 0 until 3) PduActivity.set(PduActivity.indexing(3, i))
        assertEquals("Indexing 3 files (2 done).", PhoneActivity.current.value?.text)
        PduActivity.clear()
        assertNull(PhoneActivity.current.value)
    }
}
