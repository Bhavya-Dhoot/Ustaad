package com.swarmos.app.cloud

/** One current member in a chain's state. */
class ChainMember(
    /** `fp(pub)` of a device key; the token member id of a token client. */
    val id: String,
    val nodeId: String,
    val name: String,
    val kind: MemberKind,
    val publicKey: ByteArray?,
    /** Issuing node (member id) of a token client. */
    val issuer: String?,
    val keyId: ByteArray?,
    val addedSeq: Long,
)

/** The state a chain has reached (docs/CLOUD-PROTOCOL.md 3.3). Immutable: applying an entry makes a new one. */
class ChainState internal constructor(
    val cloudId: ByteArray,
    val ownerKey: ByteArray,
    val recoveryKey: ByteArray,
    val epoch: Int,
    /** Commitments of the current and the previous epoch, newest first. */
    internal val commitments: List<Pair<Int, ByteArray>>,
    /** A removal (or a recovery transfer) happened and no rotate has followed yet. */
    val needsRotation: Boolean,
    private val byId: Map<String, ChainMember>,
    val removed: Set<String>,
    val nextSeq: Long,
    internal val headId: ByteArray,
) {
    val ownerId: String get() = Hex.of(Crypto.fingerprint(ownerKey))
    val headSeq: Long get() = nextSeq - 1
    val members: List<ChainMember> get() = byId.values.toList()

    fun member(id: String): ChainMember? = byId[id]
    fun isRemoved(id: String): Boolean = id in removed
    fun byNodeId(nodeId: String): ChainMember? = byId.values.firstOrNull { it.nodeId == nodeId }
    fun commitment(epoch: Int): ByteArray? = commitments.firstOrNull { it.first == epoch }?.second
    internal fun copy(
        ownerKey: ByteArray = this.ownerKey, recoveryKey: ByteArray = this.recoveryKey, epoch: Int = this.epoch,
        commitments: List<Pair<Int, ByteArray>> = this.commitments, needsRotation: Boolean = this.needsRotation,
        members: Map<String, ChainMember> = this.byId, removed: Set<String> = this.removed,
        nextSeq: Long = this.nextSeq, headId: ByteArray = this.headId,
    ) = ChainState(cloudId, ownerKey, recoveryKey, epoch, commitments, needsRotation, members, removed, nextSeq, headId)
}

internal sealed class Payload {
    class Genesis(val seed: ByteArray, val owner: ByteArray, val recovery: ByteArray, val nodeId: String, val name: String, val commit0: ByteArray) : Payload()
    class Add(val nodeId: String, val name: String, val kind: MemberKind, val key: ByteArray?, val issuer: ByteArray?, val keyId: ByteArray?) : Payload()
    class Remove(val target: String) : Payload()
    class Rotate(val epoch: Int, val commitment: ByteArray) : Payload()
    class Transfer(val newOwner: ByteArray, val nodeId: String, val name: String, val newRecovery: ByteArray?) : Payload()
    class ReplaceRecovery(val newRecovery: ByteArray) : Payload()
}

/**
 * One entry of the owner's chain (3.2): `lp(body) || sig`, id = SHA-256 of the body. The signature says nothing about
 * the entry's place; [Chain] checks number, predecessor, signer and rule.
 */
