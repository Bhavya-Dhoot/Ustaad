package com.swarmos.app.kb

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private const val DAY = 24L * 60 * 60 * 1000
private const val T0 = 1_760_000_100_000L

/** A store that can roll back, count reads and fail on demand: what the SQLite store does with its transaction. */
private class TxStore : KbBacking {
    val cards = LinkedHashMap<String, KbEntry>()
    val tombs = LinkedHashMap<String, KbTombstone>()
    var allCalls = 0
    var failTombstonePut = false
    var failReplaceAt = Int.MAX_VALUE
    private var replaces = 0
    var failPutAfter = Int.MAX_VALUE
    private var puts = 0

    override fun entry(id: String) = cards[id]
    override fun put(e: KbEntry) { if (++puts > failPutAfter) throw IOException("disk"); cards[e.card.id] = e }
    override fun all(): List<KbEntry> { allCalls++; return cards.values.toList() }
    override fun tombstone(id: String) = tombs[id]
    override fun putTombstone(t: KbTombstone) { if (failTombstonePut) throw IOException("disk"); tombs[t.id] = t }
    override fun tombstones() = tombs.values.toList()
    override fun <T> atomic(block: () -> T): T {
        val c = LinkedHashMap(cards)
        val t = LinkedHashMap(tombs)
        try {
            return block()
        } catch (e: Throwable) {
            cards.clear(); cards.putAll(c); tombs.clear(); tombs.putAll(t)
            throw e
        }
    }
    override fun countUsable() = cards.values.count { it.usable }
    override fun replaceCard(card: KbCard) {
        if (++replaces >= failReplaceAt) throw IOException("disk")
        cards[card.id]?.let { cards[card.id] = it.copy(card = card) }
    }
    override fun deleteTombstones(ids: Collection<String>) { ids.forEach { tombs.remove(it) } }
}

private class Node(val id: String, val name: String, var secret: String = "s1") {
    var now = T0
    val store = TxStore()
    val audit = ArrayList<String>()
    val svc = KbService(store, { secret }, { id }, { name }, { kind, _ -> audit += kind; "h${audit.size}" }, { now })

    fun save(share: Boolean, q: String = "q $id ${store.cards.size}?"): KbEntry =
        (svc.save(q, "answer [1]", listOf(KbSource(name, "file.pdf", 1_000, false)), share) as KbSave.Saved).entry
}

private class Wire(private val remote: KbService) : KbPeer {
    override suspend fun offer(o: KbOffer) = remote.handleOffer(o)
    override suspend fun cards(c: KbCards) = remote.handleCards(c)
}

private fun sync(from: Node, to: Node) = runBlocking { KbSync.run(from.svc, Wire(to.svc)) }

class KbHardeningTest {
    // ---- finding 7: the mark and the tombstone are one transaction ---------------------------------------------

    @Test fun aRevocationWhoseTombstoneCannotBeWrittenLeavesTheCardUsable() {
        val a = Node("a", "Phone A")
        val c = a.save(share = true)
        a.store.failTombstonePut = true
        try {
            a.svc.revoke(c.card.id)
            org.junit.Assert.fail("expected the write to fail")
        } catch (_: IOException) {
        }
        assertTrue("not marked revoked without its tombstone", a.store.cards[c.card.id]!!.usable)
        assertTrue(a.store.tombs.isEmpty())
        a.store.failTombstonePut = false
        assertNull(a.svc.revoke(c.card.id))
        assertFalse(a.store.cards[c.card.id]!!.usable)
        assertNotNull(a.store.tombs[c.card.id])
    }

    @Test fun aReceivedTombstoneAndTheMarkOnTheCardGoTogether() {
        val a = Node("a", "Phone A")
        val b = Node("b", "Phone B")
        val c = a.save(share = true)
        sync(a, b)
        assertNull(a.svc.revoke(c.card.id))
        val t = a.store.tombs[c.card.id]!!
        b.store.failPutAfter = b.store.cards.size // the card was put once during the first sync; any further put fails
        try {
            b.svc.ingestTombstone(t)
            org.junit.Assert.fail("expected the write to fail")
        } catch (_: IOException) {
        }
        assertTrue("no tombstone without the mark", b.store.tombs.isEmpty())
        assertTrue(b.store.cards[c.card.id]!!.usable)
    }

    @Test fun startUpRepairGivesARevokedSharedCardItsMissingTombstone() {
        val a = Node("a", "Phone A")
        val c = a.save(share = true)
        a.store.cards[c.card.id] = c.copy(revokedMs = T0 + 5) // killed between the two writes of an older build
        assertTrue(a.store.tombs.isEmpty())
        assertEquals(1, a.svc.repair())
        val t = a.store.tombs[c.card.id]!!
        assertEquals(T0 + 5, t.revokedMs)
        assertTrue(KbCanon.tombstoneSealedBy(t, "s1"))
        assertEquals("a second repair has nothing to do", 0, a.svc.repair())
    }

