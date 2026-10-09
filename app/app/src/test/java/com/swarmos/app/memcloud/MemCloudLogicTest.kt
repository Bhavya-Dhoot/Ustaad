package com.swarmos.app.memcloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** State-machine decisions for two phones (driver plus one worker) and for three (driver plus two). */
class MemCloudLogicTest {
    private fun share(id: String, name: String, driver: Boolean, first: Int, n: Int, mb: Long) =
        ShareView(id, name, driver, first, n, if (n == 0) "none" else "$first-${first + n - 1}", mb, mb + 1536, 9000, true)

    private fun phone(id: String, name: String, driver: Boolean, frac: Float = 0f) =
        PhoneView(id, name, driver, "x", loadFraction = frac)

    private val threeShares = listOf(
        share("a", "Phone A", true, 0, 16, 6000), share("b", "Phone B", false, 16, 16, 6000), share("c", "Phone C", false, 32, 16, 6000),
    )

    private fun ready(phones: List<PhoneView>, shares: List<ShareView> = threeShares) = MemCloudState(
        phase = Phase.READY, file = "m.gguf", shares = shares, phones = phones,
        generation = GenerationView("hi", text = "Hello", running = true),
    )

    @Test fun aLostPhoneIsFoundAmongTheParticipants() {
        assertEquals(listOf("b"), MemCloudLogic.lostParticipants(listOf("b", "c"), setOf("c", "x")))
        assertEquals(emptyList<String>(), MemCloudLogic.lostParticipants(listOf("b", "c"), setOf("b", "c")))
        assertEquals(listOf("b"), MemCloudLogic.lostParticipants(listOf("b"), emptySet())) // two-phone split, the only worker gone
    }

    @Test fun degradedNamesThePhoneAndStopsTheStreamInAThreePhoneSplit() {
        val s = ready(listOf(phone("a", "Phone A", true), phone("b", "Phone B", false), phone("c", "Phone C", false)))
        val d = MemCloudLogic.degraded(s, "b", "Phone B", "heartbeat lost", 5)!!
        assertEquals(Phase.DEGRADED, d.phase)
        assertEquals("Phone B", d.lostPhone)
        assertEquals(listOf(true, false, true), d.phones.map { it.online })
        assertFalse(d.generation!!.running)
        assertTrue(d.generation!!.error!!.contains("Phone B"))
        assertTrue(d.steps.last().contains("Phone B lost"))
    }

    @Test fun degradedWorksForATwoPhoneSplit() {
        val shares = listOf(share("c", "Phone C", true, 0, 18, 1500), share("b", "Phone B", false, 18, 18, 1500))
        val s = ready(listOf(phone("c", "Phone C", true), phone("b", "Phone B", false)), shares)
        val d = MemCloudLogic.degraded(s, "b", "Phone B", "rpc-server stopped", 5)!!
        assertEquals("Phone B", d.lostPhone)
        assertEquals(listOf(true, false), d.phones.map { it.online })
    }

    @Test fun degradedAlsoAppliesWhileLoadingButNotWhenIdleOrAlreadyDegraded() {
        val loading = ready(listOf(phone("b", "Phone B", false))).copy(phase = Phase.LOADING)
        assertNotNull(MemCloudLogic.degraded(loading, "b", "Phone B", "x", 1))
        assertNull(MemCloudLogic.degraded(loading.copy(phase = Phase.IDLE), "b", "Phone B", "x", 1))
        assertNull(MemCloudLogic.degraded(loading.copy(phase = Phase.DEGRADED), "b", "Phone B", "x", 1))
        assertNull(MemCloudLogic.degraded(loading.copy(phase = Phase.FAILED), "b", "Phone B", "x", 1))
    }

    @Test fun replanKeepsThePhonesThatAreStillOnline() {
        assertEquals(listOf("c"), MemCloudLogic.replanPeers(listOf("b", "c"), listOf("c")))
        assertEquals(listOf("b", "c"), MemCloudLogic.replanPeers(listOf("b", "c"), listOf("b", "c", "d")))
        assertEquals(listOf("c"), MemCloudLogic.replanPeers(listOf("b", "c"), listOf("c", "d"), requested = listOf("b", "c")))
        assertEquals(emptyList<String>(), MemCloudLogic.replanPeers(listOf("b"), emptyList()))
        assertFalse(MemCloudLogic.canReplan(0))
        assertTrue(MemCloudLogic.canReplan(1))
    }

    @Test fun threePhonesDropToTwoAndThePlanStillHoldsTogether() {
        val info = GgufInfo("m.gguf", 1, "qwen3moe", "M", 48, 2048, 4096, 128, 8, 0, List(48) { 380_000_000L }, 300_000_000L, 200_000_000L)
        fun dev(i: Int, ram: Long) = PlanDevice("n$i", "Phone ${'A' + i}", i == 0, ram, if (i == 0) null else "10.0.0.${i + 1}")
        val three = SplitPlanner.plan(info, listOf(dev(0, 14000), dev(1, 14000), dev(2, 14000)))
        assertEquals(3, three.shares.size)
        val two = SplitPlanner.plan(info, listOf(dev(0, 14000), dev(2, 14000)))
        assertEquals(listOf("0-23", "24-47"), two.shares.map { it.range })
        assertTrue(SplitPlanner.argsMatchPlan(two, SplitPlanner.serverArgs(two, 50052)))
        assertEquals(2, two.boundariesPerToken())
        assertEquals(3, three.boundariesPerToken())
        assertTrue(two.fitsAll())
    }

