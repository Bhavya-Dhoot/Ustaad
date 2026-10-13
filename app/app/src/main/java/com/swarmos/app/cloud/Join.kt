package com.swarmos.app.cloud

import java.security.KeyPair

/**
 * Joining a cloud (docs/CLOUD-PROTOCOL.md section 4). Any node may host an [Invite]; the newcomer scans it. The one-time code is a
 * secret shared by camera. The request is sealed under a key from the code; the answer is sealed under a key that also needs an
 * ephemeral ECDH result and is signed by the host's key pinned in the QR code, so a code learnt later opens nothing and a holder of
 * the code cannot forge an answer. A newcomer is a member only when the OWNER has signed its add entry after a person confirmed on
 * the owner's device (4.4, 4.5).
 */
class Invite(
    val cloudId: ByteArray,
    val nodeId: String,
    /** `fp(pub)` of the host's key: the key that signs the answer. */
    val fingerprint: ByteArray,
    val addresses: List<String>,
    val port: Int,
    /** 128 random bits: the secret. */
    val code: ByteArray,
) {
    fun toLink(): String =
        "${PREFIX}v=1&c=${B64.enc(cloudId)}&n=$nodeId&f=${B64.enc(fingerprint)}&a=${addresses.joinToString(",")}&p=$port&i=${B64.enc(code)}"

    companion object {
        const val PREFIX = "ustaad://join?"
        const val LIFETIME_MS = 600_000L
        private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
        private val IPV6 = Regex("""\[[0-9a-fA-F:.]{2,45}]""")

        /** Strict: exactly the seven fields, once each; sizes and shapes checked. */
        fun parse(link: String): Invite {
            if (!link.startsWith(PREFIX)) malformed("not an invitation")
            val f = HashMap<String, String>()
            for (part in link.removePrefix(PREFIX).split('&')) {
                val eq = part.indexOf('=')
                if (eq <= 0) malformed("invitation field")
                if (f.put(part.substring(0, eq), part.substring(eq + 1)) != null) malformed("duplicate field")
            }
            if (f.keys != setOf("v", "c", "n", "f", "a", "p", "i")) malformed("invitation fields")
            if (f["v"] != "1") malformed("version")
            val addresses = f.getValue("a").split(',')
            if (addresses.size !in 1..8 || addresses.any { !IPV4.matches(it) && !IPV6.matches(it) }) malformed("addresses")
            val port = f.getValue("p").toIntOrNull()?.takeIf { it in 1..65535 } ?: malformed("port")
            fun bin(k: String, n: Int) = B64.dec(f.getValue(k)).also { if (it.size != n) malformed("field $k") }
            return Invite(bin("c", 16), Shape.requireNodeId(f.getValue("n")), bin("f", 32), addresses, port, bin("i", 16))
        }
    }
}

/** What the host keeps per invitation, in memory. */
class InviteBook {
    internal class Record(val code: ByteArray, val kind: MemberKind, val issuedAt: Long) {
        /** Member id of the key the invite is bound to (the first valid request). */
        var boundTo: String? = null
        var nodeId = ""
        var name = ""
        var key: ByteArray? = null
        var state = NONE
        var ticket: ByteArray? = null
    }

    private val records = ArrayList<Record>()

    /** A new code for a node or a client to be admitted. At most 8 are open at once. */
    @Synchronized fun issue(kind: MemberKind, now: Long): ByteArray {
        require(kind != MemberKind.BROWSER) { "a browser joins with a token, not an invitation" }
        prune(now)
        check(records.count { it.boundTo == null && now < it.issuedAt + Invite.LIFETIME_MS } < MAX_OPEN) { "too many open invitations" }
        val r = Record(Crypto.random(16), kind, now)
        records.add(r)
        return r.code.copyOf()
    }

    /** Records still known, including bound and recently expired ones: they let the UI say why a join failed. */
    @Synchronized internal fun known(now: Long): List<Record> { prune(now); return records.toList() }

