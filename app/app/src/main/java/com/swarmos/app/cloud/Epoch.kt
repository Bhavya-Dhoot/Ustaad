package com.swarmos.app.cloud

/**
 * The cloud's shared secrets for one epoch (docs/CLOUD-PROTOCOL.md 3.7): the discovery key now, the storage key later. The
 * owner makes them at random and puts only their commitment in the chain.
 */
class EpochSecrets(val epoch: Int, val discoveryKey: ByteArray, val storageKey: ByteArray) {
    init {
        require(epoch >= 0 && discoveryKey.size == 32 && storageKey.size == 32) { "epoch secrets" }
    }

    /** `H(lp("ustaad/v1/epoch") || u32 epoch || discoveryKey || storageKey)`. */
    fun commitment(): ByteArray = Crypto.sha256(lbl("epoch"), Writer().u32(epoch).bytes(), discoveryKey, storageKey)

    /** The `Secrets` message body, sealed by the session that carries it. */
    fun encode(): ByteArray = Writer().u8(1).u32(epoch).fixed(discoveryKey, 32).fixed(storageKey, 32).bytes()

    companion object {
        fun generate(epoch: Int): EpochSecrets = EpochSecrets(epoch, Crypto.random(32), Crypto.random(32))

        fun decode(bytes: ByteArray): EpochSecrets {
            val r = Reader(bytes)
            if (r.u8() != 1) malformed("secrets version")
            val s = EpochSecrets(r.u32(), r.fixed(32), r.fixed(32))
            r.end()
            return s
        }
    }
}

/**
 * The epoch secrets this device holds. A set is accepted only for an epoch the device's own chain names and only if it
 * matches that epoch's commitment; it is served only to a current member and only for an epoch the chain names (so a
 * removed member cannot get a later set from an up-to-date peer, and a stale peer has none to give).
 */
class SecretsStore(private val graceMs: Long = GRACE_MS) {
    private val sets = HashMap<String, EpochSecrets>() // by commitment, hex
    private val learnt = HashMap<Int, Long>() // epoch to the local time this device first saw the chain name it
    private var latest = -1

    /** Remember when the chain first named the current epoch (the 24 hours of grace run from here). */
    @Synchronized fun note(state: ChainState, now: Long) {
        if (state.epoch > latest) { latest = state.epoch; learnt.putIfAbsent(state.epoch, now) }
    }

    /** True if [s] is a set the chain names (the current or the previous epoch) and its commitment matches. */
    @Synchronized fun offer(s: EpochSecrets, state: ChainState): Boolean {
        if (s.epoch != state.epoch && s.epoch != state.epoch - 1) return false
        val want = state.commitment(s.epoch) ?: return false
        if (!Crypto.equal(want, s.commitment())) return false
        sets[Hex.of(want)] = s
        return true
    }

    @Synchronized fun current(state: ChainState): EpochSecrets? = state.commitment(state.epoch)?.let { sets[Hex.of(it)] }

    /** Discovery keys to use for tags: the current epoch first, then the previous one while its grace lasts. */
    @Synchronized fun discoveryKeys(state: ChainState, now: Long): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        current(state)?.let { out.add(it.discoveryKey) }
        val first = learnt[state.epoch]
        if (state.epoch > 0 && first != null && now - first < graceMs) {
            state.commitment(state.epoch - 1)?.let { sets[Hex.of(it)] }?.let { out.add(it.discoveryKey) }
        }
        return out
    }

    /** The set of [epoch] for a requester, or null. */
    @Synchronized fun serve(epoch: Int, requesterMemberId: String, state: ChainState): EpochSecrets? {
        if (state.member(requesterMemberId) == null) return null
        val c = state.commitment(epoch) ?: return null
        return sets[Hex.of(c)]
    }

    @Synchronized fun add(s: EpochSecrets) { sets[Hex.of(s.commitment())] = s }

    companion object {
        const val GRACE_MS = 24L * 3600 * 1000
    }
}
