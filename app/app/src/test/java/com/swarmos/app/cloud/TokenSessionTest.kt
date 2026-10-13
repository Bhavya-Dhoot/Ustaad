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
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class TokenSessionTest {
    private inner class Rig {
        val parts = cloudWith(1)
        val cloud = parts.first
        val issuerPeer = parts.second[0]
        val issuer = TokenIssuer(issuerPeer.id)
        val material = issuer.prepare()
        val store = SessionStore(issuerPeer.id)
        val guard = ReplayGuard()

        init {
            cloud.chain.addToken(cloud.owner.identity, issuerPeer.id, material.nodeId, "browser tab", MemberKind.BROWSER, material.keyId, T0)
            cloud.copyTo(issuerPeer)
        }

        val state: ChainState get() = issuerPeer.chain.state()
        val verifier = RequestVerifier(store, issuer, { issuerPeer.chain.state() }, guard, T0 - 1_000)
        fun client(key: ByteArray = material.key) = TokenClient(material.keyId, key, material.nodeId, issuerPeer.nodeId, issuerPeer.id)

        /** hello, welcome, both sessions */
        fun open(now: Long = T0, c: TokenClient = client()): Session {
            val started = c.start()
            val welcome = issuer.hello(started.hello, now, state)
            return c.finish(started, welcome, now)
        }

        fun request(s: Session, id: String, plain: String, ts: Long = T0): SignedRequest {
            val wire = RequestAuth.sealBody(s, id, plain.toByteArray())
            return SignedRequest("POST", "/v1/ask", ts, id, material.nodeId, wire, RequestAuth.mac(s.macKey, "POST", "/v1/ask", ts, id, material.nodeId, wire), sessionId = s.id)
        }
    }

    @Test fun aTokenClientRunsASealedSessionAndGetsASealedAnswer() { // Codex section 16, open point 5
        val r = Rig()
        val s = r.open()
        assertEquals(16, s.id.length)
        val ok = r.verifier.verify(r.request(s, "q-1", """{"question":"what is in my notes?"}"""), T0 + 1)
        assertTrue(ok.viaToken); assertEquals(MemberKind.BROWSER, ok.kind); assertEquals(r.material.nodeId, ok.nodeId)
        assertEquals("""{"question":"what is in my notes?"}""", String(ok.body))
        val answer = RequestAuth.sealAnswer(ok.session!!, "q-1", 200, "text/plain", "private result".toByteArray())
        assertEquals(-1, Collections.indexOf(answer, "private result".toByteArray())) // a LAN observer sees ciphertext
        assertEquals("private result", String(RequestAuth.openAnswer(s, "q-1", answer).body))
        // the request body is sealed too
        val wire = r.request(s, "q-2", "secret question").body
        assertEquals(-1, Collections.indexOf(wire, "secret question".toByteArray()))
    }

    private object Collections {
        fun indexOf(hay: ByteArray, needle: ByteArray): Int {
            outer@ for (i in 0..hay.size - needle.size) {
                for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
                return i
            }
            return -1
        }
    }

    @Test fun theKeysFollowTheDocumentAndNeedOnlyHmacHkdfAndAesGcm() { // implementable in plain JavaScript
        val r = Rig()
        val c = r.client()
        val started = c.start()
        val welcome = r.issuer.hello(started.hello, T0, r.state)
        val s = c.finish(started, welcome, T0)
        // recompute everything with nothing but javax.crypto.Mac and Cipher, as a page in a browser would
        fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
            val m = Mac.getInstance("HmacSHA256"); m.init(SecretKeySpec(key, "HmacSHA256")); parts.forEach { m.update(it) }; return m.doFinal()
        }
        fun lp(s: String): ByteArray { val b = ("ustaad/v1/" + s).toByteArray(); return byteArrayOf(0, 0, 0, b.size.toByte()) + b }
        val rd = Reader(welcome); rd.u8(); val sn = rd.fixed(32); val boot = rd.fixed(16); val mac = rd.fixed(32)
        val cn = started.hello.copyOfRange(17, 49)
        assertArrayEquals(hmac(r.material.key, lp("token/welcome"), r.material.keyId, cn, sn, boot), mac)
        val prk = hmac(cn + sn + boot, r.material.key) // HKDF-Extract(salt = cn || sn || boot, ikm = K)
        fun expand(label: String, len: Int): ByteArray = hmac(prk, lp(label), byteArrayOf(1)).copyOf(len) // one block is enough for 32 bytes or fewer
        assertArrayEquals(expand("token/mac", 32), s.macKey)
        assertEquals(Hex.of(expand("token/id", 8)), s.id)
        // the c2s sealing key opens what the client seals: AES-256-GCM, stream key = HKDF-Expand(c2s, lp("frame/stream") || u8 1 || u32 stream || lp(context))
        val frame = s.sealer(5, "ctx".toByteArray(), FrameType.REQUEST).seal("hello".toByteArray(), final = true)
        val c2s = expand("token/seal/c2s", 32)
        val info = lp("frame/stream") + byteArrayOf(1) + byteArrayOf(0, 0, 0, 5) + byteArrayOf(0, 0, 0, 3) + "ctx".toByteArray()
        val streamKey = hmac(c2s, info, byteArrayOf(1))
        val aes = Cipher.getInstance("AES/GCM/NoPadding")
        aes.init(Cipher.DECRYPT_MODE, SecretKeySpec(streamKey, "AES"), GCMParameterSpec(128, ByteArray(12)))
        aes.updateAAD(lp("frame") + frame.copyOfRange(0, 14))
        assertEquals("hello", String(aes.doFinal(frame.copyOfRange(14, frame.size))))
    }

    @Test fun theClientChecksThatTheIssuerHoldsTheKey() {
        val r = Rig()
        val started = r.client().start()
        val welcome = r.issuer.hello(started.hello, T0, r.state)
        assertError(CloudError.BAD_MAC) { r.client(Crypto.random(32)).finish(started, welcome, T0) } // a client with another key
        val forged = welcome.copyOf().also { it[5] = (it[5].toInt() xor 1).toByte() }
        assertError(CloudError.BAD_MAC) { r.client().finish(started, forged, T0) }
    }

    @Test fun aRestartedIssuerHasNoSessionsAndNewKeysSoNothingRecordedBeforeCanBeReplayedAfter() { // Codex Request.kt:130
        val r = Rig()
        val s = r.open()
        val q = r.request(s, "q-1", "{}", ts = T0 + 20_000)
        r.verifier.verify(q, T0 + 1)
        // the issuer restarts: same key restored, empty memory, a new boot nonce
        val issuer2 = TokenIssuer(r.issuerPeer.id)
        issuer2.restore(Hex.of(r.material.keyId), r.material.key)
        val store2 = SessionStore(r.issuerPeer.id)
        val verifier2 = RequestVerifier(store2, issuer2, { r.issuerPeer.chain.state() }, ReplayGuard(), T0 + 1)
        assertError(CloudError.UNKNOWN_SESSION) { verifier2.verify(q, T0 + 2) } // the session is gone
        // the same hello gives different keys
        val started = r.client().start()
        val w1 = r.issuer.hello(started.hello, T0, r.state)
        val w2 = issuer2.hello(started.hello, T0, r.state)
        val sameHello = TokenClient.Started(started.hello, started.cn) // a finish is single use, so the second issuer's Welcome gets a copy of the hello
        val s1 = r.client().finish(started, w1, T0); val s2 = r.client().finish(sameHello, w2, T0)
        assertNotEquals(s1.id, s2.id); assertFalse(s1.macKey.contentEquals(s2.macKey))
        assertNotEquals(Hex.of(r.issuer.bootNonce), Hex.of(issuer2.bootNonce))
    }

    @Test fun aHelloThatIsNeverFollowedByAProvenRequestExpiresFromThePendingTableAndAProvenOneLasts() {
        val r = Rig()
        val unused = r.open(T0)
        assertEquals(1, r.issuer.pendingCount)
        assertEquals(0, r.store.size) // a hello alone admits nothing to the store
        assertNull(r.issuer.claim(unused.id, T0 + 61_000, r.state)) // a minute later it is gone
        assertEquals(0, r.issuer.pendingCount)
        assertError(CloudError.UNKNOWN_SESSION) { r.verifier.verify(r.request(unused, "late", "{}", ts = T0 + 61_000), T0 + 61_000) }
        val used = r.open(T0)
        r.verifier.verify(r.request(used, "first", "{}"), T0 + 1_000) // the first request carries a valid MAC: the session is promoted
        assertEquals(0, r.issuer.pendingCount); assertEquals(1, r.store.size)
        assertNotNull(r.store.get(used.id, T0 + 3_600_000, r.state)) // in use: stays
    }

    @Test fun aRequestWithABadMacDoesNotPromoteAPendingSessionAndCountsAgainstNoQuota() { // Codex TokenSession.kt:88
        val r = Rig()
        val s = r.open(T0)
        val q = r.request(s, "x", "{}")
        val bad = SignedRequest(q.method, q.pathWithQuery, q.ts, q.requestId, q.sender, q.body, Crypto.random(32), sessionId = q.sessionId)
        assertError(CloudError.BAD_MAC) { r.verifier.verify(bad, T0) }
        assertEquals(0, r.store.size)
        assertEquals(1, r.issuer.pendingCount) // still pending, still unproven
        r.verifier.verify(q, T0) // the genuine request promotes it
        assertEquals(1, r.store.size)
    }

    @Test fun aFloodOfHellosNeverEvictsAnEstablishedSession() { // Codex TokenSession.kt:88
        val r = Rig()
        val established = List(4) { i -> r.open(T0).also { r.verifier.verify(r.request(it, "e-$i", "{}"), T0) } }
        assertEquals(4, r.store.size) // the quota of four per client is full
        repeat(60) { i -> r.issuer.hello(r.client().start().hello, T0 + i, r.state, source = "10.0.${i / 250}.${i % 250}") }
        // the unproven hellos live in a table of their own: at most four of this client, the oldest dropped
        assertEquals(4, r.issuer.pendingCount)
        assertEquals(4, r.store.size)
        for (s in established) assertNotNull(r.store.get(s.id, T0 + 100, r.state))
    }

    @Test fun thePendingTableHasAGlobalBoundAndExpiryFreesIt() {
        val r = Rig()
        val small = TokenIssuer(r.issuerPeer.id, maxPending = 3, maxPendingPerClient = 10, pendingMs = 5_000L)
        small.restore(Hex.of(r.material.keyId), r.material.key)
        repeat(3) { small.hello(r.client().start().hello, T0 + it, r.state, source = "10.0.0.$it") }
        assertEquals(3, small.pendingCount)
        assertError(CloudError.BUSY) { small.hello(r.client().start().hello, T0 + 10, r.state, source = "10.0.0.9") }
        small.hello(r.client().start().hello, T0 + 6_000, r.state, source = "10.0.0.9") // the first three expired
        assertEquals(1, small.pendingCount)
    }

    @Test fun theRecordsOfQuietAddressesExpireAndTheTableIsBounded() { // Codex TokenSession.kt:76
        val r = Rig()
        val few = TokenIssuer(r.issuerPeer.id, maxHellosPerMinutePerSource = 3, maxAddressRecords = 8)
        few.restore(Hex.of(r.material.keyId), r.material.key)
        repeat(8) { few.hello(r.client().start().hello, T0 + it, r.state, source = "10.0.0.$it") }
        assertEquals(8, few.addressRecords)
        assertError(CloudError.BUSY) { few.hello(r.client().start().hello, T0 + 10, r.state, source = "10.0.1.1") } // a ninth address while all are live
        few.hello(r.client().start().hello, T0 + 61_000, r.state, source = "10.0.1.1") // a minute later the old records are gone
        assertEquals(1, few.addressRecords)
    }

    @Test fun aSessionOfARemovedClientIsGoneFromThePendingTableToo() {
        val r = Rig()
        val s = r.open(T0)
        r.cloud.remove(r.issuer.memberId(r.material.keyId)); r.cloud.copyTo(r.issuerPeer)
        assertNull(r.issuer.claim(s.id, T0 + 1, r.state))
        assertEquals(0, r.issuer.pendingCount)
    }

    @Test fun theClientFinishesAHelloOnceAndASecondWelcomeNeverGivesASecondSession() { // Codex TokenSession.kt:114
        val r = Rig()
        val c = r.client()
        val started = c.start()
        val w1 = r.issuer.hello(started.hello, T0, r.state)
        val w2 = r.issuer.hello(started.hello, T0, r.state) // the same hello answered twice: another server nonce
        assertFalse(w1.contentEquals(w2))
        val s1 = c.finish(started, w1, T0)
        s1.nextStreamNo(); s1.nextStreamNo()
        assertSame(s1, c.finish(started, w1, T0 + 5)) // the same Welcome: the same session, its counters not back at zero
        assertEquals(2, s1.nextStreamNo())
        assertError(CloudError.BAD_MAC) { c.finish(started, w2, T0 + 6) } // any other Welcome for this hello: refused
        assertSame(s1, c.finish(started, w1, T0 + 7))
        // a Welcome made for another hello never fits
        val other = c.start()
        assertError(CloudError.BAD_MAC) { c.finish(other, w1, T0) }
    }

    @Test fun aBrowserSessionEndsAfterTwelveHoursWithoutARequestAndAUsedOneIsKept() { // 8.5
        val r = Rig()
        val hour = 3_600_000L
        val idle = r.open(T0)
        r.verifier.verify(r.request(idle, "a", "{}"), T0)
        val busy = r.open(T0)
        r.verifier.verify(r.request(busy, "b", "{}"), T0)
        assertNotNull(r.store.get(busy.id, T0 + 11 * hour, r.state)) // used at 11 hours
        assertEquals(1, r.store.sweep(T0 + 12 * hour + 1, r.state)) // the one nobody used: gone, and a sweep is not a use
        assertNull(r.store.get(idle.id, T0 + 12 * hour + 2, r.state))
        assertNotNull(r.store.get(busy.id, T0 + 22 * hour, r.state)) // 11 hours since its last use: alive
        assertNull(r.store.get(busy.id, T0 + 22 * hour + 12 * hour, r.state)) // and past the 24 hours of every session anyway
    }

    @Test fun aRemovedClientCannotOpenASessionAndItsSessionEnds() {
        val r = Rig()
        val s = r.open()
        r.verifier.verify(r.request(s, "a", "{}"), T0)
        r.cloud.remove(r.issuer.memberId(r.material.keyId)); r.cloud.copyTo(r.issuerPeer)
        assertError(CloudError.UNKNOWN_SESSION) { r.verifier.verify(r.request(s, "b", "{}"), T0) }
        assertError(CloudError.UNKNOWN_KEY) { r.issuer.hello(r.client().start().hello, T0, r.state) }
    }

    @Test fun helloIsBudgetedAndAnUnknownKeyIdIsRefused() {
        val r = Rig()
        val c = r.client()
        val limited = TokenIssuer(r.issuerPeer.id, maxHellosPerMinutePerSource = 3)
        limited.restore(Hex.of(r.material.keyId), r.material.key)
        repeat(3) { limited.hello(c.start().hello, T0 + it, r.state, source = "10.0.0.5") }
        assertError(CloudError.BUSY) { limited.hello(c.start().hello, T0 + 4, r.state, source = "10.0.0.5") }
        limited.hello(c.start().hello, T0 + 5, r.state, source = "10.0.0.6") // another address
        assertError(CloudError.UNKNOWN_KEY) { r.issuer.hello(TokenProtocol.hello(Crypto.random(16), Crypto.random(32)), T0, r.state) }
        assertError(CloudError.MALFORMED) { r.issuer.hello(ByteArray(5), T0, r.state) }
    }

    @Test fun anUnsealedRequestIsRefusedByTheFunctionExceptFromLoopbackForATokenClient() {
        for (k in MemberKind.values()) for (a in Action.values()) {
            assertFalse("$k $a not loopback", Permissions.allowed(k, a, local = true, sealed = false, loopback = false))
        }
        assertFalse(Permissions.allowed(MemberKind.NODE, Action.SUBMIT_TASK, local = true, sealed = false, loopback = true)) // a node is never unsealed
        assertTrue(Permissions.allowed(MemberKind.CLIENT, Action.SUBMIT_TASK, local = true, sealed = false, loopback = true))
        assertTrue(Permissions.allowed(MemberKind.BROWSER, Action.USE_FILES, local = false, sealed = false, loopback = true))
        assertFalse(Permissions.allowed(MemberKind.CLIENT, Action.LEND_MEMORY, local = true, sealed = false, loopback = true)) // still the table
    }
}
