package com.swarmos.app.cloud

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTest {
    private val dk = Crypto.random(32)
    private val ctx = "request-1".toByteArray()

    private val modern = Caps(
        1, 2, Caps.CIPHER_AES or Caps.CIPHER_CHACHA, Caps.LINK_AP or Caps.LINK_DIRECT,
        Caps.FEATURE_CHANNEL or Caps.FEATURE_BULK, 4, Caps.MAX_FRAME,
    )

    private fun hs(d: Dev, caps: Caps = Caps.BASELINE) =
        SessionHandshake(d.log.cloudId, d.identity, d.nodeId, { d.log.state() }, { dk }, caps)

    private fun pair(a: Dev, b: Dev, capsA: Caps = Caps.BASELINE, capsB: Caps = Caps.BASELINE, now: Long = T0): Pair<Session, Session> {
        val ini = hs(a, capsA).initiate(b.nodeId, now)
        val resp = hs(b, capsB).respond(ini.init, now)
        val done = ini.complete(resp.reply, now)
        return done.session to resp.finish(done.finish, now)
    }

    // ---- the exchange -------------------------------------------------------------------------------------------------

    @Test fun twoMembersAgreeOnKeysAndSeeEachOther() {
        val (a, b) = cloudOf(1)
        val (sa, sb) = pair(a, b)
        assertEquals(sa.id, sb.id)
        assertEquals(16, sa.id.length)
        assertArrayEquals(sa.macKey, sb.macKey)
        assertEquals(b.nodeId, sa.peerNodeId); assertEquals(a.nodeId, sb.peerNodeId)
        assertEquals(b.id, sa.peerId); assertEquals(a.id, sb.peerId)
        assertTrue(sa.initiator); assertFalse(sb.initiator)
        // one sealing key per direction
        val f = sa.sealer(0, ctx, FrameType.REQUEST).seal("ping".toByteArray(), final = true)
        assertEquals("ping", String(sb.opener(0, ctx, FrameType.REQUEST).open(f)))
        val g = sb.sealer(0, ctx, FrameType.RESPONSE).seal("pong".toByteArray(), final = true)
        assertEquals("pong", String(sa.opener(0, ctx, FrameType.RESPONSE).open(g)))
        assertError(CloudError.BAD_FRAME) { sa.opener(0, ctx, FrameType.REQUEST).open(f) } // reflected to its sender
    }

    @Test fun countersRestartAtZeroAfterARekeyUnderNewKeysAndOldFramesDoNotOpen() {
        val (a, b) = cloudOf(1)
        val (a1, b1) = pair(a, b)
        val (a2, b2) = pair(a, b) // a new exchange between the same two members: the rekey
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

    @Test fun everySessionHasItsOwnKeys() {
        val (a, b) = cloudOf(1)
        val (s1, _) = pair(a, b)
        val (s2, _) = pair(a, b)
        assertNotEquals(s1.id, s2.id)
        assertFalse(s1.macKey.contentEquals(s2.macKey))
    }

    @Test fun aNonMemberCannotStartASession() {
        val (a, b) = cloudOf(1)
        val outsider = Dev.bare().also { it.log = a.copyLog() } // knows the cloud and the discovery key, is not in the list
        assertError(CloudError.NOT_MEMBER) { hs(b).respond(hs(outsider).initiate(b.nodeId, T0).init, T0) }
        // without the discovery key the tag is wrong and nothing else is even computed
        val noKey = SessionHandshake(a.log.cloudId, outsider.identity, outsider.nodeId, { a.log.state() }, { Crypto.random(32) })
        assertError(CloudError.NOT_MEMBER) { hs(b).respond(noKey.initiate(b.nodeId, T0).init, T0) }
        // a member's key under a made-up node id
        val liar = SessionHandshake(a.log.cloudId, a.identity, "deadbeef", { a.log.state() }, { dk })
        assertError(CloudError.NOT_MEMBER) { hs(b).respond(liar.initiate(b.nodeId, T0).init, T0) }
    }

    @Test fun aRemovedMemberCannotStartASessionEvenWithEverythingItKept() {
        val (a, b, m) = cloudOf(2)
        val kept = Dev(m.identity, m.nodeId, m.copyLog())
        a.remove(m.id)
        b.log.merge(a.log.entries())
        assertError(CloudError.NOT_MEMBER) { hs(b).respond(hs(kept).initiate(b.nodeId, T0).init, T0) }
        // and a member does not accept the removed one as a responder either: m, still believing it is a member, answers with a
        // correct Reply signed by its own (valid, but removed) key
        val ini = hs(b).initiate(m.nodeId, T0)
        val reply = hs(kept).respond(ini.init, T0).reply
        assertError(CloudError.NOT_MEMBER) { ini.complete(reply, T0) }
    }

    @Test fun aMemberRemovedDuringTheExchangeIsRefusedAtTheEnd() {
        val (a, b, c) = cloudOf(2)
        val ini = hs(b).initiate(c.nodeId, T0)
        val resp = hs(c).respond(ini.init, T0)
        val done = ini.complete(resp.reply, T0)
        a.remove(b.id)
        c.log.merge(a.log.entries()) // c hears of it before the last flight
        assertError(CloudError.NOT_MEMBER) { resp.finish(done.finish, T0) }
    }

    @Test fun aResponderWhoIsNotAMemberIsRefusedAndAForgedReplyFails() {
        val (a, b) = cloudOf(1)
        val attacker = Dev.bare()
        attacker.log = a.copyLog()
        // the attacker believes it is a member (it can believe anything) and answers as "b" with its own key
        val fake = MemberState(a.state.members + Member(b.nodeId, "b", MemberKind.NODE, attacker.id, attacker.identity.publicKey, null, a.id, T0), emptySet(), emptySet())
        val ah = SessionHandshake(a.log.cloudId, attacker.identity, b.nodeId, { fake }, { dk })
        val ini = hs(a).initiate(b.nodeId, T0)
        val reply = ah.respond(ini.init, T0).reply
        assertError(CloudError.NOT_MEMBER) { ini.complete(reply, T0) } // its key is not in the list

        // a genuine reply altered on the way: any changed field breaks the signed transcript
        val ini2 = hs(a).initiate(b.nodeId, T0)
        val good = hs(b).respond(ini2.init, T0).reply
        val at = listOf(20, 60, 100, good.size - 5) // fingerprint, ephemeral key (twice), signature
        for (i in at) assertError(CloudError.NOT_MEMBER) { ini2.complete(good.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }, T0) }
        assertNotNull(ini2.complete(good, T0)) // the unaltered one still works
    }

    @Test fun aWrongNodeIdOrVersionIsRefused() {
        val (a, b) = cloudOf(1)
        val ini = hs(a).initiate("deadbeef", T0) // dials a node id the answer will not match
        val reply = hs(b).respond(hs(a).initiate(b.nodeId, T0).init, T0).reply
        assertError(CloudError.NOT_MEMBER) { ini.complete(reply, T0) }
        assertError(CloudError.MALFORMED) { hs(b).respond(hs(a).initiate(b.nodeId, T0).init.also { it[0] = 2 }, T0) }
        assertError(CloudError.MALFORMED) { hs(b).respond(ByteArray(10), T0) }
    }

    @Test fun theFinishFlightMustBeSignedByTheInitiatorAndWorksOnce() {
        val (a, b) = cloudOf(1)
        val ini = hs(a).initiate(b.nodeId, T0)
        val resp = hs(b).respond(ini.init, T0)
        val done = ini.complete(resp.reply, T0)
        val forged = done.finish.copyOf().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 1).toByte() }
        val resp2 = hs(b).respond(ini.init, T0) // the same Init replayed gets a fresh Reply
        assertFalse(resp.reply.contentEquals(resp2.reply))
        assertError(CloudError.NOT_MEMBER) { resp2.finish(forged, T0) }
        assertError(CloudError.NOT_MEMBER) { resp2.finish(done.finish, T0) } // a response is consumed by its first try
        // a Finish recorded from the first exchange does not fit the second
        val resp3 = hs(b).respond(ini.init, T0)
        assertError(CloudError.NOT_MEMBER) { resp3.finish(done.finish, T0) }
        assertNotNull(resp.finish(done.finish, T0))
        assertError(CloudError.NOT_MEMBER) { resp.finish(done.finish, T0) } // and once
    }

    @Test fun aPendingExchangeExpiresAndThePendingTableIsBounded() {
        val (a, b) = cloudOf(1)
        fun handshake() = SessionHandshake(b.log.cloudId, b.identity, b.nodeId, { b.log.state() }, { dk }, Caps.BASELINE, pendingMs = 10_000, maxPending = 2)
        val ini = hs(a).initiate(b.nodeId, T0)
        val h1 = handshake()
        val r1 = h1.respond(ini.init, T0)
        val done = ini.complete(r1.reply, T0)
        assertError(CloudError.NOT_MEMBER) { r1.finish(done.finish, T0 + 10_001) } // too late: the 10 seconds are over
        val h2 = handshake()
        h2.respond(ini.init, T0); h2.respond(ini.init, T0)
        assertError(CloudError.BUSY) { h2.respond(ini.init, T0) } // at most two exchanges pending
        h2.respond(ini.init, T0 + 20_000) // the old ones have expired and made room
    }

    // ---- the store and the life of a session ------------------------------------------------------------------------

    @Test fun aSessionEndsAfter24HoursAndWhenEitherMemberIsRemoved() {
        val (a, b, c) = cloudOf(2)
        val (sa, _) = pair(a, b)
        val store = SessionStore(a.id)
        store.add(sa)
        assertNotNull(store.get(sa.id, T0 + Session.LIFETIME_MS - 1, a.state))
        assertNull(store.get(sa.id, T0 + Session.LIFETIME_MS, a.state))
        assertNull(store.get(sa.id, T0, a.state)) // the lookup above dropped it

        store.add(sa)
        a.remove(b.id) // the peer is removed
        assertNull(store.get(sa.id, T0, a.state))

        val (sc, _) = pair(a, c)
        store.add(sc)
        assertNotNull(store.get(sc.id, T0, a.state))
        a.remove(a.id) // this device is itself removed (it can remove itself)
        assertNull(store.get(sc.id, T0, a.state))
    }

    @Test fun theStoreKeepsFourSessionsPerPeerAndSweepsTheDead() {
        val (a, b) = cloudOf(1)
        val store = SessionStore(a.id)
        val all = List(6) { pair(a, b, now = T0 + it).first }
        all.forEach { store.add(it) }
        assertEquals(4, store.size)
        assertNull(store.get(all[0].id, T0, a.state)) // the oldest went
        assertNotNull(store.get(all[5].id, T0, a.state))
        a.remove(b.id)
        assertEquals(4, store.sweep(T0, a.state)) // all four belong to the removed peer
        assertEquals(0, store.size)
    }

    // ---- negotiation (11.1) -----------------------------------------------------------------------------------------------

    @Test fun twoBaselineDevicesUseSignedAndSealedHttpOnly() {
        val (a, b) = cloudOf(1)
        val (sa, sb) = pair(a, b)
        for (s in listOf(sa, sb)) {
            assertEquals(1, s.negotiated.profile)
            assertEquals(Aead.AES_256_GCM, s.negotiated.aead)
            assertFalse(s.negotiated.channel)
            assertEquals(0, s.negotiated.bulkStreams)
        }
    }

    @Test fun twoModernDevicesAgreeOnTheBestCommonSet() {
        val (a, b) = cloudOf(1)
        val weak = Caps(1, 2, Caps.CIPHER_AES or Caps.CIPHER_CHACHA or Caps.CIPHER_PREFER_CHACHA, Caps.LINK_DIRECT, Caps.FEATURE_CHANNEL or Caps.FEATURE_BULK, 2, 256 * 1024)
        val (sa, sb) = pair(a, b, modern, weak)
        for (s in listOf(sa, sb)) {
            val n = s.negotiated
            assertEquals(2, n.profile)
            assertEquals(Aead.CHACHA20_POLY1305, n.aead) // one side has no AES instructions
            assertTrue(n.channel)
            assertEquals(2, n.bulkStreams)
            assertEquals(256 * 1024, n.maxFrame)
            assertEquals(Caps.LINK_DIRECT, n.links)
        }
        // and the frames really are ChaCha20-Poly1305 both ways
        val f = sa.sealer(1, ctx, FrameType.REQUEST).seal("hi".toByteArray(), final = true)
        assertEquals("hi", String(sb.opener(1, ctx, FrameType.REQUEST).open(f)))
        val b1 = sb.bulkSealer(1, "t".toByteArray()).seal(0, byteArrayOf(1, 2, 3), final = true)
        assertTrue(sa.bulkOpener(1, "t".toByteArray()).open(b1)!!.final)
    }

    @Test fun anOlderDeviceNeverLocksANewerOneOut() {
        val (a, b) = cloudOf(1)
        for ((x, y) in listOf((modern to Caps.BASELINE), (Caps.BASELINE to modern))) {
            val (sa, sb) = pair(a, b, x, y)
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
        assertEquals(Aead.AES_256_GCM, Negotiated.of(c(both), c(both)).aead) // nobody needs it
        assertEquals(Aead.CHACHA20_POLY1305, Negotiated.of(c(both or Caps.CIPHER_PREFER_CHACHA), c(both)).aead)
        assertEquals(Aead.AES_256_GCM, Negotiated.of(c(both or Caps.CIPHER_PREFER_CHACHA), c(Caps.CIPHER_AES)).aead) // the other cannot
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
        val (a, b) = cloudOf(1)
        // a device on the path removes the responder's offer from the Reply to push the pair down to plain HTTP
        val ini = hs(a, modern).initiate(b.nodeId, T0)
        val reply = hs(b, modern).respond(ini.init, T0).reply
        val r = Reader(reply)
        r.u8(); r.u8(); val node = r.str(8); val fp = r.fixed(32); val eph = r.fixed(65); val nonce = r.fixed(32); r.lp(256); val sig = r.fixed(64)
        val stripped = Writer().u8(1).u8(2).str(node).fixed(fp, 32).fixed(eph, 65).fixed(nonce, 32).lp(ByteArray(0)).fixed(sig, 64).bytes()
        assertError(CloudError.NOT_MEMBER) { ini.complete(stripped, T0) }
        assertNotNull(ini.complete(reply, T0))

        // the same for the Init: the responder signs what it received, the initiator what it sent
        val ini2 = hs(a, modern).initiate(b.nodeId, T0)
        val ri = Reader(ini2.init)
        ri.u8(); ri.u8(); val n2 = ri.str(8); val f2 = ri.fixed(32); val e2 = ri.fixed(65); val no2 = ri.fixed(32); val tag = ri.fixed(8); ri.lp(256)
        val initStripped = Writer().u8(1).u8(1).str(n2).fixed(f2, 32).fixed(e2, 65).fixed(no2, 32).fixed(tag, 8).lp(ByteArray(0)).bytes()
        val resp = hs(b, modern).respond(initStripped, T0)
        assertError(CloudError.NOT_MEMBER) { ini2.complete(resp.reply, T0) }
    }

    @Test fun offersEncodeDecodeAndUnknownItemsAreSkipped() {
        assertEquals(0, Caps.BASELINE.encodeExt().size)
        assertEquals(1, Caps.decodeExt(ByteArray(0)).maxProfile)
        val d = Caps.decodeExt(modern.encodeExt())
        assertEquals(2, d.maxProfile); assertEquals(modern.ciphers, d.ciphers); assertEquals(modern.links, d.links)
        assertEquals(modern.features, d.features); assertEquals(4, d.bulkStreams); assertEquals(Caps.MAX_FRAME, d.maxFrame)
        // a later version adds item 9: this build skips it and still reads item 1
        val withUnknown = modern.encodeExt() + Writer().u8(9).lp(byteArrayOf(1, 2, 3)).bytes()
        assertEquals(2, Caps.decodeExt(withUnknown).maxProfile)
        assertError(CloudError.MALFORMED) { Caps.decodeExt(Writer().u8(9).lp(ByteArray(1)).raw(modern.encodeExt()).bytes()) } // ids must ascend
        assertError(CloudError.MALFORMED) { Caps.decodeExt(modern.encodeExt() + byteArrayOf(5)) } // a cut item
        assertError(CloudError.MALFORMED) { Caps.decodeExt(Writer().u8(1).lp(ByteArray(3)).bytes()) } // wrong size
        val noAes = Writer().u8(1).lp(Writer().u8(1).u8(2).u8(2).u8(0).u8(0).u8(0).u32(65536).bytes()).bytes()
        assertError(CloudError.MALFORMED) { Caps.decodeExt(noAes) } // AES-256-GCM is required
        val smallFrame = Writer().u8(1).lp(Writer().u8(1).u8(2).u8(1).u8(0).u8(0).u8(0).u32(100).bytes()).bytes()
        assertError(CloudError.MALFORMED) { Caps.decodeExt(smallFrame) }
    }
}
