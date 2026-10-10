package com.swarmos.app.kb

import com.swarmos.app.pdu.MergedHit
import com.swarmos.app.pdu.PduHit
import com.swarmos.app.pdu.PromptBuilder
import com.swarmos.app.pdu.QueryTerms
import com.swarmos.app.pdu.QueryUi
import com.swarmos.app.pdu.SourceUi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SECRET = "test-secret"

private class MemoryKb : KbBacking {
    val cards = LinkedHashMap<String, KbEntry>()
    val tombs = LinkedHashMap<String, KbTombstone>()
    override fun entry(id: String) = cards[id]
    override fun put(e: KbEntry) { cards[e.card.id] = e }
    override fun all() = cards.values.toList()
    override fun tombstone(id: String) = tombs[id]
    override fun putTombstone(t: KbTombstone) { tombs[t.id] = t }
    override fun tombstones() = tombs.values.toList()
}

/** One phone's knowledge bank: its own store, audit list and clock. */
private class Phone(val id: String, val name: String, secret: String = SECRET) {
    var now = 1_760_000_100_000L
    val store = MemoryKb()
    val audit = ArrayList<Pair<String, String>>()
    var changes = 0                                         // how often the service asked for a sync right now
    var isPrivate: (KbSource) -> Boolean = { false }        // what this phone's own index says about a source
    val svc = KbService(
        store, { secret }, { id }, { name }, { kind, p -> audit += kind to p.toString(); "audit-hash-${audit.size}" }, { now },
        isPrivateSource = { isPrivate(it) }, onChange = { changes++ },
    )

    fun save(q: String, a: String, share: Boolean, vararg sources: KbSource): KbEntry =
        (svc.save(q, a, sources.toList().ifEmpty { listOf(KbSource(name, "file.pdf", 1_000, false)) }, share) as KbSave.Saved).entry

    fun shared() = store.cards.values.filter { it.tier == KbTier.SWARM && it.usable }.map { it.card.id }.toSet()
    fun ids() = store.cards.keys.toSet()
}

private class Link(private val remote: KbService) : KbPeer {
    override suspend fun offer(o: KbOffer) = remote.handleOffer(o)
    override suspend fun cards(c: KbCards) = remote.handleCards(c)
}

private fun sync(from: Phone, to: Phone) = runBlocking { KbSync.run(from.svc, Link(to.svc)) }

class KbCardTest {
    private val src = listOf(KbSource("Phone A", "Laptop_Extended_Warranty_Certificate.pdf", 1_760_000_000_000, false))

    @Test fun idAndMacMatchValuesComputedIndependently() {
        // expected values from a separate implementation (Python hashlib / hmac over the same canonical text)
        val card = KbCanon.make(
            SECRET, "Where is my laptop warranty?", "The certificate is Laptop_Extended_Warranty_Certificate.pdf on Phone A [1].", src,
            "node-a", "Phone A", 1_760_000_100_000, "audit-hash-1",
        )
        assertEquals("e1a78f63a87f0fcf1a1db73cc937609a12db2dffbe1217186bae1a660d25a609", card.id)
        assertEquals("923674f9f04675b8be6f16460ea322e6b83792a98fc76a75c0d93f7328ae68e9", card.mac)
        assertEquals(
            "49aa725b27a95d9fdd38138c69bbd3798c19bee1b37c583cbfca3e6abf53e6d0",
            KbCanon.tombstone(SECRET, card.id, "node-a", 1_760_000_200_000).mac,
        )
        assertNull(KbCanon.reject(card, SECRET, "node-c", existsHere = false))
    }

    @Test fun idCoversTheContentAndTheMacCoversTheSecretAndTheAuditHash() {
        fun make(secret: String = SECRET, q: String = "q?", audit: String = "h1") = KbCanon.make(secret, q, "a", src, "n", "N", 5, audit)
        assertEquals(make(), make())
        assertTrue(make().id != make(q = "q2?").id)
        assertEquals(make().id, make(secret = "other").id)        // the id does not depend on the secret ...
        assertTrue(make().mac != make(secret = "other").mac)      // ... the MAC does
        assertEquals(make().id, make(audit = "h2").id)            // the audit hash is not in the id (it exists only after the id) ...
        assertTrue(make().mac != make(audit = "h2").mac)          // ... but it is under the MAC
    }