    @Test fun startUpRepairMarksACardThatHasItsAuthorsTombstoneButIsStillUsable() {
        val a = Node("a", "Phone A")
        val b = Node("b", "Phone B")
        val c = a.save(share = true)
        sync(a, b)
        a.svc.revoke(c.card.id)
        b.store.tombs[c.card.id] = a.store.tombs[c.card.id]!! // the tombstone arrived, the mark did not
        assertTrue(b.store.cards[c.card.id]!!.usable)
        assertEquals(1, b.svc.repair())
        assertFalse(b.store.cards[c.card.id]!!.usable)
        assertEquals(a.store.tombs[c.card.id]!!.revokedMs, b.store.cards[c.card.id]!!.revokedMs)
    }

    @Test fun repairTrustsOnlyATombstoneThatVerifiesAndComesFromTheAuthor() {
        val b = Node("b", "Phone B")
        val a = Node("a", "Phone A")
        val c = a.save(share = true)
        sync(a, b)
        b.store.tombs[c.card.id] = KbCanon.tombstone("another-secret", c.card.id, "a", T0 + 1)
        assertEquals(0, b.svc.repair())
        b.store.tombs[c.card.id] = KbCanon.tombstone("s1", c.card.id, "x", T0 + 1)
        assertEquals(0, b.svc.repair())
        assertTrue(b.store.cards[c.card.id]!!.usable)
    }

    // ---- finding 9: counting, offering and keeping --------------------------------------------------------------

    @Test fun savingDoesNotParseEveryCardToCountThem() {
        val a = Node("a", "Phone A")
        repeat(5) { a.save(share = false) }
        assertEquals("the count is the database's, not a read of every card", 0, a.store.allCalls)
    }

    @Test fun revokedCardsStopCountingTowardsTheCap() {
        val a = Node("a", "Phone A")
        val first = a.save(share = false)
        repeat(KbLimits.MAX_CARDS - 1) { a.save(share = false) }
        assertTrue((a.svc.save("one more?", "a", listOf(KbSource("Phone A", "f", 1, false)), false) as KbSave.Refused).reason.contains("full"))
        assertNull(a.svc.revoke(first.card.id))
        assertTrue("a revoked card no longer holds a place", a.svc.save("one more?", "a", listOf(KbSource("Phone A", "f", 1, false)), false) is KbSave.Saved)
    }

    @Test fun tombstonesAreOfferedBeforeCardsAndTheCutCanOnlyDropCards() {
        val a = Node("a", "Phone A")
        val live = ArrayList<KbCard>()
        repeat(KbLimits.MAX_OFFER_ITEMS + 50) { live += KbCanon.make("s1", "q$it?", "a", listOf(KbSource("Phone Z", "f", 1, false)), "z", "Phone Z", T0 - 1000 - it, "h$it") }
        for (c in live) a.store.cards[c.id] = KbEntry(c, KbTier.SWARM, null, true)
        val tomb = KbCanon.tombstone("s1", "f".repeat(64), "z", T0 - 10)
        a.store.tombs[tomb.id] = tomb
        val offer = a.svc.offerItems()
        assertEquals(KbLimits.MAX_OFFER_ITEMS, offer.size)
        assertEquals(tomb.id, offer.first().id)
        assertEquals(tomb.revokedMs, offer.first().revokedMs)
    }

    @Test fun manyTombstonesNewestAreOfferedFirstAndNoCardIsLeftInThePlaceOfARevocation() {
        val a = Node("a", "Phone A")
        repeat(KbLimits.MAX_OFFER_ITEMS + 5) { i ->
            val t = KbCanon.tombstone("s1", "%064x".format(i), "z", T0 - 100 - i)
            a.store.tombs[t.id] = t
        }
        a.save(share = true)
        val offer = a.svc.offerItems()
        assertTrue(offer.all { it.revokedMs != null })
        assertEquals(T0 - 100, offer.first().revokedMs)
    }

    // the rule: a tombstone is dropped only after the horizon, and a card older than the horizon is not taken
    @Test fun aCardOlderThanTheHorizonIsNotTakenAndAYoungerOneIs() {
        val b = Node("b", "Phone B")
        fun card(age: Long) = KbCanon.make("s1", "q$age?", "a", listOf(KbSource("Phone A", "f", 1, false)), "a", "Phone A", T0 - age, "h")
        assertEquals("older than the knowledge bank keeps", b.svc.ingestCard(card(KbLimits.HORIZON_MS + DAY)))
        assertNull(b.svc.ingestCard(card(KbLimits.HORIZON_MS - DAY)))
    }

