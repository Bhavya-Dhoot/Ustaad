package com.swarmos.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertNotEquals
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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

    private fun archivesOf(d: File) = d.listFiles()!!.filter { it.name.matches(Regex("audit-\\d+\\.jsonl")) }.sortedBy { it.name.filter(Char::isDigit).toInt() }

    @Test fun verifyDoesNotHoldTheLockWhileItReads() {
        val d = dir()
        val log = newLog(d)
        repeat(5) { log.append("e$it") }
        val parked = CountDownLatch(1)
        val release = CountDownLatch(1)
        log.afterSnapshot = { parked.countDown(); release.await(10, TimeUnit.SECONDS) }
        val result = AtomicReference<AuditVerify>()
        val verifier = Thread { result.set(log.verify()) }.also { it.start() }
        assertTrue(parked.await(5, TimeUnit.SECONDS)) // verify has its snapshot and is waiting outside the lock

        val seenCount = AtomicInteger(-1)
        val writer = Thread { log.append("while.verifying"); seenCount.set(log.count) }.also { it.start() }
        writer.join(3000)
        assertFalse("append and count waited for verify", writer.isAlive)
        assertEquals(6, seenCount.get())

        release.countDown()
        verifier.join(5000)
        val v = result.get()
        assertTrue(v.reason, v.ok)
        assertEquals(5, v.entries) // what the snapshot held, not the entry appended meanwhile
        assertEquals(6, log.verify().entries)
    }

    @Test fun keepsAtMostTheStatedNumberOfArchivesAndRecordsThePruning() {
        val d = dir()
        var t = 1000L
        val log = AuditLog(File(d, "audit.jsonl"), 600, 2) { t++ }
        repeat(30) { log.append("event.$it") }
        val archives = archivesOf(d)
        assertTrue("archives: ${archives.map { it.name }}", archives.size <= 2)
        val entries = log.entries()
        val pruned = entries.filter { it.kind == AuditLog.PRUNED }
        assertTrue(pruned.isNotEmpty())
        val v = log.verify()
        assertTrue(v.reason, v.ok)
        assertEquals(entries.size, v.entries)
        assertEquals(entries.size, log.count)
        // the oldest file left does not start at GENESIS; an audit.pruned entry names the hash it chains from
        val start = firstHash(archives.first())
        assertNotEquals(AuditLog.GENESIS, start)
        val anchors = pruned.map { (it.payload as JsonObject)["anchor"]!!.jsonPrimitive.content }
        assertTrue(start in anchors)
        assertTrue(pruned.last().payload.toString().contains("\"archives\""))
    }

    @Test fun aPrunedLogReopensWithTheSameCountAndAValidChain() {
        val d = dir()
        val log = AuditLog(File(d, "audit.jsonl"), 600, 2)
        repeat(30) { log.append("event.$it") }
        val reopened = AuditLog(File(d, "audit.jsonl"), 600, 2)
        assertEquals(log.count, reopened.count)
        assertEquals(log.lastEntryHash, reopened.lastEntryHash)
        reopened.append("after.reopen")
        val v = reopened.verify()
        assertTrue(v.reason, v.ok)
        assertEquals(reopened.count, v.entries)
    }

    @Test fun removingTheOldestFileWithoutAPruningEntryIsStillReported() {
        val d = dir()
        val log = newLog(d, rotate = 600)
        repeat(12) { log.append("event.$it") }
        assertTrue(archivesOf(d).first().delete())
        val v = log.verify()
        assertFalse(v.ok)
        assertEquals(1, v.badLine)
    }

    @Test fun aFileDeletedByPruningDuringAReadMakesVerifyStartAgain() {
        val d = dir()
        var t = 1000L
        val log = AuditLog(File(d, "audit.jsonl"), 600, 2) { t++ }
        repeat(10) { log.append("a$it") }
        val parked = CountDownLatch(1)
        val release = CountDownLatch(1)
        var first = true
        log.afterSnapshot = { if (first) { first = false; parked.countDown(); release.await(10, TimeUnit.SECONDS) } }
        val result = AtomicReference<AuditVerify>()
        val verifier = Thread { result.set(log.verify()) }.also { it.start() }
        assertTrue(parked.await(5, TimeUnit.SECONDS))
        repeat(25) { log.append("b$it") } // rotates and prunes files the first snapshot listed
        release.countDown()
        verifier.join(5000)
        val v = result.get()
        assertTrue(v.reason, v.ok)
        assertEquals(log.count, v.entries)
    }
}
