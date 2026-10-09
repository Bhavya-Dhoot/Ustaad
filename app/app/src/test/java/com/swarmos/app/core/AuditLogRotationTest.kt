package com.swarmos.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AuditLogRotationTest {
    private fun dir(): File = Files.createTempDirectory("audit-rot").toFile()

    private fun newLog(d: File, rotate: Long = AuditLog.ROTATE_BYTES): AuditLog {
        var t = 1000L
        return AuditLog(File(d, "audit.jsonl"), rotate) { t++ }
    }

    private fun firstHash(f: File) = f.readLines().first { it.isNotBlank() }.substringAfter("\"prevHash\":\"").substringBefore("\"")
    private fun lastHash(f: File) = f.readLines().last { it.isNotBlank() }.substringAfter("\"hash\":\"").substringBefore("\"")

    @Test fun rotatesNearTheLimitAndKeepsTheChain() {
        val d = dir()
        val log = newLog(d, rotate = 600)
        repeat(12) { log.append("event.$it") }
        val archives = d.listFiles()!!.filter { it.name.matches(Regex("audit-\\d+\\.jsonl")) }.sortedBy { it.name }
        assertTrue("expected at least two rotated files, got ${archives.map { it.name }}", archives.size >= 2)
        assertEquals("audit-1.jsonl", archives.first().name)
        val active = File(d, "audit.jsonl")
        assertTrue(active.length() < 600 + 400)
        // the first line of every file links to the last hash of the file before it
        assertEquals(AuditLog.GENESIS, firstHash(archives.first()))
        val chain = archives + listOfNotNull(active.takeIf { it.exists() }) // the active file is absent right after a rotation
        for (i in 1 until chain.size) assertEquals(lastHash(chain[i - 1]), firstHash(chain[i]))
        val v = log.verify()
        assertTrue(v.reason, v.ok)
        assertEquals(12, v.entries)
        assertEquals(12, log.count)
    }

    @Test fun reopeningAfterRotationContinuesTheChain() {
        val d = dir()
        val log = newLog(d, rotate = 600)
        repeat(9) { log.append("a$it") }
        val reopened = AuditLog(File(d, "audit.jsonl"), 600)
        assertEquals(9, reopened.count)
        assertEquals(log.lastEntryHash, reopened.lastEntryHash)
        reopened.append("b")
        val v = reopened.verify()
        assertTrue(v.reason, v.ok)
        assertEquals(10, v.entries)
        assertEquals(10, reopened.entries().size)
    }

    @Test fun countAndLastHashLiveInMemoryAfterTheFirstLoad() {
        val d = dir()
        val log = newLog(d)
        log.append("a"); log.append("b"); log.append("c")
        log.load()
        File(d, "audit.jsonl").writeText("") // append must not look at the file again
        log.append("d")
        assertEquals(4, log.count)
    }

    @Test fun halfWrittenLastLineIsCutOffAtLoad() {
        val d = dir()
        val f = File(d, "audit.jsonl")
        val log = newLog(d)
        log.append("a"); log.append("b"); log.append("c")
        f.appendText("{\"ts\":12,\"kind\":\"x\",\"payload\":{\"pa") // killed in the middle of a write, no newline
        val reopened = AuditLog(f)
        reopened.load()
        assertEquals(listOf("a", "b", "c", "audit.tail_repaired"), reopened.entries().map { it.kind })
        assertEquals(4, reopened.count)
        reopened.append("d")
        val v = reopened.verify()
        assertTrue(v.reason, v.ok)
        assertEquals(5, v.entries)
        assertTrue(f.readText().endsWith("\n"))
    }

    @Test fun zeroBytesLeftByACrashAreCutOffToo() {
        val d = dir()
        val f = File(d, "audit.jsonl")
        val log = newLog(d)
        log.append("a"); log.append("b")
        f.appendBytes(ByteArray(300))
        val reopened = AuditLog(f)
        reopened.append("c")
        val v = reopened.verify()
        assertTrue(v.reason, v.ok)
        assertEquals(listOf("a", "b", "audit.tail_repaired", "c"), reopened.entries().map { it.kind })
    }

    @Test fun aCompleteLastEntryWithoutItsNewlineIsKept() {
        val d = dir()
        val f = File(d, "audit.jsonl")
        val log = newLog(d)
        log.append("a"); log.append("b")
        f.writeText(f.readText().trimEnd('\n'))
        val reopened = AuditLog(f)
        reopened.append("c")
        assertEquals(listOf("a", "b", "c"), reopened.entries().map { it.kind })
        assertTrue(reopened.verify().ok)
    }

    @Test fun damageInTheMiddleIsNotRepairedAndVerifyNamesTheEntry() {
        val d = dir()
        val f = File(d, "audit.jsonl")
        val log = newLog(d)
        log.append("a", kotlinx.serialization.json.JsonObject(mapOf("n" to kotlinx.serialization.json.JsonPrimitive(1))))
        log.append("b"); log.append("c"); log.append("d")
        f.writeText(f.readText().replaceFirst("\"n\":1", "\"n\":2"))
        val before = f.length()
        val reopened = AuditLog(f)
        reopened.load()
        assertEquals(before, f.length())
        val v = reopened.verify()
        assertFalse(v.ok)
        assertEquals(1, v.badLine)
    }

    @Test fun firstBadEntryIsCountedAcrossFiles() {
        val d = dir()
        val log = newLog(d, rotate = 600)
        repeat(12) { log.append("event.$it") }
        val second = File(d, "audit-2.jsonl")
        val lines = second.readLines()
        second.writeText(lines.drop(1).joinToString("\n") + "\n") // drop the first entry of the second archive
        val first = File(d, "audit-1.jsonl").readLines().count { it.isNotBlank() }
        val v = log.verify()
        assertFalse(v.ok)
        assertEquals(first + 1, v.badLine)
    }

    @Test fun aMissingRotatedFileBreaksTheChain() {
        val d = dir()
        val log = newLog(d, rotate = 600)
        repeat(12) { log.append("event.$it") }
        assertTrue(File(d, "audit-1.jsonl").delete())
        assertFalse(log.verify().ok)
    }
}
