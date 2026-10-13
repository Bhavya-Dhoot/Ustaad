package com.swarmos.app.cloud

import java.util.BitSet

/**
 * One signed entry of a cloud's member list (section 3). The wire form is `lp(body) || sig`; the id is SHA-256 of the
 * body, so two copies of an entry with different (equally valid) ECDSA signatures are the same entry.
 */
class Entry private constructor(
    val body: ByteArray,
    val signature: ByteArray,
    val type: Int,
    /** Member id (hex) of the key that signed. */
    val signer: String,
    val time: Long,
    val deps: List<String>,
    /** Null only for the genesis, whose cloud id is derived from its body. */
    val cloudId: ByteArray?,
    val nodeId: String,
    val name: String,
    val kind: MemberKind,
    /** Device public key of an added member (genesis and add with key type 1). */
    val publicKey: ByteArray?,
    /** Token key: issuing node (hex member id) and key id. */
    val issuer: String?,
    val keyId: ByteArray?,
    /** Remove: member id (hex) of the removed key. */
    val target: String,
) {
    /** Hex SHA-256 of the body. */
    val id: String = Hex.of(Crypto.sha256(body))

    /** Member id this entry adds: `fp(pub)` of a device key, or the hash of issuer and key id of a token key. */
    val memberId: String
        get() = if (publicKey != null) Hex.of(Crypto.fingerprint(publicKey)) else Hex.of(MemberLog.tokenMemberId(Hex.parse(issuer!!), keyId!!))

    fun encode(): ByteArray = Writer().lp(body).fixed(signature, Crypto.SIG_BYTES).bytes()

    /** True when [pub] made this entry's signature. */
    fun signedBy(pub: ByteArray): Boolean = Crypto.ecdsaVerify(pub, signedMessage(body), signature)

    companion object {
        const val GENESIS = 1
        const val ADD = 2
        const val REMOVE = 3
        const val MAX_BODY = 512
        const val MAX_DEPS = 32

        private const val LABEL = "ustaad/v1/entry"
        private const val DEVICE_KEY = 1
        private const val TOKEN_KEY = 2

        internal fun signedMessage(body: ByteArray): ByteArray = concat(lbl("entry/sig"), body)

        private fun head(type: Int): Writer = Writer().str(LABEL).u8(type)

        private fun Writer.deps(deps: List<String>): Writer {
            u8(deps.size)
            for (d in deps) fixed(Hex.parse(d), 32)
            return this
        }

        internal fun genesisBody(seed: ByteArray, time: Long, nodeId: String, name: String, pub: ByteArray): ByteArray =
            head(GENESIS).fixed(seed, 16).u64(time).str(nodeId).str(name).fixed(pub, Crypto.PUB_BYTES).bytes()

        internal fun addDeviceBody(
            cloudId: ByteArray, signer: String, time: Long, deps: List<String>,
            nodeId: String, name: String, kind: MemberKind, pub: ByteArray,
        ): ByteArray = head(ADD).fixed(cloudId, 16).fixed(Hex.parse(signer), 32).u64(time).deps(deps)
            .str(nodeId).str(name).u8(kind.code).u8(DEVICE_KEY).fixed(pub, Crypto.PUB_BYTES).bytes()

        internal fun addTokenBody(
            cloudId: ByteArray, signer: String, time: Long, deps: List<String>,
            nodeId: String, name: String, kind: MemberKind, keyId: ByteArray,
        ): ByteArray = head(ADD).fixed(cloudId, 16).fixed(Hex.parse(signer), 32).u64(time).deps(deps)
            .str(nodeId).str(name).u8(kind.code).u8(TOKEN_KEY).fixed(Hex.parse(signer), 32).fixed(keyId, 16).bytes()

        internal fun removeBody(cloudId: ByteArray, signer: String, time: Long, deps: List<String>, target: String): ByteArray =
            head(REMOVE).fixed(cloudId, 16).fixed(Hex.parse(signer), 32).u64(time).deps(deps).fixed(Hex.parse(target), 32).bytes()

        internal fun sign(identity: DeviceIdentity, body: ByteArray): Entry = of(body, identity.sign(signedMessage(body)))

        /** Parses a wire entry (`lp(body) || sig`). */
        fun parse(wire: ByteArray): Entry {
            val r = Reader(wire)
            val body = r.lp(MAX_BODY)
            val sig = r.fixed(Crypto.SIG_BYTES)
            r.end()
            return of(body, sig)
        }

        /** Builds an entry from a body and a signature; parses and range-checks every field. The signature is not checked here. */
        internal fun of(body: ByteArray, sig: ByteArray): Entry {
            if (sig.size != Crypto.SIG_BYTES) malformed("signature size")
            val r = Reader(body)
            if (r.str(64) != LABEL) malformed("label")
            val type = r.u8()
            var cloudId: ByteArray? = null
            var signer = ""
            var deps = emptyList<String>()
            var nodeId = ""
            var name = ""
            var kind = MemberKind.NODE
            var pub: ByteArray? = null
            var issuer: String? = null
            var keyId: ByteArray? = null
            var target = ""
            val time: Long
            when (type) {
                GENESIS -> {
                    r.fixed(16) // seed: it only makes the cloud id random
                    time = r.u64()
                    nodeId = Shape.requireNodeId(r.str(8))
                    name = Shape.requireName(r.str(Shape.MAX_NAME_BYTES))
                    pub = r.fixed(Crypto.PUB_BYTES)
                    if (!Crypto.isValidPoint(pub)) malformed("public key")
                    signer = Hex.of(Crypto.fingerprint(pub))
                }
                ADD, REMOVE -> {
                    cloudId = r.fixed(16)
                    signer = Hex.of(r.fixed(32))
                    time = r.u64()
                    val n = r.u8()
                    if (n > MAX_DEPS) malformed("too many deps")
                    deps = List(n) { Hex.of(r.fixed(32)) }
                    if (deps != deps.sorted() || deps.toSet().size != deps.size) malformed("deps not ascending")
                    if (type == REMOVE) {
                        target = Hex.of(r.fixed(32))
                    } else {
                        nodeId = Shape.requireNodeId(r.str(8))
                        name = Shape.requireName(r.str(Shape.MAX_NAME_BYTES))
                        kind = MemberKind.of(r.u8()) ?: malformed("kind")
                        when (r.u8()) {
                            DEVICE_KEY -> {
                                pub = r.fixed(Crypto.PUB_BYTES)
                                if (!Crypto.isValidPoint(pub)) malformed("public key")
                                if (kind == MemberKind.BROWSER) malformed("a browser holds a token key")
                            }
                            TOKEN_KEY -> {
                                issuer = Hex.of(r.fixed(32))
                                keyId = r.fixed(16)
                                if (kind == MemberKind.NODE) malformed("a node holds a device key")
                                if (issuer != signer) malformed("a token client is issued by its signer")
                            }
                            else -> malformed("key type")
                        }
                    }
                }
                else -> malformed("entry type")
            }
            r.end()
            return Entry(body, sig, type, signer, time, deps, cloudId, nodeId, name, kind, pub, issuer, keyId, target)
        }
    }
}