class ChainEntry private constructor(
    val body: ByteArray,
    val signature: ByteArray,
    val op: Int,
    /** 1 owner key, 2 recovery key (transfer only). */
    val role: Int,
    val cloudId: ByteArray,
    val seq: Long,
    val prev: ByteArray,
    val time: Long,
    internal val payload: Payload,
) {
    internal val idBytes: ByteArray = Crypto.sha256(body)
    val id: String = Hex.of(idBytes)

    fun encode(): ByteArray = Writer().lp(body).fixed(signature, Crypto.SIG_BYTES).bytes()

    companion object {
        const val GENESIS = 1
        const val ADD = 2
        const val REMOVE = 3
        const val ROTATE = 4
        const val TRANSFER = 5
        const val REPLACE_RECOVERY = 6

        /** One limit for creating and parsing. An entry is 230 to 350 bytes; there are no checkpoints any more (docs 3.4). */
        const val MAX_BODY = 1024
        const val MAX_MEMBERS = 512
        const val MAX_REMOVED = 4096

        /** The chain cap: a number only the owner could reach (one owner signature per entry). */
        const val MAX_ENTRIES = 65_536
        private const val LABEL = "ustaad/v1/chain"

        internal fun signedMessage(body: ByteArray): ByteArray = concat(lbl("chain/sig"), body)

        internal fun header(op: Int, role: Int, cloudId: ByteArray, seq: Long, prev: ByteArray, time: Long): Writer =
            Writer().str(LABEL).u8(op).u8(role).fixed(cloudId, 16).u64(seq).fixed(prev, 32).u64(time)

        private fun Writer.key(key: ByteArray?, issuer: ByteArray?, keyId: ByteArray?): Writer =
            if (key != null) u8(1).fixed(key, Crypto.PUB_BYTES) else u8(2).fixed(issuer!!, 32).fixed(keyId!!, 16)

        internal fun genesisBody(seed: ByteArray, time: Long, owner: ByteArray, recovery: ByteArray, nodeId: String, name: String, commit0: ByteArray): ByteArray =
            header(GENESIS, 1, ByteArray(16), 0, ByteArray(32), time).fixed(seed, 16).fixed(owner, Crypto.PUB_BYTES)
                .fixed(recovery, Crypto.PUB_BYTES).str(nodeId).str(name).fixed(commit0, 32).bytes()

        internal fun addBody(s: ChainState, time: Long, nodeId: String, name: String, kind: MemberKind, key: ByteArray?, issuer: ByteArray?, keyId: ByteArray?): ByteArray =
            header(ADD, 1, s.cloudId, s.nextSeq, s.headId, time).str(nodeId).str(name).u8(kind.code).key(key, issuer, keyId).bytes()

        internal fun removeBody(s: ChainState, time: Long, target: String): ByteArray =
            header(REMOVE, 1, s.cloudId, s.nextSeq, s.headId, time).fixed(Hex.parse(target), 32).bytes()

        internal fun rotateBody(s: ChainState, time: Long, epoch: Int, commitment: ByteArray): ByteArray =
            header(ROTATE, 1, s.cloudId, s.nextSeq, s.headId, time).u32(epoch).fixed(commitment, 32).bytes()

        internal fun transferBody(s: ChainState, role: Int, time: Long, newOwner: ByteArray, nodeId: String, name: String, newRecovery: ByteArray?): ByteArray {
            val w = header(TRANSFER, role, s.cloudId, s.nextSeq, s.headId, time).fixed(newOwner, Crypto.PUB_BYTES).str(nodeId).str(name)
            return (if (newRecovery == null) w.u8(0) else w.u8(1).fixed(newRecovery, Crypto.PUB_BYTES)).bytes()
        }

        internal fun replaceRecoveryBody(s: ChainState, time: Long, newRecovery: ByteArray): ByteArray =
            header(REPLACE_RECOVERY, 2, s.cloudId, s.nextSeq, s.headId, time).fixed(newRecovery, Crypto.PUB_BYTES).bytes()

        internal fun sign(identity: DeviceIdentity, body: ByteArray): ChainEntry = of(body, identity.sign(signedMessage(body)))

        fun parse(wire: ByteArray): ChainEntry {
            val r = Reader(wire)
            val body = r.lp(MAX_BODY)
            val sig = r.fixed(Crypto.SIG_BYTES)
            r.end()
            return of(body, sig)
        }

        /** Parses and range-checks a body; the signature is not checked here. The one place the body limit is enforced. */
        internal fun of(body: ByteArray, sig: ByteArray): ChainEntry {
            if (body.size > MAX_BODY) malformed("entry over the body limit")
            if (sig.size != Crypto.SIG_BYTES) malformed("signature size")
            val r = Reader(body)
            if (r.str(64) != LABEL) malformed("label")
            val op = r.u8()
            val role = r.u8()
            val cloudId = r.fixed(16)
            val seq = r.u64()
            val prev = r.fixed(32)
            val time = r.u64()
            if (seq < 0) malformed("seq")
            if (role != 1 && !(role == 2 && (op == TRANSFER || op == REPLACE_RECOVERY))) malformed("role")
            val p: Payload = when (op) {
                GENESIS -> Payload.Genesis(
                    r.fixed(16), pubKey(r), pubKey(r), Shape.requireNodeId(r.str(8)), Shape.requireName(r.str(Shape.MAX_NAME_BYTES)), r.fixed(32),
                ).also { if (seq != 0L || role != 1) malformed("genesis position") }
                ADD -> {
                    val nodeId = Shape.requireNodeId(r.str(8))
                    val name = Shape.requireName(r.str(Shape.MAX_NAME_BYTES))
                    val kind = MemberKind.of(r.u8()) ?: malformed("kind")
                    when (r.u8()) {
                        1 -> Payload.Add(nodeId, name, kind, pubKey(r), null, null).also { if (kind == MemberKind.BROWSER) malformed("a browser holds a token key") }
                        2 -> Payload.Add(nodeId, name, kind, null, r.fixed(32), r.fixed(16)).also { if (kind == MemberKind.NODE) malformed("a node holds a device key") }
                        else -> malformed("key type")
                    }
                }
                REMOVE -> Payload.Remove(Hex.of(r.fixed(32)))
                ROTATE -> Payload.Rotate(r.u32(), r.fixed(32))
                TRANSFER -> {
                    val newOwner = pubKey(r)
                    val nodeId = Shape.requireNodeId(r.str(8))
                    val name = Shape.requireName(r.str(Shape.MAX_NAME_BYTES))
                    val has = r.u8()
                    if (has > 1) malformed("recovery flag")
                    Payload.Transfer(newOwner, nodeId, name, if (has == 1) pubKey(r) else null)
                }
                REPLACE_RECOVERY -> Payload.ReplaceRecovery(pubKey(r)).also { if (role != 2) malformed("role") }
                else -> malformed("entry type")
            }
            r.end()
            return ChainEntry(body, sig, op, role, cloudId, seq, prev, time, p)
        }

        private fun pubKey(r: Reader): ByteArray = r.fixed(Crypto.PUB_BYTES).also { if (!Crypto.isValidPoint(it)) malformed("public key") }
    }
}