    @Test fun aTamperedCardIsRejected() {
        val good = KbCanon.make(SECRET, "q?", "answer", src, "node-a", "Phone A", 5, "h")
        assertNull(KbCanon.reject(good, SECRET, "node-c", false))
        // text changed, id and MAC left as they were
        assertEquals("id does not match the content", KbCanon.reject(good.copy(answer = "different"), SECRET, "node-c", false))
        // text changed and the id recomputed: the MAC, which needs the secret, no longer fits
        val reId = good.copy(answer = "different").let { it.copy(id = KbCanon.idOf(KbCanon.content(it))) }
        assertEquals("MAC does not verify", KbCanon.reject(reId, SECRET, "node-c", false))
        // a source flipped from private to public or the author renamed: same story
        assertEquals("id does not match the content", KbCanon.reject(good.copy(authorName = "Phone B"), SECRET, "node-c", false))
        // made with another pairing secret
        val foreign = KbCanon.make("not-our-secret", "q?", "answer", src, "node-a", "Phone A", 5, "h")
        assertEquals("MAC does not verify", KbCanon.reject(foreign, SECRET, "node-c", false))
        // audit hash swapped
        assertEquals("MAC does not verify", KbCanon.reject(good.copy(auditHash = "other"), SECRET, "node-c", false))
    }

    @Test fun oversizedAndMalformedCardsAreRejected() {
        fun make(q: String = "q?", a: String = "a", s: List<KbSource> = src) = KbCanon.make(SECRET, q, a, s, "node-a", "Phone A", 5, "h")
        assertEquals("answer missing or too long", KbCanon.reject(make(a = "x".repeat(KbLimits.MAX_ANSWER + 1)), SECRET, "c", false))
        assertNull(KbCanon.reject(make(a = "x".repeat(KbLimits.MAX_ANSWER)), SECRET, "c", false))
        assertEquals("question missing or too long", KbCanon.reject(make(q = "q".repeat(KbLimits.MAX_QUESTION + 1)), SECRET, "c", false))
        assertEquals("too many sources", KbCanon.reject(make(s = List(KbLimits.MAX_SOURCES + 1) { src[0] }), SECRET, "c", false))
        assertEquals("source field too long", KbCanon.reject(make(s = listOf(src[0].copy(title = "t".repeat(KbLimits.MAX_FIELD + 1)))), SECRET, "c", false))
    }

    @Test fun aCardClaimingThisPhoneAsAuthorIsRefusedUnlessItAlreadyExistsHere() {
        val mine = KbCanon.make(SECRET, "q?", "a", src, "node-c", "Phone C", 5, "h")
        assertEquals("claims this phone as author", KbCanon.reject(mine, SECRET, "node-c", existsHere = false))
        assertNull(KbCanon.reject(mine, SECRET, "node-c", existsHere = true))
    }
}

class KbSyncTest {
    private val a = Phone("node-a", "Phone A")
    private val b = Phone("node-b", "Phone B")

    @Test fun aSharedSaveAndTheRevocationOfASharedCardAskForAnImmediateSync() {
        a.save("kept?", "a [1]", share = false)
        assertEquals(0, a.changes)                                    // nothing to tell anyone
        val shared = a.save("open?", "a [1]", share = true)
        assertEquals(1, a.changes)                                    // a shared save is pushed at once
        assertNull(a.svc.setShared(shared.card.id, false))
        assertEquals(1, a.changes)                                    // stopping to share cannot be pushed
        assertNull(a.svc.setShared(shared.card.id, true))
        assertEquals(2, a.changes)
        assertNull(a.svc.revoke(shared.card.id))
        assertEquals(3, a.changes)                                    // a revocation of a shared card is pushed at once
        val neverShared = a.save("private?", "a [1]", share = false)
        assertNull(a.svc.revoke(neverShared.card.id))
        assertEquals(3, a.changes)                                    // no tombstone exists for it, so nothing to push
    }

