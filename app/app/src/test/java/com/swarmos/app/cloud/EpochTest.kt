package com.swarmos.app.cloud

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpochTest {
    private val hour = 3_600_000L

    @Test fun secretsEncodeDecodeAndTheCommitmentCoversEverything() {
        val s = EpochSecrets.generate(4)
        val back = EpochSecrets.decode(s.encode())
        assertEquals(4, back.epoch); assertArrayEquals(s.discoveryKey, back.discoveryKey); assertArrayEquals(s.storageKey, back.storageKey)
        assertArrayEquals(s.commitment(), back.commitment())
        assertFalse(s.commitment().contentEquals(EpochSecrets(5, s.discoveryKey, s.storageKey).commitment())) // the epoch number
        assertFalse(s.commitment().contentEquals(EpochSecrets(4, Crypto.random(32), s.storageKey).commitment())) // the discovery key
        assertFalse(s.commitment().contentEquals(EpochSecrets(4, s.discoveryKey, Crypto.random(32)).commitment())) // the storage key
        assertError(CloudError.MALFORMED) { EpochSecrets.decode(s.encode() + byteArrayOf(0)) }
        assertError(CloudError.MALFORMED) { EpochSecrets.decode(s.encode().also { it[0] = 2 }) }
    }

    @Test fun secretsThatDoNotMatchTheirCommitmentOrTheChainAreRefused() {
        val (c, peers) = cloudWith(1)
        val store = SecretsStore()
        val state0 = c.state
        assertTrue(store.offer(c.secrets[0], state0))
        // a changed key, a set made by somebody else, a future epoch the chain does not name
        assertFalse(store.offer(EpochSecrets(0, Crypto.random(32), c.secrets[0].storageKey), state0))
        assertFalse(store.offer(EpochSecrets.generate(0), state0))
        assertFalse(store.offer(EpochSecrets.generate(1), state0))
        c.removeAndRotate(peers[0].id)
        val s1 = c.secrets[1]
        val state1 = c.state
        assertFalse(store.offer(EpochSecrets(1, s1.discoveryKey, Crypto.random(32)), state1)) // the commitment covers the storage key
        assertTrue(store.offer(s1, state1))
        assertFalse(store.offer(EpochSecrets(2, s1.discoveryKey, s1.storageKey), state1)) // no such epoch yet
        assertEquals(s1.epoch, store.current(state1)!!.epoch)
        assertTrue(store.offer(c.secrets[0], state1)) // the previous epoch is still named (two are kept)
    }

    @Test fun aRemovedMemberCannotGetTheNewEpochFromAnUpToDatePeer() {
        val (c, peers) = cloudWith(2)
        val (gone, stays) = peers
        val upToDate = SecretsStore().also { it.add(c.secrets[0]) }
        val staleChain = c.copy() // a peer that has not heard of the removal yet
        val staleStore = SecretsStore().also { it.add(c.secrets[0]) }
        c.removeAndRotate(gone.id)
        val chain = c.state
        upToDate.add(c.secrets[1])
        assertNotNull(upToDate.serve(1, stays.id, chain)) // a current member gets it
        assertNull(upToDate.serve(1, gone.id, chain)) // the removed one does not
        assertNull(upToDate.serve(1, Hex.of(Crypto.random(32)), chain)) // nor a stranger
        assertNull(upToDate.serve(7, stays.id, chain)) // nor an epoch the chain does not name
        // a stale peer would still serve it, but it has nothing to give: it never learnt the new secrets
        assertNull(staleStore.serve(1, gone.id, staleChain.state()))
        assertNotNull(staleStore.serve(0, gone.id, staleChain.state())) // the old epoch is what the removed member always had
    }

    @Test fun thePreviousEpochIsRecognisedForADayAfterTheNewOneWasLearnt() {
        val (c, peers) = cloudWith(1)
        val store = SecretsStore()
        store.add(c.secrets[0])
        store.note(c.state, T0)
        assertEquals(1, store.discoveryKeys(c.state, T0).size)
        c.removeAndRotate(peers[0].id)
        store.add(c.secrets[1])
        store.note(c.state, T0 + 10 * hour) // this device learnt of the new epoch at +10 h
        val keys = { now: Long -> store.discoveryKeys(c.state, now) }
        assertEquals(2, keys(T0 + 11 * hour).size)
        assertArrayEquals(c.secrets[1].discoveryKey, keys(T0 + 11 * hour)[0]) // the current one first (tags are made with it)
        assertArrayEquals(c.secrets[0].discoveryKey, keys(T0 + 11 * hour)[1])
        assertEquals(2, keys(T0 + 10 * hour + SecretsStore.GRACE_MS - 1).size)
        assertEquals(1, keys(T0 + 10 * hour + SecretsStore.GRACE_MS).size) // after the day only the current epoch
        // the removed member knows epoch 0 only: the tags it can make are not recognised once the grace is over
        val oldTag = Discovery.tag(c.secrets[0].discoveryKey, "0a0b0c0d", Discovery.slot(T0 + 40 * hour))
        assertFalse(keys(T0 + 40 * hour).any { Discovery.matches(it, "0a0b0c0d", oldTag, T0 + 40 * hour) })
        assertTrue(keys(T0 + 11 * hour).any { Discovery.matches(it, "0a0b0c0d", Discovery.tag(c.secrets[0].discoveryKey, "0a0b0c0d", Discovery.slot(T0 + 11 * hour)), T0 + 11 * hour) })
    }

    @Test fun aRecoveryTransferAlsoDemandsANewEpoch() {
        val (c, _) = cloudWith(1)
        val copy = c.copy()
        copy.recover(c.recovery, SoftwareIdentity.generate().publicKey, Peer.newNodeId(), "new", SoftwareIdentity.generate().publicKey, T0 + 1)
        assertTrue(copy.state().needsRotation)
    }
}
