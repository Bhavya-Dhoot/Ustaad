package com.swarmos.app.cloud

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTest {
    private val dk = Crypto.random(32)
    private val ctx = "request-1".toByteArray()

    private val modern = Caps(
        1, 2, Caps.CIPHER_AES or Caps.CIPHER_CHACHA, Caps.LINK_AP or Caps.LINK_DIRECT,
        Caps.FEATURE_CHANNEL or Caps.FEATURE_BULK, 4, Caps.MAX_FRAME,
    )

    private fun hs(p: Peer, caps: Caps = Caps.BASELINE) = p.handshake(dk, caps)
    private fun pair(a: Peer, b: Peer, capsA: Caps = Caps.BASELINE, capsB: Caps = Caps.BASELINE, now: Long = T0) = sessionBetween(a, b, dk, now, capsA, capsB)

    // ---- the exchange -------------------------------------------------------------------------------------------------

    @Test fun twoMembersAgreeOnKeysAndSeeEachOther() {
        val (c, ps) = cloudWith(2)
        val (a, b) = ps
        val (sa, sb) = pair(a, b)
        assertEquals(sa.id, sb.id)
        assertEquals(16, sa.id.length)
        assertArrayEquals(sa.macKey, sb.macKey)
        assertEquals(b.nodeId, sa.peerNodeId); assertEquals(a.nodeId, sb.peerNodeId)
        assertEquals(b.id, sa.peerId); assertEquals(a.id, sb.peerId)
        assertTrue(sa.initiator); assertFalse(sb.initiator)
        val f = sa.sealer(0, ctx, FrameType.REQUEST).seal("ping".toByteArray(), final = true)
        assertEquals("ping", String(sb.opener(0, ctx, FrameType.REQUEST).open(f)))
        val g = sb.sealer(0, ctx, FrameType.RESPONSE).seal("pong".toByteArray(), final = true)
        assertEquals("pong", String(sa.opener(0, ctx, FrameType.RESPONSE).open(g)))
        assertError(CloudError.BAD_FRAME) { sa.opener(0, ctx, FrameType.REQUEST).open(f) } // reflected to its sender
        assertNotNull(c)
    }

    @Test fun everySessionHasItsOwnKeys() {
        val (_, ps) = cloudWith(2)
        val (s1, _) = pair(ps[0], ps[1])
        val (s2, _) = pair(ps[0], ps[1])
        assertNotEquals(s1.id, s2.id)
        assertFalse(s1.macKey.contentEquals(s2.macKey))
    }

    @Test fun countersRestartAtZeroAfterARekeyUnderNewKeysAndOldFramesDoNotOpen() {
        val (_, ps) = cloudWith(2)
        val (a1, b1) = pair(ps[0], ps[1])
        val (a2, b2) = pair(ps[0], ps[1]) // a new exchange between the same two members: the rekey
        assertNotEquals(a1.id, a2.id)
        val f1 = a1.sealer(0, ctx, FrameType.REQUEST).seal("same".toByteArray(), final = true)
        val f2 = a2.sealer(0, ctx, FrameType.REQUEST).seal("same".toByteArray(), final = true)
        assertEquals(Hex.of(f1.copyOfRange(0, 14)), Hex.of(f2.copyOfRange(0, 14))) // same stream, same counter 0 ...
        assertNotEquals(Hex.of(f1), Hex.of(f2)) // ... under different keys
        assertEquals("same", String(b1.opener(0, ctx, FrameType.REQUEST).open(f1)))
        assertError(CloudError.BAD_FRAME) { b2.opener(0, ctx, FrameType.REQUEST).open(f1) } // a frame of the old session
        assertError(CloudError.BAD_FRAME) { a1.opener(0, ctx, FrameType.REQUEST).open(f1) } // and not back at its own sender
        assertEquals(0, a1.nextStreamNo()); assertEquals(1, a1.nextStreamNo())
    }

    @Test fun aNonMemberCannotStartASession() {
        val (c, ps) = cloudWith(2)
        val b = ps[1]
        val outsider = Peer.bare().also { c.copyTo(it) } // knows the cloud and the discovery key, is not in the chain
        assertError(CloudError.NOT_MEMBER) { hs(b).respond(hs(outsider).initiate(b.nodeId, b.id, T0).init, T0) }
        // without the discovery key the tag is wrong and nothing else is even computed
        val noKey = SessionHandshake(c.chain.cloudId, outsider.identity, outsider.nodeId, { c.chain.state() }, { listOf(Crypto.random(32)) })
        assertError(CloudError.NOT_MEMBER) { hs(b).respond(noKey.initiate(b.nodeId, b.id, T0).init, T0) }
        // a member's key under a made-up node id
        val liar = SessionHandshake(c.chain.cloudId, ps[0].identity, "deadbeef", { c.chain.state() }, { listOf(dk) })
        assertError(CloudError.NOT_MEMBER) { hs(b).respond(liar.initiate(b.nodeId, b.id, T0).init, T0) }
    }

    @Test fun aRemovedMemberCannotStartASessionEvenWithEverythingItKept() {
        val (c, ps) = cloudWith(2)
        val (b, m) = ps
        val kept = Peer(m.identity, m.nodeId, Chain.fromEntries(c.chain.entries()))
        c.removeAndRotate(m.id)
        c.copyTo(b)
        assertError(CloudError.NOT_MEMBER) { hs(b).respond(hs(kept).initiate(b.nodeId, b.id, T0).init, T0) }
        // and a member does not accept the removed one as a responder either: m, still believing it is a member, answers with a
        // correct Reply signed by its own (valid, but removed) key
        val ini = hs(b).initiate(m.nodeId, m.id, T0)
        val reply = hs(kept).respond(ini.init, T0).reply
        assertError(CloudError.NOT_MEMBER) { ini.complete(reply, T0) }
    }

    @Test fun aMemberRemovedDuringTheExchangeIsRefusedAtTheEnd() {
        val (c, ps) = cloudWith(2)
        val (b, d) = ps
        val ini = hs(b).initiate(d.nodeId, d.id, T0)
        val resp = hs(d).respond(ini.init, T0)
        val done = ini.complete(resp.reply, T0)
        c.removeAndRotate(b.id)
        c.copyTo(d) // d hears of it before the last flight
        assertError(CloudError.NOT_MEMBER) { resp.finish(done.finish, T0) }
    }

    @Test fun aResponderWhoIsNotAMemberIsRefusedAndAForgedReplyFails() {
        val (c, ps) = cloudWith(2)
        val (a, b) = ps
        val attacker = Peer.bare()
        c.copyTo(attacker)
        // the attacker believes it is a member (it can believe anything) and answers as "b" with its own key
        val st = a.state
        val fake = st.copy(members = st.members.associateBy { it.id } + (attacker.id to ChainMember(attacker.id, b.nodeId, "b", MemberKind.NODE, attacker.identity.publicKey, null, null, 1)))
        val ah = SessionHandshake(c.chain.cloudId, attacker.identity, b.nodeId, { fake }, { listOf(dk) })
        // an Init meant for b names b's key: the attacker cannot answer it (the Init is signed for one responder, Codex Session.kt:247)
        assertError(CloudError.NOT_MEMBER) { ah.respond(hs(a).initiate(b.nodeId, b.id, T0).init, T0) }
        // an Init that names the attacker's key is answered, and the initiator, whose chain does not list that key, refuses the Reply
        val ini = hs(a).initiate(b.nodeId, attacker.id, T0)
        val reply = ah.respond(ini.init, T0).reply
        assertError(CloudError.NOT_MEMBER) { ini.complete(reply, T0) }

        // a genuine reply altered on the way: any changed field breaks the signed transcript
        val ini2 = hs(a).initiate(b.nodeId, b.id, T0)
        val good = hs(b).respond(ini2.init, T0).reply
        for (i in listOf(20, 60, 100, good.size - 5)) { // fingerprint, ephemeral key (twice), signature
            val i3 = hs(a).initiate(b.nodeId, b.id, T0)
            val g3 = hs(b).respond(i3.init, T0).reply
            assertError(CloudError.NOT_MEMBER) { i3.complete(g3.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }, T0) }
        }
        assertNotNull(ini2.complete(good, T0)) // the unaltered one still works
    }

    @Test fun theInitiatorPinsTheExpectedMembersKeyNotOnlyItsNodeId() { // Codex Session.kt:110
        val (_, ps) = cloudWith(3)
        val (a, b, c3) = ps
        // b is a perfectly good member answering under its own name, but the caller expected c3's key at that address: the Init names
        // c3's key as the responder, so b refuses it (and a Reply from b could never reach this initiator)
        val ini = hs(a).initiate(b.nodeId, c3.id, T0)
        assertError(CloudError.NOT_MEMBER) { hs(b).respond(ini.init, T0) }
        // pinned to the right key it works
        val ini2 = hs(a).initiate(b.nodeId, b.id, T0)
        assertNotNull(ini2.complete(hs(b).respond(ini2.init, T0).reply, T0))
    }

    @Test fun aWrongNodeIdOrVersionIsRefused() {
        val (_, ps) = cloudWith(2)
        val (a, b) = ps
        val ini = hs(a).initiate("deadbeef", b.id, T0) // dials a node id the answer will not match
        val reply = hs(b).respond(hs(a).initiate(b.nodeId, b.id, T0).init, T0).reply
        assertError(CloudError.NOT_MEMBER) { ini.complete(reply, T0) }
        assertError(CloudError.MALFORMED) { hs(b).respond(hs(a).initiate(b.nodeId, b.id, T0).init.also { it[0] = 2 }, T0) }
        assertError(CloudError.MALFORMED) { hs(b).respond(ByteArray(10), T0) }
    }

    // ---- single use, replay and budgets (Codex Session.kt:132, :170, :190) ------------------------------------------------------

    @Test fun completingAnExchangeIsSingleUseAndAReplayedReplyNeverMakesASecondSession() { // Codex Session.kt:132
        val (_, ps) = cloudWith(2)
        val (a, b) = ps
        val ini = hs(a).initiate(b.nodeId, b.id, T0)
        val reply = hs(b).respond(ini.init, T0).reply
        val first = ini.complete(reply, T0)
        first.session.nextStreamNo(); first.session.nextStreamNo()
        val again = ini.complete(reply, T0 + 5)
        assertSame(first, again) // the same object, so the stream counter does not start again at zero
        assertEquals(2, again.session.nextStreamNo())
        // a different Reply is refused
        val other = hs(b).respond(hs(a).initiate(b.nodeId, b.id, T0).init, T0).reply
        assertError(CloudError.NOT_MEMBER) { ini.complete(other, T0) }
        assertSame(first, ini.complete(reply, T0)) // and the first result is still the first result
    }

    @Test fun aFailedCompletionKillsTheExchange() {
        val (_, ps) = cloudWith(2)
        val (a, b) = ps
        val ini = hs(a).initiate(b.nodeId, b.id, T0)
        val reply = hs(b).respond(ini.init, T0).reply
        assertError(CloudError.NOT_MEMBER) { ini.complete(reply.copyOf().also { it[reply.size - 3] = (it[reply.size - 3].toInt() xor 1).toByte() }, T0) }
        assertError(CloudError.NOT_MEMBER) { ini.complete(reply, T0) } // dead: the initiator starts again
    }

    @Test fun ephemeralPrivateKeysAreDroppedOnCompletionAndOnFailure() { // Codex Session.kt:190
        val (_, ps) = cloudWith(2)
        val (a, b) = ps
        val ini = hs(a).initiate(b.nodeId, b.id, T0)
        assertTrue(ini.holdsEphemeralKey())
        val resp = hs(b).respond(ini.init, T0)
        assertTrue(resp.holdsEphemeralKey())
        val done = ini.complete(resp.reply, T0)
        assertFalse(ini.holdsEphemeralKey())
        resp.finish(done.finish, T0)
        assertFalse(resp.holdsEphemeralKey())
        // failures
        val ini2 = hs(a).initiate(b.nodeId, b.id, T0)
        val resp2 = hs(b).respond(ini2.init, T0)
        assertError(CloudError.MALFORMED) { ini2.complete(ByteArray(40), T0) }
        assertFalse(ini2.holdsEphemeralKey())
        assertError(CloudError.MALFORMED) { resp2.finish(ByteArray(10).also { it[0] = 1; it[1] = 3 }, T0) }
        assertFalse(resp2.holdsEphemeralKey())
        // expiry
        val resp3 = hs(b).respond(hs(a).initiate(b.nodeId, b.id, T0).init, T0)
        assertError(CloudError.NOT_MEMBER) { resp3.finish(done.finish, T0 + 20_000) }
        assertFalse(resp3.holdsEphemeralKey())
    }

    @Test fun aReplayedInitIsRefusedBeforeAnyKeyWorkAndAFinishRecordedEarlierDoesNotFit() { // Codex Session.kt:170
        val (_, ps) = cloudWith(2)
        val (a, b) = ps
        val h = hs(b)
        val ini = hs(a).initiate(b.nodeId, b.id, T0)
        val resp = h.respond(ini.init, T0)
        val done = ini.complete(resp.reply, T0)
        assertError(CloudError.NOT_MEMBER) { h.respond(ini.init, T0 + 1) } // the same Init again: no new Reply, no key work
        assertNotNull(resp.finish(done.finish, T0))
    }

    @Test fun thePendingBudgetsAreCheckedBeforeAnyKeyIsGeneratedOrAnythingIsSigned() { // Codex Session.kt:170
        val (_, ps) = cloudWith(3)
        val (a, b, c3) = ps
        // a global budget of one: a valid, fresh Init from a member is refused while the slot is taken, and no Reply exists
        val tight = b.handshake(dk, maxPending = 1)
        val iniA = hs(a).initiate(b.nodeId, b.id, T0)
        val first = tight.respond(iniA.init, T0, source = "10.0.0.1")
        assertError(CloudError.BUSY) { tight.respond(hs(c3).initiate(b.nodeId, b.id, T0).init, T0, source = "10.0.0.2") }
        // a slot comes back when the exchange ends
        first.finish(iniA.complete(first.reply, T0).finish, T0)
        assertNotNull(tight.respond(hs(c3).initiate(b.nodeId, b.id, T0).init, T0, source = "10.0.0.2"))
        // a budget per address: the fifth from one address is refused, another address is not
        val perAddr = b.handshake(dk, maxPending = 64, maxPerSource = 4)
        repeat(4) { perAddr.respond(hs(a).initiate(b.nodeId, b.id, T0).init, T0, source = "10.0.0.9") }
        assertError(CloudError.BUSY) { perAddr.respond(hs(a).initiate(b.nodeId, b.id, T0).init, T0, source = "10.0.0.9") }
        perAddr.respond(hs(a).initiate(b.nodeId, b.id, T0).init, T0, source = "10.0.0.8")
    }

    @Test fun anAddressThatSendsTooManyInitsAMinuteIsRefused() {
        val (_, ps) = cloudWith(2)
        val (a, b) = ps
        val h = SessionHandshake(b.chain.cloudId, b.identity, b.nodeId, { b.chain.state() }, { listOf(dk) }, Caps.BASELINE, 10_000L, 10_000, 10_000, 5)
        repeat(5) { h.respond(hs(a).initiate(b.nodeId, b.id, T0 + it).init, T0 + it, "1.2.3.4") }
        assertError(CloudError.BUSY) { h.respond(hs(a).initiate(b.nodeId, b.id, T0 + 6).init, T0 + 6, "1.2.3.4") }
        h.respond(hs(a).initiate(b.nodeId, b.id, T0 + 61_000).init, T0 + 61_000, "1.2.3.4") // a minute later
    }

    @Test fun theFinishFlightMustBeSignedByTheInitiatorAndWorksOnce() {
        val (_, ps) = cloudWith(2)
        val (a, b) = ps
        val ini = hs(a).initiate(b.nodeId, b.id, T0)
        val resp = hs(b).respond(ini.init, T0)
        val done = ini.complete(resp.reply, T0)
        val forged = done.finish.copyOf().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 1).toByte() }
        val resp2 = hs(b).respond(hs(a).initiate(b.nodeId, b.id, T0).init, T0)
        assertError(CloudError.NOT_MEMBER) { resp2.finish(forged, T0) }
        assertError(CloudError.NOT_MEMBER) { resp2.finish(done.finish, T0) } // a response is consumed by its first try
        val resp3 = hs(b).respond(hs(a).initiate(b.nodeId, b.id, T0).init, T0)
        assertError(CloudError.NOT_MEMBER) { resp3.finish(done.finish, T0) } // a Finish recorded from another exchange does not fit
        assertNotNull(resp.finish(done.finish, T0))
        assertError(CloudError.NOT_MEMBER) { resp.finish(done.finish, T0) } // and once
    }

    // ---- one atomic step (Codex Session.kt:228) ------------------------------------------------------------------------------------

    private class Outcome(val ok: Int, val errors: List<CloudError>)

    /** Runs [n] responds at the same moment: each waits inside the hook, after its tag check, until all have arrived. */
    private fun raceRespond(h: SessionHandshake, inits: List<ByteArray>, sources: List<String>): Outcome {
        val barrier = java.util.concurrent.CyclicBarrier(inits.size)
        h.raceHook = { barrier.await(10, java.util.concurrent.TimeUnit.SECONDS) }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(inits.size)
        try {
            val futures = inits.indices.map { i -> pool.submit<CloudError?> { try { h.respond(inits[i], T0, sources[i]); null } catch (e: ProtocolError) { e.error } } }
            val results = futures.map { it.get(20, java.util.concurrent.TimeUnit.SECONDS) }
            return Outcome(results.count { it == null }, results.filterNotNull())
        } finally {
            pool.shutdownNow()
            h.raceHook = null
        }
    }

    @Test fun twoCopiesOfOneInitThatMeetInsideTheCheckAreAnsweredOnce() { // Codex Session.kt:228
        val (_, ps) = cloudWith(2)
        val (a, b) = ps
        val init = hs(a).initiate(b.nodeId, b.id, T0).init
        // both copies pass the tag check, then wait for each other; only then do they reserve. The nonce check and the reservation
        // are one step, so exactly one gets a Reply
        val r = raceRespond(hs(b), listOf(init, init.copyOf()), listOf("10.0.0.1", "10.0.0.2"))
        assertEquals(1, r.ok)
        assertEquals(listOf(CloudError.NOT_MEMBER), r.errors)
    }

    @Test fun twoInitsThatMeetInsideTheCheckCannotBothSpendTheLastSlotOrTheLastResyncPlace() { // Codex Session.kt:228
        val (c, ps) = cloudWith(3)
        val (a, b, c3) = ps
        // one pending place: of two different valid Inits at the same moment, one is answered and the other is told BUSY
        val tight = b.handshake(dk, maxPending = 1)
        val r1 = raceRespond(tight, listOf(hs(a).initiate(b.nodeId, b.id, T0).init, hs(c3).initiate(b.nodeId, b.id, T0).init), listOf("10.0.0.1", "10.0.0.2"))
        assertEquals(1, r1.ok); assertEquals(listOf(CloudError.BUSY), r1.errors)
        // one resync place: of two Inits with an unknown tag at the same moment, only one is let through to the chain check
        val old = Crypto.random(32)
        fun stale(p: Peer) = SessionHandshake(c.chain.cloudId, p.identity, p.nodeId, { p.chain.state() }, { listOf(old) }).initiate(b.nodeId, b.id, T0).init
        val one = SessionHandshake(b.chain.cloudId, b.identity, b.nodeId, { b.chain.state() }, { listOf(dk) }, maxResyncPerMinute = 1, maxResyncPerMinutePerMember = 1)
        val r2 = raceRespond(one, listOf(stale(a), stale(c3)), listOf("10.0.0.1", "10.0.0.2"))
        assertEquals(1, r2.ok); assertEquals(listOf(CloudError.NOT_MEMBER), r2.errors)
    }

    // ---- members offline across two rotations: the small resync path (Codex Session.kt:230) --------------------------------------------

    private fun stale(p: Peer, old: ByteArray, to: Peer, now: Long = T0) =
        SessionHandshake(p.chain.cloudId, p.identity, p.nodeId, { p.chain.state() }, { listOf(old) }).initiate(to.nodeId, to.id, now)

    @Test fun aMemberThatMissedTwoRotationsStillGetsASessionAndIsToldToPullTheChain() { // Codex Session.kt:230
        val (_, ps) = cloudWith(2)
        val (a, b) = ps
        val old = Crypto.random(32) // the discovery key of an epoch b no longer recognises
        val ini = stale(a, old, b)
        val resp = hs(b).respond(ini.init, T0, "10.0.0.1")
        val sb = resp.finish(ini.complete(resp.reply, T0).finish, T0)
        assertTrue(sb.viaResync) // marked, so the caller makes it pull the chain and the secrets first
        assertEquals(a.id, sb.peerId)
        // a normal Init is not marked
        assertFalse(pair(a, b).second.viaResync)
    }

    @Test fun theResyncPathIsJudgedByTheChainSoARemovedDeviceAndAStrangerStillFail() { // Codex Session.kt:230
        val (c, ps) = cloudWith(2)
        val (b, m) = ps
        val old = Crypto.random(32)
        val kept = Peer(m.identity, m.nodeId, Chain.fromEntries(c.chain.entries()))
        c.removeAndRotate(m.id); c.copyTo(b)
        assertError(CloudError.NOT_MEMBER) { hs(b).respond(stale(kept, old, b).init, T0, "10.0.0.1") } // removed
        val outsider = Peer.bare().also { c.copyTo(it) }
        assertError(CloudError.NOT_MEMBER) { hs(b).respond(stale(outsider, old, b).init, T0, "10.0.0.2") } // never a member
    }

    /** An Init made from a real one: the node id, fingerprint, ephemeral key, tag and offer copied, a fresh nonce, a signature made up. */
    private fun captured(real: ByteArray, nonce: ByteArray = Crypto.random(32), fp: ByteArray? = null): ByteArray {
        val r = Reader(real)
        r.u8(); r.u8()
        val node = r.str(8); val fpI = r.fixed(32); val eph = r.fixed(65); r.fixed(32); val tag = r.fixed(8); val ts = r.u64(); val ext = r.lp(256)
        return Writer().u8(1).u8(1).str(node).fixed(fp ?: fpI, 32).fixed(eph, 65).fixed(nonce, 32).fixed(tag, 8).u64(ts).lp(ext).fixed(Crypto.random(64), 64).bytes()
    }

    @Test fun outsidersAndCapturedIdentitiesNeverTouchTheResyncBudgetAndLearnNothingAboutMembership() { // Codex Session.kt:247
        val (c, ps) = cloudWith(3)
        val (a, b, gone) = ps
        c.removeAndRotate(gone.id); c.copyTo(b)
        val h = hs(b)
        val old = Crypto.random(32)
        val outsider = Peer.bare().also { c.copyTo(it) }
        // an outsider with its own key and an unknown tag, a made-up Init for a CURRENT member and one for a REMOVED member: the same
        // answer for all three, however often, from many addresses, and not one place of the resync budget is used
        repeat(30) { i ->
            assertError(CloudError.NOT_MEMBER) { h.respond(stale(outsider, old, b, T0 + i).init, T0 + i, "10.0.0.${i}") }
            assertError(CloudError.NOT_MEMBER) { h.respond(captured(stale(a, old, b, T0 + i).init), T0 + i, "10.0.1.${i}") }
            assertError(CloudError.NOT_MEMBER) { h.respond(captured(stale(gone, old, b, T0 + i).init), T0 + i, "10.0.2.${i}") }
        }
        assertEquals(0, h.resyncRecords); assertEquals(0, h.seenCount)
        // the member that really was away gets through in that very minute, as often as its own limit says
        val ini = stale(a, old, b, T0 + 40)
        val resp = h.respond(ini.init, T0 + 40, "10.0.3.1")
        assertTrue(resp.finish(ini.complete(resp.reply, T0 + 40).finish, T0 + 40).viaResync)
    }

    @Test fun aStaleMemberIsHeldToTwoResyncsAMinuteAndTheDeviceToSix() { // Codex Session.kt:247
        val (c, ps) = cloudWith(5)
        val (b, m1, m2, m3, m4) = ps
        val h = hs(b)
        val old = Crypto.random(32)
        fun go(p: Peer, now: Long, src: String) = h.respond(stale(p, old, b, now).init, now, src)
        go(m1, T0, "10.0.0.1"); go(m1, T0 + 1, "10.0.0.2")
        assertError(CloudError.NOT_MEMBER) { go(m1, T0 + 2, "10.0.0.3") } // its own limit, however many addresses
        go(m2, T0 + 3, "10.0.0.4"); go(m2, T0 + 4, "10.0.0.5"); go(m3, T0 + 5, "10.0.0.6"); go(m3, T0 + 6, "10.0.0.7")
        assertError(CloudError.NOT_MEMBER) { go(m4, T0 + 7, "10.0.0.8") } // six for the whole device
        go(m4, T0 + 61_000, "10.0.0.8") // a minute later
        assertTrue(h.resyncRecords <= 4)
    }

    @Test fun rejectedIdentitiesCannotFillTheNonceTableAndOneMemberCannotFillItForTheOthers() { // Codex Session.kt:251
        val (c, ps) = cloudWith(2)
        val (a, b) = ps
        val h = SessionHandshake(b.chain.cloudId, b.identity, b.nodeId, { b.chain.state() }, { listOf(dk) }, maxAddressRecords = 100_000, maxSeenInits = 4096)
        val real = hs(a).initiate(b.nodeId, b.id, T0).init // a good tag and node id, observed on the wire
        // 4,200 Inits that copy the tag and node id but carry fresh nonces and an unknown fingerprint, and 300 that name a real member
        // with a signature that is not its own: none is recorded, so the 4,096 places are all still free
        for (i in 0 until 4_200) assertError(CloudError.NOT_MEMBER) { h.respond(captured(real, fp = Crypto.random(32)), T0, "20.${i / 250}.${i % 250}.1") }
        for (i in 0 until 300) assertError(CloudError.NOT_MEMBER) { h.respond(captured(real), T0, "30.${i / 250}.${i % 250}.1") }
        assertEquals(0, h.seenCount)
        val ini = hs(a).initiate(b.nodeId, b.id, T0)
        assertNotNull(h.respond(ini.init, T0, "10.1.1.1")) // an honest exchange is not told busy
        assertEquals(1, h.seenCount)
        // one authenticated member may hold only so many nonces: the others are not shut out by it
        val q = SessionHandshake(b.chain.cloudId, b.identity, b.nodeId, { b.chain.state() }, { listOf(dk) }, maxPending = 64, maxSeenPerMember = 3)
        repeat(3) { q.respond(hs(a).initiate(b.nodeId, b.id, T0).init, T0, "10.0.0.${it + 1}") }
        assertError(CloudError.BUSY) { q.respond(hs(a).initiate(b.nodeId, b.id, T0).init, T0, "10.0.0.9") }
        assertNotNull(c)
    }

    @Test fun everyTableOfAddressesHasAHardCapAndALimitReachedLeavesNothingBehind() { // Codex Session.kt:275
        val (_, ps) = cloudWith(2)
        val (a, b) = ps
        val h = SessionHandshake(b.chain.cloudId, b.identity, b.nodeId, { b.chain.state() }, { listOf(dk) }, maxAddressRecords = 8, maxPending = 1000, maxPendingPerSource = 100)
        repeat(8) { h.respond(hs(a).initiate(b.nodeId, b.id, T0 + it).init, T0 + it, "10.0.0.$it") }
        assertEquals(8, h.addressRecords)
        // a ninth address is refused and NOT inserted, however often it asks
        repeat(5) { assertError(CloudError.BUSY) { h.respond(hs(a).initiate(b.nodeId, b.id, T0 + 10).init, T0 + 10, "10.0.9.9") } }
        assertEquals(8, h.addressRecords)
        // a minute later the old records are removed before the table is called full
        h.respond(hs(a).initiate(b.nodeId, b.id, T0 + 61_000).init, T0 + 61_000, "10.0.9.9")
        assertEquals(1, h.addressRecords)
        // the resync records belong to proven members and follow the same rule; refusals before the proof leave none
        val old = Crypto.random(32)
        val outsider = Peer.bare()
        val h2 = hs(b)
        repeat(20) { assertError(CloudError.NOT_MEMBER) { h2.respond(stale(outsider, old, b, T0 + 70_000).init, T0 + 70_000, "10.7.7.$it") } }
        assertEquals(0, h2.resyncRecords)
    }

    @Test fun aStrangerWhoIsRefusedHoldsNoPendingSlotAndNoNonce() { // Codex Session.kt:230, :251
        val (c, ps) = cloudWith(2)
        val (a, b) = ps
        val h = b.handshake(dk, maxPending = 1)
        val outsider = Peer.bare().also { c.copyTo(it) }
        val old = Crypto.random(32)
        repeat(8) { i -> assertError(CloudError.NOT_MEMBER) { h.respond(stale(outsider, old, b, T0 + i).init, T0 + i, "10.0.0.5") } }
        // the single pending place is free for a member, and no nonce of the strangers was kept
        assertEquals(0, h.seenCount)
        val ini = hs(a).initiate(b.nodeId, b.id, T0 + 20)
        assertNotNull(h.respond(ini.init, T0 + 20, "10.0.0.6"))
    }

    // ---- the store and the life of a session ------------------------------------------------------------------------

    @Test fun aSessionEndsAfter24HoursAndWhenEitherMemberIsRemoved() {
        val (c, ps) = cloudWith(2)
        val (a, b) = ps
        val (sa, _) = pair(a, b)
        val store = SessionStore(a.id)
        store.add(sa)
        assertNotNull(store.get(sa.id, T0 + Session.LIFETIME_MS - 1, a.state))
        assertNull(store.get(sa.id, T0 + Session.LIFETIME_MS, a.state))
        assertNull(store.get(sa.id, T0, a.state)) // the lookup above dropped it

        store.add(sa)
        c.removeAndRotate(b.id) // the peer is removed
        c.copyTo(a)
        assertNull(store.get(sa.id, T0, a.state))
    }

    @Test fun aSessionEndsWhenThisDeviceIsRemoved() {
        val (c, ps) = cloudWith(2)
        val (a, b) = ps
        val (sa, _) = pair(a, b)
        val store = SessionStore(a.id)
        store.add(sa)
        c.removeAndRotate(a.id)
        c.copyTo(a)
        assertNull(store.get(sa.id, T0, a.state))
    }

    @Test fun theStoreKeepsFourSessionsPerPeerAndSweepsTheDead() {
        val (c, ps) = cloudWith(2)
        val (a, b) = ps
        val store = SessionStore(a.id)
        val all = List(6) { pair(a, b, now = T0 + it).first }
        all.forEach { store.add(it) }
        assertEquals(4, store.size)
        assertNull(store.get(all[0].id, T0, a.state)) // the oldest went
        assertNotNull(store.get(all[5].id, T0, a.state))
        c.removeAndRotate(b.id); c.copyTo(a)
        assertEquals(4, store.sweep(T0, a.state)) // all four belong to the removed peer
        assertEquals(0, store.size)
    }

    // ---- negotiation (11.1) and the enforced frame size (Codex Session.kt:36) -----------------------------------------------

    @Test fun twoBaselineDevicesUseSignedAndSealedHttpOnly() {
        val (_, ps) = cloudWith(2)
        val (sa, sb) = pair(ps[0], ps[1])
        for (s in listOf(sa, sb)) {
            assertEquals(1, s.negotiated.profile)
            assertEquals(Aead.AES_256_GCM, s.negotiated.aead)
            assertFalse(s.negotiated.channel)
            assertEquals(0, s.negotiated.bulkStreams)
        }
        var refused = false
        try { sa.bulkSealer(1, ctx) } catch (e: IllegalStateException) { refused = true } // the bulk path was not negotiated
        assertTrue(refused)
    }

    @Test fun twoModernDevicesAgreeOnTheBestCommonSet() {
        val (_, ps) = cloudWith(2)
        val weak = Caps(1, 2, Caps.CIPHER_AES or Caps.CIPHER_CHACHA or Caps.CIPHER_PREFER_CHACHA, Caps.LINK_DIRECT, Caps.FEATURE_CHANNEL or Caps.FEATURE_BULK, 2, 256 * 1024)
        val (sa, sb) = pair(ps[0], ps[1], modern, weak)
        for (s in listOf(sa, sb)) {
            val n = s.negotiated
            assertEquals(2, n.profile)
            assertEquals(Aead.CHACHA20_POLY1305, n.aead) // one side has no AES instructions
            assertTrue(n.channel)
            assertEquals(2, n.bulkStreams)
            assertEquals(256 * 1024, n.maxFrame)
            assertEquals(Caps.LINK_DIRECT, n.links)
        }
        val f = sa.sealer(1, ctx, FrameType.REQUEST).seal("hi".toByteArray(), final = true)
        assertEquals("hi", String(sb.opener(1, ctx, FrameType.REQUEST).open(f)))
        val b1 = sb.bulkSealer(1, "t".toByteArray()).seal(0, byteArrayOf(1, 2, 3), final = true)
        assertTrue(sa.bulkOpener(1, "t".toByteArray(), 3).open(b1)!!.final)
    }

    @Test fun theNegotiatedLargestFrameIsEnforcedForBulkAndChannelStreams() { // Codex Session.kt:36
        val (_, ps) = cloudWith(2)
        val small = Caps(1, 2, Caps.CIPHER_AES, Caps.LINK_AP, Caps.FEATURE_CHANNEL or Caps.FEATURE_BULK, 4, Caps.MIN_FRAME) // 16 KiB
        val (sa, sb) = pair(ps[0], ps[1], small, small)
        assertEquals(Caps.MIN_FRAME, sa.negotiated.maxFrame)
        // the sender refuses to seal more than was agreed
        var refused = 0
        try { sa.bulkSealer(1, ctx).seal(0, ByteArray(Caps.MIN_FRAME + 1)) } catch (e: IllegalArgumentException) { refused++ }
        try { sa.sealer(2, ctx, FrameType.EVENT, channel = true).seal(ByteArray(Caps.MIN_FRAME + 1)) } catch (e: IllegalArgumentException) { refused++ }
        assertEquals(2, refused)
        sa.bulkSealer(1, ctx).seal(0, ByteArray(Caps.MIN_FRAME)) // exactly the limit is fine
        // a peer that ignores the agreement and sends a valid 1 MiB frame is refused by the receiver
        val rogue = BulkSealer(Crypto.random(32), 1, ctx) // (a different key: stands for any oversize frame)
        val big = rogue.seal(0, ByteArray(1024 * 1024))
        assertError(CloudError.BAD_FRAME) { sb.bulkOpener(1, ctx, 1L shl 30).open(big) }
        val bigEvent = FrameSealer(Crypto.random(32), 2, ctx, FrameType.EVENT).seal(ByteArray(Caps.MIN_FRAME + 1))
        assertError(CloudError.BAD_FRAME) { sb.opener(2, ctx, FrameType.EVENT, channel = true).open(bigEvent) }
        // one-shot HTTP bodies keep the larger limit
        val body = sa.sealer(3, ctx, FrameType.REQUEST).seal(ByteArray(1_000_000), final = true)
        assertEquals(1_000_000, sb.opener(3, ctx, FrameType.REQUEST).open(body).size)
    }

    @Test fun anOlderDeviceNeverLocksANewerOneOut() {
        val (_, ps) = cloudWith(2)
        for ((x, y) in listOf((modern to Caps.BASELINE), (Caps.BASELINE to modern))) {
            val (sa, sb) = pair(ps[0], ps[1], x, y)
            for (s in listOf(sa, sb)) {
                assertEquals(1, s.negotiated.profile)
                assertEquals(Aead.AES_256_GCM, s.negotiated.aead)
                assertFalse(s.negotiated.channel)
                assertEquals(0, s.negotiated.bulkStreams)
            }
            val f = sa.sealer(0, ctx, FrameType.REQUEST).seal("still works".toByteArray(), final = true)
            assertEquals("still works", String(sb.opener(0, ctx, FrameType.REQUEST).open(f)))
        }
    }

    @Test fun aCipherIsChosenOnlyWhenBothOfferItAndOneNeedsIt() {
        fun c(ciphers: Int) = Caps(1, 2, ciphers, 0, Caps.FEATURE_CHANNEL, 0, Caps.MIN_FRAME)
        val both = Caps.CIPHER_AES or Caps.CIPHER_CHACHA
        assertEquals(Aead.AES_256_GCM, Negotiated.of(c(both), c(both)).aead)
        assertEquals(Aead.CHACHA20_POLY1305, Negotiated.of(c(both or Caps.CIPHER_PREFER_CHACHA), c(both)).aead)
        assertEquals(Aead.AES_256_GCM, Negotiated.of(c(both or Caps.CIPHER_PREFER_CHACHA), c(Caps.CIPHER_AES)).aead)
    }

    @Test fun theChoiceIsTheSameFromBothSides() {
        val x = Caps(1, 3, 7, 5, 3, 3, 100_000)
        val y = Caps(2, 2, 3, 6, 1, 8, 200_000)
        val n1 = Negotiated.of(x, y); val n2 = Negotiated.of(y, x)
        assertEquals(n1.profile, n2.profile); assertEquals(n1.aead, n2.aead); assertEquals(n1.channel, n2.channel)
        assertEquals(n1.bulkStreams, n2.bulkStreams); assertEquals(n1.maxFrame, n2.maxFrame); assertEquals(n1.links, n2.links)
        assertEquals(2, n1.profile); assertEquals(0, n1.bulkStreams); assertTrue(n1.channel); assertEquals(4, n1.links)
        assertError(CloudError.MALFORMED) { Negotiated.of(Caps(3, 3, 1, 0, 0, 0, 65536), Caps(1, 2, 1, 0, 0, 0, 65536)) }
    }

    @Test fun stripOrChangeAnOfferOnTheWayAndTheSignatureFails() {
        val (_, ps) = cloudWith(2)
        val (a, b) = ps
        val ini = hs(a, modern).initiate(b.nodeId, b.id, T0)
        val reply = hs(b, modern).respond(ini.init, T0).reply
        val r = Reader(reply)
        r.u8(); r.u8(); val node = r.str(8); val fp = r.fixed(32); val eph = r.fixed(65); val nonce = r.fixed(32); r.lp(256); val sig = r.fixed(64)
        val stripped = Writer().u8(1).u8(2).str(node).fixed(fp, 32).fixed(eph, 65).fixed(nonce, 32).lp(ByteArray(0)).fixed(sig, 64).bytes()
        assertError(CloudError.NOT_MEMBER) { ini.complete(stripped, T0) }

        val ini2 = hs(a, modern).initiate(b.nodeId, b.id, T0)
        val ri = Reader(ini2.init)
        ri.u8(); ri.u8(); val n2 = ri.str(8); val f2 = ri.fixed(32); val e2 = ri.fixed(65); val no2 = ri.fixed(32); val tag = ri.fixed(8); val ts2 = ri.u64(); ri.lp(256)
        val proof = ri.fixed(64)
        val initStripped = Writer().u8(1).u8(1).str(n2).fixed(f2, 32).fixed(e2, 65).fixed(no2, 32).fixed(tag, 8).u64(ts2).lp(ByteArray(0)).fixed(proof, 64).bytes()
        assertError(CloudError.NOT_MEMBER) { hs(b, modern).respond(initStripped, T0) } // the Init is signed over its offer: caught at the responder now
    }

    @Test fun offersEncodeDecodeAndUnknownItemsAreSkipped() {
        assertEquals(0, Caps.BASELINE.encodeExt().size)
        assertEquals(1, Caps.decodeExt(ByteArray(0)).maxProfile)
        val d = Caps.decodeExt(modern.encodeExt())
        assertEquals(2, d.maxProfile); assertEquals(modern.ciphers, d.ciphers); assertEquals(modern.links, d.links)
        assertEquals(modern.features, d.features); assertEquals(4, d.bulkStreams); assertEquals(Caps.MAX_FRAME, d.maxFrame)
        val withUnknown = modern.encodeExt() + Writer().u8(9).lp(byteArrayOf(1, 2, 3)).bytes()
        assertEquals(2, Caps.decodeExt(withUnknown).maxProfile)
        assertError(CloudError.MALFORMED) { Caps.decodeExt(Writer().u8(9).lp(ByteArray(1)).raw(modern.encodeExt()).bytes()) }
        assertError(CloudError.MALFORMED) { Caps.decodeExt(modern.encodeExt() + byteArrayOf(5)) }
        assertError(CloudError.MALFORMED) { Caps.decodeExt(Writer().u8(1).lp(ByteArray(3)).bytes()) }
        val noAes = Writer().u8(1).lp(Writer().u8(1).u8(2).u8(2).u8(0).u8(0).u8(0).u32(65536).bytes()).bytes()
        assertError(CloudError.MALFORMED) { Caps.decodeExt(noAes) }
        val smallFrame = Writer().u8(1).lp(Writer().u8(1).u8(2).u8(1).u8(0).u8(0).u8(0).u32(100).bytes()).bytes()
        assertError(CloudError.MALFORMED) { Caps.decodeExt(smallFrame) }
    }

    @Test fun aCapturedSignedInitIsNotGoodAfterItsWindowEvenWhenItsNonceRecordIsGone() { // Codex Session.kt:266
        val (_, ps) = cloudWith(4)
        val (b, m1, m2, m3) = ps
        // one resync a minute for each member: a replay that got through would show at once
        val h = SessionHandshake(b.chain.cloudId, b.identity, b.nodeId, { b.chain.state() }, { listOf(dk) }, maxResyncPerMinute = 6, maxResyncPerMinutePerMember = 1)
        val old = Crypto.random(32)
        val captured = listOf(m1, m2, m3).map { stale(it, old, b, T0).init } // signed Inits whose tag no epoch of b knows: the resync path
        captured.forEachIndexed { i, init -> h.respond(init, T0 + i, "10.0.0.$i") } // each answered once, inside its window
        // inside the window a copy is a replay (the nonce record), a little after it the window refuses it
        assertError(CloudError.NOT_MEMBER) { h.respond(captured[0], T0 + 5, "10.0.0.7") }
        assertError(CloudError.SKEW) { h.respond(captured[0], T0 + SessionHandshake.INIT_SKEW_MS + 1, "10.0.0.7") }
        // after the ten minutes the nonce records are gone; the copies are cycled, six every 61 seconds, from two addresses
        var t = T0 + SessionHandshake.SEEN_INIT_MS + 1_000
        repeat(60) { i ->
            t += 61_000
            assertError(CloudError.SKEW) { h.respond(captured[i % 3], t, "10.0.0.${8 + i % 2}") }
        }
        assertEquals(3, h.seenCount) // no nonce was recorded for any of them
        // and none of that was charged: member m1's own one resync of this minute is still free
        val fresh = stale(m1, old, b, t + 1)
        assertTrue(h.respond(fresh.init, t + 1, "10.0.0.20").reply.isNotEmpty())
        // an Init from the future beyond the window is refused too, one inside the window is not
        assertError(CloudError.SKEW) { h.respond(stale(m2, old, b, t + 40_000).init, t + 1, "10.0.0.21") }
        assertTrue(h.respond(stale(m2, old, b, t + 25_000).init, t + 1, "10.0.0.21").reply.isNotEmpty())
    }
}