    private fun prune(now: Long) { records.removeAll { now > it.issuedAt + Invite.LIFETIME_MS + RETAIN_MS } }

    companion object {
        const val MAX_OPEN = 8
        const val RETAIN_MS = 3_600_000L
        const val NONE = 0
        const val WAITING = 1
        const val GRANTED = 2
        const val DENIED = 3
    }
}

/** Status byte of a sealed join answer. */
object JoinStatus {
    const val OK = 0
    const val ALREADY_MEMBER = 1
    const val REMOVED = 2
    const val NODE_ID_TAKEN = 3
    const val DECLINED = 4
    const val PENDING = 5
}

/** Why the host refused without a sealed answer. On the wire every one of these is the same 403 `join_refused`. */
enum class Refusal { MALFORMED, WRONG_CODE, BAD_PROOF, EXPIRED, USED }

sealed interface HostResult {
    /** [forward] is set the first time a request is passed on: send it to the owner over the session, or hand it to a local [Owner]. */
    class Answer(val bytes: ByteArray, val status: Int, val forward: AdmitForward?) : HostResult
    class Refused(val reason: Refusal) : HostResult
}

internal object JoinWire {
    /** Newcomer to host. `nc` is fresh per request. The request is sealed under the code alone: it carries nothing secret. */
    fun c2aKey(code: ByteArray, cloudId: ByteArray, nc: ByteArray): ByteArray = Crypto.hkdf(code, concat(cloudId, nc), lbl("join/c2a"), 32)

    /** Host to newcomer: the ECDH result AND the code. Neither a late learner of the code nor a holder of the code on the path can make it. */
    fun a2cKey(ecdh: ByteArray, code: ByteArray, cloudId: ByteArray, nc: ByteArray, na: ByteArray): ByteArray =
        Crypto.hkdf(concat(ecdh, code), concat(cloudId, nc, na), lbl("join/a2c"), 32)

    /** Possession of the key, bound to this ephemeral key, this exchange and this host. */
    fun pop(cloudId: ByteArray, nc: ByteArray, nodeId: String, name: String, key: ByteArray, eN: ByteArray, hostNodeId: String): ByteArray =
        concat(lbl("join/pop"), cloudId, nc, Writer().str(nodeId).str(name).fixed(key, Crypto.PUB_BYTES).fixed(eN, Crypto.PUB_BYTES).str(hostNodeId).bytes())

    /** What the host signs: both nonces, both ephemeral keys, the status and digests of everything it carries. */
    fun answerSig(cloudId: ByteArray, nc: ByteArray, na: ByteArray, eN: ByteArray, eH: ByteArray, status: Int, secrets: ByteArray, chain: ByteArray): ByteArray =
        concat(lbl("join/answer"), cloudId, nc, na, eN, eH, Writer().u8(status).bytes(), Crypto.sha256(secrets), Crypto.sha256(chain))

    fun secretsBytes(s: EpochSecrets): ByteArray = Writer().u32(s.epoch).fixed(s.discoveryKey, 32).fixed(s.storageKey, 32).bytes()
}

/** The six digits both screens show (4.4): the newcomer's and the owner's, computed from the same three things. */
object ConfirmationCode {
    fun of(keyOrKeyId: ByteArray, nodeId: String, name: String): String {
        val h = Crypto.sha256(lbl("join/sas"), keyOrKeyId, Writer().str(nodeId).str(name).bytes())
        val v = ((h[0].toLong() and 0xFF) shl 24) or ((h[1].toLong() and 0xFF) shl 16) or ((h[2].toLong() and 0xFF) shl 8) or (h[3].toLong() and 0xFF)
        return (v % 1_000_000L).toString().padStart(6, '0')
    }
}

