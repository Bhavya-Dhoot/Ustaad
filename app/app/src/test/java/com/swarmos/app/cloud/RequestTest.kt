package com.swarmos.app.cloud

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestTest {
    private val dk = Crypto.random(32)

    /** a (initiator) and b (receiver) in one cloud with a session; the verifier runs on b. */
    private inner class Rig {
        val devs = cloudOf(2)
        val a = devs[0]; val b = devs[1]; val c = devs[2]
        val sessions = sessionBetween(a, b, dk)
        val sa = sessions.first
        val sb = sessions.second
        val store = SessionStore(b.id).also { it.add(sb) }
        val guard = ReplayGuard()
        val verifier = RequestVerifier(store, null, { b.log.state() }, guard, startedAtMs = T0 - 1_000)

        fun req(
            ts: Long = T0, id: String = "req-1", method: String = "POST", path: String = "/v1/chat?x=1", body: String = "{}",
            sender: Dev = a, via: Session = sa,
        ): SignedRequest {
            val bytes = body.toByteArray()
            val mac = RequestAuth.mac(via.macKey, method, path, ts, id, sender.nodeId, bytes)
            return SignedRequest(method, path, ts, id, sender.nodeId, bytes, mac, sessionId = via.id)
        }
    }

    private fun SignedRequest.copy(
        method: String = this.method, pathWithQuery: String = this.pathWithQuery, ts: Long = this.ts, requestId: String = this.requestId,
        sender: String = this.sender, body: ByteArray = this.body, mac: ByteArray = this.mac,
        sessionId: String? = this.sessionId, tokenKeyId: String? = this.tokenKeyId,
    ) = SignedRequest(method, pathWithQuery, ts, requestId, sender, body, mac, sessionId, tokenKeyId)

    // ---- the signature -----------------------------------------------------------------------------------------------

    @Test fun aSignedRequestIsAccepted() {
        val r = Rig()
        val ok = r.verifier.verify(r.req(), T0 + 5_000)
        assertEquals(r.a.nodeId, ok.nodeId)
        assertEquals(MemberKind.NODE, ok.kind)
        assertEquals(r.a.id, ok.memberId)
        assertFalse(ok.viaToken)
        assertFalse(ok.resend)
    }

    @Test fun everyCoveredFieldIsCoveredIncludingSenderAndRequestId() {
        val key = Crypto.random(32)
        val base = RequestAuth.mac(key, "POST", "/p?q=1", T0, "id1", "0a0b0c0d", "body".toByteArray())
        fun differs(m: ByteArray) = assertNotEquals(Hex.of(base), Hex.of(m))
        differs(RequestAuth.mac(key, "GET", "/p?q=1", T0, "id1", "0a0b0c0d", "body".toByteArray()))
        differs(RequestAuth.mac(key, "POST", "/p?q=2", T0, "id1", "0a0b0c0d", "body".toByteArray())) // the query is covered
        differs(RequestAuth.mac(key, "POST", "/p?q=1", T0 + 1, "id1", "0a0b0c0d", "body".toByteArray()))
        differs(RequestAuth.mac(key, "POST", "/p?q=1", T0, "id2", "0a0b0c0d", "body".toByteArray())) // not covered by the old signature
        differs(RequestAuth.mac(key, "POST", "/p?q=1", T0, "id1", "0a0b0c0e", "body".toByteArray())) // nor was the sender
        differs(RequestAuth.mac(key, "POST", "/p?q=1", T0, "id1", "0a0b0c0d", "bodY".toByteArray()))
        differs(RequestAuth.mac(Crypto.random(32), "POST", "/p?q=1", T0, "id1", "0a0b0c0d", "body".toByteArray()))
        assertArrayEquals(base, RequestAuth.mac(key, "post", "/p?q=1", T0, "id1", "0a0b0c0d", "body".toByteArray())) // method case is normalised
        // length prefixes: moving a byte between two fields cannot give the same input
        assertNotEquals(
            Hex.of(RequestAuth.input("POST", "/ab", T0, "c", "0a0b0c0d", ByteArray(0))),
            Hex.of(RequestAuth.input("POST", "/a", T0, "bc", "0a0b0c0d", ByteArray(0))),
        )
    }

    @Test fun anyChangeToARequestInFlightIsRefused() {
        val r = Rig()
        val good = r.req()
        assertError(CloudError.BAD_MAC) { r.verifier.verify(good.copy(body = "{ }".toByteArray()), T0) }
        assertError(CloudError.BAD_MAC) { r.verifier.verify(good.copy(pathWithQuery = "/v1/chat?x=2"), T0) }
        assertError(CloudError.BAD_MAC) { r.verifier.verify(good.copy(method = "GET"), T0) }
        assertError(CloudError.BAD_MAC) { r.verifier.verify(good.copy(requestId = "req-2"), T0) }
        assertError(CloudError.BAD_MAC) { r.verifier.verify(good.copy(ts = T0 + 1), T0) }
        assertError(CloudError.BAD_MAC) { r.verifier.verify(good.copy(mac = Crypto.random(32)), T0) }
    }

    @Test fun aRequestUnderAnotherSendersNameIsRefused() {
        val r = Rig()
        val sc = sessionBetween(r.c, r.b, dk)
        r.store.add(sc.second)
        // c signs with its own session but names a
        val asA = RequestAuth.mac(sc.first.macKey, "GET", "/v1/health", T0, "x1", r.a.nodeId, ByteArray(0))
        val forged = SignedRequest("GET", "/v1/health", T0, "x1", r.a.nodeId, ByteArray(0), asA, sessionId = sc.first.id)
        assertError(CloudError.SENDER_MISMATCH) { r.verifier.verify(forged, T0) }
        // a's session id with c's name
        val asC = RequestAuth.mac(r.sa.macKey, "GET", "/v1/health", T0, "x2", r.c.nodeId, ByteArray(0))
        assertError(CloudError.SENDER_MISMATCH) { r.verifier.verify(SignedRequest("GET", "/v1/health", T0, "x2", r.c.nodeId, ByteArray(0), asC, sessionId = r.sa.id), T0) }
        // a's own valid request with the name rewritten: refused before the MAC even matters
        assertError(CloudError.SENDER_MISMATCH) { r.verifier.verify(r.req().copy(sender = r.c.nodeId), T0) }
        // a member that knows a session key of its own cannot make the other member's requests either
        assertTrue(r.verifier.verify(r.req(id = "own"), T0).nodeId == r.a.nodeId)
    }

    // ---- replay, retry and the stored result ---------------------------------------------------------------------------

    @Test fun anIdenticalCopyOfARequestIsRefusedAsAReplay() {
        val r = Rig()
        val q = r.req()
        r.verifier.verify(q, T0)
        assertError(CloudError.REPLAYED) { r.verifier.verify(q, T0 + 100) }
        assertError(CloudError.REPLAYED) { r.verifier.verify(q.copy(), T0 + 20_000) }
    }

    @Test fun theSendersOwnRetryWithAFreshSignatureGoesToTheStoredResultButACopyOfEitherDoesNot() {
        val r = Rig()
        val first = r.req(ts = T0)
        val retry = r.req(ts = T0 + 40) // the failover path signs again with a new time and the same request id
        assertFalse(r.verifier.verify(first, T0 + 50).resend)
        val again = r.verifier.verify(retry, T0 + 60)
        assertTrue(again.resend) // the existing dedup now returns the stored result
        assertError(CloudError.REPLAYED) { r.verifier.verify(first, T0 + 70) }
        assertError(CloudError.REPLAYED) { r.verifier.verify(retry, T0 + 80) }
        assertTrue(r.verifier.verify(r.req(ts = T0 + 90), T0 + 100).resend) // a third honest signature still works
    }

    @Test fun oneRequestIdCannotStandForTwoDifferentRequests() {
        val r = Rig()
        r.verifier.verify(r.req(body = "{\"a\":1}"), T0)
        assertError(CloudError.REQUEST_ID_REUSED) { r.verifier.verify(r.req(ts = T0 + 1, body = "{\"a\":2}"), T0 + 1) }
        assertError(CloudError.REQUEST_ID_REUSED) { r.verifier.verify(r.req(ts = T0 + 2, path = "/v1/other"), T0 + 2) }
    }

    @Test fun anIdMayBeSignedAtMostEightTimes() {
        val r = Rig()
        for (i in 0 until 8) r.verifier.verify(r.req(ts = T0 + i), T0 + i)
        assertError(CloudError.TOO_MANY_RESENDS) { r.verifier.verify(r.req(ts = T0 + 8), T0 + 8) }
    }

    @Test fun aRequestWithABadMacNeverEntersTheReplayMemory() {
        val r = Rig()
        val bad = r.req().copy(mac = Crypto.random(32))
        repeat(5) { assertError(CloudError.BAD_MAC) { r.verifier.verify(bad, T0) } }
        assertEquals(0, r.guard.size)
        assertFalse(r.verifier.verify(r.req(), T0).resend) // the id is still unused
        assertEquals(1, r.guard.size)
    }

    @Test fun theReplayMemoryRefusesInsteadOfEvictingAndForgetsAfterItsTtl() {
        var now = 0L
        val g = ReplayGuard(ttlMs = 1_000, maxEntries = 2, clock = { now })
        val mac = { Crypto.random(32) }
        val fp = ByteArray(32)
        val m1 = mac()
        g.admit("0a0b0c0d", "id1", m1, fp)
        g.admit("0a0b0c0d", "id2", mac(), fp)
        assertError(CloudError.BUSY) { g.admit("0a0b0c0d", "id3", mac(), fp) }
        assertError(CloudError.REPLAYED) { g.admit("0a0b0c0d", "id1", m1, fp) } // id1 was not evicted to make room
        now = 5_000 // every entry is past the ttl; the memory frees itself
        g.admit("0a0b0c0d", "id3", mac(), fp)
        assertEquals(1, g.size)
        // ids are per sender
        g.admit("11111111", "id3", mac(), fp)
    }

    // ---- the window and the life of the process ------------------------------------------------------------------------

    @Test fun theThirtySecondWindowHolds() {
        val r = Rig()
        r.verifier.verify(r.req(id = "a", ts = T0), T0 + 30_000) // exactly 30 s old
        assertError(CloudError.SKEW) { r.verifier.verify(r.req(id = "b", ts = T0), T0 + 30_001) }
        r.verifier.verify(r.req(id = "c", ts = T0 + 30_000), T0) // 30 s in the future
        assertError(CloudError.SKEW) { r.verifier.verify(r.req(id = "d", ts = T0 + 30_001), T0) }
    }

    @Test fun aRequestSignedBeforeThisProcessStartedIsRefused() {
        val r = Rig() // started at T0 - 1000
        assertError(CloudError.SKEW) { r.verifier.verify(r.req(ts = T0 - 2_000), T0) } // inside 30 s of now, but before the start
    }

    // ---- sessions behind the request -------------------------------------------------------------------------------------

    @Test fun anUnknownExpiredOrEndedSessionIsUnknown() {
        val r = Rig()
        val unknown = r.req().copy(sessionId = "00112233445566ff")
        assertError(CloudError.UNKNOWN_SESSION) { r.verifier.verify(unknown, T0) }
        assertError(CloudError.UNKNOWN_SESSION) { r.verifier.verify(r.req(ts = T0 + Session.LIFETIME_MS), T0 + Session.LIFETIME_MS) }
        val r2 = Rig()
        r2.b.remove(r2.a.id) // the sender is removed
        assertError(CloudError.UNKNOWN_SESSION) { r2.verifier.verify(r2.req(), T0) }
    }

    @Test fun malformedRequestsAreRefusedBeforeAnyKeyIsLookedUp() {
        val r = Rig()
        assertError(CloudError.MALFORMED) { r.verifier.verify(r.req().copy(sessionId = null), T0) } // neither a session nor a key id
        assertError(CloudError.MALFORMED) { r.verifier.verify(r.req().copy(tokenKeyId = "00".repeat(16)), T0) } // both
        assertError(CloudError.MALFORMED) { r.verifier.verify(r.req().copy(requestId = "bad id"), T0) }
        assertError(CloudError.MALFORMED) { r.verifier.verify(r.req().copy(sender = "xyz"), T0) }
    }

    // ---- token clients -------------------------------------------------------------------------------------------------

    private inner class TokenRig(kind: MemberKind = MemberKind.CLIENT) {
        val devs = cloudOf(1)
        val issuer = devs[0]; val other = devs[1]
        val tokens = TokenKeys(issuer.identity)
        val grant = tokens.issue(issuer.log, "test script", kind, T0)
        val guard = ReplayGuard()
        val verifier = RequestVerifier(SessionStore(issuer.id), tokens, { issuer.log.state() }, guard, T0 - 1_000)
        val kid = Hex.of(grant.keyId)

        fun req(ts: Long = T0, id: String = "t-1", sender: String = grant.nodeId, key: ByteArray = grant.key, kidHex: String = kid, body: String = "{}"): SignedRequest {
            val bytes = body.toByteArray()
            return SignedRequest("POST", "/v1/chat", ts, id, sender, bytes, RequestAuth.mac(key, "POST", "/v1/chat", ts, id, sender, bytes), tokenKeyId = kidHex)
        }
    }

    @Test fun aTokenClientSignsWithItsKeyAndTheIssuerVerifies() {
        val t = TokenRig()
        val ok = t.verifier.verify(t.req(), T0)
        assertEquals(MemberKind.CLIENT, ok.kind)
        assertTrue(ok.viaToken)
        assertEquals(t.grant.nodeId, ok.nodeId)
        val browser = TokenRig(MemberKind.BROWSER)
        assertEquals(MemberKind.BROWSER, browser.verifier.verify(browser.req(), T0).kind)
    }

    @Test fun aTokenClientIsAMemberOfItsIssuerAndEndsWithIt() {
        val (c, n) = cloudOf(1)
        val tokens = TokenKeys(n.identity)
        n.log = c.copyLog()
        val grant = tokens.issue(n.log, "test script", MemberKind.CLIENT, T0 + 1)
        c.log.merge(listOf(grant.entry))
        val m = c.state.member(tokens.memberId(grant.keyId))
        assertNotNull(m)
        assertEquals(MemberKind.CLIENT, m!!.kind)
        assertEquals(n.id, m.issuer)
        assertEquals(32, grant.key.size)
        // removed like any member
        c.remove(m.id)
        assertNull(c.state.member(m.id))
        // a second client of n, then n itself is removed: the client goes with its issuer
        val g2 = tokens.issue(n.log, "browser", MemberKind.BROWSER, T0 + 3)
        c.log.merge(listOf(g2.entry))
        assertNotNull(c.state.member(tokens.memberId(g2.keyId)))
        c.remove(n.id)
        assertNull(c.state.member(tokens.memberId(g2.keyId)))
    }

    @Test fun aNonNodeCannotIssueATokenAndNothingIsKeptWhenItFails() {
        val (c) = cloudOf(0)
        val client = Dev.bare()
        c.admit(client, MemberKind.CLIENT)
        client.log = c.copyLog()
        val tokens = TokenKeys(client.identity)
        var failed = false
        try { tokens.issue(client.log, "x", MemberKind.CLIENT, T0) } catch (e: IllegalStateException) { failed = true }
        assertTrue(failed)
        assertNull(tokens.key(Hex.of(ByteArray(16)))) // no key was kept
    }

    @Test fun aTokenRequestFailsWithAWrongKeyAnUnknownKeyIdOrAnotherSendersName() {
        val t = TokenRig()
        assertError(CloudError.BAD_MAC) { t.verifier.verify(t.req(key = Crypto.random(32)), T0) }
        assertError(CloudError.UNKNOWN_KEY) { t.verifier.verify(t.req(kidHex = "ab".repeat(16)), T0) }
        assertError(CloudError.SENDER_MISMATCH) { t.verifier.verify(t.req(sender = t.other.nodeId), T0) }
        assertError(CloudError.MALFORMED) { t.verifier.verify(t.req(kidHex = "abcd"), T0) }
        // a key issued by another node is not known here: only the issuer can verify its clients
        val foreign = TokenKeys(t.other.identity).issue(t.other.log, "x", MemberKind.CLIENT, T0 + 5)
        t.issuer.log.merge(t.other.log.entries()) // the entry reaches the issuer, the key never does
        val req = SignedRequest("GET", "/p", T0, "f-1", foreign.nodeId, ByteArray(0), RequestAuth.mac(foreign.key, "GET", "/p", T0, "f-1", foreign.nodeId, ByteArray(0)), tokenKeyId = Hex.of(foreign.keyId))
        assertError(CloudError.UNKNOWN_KEY) { t.verifier.verify(req, T0) }
    }

    @Test fun aTokenRequestIsNotReplayable() {
        val t = TokenRig()
        val q = t.req()
        t.verifier.verify(q, T0)
        assertError(CloudError.REPLAYED) { t.verifier.verify(q, T0 + 1) }
        assertTrue(t.verifier.verify(t.req(ts = T0 + 2), T0 + 2).resend)
    }

    @Test fun aRemovedTokenClientIsUnknownAtOnceAndSoIsOneWhoseIssuerWasRemoved() {
        val t = TokenRig()
        t.verifier.verify(t.req(id = "ok"), T0)
        t.issuer.remove(t.tokens.memberId(t.grant.keyId)) // removed like any member
        assertError(CloudError.UNKNOWN_KEY) { t.verifier.verify(t.req(id = "after"), T0) }

        val t2 = TokenRig(MemberKind.BROWSER)
        t2.verifier.verify(t2.req(), T0)
        t2.other.log = t2.issuer.copyLog()
        t2.other.remove(t2.issuer.id) // another node removes the issuer
        t2.issuer.log.merge(t2.other.log.entries())
        assertError(CloudError.UNKNOWN_KEY) { t2.verifier.verify(t2.req(id = "later"), T0) }
    }

    @Test fun aTokenClientNeverComputesForOthersOrLendsMemory() {
        for (k in listOf(MemberKind.CLIENT, MemberKind.BROWSER)) {
            for (a in listOf(Action.WORK_FOR_PEER, Action.LEND_MEMORY, Action.STORE_FOR_PEER, Action.MANAGE_MEMBERS)) {
                assertFalse(Permissions.allowed(k, a, local = true))
            }
        }
    }
}
