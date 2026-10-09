package com.swarmos.app.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SignerTest {
    private val secret = "swarmos-dev-secret"
    private val now = 1_800_000_000_000L

    @Test fun hmacMatchesRfc4231Vector() {
        // RFC 4231 test case 2
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            Signer.hmacSha256Hex("Jefe", "what do ya want for nothing?"),
        )
    }

    @Test fun signThenVerify() {
        val sig = Signer.sign(secret, "POST", "/v1/choir/round", now, """{"a":1}""")
        assertTrue(Signer.verify(secret, "POST", "/v1/choir/round", now, """{"a":1}""", sig, nowMs = now + 5_000))
    }

    @Test fun tamperedBodyFails() {
        val sig = Signer.sign(secret, "POST", "/v1/choir/round", now, """{"a":1}""")
        assertFalse(Signer.verify(secret, "POST", "/v1/choir/round", now, """{"a":2}""", sig, nowMs = now))
    }

    @Test fun tamperedPathMethodOrSecretFails() {
        val sig = Signer.sign(secret, "GET", "/v1/health", now, "")
        assertFalse(Signer.verify(secret, "GET", "/v1/other", now, "", sig, nowMs = now))
        assertFalse(Signer.verify(secret, "POST", "/v1/health", now, "", sig, nowMs = now))
        assertFalse(Signer.verify("other-secret", "GET", "/v1/health", now, "", sig, nowMs = now))
    }

    @Test fun staleTimestampFails() {
        val sig = Signer.sign(secret, "GET", "/v1/health", now, "")
        assertTrue(Signer.verify(secret, "GET", "/v1/health", now, "", sig, nowMs = now + 29_000))
        assertFalse(Signer.verify(secret, "GET", "/v1/health", now, "", sig, nowMs = now + 31_000))
        assertFalse(Signer.verify(secret, "GET", "/v1/health", now, "", sig, nowMs = now - 31_000))
    }
}

class AuditLogTest {
    private fun newLog(): Pair<AuditLog, File> {
        val f = File(Files.createTempDirectory("audit").toFile(), "audit.jsonl")
        var t = 1000L
        return AuditLog(f) { t++ } to f
    }

    @Test fun chainVerifies() {
        val (log, _) = newLog()
        log.append("a", JsonObject(mapOf("n" to JsonPrimitive(1))))
        log.append("b")
        log.append("c", JsonObject(mapOf("s" to JsonPrimitive("x"))))
        val v = log.verify()
        assertTrue(v.reason, v.ok)
        assertEquals(3, v.entries)
        assertEquals(AuditLog.GENESIS, log.entries().first().prevHash)
        assertEquals(log.entries()[0].hash, log.entries()[1].prevHash)
    }

    @Test fun chainSurvivesReopen() {
        val (log, f) = newLog()
        log.append("a")
        val reopened = AuditLog(f)
        reopened.append("b")
        assertTrue(reopened.verify().ok)
        assertEquals(2, reopened.verify().entries)
    }

    @Test fun editedEntryIsDetected() {
        val (log, f) = newLog()
        log.append("a", JsonObject(mapOf("n" to JsonPrimitive(1))))
        log.append("b")
        log.append("c")
        f.writeText(f.readText().replaceFirst("\"n\":1", "\"n\":9"))
        val v = log.verify()
        assertFalse(v.ok)
        assertEquals(1, v.badLine)
    }

    @Test fun deletedEntryIsDetected() {
        val (log, f) = newLog()
        log.append("a")
        log.append("b")
        log.append("c")
        val lines = f.readLines()
        f.writeText(listOf(lines[0], lines[2]).joinToString("\n") + "\n")
        val v = log.verify()
        assertFalse(v.ok)
        assertNotNull(v.badLine)
        assertEquals(2, v.badLine)
    }
}