/** Host to owner (4.5), sealed in their session. `ticket` ties the owner's decision back to the waiting request. */
class AdmitForward(
    val ticket: ByteArray,
    val keyType: Int,
    val nodeId: String,
    val name: String,
    val kind: MemberKind,
    val key: ByteArray?,
    val tokenKeyId: ByteArray?,
    val hostHeadSeq: Long,
    val hostHeadId: ByteArray,
) {
    fun encode(): ByteArray {
        val w = Writer().u8(1).fixed(ticket, 8).u8(keyType).str(nodeId).str(name).u8(kind.code)
        if (keyType == 1) w.fixed(key!!, Crypto.PUB_BYTES) else w.fixed(tokenKeyId!!, 16)
        return w.u64(hostHeadSeq).fixed(hostHeadId, 32).bytes()
    }

    companion object {
        fun parse(bytes: ByteArray): AdmitForward {
            val r = Reader(bytes)
            if (r.u8() != 1) malformed("admit version")
            val ticket = r.fixed(8)
            val keyType = r.u8()
            val nodeId = Shape.requireNodeId(r.str(8))
            val name = Shape.requireName(r.str(Shape.MAX_NAME_BYTES))
            val kind = MemberKind.of(r.u8()) ?: malformed("kind")
            val key = if (keyType == 1) r.fixed(Crypto.PUB_BYTES).also { if (!Crypto.isValidPoint(it)) malformed("key") } else null
            val keyId = if (keyType == 2) r.fixed(16) else null
            if (keyType !in 1..2) malformed("key type")
            val seq = r.u64()
            val head = r.fixed(32)
            r.end()
            if (seq < 0) malformed("head")
            return AdmitForward(ticket, keyType, nodeId, name, kind, key, keyId, seq, head)
        }

        /** A token client recorded by its issuing node (section 8.1). */
        fun forToken(m: TokenMaterial, name: String, kind: MemberKind, chain: Chain): AdmitForward {
            val s = chain.state()
            return AdmitForward(Crypto.random(8), 2, m.nodeId, name, kind, null, m.keyId, s.headSeq, s.headId)
        }
    }
}

/** Owner to host (4.5), sealed in their session: granted with the entries the host lacks, or denied. */
class AdmitDecision(val ticket: ByteArray, val granted: Boolean, val entries: List<ChainEntry>, val reason: String = "") {
    fun encode(): ByteArray {
        val w = Writer().u8(1).fixed(ticket, 8).u8(if (granted) 1 else 0)
        if (granted) w.raw(EntryRun.encode(entries))
        return w.bytes()
    }

    companion object {
        fun parse(bytes: ByteArray): AdmitDecision {
            val r = Reader(bytes)
            if (r.u8() != 1) malformed("decision version")
            val ticket = r.fixed(8)
            val granted = when (r.u8()) { 0 -> false; 1 -> true; else -> malformed("flag") }
            val entries = if (granted) EntryRun.decode(r) else emptyList()
            r.end()
            return AdmitDecision(ticket, granted, entries)
        }
    }
}

/** A request the owner's device shows to a person: the exact key, node id, name and kind, who passed it on, and a code to compare. */
class AdmitRequest internal constructor(
    val id: String,
    val hostMemberId: String,
    val hostNodeId: String,
    val nodeId: String,
    val name: String,
    val kind: MemberKind,
    val key: ByteArray?,
    val tokenKeyId: ByteArray?,
    val createdAt: Long,
    internal val forward: AdmitForward,
) {
    /** The newcomer's key fingerprint (null for a token client). */
    val fingerprint: String? get() = key?.let { Hex.of(Crypto.fingerprint(it)) }

    /** Six digits; the newcomer's screen shows the same, and a person compares them. */
    val confirmationCode: String get() = ConfirmationCode.of(key ?: tokenKeyId!!, nodeId, name)

    /** The only way to a granting [Approval]; the screen calls it on the tap. Cancelling is not calling it. */
    fun approve(now: Long): Approval = Approval(this, now)
}

/** A person's yes for one [AdmitRequest]: single-use, and good for 120 seconds. */
class Approval internal constructor(internal val request: AdmitRequest, val at: Long) {
    @Volatile internal var used = false
}