    @Test fun theReceivingPhoneAuditsASyncWithCountsOnly() {
        val card = a.save("where is the secret warranty?", "In the blue folder [1]", share = true).card
        assertTrue(b.audit.isEmpty())
        val reply = b.svc.handleCards(KbCards(cards = listOf(card)), from = "node-a")
        assertEquals(1, reply.accepted)
        val line = b.audit.single()
        assertEquals("kb.sync", line.first)
        assertTrue(line.second.contains("\"side\":\"received\""))
        assertTrue(line.second.contains("\"peer\":\"node-a\""))
        assertTrue(line.second.contains("\"got\":1"))
        assertFalse(line.second.contains("warranty"))                  // no card text in the log
        assertFalse(line.second.contains("blue folder"))
        // an exchange that stores nothing writes nothing
        b.svc.handleCards(KbCards(want = listOf(card.id)), from = "node-a")
        assertEquals(1, b.audit.size)
        // a refused card is logged as a refusal
        b.svc.handleCards(KbCards(cards = listOf(card.copy(answer = "changed"))), from = "node-a")
        assertEquals(2, b.audit.size)
        assertTrue(b.audit[1].second.contains("\"rejected\":1"))
    }

    @Test fun privacyIsDecidedFromThePhonesOwnFilesNotFromTheCallersFlag() {
        a.isPrivate = { it.title == "Salary_Slip.pdf" }
        val claimedPublic = KbSource("Phone A", "Salary_Slip.pdf", 1_000, priv = false)
        // the caller says priv=false; this phone knows better
        assertTrue((a.svc.save("pay?", "10 [1]", listOf(claimedPublic), share = true) as KbSave.Refused).reason.contains("private folder"))
        val kept = (a.svc.save("pay?", "10 [1]", listOf(claimedPublic), share = false) as KbSave.Saved).entry
        assertTrue(kept.card.sources.single().priv)                    // recorded as private under the MAC
        assertTrue(a.svc.setShared(kept.card.id, true)!!.contains("private folder"))
        assertEquals(KbTier.PRIVATE, a.store.cards.getValue(kept.card.id).tier)
        // the caller can make a source more private, never less
        a.isPrivate = { false }
        assertTrue(a.svc.save("pay?", "10 [1]", listOf(claimedPublic.copy(priv = true)), share = true) is KbSave.Refused)
        // a folder that became private after the save also stops a later share
        val later = a.save("other?", "x [1]", share = false, KbSource("Phone A", "Notes.txt", 1_000, false))
        a.isPrivate = { it.title == "Notes.txt" }
        assertTrue(a.svc.setShared(later.card.id, true)!!.contains("private folder"))
    }

    @Test fun thePlaceholderTitleAndTheOwnersFilesAreRecognisedAsPrivate() {
        fun priv(s: KbSource, self: String = "Phone B", index: (String) -> Boolean = { false }) = KbPrivacy.isPrivate(s, self, index)
        assertTrue(priv(KbSource("Phone B", KbPrivacy.PLACEHOLDER, 1, false)))              // what the ask report shows for a private file
        assertTrue(priv(KbSource("Phone B", "Notes.txt", 1, false)) { it == "Notes.txt" })   // this phone's index says private
        assertFalse(priv(KbSource("Phone B", "Notes.txt", 1, false)) { false })
        assertFalse(priv(KbSource("Phone C", "Notes.txt", 1, false)) { true })               // another phone's file is not this phone's to judge
        assertTrue(priv(KbSource("Phone C", "x", 1, true)))                                   // an explicit flag always counts
    }

    @Test fun savingAppendsAnAuditEntryAndStoresItsHashInTheCard() {
        val e = a.save("q?", "answer [1]", share = false)
        assertEquals(listOf("kb.save"), a.audit.map { it.first })
        assertTrue(a.audit[0].second.contains(e.card.id))
        assertEquals("audit-hash-1", e.card.auditHash)
        assertNull(KbCanon.reject(e.card, SECRET, "node-x", false)) // and the hash is under the MAC
    }