    @Test fun anExpiredTombstoneIsPurgedAndAYoungOneIsKept() {
        val a = Node("a", "Phone A")
        val old = KbCanon.tombstone("s1", "1".repeat(64), "a", T0 - KbLimits.HORIZON_MS - DAY)
        val young = KbCanon.tombstone("s1", "2".repeat(64), "a", T0 - KbLimits.HORIZON_MS + DAY)
        a.store.tombs[old.id] = old
        a.store.tombs[young.id] = young
        assertEquals(1, a.svc.purgeExpiredTombstones())
        assertEquals(setOf(young.id), a.store.tombs.keys)
    }

    @Test fun whatThePurgeDropsCanNeverComeBackBecauseTheCardBehindItIsTooOldToBeTaken() {
        // a card is created before it is revoked, so a tombstone older than the horizon guards a card older than the horizon
        val b = Node("b", "Phone B")
        val revokedMs = T0 - KbLimits.HORIZON_MS - 1
        val card = KbCanon.make("s1", "q?", "a", listOf(KbSource("Phone A", "f", 1, false)), "a", "Phone A", revokedMs - 1, "h")
        assertTrue(b.svc.tombstoneExpired(revokedMs))
        assertEquals("older than the knowledge bank keeps", b.svc.ingestCard(card))
    }

    @Test fun anExpiredTombstoneIsNotTakenFromAPeerAndNotAskedFor() {
        val b = Node("b", "Phone B")
        val old = KbCanon.tombstone("s1", "1".repeat(64), "a", T0 - KbLimits.HORIZON_MS - DAY)
        assertEquals("older than the knowledge bank keeps", b.svc.ingestTombstone(old))
        val reply = b.svc.handleOffer(KbOffer(listOf(KbOfferItem(old.id, old.revokedMs))))
        assertTrue(reply.needTombstones.isEmpty())
    }

    // ---- 3b: the cards are read once ------------------------------------------------------------------------------

    @Test fun theCardListIsReadOnceAndAgainAfterEveryKindOfWrite() {
        val a = Node("a", "Phone A")
        val b = Node("b", "Phone B")
        val c = a.save(share = true)
        a.svc.entries(); a.svc.entries(); a.svc.offerItems()
        assertEquals("read once for the three uses", 1, a.store.allCalls)

        a.svc.setShared(c.card.id, false)                      // setShared
        assertEquals(false, a.svc.entries().single().tier == KbTier.SWARM)
        assertEquals(2, a.store.allCalls)

        a.svc.setShared(c.card.id, true)
        a.svc.entries()
        val shared = a.store.allCalls
        a.svc.revoke(c.card.id)                                // revoke
        assertFalse(a.svc.entries().single().usable)
        assertEquals(shared + 1, a.store.allCalls)

        val d = a.save(share = true)                           // save
        assertEquals(2, a.svc.entries().size)
        assertNotNull(d)

        b.svc.entries()                                        // ingest of a card
        val before = b.store.allCalls
        assertNull(b.svc.ingestCard(d.card))
        assertEquals(1, b.svc.entries().size)
        assertEquals(before + 1, b.store.allCalls)

        assertNull(a.svc.revoke(d.card.id))                    // ingest of a tombstone
        assertNull(b.svc.ingestTombstone(a.store.tombs[d.card.id]!!))
        assertFalse(b.svc.entries().single().usable)

        a.store.cards[d.card.id] = d.copy(revokedMs = null)    // repair
        a.svc.entries()
        assertEquals(1, a.svc.repair())
        assertFalse(a.svc.entries().first { it.card.id == d.card.id }.usable)

        val reads = a.store.allCalls                           // re-seal
        a.svc.entries()
        a.svc.rekey("s1", "s2")
        a.svc.entries()
        assertEquals(reads + 2, a.store.allCalls) // rekey itself reads the cards once, and the cache was dropped after it
    }

    @Test fun theVectorCacheLoadsOnceAndAgainAfterInvalidate() {
        var loads = 0
        val cache = VectorCache { loads++; mapOf("a" to floatArrayOf(1f)) }
        cache.get(); cache.get()
        assertEquals(1, loads)
        cache.invalidate()
        cache.get()
        assertEquals(2, loads)
    }

    @Test fun aFailedLoadIsNotKept() {
        var fail = true
        val cache = VectorCache { if (fail) throw IOException("busy") else 7 }
        try { cache.get(); org.junit.Assert.fail() } catch (_: IOException) { }
        fail = false
        assertEquals(7, cache.get())
    }

