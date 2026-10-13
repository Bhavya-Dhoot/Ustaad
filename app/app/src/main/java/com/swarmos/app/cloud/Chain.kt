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
    class Checkpoint(val state: ByteArray) : Payload()
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
        const val CHECKPOINT = 6

        /** One limit for creating and parsing (stage 1 enforced it only when parsing). A normal entry is under 400 bytes. */
        const val MAX_BODY = 262_144
        const val MAX_MEMBERS = 512
        const val MAX_REMOVED = 4096
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

        internal fun checkpointBody(s: ChainState, time: Long): ByteArray =
            header(CHECKPOINT, 1, s.cloudId, s.nextSeq, s.headId, time).raw(StateCodec.encode(s)).bytes()

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
            if (role != 1 && !(role == 2 && op == TRANSFER)) malformed("role")
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
                CHECKPOINT -> Payload.Checkpoint(r.rest())
                else -> malformed("entry type")
            }
            if (op != CHECKPOINT) r.end()
            return ChainEntry(body, sig, op, role, cloudId, seq, prev, time, p)
        }

        private fun pubKey(r: Reader): ByteArray = r.fixed(Crypto.PUB_BYTES).also { if (!Crypto.isValidPoint(it)) malformed("public key") }
    }
}

/** Encoding of the full state inside a checkpoint (3.4): canonical, members and removed ids sorted by id. */
internal object StateCodec {
    fun encode(s: ChainState): ByteArray {
        val w = Writer().fixed(s.ownerKey, Crypto.PUB_BYTES).fixed(s.recoveryKey, Crypto.PUB_BYTES).u32(s.epoch).u8(s.commitments.size)
        for ((e, c) in s.commitments) w.u32(e).fixed(c, 32)
        w.u8(if (s.needsRotation) 1 else 0)
        val members = s.members.sortedBy { it.id }
        w.u32(members.size)
        for (m in members) {
            w.fixed(Hex.parse(m.id), 32).str(m.nodeId).str(m.name).u8(m.kind.code)
            if (m.publicKey != null) w.u8(1).fixed(m.publicKey, Crypto.PUB_BYTES) else w.u8(2).fixed(Hex.parse(m.issuer!!), 32).fixed(m.keyId!!, 16)
            w.u64(m.addedSeq)
        }
        val removed = s.removed.sorted()
        w.u32(removed.size)
        for (id in removed) w.fixed(Hex.parse(id), 32)
        return w.bytes()
    }

    /** The state a checkpoint at [seq] describes. Everything is range-checked; the owner's signature is checked by the caller. */
    fun decode(bytes: ByteArray, cloudId: ByteArray, seq: Long, headId: ByteArray): ChainState {
        val r = Reader(bytes)
        val owner = r.fixed(Crypto.PUB_BYTES)
        val recovery = r.fixed(Crypto.PUB_BYTES)
        if (!Crypto.isValidPoint(owner) || !Crypto.isValidPoint(recovery)) malformed("checkpoint key")
        val epoch = r.u32()
        val n = r.u8()
        if (n !in 1..2) malformed("checkpoint epochs")
        val commits = List(n) { r.u32() to r.fixed(32) }
        val needs = r.u8().also { if (it > 1) malformed("flag") } == 1
        val count = r.u32()
        if (count > ChainEntry.MAX_MEMBERS) malformed("too many members")
        val members = LinkedHashMap<String, ChainMember>()
        repeat(count) {
            val id = Hex.of(r.fixed(32))
            val nodeId = Shape.requireNodeId(r.str(8))
            val name = Shape.requireName(r.str(Shape.MAX_NAME_BYTES))
            val kind = MemberKind.of(r.u8()) ?: malformed("kind")
            val m = when (r.u8()) {
                1 -> ChainMember(id, nodeId, name, kind, r.fixed(Crypto.PUB_BYTES).also { if (!Crypto.isValidPoint(it)) malformed("key") }, null, null, 0)
                2 -> ChainMember(id, nodeId, name, kind, null, Hex.of(r.fixed(32)), r.fixed(16), 0)
                else -> malformed("key type")
            }
            members[id] = ChainMember(m.id, m.nodeId, m.name, m.kind, m.publicKey, m.issuer, m.keyId, r.u64())
        }
        val rc = r.u32()
        if (rc > ChainEntry.MAX_REMOVED) malformed("too many removed")
        val removed = LinkedHashSet<String>()
        repeat(rc) { removed.add(Hex.of(r.fixed(32))) }
        r.end()
        return ChainState(cloudId, owner, recovery, epoch, commits, needs, members, removed, seq + 1, headId)
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
            is Payload.Checkpoint -> {
                if (e.role != 1) bad("role")
                if (!StateCodec.encode(s).contentEquals(p.state)) bad("the checkpoint does not describe the state")
                s.copy(nextSeq = next, headId = e.idBytes)
            }
        }
    }
}

