package com.swarmos.app.kb

import com.swarmos.app.core.Signer
import com.swarmos.app.core.toHex
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** Where an answer came from: the phone, the file title and its date. Never the file text. [priv]: the file sits in a private folder of the saving phone. */
@Serializable
data class KbSource(val phone: String, val title: String, val dateMs: Long, val priv: Boolean = false)

object KbTier {
    const val PRIVATE = "PRIVATE"
    const val SWARM = "SWARM"
}

/**
 * The part of a knowledge card that travels between phones. [id] is the SHA-256 of the canonical content; [mac] is an
 * HMAC with the pairing secret over the canonical content and [auditHash] (the `kb.save` entry in the author's audit log).
 * The MAC proves the card was made by a phone that holds the pairing secret and was not changed on the way; it does not
 * say which of those phones, so [authorId] and [authorName] are a claim by a swarm member.
 */
@Serializable
data class KbCard(
    val id: String,
    val question: String,
    val answer: String,
    val sources: List<KbSource>,
    val authorId: String,
    val authorName: String,
    val createdMs: Long,
    val auditHash: String,
    val mac: String,
)

/** A card as one phone holds it. The tier is this phone's choice; a card that arrived from the swarm is always SWARM. */
data class KbEntry(val card: KbCard, val tier: String, val revokedMs: Long?, val everShared: Boolean) {
    val usable: Boolean get() = revokedMs == null
}

/** "The author withdrew card [id]". Replicates like a card and wins over it whenever it arrives. */
@Serializable
data class KbTombstone(val id: String, val authorId: String, val revokedMs: Long, val mac: String)

object KbLimits {
    const val MAX_QUESTION = 300
    const val MAX_ANSWER = 600
    const val MAX_SOURCES = 8
    const val MAX_FIELD = 120
    const val MAX_CARD_BYTES = 4096
    const val MAX_CARDS = 500
    const val MAX_CARDS_PER_SYNC = 50
    const val MAX_BYTES_PER_SYNC = 128 * 1024
    const val MAX_OFFER_ITEMS = 1000

    /**
     * How long a revocation matters. A card older than this is refused when it arrives, and a tombstone older than this is not kept
     * (see [KbService.tombstoneExpired]). The two rules go together: a tombstone is only dropped when the card it revoked is too old
     * to be accepted again, so dropping it can never let that card come back.
     */
    const val HORIZON_MS = 400L * 24 * 60 * 60 * 1000
}

object KbCanon {
    private fun part(s: String) = "${s.length}:$s"

    /** Unambiguous (every field length-prefixed) text of everything the id covers. */
    fun content(
        question: String, answer: String, sources: List<KbSource>, authorId: String, authorName: String, createdMs: Long,
    ): String = buildString {
        append("kb1")
        append(part(question)).append(part(answer)).append(part(sources.size.toString()))
        for (s in sources) append(part(s.phone)).append(part(s.title)).append(part(s.dateMs.toString())).append(part(if (s.priv) "1" else "0"))
        append(part(authorId)).append(part(authorName)).append(part(createdMs.toString()))
    }

    fun content(c: KbCard) = content(c.question, c.answer, c.sources, c.authorId, c.authorName, c.createdMs)

    fun idOf(content: String): String = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8)).toHex()

    fun macOf(secret: String, content: String, auditHash: String): String =
        Signer.hmacSha256Hex(secret, "kb1-card\n" + content + part(auditHash))

    fun tombstoneMac(secret: String, id: String, authorId: String, revokedMs: Long): String =
        Signer.hmacSha256Hex(secret, "kb1-revoke\n$id\n$authorId\n$revokedMs")

    /** The card's MAC verifies under [secret]. */
    fun sealedBy(c: KbCard, secret: String): Boolean = same(macOf(secret, content(c), c.auditHash), c.mac)

    fun tombstoneSealedBy(t: KbTombstone, secret: String): Boolean = same(tombstoneMac(secret, t.id, t.authorId, t.revokedMs), t.mac)

    fun make(
        secret: String, question: String, answer: String, sources: List<KbSource>, authorId: String, authorName: String,
        createdMs: Long, auditHash: String,
    ): KbCard {
        val content = content(question, answer, sources, authorId, authorName, createdMs)
        return KbCard(idOf(content), question, answer, sources, authorId, authorName, createdMs, auditHash, macOf(secret, content, auditHash))
    }

    fun tombstone(secret: String, id: String, authorId: String, revokedMs: Long) =
        KbTombstone(id, authorId, revokedMs, tombstoneMac(secret, id, authorId, revokedMs))

    private fun same(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.lowercase().toByteArray())

    /** Why a card from another phone is refused, or null when it is good. [existsHere]: this phone already holds a card with this id. */
    fun reject(c: KbCard, secret: String, selfId: String, existsHere: Boolean): String? {
        if (c.question.isBlank() || c.question.length > KbLimits.MAX_QUESTION) return "question missing or too long"
        if (c.answer.isBlank() || c.answer.length > KbLimits.MAX_ANSWER) return "answer missing or too long"
        if (c.sources.size > KbLimits.MAX_SOURCES) return "too many sources"
        if (c.sources.any { it.phone.length > KbLimits.MAX_FIELD || it.title.length > KbLimits.MAX_FIELD }) return "source field too long"
        if (c.authorName.length > KbLimits.MAX_FIELD || c.authorId.length > KbLimits.MAX_FIELD) return "author field too long"
        val content = content(c)
        if (!same(idOf(content), c.id)) return "id does not match the content"
        if (!same(macOf(secret, content, c.auditHash), c.mac)) return "MAC does not verify"
        if (c.sources.any { it.priv }) return "a source is in a private folder"
        if (c.authorId == selfId && !existsHere) return "claims this phone as author"
        return null
    }

    fun rejectTombstone(t: KbTombstone, secret: String, selfId: String, card: KbCard?): String? {
        if (!same(tombstoneMac(secret, t.id, t.authorId, t.revokedMs), t.mac)) return "MAC does not verify"
        if (card != null && card.authorId != t.authorId) return "not the author of that card"
        if (t.authorId == selfId && card == null) return "claims this phone as author"
        return null
    }
}

/** What the owner may do with a card. */
object KbPolicy {
    fun hasPrivateSource(c: KbCard) = c.sources.any { it.priv }

    /** Null when the card may be shared with the swarm, otherwise the reason, in words for the owner. */
    fun whyNotShareable(e: KbEntry, selfId: String): String? = when {
        e.card.authorId != selfId -> "Only the author can change who this card is shared with"
        !e.usable -> "This card is revoked"
        hasPrivateSource(e.card) -> "A source of this card is in a private folder, so it stays on this phone"
        else -> null
    }

    fun canRevoke(e: KbEntry, selfId: String) = e.card.authorId == selfId && e.usable
}
