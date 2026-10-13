package com.swarmos.app.cloud

import java.security.KeyPair

/**
 * Joining a cloud (docs/CLOUD-PROTOCOL.md section 4). Any node may host an [Invite]; the newcomer scans it. The one-time code is a
 * secret shared by camera. The request is sealed under a key from the code; the answer is sealed under a key that also needs an
 * ephemeral ECDH result and is signed by the host's key pinned in the QR code, so a code learnt later opens nothing and a holder of
 * the code cannot forge an answer. A newcomer is a member only when the OWNER has signed its add entry after a person compared six
 * digits on both screens and tapped Allow on the owner's device (4.4, 4.5). The digits are a committed comparison: the newcomer
 * commits first, the owner answers with a nonce, the newcomer opens; a host in the middle cannot steer them.
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
    /** A requester the owner declined: it keeps its own answer while the invite goes back to the next newcomer (4.3). */
    internal class Declined(val nc: ByteArray, val eN: ByteArray, val fp: String)

    internal class Record(val code: ByteArray, val kind: MemberKind, val issuedAt: Long) {
        /** Member id of the key the invite is bound to (the first valid request). */
        var boundTo: String? = null
        var nodeId = ""
        var name = ""
        var key: ByteArray? = null
        var eN: ByteArray? = null
        var nc: ByteArray? = null
        var commit: ByteArray? = null
        var nO: ByteArray? = null
        var openForwarded = false
        var state = NONE
        var ticket: ByteArray? = null
        val declined = ArrayList<Declined>()

        /** A failed or missing opening burns the invite: nobody can use it again (Codex Join.kt:357). */
        var burnt = false

        fun release() {
            boundTo = null; key = null; eN = null; nc = null; commit = null; nO = null; ticket = null
            openForwarded = false; state = NONE
        }
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
        const val MAX_DECLINED = 4
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
    /**
     * [forward] is set the first time a request is passed on, [open] the first time the newcomer's opening arrives: send them to the
     * owner over the session (or hand them to a local [Owner]).
     */
    class Answer(val bytes: ByteArray, val status: Int, val forward: AdmitForward?, val open: SasOpen? = null) : HostResult
    class Refused(val reason: Refusal) : HostResult
}

internal object JoinWire {
    /** Newcomer to host, phase 0. `nc` is fresh per request. The request is sealed under the code alone: it carries nothing secret. */
    fun c2aKey(code: ByteArray, cloudId: ByteArray, nc: ByteArray): ByteArray = Crypto.hkdf(code, concat(cloudId, nc), lbl("join/c2a"), 32)

    /** Newcomer to host, phase 1: a fresh key, so no key and nonce ever carry two plaintexts. */
    fun c2a2Key(code: ByteArray, cloudId: ByteArray, nc: ByteArray, nO: ByteArray): ByteArray =
        Crypto.hkdf(code, concat(cloudId, nc, nO), lbl("join/c2a2"), 32)

    /** Host to newcomer: the ECDH result AND the code. Neither a late learner of the code nor a holder of the code on the path can make it. */
    fun a2cKey(ecdh: ByteArray, code: ByteArray, cloudId: ByteArray, nc: ByteArray, na: ByteArray): ByteArray =
        Crypto.hkdf(concat(ecdh, code), concat(cloudId, nc, na), lbl("join/a2c"), 32)

    /** The newcomer commits to its key, node id, name and a nonce it keeps secret until the owner's nonce has arrived. */
    fun commit(cloudId: ByteArray, key: ByteArray, nodeId: String, name: String, nN: ByteArray): ByteArray =
        Crypto.sha256(lbl("join/commit"), cloudId, Writer().fixed(key, Crypto.PUB_BYTES).str(nodeId).str(name).fixed(nN, 16).bytes())

    /** Possession of the key, bound to this ephemeral key, this exchange, this commitment and this host. */
    fun pop(cloudId: ByteArray, nc: ByteArray, nodeId: String, name: String, key: ByteArray, eN: ByteArray, commit: ByteArray, hostNodeId: String): ByteArray =
        concat(lbl("join/pop"), cloudId, nc, Writer().str(nodeId).str(name).fixed(key, Crypto.PUB_BYTES).fixed(eN, Crypto.PUB_BYTES).fixed(commit, 32).str(hostNodeId).bytes())

    /** Entries in one frame of the chain part of an answer: far below the frame limit (1024 entries of at most 1.1 KB each is 1.1 MB). */
    const val PAGE = 1024

    /** `u8 1 || na || pub eH || pub hostKey || sig`: what comes before the frames of an answer. */
    const val HEAD_BYTES = 1 + 32 + Crypto.PUB_BYTES + Crypto.PUB_BYTES + Crypto.SIG_BYTES

    /** The hard caps of reception (docs 4.2, Codex Join.kt:770): the first frame, a page frame, all frames together, and the time. */
    const val MANIFEST_FRAME_MAX = 1024
    val PAGE_FRAME_MAX = FrameCodec.HEADER + FrameCodec.TAG + 4 + PAGE * EntryRun.MAX_ENTRY_BYTES
    val ANSWER_BYTES_MAX = HEAD_BYTES.toLong() + ChainEntry.MAX_ENTRIES.toLong() * EntryRun.MAX_ENTRY_BYTES + 1_048_576L
    const val ANSWER_LIMIT_MS = 120_000L

    /** What the host signs: both nonces, both ephemeral keys, the status, a digest of the secrets and the number and digest of the chain entries. */
    fun answerSig(cloudId: ByteArray, nc: ByteArray, na: ByteArray, eN: ByteArray, eH: ByteArray, status: Int, extra: ByteArray, chainCount: Int, chainDigest: ByteArray): ByteArray =
        concat(lbl("join/answer"), cloudId, nc, na, eN, eH, Writer().u8(status).bytes(), Crypto.sha256(extra), Writer().u32(chainCount).bytes(), chainDigest)