/** What happened to a run of entries offered to a [Chain]. */
sealed interface Offer {
    class Adopted(val count: Int) : Offer
    object Unchanged : Offer
    class RolledBack(val fromSeq: Long, val dropped: Int) : Offer
    /** Two different entries at one number: the device kept its own and refused the other. */
    class Equivocation(val seq: Long) : Offer
    class Refused(val reason: String) : Offer
}

/** A fork the device refused to follow, for the Devices screen. */
class Flag(val seq: Long, val heldId: String, val otherId: String)

/**
 * A cloud's member list (docs/CLOUD-PROTOCOL.md 3): one chain written by one owner key. Everything else is a reader:
 * [offer] takes entries from anyone and adopts only what extends the chain, or a recovery-signed transfer that forks it.
 * There is no merge, no fold and no shared capacity a member can exhaust. One lock; state is cached at the tip.
 */
class Chain private constructor(
    val cloudId: ByteArray,
    private val held: MutableList<ChainEntry>,
    private var baseState: ChainState,
    private var tip: ChainState,
    private val firstSeen: MutableMap<Long, Long>,
) {
    private val flagList = ArrayList<Flag>()
    private var droppedList: List<ChainEntry> = emptyList()

    private val baseSeq: Long get() = held[0].seq

    @Synchronized fun state(): ChainState = tip
    @Synchronized fun entries(): List<ChainEntry> = held.toList()
    @Synchronized fun flags(): List<Flag> = flagList.toList()

    /** Entries the last rollback dropped, so the new owner can re-apply removals that were among them. */
    @Synchronized fun dropped(): List<ChainEntry> = droppedList

    /** `<head number>:<8 bytes of the head id in hex>`, carried in the heartbeat (3.6). */
    @Synchronized fun heartbeat(): String = "${tip.headSeq}:${Hex.of(tip.headId.copyOf(8))}"

    @Synchronized private fun heldAt(seq: Long): ChainEntry = held[(seq - baseSeq).toInt()]

    /** The state right after entry [seq] (replayed from the base). */
    private fun stateAt(seq: Long): ChainState {
        var s = baseState
        for (i in 1 until held.size) {
            if (held[i].seq > seq) break
            s = ChainRules.apply(s, held[i])
        }
        return s
    }

    private fun noteCheckpoints(from: List<ChainEntry>, now: Long) {
        for (e in from) if (e.op == ChainEntry.CHECKPOINT) firstSeen.putIfAbsent(e.seq, now)
    }

    // ---- reading: what other devices send us -----------------------------------------------------------------------

    /**
     * Decide on a contiguous run of entries from anyone (3.5): extension, jump to a checkpoint, recovery rollback,
     * equivocation or refusal. A run that contains an invalid entry is refused whole; nothing of it is taken.
     */
    @Synchronized fun offer(runIn: List<ChainEntry>, now: Long): Offer {
        if (runIn.isEmpty()) return Offer.Unchanged
        if (runIn.size > MAX_RUN) return Offer.Refused("run too long")
        for (i in 1 until runIn.size) if (runIn[i].seq != runIn[i - 1].seq + 1) return Offer.Refused("not contiguous")
        var run = runIn
        val headSeq = tip.headSeq
        if (run[0].seq > headSeq + 1) return jump(run, now)
        if (run[0].seq < baseSeq) { // the part below our base is history we dropped; take it as equal and look at the rest
            val skip = (baseSeq - run[0].seq).toInt()
            if (skip >= run.size) return Offer.Unchanged
            run = run.drop(skip)
        }
        var k = 0
        while (k < run.size && run[k].seq <= headSeq && heldAt(run[k].seq).id == run[k].id) k++
        if (k == run.size) return Offer.Unchanged
        if (run[k].seq > headSeq) return extend(run.drop(k), now)

        val f = run[k].seq
        val mine = heldAt(f)
        val recoveryFork = run[k].op == ChainEntry.TRANSFER && run[k].role == 2 && !(mine.op == ChainEntry.TRANSFER && mine.role == 2)
        if (!recoveryFork) {
            // Only a VALID entry (signed by the key in force, following the rules) is evidence of a fork. Anything else is junk from
            // whoever sent it, and must not be able to raise flags: a non-owner can write nothing here.
            if (f <= baseSeq) return Offer.Refused("difference at the base")
            val valid = try { ChainRules.apply(stateAt(f - 1), run[k]); true } catch (e: ProtocolError) { false }
            if (!valid) return Offer.Refused("not a valid entry")
            if (flagList.none { it.seq == f && it.otherId == run[k].id }) flagList.add(Flag(f, mine.id, run[k].id))
            return Offer.Equivocation(f)
        }
        if (f <= baseSeq) return Offer.Refused("fork below the base")
        return try {
            var st = stateAt(f - 1)
            for (e in run.drop(k)) st = ChainRules.apply(st, e)
            droppedList = held.filter { it.seq >= f }
            while (held.last().seq >= f) held.removeAt(held.size - 1)
            held.addAll(run.drop(k))
            tip = st
            noteCheckpoints(run.drop(k), now)
            Offer.RolledBack(f, droppedList.size)
        } catch (e: ProtocolError) {
            Offer.Refused(e.message ?: "invalid")
        }
    }

    private fun extend(entries: List<ChainEntry>, now: Long): Offer = try {
        var st = tip
        for (e in entries) st = ChainRules.apply(st, e)
        held.addAll(entries)
        tip = st
        noteCheckpoints(entries, now)
        Offer.Adopted(entries.size)
    } catch (e: ProtocolError) {
        Offer.Refused(e.message ?: "invalid")
    }

    /** A checkpoint beyond our head (3.5): taken only if the owner we hold vouches for it and it keeps our removals. */
    private fun jump(run: List<ChainEntry>, now: Long): Offer {
        val c = run[0]
        val p = c.payload as? Payload.Checkpoint ?: return Offer.Refused("gap")
        return try {
            if (c.role != 1 || !c.cloudId.contentEquals(cloudId)) return Offer.Refused("not our checkpoint")
            if (!Crypto.ecdsaVerify(tip.ownerKey, ChainEntry.signedMessage(c.body), c.signature)) return Offer.Refused("not signed by the owner we hold")
            val s = StateCodec.decode(p.state, cloudId, c.seq, c.idBytes)
            if (!s.ownerKey.contentEquals(tip.ownerKey) || !s.recoveryKey.contentEquals(tip.recoveryKey)) return Offer.Refused("ownership changed: the entries are needed")
            if (!s.removed.containsAll(tip.removed) || s.epoch < tip.epoch) return Offer.Refused("checkpoint drops what we know")
            var st = s
            for (e in run.drop(1)) st = ChainRules.apply(st, e)
            held.clear(); held.addAll(run)
            baseState = s
            tip = st
            firstSeen.clear()
            noteCheckpoints(run, now)
            Offer.Adopted(run.size)
        } catch (e: ProtocolError) {
            Offer.Refused(e.message ?: "invalid")
        }
    }

    /** Drop the entries before the last checkpoint that this device first saw [holdMs] ago (3.5, compaction). */
    @Synchronized fun compact(now: Long, holdMs: Long = HOLD_MS) {
        val at = held.indices.lastOrNull { held[it].op == ChainEntry.CHECKPOINT && it > 0 && (firstSeen[held[it].seq] ?: now) + holdMs <= now } ?: return
        val newBase = stateAt(held[at].seq)
        repeat(at) { held.removeAt(0) }
        baseState = newBase
    }

    // ---- sync (3.6) ----------------------------------------------------------------------------------------------------

    /** `ChainPull` request: what this device holds (`fromSeq`, `prevId`). */
    @Synchronized fun pullRequest(): Pair<Long, ByteArray> = tip.nextSeq to tip.headId

    /** The entries a peer needs given its `ChainPull`: those after it, or from our base if it is on another branch. */
    @Synchronized fun entriesAfter(fromSeq: Long, prevId: ByteArray): List<ChainEntry> {
        val prevSeq = fromSeq - 1
        val matches = prevSeq >= baseSeq && prevSeq <= tip.headSeq && heldAt(prevSeq).idBytes.contentEquals(prevId)
        val start = if (matches) (fromSeq - baseSeq).toInt() else 0
        return held.drop(start).take(MAX_RUN_OUT)
    }

    // ---- writing: only with the owner key ---------------------------------------------------------------------------------

    private fun write(identity: DeviceIdentity, build: (ChainState) -> ByteArray): ChainEntry {
        check(identity.publicKey.contentEquals(tip.ownerKey)) { "only the owner key writes the member list" }
        val e = ChainEntry.sign(identity, build(tip))
        tip = ChainRules.apply(tip, e)
        held.add(e)
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

    @Synchronized fun checkpoint(owner: DeviceIdentity, now: Long): ChainEntry {
        val e = write(owner) { ChainEntry.checkpointBody(it, now) }
        firstSeen.putIfAbsent(e.seq, now)
        return e
    }

    /**
     * Ownership moves by the recovery key (3.1, 3.5). [forkAt] is the number of the transfer: the default is the next one
     * (a plain extension); a lower number forks the chain there and drops what followed. The recovery key checked is the
     * one in force just before [forkAt]. [newRecovery] is the key that replaces it (the old code is retired).
     */
    @Synchronized fun recover(
        recovery: DeviceIdentity, newOwner: ByteArray, nodeId: String, name: String, newRecovery: ByteArray, now: Long,
        forkAt: Long = tip.nextSeq,
    ): ChainEntry {
        require(forkAt > baseSeq && forkAt <= tip.nextSeq) { "fork point outside the held chain" }
        val before = stateAt(forkAt - 1)
        check(recovery.publicKey.contentEquals(before.recoveryKey)) { "this is not the recovery key in force there" }
        val e = ChainEntry.sign(recovery, ChainEntry.transferBody(before, 2, now, newOwner, Shape.requireNodeId(nodeId), Shape.requireName(name), newRecovery))
        val st = ChainRules.apply(before, e)
        droppedList = held.filter { it.seq >= forkAt }
        while (held.last().seq >= forkAt) held.removeAt(held.size - 1)
        held.add(e)
        tip = st
        return e
    }

    companion object {
        const val MAX_RUN = 1024
        const val MAX_RUN_OUT = 256
        const val HOLD_MS = 24L * 3600 * 1000

        /** A new cloud: the creator's key is the owner, [recoveryPublic] is the recovery key's public half, epoch 0 is [secrets0]. */
        fun create(owner: DeviceIdentity, recoveryPublic: ByteArray, nodeId: String, name: String, secrets0: EpochSecrets, now: Long): Chain {
            require(secrets0.epoch == 0) { "the genesis names epoch 0" }
            val body = ChainEntry.genesisBody(Crypto.random(16), now, owner.publicKey, recoveryPublic, Shape.requireNodeId(nodeId), Shape.requireName(name), secrets0.commitment())
            val g = ChainEntry.sign(owner, body)
            val s = ChainRules.genesisState(g)
            return Chain(s.cloudId, arrayListOf(g), s, s, HashMap())
        }

        /**
         * A chain from a received run (the join answer, a pull): it starts at the genesis, or at a checkpoint whose cloud id is
         * [expectedCloudId] (the one in the invite). A checkpoint start trusts the host and the owner's signature on it.
         */
        fun fromEntries(entries: List<ChainEntry>, now: Long, expectedCloudId: ByteArray? = null): Chain {
            if (entries.isEmpty() || entries.size > MAX_RUN * 16) throw ProtocolError(CloudError.BAD_ENTRY, "empty or too long")
            val first = entries[0]
            val base: ChainState = when (first.op) {
                ChainEntry.GENESIS -> ChainRules.genesisState(first)
                ChainEntry.CHECKPOINT -> {
                    val c = first.payload as Payload.Checkpoint
                    if (expectedCloudId == null || !first.cloudId.contentEquals(expectedCloudId)) throw ProtocolError(CloudError.BAD_ENTRY, "checkpoint of another cloud")
                    val s = StateCodec.decode(c.state, first.cloudId, first.seq, first.idBytes)
                    if (!Crypto.ecdsaVerify(s.ownerKey, ChainEntry.signedMessage(first.body), first.signature)) throw ProtocolError(CloudError.BAD_ENTRY, "checkpoint signature")
                    s
                }
                else -> throw ProtocolError(CloudError.BAD_ENTRY, "a chain starts at a genesis or a checkpoint")
            }
            if (expectedCloudId != null && !base.cloudId.contentEquals(expectedCloudId)) throw ProtocolError(CloudError.BAD_ENTRY, "another cloud")
            var st = base
            for (e in entries.drop(1)) st = ChainRules.apply(st, e)
            val chain = Chain(base.cloudId, ArrayList(entries), base, st, HashMap())
            chain.noteCheckpoints(entries, now)
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
        if (n > Chain.MAX_RUN * 16) malformed("too many entries")
        return List(n) { ChainEntry.parse(r.lp(ChainEntry.MAX_BODY + 4 + Crypto.SIG_BYTES)) }
    }
}
