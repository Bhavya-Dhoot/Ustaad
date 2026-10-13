package com.swarmos.app.hands.android

import com.swarmos.app.hands.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BrainRouteTest {
    private val bools = listOf(false, true)

    private fun pick(
        tier: Tier,
        onPhoneOnly: Boolean = false,
        local: Boolean = false,
        peer: Boolean = false,
        stronger: Boolean = false,
        share: Boolean = false,
        test: Boolean = false,
    ) = BrainRoute.pick(tier, onPhoneOnly, local, peer, stronger, share, test)

    /** Every combination of the seven inputs, for the rules that must hold for all of them. */
    private fun everyCase(check: (Tier, Boolean, Boolean, Boolean, Boolean, Boolean, Boolean) -> Unit) {
        for (tier in Tier.values()) for (a in bools) for (b in bools) for (c in bools) for (d in bools) for (e in bools) for (f in bools) check(tier, a, b, c, d, e, f)
    }

    @Test
    fun testBrainTakesEverything() = everyCase { tier, onPhoneOnly, local, peer, stronger, share, test ->
        if (test) assertEquals(Route.TEST, BrainRoute.pick(tier, onPhoneOnly, local, peer, stronger, share, test))
    }

    @Test
    fun noTestBrainNeverGivesTest() = everyCase { tier, onPhoneOnly, local, peer, stronger, share, test ->
        if (!test) assertNotEquals(Route.TEST, BrainRoute.pick(tier, onPhoneOnly, local, peer, stronger, share, test))
    }

    @Test
    fun aScreenPromptWithSharingOffNeverGoesToTheSwarm() = everyCase { tier, _, local, peer, stronger, _, test ->
        if (test) return@everyCase
        val r = BrainRoute.pick(tier, onPhoneOnly = true, localReady = local, peerHasModel = peer, peerIsStronger = stronger, shareScreens = false, testBrain = false)
        assertNotEquals("tier=$tier local=$local peer=$peer stronger=$stronger", Route.SWARM, r)
        assertEquals(if (local) Route.LOCAL else Route.NONE, r)
    }

    @Test
    fun aScreenPromptWithSharingOffStaysLocalEvenWhenAStrongerPeerExists() {
        assertEquals(Route.LOCAL, pick(Tier.STRONG, onPhoneOnly = true, local = true, peer = true, stronger = true))
        assertEquals(Route.NONE, pick(Tier.STRONG, onPhoneOnly = true, local = false, peer = true, stronger = true))
        assertEquals(Route.NONE, pick(Tier.FAST, onPhoneOnly = true, local = false, peer = true))
    }

    @Test
    fun aScreenPromptWithSharingOnFollowsTheTierRules() = everyCase { tier, _, local, peer, stronger, _, test ->
        if (test) return@everyCase
        val shared = BrainRoute.pick(tier, onPhoneOnly = true, localReady = local, peerHasModel = peer, peerIsStronger = stronger, shareScreens = true, testBrain = false)
        val plain = BrainRoute.pick(tier, onPhoneOnly = false, localReady = local, peerHasModel = peer, peerIsStronger = stronger, shareScreens = false, testBrain = false)
        assertEquals(plain, shared)
    }

    @Test
    fun fastPrefersThisPhoneThenTheSwarmThenNothing() {
        assertEquals(Route.LOCAL, pick(Tier.FAST, local = true, peer = true, stronger = true))
        assertEquals(Route.LOCAL, pick(Tier.FAST, local = true))
        assertEquals(Route.SWARM, pick(Tier.FAST, local = false, peer = true))
        assertEquals(Route.NONE, pick(Tier.FAST, local = false, peer = false))
        // "stronger" without a peer is not a peer
        assertEquals(Route.NONE, pick(Tier.FAST, local = false, peer = false, stronger = true))
    }

    @Test
    fun strongGoesToAStrongerPeerOrToAPeerWhenThisPhoneHasNoModel() {
        assertEquals(Route.SWARM, pick(Tier.STRONG, local = true, peer = true, stronger = true))
        assertEquals(Route.SWARM, pick(Tier.STRONG, local = false, peer = true))
        assertEquals(Route.SWARM, pick(Tier.STRONG, local = false, peer = true, stronger = true))
    }

    @Test
    fun strongStaysHereWhenNoPeerIsKnownToBeStronger() {
        assertEquals(Route.LOCAL, pick(Tier.STRONG, local = true, peer = true, stronger = false))
        assertEquals(Route.LOCAL, pick(Tier.STRONG, local = true, peer = false))
        assertEquals(Route.NONE, pick(Tier.STRONG, local = false, peer = false))
        assertEquals(Route.NONE, pick(Tier.STRONG, local = false, peer = false, stronger = true))
    }
}