    @Test fun twoStoresConvergeBothWaysInOneSync() {
        val c1 = a.save("where is the warranty?", "On phone A [1]", share = true)
        val c2 = a.save("private thing?", "kept", share = false)
        val c3 = b.save("what is the wifi code?", "On the router [1]", share = true)
        val r = sync(a, b)
        assertEquals(setOf(c1.card.id, c3.card.id), a.shared())
        assertEquals(setOf(c1.card.id, c3.card.id), b.shared())
        assertTrue(c2.card.id !in b.ids())
        assertEquals(1, r.sentCards); assertEquals(1, r.gotCards); assertTrue(r.rejected.isEmpty())
        // received cards are stored as shared, with the author they claim, and verify on the receiving side
        assertEquals("Phone B", a.store.cards.getValue(c3.card.id).card.authorName)
        assertEquals(KbTier.SWARM, a.store.cards.getValue(c3.card.id).tier)
        // a second sync has nothing to do
        assertEquals(KbSyncResult(), sync(a, b))
        assertEquals(KbSyncResult(), sync(b, a))
    }

    @Test fun aThirdPhoneGetsCardsFromARelayWhenTheAuthorIsGone() {
        val c = Phone("node-c", "Phone C")
        val card = a.save("q?", "a [1]", share = true)
        sync(a, b)
        sync(c, b) // A is not part of this exchange
        assertEquals(setOf(card.card.id), c.shared())
        assertEquals("Phone A", c.store.cards.getValue(card.card.id).card.authorName)
    }

    @Test fun aPrivateCardNeverAppearsInAnOfferOrIsSentEvenWhenAsked() {
        val priv = a.save("secret?", "hidden", share = false)
        val pub = a.save("open?", "visible", share = true)
        assertEquals(listOf(pub.card.id), a.svc.offerItems().map { it.id })
        assertTrue(a.svc.cardsToSend(listOf(priv.card.id)).isEmpty())
        val reply = a.svc.handleCards(KbCards(want = listOf(priv.card.id, pub.card.id)))
        assertEquals(listOf(pub.card.id), reply.cards.map { it.id })
        sync(a, b)
        assertEquals(setOf(pub.card.id), b.ids())
        // stopping the sharing takes it out of the offers; revoking a never-shared card leaves no tombstone to reveal it
        assertNull(a.svc.setShared(pub.card.id, false))
        assertTrue(a.svc.offerItems().isEmpty())
        assertNull(a.svc.revoke(priv.card.id))
        assertTrue(a.store.tombs.isEmpty())
        assertTrue(a.svc.offerItems().isEmpty())
    }

    @Test fun aCardWithAPrivateSourceCannotBecomeSwarm() {
        val withPrivate = KbSource("Phone A", "Salary_Slip.pdf", 1_000, priv = true)
        assertTrue((a.svc.save("pay?", "10", listOf(withPrivate), share = true) as KbSave.Refused).reason.contains("private folder"))
        val kept = a.save("pay?", "10", share = false, withPrivate)
        assertTrue(KbPolicy.whyNotShareable(kept, "node-a")!!.contains("private folder"))
        assertTrue(a.svc.setShared(kept.card.id, true)!!.contains("private folder"))
        assertEquals(KbTier.PRIVATE, a.store.cards.getValue(kept.card.id).tier)
        // and a copy that arrives from the swarm claiming a private source is refused (a forged or buggy peer)
        val forged = KbCanon.make(SECRET, "pay?", "10", listOf(withPrivate), "node-x", "Phone X", 5, "h")
        assertEquals("a source is in a private folder", b.svc.ingestCard(forged))
    }

