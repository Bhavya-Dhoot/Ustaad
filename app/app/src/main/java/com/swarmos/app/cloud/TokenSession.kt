package com.swarmos.app.cloud

/**
 * The token session of docs/CLOUD-PROTOCOL.md 8.2, written on three primitives only (HMAC-SHA256, HKDF-SHA256, AES-256-GCM
 * through the frames) so that a page in a browser with no WebCrypto can implement it in plain JavaScript.
 */
object TokenProtocol {
    /** `TokenHello`: `u8 1 || bytes16 keyId || bytes32 cn`. */
    fun hello(keyId: ByteArray, cn: ByteArray): ByteArray = Writer().u8(1).fixed(keyId, 16).fixed(cn, 32).bytes()

    fun parseHello(bytes: ByteArray): Pair<ByteArray, ByteArray> {
        val r = Reader(bytes)
        if (r.u8() != 1) malformed("token hello")
        val keyId = r.fixed(16)
        val cn = r.fixed(32)
        r.end()
        return keyId to cn
    }

    /** `TokenWelcome`: `u8 1 || bytes32 sn || bytes16 boot || bytes32 mac`. */
    fun welcome(sn: ByteArray, boot: ByteArray, mac: ByteArray): ByteArray = Writer().u8(1).fixed(sn, 32).fixed(boot, 16).fixed(mac, 32).bytes()

    /** The issuer proves it holds the key: `HMAC(K, lp("ustaad/v1/token/welcome") || keyId || cn || sn || boot)`. */
    fun welcomeMac(key: ByteArray, keyId: ByteArray, cn: ByteArray, sn: ByteArray, boot: ByteArray): ByteArray =
        Crypto.hmac(key, lbl("token/welcome"), keyId, cn, sn, boot)

    internal class Derived(val mac: ByteArray, val c2s: ByteArray, val s2c: ByteArray, val id: String)

    /** `prk = HKDF-Extract(salt = cn || sn || boot, ikm = K)`, then `HKDF-Expand(prk, lp(label), L)`. */
    internal fun derive(key: ByteArray, cn: ByteArray, sn: ByteArray, boot: ByteArray): Derived {
        val prk = Crypto.hkdfExtract(concat(cn, sn, boot), key)
        fun k(label: String, len: Int) = Crypto.hkdfExpand(prk, lbl(label), len)
        return Derived(k("token/mac", 32), k("token/seal/c2s", 32), k("token/seal/s2c", 32), Hex.of(k("token/id", 8)))
    }

    /** The unsealed loopback mode's MAC key (8.3): `HKDF(K, salt = boot, "token/loopback", 32)`. */
    fun loopbackMacKey(key: ByteArray, boot: ByteArray): ByteArray = Crypto.hkdf(key, boot, lbl("token/loopback"), 32)
}

/** Key material for a new token client, made by its issuing node; the owner records the client (4.5, keyType 2). */
class TokenMaterial(val nodeId: String, val keyId: ByteArray, val key: ByteArray)

/**
 * The issuer's side (section 8): it holds the 32-byte keys of the token clients it issued, answers hellos, and has a boot nonce made
 * when the process starts, which is part of every key: nothing recorded before a restart can be replayed after it (Codex
 * Request.kt:130).
 *
 * A hello is unauthenticated, so what it makes waits in a pending table of its own (small, with expiry and a global bound) and never
 * touches the established sessions. A pending session is admitted to the [SessionStore], and so to the quota of four per client, only
 * after its first request carries a valid MAC ([claim] then [promote]; Codex TokenSession.kt:88). The per-address records expire.
 */
