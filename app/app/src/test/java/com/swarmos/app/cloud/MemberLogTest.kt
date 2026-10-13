package com.swarmos.app.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class MemberLogTest {
    private fun ids(log: MemberLog) = log.state().members.map { it.id }.toSet()

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    // ---- creation, adds, encoding -----------------------------------------------------------------------------------

    @Test fun aNewCloudHasOneMemberAndAnIdTiedToItsGenesis() {
        val a = Dev.creator()
        val b = Dev.creator()
        assertEquals(16, a.log.cloudId.size)
        assertFalse(a.log.cloudId.contentEquals(b.log.cloudId)) // the seed makes it random
        assertEquals(setOf(a.id), ids(a.log))
        val g = a.log.entries().single()
        assertEquals(Entry.GENESIS, g.type)
        assertTrue(MemberLog.cloudIdOf(g).contentEquals(a.log.cloudId))
        assertEquals(MemberKind.NODE, a.state.member(a.id)!!.kind)
    }

    @Test fun anAdmittedNodeBecomesAMemberAndTheEntryRoundTripsOnTheWire() {
        val (c, n) = cloudOf(1)
        val e = c.log.entries().last()
        assertEquals(setOf(c.id, n.id), ids(c.log))
        val again = Entry.parse(e.encode())
        assertEquals(e.id, again.id)
        assertEquals(n.nodeId, again.nodeId)
        assertEquals(c.id, again.signer)
        assertTrue(again.signedBy(c.identity.publicKey))
        assertEquals(c.id, c.state.member(n.id)!!.addedBy)
    }

    @Test fun onlyANodeCanWriteToTheList() {
        val (c, n) = cloudOf(1)
        val client = Dev.bare()
        c.admit(client, MemberKind.CLIENT)
        client.log = c.copyLog()
        var refused = false
        try { client.admit(Dev.bare()) } catch (e: IllegalStateException) { refused = true }
        assertTrue(refused)
        // an outsider cannot either
        val outsider = Dev.bare()
        outsider.log = c.copyLog()
        refused = false
        try { outsider.admit(Dev.bare()) } catch (e: IllegalStateException) { refused = true }
        assertTrue(refused)
        assertEquals(3, c.state.members.size)
        assertEquals(MemberKind.CLIENT, c.state.member(client.id)!!.kind)
        assertNotNull(n)
    }

    @Test fun entryParserRejectsBadKindsKeyTypesAndShapes() {
        val c = Dev.creator()
        val cloud = c.log.cloudId
        val deps = listOf(c.log.entries()[0].id)
        val pub = Dev.bare().identity.publicKey
        fun body(kind: Int, keyType: Int, issuer: String = c.id, nodeId: String = "0a0b0c0d", name: String = "x"): ByteArray {
            val w = Writer().str("ustaad/v1/entry").u8(Entry.ADD).fixed(cloud, 16).fixed(Hex.parse(c.id), 32).u64(T0).u8(1)
                .fixed(Hex.parse(deps[0]), 32).str(nodeId).str(name).u8(kind).u8(keyType)
            return if (keyType == 1) w.fixed(pub, 65).bytes() else w.fixed(Hex.parse(issuer), 32).fixed(ByteArray(16), 16).bytes()
        }
        val sig = ByteArray(64)
        Entry.of(body(1, 1), sig) // a node with a device key parses
        Entry.of(body(2, 2), sig) // a client with a token key parses
        assertError(CloudError.MALFORMED) { Entry.of(body(3, 1), sig) } // a browser holds a token key
        assertError(CloudError.MALFORMED) { Entry.of(body(1, 2), sig) } // a node holds a device key
        assertError(CloudError.MALFORMED) { Entry.of(body(2, 2, issuer = Dev.bare().id), sig) } // issuer must be the signer
        assertError(CloudError.MALFORMED) { Entry.of(body(9, 1), sig) }
        assertError(CloudError.MALFORMED) { Entry.of(body(1, 7), sig) }
        assertError(CloudError.MALFORMED) { Entry.of(body(1, 1, nodeId = "XYZ"), sig) }
        assertError(CloudError.MALFORMED) { Entry.of(body(1, 1, name = ""), sig) }
        assertError(CloudError.MALFORMED) { Entry.of(body(1, 1) + byteArrayOf(0), sig) } // trailing byte
        assertError(CloudError.MALFORMED) { Entry.of(body(1, 1), ByteArray(63)) }
    }

    @Test fun depsMustBeAscendingAndUnique() {
        val c = Dev.creator()
        val g = c.log.entries()[0].id
        fun withDeps(vararg d: String): ByteArray {
            val w = Writer().str("ustaad/v1/entry").u8(Entry.REMOVE).fixed(c.log.cloudId, 16).fixed(Hex.parse(c.id), 32).u64(T0).u8(d.size)
            d.forEach { w.fixed(Hex.parse(it), 32) }
            return w.fixed(ByteArray(32), 32).bytes()
        }
        val hi = "ff".repeat(32); val lo = "00".repeat(32)
        Entry.of(withDeps(lo, hi), ByteArray(64))
        assertError(CloudError.MALFORMED) { Entry.of(withDeps(hi, lo), ByteArray(64)) }
        assertError(CloudError.MALFORMED) { Entry.of(withDeps(g, g), ByteArray(64)) }
    }

    // ---- forged and replayed entries ---------------------------------------------------------------------------------

    @Test fun aForgedAddEntryIsNotStored() {
        val (c, n) = cloudOf(1)
        val attacker = Dev.bare()
        val victim = Dev.bare()
        val deps = listOf(c.log.entries()[0].id)

        // 1. claims the creator as its signer, signed by the attacker's own key
        val claimsCreator = Entry.sign(attacker.identity, Entry.addDeviceBody(c.log.cloudId, c.id, T0, deps, victim.nodeId, "v", MemberKind.NODE, attacker.identity.publicKey))
        // 2. names itself as the signer, but no stored entry ever added that key
        val selfSigned = Entry.sign(attacker.identity, Entry.addDeviceBody(c.log.cloudId, attacker.id, T0, deps, attacker.nodeId, "a", MemberKind.NODE, attacker.identity.publicKey))
        // 3. a real entry whose name was changed after it was signed
        val real = c.log.entries().last()
        val at = indexOf(real.body, "dev-".toByteArray())
        val changed = real.body.copyOf().also { it[at + 4] = (it[at + 4].toInt() xor 1).toByte() }
        val tampered = Entry.of(changed, real.signature)

        val target = n.copyLog()
        val r = target.merge(listOf(claimsCreator, selfSigned, tampered))
        assertEquals(0, r.added)
        assertEquals(3, r.rejected)
        assertEquals(setOf(c.id, n.id), ids(target))
    }

    @Test fun anEntryFromAnotherCloudOrWithMissingAncestryIsNotStored() {
        val (c, n) = cloudOf(1)
        val other = Dev.creator()
        val foreign = other.log.entries()[0]
        val x = Dev.bare()
        val stranger = other.admit(x)
        val log = c.copyLog()
        assertEquals(0, log.merge(listOf(foreign, stranger)).added)
        // an entry whose dep was never sent
        val e = Entry.sign(n.identity, Entry.addDeviceBody(c.log.cloudId, n.id, T0, listOf("ab".repeat(32)), x.nodeId, "x", MemberKind.NODE, x.identity.publicKey))
        assertEquals(1, log.merge(listOf(e)).rejected)
        assertEquals(2, log.size)
    }

    @Test fun mergeIsIdempotent() {
        val (c, n) = cloudOf(1)
        val log = n.copyLog()
        val h = log.hash()
        assertEquals(0, log.merge(c.log.entries()).added)
        assertEquals(h, log.hash())
    }

    // ---- removal ---------------------------------------------------------------------------------------------------

    @Test fun removalIsForGoodAndTheKeyCannotBeAddedAgain() {
        val (c, m) = cloudOf(1)
        c.remove(m.id)
        assertEquals(setOf(c.id), ids(c.log))
        assertTrue(c.state.isRemoved(m.id))
        c.admit(m) // the same key again, by a valid node
        assertEquals(setOf(c.id), ids(c.log))
        c.log.addDevice(c.identity, Dev.newNodeId(), "renamed", MemberKind.NODE, m.identity.publicKey, T0 + 5) // under another name
        assertNull(c.state.member(m.id))
        // a reinstalled device has a new key and can join
        val fresh = Dev.bare()
        c.admit(fresh)
        assertNotNull(c.state.member(fresh.id))
    }

    @Test fun aRemoveThatArrivesBeforeItsAddHasTheSameEffect() {
        val (c, n) = cloudOf(1)
        val x = Dev.bare()
        val add = c.admit(x) // c admits x ...
        val rem = n.remove(x.id) // ... while n, which never saw that, removes x's key
        val base = c.log.entries().take(2) // genesis and n's add

        val removeFirst = MemberLog.fromEntries(base).also { it.merge(listOf(rem, add)) } // one batch, remove first
        val addFirst = MemberLog.fromEntries(base).also { it.merge(listOf(add, rem)) }
        val twoSteps = MemberLog.fromEntries(base).also { it.merge(listOf(rem)); it.merge(listOf(add)) }
        for (l in listOf(removeFirst, addFirst, twoSteps)) {
            assertNull(l.state().member(x.id))
            assertTrue(l.state().isRemoved(x.id))
            assertEquals(setOf(c.id, n.id), ids(l))
        }
        assertEquals(removeFirst.hash(), addFirst.hash())
        assertEquals(removeFirst.hash(), twoSteps.hash())
    }

    @Test fun anAddSignedByARemovedMemberIsIgnoredWhetherItKnewOfTheRemovalOrNot() {
        val (c, m, p) = cloudOf(2)
        val stale = m.copyLog() // everything m had when it was removed
        val rem = c.remove(m.id)
        val z = Dev.bare()

        // (a) m signs with the heads it had: concurrent with its removal
        val staleDev = Dev(m.identity, m.nodeId, stale)
        val a = staleDev.admit(z)
        // (b) m signs with the removal among its dependencies
        val b = Entry.sign(m.identity, Entry.addDeviceBody(c.log.cloudId, m.id, T0 + 9, c.log.heads(), z.nodeId, "z", MemberKind.NODE, z.identity.publicKey))
        assertTrue(rem.id in c.log.heads())

        for (e in listOf(a, b)) {
            val log = c.copyLog()
            assertEquals(1, log.merge(listOf(e)).added) // stored (its signer's key is known) ...
            assertNull(log.state().member(z.id)) // ... but not a member
            assertEquals(setOf(c.id, p.id), ids(log))
        }
        assertNotNull(p)
    }

    @Test fun whatAMemberDidBeforeItsRemovalSurvivesIt() {
        val (c, m) = cloudOf(1)
        val y = Dev.bare()
        m.log = c.copyLog()
        val addY = m.admit(y) // m admits y ...
        c.log.merge(listOf(addY)) // ... c hears of it, then removes m
        c.remove(m.id)
        assertEquals(setOf(c.id, y.id), ids(c.log))
    }

    @Test fun aRemovedMembersStaleRemovalOfSomeoneElseIsIgnored() {
        val (c, m, a) = cloudOf(2)
        val stale = Dev(m.identity, m.nodeId, m.copyLog())
        val rem = c.remove(m.id)
        val k = stale.remove(a.id) // m, already removed, tries to take a with it
        val k2 = stale.remove(c.id)
        for (order in listOf(listOf(rem, k, k2), listOf(k, k2, rem), listOf(k2, rem, k))) {
            val log = MemberLog.fromEntries(c.log.entries().take(3))
            log.merge(order)
            // c's own removal of m is concurrent with k2 = (m removes c): that pair removes both, see the next test.
            assertTrue(log.state().isRemoved(m.id))
            assertNotNull(log.state().member(a.id))
        }
    }

    @Test fun twoMembersRemovingEachOtherAtTheSameTimeAreBothRemoved() {
        val (c, n1, n2) = cloudOf(2)
        val r12 = n1.remove(n2.id)
        val r21 = n2.remove(n1.id)
        val one = c.copyLog().also { it.merge(listOf(r12, r21)) }
        val two = c.copyLog().also { it.merge(listOf(r21, r12)) }
        for (l in listOf(one, two)) assertEquals(setOf(c.id), ids(l))
        assertEquals(one.hash(), two.hash())
    }

    @Test fun aCycleOfThreeConcurrentRemovalsRemovesAllThree() {
        val (c, a, b, d) = cloudOf(3)
        val rab = a.remove(b.id)
        val rbd = b.remove(d.id)
        val rda = d.remove(a.id)
        val log = c.copyLog().also { it.merge(listOf(rab, rbd, rda)) }
        assertEquals(setOf(c.id), ids(log))
        val log2 = c.copyLog().also { it.merge(listOf(rda, rbd, rab)) }
        assertEquals(log.hash(), log2.hash())
        assertEquals(setOf(c.id), ids(log2))
    }

    /**
     * Pins a known cost (docs/CLOUD-PROTOCOL.md open point 3): a removed member that kept its key can forge a removal of
     * the member that removed it, dated before its own removal. The two removals are a mutual pair, so BOTH members lose
     * their membership, and the remover's own recent entries (here its removal of another node) no longer count.
     */
    @Test fun aRemovedMembersForgedCounterRemovalTakesItsRemoverWithIt() {
        val (c, thief, other) = cloudOf(2)
        val stale = Dev(thief.identity, thief.nodeId, thief.copyLog())
        c.remove(thief.id)
        val forged = stale.remove(c.id) // the creator is removed along with the thief
        val log = c.copyLog().also { it.merge(listOf(forged)) }
        assertNull(log.state().member(c.id))
        assertNull(log.state().member(thief.id))
        assertNotNull(log.state().member(other.id)) // the cloud survives only through its other nodes
    }

    // ---- merging in any order gives one state ----------------------------------------------------------------------

    @Test fun twoLogsMergedInBothOrdersGiveOneStateAndOneHash() {
        val (c, n1, n2) = cloudOf(2)
        val p = Dev.bare(); val q = Dev.bare()
        n1.admit(p) // n1 and n2 work apart
        n2.admit(q)
        n2.remove(n1.id) // and n2 even removes n1, whose add of p is concurrent with that
        val x = n1.copyLog().also { it.merge(n2.log.entries()) }
        val y = n2.copyLog().also { it.merge(n1.log.entries()) }
        assertEquals(x.hash(), y.hash())
        assertEquals(ids(x), ids(y))
        assertEquals(x.state().removed, y.state().removed)
        assertNotEquals(n1.log.hash(), n2.log.hash())
        assertNotEquals(n1.log.hash(), x.hash())
        assertEquals(setOf(c.id, n2.id, q.id), ids(x)) // n1 is removed; its concurrent add of p is ignored
    }

    @Test fun anyOrderOfArrivalOfTheSameEntriesGivesTheSameState() {
        val (c, n1, n2, n3) = cloudOf(3)
        val extra = List(3) { Dev.bare() }
        val log1 = n1.log; val log2 = n2.log
        n1.admit(extra[0])
        n2.admit(extra[1])
        n3.remove(n2.id)
        n1.log.merge(n3.log.entries()) // n1 now knows the removal and then admits one more
        n1.admit(extra[2])
        val all = (c.log.entries() + log1.entries() + log2.entries() + n3.log.entries()).distinctBy { it.id }
        val rest = all.filter { it.type != Entry.GENESIS }
        val genesis = all.first { it.type == Entry.GENESIS }
        var expected: Pair<String, Set<String>>? = null
        val rnd = Random(7)
        repeat(25) {
            val shuffled = rest.shuffled(rnd)
            val log = MemberLog.fromEntries(listOf(genesis))
            // several partial batches in a random order: entries whose ancestry is missing are dropped and sent again
            shuffled.chunked(4).forEach { log.merge(it) }
            log.merge(shuffled)
            val got = log.hash() to ids(log)
            if (expected == null) expected = got
            assertEquals(expected, got)
        }
        assertEquals(rest.size + 1, MemberLog.fromEntries(listOf(genesis)).also { it.merge(rest) }.size)
    }

    @Test fun theHashCoversTheSetOfEntriesAndNotTheirSignatures() {
        val (c, n) = cloudOf(1)
        assertEquals(16, c.log.hash().length)
        val other = n.copyLog()
        assertEquals(c.log.hash(), other.hash())
        n.admit(Dev.bare())
        assertNotEquals(c.log.hash(), n.log.hash())
        // the same entry with a different (equally valid) signature has the same id
        val e = c.log.entries().last()
        val resigned = Entry.sign(c.identity, e.body)
        assertEquals(e.id, resigned.id)
    }

    @Test fun entriesNotInGivesTheDifferenceForSync() {
        val (c, n) = cloudOf(1)
        val p = Dev.bare()
        n.admit(p)
        val need = n.log.entriesNotIn(c.log.ids())
        assertEquals(1, need.size)
        c.log.merge(need)
        assertEquals(c.log.hash(), n.log.hash())
        // the pull request framing
        assertEquals(c.log.ids(), EntryList.parsePull(EntryList.pull(c.log.ids())))
        val back = EntryList.decode(Reader(EntryList.encode(n.log.entries())))
        assertEquals(n.log.entries().map { it.id }, back.map { it.id })
    }

    // ---- limits, duplicates ----------------------------------------------------------------------------------------

    @Test fun theLogHasACapAndKeepsPlacesForRemovals() {
        val id = SoftwareIdentity.generate()
        val node = Dev.newNodeId()
        val log = MemberLog.create(id, node, "c", T0, maxEntries = 6)
        val creator = Dev(id, node, log)
        val victims = List(10) { Dev.bare() }
        var added = 0
        try { for (v in victims) { creator.admit(v); added++ } } catch (e: IllegalStateException) { /* full */ }
        assertEquals(5, added) // genesis + 5 = 6
        assertEquals(6, log.size)
        creator.remove(victims[0].id) // a removal still fits
        assertEquals(7, log.size)
        assertFalse(log.state().members.any { it.id == victims[0].id })
    }

    @Test fun twoMembersWithTheSameNodeIdAreReported() {
        val (c) = cloudOf(0)
        val x = Dev.bare(); val y = Dev.bare()
        c.log.addDevice(c.identity, "11111111", "x", MemberKind.NODE, x.identity.publicKey, T0)
        c.log.addDevice(c.identity, "11111111", "y", MemberKind.NODE, y.identity.publicKey, T0 + 1)
        assertEquals(setOf("11111111"), c.state.duplicateNodeIds)
        assertEquals(2, c.state.byNodeId("11111111").size)
    }

    @Test fun aListNeedsExactlyOneGenesis() {
        val a = Dev.creator(); val b = Dev.creator()
        assertError(CloudError.MALFORMED) { MemberLog.fromEntries(emptyList()) }
        assertError(CloudError.MALFORMED) { MemberLog.fromEntries(a.log.entries() + b.log.entries()) }
        assertError(CloudError.MALFORMED) {
            val (c, n) = cloudOf(1)
            val p = Dev.bare()
            val extra = n.also { it.log = c.copyLog() }.admit(p)
            // a list missing n's own add cannot verify n's entry
            MemberLog.fromEntries(listOf(c.log.entries()[0], extra))
        }
    }
}
