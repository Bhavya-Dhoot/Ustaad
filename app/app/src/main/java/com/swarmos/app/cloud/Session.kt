package com.swarmos.app.cloud

import java.security.KeyPair
import java.util.concurrent.atomic.AtomicLong

/**
 * Keys shared by two members after the exchange of docs/CLOUD-PROTOCOL.md section 5 (or by a token client and its issuer,
 * section 8). Memory only: a restart ends every session. The MAC key signs requests; the two sealing keys are one per direction.
 */
class Session internal constructor(
    /** 16 hex characters, sent in `X-Swarm-Sid` so the receiver finds the key. */
    val id: String,
    val peerNodeId: String,
    /** Member id of the peer's key. */
    val peerId: String,
    val peerKind: MemberKind,
    internal val macKey: ByteArray,
    private val sendKey: ByteArray,
    private val recvKey: ByteArray,
    val startedAt: Long,
    val initiator: Boolean,
    /** What both sides agreed in the exchange (11.1): cipher, channel, bulk ceiling, frame size, shared links. */
    val negotiated: Negotiated,
    /** A token session (section 8): the peer is a token client of this device. */
    val viaToken: Boolean = false,
    /** The peer's tag was not from a recent epoch: it came in on the small resync path (5.2) and should pull the chain and the secrets. */
    val viaResync: Boolean = false,
) {
    private val streams = AtomicLong(0)

    /** When a request last used this session; a browser's session ends after 12 hours of silence (8.5). */
    @Volatile internal var lastUsed = startedAt

    /** One number per stream this side opens over HTTP, never reused in this session (a stream number is a u32 with the top bit 0). */
    fun nextStreamNo(): Int {
        val n = streams.getAndIncrement()
        check(n <= Int.MAX_VALUE) { "stream numbers exhausted: make a new session" }
        return n.toInt()
    }

    private fun limit(channel: Boolean) = if (channel) negotiated.maxFrame else FrameCodec.MAX_PLAIN

    /** [channel] streams are held to the negotiated largest frame; one-shot HTTP bodies keep the 4 MiB limit (Codex Session.kt:36). */
    fun sealer(stream: Int, context: ByteArray, type: Int, thenType: Int = type, channel: Boolean = false) =
        FrameSealer(sendKey, stream, context, type, negotiated.aead, limit(channel), thenType)

    fun opener(stream: Int, context: ByteArray, type: Int, thenType: Int = type, channel: Boolean = false) =
        FrameOpener(recvKey, stream, context, type, negotiated.aead, limit(channel), thenType)

    private fun bulkLimit(): Int {
        check(negotiated.bulkStreams > 0) { "the bulk path was not negotiated" }
        return minOf(negotiated.maxFrame, FrameCodec.MAX_BULK_PLAIN)
    }

    fun bulkSealer(stream: Int, context: ByteArray) = BulkSealer(sendKey, stream, context, negotiated.aead, bulkLimit())
    /** [declaredSize] is the size the bulk open request declared; the opener refuses anything beyond it (Codex Frames.kt:229). */
    fun bulkOpener(stream: Int, context: ByteArray, declaredSize: Long) = BulkOpener(recvKey, stream, context, declaredSize, negotiated.aead, bulkLimit())

    fun expired(now: Long): Boolean = now - startedAt >= LIFETIME_MS

    companion object {
        const val LIFETIME_MS = 24L * 3600 * 1000
        const val BROWSER_IDLE_MS = 12L * 3600 * 1000
    }
}

/**
 * The three-flight exchange (section 5): Init (initiator, signed), Reply (responder, signed), Finish (initiator, signed). Both
 * later signatures cover the same transcript. The peer must be a current member of kind node or client; the key that is checked
 * is the one in the chain, never one in a message. The Init carries the initiator's signature too, so the responder knows the
 * sender holds the key before it spends a nonce record, a resync place, a pending slot, a key generation or a signature (5.2).
 */
