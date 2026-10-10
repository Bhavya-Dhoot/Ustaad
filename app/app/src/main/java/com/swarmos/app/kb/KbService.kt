package com.swarmos.app.kb

import com.swarmos.app.core.SwarmJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicInteger

/** What the service needs from storage. [KbStore] is the SQLite one; tests use a map. */
interface KbBacking {
    fun entry(id: String): KbEntry?
    fun put(e: KbEntry)
    fun all(): List<KbEntry>
    fun tombstone(id: String): KbTombstone?
    fun putTombstone(t: KbTombstone)
    fun tombstones(): List<KbTombstone>

    /** Everything [block] writes happens, or none of it does. A plain map has nothing to roll back, so the default just runs it. */
    fun <T> atomic(block: () -> T): T = block()

    /** Cards that can be used, i.e. not revoked; the cap counts these. The SQL store counts in the database instead of reading every card. */
    fun countUsable(): Int = all().count { it.usable }

    /** Replaces the stored card (a new MAC) and keeps its tier, revocation and embedding. */
    fun replaceCard(card: KbCard) { entry(card.id)?.let { put(it.copy(card = card)) } }

    /** Deletes these tombstones. */
    fun deleteTombstones(ids: Collection<String>) {}
}

// ---- wire ---------------------------------------------------------------------------------------------------

/** One line of "what I hold": a shared card ([revokedMs] null) or a tombstone. Never a PRIVATE card. */
@Serializable
data class KbOfferItem(val id: String, val revokedMs: Long? = null)

@Serializable
data class KbOffer(val items: List<KbOfferItem>)

/** The answer to an offer: what the receiver lacks, and the receiver's own list so the sender can ask for what it lacks. */
@Serializable
data class KbOfferReply(val missing: List<String>, val needTombstones: List<String>, val items: List<KbOfferItem>)

/** [cards] and [tombstones] are pushed; [want] and [wantTombstones] ask the receiver to send those back in its answer. */
@Serializable
data class KbCards(
    val cards: List<KbCard> = emptyList(),
    val tombstones: List<KbTombstone> = emptyList(),
    val want: List<String> = emptyList(),
    val wantTombstones: List<String> = emptyList(),
    val accepted: Int = 0,
    val rejected: List<String> = emptyList(),
)

/** The other end of a sync: the signed routes in the app, the other phone's [KbService] in tests. */
interface KbPeer {
    suspend fun offer(o: KbOffer): KbOfferReply
    suspend fun cards(c: KbCards): KbCards
}

data class KbSyncResult(
    val sentCards: Int = 0, val sentTombstones: Int = 0, val gotCards: Int = 0, val gotTombstones: Int = 0,
    val rejected: List<String> = emptyList(), val error: String? = null,
)

sealed interface KbSave {
    data class Saved(val entry: KbEntry) : KbSave
    data class Refused(val reason: String) : KbSave
}

/**
 * Everything the knowledge bank does with cards, apart from the screen and the network: save, share, revoke, and the two
 * halves of a sync. Plain Kotlin over [KbBacking], so two services can be wired together in a unit test.
 */