    // ---- finding 8: delivery state and the retry rule ----------------------------------------------------------------

    @Test fun aPhoneWithoutASyncSinceTheRevocationHasNotGotIt() {
        val peers = listOf(KbDelivery.Peer("b", "Phone B"), KbDelivery.Peer("c", "Phone C"), KbDelivery.Peer("d", "Phone D"))
        val lastOk = mapOf("b" to 1_000L, "c" to 5_000L)
        assertEquals(listOf("Phone B", "Phone D"), KbDelivery.pending(revokedMs = 2_000, peers = peers, lastOk = lastOk))
        assertEquals("Not delivered to Phone B yet", KbDelivery.line("Phone B"))
    }

    @Test fun onceEveryPhoneSyncedAfterTheRevocationNothingIsPending() {
        val peers = listOf(KbDelivery.Peer("b", "Phone B"))
        assertTrue(KbDelivery.pending(2_000, peers, mapOf("b" to 2_000L)).isEmpty())
    }

    @Test fun theRetryRuleIs20SecondsAndFiveTries() {
        assertEquals(20_000L, KbEngineRules.RETRY_MS)
        assertEquals(5, KbEngineRules.MAX_TRIES)
        assertEquals(20_000L, KbEngineRules.retryDelay(failedTries = 1))
        assertEquals(20_000L, KbEngineRules.retryDelay(failedTries = 4))
        assertNull("after the fifth failed try the timer takes over", KbEngineRules.retryDelay(failedTries = 5))
    }

    // ---- C3: records made under the old pairing secret are re-sealed -------------------------------------------------

    @Test fun ownCardsAndTombstonesAreResealedAndAnotherPhoneWithTheNewSecretTakesThem() {
        val a = Node("a", "Phone A", "s1")
        val live = a.save(share = true)
        val gone = a.save(share = true)
        a.svc.revoke(gone.card.id)
        // after hosting again, a phone that holds only the new secret
        val b = Node("b", "Phone B", "s2")
        assertTrue("without re-sealing, the new secret refuses the old seal", KbCanon.reject(live.card, "s2", "b", false) != null)

        val r = a.svc.rekey("s1", "s2")
        a.secret = "s2"
        assertEquals(2, r.cards)
        assertEquals(1, r.tombstones)
        assertEquals(0, r.left)
        assertTrue(KbCanon.sealedBy(a.store.cards[live.card.id]!!.card, "s2"))
        assertTrue(KbCanon.tombstoneSealedBy(a.store.tombs[gone.card.id]!!, "s2"))
        val got = sync(a, b)
        assertEquals(0, got.rejected.size)
        assertEquals(setOf(live.card.id, gone.card.id), b.store.cards.keys + b.store.tombs.keys)
        assertFalse(b.store.cards[gone.card.id]?.usable ?: false)
    }

    @Test fun recordsOfOtherAuthorsAndRecordsThatDoNotVerifyAreLeftAlone() {
        val a = Node("a", "Phone A", "s1")
        val other = KbCanon.make("s1", "q?", "a", listOf(KbSource("Phone Z", "f", 1, false)), "z", "Phone Z", T0 - 5, "h")
        a.store.cards[other.id] = KbEntry(other, KbTier.SWARM, null, true)
        val foreign = KbCanon.make("not-a-secret-we-know", "mine?", "a", listOf(KbSource("Phone A", "f", 1, false)), "a", "Phone A", T0 - 6, "h")
        a.store.cards[foreign.id] = KbEntry(foreign, KbTier.SWARM, null, true)
        val r = a.svc.rekey("s1", "s2")
        assertEquals(0, r.cards)
        assertEquals(1, r.left)
        assertEquals(other.mac, a.store.cards[other.id]!!.card.mac)
        assertEquals(foreign.mac, a.store.cards[foreign.id]!!.card.mac)
    }

    @Test fun reSealingIsOneTransaction() {
        val a = Node("a", "Phone A", "s1")
        val x = a.save(share = true)
        val y = a.save(share = true)
        a.store.failReplaceAt = 2 // the first card is re-sealed, the second write fails
        try {
            a.svc.rekey("s1", "s2")
            org.junit.Assert.fail("expected the write to fail")
        } catch (_: IOException) {
        }
        assertTrue(KbCanon.sealedBy(a.store.cards[x.card.id]!!.card, "s1"))
        assertTrue(KbCanon.sealedBy(a.store.cards[y.card.id]!!.card, "s1"))
    }

    @Test fun anUnchangedSecretReSealsNothing() {
        val a = Node("a", "Phone A", "s1")
        a.save(share = true)
        assertEquals(KbRekey(), a.svc.rekey("s1", "s1"))
    }
}
