package com.swarmos.app.cloud

/** One device in a test: its key, its node id and its copy of the member list. */
internal class Dev(val identity: SoftwareIdentity, val nodeId: String, var log: MemberLog) {
    val id: String get() = identity.memberId()
    val state: MemberState get() = log.state()

    /** This device admits [other] as a member (it writes the entry in its own list). */
    fun admit(other: Dev, kind: MemberKind = MemberKind.NODE, now: Long = T0): Entry =
        log.addDevice(identity, other.nodeId, "dev-${other.nodeId}", kind, other.identity.publicKey, now)

    fun remove(targetId: String, now: Long = T0 + 1): Entry = log.remove(identity, targetId, now)

    /** A copy of this device's list as another device would hold it. */
    fun copyLog(): MemberLog = MemberLog.fromEntries(log.entries())

    companion object {
        fun newNodeId(): String = Hex.of(Crypto.random(4))

        /** A device with its own key and no list yet. */
        fun bare(): Dev = Dev(SoftwareIdentity.generate(), newNodeId(), dummyLog())

        /** A device that creates a new cloud. */
        fun creator(): Dev {
            val id = SoftwareIdentity.generate()
            val node = newNodeId()
            return Dev(id, node, MemberLog.create(id, node, "creator", T0))
        }

        private fun dummyLog(): MemberLog = MemberLog.create(SoftwareIdentity.generate(), newNodeId(), "placeholder", T0)
    }
}

/** A creator plus [n] other nodes, all admitted by the creator, every device holding a copy of the same list. */
internal fun cloudOf(n: Int): List<Dev> {
    val root = Dev.creator()
    val others = List(n) { Dev.bare() }
    others.forEach { root.admit(it) }
    val all = listOf(root) + others
    for (d in others) d.log = root.copyLog()
    return all
}
