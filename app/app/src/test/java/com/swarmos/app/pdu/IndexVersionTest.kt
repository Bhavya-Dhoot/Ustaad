package com.swarmos.app.pdu

import com.swarmos.app.core.SwarmJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The index version: what the counter says, what carries it, and that an older peer's answer without it still reads. */
class IndexVersionTest {
    @Test fun anIndexThatHadNoChangeIsAtZero() {
        assertEquals(0L, IndexVersion.parse(null))
        assertEquals(0L, IndexVersion.parse(""))
        assertEquals(0L, IndexVersion.parse("garbage"))
        assertEquals(0L, IndexVersion.parse("-4"))
    }

    @Test fun aStoredCounterReadsBack() {
        assertEquals(41L, IndexVersion.parse("41"))
    }

    @Test fun theBumpIsOneStatementThatRunsInsideTheChangesOwnTransaction() {
        // the statement is an upsert on the meta table: no read in the code, so two writers cannot lose an increment
        assertTrue(IndexVersion.BUMP_SQL.startsWith("INSERT INTO meta(k,v) VALUES('indexVersion','1')"))
        assertTrue(IndexVersion.BUMP_SQL.contains("ON CONFLICT(k) DO UPDATE SET v=CAST(v AS INTEGER)+1"))
    }

    @Test fun sharingAFolderChangesTheVersionAndSharingItAgainDoesNot() {
        assertTrue(IndexVersion.folderChanges(prior = 1, newValue = 0)) // private to shared
        assertFalse(IndexVersion.folderChanges(prior = 0, newValue = 0)) // shared to shared
    }

    @Test fun makingAFolderPrivateChangesTheVersion() {
        assertTrue(IndexVersion.folderChanges(prior = 0, newValue = 1))
    }

    @Test fun aFolderNeverSeenIsPrivateSoMakingItPrivateChangesNothing() {
        assertFalse(IndexVersion.folderChanges(prior = null, newValue = 1))
        assertTrue(IndexVersion.folderChanges(prior = null, newValue = 0))
    }

    @Test fun thePeerResponseCarriesTheVersionWhenAsked() {
        val r = PeerResponder.respond("q", emptyList(), emptyList(), 5, 10, 3, indexVersion = 17)
        assertEquals(17L, r.indexVersion)
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

    @Test fun theVersionRouteAnswersAPlainCounter() {
        assertEquals("""{"indexVersion":12}""", SwarmJson.encodeToString(PduVersion(12)))
    }
}
