package com.swarmos.app.choir

import com.swarmos.app.core.PhoneActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class ChoirActivityTest {
    @After fun reset() = PhoneActivity.clearAll()

    @Test fun theLineReadsInPlainWordsWithRealNumbers() {
        assertEquals("Asking 2 phones to solve one problem.", ChoirActivity.asking(2))
        assertEquals("Asking 1 phone to solve one problem.", ChoirActivity.asking(1))
        assertEquals("Running an Agent Choir check (7 of 20).", ChoirActivity.check(7, 20))
        assertEquals("Solving a problem for Phone A.", ChoirActivity.solvingFor("Phone A"))
        assertEquals("Solving a problem for another phone.", ChoirActivity.solvingFor(null))
    }

    @Test fun theLineIsShownWhileTheWorkRunsAndGoneAfterIt() = runBlocking {
        val seen = ChoirActivity.during(ChoirActivity.asking(2)) {
            delay(5)
            PhoneActivity.current.value
        }
        assertEquals("choir", seen?.owner)
        assertEquals("Asking 2 phones to solve one problem.", seen?.text)
        assertNull(PhoneActivity.current.value)
    }

    @Test fun theLineIsGoneAfterTheWorkThrows() = runBlocking {
        try {
            ChoirActivity.during(ChoirActivity.solvingFor("Phone A")) {
                assertEquals("Solving a problem for Phone A.", PhoneActivity.current.value?.text)
                throw IllegalStateException("model died")
            }
            fail("expected the exception to come through")
        } catch (e: IllegalStateException) {
            assertEquals("model died", e.message)
        }
        assertNull(PhoneActivity.current.value)
    }

    @Test fun theLineIsGoneAfterTheWorkIsCancelled() = runBlocking {
        val job = launch { ChoirActivity.during(ChoirActivity.asking(3)) { delay(60_000) } }
        delay(20)
        assertEquals("Asking 3 phones to solve one problem.", PhoneActivity.current.value?.text)
        job.cancel(CancellationException("stopped"))
        job.join()
        assertNull(PhoneActivity.current.value)
    }

    @Test fun progressReplacesTheCountInPlace() {
        ChoirActivity.set(ChoirActivity.check(6, 20))
        ChoirActivity.set(ChoirActivity.check(7, 20))
        assertEquals("Running an Agent Choir check (7 of 20).", PhoneActivity.current.value?.text)
        ChoirActivity.clear()
        assertNull(PhoneActivity.current.value)
    }
}
