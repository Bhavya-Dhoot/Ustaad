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
    private fun offer(c: Chain, vararg run: ChainEntry) = c.offer(run.toList(), T0)
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
        c.chain.checkpoint(c.owner.identity, T0 + 3)
        assertEquals(ChainEntry.CHECKPOINT, c.chain.entries().last().op)
        assertEquals("5:" + Hex.of(c.state.headId.copyOf(8)), c.chain.heartbeat())
        // a replayed copy of the whole chain at a member changes nothing
        assertTrue(Chain.fromEntries(c.chain.entries(), T0).heartbeat() == c.chain.heartbeat())
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
        assertTrue(refused(stale.offer(listOf(counter), T0))) // even on its own chain it is not the owner
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
        assertTrue(member.offer(c.chain.entries().take(2), T0) is Offer.Unchanged)
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
        val copy = Chain.fromEntries(base.entries(), T0)
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
        val chain2 = Chain.fromEntries(member.entries(), T0)
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

    @Test fun aUsedRecoveryCodeIsRetiredForTheTipButStillForksTheHistoryBeforeIt() {
        val (c, peers) = cloudWith(1)
        val member = peers[0].chain
        val beforeX = c.copy() // head 2
        val x = c.admit(Peer.bare()) // an owner entry at 3
        val (_, r, p2) = recoveryRun(c.chain, c) // the recovery code is used at 4 and retired
        assertEquals(x.seq + 1, r.seq)
        assertTrue(offer(member, x, r) is Offer.Adopted)
        assertEquals(p2.id, member.state().ownerId)
        // at the tip the old code is no longer the key in force
        val s = member.state()
        val again = ChainEntry.sign(c.recovery, ChainEntry.transferBody(s, 2, T0, SoftwareIdentity.generate().publicKey, Peer.newNodeId(), "x", SoftwareIdentity.generate().publicKey))
        assertTrue(refused(offer(member, again)))
        var refusedUse = false
        try { member.recover(c.recovery, SoftwareIdentity.generate().publicKey, Peer.newNodeId(), "x", SoftwareIdentity.generate().publicKey, T0) } catch (e: IllegalStateException) { refusedUse = true }
        assertTrue(refusedUse)
        // documented cost: where it was still in force (before 4) the old code can fork, and that undoes the later recovery too
        val undo = beforeX.recover(c.recovery, SoftwareIdentity.generate().publicKey, Peer.newNodeId(), "undo", SoftwareIdentity.generate().publicKey, T0 + 70)
        assertEquals(x.seq, undo.seq)
        val o = offer(member, undo)
        assertTrue(o is Offer.RolledBack)
        assertEquals(2, (o as Offer.RolledBack).dropped) // x and r
        assertNotEquals(p2.id, member.state().ownerId)
    }

    // ---- checkpoints --------------------------------------------------------------------------------------------------------

    @Test fun aCheckpointThatLiesAboutTheStateIsRefused() {
        val (c, peers) = cloudWith(2)
        c.remove(peers[0].id)
        val member = c.copy()
        val s = member.state()
        fun cp(state: ChainState) = ChainEntry.sign(
            c.owner.identity,
            ChainEntry.header(ChainEntry.CHECKPOINT, 1, s.cloudId, s.nextSeq, s.headId, T0).raw(StateCodec.encode(state)).bytes(),
        )
        val dropsRemoval = cp(s.copy(removed = emptySet()))
        val extraMember = cp(s.copy(members = s.members.associateBy { it.id } + (Hex.of(Crypto.random(32)) to ChainMember(Hex.of(Crypto.random(32)), Peer.newNodeId(), "ghost", MemberKind.NODE, SoftwareIdentity.generate().publicKey, null, null, 1))))
        val otherOwner = cp(s.copy(ownerKey = SoftwareIdentity.generate().publicKey))
        for (lie in listOf(dropsRemoval, extraMember, otherOwner)) assertTrue(refused(offer(member, lie)))
        assertEquals(c.chain.heartbeat(), member.heartbeat())
        // the honest one is taken, and a node cannot write one
        val honest = c.chain.checkpoint(c.owner.identity, T0 + 9)
        assertTrue(offer(member, honest) is Offer.Adopted)
        assertTrue(refused(offer(c.copy(), ChainEntry.sign(peers[1].identity, ChainEntry.checkpointBody(c.copy().state(), T0)))))
    }

    @Test fun aNewcomerStartsFromTheLastCheckpointAndWhatFollows() {
        val (c, _) = cloudWith(2)
        val cp = c.chain.checkpoint(c.owner.identity, T0 + 5)
        val tail = c.admit(Peer.bare(), now = T0 + 6)
        val fresh = Chain.fromEntries(listOf(cp, tail), T0, expectedCloudId = c.chain.cloudId)
        assertEquals(c.chain.heartbeat(), fresh.heartbeat())
        assertEquals(c.state.members.map { it.id }.toSet(), fresh.state().members.map { it.id }.toSet())
        assertEquals(2, fresh.entries().size)
        assertError(CloudError.BAD_ENTRY) { Chain.fromEntries(listOf(cp, tail), T0, expectedCloudId = ByteArray(16)) }
        assertError(CloudError.BAD_ENTRY) { Chain.fromEntries(listOf(cp, tail), T0) } // a checkpoint start needs the invite's cloud id
        assertError(CloudError.BAD_ENTRY) { Chain.fromEntries(listOf(tail), T0) }
        // and it can carry on: a removal reaches it as an extension
        val rem = c.remove(c.state.members.first { it.id != c.owner.id }.id, now = T0 + 8)
        assertTrue(offer(fresh, rem) is Offer.Adopted)
    }

    @Test fun aLongAbsentMemberJumpsToACheckpointOnlyIfTheOwnerItHoldsVouchesForIt() {
        val (c, peers) = cloudWith(1)
        val member = peers[0].chain
        val alt = c.copy() // an owner-key fork made before the member's next news
        val removed = Peer.bare(); c.admit(removed); c.remove(removed.id)
        member.offer(c.chain.entries().drop(member.entries().size), T0) // the member hears of the removal
        repeat(3) { c.admit(Peer.bare()) }
        val cp = c.chain.checkpoint(c.owner.identity, T0 + 5)
        val after = c.admit(Peer.bare(), now = T0 + 6)
        val jump = listOf(cp, after)
        // a checkpoint of a branch that does not contain the removal we know: refused
        repeat(5) { alt.addDevice(c.owner.identity, Peer.newNodeId(), "alt", MemberKind.NODE, SoftwareIdentity.generate().publicKey, T0) }
        val altCp = alt.checkpoint(c.owner.identity, T0 + 5)
        assertTrue(refused(offer(member, altCp)))
        // the honest one is taken: the member's chain now starts at the checkpoint
        assertTrue(offer(member, *jump.toTypedArray()) is Offer.Adopted)
        assertEquals(c.chain.heartbeat(), member.heartbeat())
        assertEquals(ChainEntry.CHECKPOINT, member.entries()[0].op)
        // a checkpoint written by a new owner cannot be taken by a device that holds the old one: it needs the entries
        val member2 = Peer.bare().also { it.chain = Chain.fromEntries(c.chain.entries().take(3), T0) }.chain
        val n = Peer.bare(); c.admit(n)
        val t = c.chain.transfer(c.owner.identity, n.identity.publicKey, n.nodeId, "n", T0 + 20)
        val cp2 = c.chain.checkpoint(n.identity, T0 + 21)
        assertNotNull(t)
        assertTrue(refused(offer(member2, cp2, c.admit2(n))))
    }

    private fun Cloud.admit2(n: Peer): ChainEntry = chain.addDevice(n.identity, Peer.newNodeId(), "z", MemberKind.NODE, SoftwareIdentity.generate().publicKey, T0 + 22)

    @Test fun compactionWaitsOneDayAfterACheckpointWasFirstSeen() {
        val (c, _) = cloudWith(1)
        c.admit(Peer.bare())
        val cp = c.chain.checkpoint(c.owner.identity, T0)
        c.admit(Peer.bare())
        val before = c.chain.entries().size
        val membersBefore = c.state.members.map { it.id }.toSet()
        c.chain.compact(T0 + Chain.HOLD_MS - 1)
        assertEquals(before, c.chain.entries().size) // too early: the owner still has a day to fork
        c.chain.compact(T0 + Chain.HOLD_MS)
        assertEquals(cp.id, c.chain.entries()[0].id)
        assertEquals(2, c.chain.entries().size)
        // the state is the same, and a fork below the base is no longer possible
        assertEquals(membersBefore, c.state.members.map { it.id }.toSet())
        var refusedFork = false
        try { c.chain.recover(c.recovery, SoftwareIdentity.generate().publicKey, Peer.newNodeId(), "x", SoftwareIdentity.generate().publicKey, T0, forkAt = cp.seq) } catch (e: IllegalArgumentException) { refusedFork = true }
        assertTrue(refusedFork)
    }

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

    @Test fun oneBodyLimitHoldsWhenCreatingAndWhenParsing() {
        val (c, _) = cloudWith(0)
        val over = ByteArray(ChainEntry.MAX_BODY + 1)
        assertError(CloudError.MALFORMED) { ChainEntry.of(over, ByteArray(64)) }
        assertError(CloudError.MALFORMED) { ChainEntry.parse(Writer().lp(over).fixed(ByteArray(64), 64).bytes()) }
        // a real checkpoint of a full-sized state fits (512 members): the limit was chosen for it
        assertTrue(ChainEntry.MAX_BODY > 512 * 200 + 4096 * 32)
        assertEquals(1, c.chain.entries().size)
    }

    @Test fun syncPullsTheTailOrTheWholeChainWhenBranchesDiffer() {
        val (c, _) = cloudWith(1)
        val member = c.copy()
        val e1 = c.admit(Peer.bare()); c.admit(Peer.bare())
        val (from, prev) = member.pullRequest()
        val tail = c.chain.entriesAfter(from, prev)
        assertEquals(2, tail.size); assertEquals(e1.id, tail[0].id)
        assertTrue(offer(member, *tail.toTypedArray()) is Offer.Adopted)
        // a device on another branch (same number, other id) is sent the chain from the base
        val other = Peer.bare().chain
        val (f2, p2) = other.pullRequest()
        assertEquals(c.chain.entries().size, c.chain.entriesAfter(f2, p2).size)
        assertTrue(EntryRun.decode(Reader(EntryRun.encode(tail))).map { it.id } == tail.map { it.id })
    }

    @Test fun aRunThatIsNotContiguousOrIsTooLongIsRefusedWhole() {
        val (c, _) = cloudWith(0)
        val member = c.copy()
        val a = c.admit(Peer.bare()); val b = c.admit(Peer.bare())
        assertTrue(refused(offer(member, b, a)))
        assertTrue(refused(member.offer(List(Chain.MAX_RUN + 1) { a }, T0)))
        assertTrue(offer(member) is Offer.Unchanged)
    }
}
