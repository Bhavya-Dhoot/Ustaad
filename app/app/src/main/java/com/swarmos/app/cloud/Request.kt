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

    /** Opens a one-frame answer; the status and content type are those inside, never the carrier's. */
    fun openAnswer(session: Session, requestId: String, frame: ByteArray): ResponseEnvelope {
        val h = FrameHeader.parse(frame)
        val o = session.opener(h.stream, requestId.toByteArray(), FrameType.RESPONSE, FrameType.EVENT)
        val plain = o.open(frame)
        return ResponseEnvelope.parse(plain)
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

/**
 * Remembers requests seen inside the window so that an identical copy is refused (section 6.3). The key is the **verified
 * member id** and the request id (Codex Request.kt:161). An entry keeps the MACs seen under that id: a copy has a MAC already in
 * the set (`replayed`); the sender's own retry is signed again with a new time and so has a new MAC, and is handed on to the
 * dedup, which returns the stored result. Only requests whose MAC was verified may reach this, and a live entry is never evicted.
 */
class ReplayGuard(
    private val ttlMs: Long = 300_000L,
    private val maxEntries: Int = 50_000,
    private val maxMacsPerId: Int = 8,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    enum class Admit { FIRST, RESEND }

    private class Seen(val fingerprint: ByteArray, var last: Long) {
        val macs = ArrayList<ByteArray>()
    }

    private val seen = HashMap<String, Seen>()
    private var lastPurge = 0L

    val size: Int
        @Synchronized get() = seen.size

    @Synchronized fun admit(memberId: String, requestId: String, mac: ByteArray, fingerprint: ByteArray): Admit {
        val now = clock()
        if (now - lastPurge >= 1_000L) {
            seen.values.removeAll { now - it.last > ttlMs }
            lastPurge = now
        }
        val key = "$memberId/$requestId"
        val e = seen[key]
        if (e == null) {
            if (seen.size >= maxEntries) throw ProtocolError(CloudError.BUSY) // refuse; evicting a live entry would reopen the window
            seen[key] = Seen(fingerprint, now).also { it.macs.add(mac) }
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
        if (req.sessionId != null) {
            val s = sessions.get(req.sessionId, now, st) ?: throw ProtocolError(CloudError.UNKNOWN_SESSION)
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

        // the MAC is right: open the body (sealed in a session, plain in loopback mode)
        val plain: ByteArray = if (session == null || req.body.isEmpty()) req.body else {
            val h = FrameHeader.parse(req.body)
            val o = session.opener(h.stream, req.requestId.toByteArray(), FrameType.REQUEST)
            o.open(req.body).also { if (!o.finished) throw ProtocolError(CloudError.BAD_FRAME) }
        }

        // only now may the request enter the replay memory, keyed by the verified member, compared as the logical request
        val admit = guard.admit(memberId, req.requestId, req.mac, RequestAuth.fingerprint(req.method, req.pathWithQuery, plain))
        return Accepted(req.sender, kind, memberId, viaToken, admit == ReplayGuard.Admit.RESEND, plain, session)
    }
}
