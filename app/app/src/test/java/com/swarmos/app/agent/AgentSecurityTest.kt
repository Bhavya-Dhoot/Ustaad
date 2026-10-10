package com.swarmos.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64

/** The small pure pieces the agent endpoint's trust rests on: the key, the key check, the loopback check, the gate. */
class AgentSecurityTest {
    // ---- the key --------------------------------------------------------------------------------------------

    @Test fun aGeneratedKeyIsThirtyTwoRandomBytesInBase64Url() {
        val k = AgentKey.generate()
        assertEquals(43, k.length) // 32 bytes, no padding
        assertTrue(k.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' })
        assertEquals(32, Base64.getUrlDecoder().decode(k).size)
    }

    @Test fun generatedKeysDiffer() {
        val keys = List(50) { AgentKey.generate() }.toSet()
        assertEquals(50, keys.size)
    }

    @Test fun theKeyComesFromTheGivenRandomSource() {
        val a = AgentKey.generate(seeded(1))
        val b = AgentKey.generate(seeded(1))
        assertEquals(a, b)
        assertNotEquals(a, AgentKey.generate(seeded(2)))
    }

    private fun seeded(seed: Long) = object : SecureRandom() {
        private val r = java.util.Random(seed)
        override fun nextBytes(bytes: ByteArray) = r.nextBytes(bytes)
    }

    // ---- the Authorization header ---------------------------------------------------------------------------

    @Test fun bearerTokenIsTakenFromTheHeader() {
        assertEquals("abc", AgentKey.bearerToken("Bearer abc"))
        assertEquals("abc", AgentKey.bearerToken("bearer abc"))
        assertEquals("abc", AgentKey.bearerToken("  Bearer   abc  "))
    }

    @Test fun otherHeadersGiveNoToken() {
        assertNull(AgentKey.bearerToken(null))
        assertNull(AgentKey.bearerToken(""))
        assertNull(AgentKey.bearerToken("Bearer"))
        assertNull(AgentKey.bearerToken("Bearer    "))
        assertNull(AgentKey.bearerToken("Basic dXNlcjpwYXNz"))
        assertNull(AgentKey.bearerToken("abc"))
    }

    // ---- the comparison -------------------------------------------------------------------------------------

    @Test fun theRightKeyMatches() {
        val k = AgentKey.generate()
        assertTrue(AgentKey.matches(k, k))
        assertTrue(AgentKey.matches(k, String(k.toCharArray())))
    }

    @Test fun aWrongKeyDoesNot() {
        val k = AgentKey.generate()
        assertFalse(AgentKey.matches(k, AgentKey.generate()))
        assertFalse(AgentKey.matches(k, k.dropLast(1)))
        assertFalse(AgentKey.matches(k, k + "x"))
        assertFalse(AgentKey.matches(k, k.uppercase()))
        assertFalse(AgentKey.matches(k, ""))
        assertFalse(AgentKey.matches(k, null))
    }

    @Test fun noStoredKeyMatchesNothing() {
        assertFalse(AgentKey.matches(null, "anything"))
        assertFalse(AgentKey.matches("", ""))
        assertFalse(AgentKey.matches(null, null))
    }

    // ---- loopback -------------------------------------------------------------------------------------------

    @Test fun loopbackAddressesAreThisPhone() {
        for (a in listOf("127.0.0.1", "127.0.0.2", "127.255.255.254", "::1", "0:0:0:0:0:0:0:1", "0000:0000:0000:0000:0000:0000:0000:0001", "[::1]", "::ffff:127.0.0.1", "0:0:0:0:0:ffff:7f00:1", " 127.0.0.1 ", "::1%lo")) {
            assertTrue("$a should be loopback", AgentLoopback.isLoopback(a))
        }
    }

    @Test fun everythingElseIsNot() {
        for (a in listOf(
            null, "", " ", "localhost", "192.168.1.5", "10.0.0.1", "0.0.0.0", "128.0.0.1", "126.255.255.255", "1.127.0.0.1",
            "127.0.0", "127.0.0.1.1", "127.0.0.256", "127.0.0.x", "-127.0.0.1", "+127.0.0.1", "::", "::2", "fe80::1", "2001:db8::1",
            "::ffff:192.168.1.5", ":::", "1:2:3:4:5:6:7:8:9", "::1::1", "::g", "0:0:0:0:0:0:0:01234", "example.com", "127.0.0.1@evil.com",
        )) {
            assertFalse("$a should not be loopback", AgentLoopback.isLoopback(a))
        }
    }

    // ---- the gate -------------------------------------------------------------------------------------------

    private fun check(remote: String?, auth: String?, key: String?, host: String? = "127.0.0.1:8790") =
        AgentGate.check(remote, host, 8790, auth, key)

    @Test fun onlyThePhonesOwnAddressWithThisPortIsAHostName() {
        for (h in listOf("127.0.0.1:8790", "localhost:8790", "LOCALHOST:8790", "[::1]:8790", " 127.0.0.1:8790 ")) {
            assertTrue("$h should be served", AgentGate.hostAllowed(h, 8790))
        }
        for (h in listOf(null, "", "127.0.0.1", "127.0.0.1:8791", "localhost", "localhost:80", "evil.example:8790", "127.0.0.1.evil.example:8790", "192.168.1.5:8790", "[::1]", "0.0.0.0:8790", "127.0.0.1:8790@evil.example", "evil.example")) {
            assertFalse("$h should be refused", AgentGate.hostAllowed(h, 8790))
        }
    }

    @Test fun aRebindingPageIsRefusedEvenWithTheRightKey() {
        val r = check("127.0.0.1", "Bearer k1", "k1", host = "evil.example:8790")!!
        assertEquals(403, r.status)
        assertEquals("not_local", r.code)
        assertEquals(403, check("127.0.0.1", "Bearer k1", "k1", host = null)!!.status)
    }

    @Test fun theRightKeyFromThisPhonePasses() {
        assertNull(check("127.0.0.1", "Bearer k1", "k1"))
    }

    @Test fun noKeyIs401() {
        assertEquals(401, check("127.0.0.1", null, "k1")!!.status)
        assertEquals(401, check("127.0.0.1", "", "k1")!!.status)
        assertEquals("invalid_api_key", check("127.0.0.1", null, "k1")!!.code)
    }

    @Test fun aWrongKeyIs401() {
        assertEquals(401, check("127.0.0.1", "Bearer k2", "k1")!!.status)
        assertEquals(401, check("127.0.0.1", "Basic k1", "k1")!!.status)
    }

    @Test fun noStoredKeyIs401EvenForAnEmptyToken() {
        assertEquals(401, check("127.0.0.1", "Bearer x", null)!!.status)
        assertEquals(401, check("127.0.0.1", "Bearer ", "")!!.status)
    }

    @Test fun aPeerThatIsNotThisPhoneIsRefusedEvenWithTheRightKey() {
        val r = check("192.168.1.5", "Bearer k1", "k1")!!
        assertEquals(403, r.status)
        assertEquals("not_local", r.code)
        assertEquals(403, check(null, "Bearer k1", "k1")!!.status)
    }
}
