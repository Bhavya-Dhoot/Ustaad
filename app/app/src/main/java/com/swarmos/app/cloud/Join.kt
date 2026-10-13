package com.swarmos.app.cloud

/**
 * Joining a cloud (docs/CLOUD-PROTOCOL.md section 4). The admitter shows an [Invite]; the newcomer scans it. The one-time
 * code is a secret shared by camera and is the input key material of both messages, so each message is authenticated
 * (and kept secret) by AES-GCM under a key derived from it.
 */
class Invite(
    val cloudId: ByteArray,
    val nodeId: String,
    /** `fp(pub)` of the admitter's key. */
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

/** What the admitter keeps per invitation, in memory. */
class InviteBook {
    internal class Record(val code: ByteArray, val kind: MemberKind, val issuedAt: Long) {
        /** Member id of the key that used the code. */
        var usedBy: String? = null
    }

    private val records = ArrayList<Record>()

    /** A new code for a node or a client to be admitted. At most 8 are open at once. */
    @Synchronized fun issue(kind: MemberKind, now: Long): ByteArray {
        require(kind != MemberKind.BROWSER) { "a browser joins with a token, not an invitation" }
        prune(now)
        check(records.count { it.usedBy == null && now < it.issuedAt + Invite.LIFETIME_MS } < MAX_OPEN) { "too many open invitations" }
        val r = Record(Crypto.random(16), kind, now)
        records.add(r)
        return r.code.copyOf()
    }

    /** Records still known, including used and recently expired ones: they let the UI say why a join failed. */
    @Synchronized internal fun known(now: Long): List<Record> { prune(now); return records.toList() }

    private fun prune(now: Long) { records.removeAll { now > it.issuedAt + Invite.LIFETIME_MS + RETAIN_MS } }

    companion object {
        const val MAX_OPEN = 8
        const val RETAIN_MS = 3_600_000L
    }
}

/** Status byte of a sealed join answer. */
object JoinStatus {
    const val OK = 0
    const val ALREADY_MEMBER = 1
    const val REMOVED = 2
    const val NODE_ID_TAKEN = 3
    const val DECLINED = 4
}

/** Why the admitter refused without a sealed answer. On the wire every one of these is the same 403 `join_refused`. */
enum class Refusal { MALFORMED, WRONG_CODE, BAD_PROOF, EXPIRED, USED }

sealed interface AdmitResult {
    class Answer(val bytes: ByteArray, val status: Int) : AdmitResult
    class Refused(val reason: Refusal) : AdmitResult
}

internal object JoinWire {
    /** Newcomer to admitter. `nc` is fresh per request. */
    fun c2aKey(code: ByteArray, cloudId: ByteArray, nc: ByteArray): ByteArray =
        Crypto.hkdf(code, concat(cloudId, nc), lbl("join/c2a"), 32)

    /** Admitter to newcomer. `na` is fresh per answer, so a changed answer never reuses a key and nonce. */
    fun a2cKey(code: ByteArray, cloudId: ByteArray, nc: ByteArray, na: ByteArray): ByteArray =
        Crypto.hkdf(code, concat(cloudId, nc, na), lbl("join/a2c"), 32)

    /** What the newcomer signs to show it holds the private key of the key it offers. */
    fun pop(cloudId: ByteArray, nc: ByteArray, nodeId: String, name: String, pub: ByteArray, admitterNodeId: String): ByteArray =
        concat(lbl("join/pop"), cloudId, nc, Writer().str(nodeId).str(name).fixed(pub, Crypto.PUB_BYTES).str(admitterNodeId).bytes())
}

/**
 * The admitting side. [approve] is where the person confirms on the admitting device (name, node id, first characters of
 * the fingerprint): without it whoever photographs the QR code first would get in. The add entry is signed here and
 * reaches the other members by the normal merge.
 */
class Admitter(
    private val identity: DeviceIdentity,
    private val nodeId: String,
    private val log: MemberLog,
    private val discoveryKey: ByteArray,
    val invites: InviteBook,
    private val approve: (nodeId: String, name: String, fingerprint: String) -> Boolean,
) {
    private val cloudId = log.cloudId

    fun invite(kind: MemberKind, addresses: List<String>, port: Int, now: Long): Invite =
        Invite(cloudId, nodeId, Crypto.fingerprint(identity.publicKey), addresses, port, invites.issue(kind, now))

    private class Parsed(val nodeId: String, val name: String, val pub: ByteArray, val sig: ByteArray)

    private fun parseRequest(plain: ByteArray): Parsed {
        val r = Reader(plain)
        if (r.u8() != 1) malformed("version")
        val p = Parsed(Shape.requireNodeId(r.str(8)), Shape.requireName(r.str(Shape.MAX_NAME_BYTES)), r.fixed(Crypto.PUB_BYTES), r.fixed(Crypto.SIG_BYTES))
        r.end()
        return p
    }

    @Synchronized fun handle(request: ByteArray, now: Long): AdmitResult {
        val head = try {
            val r = Reader(request)
            if (r.u8() != 1) malformed("version")
            r.fixed(32) to r.rest()
        } catch (e: ProtocolError) {
            return AdmitResult.Refused(Refusal.MALFORMED)
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
        if (rec == null || plain == null) return AdmitResult.Refused(Refusal.WRONG_CODE)

        val req = try { parseRequest(plain) } catch (e: ProtocolError) { return AdmitResult.Refused(Refusal.MALFORMED) }
        // proof that the newcomer holds the private key of the key it offers (and that this request is for this admitter)
        if (!Crypto.ecdsaVerify(req.pub, JoinWire.pop(cloudId, nc, req.nodeId, req.name, req.pub, nodeId), req.sig)) {
            return AdmitResult.Refused(Refusal.BAD_PROOF)
        }
        val fp = Hex.of(Crypto.fingerprint(req.pub))
        when {
            rec.usedBy == fp -> {} // the same key again (a lost answer): answered whatever the age
            rec.usedBy != null -> return AdmitResult.Refused(Refusal.USED)
            now >= rec.issuedAt + Invite.LIFETIME_MS -> return AdmitResult.Refused(Refusal.EXPIRED)
        }

        val st = log.state()
        val status = when {
            fp in st.removed -> JoinStatus.REMOVED // a removed key never comes back; the person reinstalls and gets a new key
            st.member(fp) != null -> { rec.usedBy = fp; JoinStatus.ALREADY_MEMBER }
            st.members.any { it.nodeId == req.nodeId } -> JoinStatus.NODE_ID_TAKEN
            !approve(req.nodeId, req.name, fp) -> JoinStatus.DECLINED
            else -> {
                log.addDevice(identity, req.nodeId, req.name, rec.kind, req.pub, now)
                rec.usedBy = fp
                JoinStatus.OK
            }
        }
        val w = Writer().u8(status)
        if (status == JoinStatus.OK || status == JoinStatus.ALREADY_MEMBER) w.fixed(discoveryKey, 32).raw(EntryList.encode(log.entries()))
        val na = Crypto.random(32)
        val sealed = Frames.sealOnce(JoinWire.a2cKey(rec.code, cloudId, nc, na), 0, cloudId, FrameType.JOIN, w.bytes())
        return AdmitResult.Answer(Writer().u8(1).fixed(na, 32).raw(sealed).bytes(), status)
    }
}

sealed interface JoinOutcome {
    class Joined(val log: MemberLog, val discoveryKey: ByteArray) : JoinOutcome
    /** One of [JoinStatus.REMOVED], [JoinStatus.NODE_ID_TAKEN], [JoinStatus.DECLINED]. */
    class Rejected(val status: Int) : JoinOutcome
}

/** The newcomer's side. [request] is built once: sending the same bytes again is safe, building a second request with the same `nc` is not. */
class JoinClient(
    private val invite: Invite,
    private val identity: DeviceIdentity,
    private val nodeId: String,
    private val name: String,
) {
    private val nc = Crypto.random(32)

    val request: ByteArray = run {
        val pub = identity.publicKey
        val sig = identity.sign(JoinWire.pop(invite.cloudId, nc, Shape.requireNodeId(nodeId), Shape.requireName(name), pub, invite.nodeId))
        val plain = Writer().u8(1).str(nodeId).str(name).fixed(pub, Crypto.PUB_BYTES).fixed(sig, Crypto.SIG_BYTES).bytes()
        val frame = Frames.sealOnce(JoinWire.c2aKey(invite.code, invite.cloudId, nc), 0, invite.cloudId, FrameType.JOIN, plain)
        Writer().u8(1).fixed(nc, 32).raw(frame).bytes()
    }

    /**
     * Checks the answer completely before anything is kept: the frame opens under the code, the list verifies from its
     * genesis, the cloud id is the invitation's, the admitter is the node the QR code named, and this key is a member.
     */
    fun finish(answer: ByteArray): JoinOutcome {
        val r = Reader(answer)
        if (r.u8() != 1) malformed("version")
        val na = r.fixed(32)
        val plain = Frames.openOnce(JoinWire.a2cKey(invite.code, invite.cloudId, nc, na), 0, invite.cloudId, FrameType.JOIN, r.rest())
        val p = Reader(plain)
        val status = p.u8()
        when (status) {
            JoinStatus.OK, JoinStatus.ALREADY_MEMBER -> {}
            JoinStatus.REMOVED, JoinStatus.NODE_ID_TAKEN, JoinStatus.DECLINED -> { p.end(); return JoinOutcome.Rejected(status) }
            else -> malformed("status")
        }
        val discoveryKey = p.fixed(32)
        val entries = EntryList.decode(p)
        p.end()
        val log = MemberLog.fromEntries(entries)
        if (!log.cloudId.contentEquals(invite.cloudId)) throw ProtocolError(CloudError.JOIN_REFUSED)
        val st = log.state()
        val admitter = st.member(Hex.of(invite.fingerprint))
        if (admitter == null || admitter.nodeId != invite.nodeId || admitter.kind != MemberKind.NODE) throw ProtocolError(CloudError.JOIN_REFUSED)
        if (st.member(identity.memberId()) == null) throw ProtocolError(CloudError.JOIN_REFUSED)
        return JoinOutcome.Joined(log, discoveryKey)
    }
}
