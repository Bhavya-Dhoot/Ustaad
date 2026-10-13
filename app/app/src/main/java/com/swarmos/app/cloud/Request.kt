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

    /** What makes two requests "the same request" for the resend rule. */
    fun fingerprint(method: String, pathWithQuery: String, body: ByteArray): ByteArray =
        Crypto.sha256(Writer().str(method.uppercase()).str(pathWithQuery).lp(body).bytes())
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
 * Remembers requests seen inside the window so that an identical copy is refused (section 6.3). The key is the sender and
 * the request id. An entry keeps the MACs seen under that id: a copy has a MAC already in the set (`replayed`); the
 * sender's own retry is signed again with a new time and so has a new MAC, and is handed on to the existing dedup, which
 * returns the stored result. Only requests whose MAC was already verified may reach this, and a live entry is never evicted.
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

    @Synchronized fun admit(sender: String, requestId: String, mac: ByteArray, fingerprint: ByteArray): Admit {
        val now = clock()
        if (now - lastPurge >= 1_000L) {
            seen.values.removeAll { now - it.last > ttlMs }
            lastPurge = now
        }
        val key = "$sender/$requestId"
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

/** A key issued to a token client, shown once to the person who sets the client up. */
class TokenGrant(val nodeId: String, val keyId: ByteArray, val key: ByteArray, val entry: Entry)

/**
 * The 32-byte keys of the token clients that THIS node issued (section 8). Other members never see a key, so only the
 * issuer can verify those clients' requests. In memory here; the wiring stage keeps them in protected storage.
 */
class TokenKeys(private val identity: DeviceIdentity) {
    private val selfFp = Crypto.fingerprint(identity.publicKey)
    private val keys = HashMap<String, ByteArray>()

    /** Makes the key and the entry that records it. Nothing is kept if the entry cannot be written. */
    @Synchronized fun issue(log: MemberLog, name: String, kind: MemberKind, now: Long): TokenGrant {
        val keyId = Crypto.random(16)
        val key = Crypto.random(32)
        val nodeId = Hex.of(Crypto.random(4))
        val entry = log.addTokenClient(identity, nodeId, name, kind, keyId, now)
        keys[Hex.of(keyId)] = key
        return TokenGrant(nodeId, keyId, key.copyOf(), entry)
    }

    @Synchronized fun key(keyIdHex: String): ByteArray? = keys[keyIdHex]
    @Synchronized fun restore(keyIdHex: String, key: ByteArray) { keys[keyIdHex] = key }
    @Synchronized fun forget(keyIdHex: String) { keys.remove(keyIdHex) }

    /** The member id the list uses for the client with this key id. */
    fun memberId(keyId: ByteArray): String = Hex.of(MemberLog.tokenMemberId(selfFp, keyId))
}

/** Who sent an accepted request. [resend] is true for a retry of a request id already seen (the dedup then returns the stored result). */
class Accepted(val nodeId: String, val kind: MemberKind, val memberId: String, val viaToken: Boolean, val resend: Boolean)

/**
 * The checks of section 6.2, in order. [startedAtMs] is this process's start: a restart empties the replay memory, so a
 * request signed before it is refused (`skew`).
 */
class RequestVerifier(
    private val sessions: SessionStore,
    private val tokens: TokenKeys?,
    private val state: () -> MemberState,
    private val guard: ReplayGuard,
    private val startedAtMs: Long,
) {
    fun verify(req: SignedRequest, now: Long): Accepted {
        if (!Shape.isRequestId(req.requestId) || !Shape.isNodeId(req.sender) || (req.sessionId == null) == (req.tokenKeyId == null)) {
            throw ProtocolError(CloudError.MALFORMED)
        }
        if (req.ts < startedAtMs || abs(now - req.ts) > RequestAuth.MAX_SKEW_MS) throw ProtocolError(CloudError.SKEW)

        val st = state()
        val key: ByteArray
        val kind: MemberKind
        val memberId: String
        val viaToken = req.tokenKeyId != null
        if (req.sessionId != null) {
            val s = sessions.get(req.sessionId, now, st) ?: throw ProtocolError(CloudError.UNKNOWN_SESSION)
            if (s.peerNodeId != req.sender) throw ProtocolError(CloudError.SENDER_MISMATCH)
            key = s.macKey
            kind = s.peerKind
            memberId = s.peerId
        } else {
            val keyIdHex = req.tokenKeyId!!
            val keyId = Hex.parse(keyIdHex)
            if (keyId.size != 16) throw ProtocolError(CloudError.MALFORMED)
            val t = tokens ?: throw ProtocolError(CloudError.UNKNOWN_KEY)
            // the key must be one this node issued AND still be recorded in the list (a removal or a removed issuer ends it)
            key = t.key(keyIdHex) ?: throw ProtocolError(CloudError.UNKNOWN_KEY)
            val m = st.member(t.memberId(keyId)) ?: throw ProtocolError(CloudError.UNKNOWN_KEY)
            if (m.kind == MemberKind.NODE) throw ProtocolError(CloudError.UNKNOWN_KEY)
            if (m.nodeId != req.sender) throw ProtocolError(CloudError.SENDER_MISMATCH)
            kind = m.kind
            memberId = m.id
        }

        val expected = RequestAuth.mac(key, req.method, req.pathWithQuery, req.ts, req.requestId, req.sender, req.body)
        if (!Crypto.equal(expected, req.mac)) throw ProtocolError(CloudError.BAD_MAC)

        // only now, with a right MAC, may the request enter the replay memory
        val admit = guard.admit(req.sender, req.requestId, req.mac, RequestAuth.fingerprint(req.method, req.pathWithQuery, req.body))
        return Accepted(req.sender, kind, memberId, viaToken, admit == ReplayGuard.Admit.RESEND)
    }
}