class TokenIssuer(
    private val selfMemberId: String,
    val bootNonce: ByteArray = Crypto.random(16),
    private val maxHellosPerMinutePerSource: Int = 30,
    private val maxPending: Int = 256,
    private val maxPendingPerClient: Int = 4,
    private val pendingMs: Long = 60_000L,
    private val maxAddressRecords: Int = 4096,
) {
    private class Pending(val session: Session, val clientId: String, val expiresAt: Long)

    private val keys = HashMap<String, ByteArray>()
    private val hellos = HashMap<String, ArrayDeque<Long>>()
    private val pending = LinkedHashMap<String, Pending>() // by session id

    val pendingCount: Int get() = synchronized(pending) { pending.size }
    val addressRecords: Int get() = synchronized(hellos) { hellos.size }

    fun memberId(keyId: ByteArray): String = ChainRules.tokenMemberId(selfMemberId, keyId)

    /** Draws the key, key id and node id of a new token client; the key is kept. The owner still has to record it. */
    @Synchronized fun prepare(): TokenMaterial {
        val keyId = Crypto.random(16)
        val key = Crypto.random(32)
        keys[Hex.of(keyId)] = key
        return TokenMaterial(Hex.of(Crypto.random(4)), keyId, key.copyOf())
    }

    @Synchronized fun key(keyIdHex: String): ByteArray? = keys[keyIdHex]
    @Synchronized fun restore(keyIdHex: String, key: ByteArray) { keys[keyIdHex] = key }
    @Synchronized fun forget(keyIdHex: String) { keys.remove(keyIdHex) }

    /** The MAC key of the unsealed loopback mode for this client. */
    fun loopbackMacKey(keyIdHex: String): ByteArray? = key(keyIdHex)?.let { TokenProtocol.loopbackMacKey(it, bootNonce) }

    /** Answers a `TokenHello` with a Welcome. The session waits in the pending table; the client must be a current member recorded by the owner. */
    fun hello(bytes: ByteArray, now: Long, state: ChainState, source: String = ""): ByteArray {
        synchronized(hellos) {
            // records of quiet addresses expire; the table has a bound of its own (Codex TokenSession.kt:76)
            if (hellos.size >= maxAddressRecords / 2) hellos.entries.removeAll { it.value.isEmpty() || now - it.value.last() >= 60_000L }
            if (hellos.size >= maxAddressRecords && source !in hellos) throw ProtocolError(CloudError.BUSY)
            val times = hellos.getOrPut(source) { ArrayDeque() }
            while (times.isNotEmpty() && now - times.first() >= 60_000L) times.removeFirst()
            if (times.size >= maxHellosPerMinutePerSource) throw ProtocolError(CloudError.BUSY)
            times.addLast(now)
        }
        val (keyId, cn) = TokenProtocol.parseHello(bytes)
        val key = key(Hex.of(keyId)) ?: throw ProtocolError(CloudError.UNKNOWN_KEY)
        val m = state.member(memberId(keyId)) ?: throw ProtocolError(CloudError.UNKNOWN_KEY)
        if (m.kind == MemberKind.NODE) throw ProtocolError(CloudError.UNKNOWN_KEY)
        val sn = Crypto.random(32)
        val d = TokenProtocol.derive(key, cn, sn, bootNonce)
        val mac = TokenProtocol.welcomeMac(key, keyId, cn, sn, bootNonce)
        val session = Session(
            id = d.id, peerNodeId = m.nodeId, peerId = m.id, peerKind = m.kind, macKey = d.mac, sendKey = d.s2c, recvKey = d.c2s,
            startedAt = now, initiator = false, negotiated = Negotiated.of(Caps.BASELINE, Caps.BASELINE), viaToken = true,
        )
        synchronized(pending) {
            pending.entries.removeAll { it.value.expiresAt <= now }
            val mine = pending.entries.filter { it.value.clientId == m.id }
            if (mine.size >= maxPendingPerClient) pending.remove(mine.first().key) // the oldest unproven hello of this client goes, never an established session
            if (pending.size >= maxPending) throw ProtocolError(CloudError.BUSY)
            pending[session.id] = Pending(session, m.id, now + pendingMs)
        }
        return TokenProtocol.welcome(sn, bootNonce, mac)
    }

    /** The pending session with this id, if it has not expired and its client is still a member. It is NOT admitted yet: see [promote]. */
    fun claim(sessionId: String, now: Long, state: ChainState): Session? = synchronized(pending) {
        val p = pending[sessionId] ?: return null
        if (p.expiresAt <= now || state.member(p.session.peerId)?.nodeId != p.session.peerNodeId) {
            pending.remove(sessionId)
            return null
        }
        p.session
    }

    /** Called once the session's first request had a valid MAC: it leaves the pending table for the established store. */
    fun promote(session: Session, store: SessionStore) {
        synchronized(pending) { pending.remove(session.id) }
        store.add(session)
    }
}

/** The token client's side (a script, a browser page): the same few steps in plain code. */
class TokenClient(
    private val keyId: ByteArray,
    private val key: ByteArray,
    private val nodeId: String,
    private val issuerNodeId: String,
    private val issuerMemberId: String,
) {
    class Started internal constructor(val hello: ByteArray, internal val cn: ByteArray) {
        internal var done: Session? = null
        internal var doneWelcome: ByteArray? = null
    }

    fun start(): Started {
        val cn = Crypto.random(32)
        return Started(TokenProtocol.hello(keyId, cn), cn)
    }

    /**
     * Checks the Welcome (the issuer holds `K`) and derives the session. Single use (Codex TokenSession.kt:114): the same Welcome
     * returns the same session object, never a second one with its counters back at zero; any other Welcome for this hello fails.
     */
    fun finish(started: Started, welcome: ByteArray, now: Long): Session = synchronized(started) {
        started.done?.let { d -> if (started.doneWelcome!!.contentEquals(welcome)) return d else throw ProtocolError(CloudError.BAD_MAC) }
        val r = Reader(welcome)
        if (r.u8() != 1) malformed("token welcome")
        val sn = r.fixed(32)
        val boot = r.fixed(16)
        val mac = r.fixed(32)
        r.end()
        if (!Crypto.equal(TokenProtocol.welcomeMac(key, keyId, started.cn, sn, boot), mac)) throw ProtocolError(CloudError.BAD_MAC)
        val d = TokenProtocol.derive(key, started.cn, sn, boot)
        val session = Session(
            id = d.id, peerNodeId = issuerNodeId, peerId = issuerMemberId, peerKind = MemberKind.NODE, macKey = d.mac, sendKey = d.c2s, recvKey = d.s2c,
            startedAt = now, initiator = true, negotiated = Negotiated.of(Caps.BASELINE, Caps.BASELINE), viaToken = true,
        )
        started.done = session
        started.doneWelcome = welcome.copyOf()
        session
    }

    /** The node id the client puts in `X-Swarm-Node`. */
    fun nodeId(): String = nodeId
}
