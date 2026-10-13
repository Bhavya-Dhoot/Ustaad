package com.swarmos.app.cloud

import java.security.KeyPair
import java.util.concurrent.atomic.AtomicLong

/**
 * Keys shared by two members after the exchange of docs/CLOUD-PROTOCOL.md section 5. Memory only: a restart ends every
 * session. The MAC key signs requests; the two sealing keys are one per direction.
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
) {
    private val streams = AtomicLong(0)

    /** One number per stream this side opens over HTTP, never reused in this session (a stream number is a u32 with the top bit 0). */
    fun nextStreamNo(): Int {
        val n = streams.getAndIncrement()
        check(n <= Int.MAX_VALUE) { "stream numbers exhausted: make a new session" }
        return n.toInt()
    }

    fun sealer(stream: Int, context: ByteArray, type: Int) = FrameSealer(sendKey, stream, context, type, negotiated.aead)
    fun opener(stream: Int, context: ByteArray, type: Int) = FrameOpener(recvKey, stream, context, type, negotiated.aead)
    fun bulkSealer(stream: Int, context: ByteArray) = BulkSealer(sendKey, stream, context, negotiated.aead)
    fun bulkOpener(stream: Int, context: ByteArray) = BulkOpener(recvKey, stream, context, negotiated.aead)

    fun expired(now: Long): Boolean = now - startedAt >= LIFETIME_MS

    companion object {
        const val LIFETIME_MS = 24L * 3600 * 1000
    }
}

/**
 * The three-flight exchange (section 5): Init (initiator), Reply (responder, signed), Finish (initiator, signed). Both
 * signatures cover the same transcript, so each side proves its key and sees the other's ephemeral key. The peer must be
 * a current member of kind node or client; the key that is checked is the one in the member list, never one in a message.
 */