    /** SHA-256 over `lp(entry)` of every entry in order, however the entries are split into frames. */
    class ChainDigest {
        private val md = java.security.MessageDigest.getInstance("SHA-256")
        fun update(e: ChainEntry) { md.update(Writer().lp(e.encode()).bytes()) }
        fun done(): ByteArray = md.digest()
    }

    fun chainDigest(entries: List<ChainEntry>): ByteArray = ChainDigest().also { d -> entries.forEach { d.update(it) } }.done()

    /** One page of entries: `u32 n || lp(entry) * n`. */
    fun pageBytes(page: List<ChainEntry>): ByteArray = Writer().u32(page.size).also { w -> page.forEach { w.lp(it.encode()) } }.bytes()

    fun secretsBytes(s: EpochSecrets): ByteArray = Writer().u32(s.epoch).fixed(s.discoveryKey, 32).fixed(s.storageKey, 32).bytes()
}

/**
 * The six digits both screens show (4.4): computed from the cloud, the newcomer's key, node id and name, the newcomer's secret
 * nonce and the owner's nonce. Both nonces are fixed only after a party has committed, so nobody can search for a match.
 */
object ConfirmationCode {
    fun of(cloudId: ByteArray, keyOrKeyId: ByteArray, nodeId: String, name: String, nN: ByteArray, nO: ByteArray): String {
        val h = Crypto.sha256(lbl("join/sas"), cloudId, keyOrKeyId, Writer().str(nodeId).str(name).fixed(nN, 16).fixed(nO, 16).bytes())
        val v = ((h[0].toLong() and 0xFF) shl 24) or ((h[1].toLong() and 0xFF) shl 16) or ((h[2].toLong() and 0xFF) shl 8) or (h[3].toLong() and 0xFF)
        return (v % 1_000_000L).toString().padStart(6, '0')
    }
}

/** Host to owner (4.5), sealed in their session. `ticket` ties the owner's messages back to the waiting request. */
class AdmitForward(
    val ticket: ByteArray,
    val keyType: Int,
    val nodeId: String,
    val name: String,
    val kind: MemberKind,
    val key: ByteArray?,
    val tokenKeyId: ByteArray?,
    /** The newcomer's commitment (device keys only). */
    val commit: ByteArray?,
    val hostHeadSeq: Long,
    val hostHeadId: ByteArray,
) {
    fun encode(): ByteArray {
        val w = Writer().u8(1).fixed(ticket, 8).u8(keyType).str(nodeId).str(name).u8(kind.code)
        if (keyType == 1) w.fixed(key!!, Crypto.PUB_BYTES).fixed(commit!!, 32) else w.fixed(tokenKeyId!!, 16)
        return w.u64(hostHeadSeq).fixed(hostHeadId, 32).bytes()
    }

    companion object {
        fun parse(bytes: ByteArray): AdmitForward {
            val r = Reader(bytes)
            if (r.u8() != 1) malformed("admit version")
            val ticket = r.fixed(8)
            val keyType = r.u8()
            if (keyType !in 1..2) malformed("key type")
            val nodeId = Shape.requireNodeId(r.str(8))
            val name = Shape.requireName(r.str(Shape.MAX_NAME_BYTES))
            val kind = MemberKind.of(r.u8()) ?: malformed("kind")
            var key: ByteArray? = null; var commit: ByteArray? = null; var keyId: ByteArray? = null
            if (keyType == 1) {
                key = r.fixed(Crypto.PUB_BYTES).also { if (!Crypto.isValidPoint(it)) malformed("key") }
                commit = r.fixed(32)
            } else {
                keyId = r.fixed(16)
            }
            val seq = r.u64()
            val head = r.fixed(32)
            r.end()
            if (seq < 0) malformed("head")
            return AdmitForward(ticket, keyType, nodeId, name, kind, key, keyId, commit, seq, head)
        }

        /** A token client recorded by its issuing node (section 8.1). */
        fun forToken(m: TokenMaterial, name: String, kind: MemberKind, chain: Chain): AdmitForward {
            val s = chain.state()
            return AdmitForward(Crypto.random(8), 2, m.nodeId, name, kind, null, m.keyId, null, s.headSeq, s.headId)
        }
    }
}

/** Owner to host, sealed: the owner's fresh nonce, sent only after it holds the newcomer's commitment (4.4). */
class SasChallenge(val ticket: ByteArray, val nO: ByteArray) {
    fun encode(): ByteArray = Writer().u8(1).fixed(ticket, 8).fixed(nO, 16).bytes()

    companion object {
        fun parse(bytes: ByteArray): SasChallenge {
            val r = Reader(bytes)
            if (r.u8() != 1) malformed("challenge version")
            val c = SasChallenge(r.fixed(8), r.fixed(16))
            r.end()
            return c
        }
    }
}

/** Host to owner, sealed: the newcomer's opening of its commitment, sent after the newcomer has seen the owner's nonce. */
class SasOpen(val ticket: ByteArray, val nN: ByteArray) {
    fun encode(): ByteArray = Writer().u8(1).fixed(ticket, 8).fixed(nN, 16).bytes()

    companion object {
        fun parse(bytes: ByteArray): SasOpen {
            val r = Reader(bytes)
            if (r.u8() != 1) malformed("opening version")
            val o = SasOpen(r.fixed(8), r.fixed(16))
            r.end()
            return o
        }
    }
}

/**
 * Owner to host (4.5), sealed: granted with the entries the host lacks, or denied. A denial is either the person's (`failed` false:
 * the invite goes back to the next newcomer) or a failure of the exchange (`failed` true: the opening did not match its commitment,
 * or none came in time; the invite is burnt, Codex Join.kt:357).
 */