/**
 * The owner's side of an admission (4.4, 4.5). Only this class turns a request into a chain entry, and only with an [Approval] made
 * by that very request. No approval, someone else's, an old one, a used one, or the 120 seconds gone: refused.
 */
class Owner(private val identity: DeviceIdentity, private val chain: Chain, private val ttlMs: Long = REQUEST_TTL_MS) {
    private val open = LinkedHashMap<String, AdmitRequest>()

    /** [hostMemberId] is the verified peer of the session the forward came over; it must be a current node. */
    @Synchronized fun receive(forward: AdmitForward, hostMemberId: String, now: Long): AdmitRequest {
        expire(now)
        val st = chain.state()
        val host = st.member(hostMemberId)
        if (host == null || host.kind != MemberKind.NODE) throw ProtocolError(CloudError.NOT_MEMBER)
        if (forward.keyType == 1 && forward.kind == MemberKind.BROWSER) throw ProtocolError(CloudError.MALFORMED)
        if (forward.keyType == 2 && forward.kind == MemberKind.NODE) throw ProtocolError(CloudError.MALFORMED)
        if (open.values.count { it.hostMemberId == hostMemberId } >= MAX_OPEN_PER_HOST) throw ProtocolError(CloudError.BUSY)
        val req = AdmitRequest(
            Hex.of(Crypto.random(8)), hostMemberId, host.nodeId, forward.nodeId, forward.name, forward.kind,
            forward.key, forward.tokenKeyId, now, forward,
        )
        open[req.id] = req
        return req
    }

    @Synchronized fun pending(): List<AdmitRequest> = open.values.toList()

    /** Closes requests nobody answered within the time limit; each counts as refused. */
    @Synchronized fun expire(now: Long): Int {
        val old = open.values.filter { now - it.createdAt > ttlMs }
        old.forEach { open.remove(it.id) }
        return old.size
    }

    /** Signs the add entry if, and only if, [approval] is this request's, fresh and unused. A null approval is a refusal. */
    @Synchronized fun decide(request: AdmitRequest, approval: Approval?, now: Long): AdmitDecision {
        val ticket = request.forward.ticket
        val still = open.remove(request.id)
        fun no(why: String) = AdmitDecision(ticket, false, emptyList(), why)
        if (still == null || still !== request) return no("not an open request")
        if (approval == null) return no("cancelled")
        if (approval.request !== request) return no("approval belongs to another request")
        if (approval.used) return no("approval already used")
        approval.used = true
        if (now - request.createdAt > ttlMs || now - approval.at > ttlMs || approval.at < request.createdAt) return no("too late")
        return try {
            if (request.key != null) chain.addDevice(identity, request.nodeId, request.name, request.kind, request.key, now)
            else chain.addToken(identity, request.hostMemberId, request.nodeId, request.name, request.kind, request.tokenKeyId!!, now)
            AdmitDecision(ticket, true, chain.entriesAfter(request.forward.hostHeadSeq + 1, request.forward.hostHeadId))
        } catch (e: ProtocolError) {
            no(e.message ?: "refused by the chain rules")
        }
    }

    companion object {
        const val REQUEST_TTL_MS = 120_000L
        const val MAX_OPEN_PER_HOST = 8
    }
}

/**
 * The host's side (any node). It can read a request, check the proof, bind the invite, pass the request to the owner, and answer
 * PENDING until the owner's decision arrives. It cannot write the list. [secrets] gives the current epoch's secrets this node holds.
 */