class SessionHandshake(
    private val cloudId: ByteArray,
    private val identity: DeviceIdentity,
    private val selfNodeId: String,
    private val state: () -> ChainState,
    /** Discovery keys of the current epoch and, during the grace, the previous one (3.7); the first is used for our own tag. */
    private val discoveryKeys: () -> List<ByteArray>,
    /** What this device offers; the default is a baseline device (empty ext). */
    private val caps: Caps = Caps.BASELINE,
    private val pendingMs: Long = 10_000L,
    private val maxPending: Int = 64,
    private val maxPendingPerSource: Int = 4,
    private val maxInitsPerMinutePerSource: Int = 30,
    maxResyncPerMinute: Int = 6,
    maxResyncPerMinutePerMember: Int = 2,
    maxAddressRecords: Int = 4096,
    private val maxSeenInits: Int = 4096,
    private val maxSeenPerMember: Int = 64,
) {
    private val ownExt = caps.encodeExt()
    private val selfFp: ByteArray = Crypto.fingerprint(identity.publicKey)
    private val selfId: String = Hex.of(selfFp)

    internal class Slot(val source: String, val expiresAt: Long) { @Volatile var used = false }

    /** The memory of one Init nonce: until when, and whose it was. Only Inits whose signature verified are ever recorded. */
    private class Seen(val until: Long, val memberId: String)

    private val lock = Any()
    private val pending = ArrayList<Slot>()
    private val seenInits = LinkedHashMap<String, Seen>()

    // Every table below has a hard cap and cleans up on the paths that refuse (Codex Session.kt:275).
    private val initBudget = WindowBudget(maxInitsPerMinutePerSource, maxKeys = maxAddressRecords) // per source address, before anything is known of the sender
    private val resyncAll = WindowBudget(maxResyncPerMinute, maxKeys = 1) // Inits with an unrecognised tag, charged only after the sender proved its key
    private val resyncMember = WindowBudget(maxResyncPerMinutePerMember, maxKeys = 1024)

    /** Test instruments: the sizes of the tables an attacker might try to fill. */
    internal val seenCount: Int get() = synchronized(lock) { seenInits.size }
    internal val addressRecords: Int get() = initBudget.size
    internal val resyncRecords: Int get() = resyncMember.size

    /** Test hook: runs after the tag check and before the atomic reservation, so a test can force two calls to interleave. */
    @Volatile internal var raceHook: (() -> Unit)? = null

    private fun header(r: Reader, type: Int) {
        if (r.u8() != 1 || r.u8() != type) malformed("session message")
    }

    /** Current member, with a device key, whose node id is the one it claims. One answer for every failure (no oracle). */
    private fun peer(fp: String, nodeId: String): ChainMember {
        val st = state()
        val m = st.member(fp)
        if (st.member(selfId) == null || m == null || m.nodeId != nodeId || m.publicKey == null) throw ProtocolError(CloudError.NOT_MEMBER)
        return m
    }

    /** Both offers are signed too: whoever strips one on the way makes the signature fail (no downgrade). */
    private fun transcript(
        nodeI: String, nodeR: String, fpI: ByteArray, fpR: ByteArray,
        ephI: ByteArray, ephR: ByteArray, nonceI: ByteArray, nonceR: ByteArray, extI: ByteArray, extR: ByteArray,
    ): ByteArray = Writer().raw(lbl("session")).u8(1).fixed(cloudId, 16).str(nodeI).str(nodeR).fixed(fpI, 32).fixed(fpR, 32)
        .fixed(ephI, Crypto.PUB_BYTES).fixed(ephR, Crypto.PUB_BYTES).fixed(nonceI, 32).fixed(nonceR, 32).lp(extI).lp(extR).bytes()

    private fun signedPart(role: String, transcript: ByteArray) = concat(lbl("session/sig/$role"), transcript)

    /** What the initiator signs in the Init: everything in it, and the responder it is meant for (so it is no good at another one). */
    private fun initProof(nodeI: String, fpI: ByteArray, ephI: ByteArray, nonceI: ByteArray, tag: ByteArray, extI: ByteArray, nodeR: String, fpR: ByteArray): ByteArray =
        Writer().raw(lbl("session/init")).fixed(cloudId, 16).str(nodeI).fixed(fpI, 32).fixed(ephI, Crypto.PUB_BYTES).fixed(nonceI, 32)
            .fixed(tag, Discovery.TAG_BYTES).lp(extI).str(nodeR).fixed(fpR, 32).bytes()

    /** Keys from the ECDH result, the nonces as salt, distinct labels, the transcript hash in `info` (section 5.3). */
    private fun derive(
        secret: ByteArray, nonceI: ByteArray, nonceR: ByteArray, transcript: ByteArray, initiator: Boolean, peer: ChainMember, now: Long,
        negotiated: Negotiated, viaResync: Boolean = false,
    ): Session {
        val th = Crypto.sha256(transcript)
        val prk = Crypto.hkdfExtract(concat(nonceI, nonceR), secret)
        fun key(label: String, len: Int) = Crypto.hkdfExpand(prk, concat(lbl(label), th), len)
        val i2r = key("session/seal/i2r", 32)
        val r2i = key("session/seal/r2i", 32)
        val session = Session(
            id = Hex.of(key("session/id", 8)), peerNodeId = peer.nodeId, peerId = peer.id, peerKind = peer.kind,
            macKey = key("session/mac", 32), sendKey = if (initiator) i2r else r2i, recvKey = if (initiator) r2i else i2r,
            startedAt = now, initiator = initiator, negotiated = negotiated, viaResync = viaResync,
        )
        Crypto.wipe(secret, prk)
        return session
    }

    // ---- initiator ----------------------------------------------------------------------------------------------

    /** [peerMemberId] is the key fingerprint the caller expects (from the chain), not only the node id (Codex Session.kt:110). */
    fun initiate(peerNodeId: String, peerMemberId: String, now: Long): Initiating {
        Shape.requireNodeId(peerNodeId)
        val fpR = Hex.parse(peerMemberId)
        require(fpR.size == 32) { "member id" }
        val eph = Crypto.newKeyPair()
        val ephPub = Crypto.rawPublic(eph.public)
        val nonce = Crypto.random(32)
        val keys = discoveryKeys()
        check(keys.isNotEmpty()) { "no discovery key for the current epoch" }
        val tag = Discovery.tag(keys[0], selfNodeId, Discovery.slot(now))
        val proof = identity.sign(initProof(selfNodeId, selfFp, ephPub, nonce, tag, ownExt, peerNodeId, fpR))
        val init = Writer().u8(1).u8(1).str(selfNodeId).fixed(selfFp, 32).fixed(ephPub, Crypto.PUB_BYTES).fixed(nonce, 32)
            .fixed(tag, Discovery.TAG_BYTES).lp(ownExt).fixed(proof, Crypto.SIG_BYTES).bytes()
        return Initiating(peerNodeId, peerMemberId, eph, ephPub, nonce, init)
    }

    class Completed(val finish: ByteArray, val session: Session)

    inner class Initiating internal constructor(
        val peerNodeId: String,
        private val peerMemberId: String,
        eph: KeyPair,
        private val ephPub: ByteArray,
        private val nonce: ByteArray,
        /** Flight 1, to send. */
        val init: ByteArray,
    ) {
        private var eph: KeyPair? = eph
        private var done: Completed? = null
        private var doneReply: ByteArray? = null
        private var dead = false

        internal fun holdsEphemeralKey(): Boolean = eph != null

        /**
         * Checks flight 2 and answers with flight 3 and the session. Single use (Codex Session.kt:132): a second call with the same
         * Reply returns the same object, never a second session with fresh counters; any other Reply fails. A failure kills the
         * exchange. The ephemeral private key is dropped either way (Codex Session.kt:190).
         */
        @Synchronized fun complete(reply: ByteArray, now: Long): Completed {
            done?.let { d -> if (doneReply!!.contentEquals(reply)) return d else throw ProtocolError(CloudError.NOT_MEMBER) }
            if (dead) throw ProtocolError(CloudError.NOT_MEMBER)
            val key = eph
            eph = null
            dead = true
            if (key == null) throw ProtocolError(CloudError.NOT_MEMBER)
            val r = Reader(reply)
            header(r, 2)
            val nodeR = Shape.requireNodeId(r.str(8))
            val fpR = r.fixed(32)
            val ephR = r.fixed(Crypto.PUB_BYTES)
            val nonceR = r.fixed(32)
            val extR = r.lp(Caps.MAX_EXT)
            val sigR = r.fixed(Crypto.SIG_BYTES)
            r.end()
            if (nodeR != peerNodeId || Hex.of(fpR) != peerMemberId || !Crypto.isValidPoint(ephR)) throw ProtocolError(CloudError.NOT_MEMBER)
            val m = peer(Hex.of(fpR), nodeR)
            val capsR = Caps.decodeExt(extR)
            val t = transcript(selfNodeId, nodeR, selfFp, fpR, ephPub, ephR, nonce, nonceR, ownExt, extR)
            if (!Crypto.ecdsaVerify(m.publicKey!!, signedPart("r", t), sigR)) throw ProtocolError(CloudError.NOT_MEMBER)
            val sigI = identity.sign(signedPart("i", t))
            val session = derive(Crypto.ecdh(key.private, ephR), nonce, nonceR, t, true, m, now, Negotiated.of(caps, capsR))
            val c = Completed(Writer().u8(1).u8(3).fixed(sigI, Crypto.SIG_BYTES).bytes(), session)
            done = c
            doneReply = reply.copyOf()
            dead = false
            return c
        }
    }

    // ---- responder ----------------------------------------------------------------------------------------------

    /**
     * Answers flight 1 from [source] (the peer's address, for the budgets). Order (docs 5.2): shape; the cheap per-address budget; the
     * discovery tag (a filter only: no match leads to the resync path); the sender must be a current member whose listed key signed
     * this Init (**the identity proof**: until it holds, nothing is recorded, charged or reserved, and a captured identity cannot
     * be probed because a forged Init and a removed member get the same answer); then ONE atomic step (Codex Session.kt:228): the
     * nonce, the resync budget if the tag was unknown, the per-member and global nonce tables, a pending slot; and only then key
     * generation and the signature (Codex Session.kt:170).
     */
    fun respond(init: ByteArray, now: Long, source: String = ""): Responding {
        val r = Reader(init)
        header(r, 1)
        val nodeI = Shape.requireNodeId(r.str(8))
        val fpI = r.fixed(32)
        val ephI = r.fixed(Crypto.PUB_BYTES)
        val nonceI = r.fixed(32)
        val tag = r.fixed(Discovery.TAG_BYTES)
        val extI = r.lp(Caps.MAX_EXT)
        val proof = r.fixed(Crypto.SIG_BYTES)
        r.end()
        if (!initBudget.allow(source, now)) throw ProtocolError(CloudError.BUSY) // the cheap per-address budget; a refusal leaves no record
        // the tag is only a cheap filter: a tag from no recent epoch leads to the small resync path, not to a refusal
        val tagOk = discoveryKeys().any { Discovery.matches(it, nodeI, tag, now) }
        // the identity proof: a current member with this node id whose listed key signed exactly this Init for this responder
        val m = peer(Hex.of(fpI), nodeI)
        if (!Crypto.ecdsaVerify(m.publicKey!!, initProof(nodeI, fpI, ephI, nonceI, tag, extI, selfNodeId, selfFp), proof)) throw ProtocolError(CloudError.NOT_MEMBER)
        raceHook?.invoke()
        val nonceKey = Hex.of(nonceI)
        val slot = synchronized(lock) {
            seenInits.entries.removeAll { it.value.until <= now }
            if (nonceKey in seenInits) throw ProtocolError(CloudError.NOT_MEMBER) // a replayed Init
            if (!tagOk) spendResync(m.id, now)
            if (seenInits.size >= maxSeenInits || seenInits.values.count { it.memberId == m.id } >= maxSeenPerMember) throw ProtocolError(CloudError.BUSY)
            pending.removeAll { it.used || now > it.expiresAt }
            if (pending.size >= maxPending || pending.count { it.source == source } >= maxPendingPerSource) throw ProtocolError(CloudError.BUSY)
            seenInits[nonceKey] = Seen(now + SEEN_INIT_MS, m.id)
            Slot(source, now + pendingMs).also { pending.add(it) }
        }
        try {
            if (!Crypto.isValidPoint(ephI)) throw ProtocolError(CloudError.NOT_MEMBER)
            val capsI = Caps.decodeExt(extI)
            val eph = Crypto.newKeyPair()
            val ephR = Crypto.rawPublic(eph.public)
            val nonceR = Crypto.random(32)
            val t = transcript(nodeI, selfNodeId, fpI, selfFp, ephI, ephR, nonceI, nonceR, extI, ownExt)
            val sigR = identity.sign(signedPart("r", t))
            val reply = Writer().u8(1).u8(2).str(selfNodeId).fixed(selfFp, 32).fixed(ephR, Crypto.PUB_BYTES).fixed(nonceR, 32).lp(ownExt)
                .fixed(sigR, Crypto.SIG_BYTES).bytes()
            return Responding(reply, m, eph, ephI, nonceI, nonceR, t, slot, Negotiated.of(caps, capsI), !tagOk)
        } catch (e: Throwable) {
            slot.used = true // give the slot back; a failed exchange holds nothing
            throw e
        }
    }

    /**
     * The small, separate budget of the resync path, charged only to a member that has proved its key: 6 a minute in all and 2 a
     * minute per member. Called with [lock] held. A refusal leaves nothing behind.
     */
    private fun spendResync(memberId: String, now: Long) {
        if (!resyncMember.allow(memberId, now)) throw ProtocolError(CloudError.NOT_MEMBER)
        if (!resyncAll.allow("", now)) throw ProtocolError(CloudError.NOT_MEMBER)
    }

    inner class Responding internal constructor(
        /** Flight 2, to send. */
        val reply: ByteArray,
        private val peerMember: ChainMember,
        eph: KeyPair,
        private val ephI: ByteArray,
        private val nonceI: ByteArray,
        private val nonceR: ByteArray,
        private val transcript: ByteArray,
        private val slot: Slot,
        private val negotiated: Negotiated,
        private val viaResync: Boolean,
    ) {
        private var eph: KeyPair? = eph

        internal fun holdsEphemeralKey(): Boolean = eph != null

        /** Checks flight 3 once; a second call, or one after the 10 seconds, fails. The slot and the ephemeral key are released at once. */
        @Synchronized fun finish(finish: ByteArray, now: Long): Session {
            val key: KeyPair? = synchronized(lock) {
                if (slot.used || now > slot.expiresAt) { slot.used = true; eph = null; throw ProtocolError(CloudError.NOT_MEMBER) }
                slot.used = true
                val k = eph
                eph = null
                k
            }
            if (key == null) throw ProtocolError(CloudError.NOT_MEMBER)
            val r = Reader(finish)
            header(r, 3)
            val sigI = r.fixed(Crypto.SIG_BYTES)
            r.end()
            // the member may have been removed during the exchange: look again
            val m = peer(peerMember.id, peerMember.nodeId)
            if (!Crypto.ecdsaVerify(m.publicKey!!, signedPart("i", transcript), sigI)) throw ProtocolError(CloudError.NOT_MEMBER)
            return derive(Crypto.ecdh(key.private, ephI), nonceI, nonceR, transcript, false, m, now, negotiated, viaResync)
        }
    }

    companion object {
        const val SEEN_INIT_MS = 600_000L
    }
}