/** One current member, as the fold derives it. */
class Member(
    val nodeId: String,
    val name: String,
    val kind: MemberKind,
    /** `fp(pub)` for a device key; the token member id for a token client. */
    val id: String,
    val publicKey: ByteArray?,
    /** Issuing node (hex member id) of a token client. */
    val issuer: String?,
    val addedBy: String,
    val addedAt: Long,
)

/** The state of a member list: a pure function of the set of entries. */
class MemberState(val members: List<Member>, val removed: Set<String>, val duplicateNodeIds: Set<String>) {
    private val byId = members.associateBy { it.id }

    fun member(id: String): Member? = byId[id]
    fun isRemoved(id: String): Boolean = id in removed
    fun byNodeId(nodeId: String): List<Member> = members.filter { it.nodeId == nodeId }
}

class MergeResult(val added: Int, val rejected: Int)

/**
 * A cloud's member list (docs/CLOUD-PROTOCOL.md section 3): a set of signed entries, merged by union, folded into a
 * state in which a removal wins for good. Thread-safe by one lock; the fold is cached until the set changes.
 */
class MemberLog private constructor(val cloudId: ByteArray, genesis: Entry, private val maxEntries: Int) {
    /** Insertion order is causal order: [accept] stores an entry only after everything it cites. */
    private val byId = LinkedHashMap<String, Entry>()

