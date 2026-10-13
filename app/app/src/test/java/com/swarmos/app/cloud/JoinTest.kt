package com.swarmos.app.cloud

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JoinTest {
    private val dk = Crypto.random(32)

    private inner class Rig(var approve: Boolean = true) {
        val admin = Dev.creator()
        val asked = mutableListOf<Triple<String, String, String>>()
        val admitter = Admitter(admin.identity, admin.nodeId, admin.log, dk, InviteBook()) { n, name, fp -> asked.add(Triple(n, name, fp)); approve }

        fun invite(kind: MemberKind = MemberKind.NODE, now: Long = T0) = admitter.invite(kind, listOf("192.168.1.5"), 8787, now)
    }

    private class Newcomer(val identity: SoftwareIdentity = SoftwareIdentity.generate(), val nodeId: String = Dev.newNodeId(), val name: String = "Asha's phone")

    private fun answer(r: AdmitResult): AdmitResult.Answer = r as AdmitResult.Answer
    private fun refusal(r: AdmitResult): Refusal = (r as AdmitResult.Refused).reason

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    // ---- the invitation text -------------------------------------------------------------------------------------------

    @Test fun anInvitationSurvivesItsTextForm() {
        val rig = Rig()
        val inv = rig.invite()
        val link = inv.toLink()
        assertTrue(link.startsWith("ustaad://join?v=1&c="))
        val back = Invite.parse(link)
        assertArrayEquals(inv.cloudId, back.cloudId); assertEquals(inv.nodeId, back.nodeId)
        assertArrayEquals(inv.fingerprint, back.fingerprint); assertArrayEquals(inv.code, back.code)
        assertEquals(listOf("192.168.1.5"), back.addresses); assertEquals(8787, back.port)
        assertEquals(16, inv.code.size) // 128 bits
        assertNotEquals(Hex.of(inv.code), Hex.of(rig.invite().code))
        val v6 = Invite(inv.cloudId, inv.nodeId, inv.fingerprint, listOf("192.168.1.5", "[fe80::1]"), 80, inv.code)
        assertEquals(listOf("192.168.1.5", "[fe80::1]"), Invite.parse(v6.toLink()).addresses)
    }

    @Test fun aBadInvitationIsRefusedWholly() {
        val link = Rig().invite().toLink()
        fun bad(s: String) = assertError(CloudError.MALFORMED) { Invite.parse(s) }
        bad("http://x")
        bad(link.replace("v=1", "v=2"))
        bad(link + "&x=1") // an extra field
        bad(link + "&p=9") // a duplicate
        bad(link.replace("&p=8787", "")) // a missing one
        bad(link.replace("&p=8787", "&p=0"))
        bad(link.replace("&p=8787", "&p=70000"))
        bad(link.replace("a=192.168.1.5", "a=example.com")) // names are not accepted
        bad(link.replace("a=192.168.1.5", "a="))
        bad(link.replace(Regex("&i=[^&]*"), "&i=AAAA")) // wrong size
        bad(link.replace(Regex("&n=[^&]*"), "&n=XYZ"))
        bad(link.replace(Regex("&c=[^&]*"), "&c=***"))
    }

    // ---- the exchange ------------------------------------------------------------------------------------------------

    @Test fun aNewcomerJoinsAndEndsWithTheListTheDiscoveryKeyAndASession() {
        val rig = Rig()
        val n = Newcomer()
        val inv = rig.invite()
        val client = JoinClient(inv, n.identity, n.nodeId, n.name)
        val res = answer(rig.admitter.handle(client.request, T0 + 1_000))
        assertEquals(JoinStatus.OK, res.status)
        val out = client.finish(res.bytes) as JoinOutcome.Joined
        assertArrayEquals(dk, out.discoveryKey)
        assertArrayEquals(rig.admin.log.cloudId, out.log.cloudId)
        assertEquals(setOf(rig.admin.id, n.identity.memberId()), out.log.state().members.map { it.id }.toSet())
        assertEquals(out.log.hash(), rig.admin.log.hash()) // the admitter's own list has the new entry already
        assertEquals(listOf(Triple(n.nodeId, n.name, n.identity.memberId())), rig.asked)
        assertEquals(MemberKind.NODE, rig.admin.state.member(n.identity.memberId())!!.kind)
        // and the two can talk at once
        val newDev = Dev(n.identity, n.nodeId, out.log)
        val (s1, s2) = sessionBetween(rig.admin, newDev, dk)
        assertEquals(s1.id, s2.id)
    }

    @Test fun neitherMessageShowsItsSecretsOnTheWire() {
        val rig = Rig()
        val n = Newcomer(name = "VeryDistinctName")
        val client = JoinClient(rig.invite(), n.identity, n.nodeId, n.name)
        val res = answer(rig.admitter.handle(client.request, T0))
        assertEquals(-1, indexOf(client.request, "VeryDistinctName".toByteArray()))
        assertEquals(-1, indexOf(client.request, n.identity.publicKey))
        assertEquals(-1, indexOf(res.bytes, dk)) // the discovery key is sealed
        assertEquals(-1, indexOf(res.bytes, rig.admin.identity.publicKey))
    }

    @Test fun aWrongCodeIsRefusedAndNothingIsAdded() {
        val rig = Rig()
        val n = Newcomer()
        val real = rig.invite()
        val guess = Invite(real.cloudId, real.nodeId, real.fingerprint, real.addresses, real.port, Crypto.random(16))
        val r = rig.admitter.handle(JoinClient(guess, n.identity, n.nodeId, n.name).request, T0)
        assertEquals(Refusal.WRONG_CODE, refusal(r))
        assertEquals(1, rig.admin.log.size)
        assertTrue(rig.asked.isEmpty())
        // a code for another cloud does not work either
        val other = Invite(Crypto.random(16), real.nodeId, real.fingerprint, real.addresses, real.port, real.code)
        assertEquals(Refusal.WRONG_CODE, refusal(rig.admitter.handle(JoinClient(other, n.identity, n.nodeId, n.name).request, T0)))
    }

    @Test fun aReusedCodeIsRefusedForAnotherKeyButAnswersTheSameKeyAgain() {
        val rig = Rig()
        val first = Newcomer(); val second = Newcomer()
        val inv = rig.invite()
        val c1 = JoinClient(inv, first.identity, first.nodeId, first.name)
        assertEquals(JoinStatus.OK, answer(rig.admitter.handle(c1.request, T0)).status)
        val size = rig.admin.log.size
        // somebody who learnt the code too (a photograph) is late
        val c2 = JoinClient(inv, second.identity, second.nodeId, second.name)
        assertEquals(Refusal.USED, refusal(rig.admitter.handle(c2.request, T0 + 1)))
        assertEquals(size, rig.admin.log.size)
        // the first newcomer lost the answer and asks again with the same bytes, or with a new request
        val again = answer(rig.admitter.handle(c1.request, T0 + 2))
        assertEquals(JoinStatus.ALREADY_MEMBER, again.status)
        assertTrue(c1.finish(again.bytes) is JoinOutcome.Joined)
        val fresh = JoinClient(inv, first.identity, first.nodeId, first.name)
        assertEquals(JoinStatus.ALREADY_MEMBER, answer(rig.admitter.handle(fresh.request, T0 + 3)).status)
        assertEquals(size, rig.admin.log.size) // no second entry
        assertEquals(1, rig.asked.size) // and the person was asked once
    }

    @Test fun aCodeExpiresAfterTenMinutes() {
        val rig = Rig()
        val n = Newcomer()
        val inv = rig.invite(now = T0)
        val last = JoinClient(inv, n.identity, n.nodeId, n.name)
        assertEquals(Refusal.EXPIRED, refusal(rig.admitter.handle(last.request, T0 + Invite.LIFETIME_MS)))
        val other = Newcomer()
        val ok = JoinClient(inv, other.identity, other.nodeId, other.name)
        assertEquals(JoinStatus.OK, answer(rig.admitter.handle(ok.request, T0 + Invite.LIFETIME_MS - 1)).status)
        // once the record is forgotten (an hour later) the code is simply unknown
        val late = Newcomer()
        val c = JoinClient(inv, late.identity, late.nodeId, late.name)
        assertEquals(Refusal.WRONG_CODE, refusal(rig.admitter.handle(c.request, T0 + Invite.LIFETIME_MS + InviteBook.RETAIN_MS + 1)))
        assertEquals(2, rig.admin.log.size)
    }

    @Test fun aMemberJoiningAgainGetsTheListWithoutANewEntry() {
        val rig = Rig()
        val n = Newcomer()
        val c1 = JoinClient(rig.invite(), n.identity, n.nodeId, n.name)
        rig.admitter.handle(c1.request, T0)
        val size = rig.admin.log.size
        val c2 = JoinClient(rig.invite(), n.identity, n.nodeId, n.name) // it lost its data and has a new invitation
        val res = answer(rig.admitter.handle(c2.request, T0 + 5))
        assertEquals(JoinStatus.ALREADY_MEMBER, res.status)
        assertTrue(c2.finish(res.bytes) is JoinOutcome.Joined)
        assertEquals(size, rig.admin.log.size)
    }

    @Test fun aRemovedKeyCanNeverJoinAgain() {
        val rig = Rig()
        val n = Newcomer()
        val c1 = JoinClient(rig.invite(), n.identity, n.nodeId, n.name)
        rig.admitter.handle(c1.request, T0)
        rig.admin.remove(n.identity.memberId())
        val size = rig.admin.log.size
        val c2 = JoinClient(rig.invite(), n.identity, n.nodeId, n.name)
        val res = answer(rig.admitter.handle(c2.request, T0 + 5))
        assertEquals(JoinStatus.REMOVED, res.status)
        val out = c2.finish(res.bytes)
        assertEquals(JoinStatus.REMOVED, (out as JoinOutcome.Rejected).status)
        assertEquals(size, rig.admin.log.size)
        assertEquals(1, rig.asked.size) // nobody was asked about a removed key
        // a reinstalled device has a new key (and may keep its node id): it joins
        val reinstalled = Newcomer(nodeId = n.nodeId)
        val c3 = JoinClient(rig.invite(), reinstalled.identity, reinstalled.nodeId, reinstalled.name)
        assertTrue(c3.finish(answer(rig.admitter.handle(c3.request, T0 + 9)).bytes) is JoinOutcome.Joined)
    }

    @Test fun aNodeIdHeldByAnotherKeyIsRefusedAndADeclinedPersonLeavesTheCodeUsable() {
        val rig = Rig()
        val a = Newcomer()
        rig.admitter.handle(JoinClient(rig.invite(), a.identity, a.nodeId, a.name).request, T0)
        val clash = Newcomer(nodeId = a.nodeId)
        val cc = JoinClient(rig.invite(), clash.identity, clash.nodeId, clash.name)
        val taken = answer(rig.admitter.handle(cc.request, T0 + 1))
        assertEquals(JoinStatus.NODE_ID_TAKEN, taken.status)
        assertEquals(JoinStatus.NODE_ID_TAKEN, (cc.finish(taken.bytes) as JoinOutcome.Rejected).status)

        rig.approve = false
        val d = Newcomer()
        val cd = JoinClient(rig.invite(), d.identity, d.nodeId, d.name)
        val declined = answer(rig.admitter.handle(cd.request, T0 + 2))
        assertEquals(JoinStatus.DECLINED, declined.status)
        assertEquals(JoinStatus.DECLINED, (cd.finish(declined.bytes) as JoinOutcome.Rejected).status)
        rig.approve = true // the person thinks again and the same request now goes through
        assertEquals(JoinStatus.OK, answer(rig.admitter.handle(cd.request, T0 + 3)).status)
    }

    @Test fun aClientCanBeInvitedButABrowserCannot() {
        val rig = Rig()
        val n = Newcomer()
        val c = JoinClient(rig.invite(MemberKind.CLIENT), n.identity, n.nodeId, n.name)
        val out = c.finish(answer(rig.admitter.handle(c.request, T0)).bytes) as JoinOutcome.Joined
        assertEquals(MemberKind.CLIENT, out.log.state().member(n.identity.memberId())!!.kind)
        var refused = false
        try { rig.invite(MemberKind.BROWSER) } catch (e: IllegalArgumentException) { refused = true }
        assertTrue(refused)
    }

    @Test fun atMostEightInvitationsAreOpenAtOnce() {
        val rig = Rig()
        repeat(8) { rig.invite() }
        var refused = false
        try { rig.invite() } catch (e: IllegalStateException) { refused = true }
        assertTrue(refused)
        rig.invite(now = T0 + Invite.LIFETIME_MS) // the old ones have expired
    }

    // ---- attacks on the exchange -----------------------------------------------------------------------------------------

    private fun sealedRequest(inv: Invite, plain: ByteArray, nc: ByteArray = Crypto.random(32)): ByteArray =
        Writer().u8(1).fixed(nc, 32).raw(Frames.sealOnce(JoinWire.c2aKey(inv.code, inv.cloudId, nc), 0, inv.cloudId, FrameType.JOIN, plain)).bytes()

    @Test fun aRequestThatDoesNotProveItsKeyIsRefused() {
        val rig = Rig()
        val inv = rig.invite()
        val victim = Newcomer(); val thief = Newcomer()
        val nc = Crypto.random(32)
        // offers the victim's public key but signs with its own
        val sig = thief.identity.sign(JoinWire.pop(inv.cloudId, nc, thief.nodeId, "x", victim.identity.publicKey, inv.nodeId))
        val plain = Writer().u8(1).str(thief.nodeId).str("x").fixed(victim.identity.publicKey, 65).fixed(sig, 64).bytes()
        assertEquals(Refusal.BAD_PROOF, refusal(rig.admitter.handle(sealedRequest(inv, plain, nc), T0)))
        // a proof made for another admitter (replayed from a join elsewhere)
        val sig2 = thief.identity.sign(JoinWire.pop(inv.cloudId, nc, thief.nodeId, "x", thief.identity.publicKey, "deadbeef"))
        val plain2 = Writer().u8(1).str(thief.nodeId).str("x").fixed(thief.identity.publicKey, 65).fixed(sig2, 64).bytes()
        assertEquals(Refusal.BAD_PROOF, refusal(rig.admitter.handle(sealedRequest(inv, plain2, nc), T0)))
        assertEquals(1, rig.admin.log.size)
    }

    @Test fun garbageIsRefusedWithoutEffect() {
        val rig = Rig()
        val inv = rig.invite()
        assertEquals(Refusal.MALFORMED, refusal(rig.admitter.handle(ByteArray(5), T0)))
        assertEquals(Refusal.MALFORMED, refusal(rig.admitter.handle(ByteArray(40) { 2 }, T0)))
        assertEquals(Refusal.WRONG_CODE, refusal(rig.admitter.handle(Writer().u8(1).fixed(ByteArray(32), 32).raw(Crypto.random(80)).bytes(), T0)))
        assertEquals(Refusal.MALFORMED, refusal(rig.admitter.handle(sealedRequest(inv, ByteArray(10)), T0))) // the right code, a nonsense body
        assertEquals(1, rig.admin.log.size)
    }

    @Test fun aChangedOrForeignAnswerIsRefusedByTheNewcomer() {
        val rig = Rig()
        val n = Newcomer()
        val client = JoinClient(rig.invite(), n.identity, n.nodeId, n.name)
        val res = answer(rig.admitter.handle(client.request, T0))
        val flipped = res.bytes.copyOf().also { it[100] = (it[100].toInt() xor 1).toByte() }
        assertError(CloudError.BAD_FRAME) { client.finish(flipped) }
        assertError(CloudError.BAD_FRAME) { client.finish(res.bytes.copyOf(res.bytes.size - 1)) }
        // an answer recorded from another newcomer's join does not open for this one (the keys differ)
        val m = Newcomer()
        val other = JoinClient(rig.invite(), m.identity, m.nodeId, m.name)
        val foreign = answer(rig.admitter.handle(other.request, T0 + 1))
        assertError(CloudError.BAD_FRAME) { client.finish(foreign.bytes) }
        assertTrue(client.finish(res.bytes) is JoinOutcome.Joined)
    }

    @Test fun aNewcomerRefusesAnAnswerFromSomeoneWhoIsNotTheAdmitterNamedInTheQrCode() {
        val rig = Rig()
        val n = Newcomer()
        val real = rig.invite()
        val imposter = Dev.bare()
        // the QR code names a different fingerprint than the key that really made the cloud
        val inv = Invite(real.cloudId, real.nodeId, Crypto.fingerprint(imposter.identity.publicKey), real.addresses, real.port, real.code)
        val client = JoinClient(inv, n.identity, n.nodeId, n.name)
        val res = answer(rig.admitter.handle(client.request, T0))
        assertError(CloudError.JOIN_REFUSED) { client.finish(res.bytes) }
        // and a node id that is not the admitter's
        val inv2 = Invite(real.cloudId, "deadbeef", real.fingerprint, real.addresses, real.port, real.code)
        val c2 = JoinClient(inv2, Newcomer().identity, Dev.newNodeId(), "x")
        // (the proof names the wrong admitter, so the admitter refuses before it answers)
        assertEquals(Refusal.BAD_PROOF, refusal(rig.admitter.handle(c2.request, T0)))
    }

    @Test fun everyAnswerUsesFreshKeysSoAChangedAnswerNeverReusesANonce() {
        val rig = Rig()
        val n = Newcomer()
        val client = JoinClient(rig.invite(), n.identity, n.nodeId, n.name)
        val a1 = answer(rig.admitter.handle(client.request, T0))
        val a2 = answer(rig.admitter.handle(client.request, T0 + 1)) // the same request, answered again (status 0 then 1)
        assertNotEquals(Hex.of(a1.bytes.copyOfRange(1, 33)), Hex.of(a2.bytes.copyOfRange(1, 33))) // the admitter's nonce differs
        assertFalse(a1.bytes.contentEquals(a2.bytes))
    }
}
