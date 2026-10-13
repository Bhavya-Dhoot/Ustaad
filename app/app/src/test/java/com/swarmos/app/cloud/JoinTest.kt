package com.swarmos.app.cloud

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class JoinTest {
    /** A host (a node, or the owner itself), the owner's `Owner`, and the chain they work on. */
    private inner class Rig(hostIsOwner: Boolean = false, secretsOf: ((Cloud) -> EpochSecrets?)? = null) {
        val cloud: Cloud
        val hostPeer: Peer
        val host: JoinHost
        val owner: Owner
        var lastAnswer = ByteArray(0)

        init {
            val (c, ps) = cloudWith(1)
            cloud = c
            hostPeer = if (hostIsOwner) c.owner else ps[0]
            val sec = secretsOf ?: { cl: Cloud -> cl.secrets.last() }
            host = JoinHost(hostPeer.identity, hostPeer.nodeId, hostPeer.chain, { sec(c) })
            owner = Owner(c.owner.identity, c.chain)
        }

        fun invite(kind: MemberKind = MemberKind.NODE, now: Long = T0) = host.invite(kind, listOf("192.168.1.5"), 8787, now)

        /**
         * The newcomer asks, the host passes the request on, the owner's nonce comes back through the host, the newcomer shows the
         * digits and opens its commitment, the host passes the opening on: the owner now holds an opened request with its digits.
         * Everything goes through the wire form.
         */
        fun toOpen(client: JoinClient, now: Long, hostId: String = hostPeer.id): AdmitRequest {
            val first = host.handle(client.nextRequest(), now) as HostResult.Answer
            assertEquals(JoinStatus.PENDING, first.status)
            assertNull((client.finish(first.bytes, now) as JoinOutcome.Pending).code) // no digits before the owner's nonce
            val req = owner.receive(AdmitForward.parse(first.forward!!.encode()), hostId, now)
            assertTrue(host.challenge(SasChallenge.parse(req.challenge!!.encode()), now))
            val second = host.handle(client.nextRequest(), now + 1) as HostResult.Answer
            val shown = (client.finish(second.bytes, now + 1) as JoinOutcome.Pending).code
            assertNotNull(shown)
            val third = host.handle(client.nextRequest(), now + 2) as HostResult.Answer
            assertNull(owner.opened(req, SasOpen.parse(third.open!!.encode()), now + 2))
            assertEquals(shown, req.digits)
            return req
        }

        /** The whole exchange to its end. The owner taps Allow (or refuses) on the prompt of the request. */
        fun run(client: JoinClient, now: Long, approve: Boolean = true): JoinOutcome {
            val req = toOpen(client, now)
            val shownAt = now + 3
            val prompt = owner.prompt(req, shownAt)
            val tapAt = shownAt + Owner.MIN_DWELL_MS
            val decision = owner.decide(req, if (approve) prompt.approve(tapAt) else null, tapAt + 1)
            assertTrue(host.deliver(AdmitDecision.parse(decision.encode()), tapAt + 1))
            val last = host.handle(client.nextRequest(), tapAt + 2) as HostResult.Answer
            lastAnswer = last.bytes
            return client.finish(last.bytes, tapAt + 2)
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
        val inv0 = rig.invite()
        val c = client(inv0, n)
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
        // the newcomer holds the whole chain from the genesis whose hash is the cloud id of the invitation
        assertEquals(rig.cloud.chain.entries().size, out.chain.entries().size)
        assertEquals(ChainEntry.GENESIS, out.chain.entries().first().op)
        assertArrayEquals(inv0.cloudId, out.chain.cloudId)
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
        val first = answer(rig.host.handle(c.nextRequest(), T0 + 1_000))
        assertNotNull(first.forward) // passed on once
        assertEquals(JoinStatus.PENDING, first.status)
        repeat(20) { i ->
            val a = answer(rig.host.handle(c.nextRequest(), T0 + 2_000 + i * 2_000L)) // the newcomer polls every two seconds
            assertEquals(JoinStatus.PENDING, a.status)
            assertNull(a.forward) // not passed on again
            assertNull(a.open)
            assertNull((c.finish(a.bytes, T0 + 2_000) as JoinOutcome.Pending).code) // and no digits are shown without the owner's nonce
        }
        assertNull(c.confirmationCode)
        // neither the host nor anyone else can write: the host's own chain and the owner's are unchanged
        assertNull(rig.hostPeer.chain.state().member(n.id)); assertNull(rig.cloud.state.member(n.id))
        assertEquals(before, rig.cloud.chain.heartbeat())
        // after the invite's ten minutes and five more the wait ends in the generic refusal
        assertEquals(Refusal.EXPIRED, refusal(rig.host.handle(c.nextRequest(), T0 + Invite.LIFETIME_MS + JoinHost.PENDING_GRACE_MS)))
    }

    // ---- a declined requester releases the invite (Codex Join.kt:400) ---------------------------------------------------------

    @Test fun anOwnerWhoDeclinesLeavesTheInviteUsableAtOnceAndTheDeclinedRequesterKeepsItsOwnAnswer() {
        val rig = Rig()
        val inv = rig.invite()
        val thief = Newcomer(name = "Not mine")
        val thiefClient = client(inv, thief)
        val out = rig.run(thiefClient, T0 + 1_000, approve = false)
        assertEquals(JoinStatus.DECLINED, (out as JoinOutcome.Rejected).status)
        assertNull(rig.cloud.state.member(thief.id))
        // the invitation is live again: the real newcomer uses it
        val real = Newcomer()
        assertTrue(rig.run(client(inv, real), T0 + 5_000) is JoinOutcome.Joined)
        assertNotNull(rig.cloud.state.member(real.id))
        // the declined requester, asking again with either request, is told it was declined: never USED, never a join
        assertEquals(JoinStatus.DECLINED, answer(rig.host.handle(thiefClient.request, T0 + 6_000)).status)
        assertEquals(JoinStatus.DECLINED, answer(rig.host.handle(thiefClient.nextRequest(), T0 + 6_001)).status)
        assertNull(rig.cloud.state.member(thief.id))
    }

    @Test fun aQrPhotographerWhoBindsTheInviteAndStopsPollingDoesNotBlockTheRealNewcomer() { // Codex Join.kt:400
        val rig = Rig()
        val inv = rig.invite()
        val photographer = Newcomer(name = "Photographer")
        val p = client(inv, photographer)
        val first = answer(rig.host.handle(p.request, T0))
        val req = rig.owner.receive(first.forward!!, rig.hostPeer.id, T0)
        // the owner sees a request it does not know, taps Cancel, and the photographer never asks again
        val decision = rig.owner.decide(req, null, T0 + 1)
        assertFalse(decision.granted)
        assertTrue(rig.host.deliver(decision, T0 + 1))
        // without releasing the binding the real newcomer got USED here
        val real = Newcomer()
        val out = rig.run(client(inv, real), T0 + 2_000)
        assertTrue(out is JoinOutcome.Joined)
        assertNotNull(rig.cloud.state.member(real.id))
        assertNull(rig.cloud.state.member(photographer.id))
        // the book keeps at most a few declined requesters per invite: the oldest is forgotten
        val inv2 = rig.invite(now = T0 + 3_000)
        repeat(InviteBook.MAX_DECLINED + 2) { i ->
            val t = T0 + 3_000 + i
            val f = answer(rig.host.handle(client(inv2, Newcomer()).request, t)).forward!!
            val r = rig.owner.receive(f, rig.hostPeer.id, t)
            assertTrue(rig.host.deliver(rig.owner.decide(r, null, t), t))
        }
        val rec = rig.host.invites.known(T0 + 4_000).first { it.code.contentEquals(inv2.code) }
        assertEquals(InviteBook.MAX_DECLINED, rec.declined.size)
        assertNull(rec.boundTo)
    }

    // ---- the owner's tap (Codex Join.kt:223) ------------------------------------------------------------------------------------

    @Test fun theConfirmationIsRequiredBoundToTheDisplayedRequestAndRefusesByDefault() { // Codex Join.kt:223
        val rig = Rig()
        val second = rig.cloud.owner.id // a second host id, so that two requests can be open at once
        fun request(hostId: String = rig.hostPeer.id) = rig.toOpen(client(rig.invite(), Newcomer()), T0, hostId)
        val size = rig.cloud.chain.entries().size
        // nothing is signed without an approval: cancel is refusal
        val r1 = request()
        rig.owner.prompt(r1, T0 + 3)
        assertFalse(rig.owner.decide(r1, null, T0 + 4).granted)
        // an approval made for another request's prompt cannot be used
        val r2 = request(); val r3 = request(second)
        rig.owner.prompt(r2, T0 + 3); val p3 = rig.owner.prompt(r3, T0 + 3)
        assertFalse(rig.owner.decide(r2, p3.approve(T0 + 3 + Owner.MIN_DWELL_MS), T0 + 4 + Owner.MIN_DWELL_MS).granted)
        rig.owner.decide(r3, null, T0 + 5) // close the other
        // an approval is single use and belongs to its prompt
        val r4 = request()
        val p4 = rig.owner.prompt(r4, T0 + 3)
        val ap = p4.approve(T0 + 3 + Owner.MIN_DWELL_MS)
        assertTrue(rig.owner.decide(r4, ap, T0 + 4 + Owner.MIN_DWELL_MS).granted)
        val r5 = request()
        rig.owner.prompt(r5, T0 + 3)
        assertFalse(rig.owner.decide(r5, ap, T0 + 5 + Owner.MIN_DWELL_MS).granted)
        // a tap faster than a person can read the screen counts for nothing
        val r6 = request()
        val p6 = rig.owner.prompt(r6, T0 + 3)
        assertFalse(rig.owner.decide(r6, p6.approve(T0 + 3 + Owner.MIN_DWELL_MS - 1), T0 + 4 + Owner.MIN_DWELL_MS).granted)
        // a tap after the two minutes
        val r7 = request()
        val p7 = rig.owner.prompt(r7, T0 + 3)
        assertFalse(rig.owner.decide(r7, p7.approve(T0 + 3 + Owner.MIN_DWELL_MS), T0 + Owner.REQUEST_TTL_MS + 1).granted)
        // a request nobody answered within two minutes is closed and counted as refused
        val r8 = request()
        val p8 = rig.owner.prompt(r8, T0 + 3)
        rig.owner.expire(T0 + Owner.REQUEST_TTL_MS + 1)
        assertTrue(rig.owner.pending().isEmpty())
        assertFalse(rig.owner.decide(r8, p8.approve(T0 + Owner.REQUEST_TTL_MS + 2), T0 + Owner.REQUEST_TTL_MS + 3).granted)
        assertEquals(size + 1, rig.cloud.chain.entries().size) // exactly one entry: the one with a proper approval
    }

    @Test fun nothingIsShownToAPersonBeforeTheNewcomersOpeningHasBeenChecked() {
        val rig = Rig()
        val c = client(rig.invite(), Newcomer())
        val first = answer(rig.host.handle(c.request, T0))
        val req = rig.owner.receive(first.forward!!, rig.hostPeer.id, T0)
        assertNull(req.digits)
        var refused = false
        try { rig.owner.prompt(req, T0 + 1) } catch (e: IllegalStateException) { refused = true }
        assertTrue(refused) // no digits, so no prompt, so no approval can exist
        assertFalse(rig.owner.decide(req, null, T0 + 2).granted)
    }

    @Test fun theOwnerSeesTheExactKeyNodeIdNameAndTheHostAndBothScreensShowTheSameDigits() {
        val rig = Rig()
        val n = Newcomer(name = "Kitchen tablet")
        val c = client(rig.invite(MemberKind.CLIENT), n)
        val req = rig.toOpen(c, T0)
        assertArrayEquals(n.identity.publicKey, req.key)
        assertEquals(n.nodeId, req.nodeId); assertEquals("Kitchen tablet", req.name); assertEquals(MemberKind.CLIENT, req.kind)
        assertEquals(rig.hostPeer.id, req.hostMemberId); assertEquals(rig.hostPeer.nodeId, req.hostNodeId)
        assertEquals(n.id, req.fingerprint)
        assertEquals(6, req.digits!!.length)
        assertEquals(c.confirmationCode, req.digits) // the person compares these
        val p = rig.owner.prompt(req, T0 + 3)
        assertEquals(req.digits, p.digits); assertEquals("Kitchen tablet", p.name); assertEquals(n.nodeId, p.nodeId)
        assertEquals(MemberKind.CLIENT, p.kind); assertEquals(rig.hostPeer.nodeId, p.hostNodeId)
        // the digits depend on every part: the cloud, the key, the node id, the name and both nonces
        val cid = rig.cloud.chain.cloudId; val key = n.identity.publicKey
        val nN = Crypto.random(16); val nO = Crypto.random(16)
        val base = ConfirmationCode.of(cid, key, n.nodeId, n.name, nN, nO)
        assertNotEquals(base, ConfirmationCode.of(Crypto.random(16), key, n.nodeId, n.name, nN, nO))
        assertNotEquals(base, ConfirmationCode.of(cid, SoftwareIdentity.generate().publicKey, n.nodeId, n.name, nN, nO))
        assertNotEquals(base, ConfirmationCode.of(cid, key, n.nodeId, "Kitchen table", nN, nO))
        assertNotEquals(base, ConfirmationCode.of(cid, key, n.nodeId, n.name, Crypto.random(16), nO))
        assertNotEquals(base, ConfirmationCode.of(cid, key, n.nodeId, n.name, nN, Crypto.random(16)))
    }

    @Test fun onlyACurrentNodeMayPassARequestOnAndNotTooManyAtOnce() {
        val rig = Rig()
        val fwd = answer(rig.host.handle(client(rig.invite(), Newcomer()).request, T0)).forward!!
        assertError(CloudError.NOT_MEMBER) { rig.owner.receive(fwd, Hex.of(Crypto.random(32)), T0) } // a stranger
        val clientPeer = Peer.bare(); rig.cloud.admit(clientPeer, MemberKind.CLIENT)
        assertError(CloudError.NOT_MEMBER) { rig.owner.receive(fwd, clientPeer.id, T0) } // a client cannot host
        repeat(Owner.MAX_OPEN_PER_HOST) { rig.owner.receive(fwd, rig.hostPeer.id, T0) }
        assertError(CloudError.BUSY) { rig.owner.receive(fwd, rig.hostPeer.id, T0) } // one open request per host: no parallel tries
    }

    @Test fun aTokenClientIsRecordedTheSameWayWithItsIssuerFromTheSession() {
        val rig = Rig()
        val issuer = TokenIssuer(rig.hostPeer.id)
        val m = issuer.prepare()
        val fwd = AdmitForward.forToken(m, "test script", MemberKind.CLIENT, rig.hostPeer.chain)
        val req = rig.owner.receive(AdmitForward.parse(fwd.encode()), rig.hostPeer.id, T0)
        assertNull(req.key); assertArrayEquals(m.keyId, req.tokenKeyId)
        assertNull(req.challenge) // no newcomer device, no digits: the owner's tap on a prompt is all there is
        val p = rig.owner.prompt(req, T0 + 1)
        assertTrue(rig.owner.decide(req, p.approve(T0 + 1 + Owner.MIN_DWELL_MS), T0 + 2 + Owner.MIN_DWELL_MS).granted)
        val member = rig.cloud.state.member(issuer.memberId(m.keyId))
        assertNotNull(member); assertEquals(rig.hostPeer.id, member!!.issuer)
    }

    // ---- six digits that nobody can search for (Codex Join.kt:130) ---------------------------------------------------------------

    @Test fun aHostThatGrindsForMatchingDigitsAfterItHasSeenEverythingStillCannotGetTheOwnerToShowThem() { // Codex Join.kt:130
        val rig = Rig()
        val victim = Newcomer()
        val c = client(rig.invite(), victim)
        val real = answer(rig.host.handle(c.request, T0)).forward!!
        // the host puts its own key in front of the owner, with a commitment of its own made before it knows anything
        val attacker = SoftwareIdentity.generate()
        val cid = rig.cloud.chain.cloudId
        val fake = AdmitForward(
            real.ticket, 1, real.nodeId, real.name, real.kind, attacker.publicKey, null,
            JoinWire.commit(cid, attacker.publicKey, real.nodeId, real.name, Crypto.random(16)), real.hostHeadSeq, real.hostHeadId,
        )
        val req = rig.owner.receive(fake, rig.hostPeer.id, T0)
        val nO = req.challenge!!.nO
        assertTrue(rig.host.challenge(req.challenge!!, T0)) // the owner's nonce reaches the victim through the host
        val shown = (c.finish(answer(rig.host.handle(c.request, T0 + 1)).bytes, T0 + 1) as JoinOutcome.Pending).code!!
        val open = answer(rig.host.handle(c.nextRequest(), T0 + 2)).open!! // the victim's opening passes through the host: it sees nN
        // now the host has every input of the victim's digits and all the time it likes: it searches (about a million tries) for a
        // nonce that makes the owner's digits equal the victim's
        val candidate = ByteArray(16)
        var found: ByteArray? = null
        var i = 0L
        while (found == null && i < 8_000_000L) {
            for (b in 0 until 8) candidate[b] = (i shr (8 * b)).toByte()
            if (ConfirmationCode.of(cid, attacker.publicKey, real.nodeId, real.name, candidate, nO) == shown) found = candidate.copyOf()
            i++
        }
        assumeTrue("no match within 8 million tries (about one run in 3000)", found != null)
        // it opens with the nonce it found: it is not the one it committed to, so the owner shows nothing and closes the request
        val denial = rig.owner.opened(req, SasOpen(real.ticket, found!!), T0 + 3)
        assertNotNull(denial); assertFalse(denial!!.granted)
        assertNull(req.digits)
        assertTrue(rig.owner.pending().isEmpty())
        // one try only: the request is gone, even an opening that would match the commitment is refused now
        val again = rig.owner.opened(req, open, T0 + 4)
        assertNotNull(again); assertNull(req.digits)
        assertFalse(rig.cloud.state.members.any { it.id == attacker.memberId() })
    }

    @Test fun aHostThatSwapsTheKeyAndOpensHonestlyShowsTheOwnerOtherDigits() {
        val rig = Rig()
        val victim = Newcomer()
        val c = client(rig.invite(), victim)
        val real = answer(rig.host.handle(c.request, T0)).forward!!
        val attacker = SoftwareIdentity.generate()
        val nN = Crypto.random(16)
        val cid = rig.cloud.chain.cloudId
        val fake = AdmitForward(
            real.ticket, 1, real.nodeId, real.name, real.kind, attacker.publicKey, null,
            JoinWire.commit(cid, attacker.publicKey, real.nodeId, real.name, nN), real.hostHeadSeq, real.hostHeadId,
        )
        val req = rig.owner.receive(fake, rig.hostPeer.id, T0)
        rig.host.challenge(req.challenge!!, T0)
        c.finish(answer(rig.host.handle(c.request, T0 + 1)).bytes, T0 + 1)
        assertNull(rig.owner.opened(req, SasOpen(real.ticket, nN), T0 + 2)) // an honest opening of its own commitment
        assertNotNull(req.digits); assertNotNull(c.confirmationCode)
        assertNotEquals(c.confirmationCode, req.digits) // so the person sees two different numbers and refuses
    }

    @Test fun anOpeningThatIsNotTheCommittedOneOrNotForThisTicketIsRefusedAndClosesTheRequest() {
        val rig = Rig()
        val c = client(rig.invite(), Newcomer())
        val first = answer(rig.host.handle(c.request, T0))
        val req = rig.owner.receive(first.forward!!, rig.hostPeer.id, T0)
        val wrong = rig.owner.opened(req, SasOpen(first.forward!!.ticket, Crypto.random(16)), T0 + 1)
        assertFalse(wrong!!.granted); assertNull(req.digits); assertTrue(rig.owner.pending().isEmpty())
        // a ticket that is not this request's
        val c2 = client(rig.invite(), Newcomer())
        val req2 = rig.owner.receive(answer(rig.host.handle(c2.request, T0)).forward!!, rig.hostPeer.id, T0)
        assertFalse(rig.owner.opened(req2, SasOpen(Crypto.random(8), Crypto.random(16)), T0 + 1)!!.granted)
        assertNull(req2.digits)
    }

    // ---- statuses from the list --------------------------------------------------------------------------------------------------

    @Test fun aMemberJoiningAgainIsAnsweredFromTheListWithoutTheOwner() {
        val rig = Rig()
        val n = Newcomer()
        rig.run(client(rig.invite(), n), T0)
        val again = client(rig.invite(now = T0 + 5), n)
        val a = answer(rig.host.handle(again.nextRequest(), T0 + 6))
        assertEquals(JoinStatus.ALREADY_MEMBER, a.status); assertNull(a.forward)
        assertTrue(again.finish(a.bytes, T0 + 6) is JoinOutcome.Joined)
    }

    @Test fun aRemovedKeyCanNeverJoinAgainAndANodeIdInUseIsRefused() {
        val rig = Rig()
        val n = Newcomer()
        rig.run(client(rig.invite(), n), T0)
        rig.cloud.removeAndRotate(n.id)
        assertTrue(rig.hostPeer.chain.offer(rig.cloud.chain.entries()) is Offer.Adopted) // the host hears of the removal
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
        assertEquals(Refusal.WRONG_CODE, refusal(rig.host.handle(Writer().u8(2).fixed(Crypto.random(32), 32).fixed(Crypto.random(16), 16).raw(Crypto.random(60)).bytes(), T0)))
        fun sealed(plain: ByteArray, nc: ByteArray = Crypto.random(32)) =
            Writer().u8(1).fixed(nc, 32).raw(Frames.sealOnce(JoinWire.c2aKey(inv.code, inv.cloudId, nc), 0, inv.cloudId, FrameType.JOIN, plain)).bytes()
        assertEquals(Refusal.MALFORMED, refusal(rig.host.handle(sealed(ByteArray(10)), T0))) // the right code, a nonsense body
        // offers the victim's key but signs with its own; and a proof made for another host
        val victim = Newcomer(); val thief = Newcomer()
        val nc = Crypto.random(32); val e = Crypto.newKeyPair(); val ePub = Crypto.rawPublic(e.public); val commit = Crypto.random(32)
        val sig = thief.identity.sign(JoinWire.pop(inv.cloudId, nc, thief.nodeId, "x", victim.identity.publicKey, ePub, commit, inv.nodeId))
        val plain = Writer().u8(1).str(thief.nodeId).str("x").fixed(victim.identity.publicKey, 65).fixed(ePub, 65).fixed(commit, 32).fixed(sig, 64).bytes()
        assertEquals(Refusal.BAD_PROOF, refusal(rig.host.handle(sealed(plain, nc), T0)))
        val sig2 = thief.identity.sign(JoinWire.pop(inv.cloudId, nc, thief.nodeId, "x", thief.identity.publicKey, ePub, commit, "deadbeef"))
        val plain2 = Writer().u8(1).str(thief.nodeId).str("x").fixed(thief.identity.publicKey, 65).fixed(ePub, 65).fixed(commit, 32).fixed(sig2, 64).bytes()
        assertEquals(Refusal.BAD_PROOF, refusal(rig.host.handle(sealed(plain2, nc), T0)))
        // a proof that does not cover the commitment it carries
        val sig3 = thief.identity.sign(JoinWire.pop(inv.cloudId, nc, thief.nodeId, "x", thief.identity.publicKey, ePub, Crypto.random(32), inv.nodeId))
        val plain3 = Writer().u8(1).str(thief.nodeId).str("x").fixed(thief.identity.publicKey, 65).fixed(ePub, 65).fixed(commit, 32).fixed(sig3, 64).bytes()
        assertEquals(Refusal.BAD_PROOF, refusal(rig.host.handle(sealed(plain3, nc), T0)))
        assertEquals(2, rig.cloud.chain.entries().size) // the genesis and the host's add from the fixture: nothing more
    }

    @Test fun aStrangerWhoSawTheNoncesButDoesNotHoldTheCodeCannotSendTheOpening() {
        val rig = Rig()
        val inv = rig.invite()
        val n = Newcomer()
        val c = client(inv, n)
        val first = answer(rig.host.handle(c.request, T0))
        val req = rig.owner.receive(first.forward!!, rig.hostPeer.id, T0)
        rig.host.challenge(req.challenge!!, T0)
        val nc = c.request.copyOfRange(1, 33)
        val nO = req.challenge!!.nO
        // an opening sealed under a key that is not made from the code
        val bogus = Writer().u8(2).fixed(nc, 32).fixed(nO, 16).raw(Frames.sealOnce(Crypto.random(32), 0, inv.cloudId, FrameType.JOIN, Writer().u8(1).fixed(Crypto.random(16), 16).bytes())).bytes()
        assertEquals(Refusal.WRONG_CODE, refusal(rig.host.handle(bogus, T0 + 1)))
        // nothing was used up: the newcomer's own opening still goes through, once
        c.finish(answer(rig.host.handle(c.request, T0 + 2)).bytes, T0 + 2)
        val a = answer(rig.host.handle(c.nextRequest(), T0 + 3))
        assertNotNull(a.open)
        assertNull(answer(rig.host.handle(c.nextRequest(), T0 + 4)).open) // passed on once, not again
    }

    // ---- the answer: Codex Join.kt:166 and :230 -------------------------------------------------------------------------------------

    @Test fun someoneWhoLearnsAUsedCodeLaterCannotOpenARecordedAnswerOrAReplayedOne() { // Codex Join.kt:166
        val rig = Rig()
        val n = Newcomer()
        val inv = rig.invite()
        val c = client(inv, n)
        val recordedRequest = c.request
        assertTrue(rig.run(c, T0) is JoinOutcome.Joined)
        val finalAnswer = rig.lastAnswer
        // the attacker holds the QR code (so the code and the cloud id) and the recorded bytes
        val nc = recordedRequest.copyOfRange(1, 33)
        val r = Reader(finalAnswer); r.u8(); val na = r.fixed(32); r.fixed(65); r.fixed(65); r.fixed(64); val frame = r.rest()
        for (guess in listOf(ByteArray(32), Crypto.random(32), Crypto.hkdf(inv.code, concat(inv.cloudId, nc, na), lbl("join/a2c"), 32))) { // the stage-1 key among them
            assertError(CloudError.BAD_FRAME) { Frames.openOnce(guess, 0, inv.cloudId, FrameType.JOIN, frame) }
        }
        // replaying the recorded request gets a fresh answer sealed to the newcomer's long-dropped ephemeral key: nobody can open it
        val replay = answer(rig.host.handle(recordedRequest, T0 + 100))
        assertEquals(JoinStatus.OK, replay.status)
        assertEquals(-1, indexOf(replay.bytes, rig.cloud.secrets[0].discoveryKey))
        val c2 = client(inv, n) // someone who has the code but not the dropped key cannot finish: a new client has a new ephemeral key
        assertError(CloudError.BAD_FRAME) { c2.finish(replay.bytes, T0 + 100) }
        assertError(CloudError.JOIN_REFUSED) { c.finish(replay.bytes, T0 + 100) } // and the original has dropped it
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

    @Test fun aPendingAnswerCarriesTheOwnersNonceUnderTheHostsSignatureAndCannotBeSwappedOrDroppedByAPathHolder() {
        val rig = Rig()
        val n = Newcomer()
        val inv = rig.invite()
        val c = client(inv, n)
        val first = answer(rig.host.handle(c.request, T0))
        val req = rig.owner.receive(first.forward!!, rig.hostPeer.id, T0)
        rig.host.challenge(req.challenge!!, T0)
        val withNonce = answer(rig.host.handle(c.request, T0 + 1))
        // flipping any byte of what is signed, or of the sealed part, makes the newcomer refuse it and keep waiting
        for (at in listOf(1, 40, 120, 200, withNonce.bytes.size - 1)) {
            val bad = withNonce.bytes.copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() }
            var refused = false
            try { c.finish(bad, T0 + 2) } catch (e: ProtocolError) { refused = true }
            assertTrue("byte $at", refused)
        }
        assertNull(c.confirmationCode)
        assertTrue(c.holdsEphemeralKey())
        // the genuine one gives the digits, once; a second nonce from the owner side changes neither the digits nor the opening
        val shown = (c.finish(withNonce.bytes, T0 + 3) as JoinOutcome.Pending).code
        assertNotNull(shown); assertEquals(shown, c.confirmationCode)
        val before = c.nextRequest()
        assertTrue(rig.host.challenge(SasChallenge(first.forward!!.ticket, Crypto.random(16)), T0 + 4)) // the host keeps the first
        assertEquals(shown, (c.finish(answer(rig.host.handle(c.request, T0 + 5)).bytes, T0 + 5) as JoinOutcome.Pending).code)
        assertArrayEquals(before, c.nextRequest())
    }

    @Test fun secretsThatDoNotMatchTheChainsCommitmentAreRefusedAndEndTheExchange() {
        val rig = Rig(secretsOf = { cl -> EpochSecrets(0, Crypto.random(32), cl.secrets[0].storageKey) }) // right epoch, wrong keys
        val n = Newcomer()
        val c = client(rig.invite(), n)
        assertError(CloudError.JOIN_REFUSED) { rig.run(c, T0) }
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

    @Test fun theOpeningUsesAKeyOfItsOwnSoNoKeyAndNonceCarryTwoPlaintexts() {
        val rig = Rig()
        val inv = rig.invite()
        val nc = Crypto.random(32); val nO = Crypto.random(16)
        val k0 = JoinWire.c2aKey(inv.code, inv.cloudId, nc)
        val k1 = JoinWire.c2a2Key(inv.code, inv.cloudId, nc, nO)
        assertFalse(k0.contentEquals(k1))
        assertFalse(k1.contentEquals(JoinWire.c2a2Key(inv.code, inv.cloudId, nc, Crypto.random(16))))
        // both are used at nonce 0 of their own key: a frame sealed under one never opens under the other
        val frame = Frames.sealOnce(k0, 0, inv.cloudId, FrameType.JOIN, byteArrayOf(1))
        assertError(CloudError.BAD_FRAME) { Frames.openOnce(k1, 0, inv.cloudId, FrameType.JOIN, frame) }
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

    @Test fun theMessagesBetweenHostAndOwnerParseStrictly() {
        val rig = Rig()
        val f = answer(rig.host.handle(client(rig.invite(), Newcomer()).request, T0)).forward!!
        assertEquals(f.nodeId, AdmitForward.parse(f.encode()).nodeId)
        assertArrayEquals(f.commit, AdmitForward.parse(f.encode()).commit)
        assertError(CloudError.MALFORMED) { AdmitForward.parse(f.encode() + byteArrayOf(0)) }
        assertError(CloudError.MALFORMED) { AdmitForward.parse(f.encode().also { it[0] = 2 }) }
        val ch = SasChallenge(Crypto.random(8), Crypto.random(16))
        assertArrayEquals(ch.nO, SasChallenge.parse(ch.encode()).nO)
        assertError(CloudError.MALFORMED) { SasChallenge.parse(ch.encode() + byteArrayOf(0)) }
        assertError(CloudError.MALFORMED) { SasChallenge.parse(ch.encode().copyOf(20)) }
        val op = SasOpen(Crypto.random(8), Crypto.random(16))
        assertArrayEquals(op.nN, SasOpen.parse(op.encode()).nN)
        assertError(CloudError.MALFORMED) { SasOpen.parse(op.encode() + byteArrayOf(0)) }
        val d = AdmitDecision(f.ticket, false, emptyList())
        assertFalse(AdmitDecision.parse(d.encode()).granted)
        assertError(CloudError.MALFORMED) { AdmitDecision.parse(d.encode() + byteArrayOf(1)) }
        assertFalse(rig.host.deliver(AdmitDecision(ByteArray(8), true, emptyList()), T0)) // nothing waits for an unknown ticket
        assertFalse(rig.host.challenge(SasChallenge(ByteArray(8), Crypto.random(16)), T0))
    }
}
