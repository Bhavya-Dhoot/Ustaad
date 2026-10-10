package com.swarmos.app.pdu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The half-written case of a folder switch: the folder row says private, the chunk rows were not updated before a kill.
 * [SharePolicy.foldersToForce] is what the start-up repair of PduStore applies to the folders that still hold public chunks.
 */
class PrivacyRepairTest {
    @Test fun aFolderStoredAsPrivateIsForcedWhenItsChunksAreStillPublic() {
        val force = SharePolicy.foldersToForce(listOf("Pictures/Bills", "Documents"), mapOf("Pictures/Bills" to true, "Documents" to false))
        assertEquals(listOf("Pictures/Bills"), force)
    }

    @Test fun aSharedFolderKeepsItsPublicChunks() {
        assertTrue(SharePolicy.foldersToForce(listOf("Documents"), mapOf("Documents" to false)).isEmpty())
    }

    @Test fun aFolderUnderAFolderNamedPrivateIsForcedWhateverTheStoredChoice() {
        val force = SharePolicy.foldersToForce(listOf("Documents/Private/Tax", "Documents/private"), mapOf("Documents/Private/Tax" to false, "Documents/private" to false))
        assertEquals(listOf("Documents/Private/Tax", "Documents/private"), force)
    }

    @Test fun aFolderWithNoStoredChoiceStartsPrivate() {
        assertEquals(listOf("Pictures/New"), SharePolicy.foldersToForce(listOf("Pictures/New"), emptyMap()))
    }

    @Test fun eachFolderIsListedOnce() {
        assertEquals(listOf("A"), SharePolicy.foldersToForce(listOf("A", "A", "A"), mapOf("A" to true)))
    }

    @Test fun nothingToRepairWhenNoPublicChunksRemain() {
        assertTrue(SharePolicy.foldersToForce(emptyList(), mapOf("A" to true)).isEmpty())
    }
}
