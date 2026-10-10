package com.swarmos.app.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataOutputStream
import java.net.Socket

private const val KEY = "test-secret"

class ResponseAuthTest {
    @Test fun aGenuineAnswerVerifies() {
        val sig = ResponseAuth.sign(KEY, 200, "req-1", """{"ok":true}""")
        assertTrue(ResponseAuth.verify(KEY, 200, "req-1", """{"ok":true}""", sig))
        assertTrue("case of the hex does not matter", ResponseAuth.verify(KEY, 200, "req-1", """{"ok":true}""", sig.uppercase()))
    }

    @Test fun anyChangedPartMakesItUntrusted() {
        val sig = ResponseAuth.sign(KEY, 200, "req-1", "body")
        assertFalse("other status", ResponseAuth.verify(KEY, 500, "req-1", "body", sig))
        assertFalse("other request id: a recorded answer cannot be replayed for another request", ResponseAuth.verify(KEY, 200, "req-2", "body", sig))
        assertFalse("other body", ResponseAuth.verify(KEY, 200, "req-1", "bodY", sig))
        assertFalse("other secret", ResponseAuth.verify("another-secret", 200, "req-1", "body", sig))
        assertFalse("missing header", ResponseAuth.verify(KEY, 200, "req-1", "body", null))
        assertFalse("garbage header", ResponseAuth.verify(KEY, 200, "req-1", "body", "deadbeef"))
    }

    @Test fun aStreamsFinalEventCarriesAMacBoundToTheRequestAndTheEvent() {
        val final = """{"done":true,"tokens":42}"""
        val mac = ResponseAuth.signStream(KEY, "req-9", final)
        assertTrue(ResponseAuth.verifyStream(KEY, "req-9", final, mac))
        assertFalse("other request", ResponseAuth.verifyStream(KEY, "req-8", final, mac))
        assertFalse("other event data", ResponseAuth.verifyStream(KEY, "req-9", """{"done":true,"tokens":43}""", mac))
        assertFalse("other secret", ResponseAuth.verifyStream("x", "req-9", final, mac))
        assertFalse("a stream with no final MAC", ResponseAuth.verifyStream(KEY, "req-9", final, null))
        assertFalse("stream MAC is not a plain answer MAC", ResponseAuth.verify(KEY, 200, "req-9", final, mac))
    }

    @Test fun errorAnswersAreAuthenticatedToo() {
        val body = """{"error":"clock skew","serverTimeMs":1}"""
        assertTrue(ResponseAuth.verify(KEY, 401, "r", body, ResponseAuth.sign(KEY, 401, "r", body)))
    }
}

class TputAuthTest {
    @Test fun tokenRoundTripAndTampering() {
        val now = 1_800_000_000_000L
        val t = TputAuth.token(KEY, TputProtocol.MODE_SINK, now)
        assertEquals(TputAuth.BYTES, t.size)
        assertEquals(TputProtocol.MODE_SINK, TputAuth.verify(KEY, t, now + 5_000))
        assertNull("wrong secret", TputAuth.verify("other", t, now))
        assertNull("older than 30 s", TputAuth.verify(KEY, t, now + 31_000))
        val swapped = t.copyOf().also { it[0] = TputProtocol.MODE_ECHO.toByte() }
        assertNull("mode byte is covered by the MAC", TputAuth.verify(KEY, swapped, now))
        assertNull("short token", TputAuth.verify(KEY, t.copyOf(10), now))
    }

    @Test fun serverRefusesAConnectionWithoutAValidToken() {
        val server = TputServer(0, { KEY })
        val port = server.start()
        try {
            Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 3_000
                DataOutputStream(s.getOutputStream()).apply { write(TputAuth.token("wrong", TputProtocol.MODE_ECHO)); flush() }
                // refused: the server closes without echoing
                assertTrue(runCatching { s.getOutputStream().write('a'.code); s.getOutputStream().flush(); s.getInputStream().read() }.getOrDefault(-1) < 0)
            }
            Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 3_000
                s.getOutputStream().apply { write(ByteArray(TputAuth.BYTES)); flush() } // all zero: no MAC
                assertTrue(runCatching { s.getInputStream().read() }.getOrDefault(-1) < 0)
            }
            Thread.sleep(200)
            assertTrue("both were counted as refused, was ${server.refused.get()}", server.refused.get() >= 2)
            // and a genuine client still works
            assertEquals(5, TputClient({ KEY }) { Socket("127.0.0.1", port) }.rtt(5).n)
        } finally { server.stop() }
    }

    @Test fun serverCapsTheNumberOfConnections() {
        val server = TputServer(0, { KEY }, TputLimits(maxConnections = 1))
        val port = server.start()
        try {
            val first = Socket("127.0.0.1", port)
            first.getOutputStream().apply { write(TputAuth.token(KEY, TputProtocol.MODE_ECHO)); flush() } // holds the only slot
            Thread.sleep(200)
            Socket("127.0.0.1", port).use { second ->
                second.soTimeout = 2_000
                // the server closes it at once, so the write or the read may already fail: both mean "not served"
                val served = runCatching {
                    second.getOutputStream().apply { write(TputAuth.token(KEY, TputProtocol.MODE_ECHO)); write('a'.code); flush() }
                    second.getInputStream().read()
                }.getOrDefault(-1)
                assertTrue("second connection is closed, not served", served < 0)
            }
            assertTrue(server.refused.get() >= 1)
            first.close()
        } finally { server.stop() }
    }

    @Test fun aSinkOverItsByteCapIsCutOff() {
        val server = TputServer(0, { KEY }, TputLimits(maxBytes = 2L * 1024 * 1024))
        val port = server.start()
        try {
            // 4 MiB offered, cap is 2 MiB: no receipt comes back, the client sees the connection fail
            val failed = runCatching { TputClient({ KEY }) { Socket("127.0.0.1", port) }.throughput(4) }.isFailure
            assertTrue(failed)
        } finally { server.stop() }
    }
}

class LocalAddressTest {
    @Test fun onlyPrivateAndLinkLocalAddressesAreLocal() {
        for (ok in listOf("10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.242.89", "192.168.49.1", "169.254.10.10")) assertTrue(ok, LinkRoute.isLocalAddress(ok))
        for (bad in listOf("8.8.8.8", "172.32.0.1", "172.15.0.1", "100.64.0.1", "127.0.0.1", "0.0.0.0", "1.2.3", "example.com", "192.168.1.256", "::1", ""))
            assertFalse(bad, LinkRoute.isLocalAddress(bad))
    }
}

class ClockSkewReasonTest {
    @Test fun a401WithASkewBodySaysHowFarTheClocksAreApart() {
        val body = """{"error":"clock skew","serverTimeMs":1800000047000}"""
        val r = PeerLinks.reasonOf401(NodeHttpException(401, body), nowMs = 1_800_000_000_000L)
        assertEquals("clock off by 47 s", r)
    }

    @Test fun anyOther401MeansADifferentSecret() {
        assertEquals("different pairing secret", PeerLinks.reasonOf401(NodeHttpException(401, """{"error":"bad signature"}""")))
        assertEquals("different pairing secret", PeerLinks.reasonOf401(NodeHttpException(401, "not json")))
    }
}

class HeartbeatThreadSafetyTest {
    @Test fun counterStaysExactUnderTwoThreads() {
        val t = HeartbeatTracker(missesToAway = 1_000_000)
        val a = Thread { repeat(20_000) { t.onMiss() } }
        val b = Thread { repeat(20_000) { t.onMiss() } }
        a.start(); b.start(); a.join(); b.join()
        assertEquals(40_000, t.missesTotal)
    }
}
