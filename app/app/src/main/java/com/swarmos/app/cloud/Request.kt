package com.swarmos.app.cloud

import kotlin.math.abs

/**
 * Request signing (docs/CLOUD-PROTOCOL.md section 6). The MAC covers method, path with query, time, request id, sender
 * node id and body: unlike the old signature, the sender and the request id are covered.
 */
object RequestAuth {
    const val MAX_SKEW_MS = 30_000L

    /** `body` is the exact bytes on the wire (a sealed frame when the body is sealed). */
    fun input(method: String, pathWithQuery: String, ts: Long, requestId: String, sender: String, body: ByteArray): ByteArray =
        Writer().raw(lbl("req")).str(method.uppercase()).str(pathWithQuery).u64(ts).str(requestId).str(sender).lp(body).bytes()

    fun mac(key: ByteArray, method: String, pathWithQuery: String, ts: Long, requestId: String, sender: String, body: ByteArray): ByteArray =
        Crypto.hmac(key, input(method, pathWithQuery, ts, requestId, sender, body))

    /**
     * What makes two requests "the same request" for the resend rule: method, target and a digest of the **opened** body, not the
     * sealed bytes (a retry after a rekey or on another carrier has different sealed bytes; Codex Request.kt:161).
     */
    fun fingerprint(method: String, pathWithQuery: String, plainBody: ByteArray): ByteArray =
        Crypto.sha256(Writer().str(method.uppercase()).str(pathWithQuery).lp(Crypto.sha256(plainBody)).bytes())

    /** The sealed body of a request: one final frame on a fresh stream, bound to the request id. Empty stays empty. */
    fun sealBody(session: Session, requestId: String, plain: ByteArray): ByteArray =
        if (plain.isEmpty()) ByteArray(0)
        else session.sealer(session.nextStreamNo(), requestId.toByteArray(), FrameType.REQUEST).seal(plain, final = true)

    /** The sealed answer (6.4): the envelope in the first RESPONSE frame of a stream the responder chooses. */
    fun sealAnswer(session: Session, requestId: String, status: Int, contentType: String, body: ByteArray): ByteArray =
        session.sealer(session.nextStreamNo(), requestId.toByteArray(), FrameType.RESPONSE, FrameType.EVENT)
            .seal(ResponseEnvelope(status, contentType, body).encode(), final = true)

    /**
     * Opens a one-frame answer; the status and content type are those inside, never the carrier's. An answer is returned only if its
     * stream ended properly: a first frame without its end is `truncated`, not a partial answer (Codex Request.kt:40).
     */
    fun openAnswer(session: Session, requestId: String, frame: ByteArray): ResponseEnvelope = openAnswerFrames(session, requestId, listOf(frame))

    /** Opens an answer of several frames (envelope first, then body frames) and returns it whole, only after the final frame. */
    fun openAnswerFrames(session: Session, requestId: String, frames: List<ByteArray>): ResponseEnvelope {
        if (frames.isEmpty()) throw ProtocolError(CloudError.TRUNCATED)
        val h = FrameHeader.parse(frames[0])
        val o = session.opener(h.stream, requestId.toByteArray(), FrameType.RESPONSE, FrameType.EVENT)
        val first = ResponseEnvelope.parse(o.open(frames[0]))
        var body = first.body
        for (f in frames.drop(1)) body += o.open(f)
        o.end() // throws truncated unless the final frame has been opened
        return ResponseEnvelope(first.status, first.contentType, body)
    }
}

/** A request as received: the headers parsed, the MAC decoded. Exactly one of [sessionId] and [tokenKeyId] is set. */
class SignedRequest(
    val method: String,
    val pathWithQuery: String,
    val ts: Long,
    val requestId: String,
    val sender: String,
    val body: ByteArray,
    val mac: ByteArray,
    val sessionId: String? = null,
    val tokenKeyId: String? = null,
)

/** Which part of the replay table a request uses (6.3): the owner and the synchronisation routes have room kept back for them. */
enum class Lane { ORDINARY, SYNC, OWNER }

