package com.swarmos.app.pdu

import com.swarmos.app.core.SwarmJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two counters. The local one counts every committed change and stays on the device; the shared one is the only number another
 * device can read, and moves only when a change touches what a peer answer could contain. [IndexVersion.statements] is what the
 * store runs inside the transaction of each change, with the document's (or folder's) privacy before and after.
 */
class IndexVersionTest {
    private val local = listOf(IndexVersion.BUMP_SQL)
    private val both = listOf(IndexVersion.BUMP_SQL, IndexVersion.BUMP_SHARED_SQL)

    @Test fun anIndexThatHadNoChangeIsAtZero() {
        assertEquals(0L, IndexVersion.parse(null))
        assertEquals(0L, IndexVersion.parse(""))
        assertEquals(0L, IndexVersion.parse("garbage"))
        assertEquals(0L, IndexVersion.parse("-4"))
        assertEquals(41L, IndexVersion.parse("41"))
    }

    @Test fun eachCounterIsOneUpsertOnItsOwnKey() {
        // no read in the code, so two writers cannot lose an increment
        assertTrue(IndexVersion.BUMP_SQL.startsWith("INSERT INTO meta(k,v) VALUES('indexVersion','1')"))
        assertTrue(IndexVersion.BUMP_SHARED_SQL.startsWith("INSERT INTO meta(k,v) VALUES('sharedVersion','1')"))
        for (sql in listOf(IndexVersion.BUMP_SQL, IndexVersion.BUMP_SHARED_SQL)) assertTrue(sql.endsWith("ON CONFLICT(k) DO UPDATE SET v=CAST(v AS INTEGER)+1"))
        assertNotEquals(IndexVersion.KEY, IndexVersion.SHARED_KEY)
    }

    // ---- a document in a private folder ---------------------------------------------------------------------------

    @Test fun aDocumentAddedInAPrivateFolderRaisesOnlyTheLocalCounter() {
        assertEquals(local, IndexVersion.statements(oldPrivate = null, newPrivate = true))
    }

    @Test fun aDocumentReplacedInAPrivateFolderRaisesOnlyTheLocalCounter() {
        assertEquals(local, IndexVersion.statements(oldPrivate = true, newPrivate = true))
    }

    @Test fun aDocumentRemovedFromAPrivateFolderRaisesOnlyTheLocalCounter() {
        assertEquals(local, IndexVersion.statements(oldPrivate = true, newPrivate = null))
    }

    // ---- a document in a shared folder ------------------------------------------------------------------------------

    @Test fun aDocumentAddedReplacedOrRemovedInASharedFolderRaisesBoth() {
        assertEquals(both, IndexVersion.statements(null, false))
        assertEquals(both, IndexVersion.statements(false, false))
        assertEquals(both, IndexVersion.statements(false, null))
    }

    @Test fun aDocumentThatMovesBetweenSharedAndPrivateRaisesBoth() {
        assertEquals(both, IndexVersion.statements(oldPrivate = false, newPrivate = true)) // leaves what peers could see
        assertEquals(both, IndexVersion.statements(oldPrivate = true, newPrivate = false)) // enters it
    }

    // ---- a folder -----------------------------------------------------------------------------------------------------

    @Test fun switchingAFolderInEitherDirectionRaisesBoth() {
        assertTrue(IndexVersion.folderChanges(prior = 1, newValue = 0))
        assertEquals(both, IndexVersion.statements(oldPrivate = true, newPrivate = false)) // made shared
        assertTrue(IndexVersion.folderChanges(prior = 0, newValue = 1))
        assertEquals(both, IndexVersion.statements(oldPrivate = false, newPrivate = true)) // made private
    }

    @Test fun switchingAFolderToWhatItAlreadyIsRaisesNeitherCounter() {
        assertFalse(IndexVersion.folderChanges(prior = 0, newValue = 0))
        assertFalse(IndexVersion.folderChanges(prior = 1, newValue = 1))
        assertFalse(IndexVersion.folderChanges(prior = null, newValue = 1)) // a folder never seen is private
        assertTrue(IndexVersion.folderChanges(prior = null, newValue = 0))
    }

    @Test fun wipingForAReIndexRaisesTheSharedCounterOnlyWhenSomethingSharedWasThere() {
        assertEquals(both, IndexVersion.statements(oldPrivate = false, newPrivate = null))
        assertEquals(local, IndexVersion.statements(oldPrivate = true, newPrivate = null))
    }

    @Test fun everyChangeRaisesTheLocalCounter() {
        val states = listOf<Boolean?>(null, true, false)
        for (o in states) for (n in states) assertTrue("$o -> $n", IndexVersion.BUMP_SQL in IndexVersion.statements(o, n))
    }

    @Test fun theSharedCounterNeverMovesForAChangeWhollyInsideAPrivateFolder() {
        for (o in listOf<Boolean?>(null, true)) for (n in listOf<Boolean?>(null, true)) {
            assertFalse("$o -> $n", IndexVersion.touchesShared(o, n))
        }
    }

    // ---- what leaves the device ---------------------------------------------------------------------------------------

    private val counters = VersionCounters(local = 4_001, shared = 17)

    @Test fun onlyTheSharedCounterIsForPeers() {
        assertEquals(17L, counters.forPeers)
    }

    @Test fun theVersionRouteAnswersTheSharedCounter() {
        val json = SwarmJson.encodeToString(PduVersion.of(counters))
        assertEquals("""{"indexVersion":17}""", json)
        assertFalse("4001" in json)
    }

    @Test fun thePeerQueryResponseCarriesTheSharedCounter() {
        val r = PeerResponder.respond("q", emptyList(), emptyList(), 5, 10, 3, versions = counters)
        assertEquals(17L, r.indexVersion)
        assertFalse("4001" in SwarmJson.encodeToString(r))
    }

    @Test fun theGraphAnswerCarriesTheSharedCounter() {
        val r = GraphAnswers.answer(GraphQuery("q", "match", "x"), FakeGraph(), includePrivate = false, versions = counters)
        assertEquals(17L, r.indexVersion)
        assertFalse("4001" in SwarmJson.encodeToString(r))
    }

    @Test fun noCountersMeansNoVersionInTheAnswer() {
        assertNull(PeerResponder.respond("q", emptyList(), emptyList(), 5, 10, 3).indexVersion)
        assertNull(GraphAnswers.answer(GraphQuery("q", "match", "x"), FakeGraph(), false).indexVersion)
    }

    @Test fun theVersionIsOptionalOnTheWire() {
        val oldPeer = """{"qid":"q","hits":[],"indexedChunks":4,"tookMs":2,"withheldPrivate":1}"""
        val r = SwarmJson.decodeFromString<PduResponse>(oldPeer)
        assertNull(r.indexVersion)
        assertEquals(1, r.withheldPrivate)
        val newPeer = SwarmJson.decodeFromString<PduResponse>(SwarmJson.encodeToString(r.copy(indexVersion = 9)))
        assertEquals(9L, newPeer.indexVersion)
    }

    @Test fun aResponseWithTheVersionSurvivesTheCapOnReceipt() {
        assertEquals(5L, PduResponse("q", emptyList(), 1, 1, 0, indexVersion = 5).capped().indexVersion)
    }
}
