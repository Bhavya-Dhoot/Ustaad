package com.swarmos.app.cloud

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryTest {
    private val key = Crypto.random(32)
    private val now = 1_800_000_000_000L

    @Test fun aTagIsDeterministicAndDiffersByDeviceKeyAndTime() {
        val s = Discovery.slot(now)
        val t = Discovery.tag(key, "0a0b0c0d", s)
        assertEquals(8, t.size)
        assertArrayEquals(t, Discovery.tag(key, "0a0b0c0d", s))
        assertNotEquals(Hex.of(t), Hex.of(Discovery.tag(key, "0a0b0c0e", s))) // another device of the same cloud
        assertNotEquals(Hex.of(t), Hex.of(Discovery.tag(key, "0a0b0c0d", s + 1))) // ten minutes later
        assertNotEquals(Hex.of(t), Hex.of(Discovery.tag(Crypto.random(32), "0a0b0c0d", s))) // another cloud
        assertEquals(4, Discovery.tag(key, "0a0b0c0d", s, length = 4).size)
        assertArrayEquals(t.copyOf(4), Discovery.tag(key, "0a0b0c0d", s, length = 4))
        assertEquals(Discovery.slot(now), Discovery.slot(now + 599_999 - (now % Discovery.SLOT_MS)))
        assertEquals(Discovery.slot(now) + 1, Discovery.slot(now - (now % Discovery.SLOT_MS) + Discovery.SLOT_MS))
    }

    @Test fun membersRecogniseATagWithinOneSlotOfClockDifference() {
        val t = Discovery.tag(key, "0a0b0c0d", Discovery.slot(now))
        assertTrue(Discovery.matches(key, "0a0b0c0d", t, now))
        assertTrue(Discovery.matches(key, "0a0b0c0d", t, now + Discovery.SLOT_MS))
        assertTrue(Discovery.matches(key, "0a0b0c0d", t, now - Discovery.SLOT_MS))
        assertFalse(Discovery.matches(key, "0a0b0c0d", t, now + 2 * Discovery.SLOT_MS))
        assertFalse(Discovery.matches(key, "0a0b0c0d", t, now - 2 * Discovery.SLOT_MS))
    }

    @Test fun aTamperedTagAnotherDeviceOrAnotherCloudIsNotRecognised() {
        val t = Discovery.tag(key, "0a0b0c0d", Discovery.slot(now))
        for (i in t.indices) assertFalse(Discovery.matches(key, "0a0b0c0d", t.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }, now))
        assertFalse(Discovery.matches(key, "0a0b0c0e", t, now)) // a tag does not vouch for another node id
        assertFalse(Discovery.matches(Crypto.random(32), "0a0b0c0d", t, now)) // an outsider with some other key
        assertFalse(Discovery.matches(key, "0a0b0c0d", t.copyOf(3), now))
        assertFalse(Discovery.matches(key, "0a0b0c0d", ByteArray(8), now))
    }

    @Test fun aReceiverFindsWhichMemberAdvertisedAndNobodyElse() {
        val ids = listOf("00000001", "00000002", "00000003")
        val t = Discovery.tag(key, "00000002", Discovery.slot(now))
        assertEquals("00000002", Discovery.recognise(key, t, ids, now))
        assertNull(Discovery.recognise(key, Discovery.tag(Crypto.random(32), "00000002", Discovery.slot(now)), ids, now))
        // an outsider watching two devices of one cloud sees two unrelated values, and the same device later sees a third
        val a = Discovery.tag(key, "00000001", Discovery.slot(now)); val b = Discovery.tag(key, "00000002", Discovery.slot(now))
        assertNotEquals(Hex.of(a), Hex.of(b))
        assertNotEquals(Hex.of(a), Hex.of(Discovery.tag(key, "00000001", Discovery.slot(now) + 1)))
    }

    @Test fun theUnsignedHelloAnswersTheProtocolVersionOnly() {
        assertEquals("""{"protocol":3}""", Discovery.HELLO_JSON)
        assertEquals(3, Discovery.PROTOCOL)
    }
}

class RelayTest {
    private val key = Crypto.random(32)

    @Test fun theRouteIdIsDerivedFromTheKeyTheNodeAndItsKey() {
        val home = SoftwareIdentity.generate()
        val fp = Crypto.fingerprint(home.publicKey)
        val r = Relay.routeId(key, "0a0b0c0d", fp)
        assertEquals(16, r.size)
        assertArrayEquals(r, Relay.routeId(key, "0a0b0c0d", fp))
        assertNotEquals(Hex.of(r), Hex.of(Relay.routeId(key, "0a0b0c0e", fp))) // another home node
        assertNotEquals(Hex.of(r), Hex.of(Relay.routeId(key, "0a0b0c0d", Crypto.fingerprint(SoftwareIdentity.generate().publicKey)))) // reinstalled: new key, new route
        assertNotEquals(Hex.of(r), Hex.of(Relay.routeId(Crypto.random(32), "0a0b0c0d", fp))) // an outsider cannot compute it
    }