    @Test fun aRevocationWinsWhenItArrivesAfterTheCard() {
        val card = a.save("q?", "a [1]", share = true)
        sync(a, b)
        assertTrue(b.store.cards.getValue(card.card.id).usable)
        a.now += 1_000
        assertNull(a.svc.revoke(card.card.id))
        assertTrue(a.svc.offerItems().any { it.id == card.card.id && it.revokedMs != null }) // the tombstone is offered, the card is not
        sync(a, b)
        assertFalse(b.store.cards.getValue(card.card.id).usable)
        assertNotNull(b.store.tombs[card.card.id])
        assertFalse(card.card.id in b.shared()) // a revoked card is not offered on either
    }

    @Test fun aRevocationWinsWhenItArrivesBeforeTheCard() {
        val c = Phone("node-c", "Phone C")
        val card = a.save("q?", "a [1]", share = true)
        sync(a, b) // B holds the card, not yet revoked
        a.now += 1_000
        a.svc.revoke(card.card.id)
        sync(a, c) // C learns of the revocation before it ever sees the card
        assertNotNull(c.store.tombs[card.card.id])
        assertTrue(card.card.id !in c.ids())
        // B (still unaware) offers the card to C: C does not take it
        sync(b, c)
        assertTrue(card.card.id !in c.ids())
        // a card pushed straight to C after the tombstone is stored as revoked
        assertNull(c.svc.ingestCard(card.card))
        assertFalse(c.store.cards.getValue(card.card.id).usable)
        assertTrue(c.svc.offerItems().none { it.revokedMs == null })
        // and C passes the revocation on to B
        sync(c, b)
        assertFalse(b.store.cards.getValue(card.card.id).usable)
    }

    @Test fun onlyTheAuthorCanRevokeAndAForgedTombstoneIsRefused() {
        val card = a.save("q?", "a [1]", share = true)
        sync(a, b)
        assertEquals("Only the author can revoke a card", b.svc.revoke(card.card.id))
        val wrongAuthor = KbCanon.tombstone(SECRET, card.card.id, "node-x", 9)
        assertEquals("not the author of that card", b.svc.ingestTombstone(wrongAuthor))
        val badMac = KbCanon.tombstone("not-our-secret", card.card.id, "node-a", 9)
        assertEquals("MAC does not verify", b.svc.ingestTombstone(badMac))
        assertTrue(b.store.cards.getValue(card.card.id).usable)
        // a tombstone naming this phone as author for a card it never made
        assertEquals("claims this phone as author", b.svc.ingestTombstone(KbCanon.tombstone(SECRET, "f".repeat(64), "node-b", 9)))
    }

    @Test fun oneSyncMovesAtMostTheCapAndTheRestFollowsOnTheNextOne() {
        repeat(KbLimits.MAX_CARDS_PER_SYNC + 10) { a.save("question $it?", "answer $it [1]", share = true) }
        val first = sync(a, b)
        assertEquals(KbLimits.MAX_CARDS_PER_SYNC, b.ids().size)
        assertEquals(KbLimits.MAX_CARDS_PER_SYNC, first.sentCards)
        sync(a, b)
        assertEquals(KbLimits.MAX_CARDS_PER_SYNC + 10, b.ids().size)
    }

    @Test fun cardsOverTheByteBudgetOfOneSyncAreLeftForTheNext() {
        // 8 sources of 120-char titles: about 1.4 KB a card, so 128 KB fits well under 100 of them; check the budget itself
        val big = (0 until 5).map { a.save("q$it?", "x".repeat(KbLimits.MAX_ANSWER), share = true) }
        val bytes = big.sumOf { com.swarmos.app.core.SwarmJson.encodeToString(KbCard.serializer(), it.card).length }
        assertTrue(bytes < KbLimits.MAX_BYTES_PER_SYNC)
        assertEquals(5, a.svc.cardsToSend(big.map { it.card.id }).size)
    }

    @Test fun aSyncRefusesCardsThatFailTheChecksAndSaysWhy() {
        val good = a.save("q?", "a [1]", share = true).card
        val tampered = good.copy(answer = "changed")
        val reply = b.svc.handleCards(KbCards(cards = listOf(tampered, good)))
        assertEquals(1, reply.accepted)
        assertEquals(1, reply.rejected.size)
        assertTrue(reply.rejected[0].contains("id does not match"))
        assertEquals(setOf(good.id), b.ids())
    }
}

