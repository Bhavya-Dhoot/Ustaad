package com.swarmos.app.pdu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding C21: the last file of a shared folder was deleted and its text stayed searchable, because an empty listing never removed
 * anything. The rule now tells an empty folder (the listing succeeded) from a failed listing, in every combination of
 * what was listed, what is stored and whether the listing succeeded.
 */
class LastFileRuleTest {
    private fun doc(uri: String) = StoredDoc("id-$uri", uri, "text", "Documents")
    private val two = listOf(doc("a"), doc("b"))

    private fun uris(r: List<StoredDoc>?) = r?.map { it.uri }

    // listed nothing ---------------------------------------------------------------------------------------------

    @Test fun anEmptyFolderThatWasListedFineRemovesWhatWasStoredUnderIt() {
        assertEquals(listOf("a", "b"), uris(IndexSweep.toRemove(two, emptySet(), listingSucceeded = true)))
    }

    @Test fun anEmptyListingThatFailedRemovesNothing() {
        assertNull(IndexSweep.toRemove(two, emptySet(), listingSucceeded = false))
    }

    @Test fun nothingStoredAndNothingListedIsNothingToRemove() {
        assertEquals(emptyList<StoredDoc>(), IndexSweep.toRemove(emptyList(), emptySet(), listingSucceeded = true))
        assertEquals(emptyList<StoredDoc>(), IndexSweep.toRemove(emptyList(), emptySet(), listingSucceeded = false))
    }

    // listed something -------------------------------------------------------------------------------------------

    @Test fun aListingThatSucceededRemovesOnlyWhatItDidNotSee() {
        assertEquals(listOf("b"), uris(IndexSweep.toRemove(two, setOf("a", "new"), listingSucceeded = true)))
    }

    @Test fun aListingThatSawEverythingStoredRemovesNothing() {
        assertTrue(IndexSweep.toRemove(two, setOf("a", "b"), listingSucceeded = true)!!.isEmpty())
    }

    @Test fun aListingThatFailedHalfWayRemovesNothingEvenWithSomeFilesSeen() {
        assertNull(IndexSweep.toRemove(two, setOf("a"), listingSucceeded = false))
    }

    @Test fun somethingListedAndNothingStoredIsNothingToRemove() {
        assertTrue(IndexSweep.toRemove(emptyList(), setOf("a"), listingSucceeded = true)!!.isEmpty())
        assertTrue(IndexSweep.toRemove(emptyList(), setOf("a"), listingSucceeded = false)!!.isEmpty())
    }

    @Test fun aListingIsASuccessUnlessItSaysOtherwise() {
        assertTrue(Listing(emptyList()).succeeded)
        assertFalse(Listing(emptyList(), succeeded = false).succeeded)
    }
}