class KbService(
    private val backing: KbBacking,
    private val secret: () -> String,
    private val selfId: () -> String,
    private val selfName: () -> String,
    /** Appends an audit entry and returns its hash. */
    private val audit: (kind: String, payload: JsonObject) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Whether this phone itself holds the source as a private file. A source counts as private when the caller says so
     * or when this says so: the caller can make a card more private, never less.
     */
    private val isPrivateSource: (KbSource) -> Boolean = { false },
    /** Something other phones should hear about changed (a card was shared, a shared card was revoked): start a sync now. */
    private val onChange: () -> Unit = {},
) {
    // ---- the cards in memory ------------------------------------------------------------------------------
    // Every search and every offer needs the cards; parsing each one from the database every time is wasted work. The list is read
    // once and dropped by every write that goes through this class (save, share, revoke, a received card or revocation, a repair, a
    // re-seal). A read that overlaps a write does not keep what it read (the version moved).

    private val version = AtomicInteger()
    @Volatile private var cached: List<KbEntry>? = null

    private fun everyEntry(): List<KbEntry> {
        cached?.let { return it }
        val v = version.get()
        val loaded = backing.all()
        if (version.get() == v) cached = loaded
        return loaded
    }

    private fun changed() {
        version.incrementAndGet()
        cached = null
    }

    // ---- the owner's side -----------------------------------------------------------------------------------

    fun entries(): List<KbEntry> = everyEntry().sortedByDescending { it.card.createdMs }

    @Synchronized
    fun save(question: String, answer: String, sources: List<KbSource>, share: Boolean): KbSave {
        val q = question.trim()
        val a = answer.trim()
        if (q.isEmpty() || q.length > KbLimits.MAX_QUESTION) return KbSave.Refused("The question is empty or longer than ${KbLimits.MAX_QUESTION} characters")
        if (a.isEmpty() || a.length > KbLimits.MAX_ANSWER) return KbSave.Refused("The answer is empty or longer than ${KbLimits.MAX_ANSWER} characters")
        if (sources.isEmpty()) return KbSave.Refused("A card needs at least one source")
        if (sources.size > KbLimits.MAX_SOURCES) return KbSave.Refused("A card holds at most ${KbLimits.MAX_SOURCES} sources")
        val clipped = sources.map {
            val priv = it.priv || isPrivateSource(it) // decided here, from this phone's own files, for the button and the debug route alike
            it.copy(phone = it.phone.take(KbLimits.MAX_FIELD), title = it.title.take(KbLimits.MAX_FIELD), priv = priv)
        }
        if (share && clipped.any { it.priv }) return KbSave.Refused("A source is in a private folder, so this card cannot be shared")
        // revoked cards do not count: the cap is on the cards that can be used, and the count is made by the database, not by parsing every card
        if (backing.countUsable() >= KbLimits.MAX_CARDS) return KbSave.Refused("The knowledge bank is full (${KbLimits.MAX_CARDS} cards)")
        val createdMs = clock()
        val content = KbCanon.content(q, a, clipped, selfId(), selfName().take(KbLimits.MAX_FIELD), createdMs)
        val id = KbCanon.idOf(content)
        val tier = if (share) KbTier.SWARM else KbTier.PRIVATE
        val auditHash = audit("kb.save", JsonObject(mapOf("id" to JsonPrimitive(id), "tier" to JsonPrimitive(tier))))
        val card = KbCard(id, q, a, clipped, selfId(), selfName().take(KbLimits.MAX_FIELD), createdMs, auditHash, KbCanon.macOf(secret(), content, auditHash))
        val e = KbEntry(card, tier, null, everShared = share)
        backing.put(e)
        changed()
        if (share) onChange()
        return KbSave.Saved(e)
    }

    /** Author only. Sharing puts the card in the offers; stopping stops offering it (copies other phones already hold stay until it is revoked). */
    @Synchronized
    fun setShared(id: String, share: Boolean): String? {
        val e = backing.entry(id) ?: return "No such card"
        if (share) {
            KbPolicy.whyNotShareable(e, selfId())?.let { return it }
            // a folder may have become private since the card was saved
            if (e.card.sources.any { isPrivateSource(it) }) return "A source of this card is in a private folder, so it stays on this phone"
        } else if (e.card.authorId != selfId()) return "Only the author can change who this card is shared with"
        val tier = if (share) KbTier.SWARM else KbTier.PRIVATE
        backing.put(e.copy(tier = tier, everShared = e.everShared || share))
        changed()
        audit(if (share) "kb.share" else "kb.unshare", JsonObject(mapOf("id" to JsonPrimitive(id))))
        if (share) onChange()
        return null
    }

    /**
     * Author only. A card that was never shared is just marked; a shared one also leaves a tombstone, which replicates. The mark and
     * the tombstone are one transaction: a kill between two separate writes would leave a revocation that never reaches the other phones.
     */
    @Synchronized
    fun revoke(id: String): String? {
        val e = backing.entry(id) ?: return "No such card"
        if (!KbPolicy.canRevoke(e, selfId())) return if (e.usable) "Only the author can revoke a card" else "Already revoked"
        val now = clock()
        backing.atomic {
            backing.put(e.copy(revokedMs = now))
            if (e.everShared) backing.putTombstone(KbCanon.tombstone(secret(), id, selfId(), now))
        }
        changed()
        audit("kb.revoke", JsonObject(mapOf("id" to JsonPrimitive(id))))
        if (e.everShared) onChange()
        return null
    }

    // ---- the two halves of a sync ---------------------------------------------------------------------------

    /**
     * What this phone offers: every tombstone it holds, newest first, then the shared cards that are not revoked. Revocations come
     * first so that the cut at [KbLimits.MAX_OFFER_ITEMS] can only ever drop cards, never a revocation. PRIVATE cards never appear.
     */
    fun offerItems(): List<KbOfferItem> {
        val tombs = backing.tombstones().sortedByDescending { it.revokedMs }.map { KbOfferItem(it.id, it.revokedMs) }
        val cards = everyEntry().filter { it.tier == KbTier.SWARM && it.usable }.map { KbOfferItem(it.card.id) }
        return (tombs + cards).take(KbLimits.MAX_OFFER_ITEMS)
    }

    fun handleOffer(o: KbOffer): KbOfferReply {
        val missing = ArrayList<String>()
        val needTombstones = ArrayList<String>()
        for (item in o.items.take(KbLimits.MAX_OFFER_ITEMS)) {
            if (item.revokedMs != null) {
                if (!tombstoneExpired(item.revokedMs) && backing.tombstone(item.id) == null) needTombstones += item.id
            } else if (backing.entry(item.id) == null && backing.tombstone(item.id) == null) {
                missing += item.id
            }
        }
        return KbOfferReply(missing.take(KbLimits.MAX_CARDS_PER_SYNC), needTombstones.take(KbLimits.MAX_CARDS_PER_SYNC), offerItems())
    }

    /** Neither the card nor a tombstone for it is held here. */
    fun isUnknown(id: String) = backing.entry(id) == null && backing.tombstone(id) == null

    fun lacksTombstone(id: String) = backing.tombstone(id) == null

    /**
     * A tombstone is kept for [KbLimits.HORIZON_MS] after the revocation and no longer. This is safe because a card is only accepted
     * from another phone while it is younger than the same horizon ([ingestCard]): a card is created before it is revoked, so the card
     * behind a tombstone that has expired is itself too old to be accepted again, and the tombstone has nothing left to guard.
     */
    fun tombstoneExpired(revokedMs: Long): Boolean = revokedMs < clock() - KbLimits.HORIZON_MS

    /** Deletes the tombstones that have expired (see [tombstoneExpired]). Returns how many. */
    @Synchronized
    fun purgeExpiredTombstones(): Int {
        val old = backing.tombstones().filter { tombstoneExpired(it.revokedMs) }.map { it.id }
        if (old.isNotEmpty()) backing.deleteTombstones(old)
        return old.size
    }

    /** Stores what is pushed (after checking it), then answers with what was asked for. */
    fun handleCards(c: KbCards, from: String = ""): KbCards {
        val rejected = ArrayList<String>()
        var gotCards = 0
        var gotTombstones = 0
        var bytes = 0
        for (t in c.tombstones.take(KbLimits.MAX_CARDS_PER_SYNC)) {
            val why = ingestTombstone(t)
            if (why == null) gotTombstones++ else rejected += "tombstone ${t.id.take(8)}: $why"
        }
        for (card in c.cards.take(KbLimits.MAX_CARDS_PER_SYNC)) {
            bytes += SwarmJson.encodeToString(card).length
            val why = if (bytes > KbLimits.MAX_BYTES_PER_SYNC) "over the byte limit for one sync" else ingestCard(card)
            if (why == null) gotCards++ else rejected += "card ${card.id.take(8)}: $why"
        }
        val accepted = gotCards + gotTombstones
        // the receiving side writes its own line too (counts only, never card text)
        if (accepted > 0 || rejected.isNotEmpty()) auditSync(from, KbSyncResult(gotCards = gotCards, gotTombstones = gotTombstones, rejected = rejected), "received")
        if (c.cards.size > KbLimits.MAX_CARDS_PER_SYNC) rejected += "${c.cards.size - KbLimits.MAX_CARDS_PER_SYNC} cards over the count limit for one sync"
        return KbCards(
            cards = cardsToSend(c.want), tombstones = tombstonesToSend(c.wantTombstones), accepted = accepted, rejected = rejected,
        )
    }

    /** The cards this phone may hand out for [ids]: shared and not revoked, at most the per-sync count and bytes. */
    fun cardsToSend(ids: List<String>): List<KbCard> {
        var bytes = 0
        val out = ArrayList<KbCard>()
        for (id in ids.distinct()) {
            val e = backing.entry(id)?.takeIf { it.tier == KbTier.SWARM && it.usable } ?: continue
            bytes += SwarmJson.encodeToString(e.card).length
            if (out.size >= KbLimits.MAX_CARDS_PER_SYNC || bytes > KbLimits.MAX_BYTES_PER_SYNC) break
            out += e.card
        }
        return out
    }

    fun tombstonesToSend(ids: List<String>): List<KbTombstone> =
        ids.distinct().mapNotNull { backing.tombstone(it) }.take(KbLimits.MAX_CARDS_PER_SYNC)

    /** Null when stored, otherwise why not. A revocation that arrived first applies to the card when it arrives. */
    @Synchronized
    fun ingestCard(card: KbCard): String? {
        val existing = backing.entry(card.id)
        KbCanon.reject(card, secret(), selfId(), existing != null)?.let { return it }
        if (existing != null) return null
        // a card older than the horizon is not taken: this is what lets an old tombstone be forgotten (see tombstoneExpired)
        if (card.createdMs < clock() - KbLimits.HORIZON_MS) return "older than the knowledge bank keeps"
        if (backing.countUsable() >= KbLimits.MAX_CARDS) return "the knowledge bank is full"
        val t = backing.tombstone(card.id)?.takeIf { it.authorId == card.authorId }
        backing.put(KbEntry(card, KbTier.SWARM, t?.revokedMs, everShared = true))
        changed()
        return null
    }

    @Synchronized
    fun ingestTombstone(t: KbTombstone): String? {
        val e = backing.entry(t.id)
        KbCanon.rejectTombstone(t, secret(), selfId(), e?.card)?.let { return it }
        if (tombstoneExpired(t.revokedMs)) return "older than the knowledge bank keeps"
        // the tombstone and the mark on the card it revokes go together
        backing.atomic {
            if (backing.tombstone(t.id) == null) backing.putTombstone(t)
            if (e != null && e.revokedMs == null) backing.put(e.copy(revokedMs = t.revokedMs))
        }
        changed()
        return null
    }

    // ---- repair and re-sealing --------------------------------------------------------------------------------

    /**
     * Start-up repair of the half-written case, for a store written by an older build or killed in the middle of the two writes of a
     * revocation. A card of this phone that is revoked and was shared but has no tombstone gets its tombstone (the revocation would
     * otherwise never reach the other phones); a card that has a valid tombstone from its author but is not marked revoked is marked
     * (a receiver would otherwise keep a usable card that was withdrawn). Returns how many records were put right.
     */
    @Synchronized
    fun repair(): Int {
        val entries = backing.all()
        val tombs = backing.tombstones().associateBy { it.id }
        var fixed = 0
        backing.atomic {
            for (e in entries) {
                val t = tombs[e.card.id]
                if (e.revokedMs != null && e.everShared && e.card.authorId == selfId() && t == null) {
                    backing.putTombstone(KbCanon.tombstone(secret(), e.card.id, selfId(), e.revokedMs))
                    fixed++
                } else if (t != null && e.revokedMs == null && KbCanon.rejectTombstone(t, secret(), selfId(), e.card) == null) {
                    backing.put(e.copy(revokedMs = t.revokedMs))
                    fixed++
                }
            }
        }
        if (fixed > 0) {
            changed()
            audit("kb.repair", JsonObject(mapOf("fixed" to JsonPrimitive(fixed))))
        }
        return fixed
    }

    /**
     * The pairing secret changed (this phone hosted again, or adopted another phone's secret). Every card and tombstone is sealed with
     * an HMAC under the pairing secret, so records made under the old one would fail the check on every phone that has the new one.
     * Re-seals this phone's own records that verify under [oldSecret], in one transaction; a record that does not verify under it is
     * left alone. Records that came from other phones are not touched: only their author can seal them, and they were checked when they
     * arrived and are not checked again here. Returns what was re-sealed.
     */
    @Synchronized
    fun rekey(oldSecret: String, newSecret: String): KbRekey {
        if (oldSecret == newSecret) return KbRekey()
        val me = selfId()
        var cards = 0
        var tombs = 0
        var left = 0
        backing.atomic {
            for (e in backing.all()) {
                val c = e.card
                if (c.authorId != me) continue
                if (KbCanon.sealedBy(c, oldSecret)) {
                    backing.replaceCard(c.copy(mac = KbCanon.macOf(newSecret, KbCanon.content(c), c.auditHash)))
                    cards++
                } else if (!KbCanon.sealedBy(c, newSecret)) left++
            }
            for (t in backing.tombstones()) {
                if (t.authorId != me) continue
                if (KbCanon.tombstoneSealedBy(t, oldSecret)) {
                    backing.putTombstone(t.copy(mac = KbCanon.tombstoneMac(newSecret, t.id, t.authorId, t.revokedMs)))
                    tombs++
                } else if (!KbCanon.tombstoneSealedBy(t, newSecret)) left++
            }
        }
        changed()
        if (cards + tombs + left > 0) {
            audit("kb.rekey", JsonObject(mapOf("cards" to JsonPrimitive(cards), "tombstones" to JsonPrimitive(tombs), "left" to JsonPrimitive(left))))
        }
        return KbRekey(cards, tombs, left)
    }

    /** The audit line for a finished sync; the card texts are not logged. */
    fun auditSync(peerId: String, r: KbSyncResult, side: String = "started") {
        audit(
            "kb.sync",
            JsonObject(
                mapOf(
                    "peer" to JsonPrimitive(peerId), "side" to JsonPrimitive(side), "sent" to JsonPrimitive(r.sentCards + r.sentTombstones),
                    "got" to JsonPrimitive(r.gotCards + r.gotTombstones), "rejected" to JsonPrimitive(r.rejected.size),
                ),
            ),
        )
    }
}