class KbSearchTest {
    private fun unit(i: Int) = FloatArray(256).also { it[i] = 1f }

    private fun entries(vararg q: Pair<String, String>): List<KbEntry> {
        val p = Phone("node-a", "Phone A")
        return q.map { (question, answer) -> p.save(question, answer, share = true) }
    }

    @Test fun aRelevantCardIsPreferredAndAnUnrelatedOneIsNotReturned() {
        val es = entries(
            "What is the wifi password at the office?" to "It is on the router label [1].",
            "How long is the laptop warranty?" to "Three years from purchase [1].",
        )
        val hits = KbSearch.rank(es, emptyMap(), null, QueryTerms.terms("how long is the laptop warranty"))
        assertEquals(1, hits.size)
        assertEquals("How long is the laptop warranty?", hits[0].entry.card.question)
        assertTrue(KbSearch.answersByItself(hits[0]))
    }

    @Test fun withVectorsTheCloserCardWins() {
        val es = entries("Laptop warranty period?" to "Three years [1].", "Laptop warranty claim steps?" to "Call the number [1].")
        val v = mapOf(es[0].card.id to unit(0), es[1].card.id to unit(1))
        val hits = KbSearch.rank(es, v, unit(0), QueryTerms.terms("laptop warranty"))
        assertEquals(es[0].card.id, hits[0].entry.card.id)
        assertEquals(1f, hits[0].cosine!!, 1e-6f)
        assertTrue(KbSearch.answersByItself(hits[0]))
        assertFalse(KbSearch.answersByItself(hits[0].copy(cosine = 0.5f))) // close-ish in words but not in meaning: only a source
    }

    @Test fun aRevokedCardIsIgnored() {
        val es = entries("How long is the laptop warranty?" to "Three years [1].")
        val revoked = es.map { it.copy(revokedMs = 5L) }
        assertTrue(KbSearch.rank(revoked, emptyMap(), null, QueryTerms.terms("how long is the laptop warranty")).isEmpty())
        assertEquals(1, KbSearch.rank(es, emptyMap(), null, QueryTerms.terms("how long is the laptop warranty")).size)
    }

    @Test fun aPartialMatchJoinsThePromptButDoesNotAnswerByItself() {
        val es = entries("How long is the laptop warranty?" to "Three years [1].")
        val hit = KbSearch.rank(es, emptyMap(), null, QueryTerms.terms("how long does my laptop warranty last")).single()
        assertEquals(0.75, hit.coverage, 1e-9)
        assertFalse(KbSearch.answersByItself(hit))
    }

    @Test fun savedAnswersStillAnswerWhenThePhoneThatHeldTheFilesIsAway() {
        val a = Phone("node-a", "Phone A")
        val c = Phone("node-c", "Phone C")
        a.save("How long is the laptop warranty?", "Three years from purchase [1].", share = true, KbSource("Phone A", "Warranty.pdf", 1_000, false))
        sync(a, c)
        // Phone A is switched off: nothing below touches it, only Phone C's own store
        val hits = KbSearch.rank(c.svc.entries(), emptyMap(), null, QueryTerms.terms("how long is the laptop warranty"))
        val best = hits.first()
        assertTrue(KbSearch.answersByItself(best))
        assertEquals("Three years from purchase [1].", best.entry.card.answer)
        assertEquals("Phone A", best.entry.card.authorName)
        assertEquals("Warranty.pdf", best.entry.card.sources.single().title)
    }

