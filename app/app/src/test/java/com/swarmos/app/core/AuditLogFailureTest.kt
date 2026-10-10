package com.swarmos.app.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** A failed write must not tear the chain (finding 12); a prune must be explained on disk before it deletes (finding C2). */
class AuditLogFailureTest {
    private fun dir(): File = Files.createTempDirectory("audit-fail").toFile()
    private fun archivesOf(d: File) = d.listFiles()!!.filter { it.name.matches(Regex("audit-\\d+\\.jsonl")) }.sortedBy { it.name }

    @Test fun aTornWriteIsCutBackAndTheNextEntryChainsFromTheLastGoodOne() {
        val d = dir()
        val f = File(d, "audit.jsonl")
        val log = AuditLog(f)
        log.append("one")
        log.append("two")
        val good = f.length()
        val lastGood = log.lastEntryHash
        var fail = true
        val real = log.appender
        log.appender = { file, bytes ->
            if (fail) {
                file.appendBytes(bytes.copyOf(bytes.size / 2)) // half a line reaches the disk, then the write fails
                throw IOException("No space left on device")
            }
            real(file, bytes)
        }
        val lost = log.append("lost")
        assertTrue(lost.failed)
        assertNull(lost.writtenHash)
        assertEquals(good, f.length())
        assertEquals(lastGood, log.lastEntryHash)
        assertEquals(2, log.count)
        assertEquals(1, log.failedAppends)

        fail = false
        val next = log.append("three")
        assertFalse(next.failed)
        assertEquals(lastGood, next.prevHash)
        val v = log.verify()
        assertTrue(v.reason, v.ok)
        assertEquals(3, v.entries)
        assertEquals(listOf("one", "two", "three"), log.entries().map { it.kind })
    }

    @Test fun anAppendThatCannotWriteDoesNotThrow() {
        val d = dir()
        val log = AuditLog(File(d, "audit.jsonl"))
        log.append("one")
        log.appender = { _, _ -> throw IOException("read-only file system") }
        repeat(3) { assertTrue(log.append("x$it").failed) }
        assertEquals(1, log.count)
        assertTrue(log.verify().ok)
    }

    @Test fun aWrittenEntryHasItsHashAndAFailedOneHasNone() {
        val log = AuditLog(File(dir(), "audit.jsonl"))
        val e = log.append("one")
        assertEquals(e.hash, e.writtenHash)
        assertTrue(e.hash.isNotEmpty())
    }

    // ---- C2: the prune is written down before anything is deleted -------------------------------------

    @Test fun theIntentIsOnDiskBeforeTheFirstArchiveIsDeleted() {
        val d = dir()
        val log = AuditLog(File(d, "audit.jsonl"), 600, 2)
        var checked = 0
        log.beforeDelete = { victim ->
            checked++
            val all = (archivesOf(d) + File(d, "audit.jsonl")).filter { it.exists() }.flatMap { it.readLines() }
            val intents = all.filter { it.contains("\"audit.pruned\"") }
            assertTrue("no audit.pruned line on disk before deleting ${victim.name}", intents.isNotEmpty())
        }
        repeat(30) { log.append("event.$it") }
        assertTrue(checked > 0)
        val v = log.verify()
        assertTrue(v.reason, v.ok)
    }

    @Test fun aKillBetweenTheIntentAndTheDeletionIsFinishedAtTheNextStart() {
        val d = dir()
        val log = AuditLog(File(d, "audit.jsonl"), 600, 2)
        var killed = false
        log.beforeDelete = { if (!killed) { killed = true; throw IllegalStateException("killed") } }
        try {
            repeat(30) { log.append("event.$it") }
        } catch (_: IllegalStateException) {
            // the process died here: the intent is written, no archive was deleted
        }
        assertTrue(killed)
        assertTrue("more archives than allowed: ${archivesOf(d).map { it.name }}", archivesOf(d).size > 2)
        assertTrue("the log verifies even before the prune is finished", AuditLog(File(d, "audit.jsonl"), 600, 10).verify().ok)

        val reopened = AuditLog(File(d, "audit.jsonl"), 600, 2)
        reopened.load()
        assertTrue("archives: ${archivesOf(d).map { it.name }}", archivesOf(d).size <= 2)
        val v = reopened.verify()
        assertTrue(v.reason, v.ok)
        assertEquals(reopened.count, v.entries)
        reopened.append("after")
        assertTrue(reopened.verify().ok)
        // one intent for the interrupted prune, not a second one written at the restart
        val anchors = reopened.entries().filter { it.kind == AuditLog.PRUNED }.map { (it.payload as JsonObject)["anchor"]!!.jsonPrimitive.content }
        assertEquals(anchors.size, anchors.distinct().size)
    }

    @Test fun aKillAfterSomeOfTheDeletionsIsFinishedToo() {
        val d = dir()
        val log = AuditLog(File(d, "audit.jsonl"), 600, 1)
        var n = 0
        log.beforeDelete = { if (++n == 2) throw IllegalStateException("killed") }
        try {
            repeat(40) { log.append("event.$it") }
        } catch (_: IllegalStateException) {
        }
        val reopened = AuditLog(File(d, "audit.jsonl"), 600, 1)
        reopened.load()
        assertTrue(archivesOf(d).size <= 1)
        val v = reopened.verify()
        assertTrue(v.reason, v.ok)
    }

    // ---- finding 2: an uncaught exception in a module scope is one audit entry ---------------------------

    @Test fun anUncaughtExceptionInAScopeWritesOneAuditEntryAndTheScopeLivesOn() = runBlocking {
        val log = AuditLog(File(dir(), "audit.jsonl"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + AuditScope.handler("test", { log }))
        scope.launch { throw IOException("boom") }.join()
        val ok = CompletableOk()
        scope.launch { ok.done = true }.join()
        assertTrue(ok.done)
        val e = log.entries().filter { it.kind == "scope.error" }
        assertEquals(1, e.size)
        assertEquals("test", (e[0].payload as JsonObject)["scope"]!!.jsonPrimitive.content)
        assertTrue((e[0].payload as JsonObject)["error"]!!.jsonPrimitive.content.contains("IOException: boom"))
    }

    private class CompletableOk { @Volatile var done = false }
}
