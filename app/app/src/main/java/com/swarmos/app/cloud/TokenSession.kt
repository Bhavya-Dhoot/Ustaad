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
 * The issuer's side (section 8): it holds the 32-byte keys of the token clients it issued, answers hellos with a token session,
 * and has a boot nonce made when the process starts, which is part of every key: nothing recorded before a restart can be replayed
 * after it (Codex Request.kt:130).
 */
class TokenIssuer(
    private val selfMemberId: String,
    val bootNonce: ByteArray = Crypto.random(16),
    private val maxHellosPerMinutePerSource: Int = 30,
) {
    private val keys = HashMap<String, ByteArray>()
    private val hellos = HashMap<String, ArrayDeque<Long>>()

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

    /** Answers a `TokenHello`: the Welcome, and a session added to [store]. The client must be a current member recorded by the owner. */
    fun hello(bytes: ByteArray, now: Long, state: ChainState, store: SessionStore, source: String = ""): ByteArray {
        synchronized(hellos) {
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
        store.add(
            Session(
                id = d.id, peerNodeId = m.nodeId, peerId = m.id, peerKind = m.kind, macKey = d.mac, sendKey = d.s2c, recvKey = d.c2s,
                startedAt = now, initiator = false, negotiated = Negotiated.of(Caps.BASELINE, Caps.BASELINE), viaToken = true,
            ),
        )
        return TokenProtocol.welcome(sn, bootNonce, mac)
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
    class Started internal constructor(val hello: ByteArray, internal val cn: ByteArray)

    fun start(): Started {
        val cn = Crypto.random(32)
        return Started(TokenProtocol.hello(keyId, cn), cn)
    }

    /** Checks the Welcome (the issuer holds `K`) and derives the session. */
    fun finish(started: Started, welcome: ByteArray, now: Long): Session {
        val r = Reader(welcome)
        if (r.u8() != 1) malformed("token welcome")
        val sn = r.fixed(32)
        val boot = r.fixed(16)
        val mac = r.fixed(32)
        r.end()
        if (!Crypto.equal(TokenProtocol.welcomeMac(key, keyId, started.cn, sn, boot), mac)) throw ProtocolError(CloudError.BAD_MAC)
        val d = TokenProtocol.derive(key, started.cn, sn, boot)
        return Session(
            id = d.id, peerNodeId = issuerNodeId, peerId = issuerMemberId, peerKind = MemberKind.NODE, macKey = d.mac, sendKey = d.c2s, recvKey = d.s2c,
            startedAt = now, initiator = true, negotiated = Negotiated.of(Caps.BASELINE, Caps.BASELINE), viaToken = true,
        )
    }

    /** The node id the client puts in `X-Swarm-Node`. */
    fun nodeId(): String = nodeId
}