class SessionHandshake(
    private val cloudId: ByteArray,
    private val identity: DeviceIdentity,
    private val selfNodeId: String,
    private val state: () -> MemberState,
    private val discoveryKey: () -> ByteArray,
    /** What this device offers; the default is a baseline device (empty ext). */
    private val caps: Caps = Caps.BASELINE,
    private val pendingMs: Long = 10_000L,
    private val maxPending: Int = 64,
) {
    private val ownExt = caps.encodeExt()
    private val selfFp: ByteArray = Crypto.fingerprint(identity.publicKey)
    private val pending = ArrayList<Responding>()

    private fun header(r: Reader, type: Int) {
        if (r.u8() != 1 || r.u8() != type) malformed("session message")
    }

    /** Current member, with a device key, whose node id is the one it claims. One answer for every failure (no oracle). */
    private fun peer(fp: String, nodeId: String): Member {
        val st = state()
        val m = st.member(fp)
        if (st.member(Hex.of(selfFp)) == null || m == null || m.nodeId != nodeId || m.publicKey == null) {
            throw ProtocolError(CloudError.NOT_MEMBER)
        }
        return m
    }

    /** Both offers are signed too: whoever strips one on the way makes the signature fail (no downgrade). */
    private fun transcript(
        nodeI: String, nodeR: String, fpI: ByteArray, fpR: ByteArray,
        ephI: ByteArray, ephR: ByteArray, nonceI: ByteArray, nonceR: ByteArray, extI: ByteArray, extR: ByteArray,
    ): ByteArray = Writer().raw(lbl("session")).u8(1).fixed(cloudId, 16).str(nodeI).str(nodeR).fixed(fpI, 32).fixed(fpR, 32)
        .fixed(ephI, Crypto.PUB_BYTES).fixed(ephR, Crypto.PUB_BYTES).fixed(nonceI, 32).fixed(nonceR, 32).lp(extI).lp(extR).bytes()

    private fun signedPart(role: String, transcript: ByteArray) = concat(lbl("session/sig/$role"), transcript)

    /** Keys from the ECDH result, the nonces as salt, distinct labels, the transcript hash in `info` (section 5.3). */
    private fun derive(
        secret: ByteArray, nonceI: ByteArray, nonceR: ByteArray, transcript: ByteArray, initiator: Boolean, peer: Member, now: Long,
        negotiated: Negotiated,
    ): Session {
        val th = Crypto.sha256(transcript)
        val prk = Crypto.hkdfExtract(concat(nonceI, nonceR), secret)
        fun key(label: String, len: Int) = Crypto.hkdfExpand(prk, concat(lbl(label), th), len)
        val i2r = key("session/seal/i2r", 32)
        val r2i = key("session/seal/r2i", 32)
        val session = Session(
            id = Hex.of(key("session/id", 8)), peerNodeId = peer.nodeId, peerId = peer.id, peerKind = peer.kind,
            macKey = key("session/mac", 32), sendKey = if (initiator) i2r else r2i, recvKey = if (initiator) r2i else i2r,
            startedAt = now, initiator = initiator, negotiated = negotiated,
        )
        Crypto.wipe(secret, prk)
        return session
    }

    // ---- initiator ----------------------------------------------------------------------------------------------

    fun initiate(peerNodeId: String, now: Long): Initiating {
        Shape.requireNodeId(peerNodeId)
        val eph = Crypto.newKeyPair()
        val ephPub = Crypto.rawPublic(eph.public)
        val nonce = Crypto.random(32)
        val tag = Discovery.tag(discoveryKey(), selfNodeId, Discovery.slot(now))
        val init = Writer().u8(1).u8(1).str(selfNodeId).fixed(selfFp, 32).fixed(ephPub, Crypto.PUB_BYTES).fixed(nonce, 32)
            .fixed(tag, Discovery.TAG_BYTES).lp(ownExt).bytes()
        return Initiating(peerNodeId, eph, ephPub, nonce, init)
    }

    class Completed(val finish: ByteArray, val session: Session)

    inner class Initiating internal constructor(
        val peerNodeId: String,
        private val eph: KeyPair,
        private val ephPub: ByteArray,
        private val nonce: ByteArray,
        /** Flight 1, to send. */
        val init: ByteArray,
    ) {
        /** Checks flight 2 and answers with flight 3 and the session. */
        fun complete(reply: ByteArray, now: Long): Completed {
            val r = Reader(reply)
            header(r, 2)
            val nodeR = Shape.requireNodeId(r.str(8))
            val fpR = r.fixed(32)
            val ephR = r.fixed(Crypto.PUB_BYTES)
            val nonceR = r.fixed(32)
            val extR = r.lp(Caps.MAX_EXT)
            val sigR = r.fixed(Crypto.SIG_BYTES)
            r.end()
            if (nodeR != peerNodeId || !Crypto.isValidPoint(ephR)) throw ProtocolError(CloudError.NOT_MEMBER)
            val m = peer(Hex.of(fpR), nodeR)
            val capsR = Caps.decodeExt(extR)
            val t = transcript(selfNodeId, nodeR, selfFp, fpR, ephPub, ephR, nonce, nonceR, ownExt, extR)
            if (!Crypto.ecdsaVerify(m.publicKey!!, signedPart("r", t), sigR)) throw ProtocolError(CloudError.NOT_MEMBER)
            val sigI = identity.sign(signedPart("i", t))
            val session = derive(Crypto.ecdh(eph.private, ephR), nonce, nonceR, t, true, m, now, Negotiated.of(caps, capsR))
            return Completed(Writer().u8(1).u8(3).fixed(sigI, Crypto.SIG_BYTES).bytes(), session)
        }
    }

    // ---- responder ----------------------------------------------------------------------------------------------

    /** Answers flight 1. The discovery tag is checked first: a stranger costs one HMAC, not an ECDH and a signature. */
    fun respond(init: ByteArray, now: Long): Responding {
        val r = Reader(init)
        header(r, 1)
        val nodeI = Shape.requireNodeId(r.str(8))
        val fpI = r.fixed(32)
        val ephI = r.fixed(Crypto.PUB_BYTES)
        val nonceI = r.fixed(32)
        val tag = r.fixed(Discovery.TAG_BYTES)
        val extI = r.lp(Caps.MAX_EXT)
        r.end()
        if (!Discovery.matches(discoveryKey(), nodeI, tag, now)) throw ProtocolError(CloudError.NOT_MEMBER)
        if (!Crypto.isValidPoint(ephI)) throw ProtocolError(CloudError.NOT_MEMBER)
        val capsI = Caps.decodeExt(extI)
        val m = peer(Hex.of(fpI), nodeI)
        val eph = Crypto.newKeyPair()
        val ephR = Crypto.rawPublic(eph.public)
        val nonceR = Crypto.random(32)
        val t = transcript(nodeI, selfNodeId, fpI, selfFp, ephI, ephR, nonceI, nonceR, extI, ownExt)
        val sigR = identity.sign(signedPart("r", t))
        val reply = Writer().u8(1).u8(2).str(selfNodeId).fixed(selfFp, 32).fixed(ephR, Crypto.PUB_BYTES).fixed(nonceR, 32).lp(ownExt)
            .fixed(sigR, Crypto.SIG_BYTES).bytes()
        val responding = Responding(reply, m, eph, ephI, nonceI, nonceR, t, now + pendingMs, Negotiated.of(caps, capsI))
        synchronized(pending) {
            pending.removeAll { it.used || now > it.expiresAt }
            if (pending.size >= maxPending) throw ProtocolError(CloudError.BUSY)
            pending.add(responding)
        }
        return responding
    }

    inner class Responding internal constructor(
        /** Flight 2, to send. */
        val reply: ByteArray,
        private val peerMember: Member,
        private val eph: KeyPair,
        private val ephI: ByteArray,
        private val nonceI: ByteArray,
        private val nonceR: ByteArray,
        private val transcript: ByteArray,
        internal val expiresAt: Long,
        private val negotiated: Negotiated,
    ) {
        @Volatile internal var used = false

        /** Checks flight 3 once; a second call, or one after the 10 seconds, fails. */
        fun finish(finish: ByteArray, now: Long): Session {
            synchronized(pending) {
                if (used || now > expiresAt) throw ProtocolError(CloudError.NOT_MEMBER)
                used = true
            }
            val r = Reader(finish)
            header(r, 3)
            val sigI = r.fixed(Crypto.SIG_BYTES)
            r.end()
            // the member may have been removed during the exchange: look again
            val m = peer(peerMember.id, peerMember.nodeId)
            if (!Crypto.ecdsaVerify(m.publicKey!!, signedPart("i", transcript), sigI)) throw ProtocolError(CloudError.NOT_MEMBER)
            return derive(Crypto.ecdh(eph.private, ephI), nonceI, nonceR, transcript, false, m, now, negotiated)
        }
    }
}