class JoinHost(
    private val identity: DeviceIdentity,
    private val nodeId: String,
    private val chain: Chain,
    private val secrets: () -> EpochSecrets?,
    val invites: InviteBook = InviteBook(),
) {
    private val cloudId = chain.cloudId

    fun invite(kind: MemberKind, addresses: List<String>, port: Int, now: Long): Invite =
        Invite(cloudId, nodeId, Crypto.fingerprint(identity.publicKey), addresses, port, invites.issue(kind, now))

    private class Parsed(val nodeId: String, val name: String, val key: ByteArray, val eN: ByteArray, val sig: ByteArray)

    private fun parseRequest(plain: ByteArray): Parsed {
        val r = Reader(plain)
        if (r.u8() != 1) malformed("version")
        val p = Parsed(
            Shape.requireNodeId(r.str(8)), Shape.requireName(r.str(Shape.MAX_NAME_BYTES)),
            r.fixed(Crypto.PUB_BYTES), r.fixed(Crypto.PUB_BYTES), r.fixed(Crypto.SIG_BYTES),
        )
        r.end()
        if (!Crypto.isValidPoint(p.key) || !Crypto.isValidPoint(p.eN)) malformed("key")
        return p
    }

    @Synchronized fun handle(request: ByteArray, now: Long): HostResult {
        val head = try {
            val r = Reader(request)
            if (r.u8() != 1) malformed("version")
            r.fixed(32) to r.rest()
        } catch (e: ProtocolError) {
            return HostResult.Refused(Refusal.MALFORMED)
        }
        val (nc, frame) = head
        // The request does not say which code it uses: try each (at most 8 live plus a few retained); only the right one opens it.
        var rec: InviteBook.Record? = null
        var plain: ByteArray? = null
        for (c in invites.known(now)) {
            plain = try {
                Frames.openOnce(JoinWire.c2aKey(c.code, cloudId, nc), 0, cloudId, FrameType.JOIN, frame)
            } catch (e: ProtocolError) {
                continue
            }
            rec = c
            break
        }
        if (rec == null || plain == null) return HostResult.Refused(Refusal.WRONG_CODE)

        val req = try { parseRequest(plain) } catch (e: ProtocolError) { return HostResult.Refused(Refusal.MALFORMED) }
        if (!Crypto.ecdsaVerify(req.key, JoinWire.pop(cloudId, nc, req.nodeId, req.name, req.key, req.eN, nodeId), req.sig)) {
            return HostResult.Refused(Refusal.BAD_PROOF)
        }
        val fp = Hex.of(Crypto.fingerprint(req.key))
        val st = chain.state()
        val isMember = st.member(fp) != null
        when {
            rec.boundTo == fp -> if (!isMember && rec.state == InviteBook.WAITING && now >= rec.issuedAt + Invite.LIFETIME_MS + PENDING_GRACE_MS) {
                return HostResult.Refused(Refusal.EXPIRED)
            }
            rec.boundTo != null -> return HostResult.Refused(Refusal.USED)
            now >= rec.issuedAt + Invite.LIFETIME_MS -> return HostResult.Refused(Refusal.EXPIRED)
        }

        var forward: AdmitForward? = null
        val sec = secrets()?.takeIf { it.epoch == st.epoch }
        val status = when {
            st.isRemoved(fp) -> JoinStatus.REMOVED // a removed key never comes back; the person reinstalls and gets a new key
            isMember && sec != null -> { rec.boundTo = fp; if (rec.state == InviteBook.GRANTED) JoinStatus.OK else JoinStatus.ALREADY_MEMBER }
            isMember -> JoinStatus.PENDING // we do not hold the current epoch's secrets yet
            st.byNodeId(req.nodeId) != null -> JoinStatus.NODE_ID_TAKEN
            rec.boundTo == fp && rec.state == InviteBook.DENIED -> {
                rec.boundTo = null; rec.state = InviteBook.NONE; rec.ticket = null // the owner said no: the invite is usable again
                JoinStatus.DECLINED
            }
            else -> {
                if (rec.state == InviteBook.NONE) {
                    rec.boundTo = fp; rec.nodeId = req.nodeId; rec.name = req.name; rec.key = req.key
                    rec.state = InviteBook.WAITING; rec.ticket = Crypto.random(8)
                    forward = AdmitForward(rec.ticket!!, 1, req.nodeId, req.name, rec.kind, req.key, null, st.headSeq, st.headId)
                }
                JoinStatus.PENDING
            }
        }
        val withMaterial = status == JoinStatus.OK || status == JoinStatus.ALREADY_MEMBER
        val secretsBytes = if (withMaterial) JoinWire.secretsBytes(sec!!) else ByteArray(0)
        val chainBytes = if (withMaterial) EntryRun.encode(chain.entries()) else ByteArray(0)

        // The answer: sealed under a key that needs the newcomer's ephemeral key and the code, signed by this host's key.
        val na = Crypto.random(32)
        val eH = Crypto.newKeyPair()
        val eHPub = Crypto.rawPublic(eH.public)
        val ecdh = Crypto.ecdh(eH.private, req.eN)
        val key = JoinWire.a2cKey(ecdh, rec.code, cloudId, nc, na)
        Crypto.wipe(ecdh)
        val sig = identity.sign(JoinWire.answerSig(cloudId, nc, na, req.eN, eHPub, status, secretsBytes, chainBytes))
        val sealed = Frames.sealOnce(key, 0, cloudId, FrameType.JOIN, concat(Writer().u8(status).bytes(), secretsBytes, chainBytes))
        Crypto.wipe(key)
        val bytes = Writer().u8(1).fixed(na, 32).fixed(eHPub, Crypto.PUB_BYTES).fixed(identity.publicKey, Crypto.PUB_BYTES)
            .fixed(sig, Crypto.SIG_BYTES).raw(sealed).bytes()
        return HostResult.Answer(bytes, status, forward)
    }

    /** The owner's decision for a waiting request, matched by its ticket. Returns false if nothing was waiting for it. */
    @Synchronized fun deliver(decision: AdmitDecision, now: Long): Boolean {
        val rec = invites.known(now).firstOrNull { it.ticket != null && it.ticket!!.contentEquals(decision.ticket) && it.state == InviteBook.WAITING } ?: return false
        if (!decision.granted) { rec.state = InviteBook.DENIED; return true }
        return when (chain.offer(decision.entries, now)) {
            is Offer.Adopted, is Offer.Unchanged -> { rec.state = InviteBook.GRANTED; true }
            else -> false // entries that do not apply: keep waiting, the caller may ask again
        }
    }

    companion object {
        const val PENDING_GRACE_MS = 300_000L
    }
}

