package com.swarmos.app.kb

import com.swarmos.app.core.SwarmJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** What the service needs from storage. [KbStore] is the SQLite one; tests use a map. */
interface KbBacking {
    fun entry(id: String): KbEntry?
    fun put(e: KbEntry)
    fun all(): List<KbEntry>
    fun tombstone(id: String): KbTombstone?
    fun putTombstone(t: KbTombstone)
    fun tombstones(): List<KbTombstone>
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
) {
    // ---- the owner's side -----------------------------------------------------------------------------------

    fun entries(): List<KbEntry> = backing.all().sortedByDescending { it.card.createdMs }

    @Synchronized
    fun save(question: String, answer: String, sources: List<KbSource>, share: Boolean): KbSave {
        val q = question.trim()
        val a = answer.trim()
        if (q.isEmpty() || q.length > KbLimits.MAX_QUESTION) return KbSave.Refused("The question is empty or longer than ${KbLimits.MAX_QUESTION} characters")
        if (a.isEmpty() || a.length > KbLimits.MAX_ANSWER) return KbSave.Refused("The answer is empty or longer than ${KbLimits.MAX_ANSWER} characters")
        if (sources.isEmpty()) return KbSave.Refused("A card needs at least one source")
        if (sources.size > KbLimits.MAX_SOURCES) return KbSave.Refused("A card holds at most ${KbLimits.MAX_SOURCES} sources")
        val clipped = sources.map { it.copy(phone = it.phone.take(KbLimits.MAX_FIELD), title = it.title.take(KbLimits.MAX_FIELD)) }
        if (share && clipped.any { it.priv }) return KbSave.Refused("A source is in a private folder, so this card cannot be shared")
        if (backing.all().size >= KbLimits.MAX_CARDS) return KbSave.Refused("The knowledge bank is full (${KbLimits.MAX_CARDS} cards)")
        val createdMs = clock()
        val content = KbCanon.content(q, a, clipped, selfId(), selfName().take(KbLimits.MAX_FIELD), createdMs)
        val id = KbCanon.idOf(content)
        val tier = if (share) KbTier.SWARM else KbTier.PRIVATE
        val auditHash = audit("kb.save", JsonObject(mapOf("id" to JsonPrimitive(id), "tier" to JsonPrimitive(tier))))
        val card = KbCard(id, q, a, clipped, selfId(), selfName().take(KbLimits.MAX_FIELD), createdMs, auditHash, KbCanon.macOf(secret(), content, auditHash))
        val e = KbEntry(card, tier, null, everShared = share)
        backing.put(e)
        return KbSave.Saved(e)
    }

    /** Author only. Sharing puts the card in the offers; stopping stops offering it (copies other phones already hold stay until it is revoked). */
    @Synchronized
    fun setShared(id: String, share: Boolean): String? {
        val e = backing.entry(id) ?: return "No such card"
        if (share) KbPolicy.whyNotShareable(e, selfId())?.let { return it }
        else if (e.card.authorId != selfId()) return "Only the author can change who this card is shared with"
        val tier = if (share) KbTier.SWARM else KbTier.PRIVATE
        backing.put(e.copy(tier = tier, everShared = e.everShared || share))
        audit(if (share) "kb.share" else "kb.unshare", JsonObject(mapOf("id" to JsonPrimitive(id))))
        return null
    }

    /** Author only. A card that was never shared is just marked; a shared one also leaves a tombstone, which replicates. */
    @Synchronized
    fun revoke(id: String): String? {
        val e = backing.entry(id) ?: return "No such card"
        if (!KbPolicy.canRevoke(e, selfId())) return if (e.usable) "Only the author can revoke a card" else "Already revoked"
        val now = clock()
        backing.put(e.copy(revokedMs = now))
        if (e.everShared) backing.putTombstone(KbCanon.tombstone(secret(), id, selfId(), now))
        audit("kb.revoke", JsonObject(mapOf("id" to JsonPrimitive(id))))
        return null
    }

    // ---- the two halves of a sync ---------------------------------------------------------------------------

    /** What this phone offers: shared cards that are not revoked, and every tombstone it holds. PRIVATE cards never appear. */
    fun offerItems(): List<KbOfferItem> {
        val cards = backing.all().filter { it.tier == KbTier.SWARM && it.usable }.map { KbOfferItem(it.card.id) }
        val tombs = backing.tombstones().map { KbOfferItem(it.id, it.revokedMs) }
        return (cards + tombs).take(KbLimits.MAX_OFFER_ITEMS)
    }

    fun handleOffer(o: KbOffer): KbOfferReply {
        val missing = ArrayList<String>()
        val needTombstones = ArrayList<String>()
        for (item in o.items.take(KbLimits.MAX_OFFER_ITEMS)) {
            if (item.revokedMs != null) {
                if (backing.tombstone(item.id) == null) needTombstones += item.id
            } else if (backing.entry(item.id) == null && backing.tombstone(item.id) == null) {
                missing += item.id
            }
        }
        return KbOfferReply(missing.take(KbLimits.MAX_CARDS_PER_SYNC), needTombstones.take(KbLimits.MAX_CARDS_PER_SYNC), offerItems())
    }

    /** Neither the card nor a tombstone for it is held here. */
    fun isUnknown(id: String) = backing.entry(id) == null && backing.tombstone(id) == null

    fun lacksTombstone(id: String) = backing.tombstone(id) == null

    /** Stores what is pushed (after checking it), then answers with what was asked for. */
    fun handleCards(c: KbCards): KbCards {
        val rejected = ArrayList<String>()
        var accepted = 0
        var bytes = 0
        for (t in c.tombstones.take(KbLimits.MAX_CARDS_PER_SYNC)) {
            val why = ingestTombstone(t)
            if (why == null) accepted++ else rejected += "tombstone ${t.id.take(8)}: $why"
        }
        for (card in c.cards.take(KbLimits.MAX_CARDS_PER_SYNC)) {
            bytes += SwarmJson.encodeToString(card).length
            val why = if (bytes > KbLimits.MAX_BYTES_PER_SYNC) "over the byte limit for one sync" else ingestCard(card)
            if (why == null) accepted++ else rejected += "card ${card.id.take(8)}: $why"
        }
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
        if (backing.all().size >= KbLimits.MAX_CARDS) return "the knowledge bank is full"
        val t = backing.tombstone(card.id)?.takeIf { it.authorId == card.authorId }
        backing.put(KbEntry(card, KbTier.SWARM, t?.revokedMs, everShared = true))
        return null
    }

    @Synchronized
    fun ingestTombstone(t: KbTombstone): String? {
        val e = backing.entry(t.id)
        KbCanon.rejectTombstone(t, secret(), selfId(), e?.card)?.let { return it }
        if (backing.tombstone(t.id) == null) backing.putTombstone(t)
        if (e != null && e.revokedMs == null) backing.put(e.copy(revokedMs = t.revokedMs))
        return null
    }

    /** The audit line for a finished sync; the card texts are not logged. */
    fun auditSync(peerId: String, r: KbSyncResult) {
        audit(
            "kb.sync",
            JsonObject(mapOf(
                "peer" to JsonPrimitive(peerId), "sent" to JsonPrimitive(r.sentCards + r.sentTombstones),
                "got" to JsonPrimitive(r.gotCards + r.gotTombstones), "rejected" to JsonPrimitive(r.rejected.size),
            )),
        )
    }
}

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
        val wantTombs = theirs.filter { it.revokedMs != null && local.lacksTombstone(it.id) }.map { it.id }.take(KbLimits.MAX_CARDS_PER_SYNC)
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
