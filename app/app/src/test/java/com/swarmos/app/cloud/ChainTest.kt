package com.swarmos.app.cloud

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainTest {
    private fun offer(c: Chain, vararg run: ChainEntry) = c.offer(run.toList())
    private fun ids(c: Chain) = c.state().members.map { it.id }.toSet()
    private fun refused(o: Offer) = o is Offer.Refused

    // ---- the genesis and the operations --------------------------------------------------------------------------------

    @Test fun aNewCloudHasOneMemberTheOwnerAndTwoDistinctKeys() {
        val a = Cloud.create(); val b = Cloud.create()
        val s = a.state
        assertEquals(16, a.chain.cloudId.size)
        assertFalse(a.chain.cloudId.contentEquals(b.chain.cloudId)) // the seed makes it random
        assertEquals(setOf(a.owner.id), ids(a.chain))
        assertEquals(a.owner.id, s.ownerId)
        assertFalse(s.ownerKey.contentEquals(s.recoveryKey))
        assertEquals(0, s.epoch); assertEquals(1L, s.nextSeq); assertFalse(s.needsRotation)
        assertTrue(MemberKind.NODE == s.member(a.owner.id)!!.kind)
        var refused = false
        try { Chain.create(a.owner.identity, a.recovery.publicKey, "0a0b0c0d", "x", EpochSecrets.generate(3), T0) } catch (e: IllegalArgumentException) { refused = true }
        assertTrue(refused)
    }

    @Test fun everyOperationAppliesAndTheEntriesRoundTripOnTheWire() {
        val (c, peers) = cloudWith(2)
        val p = peers[0]
        val kinds = c.chain.entries().map { it.op }
        assertEquals(listOf(ChainEntry.GENESIS, ChainEntry.ADD, ChainEntry.ADD), kinds)
        val e = c.chain.entries().last()
        val back = ChainEntry.parse(e.encode())
        assertEquals(e.id, back.id); assertEquals(e.seq, back.seq)
        // ids ignore the signature: the same body signed again is the same entry
        assertEquals(e.id, ChainEntry.sign(c.owner.identity, e.body).id)
        // the others
        val s1 = EpochSecrets.generate(1)
        c.remove(p.id); assertTrue(c.state.needsRotation)
        c.chain.rotate(c.owner.identity, s1, T0 + 2)
        assertFalse(c.state.needsRotation); assertEquals(1, c.state.epoch)
        assertEquals("4:" + Hex.of(c.state.headId.copyOf(8)), c.chain.heartbeat())
        // a replayed copy of the whole chain at a member changes nothing
        assertTrue(Chain.fromEntries(c.chain.entries()).heartbeat() == c.chain.heartbeat())
    }

    @Test fun aRemovalIsFinalAndTheOwnerCannotAddTheKeyAgain() {
        val (c, peers) = cloudWith(1)
        val p = peers[0]
        c.remove(p.id)
        assertNull(c.state.member(p.id)); assertTrue(c.state.isRemoved(p.id))
        assertError(CloudError.BAD_ENTRY) { c.admit(p) } // the same key again
        assertError(CloudError.BAD_ENTRY) { c.chain.addDevice(c.owner.identity, Peer.newNodeId(), "renamed", MemberKind.NODE, p.identity.publicKey, T0) }
        assertError(CloudError.BAD_ENTRY) { c.chain.remove(c.owner.identity, p.id, T0) } // not a member any more
        // the owner key itself cannot be removed (it transfers first)
        assertError(CloudError.BAD_ENTRY) { c.chain.remove(c.owner.identity, c.owner.id, T0) }
        // a reinstalled device has a new key and may keep its node id once the old entry is gone
        val fresh = Peer.bare()
        val again = Peer(fresh.identity, p.nodeId, fresh.chain)
        c.admit(again)
        assertNotNull(c.state.member(again.id))
    }

    @Test fun aNodeIdBelongsToOneCurrentMemberOnly() {
        val (c, peers) = cloudWith(1)
        val clash = Peer(SoftwareIdentity.generate(), peers[0].nodeId, peers[0].chain)
        assertError(CloudError.BAD_ENTRY) { c.admit(clash) }
    }

    // ---- who may write (Codex MemberLog.kt:232, :233, :270, :327, :331, :359 and MemberLogTest.kt:49) ------------------------

    @Test fun onlyTheOwnerKeyWritesNodesClientsRemovedMembersAndStrangersAreRefused() {
        val (c, peers) = cloudWith(2)
        val node = peers[0]
        val client = Peer.bare(); c.admit(client, MemberKind.CLIENT)
        val removed = peers[1]; c.remove(removed.id)
        val member = c.copy()
        val s = member.state()
        val stranger = SoftwareIdentity.generate()
        for ((who, label) in listOf(node.identity to "node", client.identity to "client", removed.identity to "removed", stranger to "stranger")) {
            val add = forgedAdd(who, s, Peer.newNodeId(), SoftwareIdentity.generate().publicKey)
            val rem = ChainEntry.sign(who, ChainEntry.removeBody(s, T0, c.owner.id))
            val rot = ChainEntry.sign(who, ChainEntry.rotateBody(s, T0, s.epoch + 1, ByteArray(32)))
            for (e in listOf(add, rem, rot)) assertTrue("$label: ${e.op}", refused(offer(member, e)))
        }
        assertEquals(c.chain.heartbeat(), member.heartbeat()) // nothing was taken
        // writing through the API needs the owner key too
        var refusedWrite = false
        try { member.addDevice(node.identity, Peer.newNodeId(), "x", MemberKind.NODE, stranger.publicKey, T0) } catch (e: IllegalStateException) { refusedWrite = true }
        assertTrue(refusedWrite)
    }

    @Test fun aFloodOfForgedEntriesFillsNothingAndBlocksNoRemoval() { // Codex :232 :270 :327
        val (c, peers) = cloudWith(2)
        val attacker = peers[0]
        val member = c.copy()
        repeat(300) { i ->
            val e = if (i % 2 == 0) forgedAdd(attacker.identity, member.state(), Peer.newNodeId(), SoftwareIdentity.generate().publicKey)
            else ChainEntry.sign(attacker.identity, ChainEntry.removeBody(member.state(), T0, "ab".repeat(32)))
            assertTrue(refused(offer(member, e)))
        }
        assertEquals(c.chain.heartbeat(), member.heartbeat())
        // the owner removes the attacker at once; nothing was in the way
        val removal = c.removeAndRotate(attacker.id)
        assertTrue(offer(member, *removal.toTypedArray()) is Offer.Adopted)
        assertNull(member.state().member(attacker.id))
    }

    @Test fun aRemoversRemovalCannotBeUndoneByTheRemovedOrBySomeoneElse() { // Codex :331 and :359
        val (c, peers) = cloudWith(3)
        val (thief, bad, other) = peers
        val stale = thief.chain // the thief's copy from before it was removed
        c.removeAndRotate(thief.id)
        c.removeAndRotate(bad.id)
        // the thief signs a removal of the owner and one of "other" from its stale chain; so does a member that is not the owner
        val s = stale.state()
        val counter = ChainEntry.sign(thief.identity, ChainEntry.removeBody(s, T0, c.owner.id))
        val byOther = ChainEntry.sign(other.identity, ChainEntry.removeBody(s, T0, c.owner.id))
        val member = c.copy()
        assertTrue(refused(offer(member, counter))); assertTrue(refused(offer(member, byOther)))
        assertTrue(refused(stale.offer(listOf(counter)))) // even on its own chain it is not the owner
        assertEquals(setOf(c.owner.id, other.id), ids(member)) // both removals stand
        assertTrue(member.state().isRemoved(thief.id) && member.state().isRemoved(bad.id))
    }

    // ---- accepting a chain: extension, gap, fork -------------------------------------------------------------------------------

    @Test fun aLongerChainThatExtendsOursIsAdoptedAndAShorterOneChangesNothing() {
        val (c, peers) = cloudWith(1)
        val member = c.copy()
        val e1 = c.admit(Peer.bare()); val e2 = c.admit(Peer.bare())
        val o = offer(member, e1, e2)
        assertEquals(2, (o as Offer.Adopted).count)
        assertTrue(offer(member, e1) is Offer.Unchanged)
        assertTrue(member.offer(c.chain.entries().take(2)) is Offer.Unchanged)
        assertEquals(c.chain.heartbeat(), member.heartbeat())
        assertNotNull(peers)
    }

    @Test fun aGapOrAnEntryOfAnotherBranchIsRefused() {
        val (c, _) = cloudWith(1)
        val member = c.copy()
        val alt = c.copy()
        val e1 = c.admit(Peer.bare()); val e2 = c.admit(Peer.bare()); val e3 = c.admit(Peer.bare())
        assertTrue(refused(offer(member, e2, e3))) // starts beyond our head
        assertTrue(refused(offer(member, e1, e3))) // an entry missing in the middle
        // an entry of a branch the owner never wrote here: right number, wrong predecessor
        val b1 = alt.addDevice(c.owner.identity, Peer.newNodeId(), "alt", MemberKind.NODE, SoftwareIdentity.generate().publicKey, T0)
        assertTrue(refused(offer(member, b1, e2)))
        assertEquals(c.chain.entries().size - 3, member.entries().size)
    }

    @Test fun twoEntriesAtOneNumberFromTheOwnerKeyAreEquivocationAndTheFirstStays() {
        val (c, _) = cloudWith(1)
        val m1 = c.copy(); val m2 = c.copy(); val alt = c.copy()
        val a = c.admit(Peer.bare(), now = T0)
        val b = alt.addDevice(c.owner.identity, Peer.newNodeId(), "second", MemberKind.NODE, SoftwareIdentity.generate().publicKey, T0 + 5)
        assertNotEquals(a.id, b.id); assertEquals(a.seq, b.seq)
        offer(m1, a)
        val o = offer(m1, b)
        assertTrue(o is Offer.Equivocation)
        assertEquals(a.id, m1.entries().last().id) // we keep what we had
        assertEquals(1, m1.flags().size)
        offer(m1, b); assertEquals(1, m1.flags().size) // one flag per fork
        offer(m2, b); assertTrue(offer(m2, a) is Offer.Equivocation) // the other order: the other stays
        assertEquals(b.id, m2.entries().last().id)
        assertEquals(m1.flags()[0].heldId, a.id); assertEquals(m1.flags()[0].otherId, b.id)
    }

    @Test fun anEntryByTheOldOwnerAfterATransferIsRefusedAndAtTheSameNumberIsAFork() {
        val (c, peers) = cloudWith(1)
        val n = peers[0]
        val old = c.owner.identity
        val member = c.copy(); val alt = c.copy()
        val t = c.chain.transfer(old, n.identity.publicKey, n.nodeId, "new owner", T0 + 1)
        val rival = alt.addDevice(old, Peer.newNodeId(), "rival", MemberKind.NODE, SoftwareIdentity.generate().publicKey, T0 + 1)
        assertTrue(offer(member, t) is Offer.Adopted)
        assertEquals(n.id, member.state().ownerId)
        assertTrue(offer(member, rival) is Offer.Equivocation) // same number as the transfer
        val late = ChainEntry.sign(old, ChainEntry.addBody(member.state(), T0, Peer.newNodeId(), "late", MemberKind.NODE, SoftwareIdentity.generate().publicKey, null, null))
        assertTrue(refused(offer(member, late))) // the old key is no longer the owner
        // the new owner writes
        member.addDevice(n.identity, Peer.newNodeId(), "ok", MemberKind.NODE, SoftwareIdentity.generate().publicKey, T0 + 2)
    }

    // ---- the recovery key -----------------------------------------------------------------------------------------------------

    private fun recoveryRun(base: Chain, c: Cloud, forkAt: Long = base.state().nextSeq, newOwner: Peer = Peer.bare(), newRecovery: SoftwareIdentity = SoftwareIdentity.generate()): Triple<Chain, ChainEntry, Peer> {
        val copy = Chain.fromEntries(base.entries())
        val e = copy.recover(c.recovery, newOwner.identity.publicKey, newOwner.nodeId, "recovered", newRecovery.publicKey, T0 + 50, forkAt)
        return Triple(copy, e, newOwner)
    }

    @Test fun aRecoveryTransferWinsOverAnOwnerEntryAtTheSameNumber() {
        val (c, peers) = cloudWith(1)
        val member = peers[0].chain
        val thief = c.copy() // the stolen owner phone, still the owner key
        val bad = Peer.bare()
        val xAdd = thief.addDevice(c.owner.identity, bad.nodeId, "thief's device", MemberKind.NODE, bad.identity.publicKey, T0 + 10)
        assertTrue(offer(member, xAdd) is Offer.Adopted) // the thief got to the member first
        assertNotNull(member.state().member(bad.id))
        val (_, r, p2) = recoveryRun(c.chain, c) // the real owner, with the recovery code, on a new device that never saw xAdd
        assertEquals(xAdd.seq, r.seq)
        val o = offer(member, r)
        assertTrue(o is Offer.RolledBack)
        assertEquals(1, (o as Offer.RolledBack).dropped)
        assertEquals(p2.id, member.state().ownerId)
        assertNull(member.state().member(bad.id)) // the thief's device is gone
        assertEquals(xAdd.id, member.dropped().single().id) // kept so the new owner can see what was undone
        assertTrue(member.state().needsRotation) // the old owner phone is not trusted any more
    }

    @Test fun aRecoveryTransferAlsoUndoesATheftThatRanSeveralEntriesAndAChangeOfOwner() {
        val (c, peers) = cloudWith(1)
        val member = peers[0].chain
        val thief = c.copy()
        val t2 = Peer.bare()
        val x = thief.transfer(c.owner.identity, t2.identity.publicKey, t2.nodeId, "thief owner", T0 + 10)
        val y = thief.addDevice(t2.identity, Peer.newNodeId(), "more", MemberKind.NODE, SoftwareIdentity.generate().publicKey, T0 + 11)
        val z = thief.remove(t2.identity, peers[0].id, T0 + 12) // the thief removes the real member
        assertTrue(offer(member, x, y, z) is Offer.Adopted)
        assertEquals(t2.id, member.state().ownerId)
        val (_, r, p2) = recoveryRun(c.chain, c) // fork at x's number
        assertTrue(offer(member, r) is Offer.RolledBack)
        assertEquals(p2.id, member.state().ownerId)
        assertEquals(3, member.dropped().size)
        assertNotNull(member.state().member(peers[0].id)) // the removal by the thief is undone
        assertEquals(c.chain.entries().size + 1, member.entries().size)
    }

    @Test fun anOwnerEntryDoesNotReplaceAHeldRecoveryTransferAndTwoRecoveryTransfersFork() {
        val (c, peers) = cloudWith(1)
        val member = peers[0].chain
        val thief = c.copy()
        val xAdd = thief.addDevice(c.owner.identity, Peer.newNodeId(), "late thief", MemberKind.NODE, SoftwareIdentity.generate().publicKey, T0 + 10)
        val (_, r, _) = recoveryRun(c.chain, c)
        assertTrue(offer(member, r) is Offer.Adopted)
        assertTrue(offer(member, xAdd) is Offer.Equivocation) // the thief arrives second and loses
        assertEquals(r.id, member.entries().last().id)
        val (_, r2, _) = recoveryRun(c.chain, c) // a second, different recovery transfer at the same number
        assertNotEquals(r.id, r2.id)
        assertTrue(offer(member, r2) is Offer.Equivocation) // the first seen stays
    }

    @Test fun theOwnerKeyCannotChangeTheRecoveryKeyAndTheRecoveryKeyOnlySignsTransfers() {
        val (c, peers) = cloudWith(1)
        val member = c.copy()
        val s = member.state()
        val n = peers[0]
        val sneaky = ChainEntry.sign(c.owner.identity, ChainEntry.transferBody(s, 1, T0, n.identity.publicKey, n.nodeId, "n", SoftwareIdentity.generate().publicKey))
        assertTrue(refused(offer(member, sneaky))) // a thief cannot lock the real owner out
        // a plain owner transfer keeps the recovery key, so the recovery code still works afterwards
        val t = member.transfer(c.owner.identity, n.identity.publicKey, n.nodeId, "n", T0 + 1)
        assertArrayEquals(c.state.recoveryKey, member.state().recoveryKey)
        val chain2 = Chain.fromEntries(member.entries())
        chain2.recover(c.recovery, SoftwareIdentity.generate().publicKey, Peer.newNodeId(), "back", SoftwareIdentity.generate().publicKey, T0 + 2)
        assertNotNull(t)
        // role 2 on any other operation does not even parse
        val addBody = ChainEntry.addBody(s, T0, Peer.newNodeId(), "x", MemberKind.NODE, SoftwareIdentity.generate().publicKey, null, null)
        assertError(CloudError.MALFORMED) { ChainEntry.of(addBody.copyOf().also { it[4 + 15 + 1] = 2 }, ByteArray(64)) }
        // a recovery transfer must bring a new recovery key
        val same = ChainEntry.sign(c.recovery, ChainEntry.transferBody(s, 2, T0, n.identity.publicKey, n.nodeId, "n", c.recovery.publicKey))
        assertTrue(refused(offer(c.copy(), same)))
        val none = ChainEntry.sign(c.recovery, ChainEntry.transferBody(s, 2, T0, n.identity.publicKey, n.nodeId, "n", null))
        assertTrue(refused(offer(c.copy(), none)))
    }

    // ---- checkpoints --------------------------------------------------------------------------------------------------------

    // ---- rotate, tokens, limits, sync --------------------------------------------------------------------------------------

    @Test fun aRotateFollowsEveryRemovalAndOnlyTheNextEpochIsAccepted() {
        val (c, peers) = cloudWith(2)
        val member = c.copy()
        val entries = c.removeAndRotate(peers[0].id)
        assertEquals(listOf(ChainEntry.REMOVE, ChainEntry.ROTATE), entries.map { it.op })
        assertTrue(offer(member, *entries.toTypedArray()) is Offer.Adopted)
        assertEquals(1, member.state().epoch); assertFalse(member.state().needsRotation)
        assertTrue(member.state().commitment(1)!!.contentEquals(c.secrets[1].commitment()))
        assertNotNull(member.state().commitment(0)) // the previous epoch's commitment is kept
        assertError(CloudError.BAD_ENTRY) { c.chain.rotate(c.owner.identity, EpochSecrets.generate(5), T0) } // not epoch + 1
        assertError(CloudError.BAD_ENTRY) { c.chain.rotate(c.owner.identity, EpochSecrets.generate(1), T0) } // not the next any more
        c.chain.rotate(c.owner.identity, EpochSecrets.generate(2), T0 + 5)
        assertEquals(2, c.state.epoch); assertNull(c.state.commitment(0)) // only two are kept
    }

    @Test fun tokenClientsBelongToTheirIssuerAndDieWithIt() {
        val (c, peers) = cloudWith(2)
        val issuer = peers[0]
        val keyId = Crypto.random(16)
        val e = c.chain.addToken(c.owner.identity, issuer.id, Peer.newNodeId(), "script", MemberKind.CLIENT, keyId, T0 + 1)
        val tokenId = ChainRules.tokenMemberId(issuer.id, keyId)
        assertNotNull(c.state.member(tokenId)); assertEquals(issuer.id, c.state.member(tokenId)!!.issuer)
        assertNotNull(e)
        assertError(CloudError.BAD_ENTRY) { c.chain.addToken(c.owner.identity, issuer.id, Peer.newNodeId(), "again", MemberKind.CLIENT, keyId, T0) } // same token twice
        val client = Peer.bare(); c.admit(client, MemberKind.CLIENT)
        assertError(CloudError.BAD_ENTRY) { c.chain.addToken(c.owner.identity, client.id, Peer.newNodeId(), "x", MemberKind.BROWSER, Crypto.random(16), T0) } // issuer must be a node
        c.remove(issuer.id)
        assertNull(c.state.member(tokenId)); assertTrue(c.state.isRemoved(tokenId))
        // wire shape: a node with a token key or a browser with a device key does not parse
        val s = c.state
        val bad1 = ChainEntry.addBody(s, T0, Peer.newNodeId(), "x", MemberKind.NODE, null, Crypto.random(32), Crypto.random(16))
        val bad2 = ChainEntry.addBody(s, T0, Peer.newNodeId(), "x", MemberKind.BROWSER, SoftwareIdentity.generate().publicKey, null, null)
        assertError(CloudError.MALFORMED) { ChainEntry.of(bad1, ByteArray(64)) }
        assertError(CloudError.MALFORMED) { ChainEntry.of(bad2, ByteArray(64)) }
    }

    @Test fun syncPullsTheTailOrTheWholeChainWhenBranchesDiffer() {
        val (c, _) = cloudWith(1)
        val member = c.copy()
        val e1 = c.admit(Peer.bare()); c.admit(Peer.bare())
        val tail = c.chain.entriesFor(ChainPull.parse(member.pullRequest().encode()))
        assertEquals(2, tail.size); assertEquals(e1.id, tail[0].id)
        assertTrue(offer(member, *tail.toTypedArray()) is Offer.Adopted)
        // a device on another branch (same number, other id) is sent a page from its cursor: the base for a first request
        val other = Peer.bare().chain
        assertEquals(c.chain.entries().size, c.chain.entriesFor(other.pullRequest()).size)
        assertEquals(c.chain.entries().size - 2, c.chain.entriesFor(other.pullRequest(cursor = 2)).size)
        assertTrue(c.chain.entriesFor(other.pullRequest(cursor = 99)).isEmpty())
        assertError(CloudError.MALFORMED) { ChainPull.parse(other.pullRequest().encode() + byteArrayOf(0)) }
        assertTrue(EntryRun.decode(Reader(EntryRun.encode(tail))).map { it.id } == tail.map { it.id })
    }

    @Test fun aRunThatIsNotContiguousOrIsTooLongIsRefusedWhole() {
        val (c, _) = cloudWith(0)
        val member = c.copy()
        val a = c.admit(Peer.bare()); val b = c.admit(Peer.bare())
        assertTrue(refused(offer(member, b, a)))
        assertTrue(refused(member.offer(List(Chain.MAX_RUN + 1) { a })))
        assertTrue(offer(member) is Offer.Unchanged)
    }

    // ---- stage 1c: no checkpoints, the recovery boundary, bounded evidence and bounded work -----------------------------------

    private fun newKey() = SoftwareIdentity.generate().publicKey

    @Test fun aNewcomerChecksTheWholeChainFromTheGenesisWhoseHashIsTheCloudId() { // Codex Chain.kt:572 (blocker)
        val (c, _) = cloudWith(2)
        val ok = Chain.fromEntries(c.chain.entries(), c.chain.cloudId)
        assertEquals(c.chain.heartbeat(), ok.heartbeat())
        // a host builds a chain of its own: its own genesis, itself as owner, the newcomer as a member
        val host = Peer.bare(); val newcomer = Peer.bare()
        host.chain.addDevice(host.identity, newcomer.nodeId, "n", MemberKind.NODE, newcomer.identity.publicKey, T0)
        assertError(CloudError.BAD_ENTRY) { Chain.fromEntries(host.chain.entries(), c.chain.cloudId) } // its genesis does not hash to the invitation's cloud id
        // the real genesis followed by an entry the owner never signed
        val forged = ChainEntry.sign(host.identity, ChainEntry.addBody(c.state, T0, newcomer.nodeId, "n", MemberKind.NODE, newcomer.identity.publicKey, null, null))
        assertError(CloudError.BAD_ENTRY) { Chain.fromEntries(c.chain.entries() + forged, c.chain.cloudId) }
        // nothing but a genesis can start a chain, there is no checkpoint, and the cap is enforced
        assertError(CloudError.BAD_ENTRY) { Chain.fromEntries(c.chain.entries().drop(1), c.chain.cloudId) }
        val g = c.chain.entries()[0]
        assertError(CloudError.MALFORMED) { ChainEntry.of(g.body.copyOf().also { it[19] = 7 }, ByteArray(64)) } // operation 7 does not exist
        assertError(CloudError.BAD_ENTRY) { Chain.fromEntries(List(ChainEntry.MAX_ENTRIES + 1) { g }) }
    }

    @Test fun aRunThatSkipsAheadIsRefusedSoNoHistoryIsDiscardedAndAFormerlyRemovedMemberStaysRemoved() { // Codex Chain.kt:452 and :457
        val (c, peers) = cloudWith(2)
        val member = peers[0].chain
        val thief = c.copy() // the owner key in a thief's hands
        val run = List(5) { thief.addDevice(c.owner.identity, Peer.newNodeId(), "t$it", MemberKind.NODE, newKey(), T0 + it) }
        assertTrue(refused(offer(member, run.last()))) // only the last entry, far beyond the head: a gap, nothing is dropped
        assertEquals(c.chain.heartbeat(), member.heartbeat())
        // the real owner can still undo the thief's entries afterwards, because the member kept its history
        val (_, r, p2) = recoveryRun(c.chain, c)
        assertTrue(offer(member, *run.toTypedArray()) is Offer.Adopted)
        assertTrue(offer(member, r) is Offer.RolledBack)
        assertEquals(p2.id, member.state().ownerId)
        // a removed member cannot be put back by any run: the only way in is an add, and an add of a removed key is refused
        val (c2, ps2) = cloudWith(1)
        val m2 = ps2[0].chain
        c2.removeAndRotate(ps2[0].id); m2.offer(c2.chain.entries())
        val back = ChainEntry.sign(c2.owner.identity, ChainEntry.addBody(m2.state(), T0, ps2[0].nodeId, "back", MemberKind.NODE, ps2[0].identity.publicKey, null, null))
        assertTrue(refused(offer(m2, back)))
        assertTrue(m2.state().isRemoved(ps2[0].id)); assertNull(m2.state().member(ps2[0].id))
    }

    @Test fun aRetiredRecoveryCodeCannotForkBelowTheBoundaryAndOnlyEquivocatesAtIt() { // Codex Chain.kt:421
        val (c, peers) = cloudWith(1)
        val member = peers[0].chain
        val beforeX = c.copy()
        val beforeX2 = c.copy()
        val x = c.admit(Peer.bare()) // an owner entry
        val (_, r, p2) = recoveryRun(c.chain, c) // the code R is used on the next number and replaced
        assertEquals(x.seq + 1, r.seq)
        assertTrue(offer(member, x, r) is Offer.Adopted)
        assertEquals(r.seq, member.recoveryBoundary())
        // someone who kept R signs a transfer at 3, where R was still in force: both branches carry a recovery entry after the fork, so
        // nothing moves; it is recorded as evidence (Codex Chain.kt:430, flag it), ownership does not move
        val undo = beforeX.recover(c.recovery, newKey(), Peer.newNodeId(), "undo", newKey(), T0 + 70)
        assertEquals(x.seq, undo.seq)
        assertTrue(offer(member, undo) is Offer.Equivocation)
        assertEquals(1, member.flags().size)
        assertEquals(p2.id, member.state().ownerId); assertEquals(r.id, member.entries().last().id)
        // at the tip R is not in force; at 4 itself it is a second recovery entry: equivocation, the first stays
        val atTip = ChainEntry.sign(c.recovery, ChainEntry.transferBody(member.state(), 2, T0, newKey(), Peer.newNodeId(), "x", newKey()))
        assertTrue(refused(offer(member, atTip)))
        val (_, r2, _) = recoveryRun(c.chain, c)
        assertTrue(offer(member, r2) is Offer.Equivocation)
        assertEquals(r.id, member.entries()[r.seq.toInt()].id)
        // an owner-key entry below the boundary is a fork between owners at most, never a rollback
        val alt = beforeX2.addDevice(c.owner.identity, Peer.newNodeId(), "alt", MemberKind.NODE, newKey(), T0 + 80)
        assertTrue(offer(member, alt) is Offer.Equivocation)
        assertEquals(r.id, member.entries()[r.seq.toInt()].id)
    }

    @Test fun theRecoveryCodeCanBeReplacedByItselfAndOnlyByItself() {
        val (c, peers) = cloudWith(1)
        val member = c.copy()
        val newCode = SoftwareIdentity.generate()
        val e = c.chain.replaceRecovery(c.recovery, newCode.publicKey, T0 + 5)
        assertEquals(ChainEntry.REPLACE_RECOVERY, e.op); assertEquals(2, e.role)
        assertTrue(offer(member, e) is Offer.Adopted)
        assertArrayEquals(newCode.publicKey, member.state().recoveryKey)
        assertEquals(e.seq, member.recoveryBoundary())
        assertFalse(member.state().needsRotation) // the owner keeps its secrets
        // the retired code signs nothing a member accepts now
        var refusedUse = false
        try { c.chain.replaceRecovery(c.recovery, newKey(), T0) } catch (x: IllegalStateException) { refusedUse = true }
        assertTrue(refusedUse)
        val old = ChainEntry.sign(c.recovery, ChainEntry.replaceRecoveryBody(member.state(), T0, newKey()))
        assertTrue(refused(offer(member, old)))
        // the new code works
        Chain.fromEntries(member.entries()).recover(newCode, newKey(), Peer.newNodeId(), "back", newKey(), T0 + 9)
        // the owner key, a node and a stranger cannot replace it: role 1 does not even parse; a signature by anyone else is refused
        val owner = ChainEntry.replaceRecoveryBody(member.state(), T0, newKey()).also { it[20] = 1 }
        assertError(CloudError.MALFORMED) { ChainEntry.of(owner, ByteArray(64)) }
        assertTrue(refused(offer(member, ChainEntry.sign(peers[0].identity, ChainEntry.replaceRecoveryBody(member.state(), T0, newKey())))))
        // the new key must be new, not the owner key, not a device key of a member
        assertError(CloudError.BAD_ENTRY) { c.chain.replaceRecovery(newCode, newCode.publicKey, T0) }
        assertError(CloudError.BAD_ENTRY) { c.chain.replaceRecovery(newCode, c.owner.identity.publicKey, T0) }
        assertError(CloudError.BAD_ENTRY) { c.chain.replaceRecovery(newCode, peers[0].identity.publicKey, T0) }
    }

    @Test fun aLeakedCodeThatNobodyUsedIsTheRootTwoReplacementsAtOneNumberFork() {
        val (c, _) = cloudWith(1)
        val member = c.copy(); val alt = c.copy()
        val owners = c.chain.replaceRecovery(c.recovery, newKey(), T0 + 1) // the real owner replaces the code it fears was seen
        val holder = alt.replaceRecovery(c.recovery, newKey(), T0 + 1) // so does whoever saw it, at the same number
        assertTrue(offer(member, owners) is Offer.Adopted)
        assertTrue(offer(member, holder) is Offer.Equivocation) // the first seen stays: the documented residue of open point 2
        assertEquals(owners.id, member.entries().last().id)
    }

    @Test fun evidenceOfForksIsOnePerNumberAndSignerAndTheListIsCapped() { // Codex Chain.kt:416
        val (c, peers) = cloudWith(1)
        val old = c.owner.identity
        val stateAfter = HashMap<Long, ChainState>()
        stateAfter[c.state.headSeq] = c.state
        repeat(70) { i ->
            c.chain.addDevice(old, Peer.newNodeId(), "d", MemberKind.NODE, newKey(), T0 + i)
            stateAfter[c.state.headSeq] = c.state
        }
        c.chain.transfer(old, peers[0].identity.publicKey, peers[0].nodeId, "new owner", T0 + 99) // the old key keeps authority for every earlier number
        val member = c.copy()
        fun altAt(f: Long) = ChainEntry.sign(old, ChainEntry.addBody(stateAfter.getValue(f - 1), T0 + 500, Peer.newNodeId(), "alt", MemberKind.NODE, newKey(), null, null))
        // 200 different alternatives at one number: one record, and after the first no replay at all
        assertTrue(offer(member, altAt(10)) is Offer.Equivocation)
        val replaysAfterFirst = member.replays
        repeat(200) { assertTrue(offer(member, altAt(10)) is Offer.Equivocation) }
        assertEquals(1, member.flags().size)
        assertEquals(replaysAfterFirst, member.replays)
        // alternatives at many numbers: the list stops at 64
        for (f in 3L..72L) offer(member, altAt(f))
        assertEquals(Chain.MAX_FLAGS, member.flags().size)
        assertTrue(offer(member, altAt(2)) is Offer.Equivocation) // still answered, no longer recorded
        assertEquals(Chain.MAX_FLAGS, member.flags().size)
        assertNotNull(peers)
    }

    @Test fun anOfferedEntryIsAuthenticatedBeforeAnyHistoryIsReplayed() { // Codex Chain.kt:414
        val (c, peers) = cloudWith(1)
        val stateBeforeLast = HashMap<Long, ChainState>()
        repeat(150) { c.chain.addDevice(c.owner.identity, Peer.newNodeId(), "d", MemberKind.NODE, newKey(), T0 + it); stateBeforeLast[c.state.headSeq] = c.state }
        val member = c.copy()
        val head = member.state().headSeq
        // junk that differs near the tip: signed by a member, not the owner
        repeat(300) {
            val junk = ChainEntry.sign(peers[0].identity, ChainEntry.addBody(stateBeforeLast.getValue(head - 1), T0, Peer.newNodeId(), "j", MemberKind.NODE, newKey(), null, null))
            assertTrue(refused(offer(member, junk)))
        }
        assertEquals(0, member.replays); assertEquals(0L, member.replayedEntries)
        assertTrue(member.flags().isEmpty())
    }

    @Test fun aValidForkIsCheckedAgainstAtMost63EntriesOfReplay() {
        val (c, _) = cloudWith(1)
        val states = HashMap<Long, ChainState>()
        repeat(200) { c.chain.addDevice(c.owner.identity, Peer.newNodeId(), "d", MemberKind.NODE, newKey(), T0 + it); states[c.state.headSeq] = c.state }
        val member = c.copy()
        val alt = ChainEntry.sign(c.owner.identity, ChainEntry.addBody(states.getValue(189), T0 + 900, Peer.newNodeId(), "alt", MemberKind.NODE, newKey(), null, null))
        assertEquals(190L, alt.seq)
        assertTrue(offer(member, alt) is Offer.Equivocation)
        assertEquals(1, member.replays)
        assertTrue("replayed ${member.replayedEntries}", member.replayedEntries in 1..63)
    }

    @Test fun theChainCapAndTheBodyLimitAreEnforcedByOneRuleEachWhenCreatingAndParsing() {
        val (c, _) = cloudWith(0)
        val full = c.state.copy(nextSeq = ChainEntry.MAX_ENTRIES.toLong())
        val e = ChainEntry.sign(c.owner.identity, ChainEntry.addBody(full, T0, Peer.newNodeId(), "x", MemberKind.NODE, newKey(), null, null))
        assertError(CloudError.BAD_ENTRY) { ChainRules.apply(full, e) } // the cap is a number only the owner could reach
        val over = ByteArray(ChainEntry.MAX_BODY + 1)
        assertError(CloudError.MALFORMED) { ChainEntry.of(over, ByteArray(64)) }
        assertError(CloudError.MALFORMED) { ChainEntry.parse(Writer().lp(over).fixed(ByteArray(64), 64).bytes()) }
        // the largest legitimate entry (a 64-byte name) is far below the limit
        val big = ChainEntry.addBody(c.state, T0, Peer.newNodeId(), "x".repeat(64), MemberKind.NODE, newKey(), null, null)
        assertTrue(big.size in 200..400)
        assertTrue(ChainEntry.MAX_BODY >= 4 * big.size)
    }

    // ---- stage 1d: two owner branches and a later recovery entry, the member slot, paging, the pre-membership path ----------------

    /** Two branches of one cloud that part at number 2 by owner entries; B goes on with a recovery transfer. */
    private inner class TwoBranches {
        val c = cloudWith(1).first
        val a = c.copy()
        val b = c.copy()
        val bBeforeRecovery: ChainState
        val recoveryEntry: ChainEntry
        val newOwner = Peer.bare()

        init {
            repeat(2) { a.addDevice(c.owner.identity, Peer.newNodeId(), "a$it", MemberKind.NODE, newKey(), T0 + 10 + it) }
            repeat(2) { b.addDevice(c.owner.identity, Peer.newNodeId(), "b$it", MemberKind.NODE, newKey(), T0 + 20 + it) }
            bBeforeRecovery = b.state()
            recoveryEntry = b.recover(c.recovery, newOwner.identity.publicKey, newOwner.nodeId, "recovered", newKey(), T0 + 50)
        }

        fun aRun() = a.entries().drop(2)
        fun bRun() = b.entries().drop(2)
    }

    @Test fun aDeviceWithoutRecoveryEntriesAfterTheForkSwitchesToTheBranchThatHasOneAndNotTheOtherWayRound() { // Codex Chain.kt:430
        val t = TwoBranches()
        assertEquals(2L, t.aRun().first().seq); assertEquals(2L, t.bRun().first().seq)
        assertNotEquals(t.aRun().first().id, t.bRun().first().id) // the owner key signed two different entries at number 2
        // the device on A has no recovery entry after the fork: it follows B, run begins at the fork, owner entries lead to the recovery one
        val devA = Chain.fromEntries(t.a.entries())
        val o = devA.offer(t.bRun())
        assertTrue(o is Offer.RolledBack)
        assertEquals(2, (o as Offer.RolledBack).dropped)
        assertEquals(t.b.heartbeat(), devA.heartbeat())
        assertEquals(t.newOwner.id, devA.state().ownerId)
        assertEquals(t.recoveryEntry.seq, devA.recoveryBoundary())
        // the device on B holds a recovery entry after the fork: it never goes to A, whatever A carries
        val devB = Chain.fromEntries(t.b.entries())
        assertTrue(devB.offer(t.aRun()) is Offer.Equivocation)
        assertEquals(t.b.heartbeat(), devB.heartbeat())
        // the recovery entry may also be the first entry of the run (the old rule): same result
        val devA2 = Chain.fromEntries(t.a.entries())
        val direct = t.c.copy().recover(t.c.recovery, newKey(), Peer.newNodeId(), "direct", newKey(), T0 + 60, forkAt = 2L)
        assertTrue(devA2.offer(listOf(direct)) is Offer.RolledBack)
    }

    @Test fun twoBranchesThatBothCarryRecoveryEntriesAfterTheForkDoNotConvergeAndAreFlagged() { // Codex Chain.kt:430 (the decided residue)
        val t = TwoBranches()
        // A also gets a recovery entry, a different one (another new owner)
        val other = Peer.bare()
        t.a.recover(t.c.recovery, other.identity.publicKey, other.nodeId, "other recovery", newKey(), T0 + 55)
        val devA = Chain.fromEntries(t.a.entries()); val devB = Chain.fromEntries(t.b.entries())
        val headA = devA.heartbeat(); val headB = devB.heartbeat()
        assertTrue(devA.offer(t.bRun()) is Offer.Equivocation)
        assertTrue(devB.offer(t.a.entries().drop(2)) is Offer.Equivocation)
        assertEquals(headA, devA.heartbeat()); assertEquals(headB, devB.heartbeat()) // each keeps its own, no hash tie-break
        assertEquals(1, devA.flags().size); assertEquals(1, devB.flags().size)
        assertEquals(other.id, devA.state().ownerId); assertEquals(t.newOwner.id, devB.state().ownerId)
    }

    @Test fun aRunWhoseRecoveryEntryIsNotSignedByTheRecoveryKeyInForceIsRefusedBeforeAnyReplay() { // Codex Chain.kt:430, :414
        val t = TwoBranches()
        val devA = Chain.fromEntries(t.a.entries())
        val fake = ChainEntry.sign(SoftwareIdentity.generate(), ChainEntry.transferBody(t.bBeforeRecovery, 2, T0, newKey(), Peer.newNodeId(), "x", newKey()))
        val run = t.b.entries().drop(2).dropLast(1) + fake
        val replaysBefore = devA.replays
        assertTrue(refused(devA.offer(run)))
        assertEquals(replaysBefore, devA.replays)
        assertEquals(t.a.heartbeat(), devA.heartbeat())
        // a recovery entry that is right but follows an owner entry that is wrong makes the whole run invalid, nothing is taken
        val bad = ChainEntry.sign(SoftwareIdentity.generate(), ChainEntry.addBody(t.c.copy().state(), T0, Peer.newNodeId(), "z", MemberKind.NODE, newKey(), null, null))
        assertTrue(refused(devA.offer(listOf(bad) + t.bRun().drop(1))))
        assertEquals(t.a.heartbeat(), devA.heartbeat())
    }

    @Test fun aFullMemberListKeepsTheLastPlaceForARecoveryTransferWhichThenTakesTheOldOwnersPlace() { // Codex Chain.kt:260
        val c = Cloud.create()
        while (c.state.members.size < ChainEntry.MAX_MEMBERS - 1) c.chain.addDevice(c.owner.identity, Peer.newNodeId(), "m", MemberKind.NODE, newKey(), T0)
        assertEquals(ChainEntry.MAX_MEMBERS - 1, c.state.members.size)
        assertError(CloudError.BAD_ENTRY) { c.chain.addDevice(c.owner.identity, Peer.newNodeId(), "one too many", MemberKind.NODE, newKey(), T0) }
        val spare = Peer.bare()
        assertError(CloudError.BAD_ENTRY) { c.chain.transfer(c.owner.identity, spare.identity.publicKey, spare.nodeId, "owner transfer to a new key", T0) }
        // the owner key is unavailable: the recovery key moves ownership into the reserved place
        val n1 = Peer.bare(); val rec1 = SoftwareIdentity.generate()
        c.chain.recover(c.recovery, n1.identity.publicKey, n1.nodeId, "n1", rec1.publicKey, T0 + 1)
        assertEquals(ChainEntry.MAX_MEMBERS, c.state.members.size)
        assertEquals(n1.id, c.state.ownerId)
        // full now: a second recovery transfer (after the recovery key was replaced by the first) takes the old owner's place
        val n2 = Peer.bare(); val rec2 = SoftwareIdentity.generate()
        c.chain.recover(rec1, n2.identity.publicKey, n2.nodeId, "n2", rec2.publicKey, T0 + 2)
        assertEquals(ChainEntry.MAX_MEMBERS, c.state.members.size)
        assertEquals(n2.id, c.state.ownerId)
        assertNull(c.state.member(n1.id))
        assertFalse(c.state.isRemoved(n1.id)); assertTrue(c.state.removed.isEmpty()) // the removed set keeps its bound: the chain remembers the eviction (Codex Chain.kt:267)
        // a member who verifies the chain from the genesis agrees
        assertEquals(c.chain.heartbeat(), Chain.fromEntries(c.chain.entries()).heartbeat())
    }

    @Test fun aPullPagesForwardOverTheCommonPartAndReachesAForkBeyondTheFirstPage() { // Codex Chain.kt:451
        val c = Cloud.create()
        repeat(150) { c.chain.rotate(c.owner.identity, EpochSecrets.generate(it + 1), T0 + it) } // 150 common entries after the genesis
        val a = c.copy(); val b = c.copy()
        a.addDevice(c.owner.identity, Peer.newNodeId(), "on A", MemberKind.NODE, newKey(), T0 + 200)
        b.addDevice(c.owner.identity, Peer.newNodeId(), "on B", MemberKind.NODE, newKey(), T0 + 200)
        b.recover(c.recovery, newKey(), Peer.newNodeId(), "recovered", newKey(), T0 + 300)
        val dev = Chain.fromEntries(a.entries())
        // the old answer to a mismatch was the first page again, for ever: the device held it already and learnt nothing
        assertEquals(b.entries().take(64).map { it.id }, b.entriesFor(dev.pullRequest(), page = 64).map { it.id })
        val puller = ChainPuller(dev)
        var rounds = 0
        while (!puller.done && rounds < 20) {
            puller.received(b.entriesFor(ChainPull.parse(puller.request().encode()), page = 64))
            rounds++
        }
        assertTrue(puller.done); assertTrue("took $rounds rounds", rounds <= 5)
        assertEquals(b.heartbeat(), dev.heartbeat())
        // a peer that repeats one page cannot keep the puller busy: no advance, it stops
        val dev2 = Chain.fromEntries(a.entries())
        val p2 = ChainPuller(dev2)
        val page0 = b.entriesFor(dev2.pullRequest(), page = 16)
        p2.received(page0); p2.received(page0)
        assertTrue(p2.done)
    }

    @Test fun anOwnerForkWithNoRecoveryEntryInReachStopsThePullAfterOneLookAtTheFork() {
        val c = Cloud.create()
        val a = c.copy(); val b = c.copy()
        a.addDevice(c.owner.identity, Peer.newNodeId(), "on A", MemberKind.NODE, newKey(), T0)
        b.addDevice(c.owner.identity, Peer.newNodeId(), "on B", MemberKind.NODE, newKey(), T0)
        val dev = Chain.fromEntries(a.entries())
        val puller = ChainPuller(dev)
        var rounds = 0
        while (!puller.done && rounds < 10) { puller.received(b.entriesFor(puller.request())); rounds++ }
        assertTrue(puller.done); assertEquals(2, rounds)
        assertEquals(1, dev.flags().size)
        assertEquals(a.heartbeat(), dev.heartbeat())
    }

    @Test fun aChainBuiltInPagesIsCheckedEntryByEntryAndAFailureStopsItThere() { // Codex Join.kt:561
        val c = cloudWith(0).first
        repeat(20) { c.admit(Peer.bare()) }
        val all = c.chain.entries()
        val b = Chain.Builder(c.chain.cloudId)
        b.add(all.subList(0, 7)); b.add(all.subList(7, 14)); b.add(all.subList(14, 16))
        assertEquals(16, b.count)
        val forged = ChainEntry.sign(SoftwareIdentity.generate(), ChainEntry.addBody(Chain.fromEntries(all.take(16)).state(), T0, Peer.newNodeId(), "x", MemberKind.NODE, newKey(), null, null))
        assertError(CloudError.BAD_ENTRY) { b.add(listOf(forged)) }
        assertEquals(16, b.count)
        b.add(all.subList(16, all.size)) // the right entries go on from where the good ones stopped
        assertEquals(c.chain.heartbeat(), b.build().heartbeat())
        // the genesis must hash to the cloud id that was asked for, and nothing else may come first
        assertError(CloudError.BAD_ENTRY) { Chain.Builder(Crypto.random(16)).add(all) }
        assertError(CloudError.BAD_ENTRY) { Chain.Builder(null).add(all.drop(1)) }
        assertError(CloudError.BAD_ENTRY) { Chain.Builder(null).build() }
    }

    // ---- the pre-membership path (docs 3.8, Codex section 5.2) ------------------------------------------------------------

    /** Honest devices hold the thief's chain (it removed a member and rotated); the recovery holder has built its chain elsewhere. */
    private inner class Takeover {
        val parts = cloudWith(2)
        val c = parts.first
        val peers = parts.second
        val thief = c.copy()
        val victim = peers[0]
        val newOwner = Peer.bare()
        val device = Chain.fromEntries(c.chain.entries().take(3) + thief.let { t ->
            t.removeAndRotate(c.owner.identity, victim.id, EpochSecrets.generate(1), T0 + 10)
        }) // an honest peer that heard of the thief's removal and rotate
        val repair: ChainEntry = c.copy().recover(c.recovery, newOwner.identity.publicKey, newOwner.nodeId, "recovered", newKey(), T0 + 40, forkAt = 3L)
    }

    @Test fun aRecoveryProofFromAnUnauthenticatedSenderIsAdoptedByADeviceThatHoldsTheThiefsChain() { // docs 5.2, 19.9
        val t = Takeover()
        assertNull(t.device.state().member(t.victim.id)) // the thief removed it
        val intake = RecoveryIntake(t.device)
        assertTrue(intake.receive(EntryRun.encode(listOf(t.repair)), "10.0.0.7", T0))
        assertEquals(t.newOwner.id, t.device.state().ownerId)
        assertNotNull(t.device.state().member(t.victim.id)) // the removal is undone
        assertEquals(2, t.device.dropped().size)
        assertEquals(t.repair.seq, t.device.recoveryBoundary())
        // asking again changes nothing and says no
        assertFalse(intake.receive(EntryRun.encode(listOf(t.repair)), "10.0.0.7", T0 + 1))
    }

    @Test fun theUnauthenticatedPathTakesNothingButRecoverySignedProofsAndReleasesNothing() {
        val t = Takeover()
        val before = t.device.heartbeat()
        val intake = RecoveryIntake(t.device)
        // the thief's own owner-signed entries: not a recovery proof
        val more = t.thief.addDevice(t.c.owner.identity, Peer.newNodeId(), "more", MemberKind.NODE, newKey(), T0 + 30)
        assertFalse(intake.receive(EntryRun.encode(listOf(more)), "10.0.0.1", T0))
        // an owner-key holder's fork with owner entries only
        val alt = ChainEntry.sign(t.c.owner.identity, ChainEntry.addBody(t.c.copy().state(), T0, Peer.newNodeId(), "alt", MemberKind.NODE, newKey(), null, null))
        assertFalse(intake.receive(EntryRun.encode(listOf(alt)), "10.0.0.2", T0))
        // a "recovery" entry signed by somebody else
        val forged = ChainEntry.sign(SoftwareIdentity.generate(), ChainEntry.transferBody(t.c.copy().state(), 2, T0, newKey(), Peer.newNodeId(), "x", newKey()))
        assertFalse(intake.receive(EntryRun.encode(listOf(forged)), "10.0.0.3", T0))
        // a gap, a run that is not contiguous, an empty run
        assertFalse(intake.receive(EntryRun.encode(emptyList()), "10.0.0.4", T0))
        assertFalse(intake.receive(EntryRun.encode(listOf(t.repair, t.repair)), "10.0.0.4", T0))
        assertEquals(before, t.device.heartbeat())
        assertTrue(t.device.flags().isEmpty())
    }

    @Test fun aRetiredRecoveryCodeCannotUseTheUnauthenticatedPathBelowTheBoundary() {
        val t = Takeover()
        val intake = RecoveryIntake(t.device)
        assertTrue(intake.receive(EntryRun.encode(listOf(t.repair)), "10.0.0.7", T0))
        // someone who kept the old code signs another repair at the same number: both carry recovery entries, nothing moves
        val again = t.c.copy().recover(t.c.recovery, newKey(), Peer.newNodeId(), "second", newKey(), T0 + 60, forkAt = 3L)
        val head = t.device.heartbeat()
        assertFalse(intake.receive(EntryRun.encode(listOf(again)), "10.0.0.8", T0 + 1))
        assertEquals(head, t.device.heartbeat())
    }

    @Test fun theUnauthenticatedPathHasItsOwnSmallBudgetAndABoundedAddressTable() {
        val t = Takeover()
        val junk = ChainEntry.sign(SoftwareIdentity.generate(), ChainEntry.transferBody(t.c.copy().state(), 2, T0, newKey(), Peer.newNodeId(), "x", newKey()))
        val intake = RecoveryIntake(t.device, maxPerMinute = 100, maxPerMinutePerSource = 2, maxSources = 4, maxRun = 3)
        // two tries a minute from one address, then it is refused before anything is verified, even with the right proof
        assertFalse(intake.receive(EntryRun.encode(listOf(junk)), "10.0.0.1", T0)); assertFalse(intake.receive(EntryRun.encode(listOf(junk)), "10.0.0.1", T0 + 1))
        assertFalse(intake.receive(EntryRun.encode(listOf(t.repair)), "10.0.0.1", T0 + 2))
        // four addresses fill the table; a fifth is refused and leaves no record, the right proof from an old address is still counted
        for (i in 2..4) assertFalse(intake.receive(EntryRun.encode(listOf(junk)), "10.0.0.$i", T0 + 3))
        assertFalse(intake.receive(EntryRun.encode(listOf(t.repair)), "10.0.0.9", T0 + 4))
        // a minute later the table is empty again and the proof goes through
        assertTrue(intake.receive(EntryRun.encode(listOf(t.repair)), "10.0.0.9", T0 + 61_000))
        // a run over the cap is refused unread
        val c2 = Takeover()
        val capped = RecoveryIntake(c2.device, maxRun = 3)
        assertFalse(capped.receive(EntryRun.encode(List(4) { c2.repair }), "10.0.0.1", T0))
        assertTrue(capped.receive(EntryRun.encode(listOf(c2.repair)), "10.0.0.1", T0 + 1))
        // and the whole device has a budget too: the right proof from a fresh address is refused once three tries were made
        val c3 = Takeover()
        val global = RecoveryIntake(c3.device, maxPerMinute = 3, maxPerMinutePerSource = 10)
        repeat(3) { assertFalse(global.receive(EntryRun.encode(listOf(junk)), "10.0.1.$it", T0)) }
        assertFalse(global.receive(EntryRun.encode(listOf(c3.repair)), "10.0.1.9", T0 + 1))
        assertTrue(global.receive(EntryRun.encode(listOf(c3.repair)), "10.0.1.9", T0 + 61_000))
    }

    // ---- stage 1e ----------------------------------------------------------------------------------------------------------------------

    @Test fun aRecoveryTransferToAFullListNeverGrowsTheRemovedSetAndTheEvictedKeyStillCannotComeBack() { // Codex Chain.kt:267
        // rules alone: 512 members and a removed set already at its bound of 4096
        val c = Cloud.create()
        val full = HashMap<String, ChainMember>()
        full.putAll(c.state.members.associateBy { it.id })
        repeat(ChainEntry.MAX_MEMBERS - 1) {
            val id = Hex.of(Crypto.random(32))
            full[id] = ChainMember(id, Peer.newNodeId(), "m", MemberKind.NODE, null, null, null, 1)
        }
        val tombstones = HashSet<String>().apply { repeat(ChainEntry.MAX_REMOVED) { add(Hex.of(Crypto.random(32))) } }
        val st = c.state.copy(members = full, removed = tombstones)
        assertEquals(ChainEntry.MAX_MEMBERS, st.members.size); assertEquals(ChainEntry.MAX_REMOVED, st.removed.size)
        val n1 = Peer.bare()
        val e = ChainEntry.sign(c.recovery, ChainEntry.transferBody(st, 2, T0, n1.identity.publicKey, n1.nodeId, "n1", newKey()))
        val next = ChainRules.apply(st, e) // recovery must not fail at capacity
        assertEquals(ChainEntry.MAX_MEMBERS, next.members.size)
        assertEquals(ChainEntry.MAX_REMOVED, next.removed.size) // not 4097
        assertNull(next.member(c.owner.id)); assertEquals(n1.id, next.ownerId)
        // on a chain: the evicted old owner is looked up in the chain, so it can neither be added nor made owner again
        val ch = Cloud.create()
        while (ch.state.members.size < ChainEntry.MAX_MEMBERS - 1) ch.chain.addDevice(ch.owner.identity, Peer.newNodeId(), "m", MemberKind.NODE, newKey(), T0)
        val a1 = Peer.bare(); val r1 = SoftwareIdentity.generate()
        ch.chain.recover(ch.recovery, a1.identity.publicKey, a1.nodeId, "a1", r1.publicKey, T0 + 1)
        val a2 = Peer.bare()
        ch.chain.recover(r1, a2.identity.publicKey, a2.nodeId, "a2", newKey(), T0 + 2) // full: a1 is pushed out
        assertNull(ch.state.member(a1.id)); assertTrue(ch.state.removed.isEmpty())
        ch.chain.remove(a2.identity, ch.state.members.first { it.id != a2.id && it.id != ch.owner.id }.id, T0 + 3) // a place is free again
        assertError(CloudError.BAD_ENTRY) { ch.chain.addDevice(a2.identity, Peer.newNodeId(), "back", MemberKind.NODE, a1.identity.publicKey, T0 + 4) }
        assertError(CloudError.BAD_ENTRY) { ch.chain.transfer(a2.identity, a1.identity.publicKey, Peer.newNodeId(), "back", T0 + 4) }
        // the same on a chain rebuilt from its entries (a newcomer, a restart)
        val rebuilt = Chain.fromEntries(ch.chain.entries())
        assertEquals(ch.chain.heartbeat(), rebuilt.heartbeat())
        assertError(CloudError.BAD_ENTRY) { rebuilt.addDevice(a2.identity, Peer.newNodeId(), "back", MemberKind.NODE, a1.identity.publicKey, T0 + 5) }
    }

    /** A branch with [owners] owner entries after the shared part, a recovery entry, and one more entry with a signature that is not valid. */
    private inner class PaddedFork(owners: Int) {
        val c = cloudWith(1).first
        val a = c.copy()
        val b = c.copy()
        val bad: ChainEntry
        val run: List<ChainEntry>

        init {
            a.addDevice(c.owner.identity, Peer.newNodeId(), "on A", MemberKind.NODE, newKey(), T0 + 5)
            repeat(owners) { b.addDevice(c.owner.identity, Peer.newNodeId(), "b$it", MemberKind.NODE, newKey(), T0 + 10 + it) }
            b.recover(c.recovery, newKey(), Peer.newNodeId(), "recovered", newKey(), T0 + 5_000)
            bad = ChainEntry.sign(SoftwareIdentity.generate(), ChainEntry.addBody(b.state(), T0, Peer.newNodeId(), "tail", MemberKind.NODE, newKey(), null, null))
            run = b.entries().drop(2) + bad
        }
    }

    @Test fun theWorkOfVerifyingIsPaidByEntriesAndAnAuthenticatedPrefixIsNeverVerifiedTwice() { // Codex Chain.kt:452
        val f = PaddedFork(60)
        val dev = Chain.fromEntries(f.a.entries())
        val intake = RecoveryIntake(dev, maxPerMinute = 100, maxPerMinutePerSource = 100, maxEntriesPerMinute = 200, maxEntriesPerMinutePerSource = 200)
        val body = EntryRun.encode(f.run) // 62 entries: 60 owner entries, the recovery entry and a tail that is not signed by anyone who counts
        // the first time the valid prefix is really verified; the whole run is refused because of its tail, the device keeps its branch
        val v0 = dev.verifies
        assertFalse(intake.receive(body, "10.0.0.1", T0))
        val first = dev.verifies - v0
        assertTrue("first run verified $first", first >= 60)
        assertEquals(f.a.heartbeat(), dev.heartbeat())
        // the same run from other addresses costs the one signature that is wrong and nothing else
        val v1 = dev.verifies
        assertFalse(intake.receive(body, "10.0.0.2", T0 + 1))
        assertTrue("second run verified ${dev.verifies - v1}", dev.verifies - v1 <= 3)
        val v2 = dev.verifies
        assertFalse(intake.receive(body, "10.0.0.3", T0 + 2))
        assertTrue(dev.verifies - v2 <= 3)
        // and the entries are charged: 3 x 62 is 186 of the 200 for the minute, the fourth run is refused before it is decoded or verified
        val v3 = dev.verifies
        assertFalse(intake.receive(body, "10.0.0.4", T0 + 3))
        assertEquals(v3, dev.verifies)
        // a minute later the budget is back and the genuine run (without the bad tail) is adopted at once: its signatures are known
        val good = EntryRun.encode(f.run.dropLast(1))
        assertTrue(intake.receive(good, "10.0.0.5", T0 + 61_000))
        assertEquals(f.b.heartbeat(), dev.heartbeat())
    }

    @Test fun theIntakeChargesItsBudgetBeforeDecodingAndRefusesWhatIsTooBigBeforeAllocating() { // Codex Chain.kt:713
        val t = Takeover()
        val intake = RecoveryIntake(t.device, maxPerMinute = 100, maxPerMinutePerSource = 2)
        // 65,536 entries announced (the cap of a whole chain): refused from the count alone, the bytes behind it are never parsed
        val huge = Writer().u32(ChainEntry.MAX_ENTRIES).raw(ByteArray(2_000)).bytes()
        assertFalse(intake.receive(huge, "10.0.0.1", T0))
        assertFalse(intake.receive(huge, "10.0.0.1", T0 + 1))
        // two refusals used the address's budget: the right proof from the same address is refused too, without being read
        assertFalse(intake.receive(EntryRun.encode(listOf(t.repair)), "10.0.0.1", T0 + 2))
        assertTrue(intake.receive(EntryRun.encode(listOf(t.repair)), "10.0.0.2", T0 + 3))
        // a count of 1025, a body over the size cap, a body too short for its count, and no body at all
        val t2 = Takeover()
        val i2 = RecoveryIntake(t2.device)
        assertFalse(i2.receive(Writer().u32(Chain.MAX_RUN + 1).raw(ByteArray(64)).bytes(), "10.0.1.1", T0))
        assertFalse(i2.receive(ByteArray(i2.maxBytes + 1), "10.0.1.2", T0))
        assertFalse(i2.receive(Writer().u32(1000).raw(ByteArray(100)).bytes(), "10.0.1.3", T0))
        assertFalse(i2.receive(ByteArray(0), "10.0.1.4", T0))
        assertEquals(4 + Chain.MAX_RUN * EntryRun.MAX_ENTRY_BYTES, i2.maxBytes)
        // the decoder itself refuses a count over its maximum before allocating, and a count the bytes cannot hold
        assertError(CloudError.MALFORMED) { EntryRun.decode(Reader(Writer().u32(65_536).bytes()), 1024) }
        assertError(CloudError.MALFORMED) { EntryRun.decode(Reader(Writer().u32(1_000).raw(ByteArray(100)).bytes()), 1024) }
        assertTrue(i2.receive(EntryRun.encode(listOf(t2.repair)), "10.0.1.5", T0))
    }
}