class AdmitDecision(val ticket: ByteArray, val granted: Boolean, val entries: List<ChainEntry>, val reason: String = "", val failed: Boolean = false) {
    init { require(!(granted && failed)) { "a failure is not a grant" } }

    fun encode(): ByteArray {
        val w = Writer().u8(1).fixed(ticket, 8).u8(if (granted) 1 else if (failed) 2 else 0)
        if (granted) w.raw(EntryRun.encode(entries))
        return w.bytes()
    }

    companion object {
        fun parse(bytes: ByteArray): AdmitDecision {
            val r = Reader(bytes)
            if (r.u8() != 1) malformed("decision version")
            val ticket = r.fixed(8)
            val code = r.u8()
            if (code !in 0..2) malformed("flag")
            val entries = if (code == 1) EntryRun.decode(r) else emptyList()
            r.end()
            return AdmitDecision(ticket, code == 1, entries, failed = code == 2)
        }
    }
}

/** A request at the owner: the exact key, node id, name and kind, who passed it on, and (once the opening has been checked) six digits. */
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
    internal val cloudId: ByteArray,
    internal val nO: ByteArray?,
) {
    /** The owner's nonce to send back to the host. Null for a token client (no newcomer device, no digits). */
    val challenge: SasChallenge? get() = nO?.let { SasChallenge(forward.ticket, it) }

    /** Six digits, known only after the newcomer's opening was checked against its commitment. */
    @Volatile var digits: String? = null
        internal set

    /** The newcomer's key fingerprint (null for a token client). */
    val fingerprint: String? get() = key?.let { Hex.of(Crypto.fingerprint(it)) }

    internal val opened: Boolean get() = tokenKeyId != null || digits != null
    @Volatile internal var prompt: Prompt? = null
}

/**
 * What the owner's screen is given to draw: the host, node id, name, kind and the six digits. The only way to a granting [Approval]
 * is [approve], and it is meant to be called from the tap on Allow and from nowhere else (docs section 19).
 */
class Prompt internal constructor(internal val request: AdmitRequest, val shownAt: Long) {
    val hostNodeId: String get() = request.hostNodeId
    val nodeId: String get() = request.nodeId
    val name: String get() = request.name
    val kind: MemberKind get() = request.kind
    val digits: String? get() = request.digits

    fun approve(now: Long): Approval = Approval(this, now)
}

/** A person's yes for one [Prompt]: single-use, at least 800 ms after the prompt was shown and at most 120 seconds. */
class Approval internal constructor(internal val prompt: Prompt, val at: Long) {
    @Volatile internal var used = false
}

/** A join attempt through [hostNodeId] that failed: the opening did not match, or none came (4.4). The owner's screen says "show a new code". */
class JoinFailure(val hostMemberId: String, val hostNodeId: String, val at: Long, val reason: String)

/**
 * The owner's side of an admission (4.4, 4.5). Only this class turns a request into a chain entry, and only with an [Approval]
 * made by the [Prompt] of that very request. No approval, someone else's, one made too fast or too late, a used one, a request whose
 * commitment was not opened, or the 120 seconds gone: refused.
 */