    @Test fun theModelSeesACardAsOneSourceAmongTheOthersWithNoClaimOfTruth() {
        val card = entries("How long is the laptop warranty?" to "Three years [1].").single()
        val hit = KbSearch.rank(listOf(card), emptyMap(), null, QueryTerms.terms("laptop warranty")).single()
        val file = MergedHit("node-c", "Phone C", false, PduHit("Warranty.pdf", "h", "pdf", 1_000, "Valid for 36 months", 0.9, 1), 0.0)
        val cardSource = MergedHit("node-a", "Phone A", false, KbSearch.asHit(hit, 2), 0.0)
        val prompt = PromptBuilder.build("How long is the laptop warranty?", listOf(file, cardSource)).messages.joinToString("\n") { it.content }
        assertTrue(prompt.contains("[1] Phone C · Warranty.pdf"))
        assertTrue(prompt.contains("[2] Phone A · Saved answer: How long is the laptop warranty?"))
        assertFalse(prompt.lowercase().contains("ground truth"))
        assertFalse(prompt.lowercase().contains("verified"))
    }

    @Test fun ageIsShownInTheLargestUnit() {
        assertEquals("just now", KbSearch.age(1_000, 20_000))
        assertEquals("5 min", KbSearch.age(0, 5 * 60_000L))
        assertEquals("3 h", KbSearch.age(0, 3 * 3_600_000L))
        assertEquals("1 day", KbSearch.age(0, 30 * 3_600_000L))
        assertEquals("4 days", KbSearch.age(0, 4 * 86_400_000L))
    }
}

class KbTextTest {
    private val ms = 1_760_000_000_000L

    @Test fun everySourceLineShowsPhoneTitleAndDateAndNeverEndsOnTheSeparator() {
        val lines = listOf(
            KbText.sourceLine(KbSource("Phone A", "Laptop_Extended_Warranty_Certificate.pdf", ms, false)),
            KbText.sourceLine(KbSource("Phone B", "", ms, false)),
            KbText.sourceLine(KbSource("Phone B", "   ", ms, false)),
            KbText.sourceLine(KbSource("Phone C", "IMG_2231.jpg", 0, false)),
        )
        for (l in lines) assertFalse(l, l.trimEnd().endsWith("\u00b7"))
        assertTrue(lines[0].startsWith("Phone A \u00b7 Laptop_\u2026") && lines[0].contains("ate.pdf") && lines[0].endsWith("2025-10-09"))
        assertTrue(lines[0].length <= 40)                                 // fits one line of the card, so the date is not cut off
        assertEquals("Phone B \u00b7 (untitled) \u00b7 2025-10-09", lines[1])
        assertEquals(lines[1], lines[2])
        assertEquals("Phone C \u00b7 IMG_2231.jpg \u00b7 undated", lines[3])
    }

    @Test fun aMarkerWithASourceGetsItsLineAndOneWithoutStaysPlainText() {
        val card = KbCanon.make("s", "q?", "a [1] b [2] c [3]", listOf(KbSource("Phone B", "IMG_2231.jpg", ms, false), KbSource("Phone A", "", ms, false)), "n", "N", 5, "h")
        assertEquals("[1] Phone B \u00b7 IMG_2231.jpg \u00b7 2025-10-09", KbText.citeLine(card, 1))
        assertEquals("[2] Phone A \u00b7 (untitled) \u00b7 2025-10-09", KbText.citeLine(card, 2))
        assertNull(KbText.citeLine(card, 3))                              // [3] has no source: no chip, no action
        assertNull(KbText.citeLine(card, 0))
    }

    @Test fun cardsSavedOnTheSameDayAreToldApartByTheTime() {
        val zone = java.time.ZoneId.of("Asia/Kolkata")
        assertEquals("2026-10-10 09:54", KbText.dayTime(java.time.ZonedDateTime.of(2026, 10, 10, 9, 54, 30, 0, zone).toInstant().toEpochMilli(), zone))
        assertEquals("2026-10-10 14:05", KbText.dayTime(java.time.ZonedDateTime.of(2026, 10, 10, 14, 5, 0, 0, zone).toInstant().toEpochMilli(), zone))
    }
}

