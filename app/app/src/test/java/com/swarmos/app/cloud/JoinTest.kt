package com.swarmos.app.cloud

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JoinTest {
    /** A host (a node, or the owner itself), the owner's `Owner`, and the chain they work on. */
    private inner class Rig(hostIsOwner: Boolean = false, secretsOf: ((Cloud) -> EpochSecrets?)? = null) {
        val cloud: Cloud
        val hostPeer: Peer
        val host: JoinHost
        val owner: Owner

        init {
            val (c, ps) = cloudWith(1)
            cloud = c
            hostPeer = if (hostIsOwner) c.owner else ps[0]
            val sec = secretsOf ?: { cl: Cloud -> cl.secrets.last() }
            host = JoinHost(hostPeer.identity, hostPeer.nodeId, hostPeer.chain, { sec(c) })
            owner = Owner(c.owner.identity, c.chain)
        }

        fun invite(kind: MemberKind = MemberKind.NODE, now: Long = T0) = host.invite(kind, listOf("192.168.1.5"), 8787, now)

        /** Newcomer asks, the host passes it on, the owner decides, the host delivers, the newcomer asks again. */
        fun run(client: JoinClient, now: Long, approve: Boolean = true): JoinOutcome {
            val first = host.handle(client.request, now) as HostResult.Answer
            assertEquals(JoinStatus.PENDING, first.status)
            assertTrue(client.finish(first.bytes, now) is JoinOutcome.Pending)
            val req = owner.receive(AdmitForward.parse(first.forward!!.encode()), hostPeer.id, now) // through the wire form
            val decision = owner.decide(req, if (approve) req.approve(now) else null, now)
            assertTrue(host.deliver(AdmitDecision.parse(decision.encode()), now))
            val second = host.handle(client.request, now + 1) as HostResult.Answer
            return client.finish(second.bytes, now + 1)
        }
    }

    private class Newcomer(val identity: SoftwareIdentity = SoftwareIdentity.generate(), val nodeId: String = Peer.newNodeId(), val name: String = "Asha's phone") {
        val id: String get() = identity.memberId()
    }

    private fun client(inv: Invite, n: Newcomer) = JoinClient(inv, n.identity, n.nodeId, n.name)
    private fun answer(r: HostResult) = r as HostResult.Answer
    private fun refusal(r: HostResult) = (r as HostResult.Refused).reason

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    // ---- the invitation text -------------------------------------------------------------------------------------------

    @Test fun anInvitationSurvivesItsTextForm() {
        val inv = Rig().invite()
        val back = Invite.parse(inv.toLink())
        assertArrayEquals(inv.cloudId, back.cloudId); assertEquals(inv.nodeId, back.nodeId)
        assertArrayEquals(inv.fingerprint, back.fingerprint); assertArrayEquals(inv.code, back.code)
        assertEquals(listOf("192.168.1.5"), back.addresses); assertEquals(8787, back.port)
        assertEquals(16, inv.code.size)
        val v6 = Invite(inv.cloudId, inv.nodeId, inv.fingerprint, listOf("192.168.1.5", "[fe80::1]"), 80, inv.code)
        assertEquals(listOf("192.168.1.5", "[fe80::1]"), Invite.parse(v6.toLink()).addresses)
    }

    @Test fun aBadInvitationIsRefusedWholly() {
        val link = Rig().invite().toLink()
        fun bad(s: String) = assertError(CloudError.MALFORMED) { Invite.parse(s) }
        bad("http://x"); bad(link.replace("v=1", "v=2")); bad(link + "&x=1"); bad(link + "&p=9"); bad(link.replace("&p=8787", ""))
        bad(link.replace("&p=8787", "&p=0")); bad(link.replace("&p=8787", "&p=70000")); bad(link.replace("a=192.168.1.5", "a=example.com"))
        bad(link.replace("a=192.168.1.5", "a=")); bad(link.replace(Regex("&i=[^&]*"), "&i=AAAA"))
        bad(link.replace(Regex("&n=[^&]*"), "&n=XYZ")); bad(link.replace(Regex("&c=[^&]*"), "&c=***"))
    }

    // ---- the exchange, through the owner --------------------------------------------------------------------------------------

    @Test fun aNewcomerJoinsThroughAHostAndTheOwnersTapAndEndsWithTheChainTheSecretsAndASession() {
        val rig = Rig()
        val n = Newcomer()
        val c = client(rig.invite(), n)
        val out = rig.run(c, T0 + 1_000) as JoinOutcome.Joined
        val st = out.chain.state()
        assertEquals(rig.cloud.state.members.map { it.id }.toSet() + n.id, st.members.map { it.id }.toSet())
        assertArrayEquals(rig.cloud.chain.cloudId, out.chain.cloudId)
        assertEquals(0, out.secrets.epoch)
        assertArrayEquals(rig.cloud.secrets[0].discoveryKey, out.secrets.discoveryKey)
        assertEquals(rig.cloud.chain.heartbeat(), out.chain.heartbeat())
        assertEquals(MemberKind.NODE, st.member(n.id)!!.kind)
        assertFalse(c.holdsEphemeralKey()) // dropped when the exchange ended
        // the only entry was written by the owner key
        assertEquals(rig.cloud.owner.id, rig.cloud.state.ownerId)
        // and the two can talk at once
        val newPeer = Peer(n.identity, n.nodeId, out.chain)
        val (s1, s2) = sessionBetween(rig.hostPeer.also { rig.cloud.copyTo(it) }, newPeer, out.secrets.discoveryKey)
        assertEquals(s1.id, s2.id)
    }

    @Test fun theOwnersDeviceMayHostItsOwnInvite() {
        val rig = Rig(hostIsOwner = true)
        val n = Newcomer()
        val out = rig.run(client(rig.invite(), n), T0 + 1_000)
        assertTrue(out is JoinOutcome.Joined)
        assertNotNull(rig.cloud.state.member(n.id))
    }

    @Test fun whileTheOwnerIsNotReachedTheNewcomerWaitsAndNothingIsGranted() {
        val rig = Rig()
        val n = Newcomer()
        val inv = rig.invite()
        val c = client(inv, n)
        val before = rig.cloud.chain.heartbeat()
        val first = answer(rig.host.handle(c.request, T0 + 1_000))
        assertNotNull(first.forward) // passed on once
        assertEquals(JoinStatus.PENDING, first.status)
        repeat(20) { i ->
            val a = answer(rig.host.handle(c.request, T0 + 2_000 + i * 2_000L)) // the newcomer polls every two seconds
            assertEquals(JoinStatus.PENDING, a.status)
            assertNull(a.forward) // not passed on again
            assertTrue(c.finish(a.bytes, T0 + 2_000) is JoinOutcome.Pending)
        }
        // neither the host nor anyone else can write: the host's own chain and the owner's are unchanged
        assertNull(rig.hostPeer.chain.state().member(n.id)); assertNull(rig.cloud.state.member(n.id))
        assertEquals(before, rig.cloud.chain.heartbeat())
        // after the invite's ten minutes and five more the wait ends in the generic refusal
        assertEquals(Refusal.EXPIRED, refusal(rig.host.handle(c.request, T0 + Invite.LIFETIME_MS + JoinHost.PENDING_GRACE_MS)))
    }

    @Test fun anOwnerWhoDeclinesLeavesTheInviteUsableForSomeoneElse() {
        val rig = Rig()
        val inv = rig.invite()
        val thief = Newcomer(name = "Not mine")
        val out = rig.run(client(inv, thief), T0 + 1_000, approve = false)
        assertEquals(JoinStatus.DECLINED, (out as JoinOutcome.Rejected).status)
        assertNull(rig.cloud.state.member(thief.id))
        // the invitation is live again: the real newcomer uses it
        val real = Newcomer()
        assertTrue(rig.run(client(inv, real), T0 + 5_000) is JoinOutcome.Joined)
        assertNotNull(rig.cloud.state.member(real.id))
    }

    @Test fun theConfirmationIsRequiredBoundToTheExactRequestAndRefusesByDefault() { // Codex Join.kt:176
        val rig = Rig()
        fun request(n: Newcomer = Newcomer()): Pair<JoinClient, AdmitRequest> {
            val c = client(rig.invite(), n)
            val f = answer(rig.host.handle(c.request, T0)).forward!!
            return c to rig.owner.receive(f, rig.hostPeer.id, T0)
        }
        val size = rig.cloud.chain.entries().size
        // nothing is signed without an approval: cancel is refusal
        val (_, r1) = request()
        assertFalse(rig.owner.decide(r1, null, T0 + 1).granted)
        // an approval made for another request cannot be used
        val (_, r2) = request(); val (_, r3) = request()
        assertFalse(rig.owner.decide(r2, r3.approve(T0 + 1), T0 + 2).granted)
        // an approval is single use
        val (_, r4) = request()
        val ap = r4.approve(T0 + 1)
        assertTrue(rig.owner.decide(r4, ap, T0 + 2).granted)
        val (_, r5) = request()
        assertFalse(rig.owner.decide(r5, ap, T0 + 3).granted)
        // an old approval, and a request nobody answered within two minutes
        val (_, r6) = request()
        assertFalse(rig.owner.decide(r6, r6.approve(T0), T0 + Owner.REQUEST_TTL_MS + 1).granted)
        val (_, r7) = request()
        rig.owner.expire(T0 + Owner.REQUEST_TTL_MS + 1) // nobody tapped in two minutes: closed, counted as refused
        assertTrue(rig.owner.pending().isEmpty())
        assertFalse(rig.owner.decide(r7, r7.approve(T0 + Owner.REQUEST_TTL_MS + 2), T0 + Owner.REQUEST_TTL_MS + 3).granted)
        assertEquals(size + 1, rig.cloud.chain.entries().size) // exactly one entry: the one with a proper approval
    }

    @Test fun theOwnerSeesTheExactKeyNodeIdNameAndTheHostAndBothScreensShowTheSameDigits() {
        val rig = Rig()
        val n = Newcomer(name = "Kitchen tablet")
        val c = client(rig.invite(MemberKind.CLIENT), n)
        val req = rig.owner.receive(answer(rig.host.handle(c.request, T0)).forward!!, rig.hostPeer.id, T0)
        assertArrayEquals(n.identity.publicKey, req.key)
        assertEquals(n.nodeId, req.nodeId); assertEquals("Kitchen tablet", req.name); assertEquals(MemberKind.CLIENT, req.kind)
        assertEquals(rig.hostPeer.id, req.hostMemberId); assertEquals(rig.hostPeer.nodeId, req.hostNodeId)
        assertEquals(n.id, req.fingerprint)
        assertEquals(6, req.confirmationCode.length)
        assertEquals(c.confirmationCode, req.confirmationCode) // the person compares these
        // a request for another key or name shows other digits
        assertNotEquals(c.confirmationCode, ConfirmationCode.of(SoftwareIdentity.generate().publicKey, n.nodeId, n.name))
        assertNotEquals(c.confirmationCode, ConfirmationCode.of(n.identity.publicKey, n.nodeId, "Kitchen table"))
    }

    @Test fun onlyACurrentNodeMayPassARequestOnAndNotTooManyAtOnce() {
        val rig = Rig()
        val fwd = answer(rig.host.handle(client(rig.invite(), Newcomer()).request, T0)).forward!!
        assertError(CloudError.NOT_MEMBER) { rig.owner.receive(fwd, Hex.of(Crypto.random(32)), T0) } // a stranger
        val clientPeer = Peer.bare(); rig.cloud.admit(clientPeer, MemberKind.CLIENT)
        assertError(CloudError.NOT_MEMBER) { rig.owner.receive(fwd, clientPeer.id, T0) } // a client cannot host
        repeat(Owner.MAX_OPEN_PER_HOST) { rig.owner.receive(fwd, rig.hostPeer.id, T0) }
        assertError(CloudError.BUSY) { rig.owner.receive(fwd, rig.hostPeer.id, T0) }
    }

    @Test fun aTokenClientIsRecordedTheSameWayWithItsIssuerFromTheSession() {
        val rig = Rig()
        val issuer = TokenIssuer(rig.hostPeer.id)
        val m = issuer.prepare()
        val fwd = AdmitForward.forToken(m, "test script", MemberKind.CLIENT, rig.hostPeer.chain)
        val req = rig.owner.receive(AdmitForward.parse(fwd.encode()), rig.hostPeer.id, T0)
        assertNull(req.key); assertArrayEquals(m.keyId, req.tokenKeyId)
        assertTrue(rig.owner.decide(req, req.approve(T0 + 1), T0 + 2).granted)
        val member = rig.cloud.state.member(issuer.memberId(m.keyId))
        assertNotNull(member); assertEquals(rig.hostPeer.id, member!!.issuer)
    }

    // ---- statuses from the list --------------------------------------------------------------------------------------------------

    @Test fun aMemberJoiningAgainIsAnsweredFromTheListWithoutTheOwner() {
        val rig = Rig()
        val n = Newcomer()
        rig.run(client(rig.invite(), n), T0)
        val again = client(rig.invite(now = T0 + 5), n)
        val a = answer(rig.host.handle(again.request, T0 + 6))
        assertEquals(JoinStatus.ALREADY_MEMBER, a.status); assertNull(a.forward)
        assertTrue(again.finish(a.bytes, T0 + 6) is JoinOutcome.Joined)
    }

    @Test fun aRemovedKeyCanNeverJoinAgainAndANodeIdInUseIsRefused() {
        val rig = Rig()
        val n = Newcomer()
        rig.run(client(rig.invite(), n), T0)
        rig.cloud.removeAndRotate(n.id)
        assertTrue(rig.hostPeer.chain.offer(rig.cloud.chain.entries(), T0) is Offer.Adopted) // the host hears of the removal
        val c2 = client(rig.invite(now = T0 + 5), n)
        val a = answer(rig.host.handle(c2.request, T0 + 6))
        assertEquals(JoinStatus.REMOVED, a.status); assertNull(a.forward)
        assertEquals(JoinStatus.REMOVED, (c2.finish(a.bytes, T0 + 6) as JoinOutcome.Rejected).status)
        // a node id that a current member holds
        val clash = Newcomer(nodeId = rig.hostPeer.nodeId)
        val c3 = client(rig.invite(now = T0 + 7), clash)
        val b = answer(rig.host.handle(c3.request, T0 + 8))
        assertEquals(JoinStatus.NODE_ID_TAKEN, b.status)
        assertEquals(JoinStatus.NODE_ID_TAKEN, (c3.finish(b.bytes, T0 + 8) as JoinOutcome.Rejected).status)
        // a reinstalled device has a new key and may take the node id of the removed one
        val reinstalled = Newcomer(nodeId = n.nodeId)
        assertTrue(rig.run(client(rig.invite(now = T0 + 9), reinstalled), T0 + 10) is JoinOutcome.Joined)
    }

    // ---- refusals without a sealed answer ----------------------------------------------------------------------------------------

    @Test fun aWrongReusedOrExpiredCodeIsRefusedAndNothingIsPassedOn() {
        val rig = Rig()
        val real = rig.invite()
        val n = Newcomer()
        val guess = Invite(real.cloudId, real.nodeId, real.fingerprint, real.addresses, real.port, Crypto.random(16))
        assertEquals(Refusal.WRONG_CODE, refusal(rig.host.handle(client(guess, n).request, T0)))
        val otherCloud = Invite(Crypto.random(16), real.nodeId, real.fingerprint, real.addresses, real.port, real.code)
        assertEquals(Refusal.WRONG_CODE, refusal(rig.host.handle(client(otherCloud, n).request, T0)))
        // the first valid request binds the code; another key is refused
        val c1 = client(real, n)
        assertNotNull(answer(rig.host.handle(c1.request, T0)).forward)
        assertEquals(Refusal.USED, refusal(rig.host.handle(client(real, Newcomer()).request, T0 + 1)))
        // an expired code
        val old = rig.invite(now = T0)
        assertEquals(Refusal.EXPIRED, refusal(rig.host.handle(client(old, Newcomer()).request, T0 + Invite.LIFETIME_MS)))
        // once the record is forgotten it is simply unknown
        assertEquals(Refusal.WRONG_CODE, refusal(rig.host.handle(client(old, Newcomer()).request, T0 + Invite.LIFETIME_MS + InviteBook.RETAIN_MS + 1)))
    }

    @Test fun garbageAndABadProofAreRefusedWithoutEffect() {
        val rig = Rig()
        val inv = rig.invite()
        assertEquals(Refusal.MALFORMED, refusal(rig.host.handle(ByteArray(5), T0)))
        assertEquals(Refusal.MALFORMED, refusal(rig.host.handle(ByteArray(40) { 2 }, T0)))
        assertEquals(Refusal.WRONG_CODE, refusal(rig.host.handle(Writer().u8(1).fixed(ByteArray(32), 32).raw(Crypto.random(80)).bytes(), T0)))
        fun sealed(plain: ByteArray, nc: ByteArray = Crypto.random(32)) =
            Writer().u8(1).fixed(nc, 32).raw(Frames.sealOnce(JoinWire.c2aKey(inv.code, inv.cloudId, nc), 0, inv.cloudId, FrameType.JOIN, plain)).bytes()
        assertEquals(Refusal.MALFORMED, refusal(rig.host.handle(sealed(ByteArray(10)), T0))) // the right code, a nonsense body
        // offers the victim's key but signs with its own; and a proof made for another host
        val victim = Newcomer(); val thief = Newcomer()
        val nc = Crypto.random(32); val e = Crypto.newKeyPair(); val ePub = Crypto.rawPublic(e.public)
        val sig = thief.identity.sign(JoinWire.pop(inv.cloudId, nc, thief.nodeId, "x", victim.identity.publicKey, ePub, inv.nodeId))
        val plain = Writer().u8(1).str(thief.nodeId).str("x").fixed(victim.identity.publicKey, 65).fixed(ePub, 65).fixed(sig, 64).bytes()
        assertEquals(Refusal.BAD_PROOF, refusal(rig.host.handle(sealed(plain, nc), T0)))
        val sig2 = thief.identity.sign(JoinWire.pop(inv.cloudId, nc, thief.nodeId, "x", thief.identity.publicKey, ePub, "deadbeef"))
        val plain2 = Writer().u8(1).str(thief.nodeId).str("x").fixed(thief.identity.publicKey, 65).fixed(ePub, 65).fixed(sig2, 64).bytes()
        assertEquals(Refusal.BAD_PROOF, refusal(rig.host.handle(sealed(plain2, nc), T0)))
        assertEquals(2, rig.cloud.chain.entries().size) // the genesis and the host's add from the fixture: nothing more
    }

    // ---- the answer: Codex Join.kt:166 and :230 -------------------------------------------------------------------------------------

    @Test fun someoneWhoLearnsAUsedCodeLaterCannotOpenARecordedAnswerOrAReplayedOne() { // Codex Join.kt:166
        val rig = Rig()
        val n = Newcomer()
        val inv = rig.invite()
        val c = client(inv, n)
        val recordedRequest = c.request
        val first = answer(rig.host.handle(c.request, T0))
        val req = rig.owner.receive(first.forward!!, rig.hostPeer.id, T0)
        rig.host.deliver(rig.owner.decide(req, req.approve(T0), T0), T0)
        val finalAnswer = answer(rig.host.handle(c.request, T0 + 1))
        assertTrue(c.finish(finalAnswer.bytes, T0 + 1) is JoinOutcome.Joined)
        // the attacker holds the QR code (so the code and the cloud id) and the recorded bytes
        val nc = recordedRequest.copyOfRange(1, 33)
        val r = Reader(finalAnswer.bytes); r.u8(); val na = r.fixed(32); r.fixed(65); r.fixed(65); r.fixed(64); val frame = r.rest()
        for (guess in listOf(ByteArray(32), Crypto.random(32), Crypto.hkdf(inv.code, concat(inv.cloudId, nc, na), lbl("join/a2c"), 32))) { // the stage-1 key among them
            assertError(CloudError.BAD_FRAME) { Frames.openOnce(guess, 0, inv.cloudId, FrameType.JOIN, frame) }
        }
        // replaying the recorded request gets a fresh answer sealed to the newcomer's long-dropped ephemeral key: nobody can open it
        val replay = answer(rig.host.handle(recordedRequest, T0 + 2))
        assertEquals(JoinStatus.OK, replay.status)
        assertEquals(-1, indexOf(replay.bytes, rig.cloud.secrets[0].discoveryKey))
        val c2 = client(inv, n) // someone who has the code but not the dropped key cannot finish: a new client has a new ephemeral key
        assertError(CloudError.BAD_FRAME) { c2.finish(replay.bytes, T0 + 2) }
        assertError(CloudError.JOIN_REFUSED) { c.finish(replay.bytes, T0 + 2) } // and the original has dropped it
    }

    @Test fun aHolderOfTheCodeOnThePathCannotForgeAnAnswerAndAJunkAnswerDoesNotKillTheExchange() { // Codex Join.kt:230
        val rig = Rig()
        val n = Newcomer()
        val inv = rig.invite()
        val c = client(inv, n)
        val first = answer(rig.host.handle(c.request, T0))
        val nc = c.request.copyOfRange(1, 33)
        // the attacker reads the (code-sealed) request, learns eN, and builds an answer under a key it can compute
        val plain = Frames.openOnce(JoinWire.c2aKey(inv.code, inv.cloudId, nc), 0, inv.cloudId, FrameType.JOIN, c.request.copyOfRange(33, c.request.size))
        val pr = Reader(plain); pr.u8(); pr.str(8); pr.str(64); pr.fixed(65); val eN = pr.fixed(65)
        val attacker = SoftwareIdentity.generate()
        val eA = Crypto.newKeyPair(); val eAPub = Crypto.rawPublic(eA.public); val na = Crypto.random(32)
        val key = JoinWire.a2cKey(Crypto.ecdh(eA.private, eN), inv.code, inv.cloudId, nc, na)
        val fakeSecrets = EpochSecrets(0, Crypto.random(32), Crypto.random(32))
        val chainBytes = EntryRun.encode(rig.cloud.chain.entries())
        val body = concat(Writer().u8(JoinStatus.OK).bytes(), JoinWire.secretsBytes(fakeSecrets), chainBytes)
        fun forged(signer: DeviceIdentity, pubShown: ByteArray): ByteArray {
            val sig = signer.sign(JoinWire.answerSig(inv.cloudId, nc, na, eN, eAPub, JoinStatus.OK, JoinWire.secretsBytes(fakeSecrets), chainBytes))
            return Writer().u8(1).fixed(na, 32).fixed(eAPub, 65).fixed(pubShown, 65).fixed(sig, 64).raw(Frames.sealOnce(key, 0, inv.cloudId, FrameType.JOIN, body)).bytes()
        }
        // (a) signed by the attacker's own key: its fingerprint is not the one in the QR code
        assertError(CloudError.JOIN_REFUSED) { c.finish(forged(attacker, attacker.publicKey), T0 + 1) }
        // (b) wearing the host's public key but signed by the attacker
        assertError(CloudError.JOIN_REFUSED) { c.finish(forged(attacker, rig.hostPeer.identity.publicKey), T0 + 1) }
        // junk did not end the exchange: the real answer still completes it
        assertTrue(c.holdsEphemeralKey())
        assertTrue(c.finish(first.bytes, T0 + 2) is JoinOutcome.Pending)
        // a tampered sealed part is refused too
        assertError(CloudError.BAD_FRAME) { c.finish(first.bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }, T0 + 3) }
        assertTrue(c.holdsEphemeralKey())
    }

    @Test fun secretsThatDoNotMatchTheChainsCommitmentAreRefusedAndEndTheExchange() {
        val rig = Rig(secretsOf = { cl -> EpochSecrets(0, Crypto.random(32), cl.secrets[0].storageKey) }) // right epoch, wrong keys
        val n = Newcomer()
        val c = client(rig.invite(), n)
        val first = answer(rig.host.handle(c.request, T0))
        val req = rig.owner.receive(first.forward!!, rig.hostPeer.id, T0)
        rig.host.deliver(rig.owner.decide(req, req.approve(T0), T0), T0)
        val final = answer(rig.host.handle(c.request, T0 + 1))
        assertEquals(JoinStatus.OK, final.status)
        assertError(CloudError.JOIN_REFUSED) { c.finish(final.bytes, T0 + 1) }
        assertFalse(c.holdsEphemeralKey()) // a validly signed answer that is wrong ends it
    }

    @Test fun aNewcomerRefusesAnAnswerFromSomeoneWhoIsNotTheHostNamedInTheQrCode() {
        val rig = Rig()
        val n = Newcomer()
        val real = rig.invite()
        val imposter = Peer.bare()
        val inv = Invite(real.cloudId, real.nodeId, Crypto.fingerprint(imposter.identity.publicKey), real.addresses, real.port, real.code)
        val c = client(inv, n)
        val a = answer(rig.host.handle(c.request, T0))
        assertError(CloudError.JOIN_REFUSED) { c.finish(a.bytes, T0) } // the host's key is not the pinned one
        // the QR names another node id: the host refuses the proof (it names this host)
        val inv2 = Invite(real.cloudId, "deadbeef", real.fingerprint, real.addresses, real.port, real.code)
        assertEquals(Refusal.BAD_PROOF, refusal(rig.host.handle(client(inv2, Newcomer()).request, T0)))
    }

    @Test fun everyAnswerUsesFreshKeysAndHidesItsContents() {
        val rig = Rig()
        val n = Newcomer()
        val c = client(rig.invite(), n)
        val a1 = answer(rig.host.handle(c.request, T0))
        val a2 = answer(rig.host.handle(c.request, T0 + 1))
        assertNotEquals(Hex.of(a1.bytes.copyOfRange(1, 33)), Hex.of(a2.bytes.copyOfRange(1, 33))) // na differs
        assertFalse(a1.bytes.contentEquals(a2.bytes))
        assertEquals(-1, indexOf(c.request, n.identity.publicKey)); assertEquals(-1, indexOf(c.request, "Asha".toByteArray()))
        // an answer made for one newcomer does not open for another
        val other = client(rig.invite(now = T0 + 2), Newcomer())
        assertError(CloudError.BAD_FRAME) { other.finish(a1.bytes, T0) }
    }

    @Test fun anInvitationHasLimitsAndAClientCanBeInvitedButABrowserCannot() {
        val rig = Rig()
        var refused = false
        try { rig.invite(MemberKind.BROWSER) } catch (e: IllegalArgumentException) { refused = true }
        assertTrue(refused)
        repeat(InviteBook.MAX_OPEN - 0) { rig.invite() }
        refused = false
        try { rig.invite() } catch (e: IllegalStateException) { refused = true }
        assertTrue(refused)
        val n = Newcomer()
        val rig2 = Rig()
        val out = rig2.run(client(rig2.invite(MemberKind.CLIENT), n), T0) as JoinOutcome.Joined
        assertEquals(MemberKind.CLIENT, out.chain.state().member(n.id)!!.kind)
    }

    @Test fun theForwardAndTheDecisionParseStrictly() {
        val rig = Rig()
        val f = answer(rig.host.handle(client(rig.invite(), Newcomer()).request, T0)).forward!!
        assertEquals(f.nodeId, AdmitForward.parse(f.encode()).nodeId)
        assertError(CloudError.MALFORMED) { AdmitForward.parse(f.encode() + byteArrayOf(0)) }
        assertError(CloudError.MALFORMED) { AdmitForward.parse(f.encode().also { it[0] = 2 }) }
        val d = AdmitDecision(f.ticket, false, emptyList())
        assertFalse(AdmitDecision.parse(d.encode()).granted)
        assertError(CloudError.MALFORMED) { AdmitDecision.parse(d.encode() + byteArrayOf(1)) }
        assertFalse(rig.host.deliver(AdmitDecision(ByteArray(8), true, emptyList()), T0)) // nothing waits for an unknown ticket
    }
}