/** The live sessions of this device (section 5.4). A lookup fails once the session is old or idle, or either member is gone. */
class SessionStore(private val selfId: String, private val maxPerPeer: Int = 4) {
    private val sessions = LinkedHashMap<String, Session>()

    @Synchronized fun add(s: Session) {
        sessions[s.id] = s
        val same = sessions.values.filter { it.peerId == s.peerId }
        if (same.size > maxPerPeer) same.sortedBy { it.startedAt }.take(same.size - maxPerPeer).forEach { sessions.remove(it.id) }
    }

    private fun alive(s: Session, now: Long, state: ChainState): Boolean {
        val peerOk = state.member(s.peerId)?.nodeId == s.peerNodeId
        val idle = s.peerKind == MemberKind.BROWSER && now - s.lastUsed > Session.BROWSER_IDLE_MS
        return !s.expired(now) && !idle && state.member(selfId) != null && peerOk
    }

    /** The session, unless it is unknown, 24 hours old, a browser's that was silent for 12 hours, or this device or its peer is not a current member. */
    @Synchronized fun get(id: String, now: Long, state: ChainState): Session? {
        val s = sessions[id] ?: return null
        if (!alive(s, now, state)) {
            sessions.remove(id)
            return null
        }
        s.lastUsed = now
        return s
    }

    /** Drops every session that [get] would refuse; returns how many. A sweep is not a use. */
    @Synchronized fun sweep(now: Long, state: ChainState): Int {
        val before = sessions.size
        sessions.entries.removeAll { !alive(it.value, now, state) }
        return before - sessions.size
    }

    @Synchronized fun clear() = sessions.clear()

    val size: Int
        @Synchronized get() = sessions.size
}