    /** Device public keys of every stored add or genesis, by member id: the keys that can have signed an entry. */
    private val keys = HashMap<String, ByteArray>()
    private var cached: MemberState? = null

    init { store(genesis) }

    val size: Int
        @Synchronized get() = byId.size

    @Synchronized fun entries(): List<Entry> = byId.values.toList()

    @Synchronized fun entriesNotIn(have: Set<String>): List<Entry> = byId.values.filter { it.id !in have }

    @Synchronized fun ids(): Set<String> = byId.keys.toSet()

    /** Section 3.7: 8 bytes of SHA-256 over the sorted ids, in hex. Signatures are not part of it. */
    @Synchronized fun hash(): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(lbl("loghash"))
        for (id in byId.keys.sorted()) md.update(Hex.parse(id))
        return Hex.of(md.digest().copyOf(8))
    }

    private fun store(e: Entry) {
        byId[e.id] = e
        if (e.publicKey != null) keys[e.memberId] = e.publicKey
        cached = null
    }

    /** Section 3.3. False for an entry that cannot be stored now (it may be stored later once its ancestry has arrived). */
    private fun accept(e: Entry): Boolean {
        if (e.type == Entry.GENESIS) return false // a log has exactly one root, the one it was created from
        if (e.cloudId == null || !e.cloudId.contentEquals(cloudId)) return false
        val limit = if (e.type == Entry.REMOVE) maxEntries + RESERVED_FOR_REMOVES else maxEntries
        if (byId.size >= limit) return false
        if (!e.deps.all { it in byId }) return false // the whole ancestry must be here
        val signerKey = keys[e.signer] ?: return false // an unknown key cannot have signed
        if (!e.signedBy(signerKey)) return false // the check that stops a forged entry
        store(e)
        return true
    }

    /** Set union of the entries that pass [accept]; order of arrival does not matter within and across calls. */
    @Synchronized fun merge(incoming: Collection<Entry>): MergeResult {
        val pending = LinkedHashMap<String, Entry>()
        for (e in incoming) if (e.id !in byId) pending.putIfAbsent(e.id, e)
        var added = 0
        var progress = true
        while (progress && pending.isNotEmpty()) {
            progress = false
            val it = pending.values.iterator()
            while (it.hasNext()) {
                if (accept(it.next())) { it.remove(); added++; progress = true }
            }
        }
        return MergeResult(added, pending.size)
    }

    // ---- creating entries (section 3.5) -----------------------------------------------------------------------

    /** The entries no stored entry cites: what the author has seen. */
    @Synchronized internal fun heads(): List<String> {
        val cited = HashSet<String>()
        for (e in byId.values) cited.addAll(e.deps)
        return byId.keys.filter { it !in cited }.sorted()
    }

    private fun create(by: DeviceIdentity, build: (signer: String, deps: List<String>) -> ByteArray): Entry {
        val signer = by.memberId()
        check(state().member(signer)?.kind == MemberKind.NODE) { "only a current node can write to the member list" }
        val deps = heads()
        check(deps.size <= Entry.MAX_DEPS) { "too many unmerged branches" }
        val e = Entry.sign(by, build(signer, deps))
        check(accept(e)) { "the new entry was not accepted" }
        return e
    }

    /** Admits a device key as node or client. */
    @Synchronized fun addDevice(by: DeviceIdentity, nodeId: String, name: String, kind: MemberKind, key: ByteArray, now: Long): Entry {
        require(kind != MemberKind.BROWSER) { "a browser holds a token key" }
        return create(by) { signer, deps ->
            Entry.addDeviceBody(cloudId, signer, now, deps, Shape.requireNodeId(nodeId), Shape.requireName(name), kind, key)
        }
    }

    /** Records a token client of [by] (client or browser). */
    @Synchronized fun addTokenClient(by: DeviceIdentity, nodeId: String, name: String, kind: MemberKind, keyId: ByteArray, now: Long): Entry {
        require(kind != MemberKind.NODE) { "a node holds a device key" }
        return create(by) { signer, deps ->
            Entry.addTokenBody(cloudId, signer, now, deps, Shape.requireNodeId(nodeId), Shape.requireName(name), kind, keyId)
        }
    }

    /** Removes the member id [target] for good. A key never seen added may be named. */
    @Synchronized fun remove(by: DeviceIdentity, target: String, now: Long): Entry =
        create(by) { signer, deps -> Entry.removeBody(cloudId, signer, now, deps, target) }

    // ---- the fold (section 3.4) -------------------------------------------------------------------------------

    @Synchronized fun state(): MemberState = cached ?: compute().also { cached = it }

    private fun compute(): MemberState {
        val list = byId.values.toList()
        val n = list.size
        val index = HashMap<String, Int>()
        list.forEachIndexed { i, e -> index[e.id] = i }
        // anc[i] = every entry reachable from i through deps. Entries are stored after their deps, so a pass in order works.
        val anc = Array(n) { BitSet(n) }
        for (i in 0 until n) for (d in list[i].deps) {
            val j = index.getValue(d)
            anc[i].set(j)
            anc[i].or(anc[j])
        }
        val removes = list.indices.filter { list[it].type == Entry.REMOVE }
        val addsOf = HashMap<String, MutableList<Int>>()
        list.forEachIndexed { i, e -> if (e.type != Entry.REMOVE) addsOf.getOrPut(e.memberId) { mutableListOf() }.add(i) }

        /** Which entries are valid if the removals in [t] are taken to be in effect (rules V1 to V3). */
        fun validUnder(t: Set<Int>): BooleanArray {
            val valid = BooleanArray(n)
            for (i in 0 until n) {
                val e = list[i]
                if (e.type == Entry.GENESIS) { valid[i] = true; continue }
                val s = e.signer
                // V1: the signer was a valid node in e's own past
                val wasNode = addsOf[s].orEmpty().any { a -> anc[i].get(a) && valid[a] && list[a].kind == MemberKind.NODE && list[a].publicKey != null }
                if (!wasNode) continue
                var ok = true
                for (r in t) {
                    if (list[r].target != s || r == i) continue
                    if (anc[i].get(r)) { ok = false; break } // V2: the signer was already removed in e's past
                    if (anc[r].get(i)) continue // e came before that removal: it stands
                    if (e.type == Entry.REMOVE && e.target == list[r].signer) continue // two members removing each other: both count
                    ok = false; break // V3: concurrent with the removal of its signer: ignored
                }
                valid[i] = ok
            }
            return valid
        }

        fun f(t: Set<Int>): Set<Int> {
            val valid = validUnder(t)
            return removes.filterTo(HashSet()) { valid[it] }
        }

        // F is antitone, so the sequence T0, F(T0), ... either settles or alternates; where it alternates the removal takes effect.
        fun settle(): Set<Int> {
            var cur: Set<Int> = removes.toSet()
            var prev: Set<Int>? = null
            var steps = 0
            while (true) {
                val next = f(cur)
                if (next == cur) return cur
                if (prev != null && next == prev) return cur + next
                if (++steps > 2 * removes.size + 4) return cur + next // cannot happen; a bound that errs towards removal
                prev = cur
                cur = next
            }
        }

        val effective = settle()
        val valid = validUnder(effective)
        val removed = effective.mapTo(HashSet()) { list[it].target }
        val liveNodes = HashSet<String>()
        for (i in 0 until n) {
            val e = list[i]
            if (e.type != Entry.REMOVE && valid[i] && e.kind == MemberKind.NODE && e.memberId !in removed) liveNodes.add(e.memberId)
        }
        val seen = HashMap<String, Entry>()
        for (i in 0 until n) {
            val e = list[i]
            if (e.type == Entry.REMOVE || !valid[i] || e.memberId in removed) continue
            if (e.issuer != null && e.issuer !in liveNodes) continue // the issuing node is gone: so are its token clients
            val old = seen[e.memberId]
            if (old == null || e.id < old.id) seen[e.memberId] = e
        }
        val members = seen.values.sortedWith(compareBy<Entry>({ it.time }, { it.id })).map {
            Member(it.nodeId, it.name, it.kind, it.memberId, it.publicKey, it.issuer, it.signer, it.time)
        }
        val duplicates = members.groupBy { it.nodeId }.filterValues { it.size > 1 }.keys
        return MemberState(members, removed, duplicates)
    }

    companion object {
        const val MAX_ENTRIES = 2048
        const val RESERVED_FOR_REMOVES = 64

        /** `H(lp("ustaad/v1/token") || issuer || keyId)`: the member id of a token client. */
        fun tokenMemberId(issuerFp: ByteArray, keyId: ByteArray): ByteArray = Crypto.sha256(lbl("token"), issuerFp, keyId)

        /** `H(lp("ustaad/v1/cloud") || genesis body)` cut to 16 bytes: the id is random (seed) and tied to one root. */
        fun cloudIdOf(genesis: Entry): ByteArray = Crypto.sha256(lbl("cloud"), genesis.body).copyOf(16)

        /** A new cloud: the creator adds itself. */
        fun create(identity: DeviceIdentity, nodeId: String, name: String, now: Long, maxEntries: Int = MAX_ENTRIES): MemberLog {
            val body = Entry.genesisBody(Crypto.random(16), now, Shape.requireNodeId(nodeId), Shape.requireName(name), identity.publicKey)
            val genesis = Entry.sign(identity, body)
            return MemberLog(cloudIdOf(genesis), genesis, maxEntries)
        }

        /**
         * Builds a log from a received list (the join answer). Fails unless it has exactly one genesis, signed by its own
         * key, and every other entry is accepted.
         */
        fun fromEntries(entries: List<Entry>): MemberLog {
            val roots = entries.filter { it.type == Entry.GENESIS }
            if (roots.size != 1) malformed("a list has exactly one genesis")
            val genesis = roots[0]
            if (!genesis.signedBy(genesis.publicKey!!)) malformed("genesis signature")
            val log = MemberLog(cloudIdOf(genesis), genesis, MAX_ENTRIES)
            val result = log.merge(entries.filter { it.type != Entry.GENESIS })
            if (result.rejected != 0) malformed("entries that do not verify")
            return log
        }
    }
}

/** List framing (sections 3.7 and 4.2). */
internal object EntryList {
    fun encode(entries: List<Entry>): ByteArray {
        val w = Writer().u32(entries.size)
        for (e in entries) w.lp(e.encode())
        return w.bytes()
    }

    fun decode(r: Reader): List<Entry> {
        val n = r.u32()
        if (n > MemberLog.MAX_ENTRIES + MemberLog.RESERVED_FOR_REMOVES) malformed("too many entries")
        return List(n) { Entry.parse(r.lp(Entry.MAX_BODY + 4 + Crypto.SIG_BYTES)) }
    }

    /** `LogPull` request body: the ids the requester already holds. */
    fun pull(have: Set<String>): ByteArray {
        val w = Writer().u32(have.size)
        for (id in have.sorted()) w.fixed(Hex.parse(id), 32)
        return w.bytes()
    }

    fun parsePull(body: ByteArray): Set<String> {
        val r = Reader(body)
        val n = r.u32()
        if (n > MemberLog.MAX_ENTRIES + MemberLog.RESERVED_FOR_REMOVES) malformed("too many ids")
        val ids = HashSet<String>()
        repeat(n) { ids.add(Hex.of(r.fixed(32))) }
        r.end()
        return ids
    }
}