class Owner(
    private val identity: DeviceIdentity,
    private val chain: Chain,
    private val ttlMs: Long = REQUEST_TTL_MS,
    private val minDwellMs: Long = MIN_DWELL_MS,
) {
    private val open = LinkedHashMap<String, AdmitRequest>()
    private val failed = LinkedHashMap<String, JoinFailure>()
    private val outbox = ArrayList<AdmitDecision>()

    /** Attempts that failed and that nobody has acknowledged: while a host is in this list the owner takes no new request from it. */
    @Synchronized fun failures(): List<JoinFailure> = failed.values.toList()

    /** A person has read "a join attempt failed, show a new code" on the owner's screen: the host may pass on requests again. */
    @Synchronized fun acknowledge(hostMemberId: String) { failed.remove(hostMemberId) }

    private fun fail(r: AdmitRequest, now: Long, why: String) {
        failed[r.hostMemberId] = JoinFailure(r.hostMemberId, r.hostNodeId, now, why)
    }

    /**
     * [hostMemberId] is the verified peer of the session the forward came over; it must be a current node. At most one request per
     * host is open at a time, so a host cannot try several commitments in parallel against one set of digits. **A failed or missing
     * opening is a trial that has been spent** (Codex Join.kt:357): until a person acknowledges it ([acknowledge]) the host gets
     * `busy`, so a host that learnt the newcomer's digits cannot go on drawing new owner nonces until one fits.
     */
    @Synchronized fun receive(forward: AdmitForward, hostMemberId: String, now: Long): AdmitRequest {
        sweep(now)
        if (hostMemberId in failed) throw ProtocolError(CloudError.BUSY)
        val st = chain.state()
        val host = st.member(hostMemberId)
        if (host == null || host.kind != MemberKind.NODE) throw ProtocolError(CloudError.NOT_MEMBER)
        if (forward.keyType == 1 && forward.kind == MemberKind.BROWSER) throw ProtocolError(CloudError.MALFORMED)
        if (forward.keyType == 2 && forward.kind == MemberKind.NODE) throw ProtocolError(CloudError.MALFORMED)
        if (open.values.count { it.hostMemberId == hostMemberId } >= MAX_OPEN_PER_HOST) throw ProtocolError(CloudError.BUSY)
        val req = AdmitRequest(
            Hex.of(Crypto.random(8)), hostMemberId, host.nodeId, forward.nodeId, forward.name, forward.kind,
            forward.key, forward.tokenKeyId, now, forward, chain.cloudId, if (forward.keyType == 1) Crypto.random(16) else null,
        )
        open[req.id] = req
        return req
    }

    @Synchronized fun pending(): List<AdmitRequest> = open.values.toList()

    /**
     * The newcomer's opening arrived: check it against the commitment it made before the owner's nonce existed. A wrong opening
     * closes the request, spends the host's trial (see [receive]) and returns a failure for the host, which burns the invite; a right
     * one makes the digits. The same right opening again changes nothing (Codex Join.kt:514).
     */
    @Synchronized fun opened(request: AdmitRequest, opening: SasOpen, now: Long): AdmitDecision? {
        sweep(now)
        if (open[request.id] !== request) {
            // already closed: a device request that never was opened was closed as a failure, and the host is told the same again
            return AdmitDecision(request.forward.ticket, false, emptyList(), "not an open request", failed = request.key != null && request.digits == null)
        }
        val ok = request.key != null && request.nO != null && opening.ticket.contentEquals(request.forward.ticket) &&
            Crypto.equal(JoinWire.commit(request.cloudId, request.key, request.nodeId, request.name, opening.nN), request.forward.commit!!)
        if (!ok) {
            open.remove(request.id)
            fail(request, now, "the opening does not match the commitment")
            return AdmitDecision(request.forward.ticket, false, emptyList(), "the opening does not match the commitment", failed = true)
        }
        if (request.digits == null) request.digits = ConfirmationCode.of(request.cloudId, request.key, request.nodeId, request.name, opening.nN, request.nO)
        return null
    }

    /** What the screen draws. Only for a request that is still open and whose commitment has been opened (a token client needs none). */
    @Synchronized fun prompt(request: AdmitRequest, now: Long): Prompt {
        check(open[request.id] === request) { "not an open request" }
        check(request.opened) { "the newcomer has not opened its commitment yet: no digits to show" }
        return request.prompt ?: Prompt(request, now).also { request.prompt = it }
    }

    /**
     * Closes requests nobody answered within the time limit; each counts as refused. A device request whose opening never came is a
     * spent trial like a wrong opening (Codex Join.kt:357). Returns the decisions to pass to the hosts: a failure (code 2) burns the
     * invite; a request that was opened or was a token request but got no tap is a code-0 denial, so that the host answers DECLINED
     * and releases the invite (Codex Join.kt:432).
     */
    @Synchronized fun expire(now: Long): List<AdmitDecision> {
        sweep(now)
        return outbox.toList().also { outbox.clear() }
    }

    /** The same sweep, run inside the other calls too; the failures it finds wait in the outbox until [expire] hands them over. */
    private fun sweep(now: Long) {
        val old = open.values.filter { now - it.createdAt > ttlMs }
        for (r in old) {
            open.remove(r.id)
            if (r.key != null && r.digits == null) {
                fail(r, now, "no opening arrived")
                outbox.add(AdmitDecision(r.forward.ticket, false, emptyList(), "no opening arrived", failed = true))
            } else {
                outbox.add(AdmitDecision(r.forward.ticket, false, emptyList(), "no tap in time"))
            }
        }
    }

    /**
     * Signs the add entry if, and only if, [approval] is this request's prompt's, in time and unused. A null approval is a refusal.
     * **Closing a device request whose opening has not been checked, by whatever route (a timeout handler, a cancel, a made-up
     * approval), is a spent trial** and not a silent removal (Codex Join.kt:444): there is no prompt to cancel before the digits exist,
     * so this can only be the host's trial being thrown away.
     */
    @Synchronized fun decide(request: AdmitRequest, approval: Approval?, now: Long): AdmitDecision {
        val ticket = request.forward.ticket
        val still = open.remove(request.id)
        fun no(why: String) = AdmitDecision(ticket, false, emptyList(), why)
        if (still == null || still !== request) return no("not an open request")
        if (request.key != null && request.digits == null) {
            fail(request, now, "closed before its opening arrived")
            return AdmitDecision(ticket, false, emptyList(), "closed before its opening arrived", failed = true)
        }
        if (approval == null) return no("cancelled")
        val prompt = request.prompt ?: return no("nothing was shown to a person")
        if (approval.prompt !== prompt) return no("approval belongs to another prompt")
        if (approval.used) return no("approval already used")
        approval.used = true
        if (approval.at - prompt.shownAt < minDwellMs) return no("approved faster than a person can read")
        if (now - request.createdAt > ttlMs || approval.at - prompt.shownAt > ttlMs || now - approval.at > ttlMs) return no("too late")
        return try {
            if (request.key != null) chain.addDevice(identity, request.nodeId, request.name, request.kind, request.key, now)
            else chain.addToken(identity, request.hostMemberId, request.nodeId, request.name, request.kind, request.tokenKeyId!!, now)
            AdmitDecision(ticket, true, chain.entriesFor(ChainPull(request.forward.hostHeadSeq + 1, request.forward.hostHeadId, 0L)))
        } catch (e: ProtocolError) {
            no(e.message ?: "refused by the chain rules")
        }
    }

    companion object {
        const val REQUEST_TTL_MS = 120_000L
        const val MIN_DWELL_MS = 800L
        const val MAX_OPEN_PER_HOST = 1
    }
}

/**
 * The host's side (any node). It can read a request, check the proof, bind the invite, pass the request and the opening to the
 * owner, and answer PENDING until the owner's decision arrives. It cannot write the list. [secrets] gives the current epoch's
 * secrets this node holds.
 */