/** What [KbService.rekey] did: records re-sealed, and own records that verify under neither secret. */
data class KbRekey(val cards: Int = 0, val tombstones: Int = 0, val left: Int = 0)

object KbSync {
    /**
     * One exchange with [peer]: offer what is shared here, learn what the peer lacks and what it holds, push the first and
     * ask for the second in one request. Both phones end up with the union (revocations included).
     */
    suspend fun run(local: KbService, peer: KbPeer): KbSyncResult {
        val reply = peer.offer(KbOffer(local.offerItems()))
        val cards = local.cardsToSend(reply.missing)
        val tombs = local.tombstonesToSend(reply.needTombstones)
        val theirs = reply.items
        val wantCards = theirs.filter { it.revokedMs == null && local.isUnknown(it.id) }.map { it.id }.take(KbLimits.MAX_CARDS_PER_SYNC)
        val wantTombs = theirs.filter { it.revokedMs != null && !local.tombstoneExpired(it.revokedMs) && local.lacksTombstone(it.id) }.map { it.id }.take(KbLimits.MAX_CARDS_PER_SYNC)
        if (cards.isEmpty() && tombs.isEmpty() && wantCards.isEmpty() && wantTombs.isEmpty()) return KbSyncResult()
        val back = peer.cards(KbCards(cards, tombs, wantCards, wantTombs))
        val rejected = ArrayList(back.rejected)
        var gotCards = 0
        var gotTombs = 0
        for (t in back.tombstones) {
            val why = local.ingestTombstone(t)
            if (why == null) gotTombs++ else rejected += "tombstone ${t.id.take(8)}: $why"
        }
        for (c in back.cards) {
            val why = local.ingestCard(c)
            if (why == null) gotCards++ else rejected += "card ${c.id.take(8)}: $why"
        }
        return KbSyncResult(cards.size, tombs.size, gotCards, gotTombs, rejected)
    }
}