sealed interface JoinOutcome {
    class Joined(val chain: Chain, val secrets: EpochSecrets) : JoinOutcome

    /** One of [JoinStatus.REMOVED], [JoinStatus.NODE_ID_TAKEN], [JoinStatus.DECLINED]. */
    class Rejected(val status: Int) : JoinOutcome

    /** The owner has not decided yet: ask again with the same [JoinClient.request]. */
    object Pending : JoinOutcome
}

/**
 * The newcomer's side. [request] is built once: sending the same bytes again is how it polls. Its ephemeral private key is dropped
 * as soon as the exchange ends (a final status or an error).
 */
class JoinClient(
    private val invite: Invite,
    private val identity: DeviceIdentity,
    private val nodeId: String,
    private val name: String,
) {
    private val nc = Crypto.random(32)
    private var eph: KeyPair? = Crypto.newKeyPair()
    private val ephPub = Crypto.rawPublic(eph!!.public)

    /** Six digits to compare with the owner's screen (4.4). */
    val confirmationCode: String = ConfirmationCode.of(identity.publicKey, Shape.requireNodeId(nodeId), Shape.requireName(name))

    val request: ByteArray = run {
        val key = identity.publicKey
        val sig = identity.sign(JoinWire.pop(invite.cloudId, nc, nodeId, name, key, ephPub, invite.nodeId))
        val plain = Writer().u8(1).str(nodeId).str(name).fixed(key, Crypto.PUB_BYTES).fixed(ephPub, Crypto.PUB_BYTES).fixed(sig, Crypto.SIG_BYTES).bytes()
        val frame = Frames.sealOnce(JoinWire.c2aKey(invite.code, invite.cloudId, nc), 0, invite.cloudId, FrameType.JOIN, plain)
        Writer().u8(1).fixed(nc, 32).raw(frame).bytes()
    }

    internal fun holdsEphemeralKey(): Boolean = eph != null

    /** An answer that fails before its signature checks out may be junk from someone on the path: refuse it, keep the exchange alive. */
    private fun junk(): Nothing = throw ProtocolError(CloudError.JOIN_REFUSED)

    /** A validly signed answer that is wrong (or the host being untrustworthy) ends the exchange. */
    private fun refuse(): Nothing { eph = null; throw ProtocolError(CloudError.JOIN_REFUSED) }

    /**
     * Checks the answer completely before anything is kept: it is signed by the host key pinned in the QR code over both nonces,
     * both ephemeral keys, the status and digests of what it carries; the frame opens under the ECDH-and-code key; the chain
     * verifies; the cloud id is the invitation's; the host is a current node with the pinned fingerprint; this key is a member; the
     * secrets match the chain's commitment for the current epoch.
     */
    @Synchronized fun finish(answer: ByteArray, now: Long): JoinOutcome {
        val mine = eph ?: throw ProtocolError(CloudError.JOIN_REFUSED)
        val r = Reader(answer)
        if (r.u8() != 1) junk()
        val na = r.fixed(32)
        val eH = r.fixed(Crypto.PUB_BYTES)
        val hostKey = r.fixed(Crypto.PUB_BYTES)
        val sig = r.fixed(Crypto.SIG_BYTES)
        val frame = r.rest()
        if (!Crypto.isValidPoint(eH) || !Crypto.isValidPoint(hostKey) || !Crypto.equal(Crypto.fingerprint(hostKey), invite.fingerprint)) junk()
        val ecdh = Crypto.ecdh(mine.private, eH)
        val key = JoinWire.a2cKey(ecdh, invite.code, invite.cloudId, nc, na)
        Crypto.wipe(ecdh)
        val plain = try {
            Frames.openOnce(key, 0, invite.cloudId, FrameType.JOIN, frame)
        } finally {
            Crypto.wipe(key)
        }
        val p = Reader(plain)
        val status = p.u8()
        var secrets: EpochSecrets? = null
        var entries: List<ChainEntry> = emptyList()
        when (status) {
            JoinStatus.OK, JoinStatus.ALREADY_MEMBER -> {
                secrets = EpochSecrets(p.u32(), p.fixed(32), p.fixed(32))
                entries = EntryRun.decode(p)
            }
            JoinStatus.REMOVED, JoinStatus.NODE_ID_TAKEN, JoinStatus.DECLINED, JoinStatus.PENDING -> {}
            else -> junk()
        }
        p.end()
        val secretsBytes = secrets?.let { JoinWire.secretsBytes(it) } ?: ByteArray(0)
        val chainBytes = if (entries.isEmpty()) ByteArray(0) else EntryRun.encode(entries)
        if (!Crypto.ecdsaVerify(hostKey, JoinWire.answerSig(invite.cloudId, nc, na, ephPub, eH, status, secretsBytes, chainBytes), sig)) junk()
        when (status) {
            JoinStatus.PENDING -> return JoinOutcome.Pending
            JoinStatus.REMOVED, JoinStatus.NODE_ID_TAKEN, JoinStatus.DECLINED -> { eph = null; return JoinOutcome.Rejected(status) }
        }
        val chain = try {
            Chain.fromEntries(entries, now, invite.cloudId)
        } catch (e: ProtocolError) {
            refuse()
        }
        val st = chain.state()
        val host = st.member(Hex.of(invite.fingerprint))
        if (!chain.cloudId.contentEquals(invite.cloudId) || host == null || host.nodeId != invite.nodeId || host.kind != MemberKind.NODE) refuse()
        if (st.member(identity.memberId()) == null) refuse()
        val s = secrets!!
        val want = st.commitment(s.epoch)
        if (s.epoch != st.epoch || want == null || !Crypto.equal(want, s.commitment())) refuse()
        eph = null
        return JoinOutcome.Joined(chain, s)
    }
}