    @Test fun anyNumberOfPeersGetsAValidPlan() {
        val info = GgufInfo("m.gguf", 1, "qwen3", "M", 36, 2560, 4096, 0, 0, 0, List(36) { 70_000_000L }, 100_000_000L, 100_000_000L)
        for (peers in 1..4) {
            val devs = (0..peers).map { PlanDevice("n$it", "Phone ${'A' + it}", it == 0, 9000, if (it == 0) null else "10.0.0.${it + 1}") }
            val p = SplitPlanner.plan(info, devs)
            assertEquals(36, p.counts().sum())
            assertTrue("peers=$peers", SplitPlanner.argsMatchPlan(p, SplitPlanner.serverArgs(p, 50052)))
            assertEquals(36, p.assignments().count { it != null })
        }
    }

    @Test fun loadFractionFollowsTheBytesReceived() {
        assertEquals(0.5f, MemCloudLogic.loadFraction(500, 1000L * 1024 * 1024)!!, 1e-6f)
        assertEquals(1f, MemCloudLogic.loadFraction(5000, 1000L * 1024 * 1024)!!, 1e-6f)
        assertNull(MemCloudLogic.loadFraction(null, 1000))
        assertNull(MemCloudLogic.loadFraction(10, 0))
    }

    @Test fun overallPercentWeighsPeersByTheirPlannedBytes() {
        val phones = listOf(phone("a", "Phone A", true), phone("b", "Phone B", false, 0.5f), phone("c", "Phone C", false, 0f))
        assertEquals(25, MemCloudLogic.overallPercent(threeShares, phones))
        assertEquals(0, MemCloudLogic.overallPercent(threeShares, emptyList()))
        // two phones: one remote peer at 100 %
        val two = listOf(share("c", "Phone C", true, 0, 18, 1500), share("b", "Phone B", false, 18, 18, 1500))
        assertEquals(100, MemCloudLogic.overallPercent(two, listOf(phone("b", "Phone B", false, 1f))))
    }

    @Test fun loadedLayersFillFromThePeersProgress() {
        val phones = listOf(phone("a", "Phone A", true), phone("b", "Phone B", false, 0.5f), phone("c", "Phone C", false, 0f))
        val flags = MemCloudLogic.loadedLayers(threeShares, phones, ready = false, nLayers = 48)
        assertEquals(48, flags.size)
        assertEquals(8, flags.subList(16, 32).count { it })   // half of Phone B's 16 layers
        assertEquals(0, flags.subList(32, 48).count { it })   // Phone C has received nothing
        assertEquals(4, flags.subList(0, 16).count { it })    // driver follows the overall 25 %
        assertTrue(MemCloudLogic.loadedLayers(threeShares, phones, ready = true, nLayers = 48).all { it })
    }

    @Test fun theActivityLineFollowsThePhaseForTwoAndThreePhones() {
        val two = listOf(share("c", "Phone C", true, 0, 18, 1500), share("a", "Phone A", false, 18, 18, 1500))
        val phonesTwo = listOf(phone("c", "Phone C", true), phone("a", "Phone A", false, 0.41f))
        val loading = MemCloudState(phase = Phase.LOADING, shares = two, phones = phonesTwo)
        assertEquals("Loading a large model across 2 phones (41 percent).", MemCloudLogic.activityText(loading))
        assertEquals("Running a large model with Phone A.", MemCloudLogic.activityText(loading.copy(phase = Phase.READY)))
        val three = MemCloudState(
            phase = Phase.READY, shares = threeShares,
            phones = listOf(phone("a", "Phone A", true), phone("b", "Phone B", false), phone("c", "Phone C", false)),
        )
        assertEquals("Running a large model with Phone B and Phone C.", MemCloudLogic.activityText(three))
        assertEquals("A large model is paused: Phone B stopped answering.", MemCloudLogic.activityText(three.copy(phase = Phase.DEGRADED, lostPhone = "Phone B")))
        assertEquals("Preparing a large model across phones.", MemCloudLogic.activityText(MemCloudState(phase = Phase.PLANNING)))
        assertNull(MemCloudLogic.activityText(MemCloudState(phase = Phase.IDLE)))
        assertNull(MemCloudLogic.activityText(MemCloudState(phase = Phase.FAILED)))
    }

    @Test fun aNewLoadIsRefusedWhileASplitIsServingOrDegraded() {
        assertFalse(MemCloudLogic.canStart(Phase.READY))
        assertFalse(MemCloudLogic.canStart(Phase.DEGRADED))
        assertTrue(MemCloudLogic.canStart(Phase.IDLE))
        assertTrue(MemCloudLogic.canStart(Phase.FAILED))
    }
}