/** The live sessions of this device (section 5.4). A lookup fails once the session is old or either member is gone. */
class SessionStore(private val selfId: String, private val maxPerPeer: Int = 4) {
    private val sessions = LinkedHashMap<String, Session>()

    @Synchronized fun add(s: Session) {
        sessions[s.id] = s
        val same = sessions.values.filter { it.peerId == s.peerId }
        if (same.size > maxPerPeer) same.sortedBy { it.startedAt }.take(same.size - maxPerPeer).forEach { sessions.remove(it.id) }
    }

    /** The session, unless it is unknown, 24 hours old, or this device or its peer is not a current member (same key and node id). */
    @Synchronized fun get(id: String, now: Long, state: MemberState): Session? {
        val s = sessions[id] ?: return null
        val peerOk = state.member(s.peerId)?.nodeId == s.peerNodeId
        if (s.expired(now) || state.member(selfId) == null || !peerOk) {
            sessions.remove(id)
            return null
        }
        return s
    }

    /** Drops every session that [get] would refuse; returns how many. */
    @Synchronized fun sweep(now: Long, state: MemberState): Int {
        val before = sessions.size
        for (id in sessions.keys.toList()) get(id, now, state)
        return before - sessions.size
    }

    @Synchronized fun clear() = sessions.clear()

    val size: Int
        @Synchronized get() = sessions.size
}
