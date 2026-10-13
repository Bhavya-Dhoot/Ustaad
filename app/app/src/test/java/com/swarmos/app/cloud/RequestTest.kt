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

    /** a (initiator) and b (receiver) with a session, c3 with its own; the verifier runs on b. */
    private inner class Rig {
        val parts = cloudWith(3)
        val cloud = parts.first
        val a = parts.second[0]; val b = parts.second[1]; val c3 = parts.second[2]
        val sessions = sessionBetween(a, b, dk)
        val sa = sessions.first
        val sb = sessions.second
        val store = SessionStore(b.id).also { it.add(sb) }
        val guard = ReplayGuard()
        val verifier = RequestVerifier(store, null, { b.chain.state() }, guard, startedAtMs = T0 - 1_000)

        fun req(
            ts: Long = T0, id: String = "req-1", method: String = "POST", path: String = "/v1/chat?x=1", body: String = "{}",
            sender: Peer = a, via: Session = sa,
        ): SignedRequest {
            val wire = RequestAuth.sealBody(via, id, body.toByteArray())
            val mac = RequestAuth.mac(via.macKey, method, path, ts, id, sender.nodeId, wire)
            return SignedRequest(method, path, ts, id, sender.nodeId, wire, mac, sessionId = via.id)
        }
    }

    private fun SignedRequest.copy(
        method: String = this.method, pathWithQuery: String = this.pathWithQuery, ts: Long = this.ts, requestId: String = this.requestId,
        sender: String = this.sender, body: ByteArray = this.body, mac: ByteArray = this.mac,
        sessionId: String? = this.sessionId, tokenKeyId: String? = this.tokenKeyId,
    ) = SignedRequest(method, pathWithQuery, ts, requestId, sender, body, mac, sessionId, tokenKeyId)

    // ---- the signature -----------------------------------------------------------------------------------------------

    @Test fun aSignedSealedRequestIsAcceptedAndItsBodyIsOpened() {
        val r = Rig()
        val ok = r.verifier.verify(r.req(body = """{"q":"hi"}"""), T0 + 5_000)
        assertEquals(r.a.nodeId, ok.nodeId)
        assertEquals(MemberKind.NODE, ok.kind)
        assertEquals(r.a.id, ok.memberId)
        assertFalse(ok.viaToken); assertFalse(ok.resend)
        assertEquals("""{"q":"hi"}""", String(ok.body))
        assertNotNull(ok.session)
    }

    @Test fun everyCoveredFieldIsCoveredIncludingSenderAndRequestId() {
        val key = Crypto.random(32)
        val base = RequestAuth.mac(key, "POST", "/p?q=1", T0, "id1", "0a0b0c0d", "body".toByteArray())
        fun differs(m: ByteArray) = assertNotEquals(Hex.of(base), Hex.of(m))
        differs(RequestAuth.mac(key, "GET", "/p?q=1", T0, "id1", "0a0b0c0d", "body".toByteArray()))
        differs(RequestAuth.mac(key, "POST", "/p?q=2", T0, "id1", "0a0b0c0d", "body".toByteArray()))
        differs(RequestAuth.mac(key, "POST", "/p?q=1", T0 + 1, "id1", "0a0b0c0d", "body".toByteArray()))
        differs(RequestAuth.mac(key, "POST", "/p?q=1", T0, "id2", "0a0b0c0d", "body".toByteArray()))
        differs(RequestAuth.mac(key, "POST", "/p?q=1", T0, "id1", "0a0b0c0e", "body".toByteArray()))
        differs(RequestAuth.mac(key, "POST", "/p?q=1", T0, "id1", "0a0b0c0d", "bodY".toByteArray()))
        differs(RequestAuth.mac(Crypto.random(32), "POST", "/p?q=1", T0, "id1", "0a0b0c0d", "body".toByteArray()))
        assertArrayEquals(base, RequestAuth.mac(key, "post", "/p?q=1", T0, "id1", "0a0b0c0d", "body".toByteArray()))
        assertNotEquals(
            Hex.of(RequestAuth.input("POST", "/ab", T0, "c", "0a0b0c0d", ByteArray(0))),
            Hex.of(RequestAuth.input("POST", "/a", T0, "bc", "0a0b0c0d", ByteArray(0))),
        )
    }

    @Test fun anyChangeToARequestInFlightIsRefused() {
        val r = Rig()
        val good = r.req()
        assertError(CloudError.BAD_MAC) { r.verifier.verify(good.copy(body = good.body.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }), T0) }
        assertError(CloudError.BAD_MAC) { r.verifier.verify(good.copy(pathWithQuery = "/v1/chat?x=2"), T0) }
        assertError(CloudError.BAD_MAC) { r.verifier.verify(good.copy(method = "GET"), T0) }
        assertError(CloudError.BAD_MAC) { r.verifier.verify(good.copy(requestId = "req-2"), T0) }
        assertError(CloudError.BAD_MAC) { r.verifier.verify(good.copy(ts = T0 + 1), T0) }
        assertError(CloudError.BAD_MAC) { r.verifier.verify(good.copy(mac = Crypto.random(32)), T0) }
    }

    @Test fun aRequestUnderAnotherSendersNameIsRefused() {
        val r = Rig()
        val sc = sessionBetween(r.c3, r.b, dk)
        r.store.add(sc.second)
        val asA = r.req(sender = r.a, via = sc.first, id = "x1") // c3 signs with its own session but names a
        assertError(CloudError.SENDER_MISMATCH) { r.verifier.verify(asA, T0) }
        val asC = r.req(sender = r.c3, via = r.sa, id = "x2") // a's session with c3's name
        assertError(CloudError.SENDER_MISMATCH) { r.verifier.verify(asC, T0) }
        assertError(CloudError.SENDER_MISMATCH) { r.verifier.verify(r.req().copy(sender = r.c3.nodeId), T0) }
        assertEquals(r.a.nodeId, r.verifier.verify(r.req(id = "own"), T0).nodeId)
    }

    // ---- the sealed body and the sealed answer (sections 6.2, 6.4) -----------------------------------------------------------

    @Test fun aBodyThatIsNotAFrameOrWasSealedForAnotherRequestIsRefused() {
        val r = Rig()
        // right MAC over a plain body: the session path never accepts one
        val plain = "{}".toByteArray()
        val macPlain = RequestAuth.mac(r.sa.macKey, "POST", "/p", T0, "p1", r.a.nodeId, plain)
        assertError(CloudError.BAD_FRAME) { r.verifier.verify(SignedRequest("POST", "/p", T0, "p1", r.a.nodeId, plain, macPlain, sessionId = r.sa.id), T0) }
        // a frame sealed for request "other" cannot carry request "mine"
        val moved = RequestAuth.sealBody(r.sa, "other", "{}".toByteArray())
        val macMoved = RequestAuth.mac(r.sa.macKey, "POST", "/p", T0, "mine", r.a.nodeId, moved)
        assertError(CloudError.BAD_FRAME) { r.verifier.verify(SignedRequest("POST", "/p", T0, "mine", r.a.nodeId, moved, macMoved, sessionId = r.sa.id), T0) }
        // a body that is only a non-final frame is not a complete body
        val partial = r.sa.sealer(r.sa.nextStreamNo(), "q".toByteArray(), FrameType.REQUEST).seal("{}".toByteArray(), final = false)
        val macPartial = RequestAuth.mac(r.sa.macKey, "POST", "/p", T0, "q", r.a.nodeId, partial)
        assertError(CloudError.BAD_FRAME) { r.verifier.verify(SignedRequest("POST", "/p", T0, "q", r.a.nodeId, partial, macPartial, sessionId = r.sa.id), T0) }
    }

    @Test fun anAnswerCarriesItsStatusAndContentTypeInsideTheSealAndOnlyOpensForItsRequest() { // sections 6, 7, 10
        val r = Rig()
        val ok = r.verifier.verify(r.req(id = "ask"), T0)
        val frame = RequestAuth.sealAnswer(ok.session!!, "ask", 404, "application/json", """{"error":"nope"}""".toByteArray())
        val env = RequestAuth.openAnswer(r.sa, "ask", frame)
        assertEquals(404, env.status); assertEquals("application/json", env.contentType); assertEquals("""{"error":"nope"}""", String(env.body))
        assertError(CloudError.BAD_FRAME) { RequestAuth.openAnswer(r.sa, "another-request", frame) } // moved to another request
        assertError(CloudError.BAD_FRAME) { RequestAuth.openAnswer(r.sa, "ask", frame.copyOf().also { it[16] = (it[16].toInt() xor 1).toByte() }) } // the sealed status
        // a 200 recorded for one request cannot stand in for an error answer of another
        val success = RequestAuth.sealAnswer(ok.session, "first", 200, "application/json", "{}".toByteArray())
        assertError(CloudError.BAD_FRAME) { RequestAuth.openAnswer(r.sa, "second", success) }
    }

    // ---- replay, retry and the stored result (Codex Request.kt:161) ----------------------------------------------------------

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
        assertTrue(r.verifier.verify(retry, T0 + 60).resend)
        assertError(CloudError.REPLAYED) { r.verifier.verify(first, T0 + 70) }
        assertError(CloudError.REPLAYED) { r.verifier.verify(retry, T0 + 80) }
        assertTrue(r.verifier.verify(r.req(ts = T0 + 90), T0 + 100).resend)
    }

    @Test fun aRetryAfterARekeyIsStillARetryBecauseTheLogicalRequestIsCompared() { // Codex Request.kt:161 (medium)
        val r = Rig()
        r.verifier.verify(r.req(id = "job-7", body = """{"prompt":"x"}"""), T0)
        // a new exchange between the same two members: new keys, so the sealed bytes of the very same request differ
        val (sa2, sb2) = sessionBetween(r.a, r.b, dk, T0 + 1)
        r.store.add(sb2)
        val again = r.req(ts = T0 + 2, id = "job-7", body = """{"prompt":"x"}""", via = sa2)
        assertFalse(again.body.contentEquals(r.req(id = "job-7", body = """{"prompt":"x"}""").body))
        assertTrue(r.verifier.verify(again, T0 + 3).resend) // the stored result is returned, not request_id_reused
        // a different request under the same id is still refused
        assertError(CloudError.REQUEST_ID_REUSED) { r.verifier.verify(r.req(ts = T0 + 4, id = "job-7", body = """{"prompt":"y"}""", via = sa2), T0 + 4) }
    }

    @Test fun theGuardIsKeyedByTheVerifiedMemberSoAnotherMemberCannotPoisonAnId() { // Codex Request.kt:161 (high)
        val r = Rig()
        val sc = sessionBetween(r.c3, r.b, dk)
        r.store.add(sc.second)
        // c3 uses the very request id that a will use, with a different body, and gets there first
        assertFalse(r.verifier.verify(r.req(id = "shared", body = "{\"who\":\"c3\"}", sender = r.c3, via = sc.first), T0).resend)
        // a's own request with that id is a first use, not request_id_reused
        assertFalse(r.verifier.verify(r.req(id = "shared", body = "{\"who\":\"a\"}"), T0 + 1).resend)
        // the guard itself separates members
        val g = ReplayGuard()
        val fp = ByteArray(32)
        assertEquals(ReplayGuard.Admit.FIRST, g.admit("member-1", "id", Crypto.random(32), fp))
        assertEquals(ReplayGuard.Admit.FIRST, g.admit("member-2", "id", Crypto.random(32), Crypto.random(32)))
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
        assertFalse(r.verifier.verify(r.req(), T0).resend)
        assertEquals(1, r.guard.size)
    }

    @Test fun theReplayMemoryRefusesInsteadOfEvictingAndForgetsAfterItsTtl() {
        var now = 0L
        val g = ReplayGuard(ttlMs = 1_000, maxEntries = 2, clock = { now })
        val fp = ByteArray(32)
        val m1 = Crypto.random(32)
        g.admit("m1", "id1", m1, fp)
        g.admit("m1", "id2", Crypto.random(32), fp)
        assertError(CloudError.BUSY) { g.admit("m1", "id3", Crypto.random(32), fp) }
        assertError(CloudError.REPLAYED) { g.admit("m1", "id1", m1, fp) } // id1 was not evicted to make room
        now = 5_000
        g.admit("m1", "id3", Crypto.random(32), fp)
        assertEquals(1, g.size)
    }

    // ---- the window and the life of the process ------------------------------------------------------------------------

    @Test fun theThirtySecondWindowHolds() {
        val r = Rig()
        r.verifier.verify(r.req(id = "a", ts = T0), T0 + 30_000)
        assertError(CloudError.SKEW) { r.verifier.verify(r.req(id = "b", ts = T0), T0 + 30_001) }
        r.verifier.verify(r.req(id = "c", ts = T0 + 30_000), T0)
        assertError(CloudError.SKEW) { r.verifier.verify(r.req(id = "d", ts = T0 + 30_001), T0) }
    }

    @Test fun aRequestSignedBeforeThisProcessStartedIsRefused() {
        val r = Rig()
        assertError(CloudError.SKEW) { r.verifier.verify(r.req(ts = T0 - 2_000), T0) }
    }

    @Test fun anUnknownExpiredOrEndedSessionIsUnknown() {
        val r = Rig()
        assertError(CloudError.UNKNOWN_SESSION) { r.verifier.verify(r.req().copy(sessionId = "00112233445566ff"), T0) }
        assertError(CloudError.UNKNOWN_SESSION) { r.verifier.verify(r.req(ts = T0 + Session.LIFETIME_MS), T0 + Session.LIFETIME_MS) }
        val r2 = Rig()
        r2.cloud.removeAndRotate(r2.a.id) // the sender is removed, and b hears of it
        r2.cloud.copyTo(r2.b)
        assertError(CloudError.UNKNOWN_SESSION) { r2.verifier.verify(r2.req(), T0) }
    }

    @Test fun malformedRequestsAreRefusedBeforeAnyKeyIsLookedUp() {
        val r = Rig()
        assertError(CloudError.MALFORMED) { r.verifier.verify(r.req().copy(sessionId = null), T0) }
        assertError(CloudError.MALFORMED) { r.verifier.verify(r.req().copy(tokenKeyId = "00".repeat(16)), T0) }
        assertError(CloudError.MALFORMED) { r.verifier.verify(r.req().copy(requestId = "bad id"), T0) }
        assertError(CloudError.MALFORMED) { r.verifier.verify(r.req().copy(sender = "xyz"), T0) }
    }

    // ---- the unsealed loopback mode (8.3) and the boot nonce (Codex Request.kt:130) ---------------------------------------------

    private inner class TokenRig(kind: MemberKind = MemberKind.CLIENT) {
        val parts = cloudWith(1)
        val cloud = parts.first
        val issuerPeer = parts.second[0]
        val issuer = TokenIssuer(issuerPeer.id)
        val m = issuer.prepare()
        val guard = ReplayGuard()
        val verifier: RequestVerifier
        val kid = Hex.of(m.keyId)

        init {
            cloud.chain.addToken(cloud.owner.identity, issuerPeer.id, m.nodeId, "test script", kind, m.keyId, T0)
            cloud.copyTo(issuerPeer)
            verifier = RequestVerifier(SessionStore(issuerPeer.id), issuer, { issuerPeer.chain.state() }, guard, T0 - 1_000)
        }

        fun req(ts: Long = T0, id: String = "t-1", sender: String = m.nodeId, key: ByteArray = issuer.loopbackMacKey(kid)!!, kidHex: String = kid, body: String = "{}"): SignedRequest {
            val bytes = body.toByteArray()
            return SignedRequest("POST", "/v1/chat", ts, id, sender, bytes, RequestAuth.mac(key, "POST", "/v1/chat", ts, id, sender, bytes), tokenKeyId = kidHex)
        }
    }

    @Test fun anUnsealedTokenRequestIsAcceptedFromLoopbackOnly() {
        val t = TokenRig()
        val ok = t.verifier.verify(t.req(), T0, fromLoopback = true)
        assertEquals(MemberKind.CLIENT, ok.kind); assertTrue(ok.viaToken); assertEquals("{}", String(ok.body)); assertNull(ok.session)
        assertError(CloudError.FORBIDDEN) { t.verifier.verify(t.req(id = "t-2"), T0, fromLoopback = false) } // from the Wi-Fi: never
        val browser = TokenRig(MemberKind.BROWSER)
        assertEquals(MemberKind.BROWSER, browser.verifier.verify(browser.req(), T0, fromLoopback = true).kind)
    }

    @Test fun aLoopbackTokenRequestFailsWithAWrongKeyAnUnknownKeyIdOrAnotherSendersName() {
        val t = TokenRig()
        assertError(CloudError.BAD_MAC) { t.verifier.verify(t.req(key = Crypto.random(32)), T0, true) }
        assertError(CloudError.UNKNOWN_KEY) { t.verifier.verify(t.req(kidHex = "ab".repeat(16)), T0, true) }
        assertError(CloudError.SENDER_MISMATCH) { t.verifier.verify(t.req(sender = t.issuerPeer.nodeId), T0, true) }
        assertError(CloudError.MALFORMED) { t.verifier.verify(t.req(kidHex = "abcd"), T0, true) }
    }

    @Test fun aLoopbackTokenRequestIsNotReplayableNotEvenAfterTheIssuerRestarts() { // Codex Request.kt:130
        val t = TokenRig()
        val q = t.req(ts = T0 + 20_000) // signed 20 s in the future, still inside the window
        t.verifier.verify(q, T0, true)
        assertError(CloudError.REPLAYED) { t.verifier.verify(q, T0 + 1, true) }
        // the issuer restarts: the same key is restored, the replay memory is empty, the start time is later than the signing time
        val restarted = TokenIssuer(t.issuerPeer.id)
        restarted.restore(t.kid, t.issuer.key(t.kid)!!)
        val after = RequestVerifier(SessionStore(t.issuerPeer.id), restarted, { t.issuerPeer.chain.state() }, ReplayGuard(), startedAtMs = T0 + 1)
        assertNotEquals(Hex.of(t.issuer.bootNonce), Hex.of(restarted.bootNonce))
        assertError(CloudError.BAD_MAC) { after.verify(q, T0 + 2, true) } // the MAC key includes the boot nonce
    }

    @Test fun aRemovedTokenClientOrARemovedIssuerIsUnknownAtOnce() {
        val t = TokenRig()
        t.verifier.verify(t.req(id = "ok"), T0, true)
        t.cloud.remove(t.issuer.memberId(t.m.keyId)); t.cloud.copyTo(t.issuerPeer)
        assertError(CloudError.UNKNOWN_KEY) { t.verifier.verify(t.req(id = "after"), T0, true) }
        val t2 = TokenRig(MemberKind.BROWSER)
        t2.cloud.removeAndRotate(t2.issuerPeer.id); t2.cloud.copyTo(t2.issuerPeer)
        assertError(CloudError.UNKNOWN_KEY) { t2.verifier.verify(t2.req(id = "later"), T0, true) }
    }

    @Test fun aTokenClientNeverComputesForOthersOrLendsMemory() {
        for (k in listOf(MemberKind.CLIENT, MemberKind.BROWSER)) for (a in listOf(Action.WORK_FOR_PEER, Action.LEND_MEMORY, Action.STORE_FOR_PEER, Action.MANAGE_MEMBERS)) {
            assertFalse(Permissions.allowed(k, a, local = true))
            assertFalse(Permissions.allowed(k, a, local = true, sealed = false, loopback = true))
        }
    }
}