/** The rules of 3.3: what each operation requires of the state it follows. */
internal object ChainRules {
    private fun bad(why: String): Nothing = throw ProtocolError(CloudError.BAD_ENTRY, why)

    fun tokenMemberId(issuer: String, keyId: ByteArray): String = Hex.of(Crypto.sha256(lbl("token"), Hex.parse(issuer), keyId))

    fun genesisState(e: ChainEntry): ChainState {
        val g = e.payload as? Payload.Genesis ?: bad("not a genesis")
        if (e.op != ChainEntry.GENESIS || e.seq != 0L) bad("genesis position")
        if (g.owner.contentEquals(g.recovery)) bad("owner and recovery key must differ")
        if (!Crypto.ecdsaVerify(g.owner, ChainEntry.signedMessage(e.body), e.signature)) bad("genesis signature")
        val ownerId = Hex.of(Crypto.fingerprint(g.owner))
        val cloudId = Crypto.sha256(lbl("cloud"), e.body).copyOf(16)
        val me = ChainMember(ownerId, g.nodeId, g.name, MemberKind.NODE, g.owner, null, null, 0)
        return ChainState(cloudId, g.owner, g.recovery, 0, listOf(0 to g.commit0), false, mapOf(ownerId to me), emptySet(), 1, e.idBytes)
    }