/**
 * Remembers requests seen inside the window so that an identical copy is refused (section 6.3). The key is the **verified
 * member id** and the request id (Codex Request.kt:161). An entry keeps the MACs seen under that id: a copy has a MAC already in
 * the set (`replayed`); the sender's own retry is signed again with a new time and so has a new MAC, and is handed on to the
 * dedup, which returns the stored result. Only requests whose MAC was verified may reach this, and a live entry is never evicted.
 *
 * Quotas (Codex Request.kt:91): an ordinary request may use at most [maxPerMember] entries of one member and never the last
 * [reserved] places of the table; synchronisation requests (chain and secrets) have [maxSyncPerMember] per member and may use the
 * reserve; the owner's requests have the whole table. One member cannot fill the table for the others.
 */
class ReplayGuard(
    private val ttlMs: Long = 300_000L,
    private val maxEntries: Int = 50_000,
    private val maxMacsPerId: Int = 8,
    private val maxPerMember: Int = 2_000,
    private val reserved: Int = 5_000,
    private val maxSyncPerMember: Int = 500,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    enum class Admit { FIRST, RESEND }

    private class Seen(val memberId: String, val lane: Lane, val fingerprint: ByteArray, var last: Long) {
        val macs = ArrayList<ByteArray>()
    }

    private val seen = HashMap<String, Seen>()
    private val ordinaryCount = HashMap<String, Int>()
    private val syncCount = HashMap<String, Int>()
    private var lastPurge = 0L

    val size: Int
        @Synchronized get() = seen.size

    private fun release(e: Seen) {
        when (e.lane) {
            Lane.ORDINARY -> ordinaryCount.merge(e.memberId, -1) { a, b -> (a + b).takeIf { it > 0 } }
            Lane.SYNC -> syncCount.merge(e.memberId, -1) { a, b -> (a + b).takeIf { it > 0 } }
            Lane.OWNER -> {}
        }
    }

    @Synchronized fun admit(memberId: String, requestId: String, mac: ByteArray, fingerprint: ByteArray, lane: Lane = Lane.ORDINARY): Admit {
        val now = clock()
        if (now - lastPurge >= 1_000L) {
            val it = seen.values.iterator()
            while (it.hasNext()) { val e = it.next(); if (now - e.last > ttlMs) { release(e); it.remove() } }
            lastPurge = now
        }
        val key = "$memberId/$requestId"
        val e = seen[key]
        if (e == null) {
            // refuse; evicting a live entry would reopen the window
            when (lane) {
                Lane.ORDINARY -> if (seen.size >= maxEntries - reserved || (ordinaryCount[memberId] ?: 0) >= maxPerMember) throw ProtocolError(CloudError.BUSY)
                Lane.SYNC -> if (seen.size >= maxEntries || (syncCount[memberId] ?: 0) >= maxSyncPerMember) throw ProtocolError(CloudError.BUSY)
                Lane.OWNER -> if (seen.size >= maxEntries) throw ProtocolError(CloudError.BUSY)
            }
            seen[key] = Seen(memberId, lane, fingerprint, now).also { it.macs.add(mac) }
            when (lane) {
                Lane.ORDINARY -> ordinaryCount.merge(memberId, 1, Int::plus)
                Lane.SYNC -> syncCount.merge(memberId, 1, Int::plus)
                Lane.OWNER -> {}
            }
            return Admit.FIRST
        }
        if (e.macs.any { Crypto.equal(it, mac) }) throw ProtocolError(CloudError.REPLAYED)
        if (!Crypto.equal(e.fingerprint, fingerprint)) throw ProtocolError(CloudError.REQUEST_ID_REUSED)
        if (e.macs.size >= maxMacsPerId) throw ProtocolError(CloudError.TOO_MANY_RESENDS)
        e.macs.add(mac)
        e.last = now
        return Admit.RESEND
    }
}

/**
 * Who sent an accepted request and what it said. [body] is the opened body. [resend] is true for a retry of a request id already
 * seen (the dedup then returns the stored result). [session] is the session to seal the answer with, or null in loopback mode.
 */
class Accepted(
    val nodeId: String,
    val kind: MemberKind,
    val memberId: String,
    val viaToken: Boolean,
    val resend: Boolean,
    val body: ByteArray,
    val session: Session?,
)