class KbDraftTest {
    @Test fun markersInTheSavedTextAreRenumberedToTheCardsSourceList() {
        // the answer cited sources 1 and 3 of the live list; the card keeps those two as [1] and [2]
        assertEquals("A [1] and B [2].", KbDraft.renumber("A [1] and B [3].", listOf(1, 3)))
        assertEquals("Only [1] [1].", KbDraft.renumber("Only [3] [3].", listOf(3)))
        assertEquals("Fact.", KbDraft.renumber("Fact [7].", listOf(1)))                       // a marker with no cited source is dropped
        val d = KbDraft.of(done("Pay is 10 [2] and tax is 2 [3].", sources = listOf(source(1), source(2), source(3))))!!
        assertEquals("Pay is 10 [1] and tax is 2 [2].", d.answer)
        assertEquals(listOf("File2.pdf", "File3.pdf"), d.sources.map { it.title })
    }

    @Test fun aSavedAnswerCarriesItsCitedFilesNumberedAsItsTextNumbersThem() {
        val card = Phone("node-b", "Phone B").save("q?", "a [1] b [2]", false, KbSource("Phone B", "IMG_2231.jpg", 5_000, false), KbSource("Phone A", "Cert.pdf", 6_000, false)).card
        val s = KbSearch.sourcesOf(card)
        assertEquals(listOf(1, 2), s.map { it.n })
        assertEquals(listOf("Phone B", "Phone A"), s.map { it.deviceName })
        assertEquals(listOf("IMG_2231.jpg", "Cert.pdf"), s.map { it.hit.title })
        assertEquals(listOf(5_000L, 6_000L), s.map { it.hit.dateMs })
        assertTrue(s.all { it.hit.snippet.isEmpty() && it.hit.uriHash.isEmpty() }) // names and dates, never file text
    }

    private fun source(n: Int, priv: Boolean = false) =
        SourceUi(n, "node-$n", "Phone $n", n == 1, PduHit("File$n.pdf", "h$n", "pdf", 1_000L * n, "text", 0.5, n), priv)

    private fun done(answer: String, model: String = "Qwen", sources: List<SourceUi> = listOf(source(1), source(2))) =
        QueryUi("q1", "How long is the warranty?", "done", sources = sources, answer = answer, model = model)

    @Test fun onlyCitedSourcesGoIntoTheCard() {
        val d = KbDraft.of(done("Three years [2]. See also [7]."))!!
        assertEquals(listOf("File2.pdf"), d.sources.map { it.title })
        assertEquals(listOf(2), KbDraft.cited("Three years [2]. See also [7].", 2))
        assertEquals("How long is the warranty?", d.question)
        assertFalse(d.clipped)
    }

    @Test fun notOfferedWithoutARealModelACitationOrAFinishedAnswer() {
        assertNull(KbDraft.of(done("Three years.")))                                    // no citation
        assertNull(KbDraft.of(done("Three years [1].", model = "")))                    // no real chat model produced it
        assertNull(KbDraft.of(done("Three years [1].").copy(note = "stopped early")))   // the model stopped before it finished
        assertNull(KbDraft.of(done("Three years [1].").copy(phase = "answering")))
        assertNull(KbDraft.of(done("")))
        val card = Phone("node-a", "Phone A").save("q?", "a", false)
        assertNull(KbDraft.of(done("Three years [1].").copy(savedAnswer = KbHit(card, null, 1.0, 1.0)))) // a saved answer is not saved again
    }

    @Test fun aPrivateSourceIsCarriedIntoTheDraftSoSharingCanBeRefused() {
        val d = KbDraft.of(done("Pay is 10 [1].", sources = listOf(source(1, priv = true))))!!
        assertTrue(d.sources.single().priv)
        assertTrue(Phone("node-a", "Phone A").svc.save(d.question, d.answer, d.sources, share = true) is KbSave.Refused)
    }

    @Test fun aLongAnswerIsCutToTheCardLimitWithAnEllipsis() {
        val long = "word ".repeat(200) + "[1]"
        val d = KbDraft.of(done(long))!!
        assertTrue(d.clipped)
        assertEquals(KbLimits.MAX_ANSWER, d.answer.length)
        assertTrue(d.answer.endsWith("…"))
    }
}