    /** The state after [e], or [ProtocolError] `bad_entry` if [e] does not follow from [s]. */
    fun apply(s: ChainState, e: ChainEntry): ChainState {
        if (e.seq != s.nextSeq) bad("wrong number")
        if (e.seq >= ChainEntry.MAX_ENTRIES) bad("the chain is full")
        if (!e.prev.contentEquals(s.headId)) bad("wrong predecessor")
        if (!e.cloudId.contentEquals(s.cloudId)) bad("another cloud")
        val signer = if (e.role == 1) s.ownerKey else s.recoveryKey
        if (!Crypto.ecdsaVerify(signer, ChainEntry.signedMessage(e.body), e.signature)) bad("not signed by the key in force")
        val next = s.nextSeq + 1
        return when (val p = e.payload) {
            is Payload.Genesis -> bad("a second genesis")
            is Payload.Add -> {
                if (s.members.size >= ChainEntry.MAX_MEMBERS) bad("member limit")
                if (s.byNodeId(p.nodeId) != null) bad("node id in use")
                val id: String
                val m: ChainMember
                if (p.key != null) {
                    if (p.kind == MemberKind.BROWSER) bad("a browser holds a token key")
                    if (p.key.contentEquals(s.recoveryKey)) bad("the recovery key is not a device key")
                    id = Hex.of(Crypto.fingerprint(p.key))
                    m = ChainMember(id, p.nodeId, p.name, p.kind, p.key, null, null, e.seq)
                } else {
                    if (p.kind == MemberKind.NODE) bad("a node holds a device key")
                    val issuer = Hex.of(p.issuer!!)
                    if (s.member(issuer)?.kind != MemberKind.NODE) bad("a token client needs a current node as issuer")
                    id = tokenMemberId(issuer, p.keyId!!)
                    m = ChainMember(id, p.nodeId, p.name, p.kind, null, issuer, p.keyId, e.seq)
                }
                if (s.member(id) != null || s.isRemoved(id)) bad("member exists or was removed")
                s.copy(members = s.members.associateBy { it.id } + (id to m), nextSeq = next, headId = e.idBytes)
            }
            is Payload.Remove -> {
                if (e.role != 1) bad("role")
                if (s.member(p.target) == null) bad("not a current member")
                if (p.target == s.ownerId) bad("the owner transfers before it can be removed")
                val gone = HashSet<String>().apply { add(p.target); s.members.filter { it.issuer == p.target }.forEach { add(it.id) } }
                if (s.removed.size + gone.size > ChainEntry.MAX_REMOVED) bad("removed limit")
                s.copy(members = s.members.filter { it.id !in gone }.associateBy { it.id }, removed = s.removed + gone, needsRotation = true, nextSeq = next, headId = e.idBytes)
            }
            is Payload.Rotate -> {
                if (e.role != 1) bad("role")
                if (p.epoch != s.epoch + 1) bad("epoch must be the next one")
                s.copy(epoch = p.epoch, commitments = listOf(p.epoch to p.commitment) + s.commitments.take(1), needsRotation = false, nextSeq = next, headId = e.idBytes)
            }
            is Payload.Transfer -> {
                val newId = Hex.of(Crypto.fingerprint(p.newOwner))
                if (s.isRemoved(newId)) bad("a removed key cannot own the cloud")
                if (p.newOwner.contentEquals(s.recoveryKey)) bad("the recovery key is not a device key")
                if (e.role == 1) {
                    if (p.newRecovery != null) bad("only the recovery key sets a recovery key")
                    if (p.newOwner.contentEquals(s.ownerKey)) bad("transfer to the same key")
                } else {
                    val nr = p.newRecovery ?: bad("a recovery transfer sets a new recovery key")
                    if (nr.contentEquals(s.recoveryKey) || nr.contentEquals(p.newOwner)) bad("the new recovery key must be new")
                }
                val existing = s.member(newId)
                val members = s.members.associateBy { it.id }.toMutableMap()
                if (existing != null) {
                    if (existing.kind != MemberKind.NODE || existing.publicKey == null) bad("the new owner must be a node")
                } else {
                    if (members.size >= ChainEntry.MAX_MEMBERS) bad("member limit")
                    if (s.byNodeId(p.nodeId) != null) bad("node id in use")
                    members[newId] = ChainMember(newId, p.nodeId, p.name, MemberKind.NODE, p.newOwner, null, null, e.seq)
                }
                s.copy(
                    ownerKey = p.newOwner, recoveryKey = p.newRecovery ?: s.recoveryKey, members = members,
                    needsRotation = s.needsRotation || e.role == 2, nextSeq = next, headId = e.idBytes,
                )
            }
            is Payload.ReplaceRecovery -> {
                val nr = p.newRecovery
                if (e.role != 2) bad("only the recovery key replaces the recovery key")
                if (nr.contentEquals(s.recoveryKey) || nr.contentEquals(s.ownerKey)) bad("the new recovery key must be new")
                val nrId = Hex.of(Crypto.fingerprint(nr))
                if (s.member(nrId) != null || s.isRemoved(nrId)) bad("the recovery key is not a device key")
                s.copy(recoveryKey = nr, nextSeq = next, headId = e.idBytes)
            }
        }
    }
}

