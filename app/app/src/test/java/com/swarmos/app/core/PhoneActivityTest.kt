package com.swarmos.app.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneActivityTest {
    @After
    fun reset() = PhoneActivity.clearAll()

    @Test
    fun idleWhenNothingIsSet() {
        assertNull(PhoneActivity.current.value)
    }

    @Test
    fun theNewestOwnerIsShownAndTheOneBeforeComesBackWhenItClears() {
        PhoneActivity.set("choir", "Running a 20-problem check started from Phone A (1 of 20).")
        PhoneActivity.set("memcloud", "Lending memory to Phone C.")
        assertEquals("Lending memory to Phone C.", PhoneActivity.current.value?.text)
        PhoneActivity.clear("memcloud")
        assertEquals("Running a 20-problem check started from Phone A (1 of 20).", PhoneActivity.current.value?.text)
        PhoneActivity.clear("choir")
        assertNull(PhoneActivity.current.value)
    }

    @Test
    fun anOwnerIsActiveWhileItHasAnEntryEvenWhenAnotherOwnerIsOnShow() {
        PhoneActivity.set("pdu", "Indexing 12 files.")
        PhoneActivity.set("choir", "Running a 20-problem check (1 of 20).")
        assertEquals("Running a 20-problem check (1 of 20).", PhoneActivity.current.value?.text)
        assertEquals(true, PhoneActivity.isActive("pdu"))
        PhoneActivity.clear("pdu")
        assertEquals(false, PhoneActivity.isActive("pdu"))
        assertEquals(false, PhoneActivity.isActive("nobody"))
    }

    @Test
    fun progressFromTheSameOwnerReplacesItsTextInPlace() {
        PhoneActivity.set("choir", "Running a 20-problem check (6 of 20).")
        PhoneActivity.set("pdu", "Indexing 12 files.")
        PhoneActivity.set("choir", "Running a 20-problem check (7 of 20).")
        // choir kept its place below pdu: updating it does not push it on top
        assertEquals("Indexing 12 files.", PhoneActivity.current.value?.text)
        PhoneActivity.clear("pdu")
        assertEquals("Running a 20-problem check (7 of 20).", PhoneActivity.current.value?.text)
    }

    @Test
    fun theStopActionTravelsWithTheEntry() {
        var stopped = false
        PhoneActivity.set("choir", "Running a check.", onStop = { stopped = true })
        PhoneActivity.current.value?.onStop?.invoke()
        assertEquals(true, stopped)
        PhoneActivity.set("pdu", "Indexing.")
        assertNull(PhoneActivity.current.value?.onStop)
        assertNotNull(PhoneActivity.current.value)
    }

    @Test
    fun clearingAnOwnerThatIsNotThereChangesNothing() {
        PhoneActivity.set("choir", "Running a check.")
        PhoneActivity.clear("pdu")
        assertEquals("Running a check.", PhoneActivity.current.value?.text)
    }

    @Test
    fun serviceLineReadsInPlainWords() {
        assertEquals("2 phones connected. Chat model ready.", ServiceLine.describe(2, ServiceLine.Chat.READY, null))
        assertEquals("1 phone connected. Chat model loading.", ServiceLine.describe(1, ServiceLine.Chat.LOADING, null))
        assertEquals("No other phones connected. No chat model loaded.", ServiceLine.describe(0, ServiceLine.Chat.NONE, null))
        assertEquals("No other phones connected. Chat model failed.", ServiceLine.describe(0, ServiceLine.Chat.FAILED, " "))
    }

    @Test
    fun anActivityReplacesTheStateLine() {
        val text = "Running a 20-problem check started from Phone A (7 of 20)."
        assertEquals(text, ServiceLine.describe(2, ServiceLine.Chat.READY, text))
    }
}
