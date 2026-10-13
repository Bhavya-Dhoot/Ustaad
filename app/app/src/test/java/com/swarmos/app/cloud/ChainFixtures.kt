package com.swarmos.app.cloud

/** One device in a chain test: its key, its node id and its copy of the chain. */
internal class Peer(val identity: SoftwareIdentity, val nodeId: String, var chain: Chain) {
    val id: String get() = identity.memberId()
    val state: ChainState get() = chain.state()

    companion object {
        fun newNodeId(): String = Hex.of(Crypto.random(4))

        /** A device with its own key and a chain of its own cloud (it has not joined the cloud under test). */
        fun bare(): Peer {
            val id = SoftwareIdentity.generate()
            val node = newNodeId()
            val rec = SoftwareIdentity.generate()
            return Peer(id, node, Chain.create(id, rec.publicKey, node, "placeholder", EpochSecrets.generate(0), T0))
        }
    }
}

/** A cloud under test: the owner's device, the recovery key and the epoch secrets the owner made. */
internal class Cloud private constructor(val owner: Peer, val recovery: SoftwareIdentity, val secrets: MutableList<EpochSecrets>) {
    val chain: Chain get() = owner.chain
    val state: ChainState get() = owner.chain.state()

    fun admit(p: Peer, kind: MemberKind = MemberKind.NODE, now: Long = T0): ChainEntry =
        chain.addDevice(owner.identity, p.nodeId, "dev-${p.nodeId}", kind, p.identity.publicKey, now)

    /** [p] holds a copy of the owner's chain as it is now. */
    fun copyTo(p: Peer) { p.chain = Chain.fromEntries(chain.entries()) }

    /** A copy of the owner's chain for a device that is not the owner (what any member holds). */
    fun copy(): Chain = Chain.fromEntries(chain.entries())

    fun remove(memberId: String, now: Long = T0 + 1): ChainEntry = chain.remove(owner.identity, memberId, now)

    /** Remove and rotate, remembering the new secrets. */
    fun removeAndRotate(memberId: String, now: Long = T0 + 1): List<ChainEntry> {
        val next = EpochSecrets.generate(state.epoch + 1)
        secrets.add(next)
        return chain.removeAndRotate(owner.identity, memberId, next, now)
    }

    companion object {
        fun create(): Cloud {
            val id = SoftwareIdentity.generate()
            val rec = SoftwareIdentity.generate()
            val s0 = EpochSecrets.generate(0)
            val node = Peer.newNodeId()
            return Cloud(Peer(id, node, Chain.create(id, rec.publicKey, node, "owner", s0, T0)), rec, mutableListOf(s0))
        }
    }
}

/** A cloud with [n] nodes admitted by the owner, each holding a copy of the chain. */
internal fun cloudWith(n: Int): Pair<Cloud, List<Peer>> {
    val c = Cloud.create()
    val peers = List(n) { Peer.bare() }
    peers.forEach { c.admit(it) }
    peers.forEach { c.copyTo(it) }
    return c to peers
}

/** An entry signed by [by] (any key, to forge) at the chain's next number. */
internal fun forgedAdd(by: DeviceIdentity, s: ChainState, nodeId: String, key: ByteArray, role: Int = 1): ChainEntry {
    val body = ChainEntry.addBody(s, T0, nodeId, "forged", MemberKind.NODE, key, null, null)
    return ChainEntry.sign(by, body)
}
