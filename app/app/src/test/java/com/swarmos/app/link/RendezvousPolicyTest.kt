package com.swarmos.app.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RendezvousPolicyTest {
    private var now = 0L
    private val p = RendezvousPolicy(clock = { now })

    // ---- a client with stored credentials and a host that is not hosting (finding 9)

    private fun periodic() = p.decide("bbbb", "aaaa", role = "none", phase = "idle", hostsDirect = false, storedClient = true)
    private fun hostFlagHeard() = p.decide("bbbb", "aaaa", role = "none", phase = "idle", hostsDirect = true, storedClient = true)

    @Test fun afterTwoFailedJoinsTheClientStopsUntilTheHostFlagIsHeardAgain() {
        assertEquals(RendezvousStep.JOIN, periodic())
        p.noteTry("aaaa"); p.onJoinFailed("aaaa")
        now += 20_000
        assertEquals("one failure: try once more", RendezvousStep.JOIN, periodic())
        p.noteTry("aaaa"); p.onJoinFailed("aaaa")
        now += 20_000
        assertEquals("two failures: no more radio scans for a host that is not hosting", RendezvousStep.SKIP, periodic())
        now += 600_000
        assertEquals("still stopped ten minutes later", RendezvousStep.SKIP, periodic())
        assertEquals("the host flag is heard again", RendezvousStep.JOIN, hostFlagHeard())
        assertEquals("and the count starts over", 0, p.failuresOf("aaaa"))
    }

    @Test fun aSuccessfulJoinClearsTheFailures() {
        p.onJoinFailed("aaaa")
        p.onJoined("aaaa")
        assertEquals(0, p.failuresOf("aaaa"))
    }

    @Test fun joinsAreSpacedTwentySecondsApart() {
        assertEquals(RendezvousStep.JOIN, periodic())
        p.noteTry("aaaa")
        now += 19_999
        assertEquals(RendezvousStep.SKIP, periodic())
        now += 1
        assertEquals(RendezvousStep.JOIN, periodic())
    }

    @Test fun withoutStoredClientCredentialsAndWithoutTheHostFlagNothingIsTried() {
        assertEquals(RendezvousStep.SKIP, p.decide("bbbb", "aaaa", "none", "idle", hostsDirect = false, storedClient = false))
        assertEquals("the host flag alone is enough: the credentials are fetched from the host", RendezvousStep.JOIN,
            p.decide("bbbb", "aaaa", "none", "idle", hostsDirect = true, storedClient = false))
    }

    @Test fun aPhoneAlreadyInAGroupDoesNotJoinAgain() {
        assertEquals(RendezvousStep.SKIP, p.decide("bbbb", "aaaa", "client", "up", hostsDirect = true, storedClient = true))
        assertEquals(RendezvousStep.SKIP, p.decide("bbbb", "aaaa", "client", "starting", hostsDirect = true, storedClient = true))
    }

    // ---- two phones that both host (finding 10)

    @Test fun ofTwoHostsTheLargerNodeIdYieldsAndTheSmallerKeepsHosting() {
        assertEquals(RendezvousStep.YIELD_AND_JOIN, p.decide("bbbb", "aaaa", "host", "up", hostsDirect = true, storedClient = false))
        assertEquals(RendezvousStep.SKIP, p.decide("aaaa", "bbbb", "host", "up", hostsDirect = true, storedClient = false))
        assertTrue(p.mustYield("bbbb", "aaaa"))
        assertFalse(p.mustYield("aaaa", "bbbb"))
    }

    @Test fun exactlyOneOfTwoPhonesYields() {
        for ((a, b) in listOf("1a2b3c4d" to "9f8e7d6c", "00000001" to "00000002", "ffffffff" to "00000000")) {
            assertTrue("$a/$b", p.mustYield(a, b) != p.mustYield(b, a))
        }
    }

    @Test fun aHostDoesNotYieldToAPeerThatIsNotHosting() {
        assertEquals(RendezvousStep.SKIP, p.decide("bbbb", "aaaa", "host", "up", hostsDirect = false, storedClient = false))
    }

    @Test fun aYieldingHostIsAlsoSpacedByTheRetryGap() {
        assertEquals(RendezvousStep.YIELD_AND_JOIN, p.decide("bbbb", "aaaa", "host", "up", hostsDirect = true, storedClient = false))
        p.noteTry("aaaa")
        assertEquals(RendezvousStep.SKIP, p.decide("bbbb", "aaaa", "host", "up", hostsDirect = true, storedClient = false))
    }

    @Test fun afterYieldingTheNextHostFlagJoinsTheOtherGroup() {
        // the group is gone (state idle) and the stored host credentials were forgotten: only the host flag brings it in
        now += 25_000
        assertEquals(RendezvousStep.JOIN, p.decide("bbbb", "aaaa", "none", "idle", hostsDirect = true, storedClient = false))
    }
}