class JoinHost(
    private val identity: DeviceIdentity,
    private val nodeId: String,
    private val chain: Chain,
    private val secrets: () -> EpochSecrets?,
    val invites: InviteBook = InviteBook(),
    /** Entries per frame of the chain part of an answer (a test makes it small). */
    private val joinPage: Int = JoinWire.PAGE,
) {
    init { require(joinPage in 1..JoinWire.PAGE) { "page size" } }

    private val cloudId = chain.cloudId

    fun invite(kind: MemberKind, addresses: List<String>, port: Int, now: Long): Invite =
        Invite(cloudId, nodeId, Crypto.fingerprint(identity.publicKey), addresses, port, invites.issue(kind, now))

    private class Parsed(val nodeId: String, val name: String, val key: ByteArray, val eN: ByteArray, val commit: ByteArray, val sig: ByteArray)

    private fun parseRequest(plain: ByteArray): Parsed {
        val r = Reader(plain)
        if (r.u8() != 1) malformed("version")
        val p = Parsed(
            Shape.requireNodeId(r.str(8)), Shape.requireName(r.str(Shape.MAX_NAME_BYTES)),
            r.fixed(Crypto.PUB_BYTES), r.fixed(Crypto.PUB_BYTES), r.fixed(32), r.fixed(Crypto.SIG_BYTES),
        )
        r.end()
        if (!Crypto.isValidPoint(p.key) || !Crypto.isValidPoint(p.eN)) malformed("key")
        return p
    }

    /** Everything the answer needs, whichever phase the request was. */
    private class Ctx(val rec: InviteBook.Record?, val nc: ByteArray, val eN: ByteArray, val fp: String, val nodeId: String, val name: String, val key: ByteArray, val commit: ByteArray)

    private class Head(val ver: Int, val nc: ByteArray, val nO: ByteArray?, val frame: ByteArray)

    @Synchronized fun handle(request: ByteArray, now: Long): HostResult {
        val h = try {
            val r = Reader(request)
            val ver = r.u8()
            if (ver != 1 && ver != 2) malformed("version")
            val nc = r.fixed(32)
            val nO = if (ver == 2) r.fixed(16) else null
            Head(ver, nc, nO, r.rest())
        } catch (e: ProtocolError) {
            return HostResult.Refused(Refusal.MALFORMED)
        }
        return if (h.ver == 1) phase0(h.nc, h.frame, now) else phase1(h.nc, h.nO!!, h.frame, now)
    }

    private fun phase0(nc: ByteArray, frame: ByteArray, now: Long): HostResult {
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
        if (!Crypto.ecdsaVerify(req.key, JoinWire.pop(cloudId, nc, req.nodeId, req.name, req.key, req.eN, req.commit, nodeId), req.sig)) {
            return HostResult.Refused(Refusal.BAD_PROOF)
        }
        val fp = Hex.of(Crypto.fingerprint(req.key))
        val st = chain.state()
        val isMember = st.member(fp) != null
        val declined = rec.declined.any { it.fp == fp }
        when {
            rec.burnt -> return HostResult.Refused(Refusal.USED) // a failed or missing opening burns the invite for everyone (Codex Join.kt:357)
            declined -> {}
            rec.boundTo == fp -> {
                // the invite is bound to the first commitment it received: the same key with another one is not the exchange in progress
                if (rec.commit != null && !Crypto.equal(rec.commit!!, req.commit)) return HostResult.Refused(Refusal.USED)
                if (!isMember && rec.state == InviteBook.WAITING && now >= rec.issuedAt + Invite.LIFETIME_MS + PENDING_GRACE_MS) {
                    return HostResult.Refused(Refusal.EXPIRED)
                }
            }
            rec.boundTo != null -> return HostResult.Refused(Refusal.USED)
            now >= rec.issuedAt + Invite.LIFETIME_MS -> return HostResult.Refused(Refusal.EXPIRED)
        }
        return answer(Ctx(rec, nc, req.eN, fp, req.nodeId, req.name, req.key, req.commit), declined, null, now)
    }

    private fun phase1(nc: ByteArray, nO: ByteArray, frame: ByteArray, now: Long): HostResult {
        val rec = invites.known(now).firstOrNull { r ->
            (r.nc?.contentEquals(nc) == true && r.nO?.contentEquals(nO) == true) || r.declined.any { it.nc.contentEquals(nc) }
        } ?: return HostResult.Refused(Refusal.WRONG_CODE)
        val dec = rec.declined.firstOrNull { it.nc.contentEquals(nc) }
        if (dec != null) { // the requester the owner declined keeps asking: it gets its own answer, whoever holds the invite now
            return answer(Ctx(rec, nc, dec.eN, dec.fp, "", "", ByteArray(65), ByteArray(32)), true, null, now)
        }
        val nN = try {
            val p = Reader(Frames.openOnce(JoinWire.c2a2Key(rec.code, cloudId, nc, nO), 0, cloudId, FrameType.JOIN, frame))
            if (p.u8() != 1) malformed("version")
            p.fixed(16).also { p.end() }
        } catch (e: ProtocolError) {
            return HostResult.Refused(Refusal.WRONG_CODE)
        }
        if (rec.burnt) return HostResult.Refused(Refusal.USED)
        val fp = rec.boundTo ?: return HostResult.Refused(Refusal.WRONG_CODE)
        // the opening must be the one the bound commitment commits to, checked here before anything is forwarded or latched: somebody
        // who holds the code and saw both nonces can seal a wrong opening, and it must neither reach the owner nor use up the forward
        // (Codex Join.kt:514). Only the newcomer knows the right one.
        if (!Crypto.equal(JoinWire.commit(cloudId, rec.key!!, rec.nodeId, rec.name, nN), rec.commit!!)) return HostResult.Refused(Refusal.BAD_PROOF)
        var open: SasOpen? = null
        if (!rec.openForwarded && rec.state == InviteBook.WAITING && rec.ticket != null) {
            rec.openForwarded = true
            open = SasOpen(rec.ticket!!, nN)
        }
        return answer(Ctx(rec, nc, rec.eN!!, fp, rec.nodeId, rec.name, rec.key!!, rec.commit!!), false, open, now)
    }

    /** The status from the list and the invite's state, then the signed, sealed answer. */
    private fun answer(c: Ctx, declined: Boolean, open: SasOpen?, now: Long): HostResult {
        val rec = c.rec!!
        val st = chain.state()
        val isMember = st.member(c.fp) != null
        var forward: AdmitForward? = null
        val sec = secrets()?.takeIf { it.epoch == st.epoch }
        val status = when {
            declined -> JoinStatus.DECLINED
            st.isRemoved(c.fp) -> JoinStatus.REMOVED // a removed key never comes back; the person reinstalls and gets a new key
            isMember && sec != null -> { rec.boundTo = c.fp; if (rec.state == InviteBook.GRANTED) JoinStatus.OK else JoinStatus.ALREADY_MEMBER }
            isMember -> JoinStatus.PENDING // we do not hold the current epoch's secrets yet
            st.byNodeId(c.nodeId) != null -> JoinStatus.NODE_ID_TAKEN
            else -> {
                if (rec.state == InviteBook.NONE) {
                    rec.boundTo = c.fp; rec.nodeId = c.nodeId; rec.name = c.name; rec.key = c.key; rec.eN = c.eN; rec.nc = c.nc; rec.commit = c.commit
                    rec.state = InviteBook.WAITING; rec.ticket = Crypto.random(8)
                    forward = AdmitForward(rec.ticket!!, 1, c.nodeId, c.name, rec.kind, c.key, null, c.commit, st.headSeq, st.headId)
                }
                JoinStatus.PENDING
            }
        }
        val material = status == JoinStatus.OK || status == JoinStatus.ALREADY_MEMBER
        // the signed "extra": the secrets for a final answer, the owner's nonce (or its absence) for PENDING
        val extra = when {
            material -> JoinWire.secretsBytes(sec!!)
            status == JoinStatus.PENDING -> rec.nO?.takeIf { rec.boundTo == c.fp }?.let { byteArrayOf(1) + it } ?: byteArrayOf(0)
            else -> ByteArray(0)
        }
        val entries = if (material) chain.entries() else emptyList()

        // The answer: sealed under a key that needs the newcomer's ephemeral key and the code, signed by this host's key. The chain
        // part is a stream of frames of at most joinPage entries each, so no frame outgrows the frame limit (Codex Join.kt:561); the
        // signature covers the number and the digest of all the entries.
        val na = Crypto.random(32)
        val eH = Crypto.newKeyPair()
        val eHPub = Crypto.rawPublic(eH.public)
        val ecdh = Crypto.ecdh(eH.private, c.eN)
        val rcode = rec.code
        val key = JoinWire.a2cKey(ecdh, rcode, cloudId, c.nc, na)
        Crypto.wipe(ecdh)
        val digest = JoinWire.chainDigest(entries)
        val sig = identity.sign(JoinWire.answerSig(cloudId, c.nc, na, c.eN, eHPub, status, extra, entries.size, digest))
        val sealer = FrameSealer(key, 0, cloudId, FrameType.JOIN)
        val head = concat(Writer().u8(status).bytes(), extra)
        val frames: List<ByteArray> = if (!material) {
            listOf(sealer.seal(head, final = true)) // an answer without a chain is ONE final frame
        } else {
            // the manifest first (status, secrets, entry count, page size, digest of the entries: the host signed exactly these), then pages
            val pages = entries.chunked(joinPage)
            listOf(sealer.seal(concat(head, Writer().u32(entries.size).u32(joinPage).bytes(), digest), final = false)) +
                pages.mapIndexed { i, page -> sealer.seal(JoinWire.pageBytes(page), final = i == pages.lastIndex) }
        }
        Crypto.wipe(key)
        val w = Writer().u8(1).fixed(na, 32).fixed(eHPub, Crypto.PUB_BYTES).fixed(identity.publicKey, Crypto.PUB_BYTES).fixed(sig, Crypto.SIG_BYTES)
        frames.forEach { w.lp(it) }
        return HostResult.Answer(w.bytes(), status, forward, open)
    }

    /** The owner's nonce for a waiting request, matched by its ticket. False if nothing waits for it. */
    @Synchronized fun challenge(challenge: SasChallenge, now: Long): Boolean {
        val rec = invites.known(now).firstOrNull { it.ticket != null && it.ticket!!.contentEquals(challenge.ticket) && it.state == InviteBook.WAITING } ?: return false
        if (rec.nO == null) rec.nO = challenge.nO.copyOf()
        return true
    }

    /** The owner's decision for a waiting request, matched by its ticket. Returns false if nothing was waiting for it. */
    @Synchronized fun deliver(decision: AdmitDecision, now: Long): Boolean {
        val rec = invites.known(now).firstOrNull { it.ticket != null && it.ticket!!.contentEquals(decision.ticket) && it.state == InviteBook.WAITING } ?: return false
        if (decision.failed) {
            // a failed or missing opening: the invite is burnt, nobody can use it again; the owner's screen says to show a new code
            rec.burnt = true
            return true
        }
        if (!decision.granted) {
            // the invite goes back to the next newcomer at once; the declined requester keeps its own answer (4.3)
            if (rec.declined.size >= InviteBook.MAX_DECLINED) rec.declined.removeAt(0)
            rec.declined.add(InviteBook.Declined(rec.nc!!, rec.eN!!, rec.boundTo!!))
            rec.release()
            return true
        }
        return when (chain.offer(decision.entries)) {
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

    /** The owner has not decided yet: ask again with [JoinClient.nextRequest]. [code] is the six digits once the owner's nonce is in. */
    class Pending(val code: String?) : JoinOutcome
}

/**
 * The newcomer's side. [request] (phase 0) is built once; once the owner's nonce is in, [nextRequest] gives the phase-1 request
 * with the opening. Sending the same bytes again is how it polls. Its ephemeral private key is dropped as soon as the exchange ends.
 */
class JoinClient(
    private val invite: Invite,
    private val identity: DeviceIdentity,
    private val nodeId: String,
    private val name: String,
) {
    private val nc = Crypto.random(32)
    private val nN = Crypto.random(16)
    private var eph: KeyPair? = Crypto.newKeyPair()
    private val ephPub = Crypto.rawPublic(eph!!.public)
    private var nO: ByteArray? = null
    private var opening: ByteArray? = null

    /** Six digits to compare with the owner's screen (4.4); null until the owner's nonce has arrived. */
    var confirmationCode: String? = null
        private set

    val request: ByteArray = run {
        val key = identity.publicKey
        val commit = JoinWire.commit(invite.cloudId, key, Shape.requireNodeId(nodeId), Shape.requireName(name), nN)
        val sig = identity.sign(JoinWire.pop(invite.cloudId, nc, nodeId, name, key, ephPub, commit, invite.nodeId))
        val plain = Writer().u8(1).str(nodeId).str(name).fixed(key, Crypto.PUB_BYTES).fixed(ephPub, Crypto.PUB_BYTES).fixed(commit, 32).fixed(sig, Crypto.SIG_BYTES).bytes()
        val frame = Frames.sealOnce(JoinWire.c2aKey(invite.code, invite.cloudId, nc), 0, invite.cloudId, FrameType.JOIN, plain)
        Writer().u8(1).fixed(nc, 32).raw(frame).bytes()
    }

    /** The request to send now: phase 0 until the owner's nonce is in, then the phase-1 request with the opening. */
    @Synchronized fun nextRequest(): ByteArray = opening ?: request

    internal fun holdsEphemeralKey(): Boolean = eph != null

    /** An answer that fails before its signature checks out may be junk from someone on the path: refuse it, keep the exchange alive. */
    private fun junk(): Nothing = throw ProtocolError(CloudError.JOIN_REFUSED)

    /** A validly signed answer that is wrong (or the host being untrustworthy) ends the exchange. */
    private fun refuse(): Nothing { eph = null; throw ProtocolError(CloudError.JOIN_REFUSED) }

    /**
     * Reads a whole answer that is already in memory: the same checks, in the same order, as reading it frame by frame. A wiring stage
     * that reads from a socket uses [beginAnswer] instead, so that nothing is held that has not been checked.
     */
    @Synchronized fun finish(answer: ByteArray, now: Long): JoinOutcome {
        if (answer.size < JoinWire.HEAD_BYTES || answer.size > JoinWire.ANSWER_BYTES_MAX) junk()
        val rd = beginAnswer(now)
        val r = Reader(answer)
        rd.head(r.fixed(JoinWire.HEAD_BYTES))
        while (r.remaining() > 0) rd.frame(r.lp(JoinWire.PAGE_FRAME_MAX), now)
        return rd.end(now)
    }

    /** Starts reading one answer, a piece at a time: [AnswerReader.head] (227 bytes), then each `lp(frame)` body, then [AnswerReader.end]. */
    @Synchronized fun beginAnswer(now: Long): AnswerReader {
        if (eph == null) throw ProtocolError(CloudError.JOIN_REFUSED)
        return AnswerReader(now)
    }

    /**
     * One answer being received (docs 4.2, Codex Join.kt:770). **Nothing beyond the first frame is accepted until the host's
     * signature has been checked**: an answer without a chain is one final frame; a chain answer starts with a manifest (status,
     * secrets, entry count, page size, digest of the entries) that the host signed with the key pinned in the QR code. Frames are
     * sealed under a key that someone on the path with the code can compute, so the signature is what counts, and it is checked
     * on the first frame. After it the reception is incremental and bounded: at most `1 + ceil(count / pageSize)` frames, each of
     * its position's size at most, [JoinWire.ANSWER_BYTES_MAX] bytes all told and [JoinWire.ANSWER_LIMIT_MS] of time; every page holds
     * exactly the entries the manifest says; each entry is checked against the chain so far as it arrives. A failure before the
     * signature holds is junk (the exchange goes on); one after it ends the exchange. A reader that has failed is dead.
     */
    inner class AnswerReader internal constructor(private val startedAt: Long) {
        private var opener: FrameOpener? = null
        private var key: ByteArray? = null
        private var na = ByteArray(0)
        private var eH = ByteArray(0)
        private var hostKey = ByteArray(0)
        private var sig = ByteArray(0)
        private var status = -1
        private var secrets: EpochSecrets? = null
        private var extra = ByteArray(0)
        private var ownerNonce: ByteArray? = null
        private var total = 0
        private var pageSize = 0
        private var wantDigest = ByteArray(0)
        private var received = 0
        private var frames = 0
        private var bytes = 0L
        private var pagesExpected = 0
        private var verified = false
        private var dead = false
        private val builder = Chain.Builder(invite.cloudId)
        private val digest = JoinWire.ChainDigest()

        private fun chainStatus() = status == JoinStatus.OK || status == JoinStatus.ALREADY_MEMBER

        private fun die() { dead = true; key?.let { Crypto.wipe(it) }; key = null }

        private inline fun <T> guarded(block: () -> T): T = synchronized(this@JoinClient) {
            if (dead) throw ProtocolError(CloudError.JOIN_REFUSED)
            try {
                block()
            } catch (e: ProtocolError) {
                die()
                throw e
            }
        }

        fun head(bytes: ByteArray) = guarded {
            val mine = eph ?: junk()
            if (opener != null || bytes.size != JoinWire.HEAD_BYTES) junk()
            val r = Reader(bytes)
            if (r.u8() != 1) junk()
            na = r.fixed(32)
            eH = r.fixed(Crypto.PUB_BYTES)
            hostKey = r.fixed(Crypto.PUB_BYTES)
            sig = r.fixed(Crypto.SIG_BYTES)
            if (!Crypto.isValidPoint(eH) || !Crypto.isValidPoint(hostKey) || !Crypto.equal(Crypto.fingerprint(hostKey), invite.fingerprint)) junk()
            val ecdh = Crypto.ecdh(mine.private, eH)
            key = JoinWire.a2cKey(ecdh, invite.code, invite.cloudId, nc, na)
            Crypto.wipe(ecdh)
            opener = FrameOpener(key!!, 0, invite.cloudId, FrameType.JOIN)
        }

        /** One frame, as received (the bytes after its length). [now] is the clock, for the time limit. */
        fun frame(frame: ByteArray, now: Long) = guarded {
            val op = opener ?: junk()
            if (now - startedAt > JoinWire.ANSWER_LIMIT_MS) junk()
            frames++
            bytes += frame.size
            if (bytes > JoinWire.ANSWER_BYTES_MAX) junk()
            if (frame.size > (if (frames == 1) JoinWire.MANIFEST_FRAME_MAX else JoinWire.PAGE_FRAME_MAX)) junk()
            if (verified && frames > 1 + pagesExpected) junk() // more frames than the signed manifest allows
            if (frames > 1 && !verified) junk() // nothing is read past the first frame until the signature held
            val plain = op.open(frame)
            if (frames == 1) first(plain, op) else page(plain)
        }

        private fun first(plain: ByteArray, op: FrameOpener) {
            val p = Reader(plain)
            status = p.u8()
            when (status) {
                JoinStatus.OK, JoinStatus.ALREADY_MEMBER -> {
                    secrets = EpochSecrets(p.u32(), p.fixed(32), p.fixed(32))
                    extra = JoinWire.secretsBytes(secrets!!)
                    total = p.u32()
                    pageSize = p.u32()
                    wantDigest = p.fixed(32)
                    p.end()
                    if (total < 1 || total > ChainEntry.MAX_ENTRIES || pageSize < 1 || pageSize > JoinWire.PAGE) junk()
                    if (op.finished) junk() // a manifest is followed by its pages
                    pagesExpected = (total + pageSize - 1) / pageSize
                }
                JoinStatus.PENDING -> {
                    val has = p.u8()
                    if (has > 1) junk()
                    if (has == 1) { ownerNonce = p.fixed(16); extra = byteArrayOf(1) + ownerNonce!! } else extra = byteArrayOf(0)
                    p.end()
                    if (!op.finished) junk() // an answer without a chain is one final frame
                }
                JoinStatus.REMOVED, JoinStatus.NODE_ID_TAKEN, JoinStatus.DECLINED -> {
                    p.end()
                    if (!op.finished) junk()
                }
                else -> junk()
            }
            // the host's signature, now: over the status, the secrets or the owner's nonce, and the count and digest of the entries
            val count = if (chainStatus()) total else 0
            val dg = if (chainStatus()) wantDigest else JoinWire.chainDigest(emptyList())
            if (!Crypto.ecdsaVerify(hostKey, JoinWire.answerSig(invite.cloudId, nc, na, ephPub, eH, status, extra, count, dg), sig)) junk()
            verified = true
        }

        private fun page(plain: ByteArray) {
            if (!chainStatus()) junk()
            // from here on the frames come from the host that signed the manifest: what is wrong with them ends the exchange
            try {
                val p = Reader(plain)
                val n = p.u32()
                if (n != minOf(pageSize, total - received)) refuse()
                repeat(n) {
                    val e = ChainEntry.parse(p.lp(ChainEntry.MAX_BODY + 4 + Crypto.SIG_BYTES))
                    digest.update(e)
                    received++
                    builder.add(listOf(e))
                }
                p.end()
            } catch (x: ProtocolError) {
                refuse()
            }
        }

        /** The carrier closed. The last frame must have been the final one; then the outcome, as before. */
        fun end(now: Long): JoinOutcome = guarded {
            val op = opener ?: junk()
            if (now - startedAt > JoinWire.ANSWER_LIMIT_MS) junk()
            op.end() // a cut answer is not an answer (truncated)
            die()
            if (!verified) junk()
            when (status) {
                JoinStatus.PENDING -> {
                    val on = ownerNonce
                    if (on != null && nO == null) { // the first owner nonce is the one; the digits are fixed from here
                        nO = on
                        confirmationCode = ConfirmationCode.of(invite.cloudId, identity.publicKey, nodeId, name, nN, on)
                        val ok = JoinWire.c2a2Key(invite.code, invite.cloudId, nc, on)
                        val openFrame = Frames.sealOnce(ok, 0, invite.cloudId, FrameType.JOIN, Writer().u8(1).fixed(nN, 16).bytes())
                        Crypto.wipe(ok)
                        opening = Writer().u8(2).fixed(nc, 32).fixed(on, 16).raw(openFrame).bytes()
                    }
                    return@guarded JoinOutcome.Pending(confirmationCode)
                }
                JoinStatus.REMOVED, JoinStatus.NODE_ID_TAKEN, JoinStatus.DECLINED -> { eph = null; return@guarded JoinOutcome.Rejected(status) }
            }
            if (received != total || !Crypto.equal(digest.done(), wantDigest)) refuse() // the host signed a manifest its pages do not fit
            val chain = try { builder.build() } catch (e: ProtocolError) { refuse() }
            val st = chain.state()
            val host = st.member(Hex.of(invite.fingerprint))
            if (!chain.cloudId.contentEquals(invite.cloudId) || host == null || host.nodeId != invite.nodeId || host.kind != MemberKind.NODE) refuse()
            if (st.member(identity.memberId()) == null) refuse()
            val s = secrets!!
            val want = st.commitment(s.epoch)
            if (s.epoch != st.epoch || want == null || !Crypto.equal(want, s.commitment())) refuse()
            eph = null
            JoinOutcome.Joined(chain, s)
        }
    }
}