    @Test fun helloRoundTripsAndStrictlyRefusesTheRest() {
        val route = Crypto.random(16)
        val (role, back) = Relay.parseHello(Relay.hello(Relay.ROLE_HOME, route))
        assertEquals(Relay.ROLE_HOME, role); assertArrayEquals(route, back)
        assertEquals(Relay.ROLE_AWAY, Relay.parseHello(Relay.hello(Relay.ROLE_AWAY, route)).first)
        assertError(CloudError.MALFORMED) { Relay.parseHello(Writer().u8(Relay.HELLO).u8(1).u8(9).fixed(route, 16).bytes()) } // role
        assertError(CloudError.MALFORMED) { Relay.parseHello(Writer().u8(Relay.HELLO).u8(2).u8(1).fixed(route, 16).bytes()) } // version
        assertError(CloudError.MALFORMED) { Relay.parseHello(Relay.hello(1, route) + byteArrayOf(0)) }
        assertError(CloudError.MALFORMED) { Relay.parseHello(Relay.hello(1, route).copyOf(10)) }
    }

    @Test fun onlyTheHolderOfTheKeyProvesItMayUseARoute() {
        val home = SoftwareIdentity.generate()
        val route = Relay.routeId(key, "0a0b0c0d", Crypto.fingerprint(home.publicKey))
        val nonce = Crypto.random(32)
        val msg = Relay.proof(home, "relay.example", route, nonce)
        assertArrayEquals(home.publicKey, Relay.verifyProof(msg, "relay.example", route, nonce)) // the relay stores this key for the route
        assertNull(Relay.verifyProof(msg, "other-relay.example", route, nonce)) // a proof for one relay is no good at another
        assertNull(Relay.verifyProof(msg, "relay.example", route, Crypto.random(32))) // nor with another nonce (replay)
        assertNull(Relay.verifyProof(msg, "relay.example", Crypto.random(16), nonce)) // nor for another route
        val stranger = SoftwareIdentity.generate()
        val swapped = msg.copyOf().also { System.arraycopy(stranger.publicKey, 0, it, 1, 65) } // someone else's key on this signature
        assertNull(Relay.verifyProof(swapped, "relay.example", route, nonce))
        assertNull(Relay.verifyProof(msg.copyOf(50), "relay.example", route, nonce))
        assertNull(Relay.verifyProof(byteArrayOf(Relay.PROOF.toByte()), "relay.example", route, nonce))
        // a different key can sign its own proof, but the relay would then see a different key than the one it stored
        val other = Relay.verifyProof(Relay.proof(stranger, "relay.example", route, nonce), "relay.example", route, nonce)
        assertNotEquals(Hex.of(home.publicKey), Hex.of(other!!))
    }
}

class PermissionsTest {
    private fun row(kind: MemberKind, local: Boolean) = Action.values().map { Permissions.allowed(kind, it, local) }

    @Test fun theTableOfTheDocumentKindByActionAndLink() {
        // order: READ_STATUS, SUBMIT_TASK, ASK_QUESTION, USE_FILES, WORK_FOR_PEER, LEND_MEMORY, STORE_FOR_PEER, MANAGE_MEMBERS
        assertEquals(listOf(true, true, true, true, true, true, true, true), row(MemberKind.NODE, local = true))
        assertEquals(listOf(true, true, true, true, false, false, false, true), row(MemberKind.NODE, local = false)) // away: no pooling
        assertEquals(listOf(true, true, true, true, false, false, false, false), row(MemberKind.CLIENT, local = true))
        assertEquals(listOf(true, true, true, true, false, false, false, false), row(MemberKind.CLIENT, local = false))
        assertEquals(listOf(true, true, true, true, false, false, false, false), row(MemberKind.BROWSER, local = true))
        assertEquals(listOf(true, true, true, true, false, false, false, false), row(MemberKind.BROWSER, local = false))
        assertEquals(8, Action.values().size)
    }

    @Test fun aClientOrBrowserNeverComputesForOthersLendsMemoryOrManagesMembers() {
        for (k in listOf(MemberKind.CLIENT, MemberKind.BROWSER)) for (local in listOf(true, false)) {
            for (a in listOf(Action.WORK_FOR_PEER, Action.LEND_MEMORY, Action.STORE_FOR_PEER, Action.MANAGE_MEMBERS)) {
                assertFalse("$k $a $local", Permissions.allowed(k, a, local))
            }
            for (a in listOf(Action.SUBMIT_TASK, Action.ASK_QUESTION, Action.USE_FILES, Action.READ_STATUS)) assertTrue(Permissions.allowed(k, a, local))
        }
    }
}
