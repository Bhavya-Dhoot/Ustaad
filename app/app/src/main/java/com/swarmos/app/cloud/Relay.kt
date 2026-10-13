package com.swarmos.app.cloud

/**
 * The relay's messages and the home node's proof of its route (docs/CLOUD-PROTOCOL.md section 12). The relay itself is a
 * later stage; this is the part both ends and the relay must agree on byte for byte.
 */
object Relay {
    const val ROUTE_BYTES = 16
    const val MAX_DATA = 64 * 1024

    const val HELLO = 1
    const val CHALLENGE = 2
    const val PROOF = 3
    const val READY = 4
    const val PAIRED = 5
    const val ERR = 6
    const val DATA = 7

    const val ROLE_HOME = 1
    const val ROLE_AWAY = 2

    /**
     * The route of one home node: only a holder of the discovery key can compute it. It includes the home node's key
     * fingerprint, so a reinstalled home node (new key) gets a new route.
     */
    fun routeId(discoveryKey: ByteArray, homeNodeId: String, homeFingerprint: ByteArray): ByteArray =
        Crypto.hkdf(discoveryKey, ByteArray(0), concat(lbl("relay/route"), Writer().str(homeNodeId).fixed(homeFingerprint, 32).bytes()), ROUTE_BYTES)

    /** What the home node signs: a fresh relay nonce, the relay's own host name (no replay at another relay) and the route. */
    fun openMessage(relayHost: String, route: ByteArray, nonce: ByteArray): ByteArray =
        concat(lbl("relay/open"), Writer().str(relayHost).fixed(route, ROUTE_BYTES).fixed(nonce, 32).bytes())

    fun hello(role: Int, route: ByteArray): ByteArray = Writer().u8(HELLO).u8(1).u8(role).fixed(route, ROUTE_BYTES).bytes()

    /** Role and route of a HELLO message. */
    fun parseHello(msg: ByteArray): Pair<Int, ByteArray> {
        val r = Reader(msg)
        if (r.u8() != HELLO || r.u8() != 1) malformed("hello")
        val role = r.u8()
        if (role != ROLE_HOME && role != ROLE_AWAY) malformed("role")
        val route = r.fixed(ROUTE_BYTES)
        r.end()
        return role to route
    }

    fun proof(identity: DeviceIdentity, relayHost: String, route: ByteArray, nonce: ByteArray): ByteArray =
        Writer().u8(PROOF).fixed(identity.publicKey, Crypto.PUB_BYTES)
            .fixed(identity.sign(openMessage(relayHost, route, nonce)), Crypto.SIG_BYTES).bytes()

    /** The public key that signed a PROOF message for this nonce, route and relay, or null. The relay stores it for the route. */
    fun verifyProof(msg: ByteArray, relayHost: String, route: ByteArray, nonce: ByteArray): ByteArray? = try {
        val r = Reader(msg)
        if (r.u8() != PROOF) malformed("proof")
        val pub = r.fixed(Crypto.PUB_BYTES)
        val sig = r.fixed(Crypto.SIG_BYTES)
        r.end()
        if (Crypto.ecdsaVerify(pub, openMessage(relayHost, route, nonce), sig)) pub else null
    } catch (e: ProtocolError) {
        null
    }
}