/**
 * The checks of section 6.2, in order. [startedAtMs] is this process's start: a restart empties the replay memory, so a request
 * signed before it is refused (`skew`).
 */
class RequestVerifier(
    private val sessions: SessionStore,
    private val tokens: TokenIssuer?,
    private val state: () -> ChainState,
    private val guard: ReplayGuard,
    private val startedAtMs: Long,
) {
    companion object {
        /** The synchronisation routes the replay guard keeps room for (3.6, 3.7). */
        val SYNC_PREFIXES = listOf("/v1/cloud/chain", "/v1/cloud/secrets")
    }

    fun verify(req: SignedRequest, now: Long, fromLoopback: Boolean = false): Accepted {
        if (!Shape.isRequestId(req.requestId) || !Shape.isNodeId(req.sender) || (req.sessionId == null) == (req.tokenKeyId == null)) {
            throw ProtocolError(CloudError.MALFORMED)
        }
        if (req.ts < startedAtMs || abs(now - req.ts) > RequestAuth.MAX_SKEW_MS) throw ProtocolError(CloudError.SKEW)

        val st = state()
        val key: ByteArray
        val kind: MemberKind
        val memberId: String
        val session: Session?
        val viaToken: Boolean
        var unproven: Session? = null // a token session still in the pending table: admitted only after a valid MAC
        if (req.sessionId != null) {
            val s = sessions.get(req.sessionId, now, st)
                ?: tokens?.claim(req.sessionId, now, st)?.also { unproven = it }
                ?: throw ProtocolError(CloudError.UNKNOWN_SESSION)
            if (s.peerNodeId != req.sender) throw ProtocolError(CloudError.SENDER_MISMATCH)
            key = s.macKey; kind = s.peerKind; memberId = s.peerId; session = s; viaToken = s.viaToken
        } else {
            // the unsealed token mode exists for the PC test scripts through adb forward, and for nobody else
            if (!fromLoopback) throw ProtocolError(CloudError.FORBIDDEN)
            val keyIdHex = req.tokenKeyId!!
            val keyId = Hex.parse(keyIdHex)
            if (keyId.size != 16) throw ProtocolError(CloudError.MALFORMED)
            val t = tokens ?: throw ProtocolError(CloudError.UNKNOWN_KEY)
            key = t.loopbackMacKey(keyIdHex) ?: throw ProtocolError(CloudError.UNKNOWN_KEY)
            val m = st.member(t.memberId(keyId)) ?: throw ProtocolError(CloudError.UNKNOWN_KEY)
            if (m.kind == MemberKind.NODE) throw ProtocolError(CloudError.UNKNOWN_KEY)
            if (m.nodeId != req.sender) throw ProtocolError(CloudError.SENDER_MISMATCH)
            kind = m.kind; memberId = m.id; session = null; viaToken = true
        }

        val expected = RequestAuth.mac(key, req.method, req.pathWithQuery, req.ts, req.requestId, req.sender, req.body)
        if (!Crypto.equal(expected, req.mac)) throw ProtocolError(CloudError.BAD_MAC)
        unproven?.let { tokens!!.promote(it, sessions) } // the client proved it holds the key: now it counts against its quota of sessions

        // the MAC is right: open the body (sealed in a session, plain in loopback mode)
        val plain: ByteArray = if (session == null || req.body.isEmpty()) req.body else {
            val h = FrameHeader.parse(req.body)
            val o = session.opener(h.stream, req.requestId.toByteArray(), FrameType.REQUEST)
            o.open(req.body).also { if (!o.finished) throw ProtocolError(CloudError.BAD_FRAME) }
        }

        // only now may the request enter the replay memory, keyed by the verified member, compared as the logical request
        val lane = when {
            memberId == st.ownerId -> Lane.OWNER
            SYNC_PREFIXES.any { req.pathWithQuery.startsWith(it) } -> Lane.SYNC
            else -> Lane.ORDINARY
        }
        val admit = guard.admit(memberId, req.requestId, req.mac, RequestAuth.fingerprint(req.method, req.pathWithQuery, plain), lane)
        return Accepted(req.sender, kind, memberId, viaToken, admit == ReplayGuard.Admit.RESEND, plain, session)
    }
}