/** What happened to a run of entries offered to a [Chain]. */
sealed interface Offer {
    class Adopted(val count: Int) : Offer
    object Unchanged : Offer
    class RolledBack(val fromSeq: Long, val dropped: Int) : Offer
    /** Two different valid entries at one number: the device kept its own and refused the other. */
    class Equivocation(val seq: Long) : Offer
    class Refused(val reason: String) : Offer
}

/** Evidence of a fork the device refused to follow, for the Devices screen: one per number and signer (3.5). */
class Flag(val seq: Long, val heldId: String, val otherId: String, val signer: String)

/**
 * A cloud's member list (docs/CLOUD-PROTOCOL.md 3): one chain written by one owner key, held whole from the genesis. Everything
 * else is a reader: [offer] takes entries from anyone and adopts only what extends the chain, or a recovery-signed entry that forks
 * it above the recovery boundary. There is no merge, no fold, no checkpoint and no shared capacity a member can exhaust.
 *
 * Verification results for the part already held are kept: the keys in force at every number, and a state snapshot every
 * [SNAP] entries. An offered entry is authenticated against the keys in force (one signature check) before anything is replayed,
 * and a replay never covers more than [SNAP] - 1 entries. One lock.
 */
class Chain private constructor(
    val cloudId: ByteArray,
    private val held: MutableList<ChainEntry>,
    private var tip: ChainState,
) {
    /** The owner and recovery keys that sign entries numbered [fromSeq] and above, until the next change. */
    private class Auth(val fromSeq: Long, val owner: ByteArray, val recovery: ByteArray)

    private val auths = ArrayList<Auth>()
    private val snaps = ArrayList<ChainState>() // snaps[k] is the state after entry SNAP * k
    private val flagMap = LinkedHashMap<String, Flag>()
    private var droppedList: List<ChainEntry> = emptyList()
    private var floor = 0L // the highest number of a recovery-signed entry held: the recovery boundary

    /** Test instruments: how many times history was replayed, and how many entries that replay applied. */
    internal var replays = 0
    internal var replayedEntries = 0L

    init {
        snaps.add(tip)
        auths.add(Auth(1, tip.ownerKey, tip.recoveryKey))
    }

    @Synchronized fun state(): ChainState = tip
    @Synchronized fun entries(): List<ChainEntry> = held.toList()
    @Synchronized fun flags(): List<Flag> = flagMap.values.toList()

    /** Entries the last rollback dropped, so the new owner can re-apply removals that were among them. */
    @Synchronized fun dropped(): List<ChainEntry> = droppedList

    /** The recovery boundary: no fork below this number is accepted (3.5). */
    @Synchronized fun recoveryBoundary(): Long = floor

    /** `<head number>:<8 bytes of the head id in hex>`, carried in the heartbeat (3.6). */
    @Synchronized fun heartbeat(): String = "${tip.headSeq}:${Hex.of(tip.headId.copyOf(8))}"

    private fun authorityFor(seq: Long): Auth = auths.last { it.fromSeq <= seq }

    /** The state right after entry [seq]: from the nearest snapshot, at most [SNAP] - 1 entries of replay. */
    private fun stateAt(seq: Long): ChainState {
        replays++
        val k = (seq / SNAP).toInt().coerceAtMost(snaps.size - 1)
        var s = snaps[k]
        for (i in s.headSeq + 1..seq) {
            s = ChainRules.apply(s, held[i.toInt()])
            replayedEntries++
        }
        return s
    }

    /** Records what [after] means for the bookkeeping, once [e] has been applied to [before] and added to the chain. */
    private fun noted(before: ChainState, e: ChainEntry, after: ChainState) {
        if (e.seq % SNAP == 0L) snaps.add(after)
        if (!after.ownerKey.contentEquals(before.ownerKey) || !after.recoveryKey.contentEquals(before.recoveryKey)) {
            auths.add(Auth(e.seq + 1, after.ownerKey, after.recoveryKey))
        }
        if (e.role == 2) floor = maxOf(floor, e.seq)
    }

    private fun append(e: ChainEntry, after: ChainState) {
        val before = tip
        held.add(e)
        tip = after
        noted(before, e, after)
    }

    /** Drops every entry numbered [f] and above, the bookkeeping about them, and puts the tip back to the state before [f]. */
    private fun truncateTo(f: Long) {
        droppedList = held.filter { it.seq >= f }
        while (held.last().seq >= f) held.removeAt(held.size - 1)
        snaps.removeAll { it.headSeq >= f }
        auths.removeAll { it.fromSeq > f }
        floor = held.filter { it.role == 2 }.maxOfOrNull { it.seq } ?: 0L
        tip = stateAt(f - 1)
    }

    // ---- reading: what other devices send us -----------------------------------------------------------------------

    /**
     * Decide on a contiguous run of entries from anyone (3.5): extension, recovery rollback, equivocation or refusal. A run that
     * contains an invalid entry is refused whole; nothing of it is taken.
     */
    @Synchronized fun offer(run: List<ChainEntry>): Offer {
        if (run.isEmpty()) return Offer.Unchanged
        if (run.size > MAX_RUN) return Offer.Refused("run too long")
        for (i in 1 until run.size) if (run[i].seq != run[i - 1].seq + 1) return Offer.Refused("not contiguous")
        val headSeq = tip.headSeq
        if (run[0].seq > headSeq + 1) return Offer.Refused("gap")
        var k = 0
        while (k < run.size && run[k].seq <= headSeq && held[run[k].seq.toInt()].id == run[k].id) k++
        if (k == run.size) return Offer.Unchanged
        return if (run[k].seq > headSeq) extend(run.drop(k)) else fork(run, k)
    }

    private fun extend(entries: List<ChainEntry>): Offer = try {
        var st = tip
        val states = ArrayList<ChainState>(entries.size)
        for (e in entries) {
            st = ChainRules.apply(st, e)
            states.add(st)
        }
        for ((i, e) in entries.withIndex()) append(e, states[i]) // only now, when the whole run is valid
        Offer.Adopted(entries.size)
    } catch (e: ProtocolError) {
        Offer.Refused(e.message ?: "invalid")
    }

    private fun fork(run: List<ChainEntry>, k: Int): Offer {
        val e = run[k]
        val f = e.seq
        if (f == 0L) return Offer.Refused("a different genesis: another cloud")
        val mine = held[f.toInt()]
        // 1. authenticate against the keys in force at f: one signature check, no replay of history (Codex Chain.kt:414)
        val a = authorityFor(f)
        val key = if (e.role == 1) a.owner else a.recovery
        if (!Crypto.ecdsaVerify(key, ChainEntry.signedMessage(e.body), e.signature)) return Offer.Refused("not signed by a key in force there")
        val recoveryFork = e.role == 2 && mine.role != 2
        if (!recoveryFork) {
            // 2. a valid entry is evidence of a fork: one record per number and signer, a capped list (Codex Chain.kt:416)
            val id = "$f:${Hex.of(Crypto.fingerprint(key))}"
            if (flagMap.containsKey(id) || flagMap.size >= MAX_FLAGS) return Offer.Equivocation(f)
            val valid = try { ChainRules.apply(stateAt(f - 1), e); true } catch (x: ProtocolError) { false }
            if (!valid) return Offer.Refused("not a valid entry")
            flagMap[id] = Flag(f, mine.id, e.id, Hex.of(Crypto.fingerprint(key)))
            return Offer.Equivocation(f)
        }
        // 3. a recovery-signed entry replaces the owner's entries from f on, but never below the recovery boundary
        if (f < floor) return Offer.Refused("below a recovery boundary")
        return try {
            var st = stateAt(f - 1)
            val states = ArrayList<ChainState>()
            for (x in run.drop(k)) {
                st = ChainRules.apply(st, x)
                states.add(st)
            }
            truncateTo(f)
            val kept = droppedList
            for ((i, x) in run.drop(k).withIndex()) append(x, states[i])
            droppedList = kept
            Offer.RolledBack(f, kept.size)
        } catch (x: ProtocolError) {
            Offer.Refused(x.message ?: "invalid")
        }
    }

    // ---- sync (3.6) ----------------------------------------------------------------------------------------------------

    /** `ChainPull` request: what this device holds (`fromSeq`, `prevId`). */
    @Synchronized fun pullRequest(): Pair<Long, ByteArray> = tip.nextSeq to tip.headId

    /** The entries a peer needs given its `ChainPull`: those after it, or from the genesis if it is on another branch. */
    @Synchronized fun entriesAfter(fromSeq: Long, prevId: ByteArray): List<ChainEntry> {
        val prevSeq = fromSeq - 1
        val matches = prevSeq in 0..tip.headSeq && held[prevSeq.toInt()].idBytes.contentEquals(prevId)
        val start = if (matches) fromSeq.toInt() else 0
        return held.drop(start).take(MAX_RUN_OUT)
    }

    // ---- writing: only with the owner key ---------------------------------------------------------------------------------

    private fun write(identity: DeviceIdentity, build: (ChainState) -> ByteArray): ChainEntry {
        check(identity.publicKey.contentEquals(tip.ownerKey)) { "only the owner key writes the member list" }
        val e = ChainEntry.sign(identity, build(tip))
        append(e, ChainRules.apply(tip, e))
        return e
    }

    @Synchronized fun addDevice(owner: DeviceIdentity, nodeId: String, name: String, kind: MemberKind, key: ByteArray, now: Long): ChainEntry =
        write(owner) { ChainEntry.addBody(it, now, Shape.requireNodeId(nodeId), Shape.requireName(name), kind, key, null, null) }

    /** Records a token client of the node [issuer] (a member id). */
    @Synchronized fun addToken(owner: DeviceIdentity, issuer: String, nodeId: String, name: String, kind: MemberKind, keyId: ByteArray, now: Long): ChainEntry =
        write(owner) { ChainEntry.addBody(it, now, Shape.requireNodeId(nodeId), Shape.requireName(name), kind, null, Hex.parse(issuer), keyId) }

    @Synchronized fun remove(owner: DeviceIdentity, target: String, now: Long): ChainEntry =
        write(owner) { ChainEntry.removeBody(it, now, target) }

    /** The next epoch's secrets; [secrets] must carry epoch + 1. */
    @Synchronized fun rotate(owner: DeviceIdentity, secrets: EpochSecrets, now: Long): ChainEntry =
        write(owner) { ChainEntry.rotateBody(it, now, secrets.epoch, secrets.commitment()) }

    /** A removal and, at once, the rotate that must follow it (3.7). */
    @Synchronized fun removeAndRotate(owner: DeviceIdentity, target: String, secrets: EpochSecrets, now: Long): List<ChainEntry> =
        listOf(remove(owner, target, now), rotate(owner, secrets, now))

    @Synchronized fun transfer(owner: DeviceIdentity, newOwner: ByteArray, nodeId: String, name: String, now: Long): ChainEntry =
        write(owner) { ChainEntry.transferBody(it, 1, now, newOwner, Shape.requireNodeId(nodeId), Shape.requireName(name), null) }

    /**
     * Ownership moves by the recovery key (3.1, 3.5). [forkAt] is the number of the transfer: the default is the next one (a
     * plain extension); a lower number forks the chain there and drops what followed, but never at or below the recovery boundary.
     * The recovery key checked is the one in force just before [forkAt]. [newRecovery] replaces it (the old code is retired).
     */
    @Synchronized fun recover(
        recovery: DeviceIdentity, newOwner: ByteArray, nodeId: String, name: String, newRecovery: ByteArray, now: Long,
        forkAt: Long = tip.nextSeq,
    ): ChainEntry {
        require(forkAt in 1..tip.nextSeq) { "fork point outside the held chain" }
        require(forkAt == tip.nextSeq || forkAt > floor) { "below a recovery boundary" }
        val before = stateAt(forkAt - 1)
        check(recovery.publicKey.contentEquals(before.recoveryKey)) { "this is not the recovery key in force there" }
        val e = ChainEntry.sign(recovery, ChainEntry.transferBody(before, 2, now, newOwner, Shape.requireNodeId(nodeId), Shape.requireName(name), newRecovery))
        val after = ChainRules.apply(before, e)
        truncateTo(forkAt)
        append(e, after)
        return e
    }

    /** Retires the recovery code in force and puts [newRecovery] in its place; signed by the current recovery key only (3.3). */
    @Synchronized fun replaceRecovery(recovery: DeviceIdentity, newRecovery: ByteArray, now: Long): ChainEntry {
        check(recovery.publicKey.contentEquals(tip.recoveryKey)) { "this is not the recovery key in force" }
        val e = ChainEntry.sign(recovery, ChainEntry.replaceRecoveryBody(tip, now, newRecovery))
        append(e, ChainRules.apply(tip, e))
        return e
    }

    companion object {
        const val MAX_RUN = 1024
        const val MAX_RUN_OUT = 256
        const val MAX_FLAGS = 64
        const val SNAP = 64L

        /** A new cloud: the creator's key is the owner, [recoveryPublic] is the recovery key's public half, epoch 0 is [secrets0]. */
        fun create(owner: DeviceIdentity, recoveryPublic: ByteArray, nodeId: String, name: String, secrets0: EpochSecrets, now: Long): Chain {
            require(secrets0.epoch == 0) { "the genesis names epoch 0" }
            val body = ChainEntry.genesisBody(Crypto.random(16), now, owner.publicKey, recoveryPublic, Shape.requireNodeId(nodeId), Shape.requireName(name), secrets0.commitment())
            val g = ChainEntry.sign(owner, body)
            val s = ChainRules.genesisState(g)
            return Chain(s.cloudId, arrayListOf(g), s)
        }

        /**
         * A chain from a received run (the join answer, a pull), always from the genesis. Every entry is checked in order; the
         * genesis must hash to [expectedCloudId] (the one in the invite) when it is given (docs 3.4).
         */
        fun fromEntries(entries: List<ChainEntry>, expectedCloudId: ByteArray? = null): Chain {
            if (entries.isEmpty() || entries.size > ChainEntry.MAX_ENTRIES) throw ProtocolError(CloudError.BAD_ENTRY, "empty or too long")
            val first = entries[0]
            if (first.op != ChainEntry.GENESIS) throw ProtocolError(CloudError.BAD_ENTRY, "a chain starts at its genesis")
            val s0 = ChainRules.genesisState(first)
            if (expectedCloudId != null && !s0.cloudId.contentEquals(expectedCloudId)) throw ProtocolError(CloudError.BAD_ENTRY, "the genesis is not the invitation's cloud")
            val chain = Chain(s0.cloudId, arrayListOf(first), s0)
            var st = s0
            for (e in entries.drop(1)) {
                val next = ChainRules.apply(st, e)
                chain.append(e, next)
                st = next
            }
            return chain
        }
    }
}

/** List framing for runs of entries (4.2, 4.5, 3.6). */
internal object EntryRun {
    fun encode(entries: List<ChainEntry>): ByteArray {
        val w = Writer().u32(entries.size)
        for (e in entries) w.lp(e.encode())
        return w.bytes()
    }

    fun decode(r: Reader): List<ChainEntry> {
        val n = r.u32()
        if (n > ChainEntry.MAX_ENTRIES) malformed("too many entries")
        return List(n) { ChainEntry.parse(r.lp(ChainEntry.MAX_BODY + 4 + Crypto.SIG_BYTES)) }
    }
}
