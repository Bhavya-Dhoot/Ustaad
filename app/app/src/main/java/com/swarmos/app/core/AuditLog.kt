package com.swarmos.app.core

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

@Serializable
data class AuditEntry(
    val ts: Long,
    val kind: String,
    val payload: JsonElement,
    val prevHash: String,
    val hash: String,
    /**
     * Never stored or sent. True on the entry [AuditLog.append] returns when the line could not be written (disk full, file gone):
     * the file was cut back to its last good length, [hash] is empty, and nothing was added to the chain. Callers may ignore it.
     */
    @Transient val failed: Boolean = false,
) {
    /** [hash] of an entry that was written, null for one that was not; for callers that store the hash as a reference. */
    val writtenHash: String? get() = hash.takeIf { !failed && it.isNotEmpty() }
}

/** Result of [AuditLog.verify]. `badLine` is the 1-based number of the first bad entry (counted across all files), null when the chain is intact. */
data class AuditVerify(val ok: Boolean, val entries: Int, val badLine: Int? = null, val reason: String? = null)

/**
 * Append-only, hash-chained JSONL log. Each line: {ts, kind, payload, prevHash, hash} with
 * hash = SHA-256 hex of "$prevHash\n$ts\n$kind\n$payload" (payload as compact JSON text).
 * The first entry chains from [GENESIS].
 *
 * The file is read once ([load], called off the main thread at start, or on first use): after that the last
 * hash, the entry count and the file size live in memory and [append] only writes. When the active file
 * reaches [rotateBytes] it is renamed to `audit-1.jsonl`, `audit-2.jsonl`, ... (oldest first) and a new
 * active file starts whose first entry has the last hash of the old one as prevHash, so the chain goes on
 * across files; [verify] walks them all in that order. A torn last line (the process was killed in the
 * middle of a write) is cut off at load and an `audit.tail_repaired` entry records it.
 *
 * At most [maxArchives] rotated files are kept. Rotating past that deletes the oldest ones and appends an
 * `audit.pruned` entry {archives, entries, anchor}: `anchor` is the hash the oldest remaining entry chains from,
 * so the new start of the chain is explicit and [verify] accepts it only when such an entry exists.
 *
 * A prune writes its `audit.pruned` entry (the intent, with the new anchor) and syncs it to disk before it deletes any archive;
 * a prune that was cut short is finished at load. A failed write is cut back to the last good length and reported on the returned
 * entry ([AuditEntry.failed]), never thrown, so a full disk cannot tear the chain or abort the ask or run that logged.
 *
 * [verify] and [entries] work on a snapshot (the list of files and the length of the active one, taken under
 * the lock in microseconds) and read outside it, so [count] and [append] never wait for them.
 */
class AuditLog(
    private val file: File,
    private val rotateBytes: Long = ROTATE_BYTES,
    private val maxArchives: Int = MAX_ARCHIVES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var loaded = false
    private var lastHash: String = GENESIS
    private var total = 0
    private var activeBytes = 0L
    private var generation = 0 // bumped by every rotation; a read that straddles one is repeated

    /** Test hook: runs after a snapshot is taken and before it is read, outside the lock. */
    internal var afterSnapshot: (() -> Unit)? = null

    /** Test hooks: how a line reaches the file, and a step run just before an archive is deleted by a prune. */
    internal var appender: (File, ByteArray) -> Unit = { f, b -> java.io.FileOutputStream(f, true).use { it.write(b) } }
    internal var beforeDelete: ((File) -> Unit)? = null
    private var failedWrites = 0

    /** Lines that could not be written since this log was opened. */
    val failedAppends: Int @Synchronized get() = failedWrites

    /** Entries in all files. Read from memory after the first load. */
    val count: Int @Synchronized get() = ensureLoaded().let { total }

    /** Hash of the newest entry, or [GENESIS] when the log is empty. */
    val lastEntryHash: String @Synchronized get() = ensureLoaded().let { lastHash }

    /** Reads the files once. Call it off the main thread; it is a no-op when already loaded. */
    @Synchronized
    fun load() {
        ensureLoaded()
    }

    @Synchronized
    fun append(kind: String, payload: JsonElement = JsonObject(emptyMap())): AuditEntry {
        ensureLoaded()
        return write(kind, payload)
    }

    /** Every entry of every file, oldest first. Parses the files outside the lock; use [count] for the size. */
    fun entries(): List<AuditEntry> =
        readSnapshot(emptyList()) { lines -> lines.mapNotNull { parse(it) }.toList() }

    /** Walks all files, oldest first. Does not hold the lock while it reads. */
    fun verify(): AuditVerify =
        readSnapshot(AuditVerify(false, 0, 1, "the log files kept changing while they were read")) { verifyLines(it) }

    private class Snapshot(val archives: List<File>, val activeLength: Long, val generation: Int)

    @Synchronized
    private fun snapshot(): Snapshot {
        ensureLoaded()
        return Snapshot(archives(), if (file.exists()) activeBytes else 0L, generation)
    }

    @Synchronized
    private fun currentGeneration() = generation

    /**
     * Runs [block] over the lines of the snapshot (rotated files, then the first [Snapshot.activeLength] bytes of the
     * active one). A rotation or a pruned file during the read makes it start again, a few times at most.
     */
    private fun <T> readSnapshot(fallback: T, block: (Sequence<String>) -> T): T {
        repeat(4) {
            val snap = snapshot()
            afterSnapshot?.invoke()
            try {
                val result = block(linesOf(snap))
                if (currentGeneration() == snap.generation) return result
            } catch (_: java.io.IOException) {
                // a rotated file was pruned between the snapshot and the read: take a new snapshot
            }
        }
        return fallback
    }

    private fun linesOf(s: Snapshot): Sequence<String> = sequence {
        for (f in s.archives) f.bufferedReader().use { r -> for (line in r.lineSequence()) yield(line) }
        for (line in String(readPrefix(file, s.activeLength), Charsets.UTF_8).lineSequence()) yield(line)
    }

    private fun readPrefix(f: File, n: Long): ByteArray {
        if (n <= 0 || !f.exists()) return ByteArray(0)
        RandomAccessFile(f, "r").use { raf ->
            val b = ByteArray(minOf(n, raf.length()).toInt())
            raf.readFully(b)
            return b
        }
    }

    private fun verifyLines(lines: Sequence<String>): AuditVerify {
        var prev: String? = null
        var start: String? = null
        var n = 0
        val anchors = HashSet<String>()
        for (line in lines) {
            if (line.isBlank()) continue
            val e = parse(line) ?: return AuditVerify(false, n, n + 1, "unparseable line")
            if (prev == null) start = e.prevHash
            else if (e.prevHash != prev) return AuditVerify(false, n, n + 1, "prevHash does not match previous entry")
            if (e.hash != hashOf(e.prevHash, e.ts, e.kind, e.payload)) return AuditVerify(false, n, n + 1, "hash mismatch")
            if (e.kind == PRUNED) ((e.payload as? JsonObject)?.get("anchor") as? JsonPrimitive)?.content?.let { anchors += it }
            prev = e.hash
            n++
        }
        // the chain starts at GENESIS, or at the anchor of an audit.pruned entry that explains why older files are gone
        if (start != null && start != GENESIS && start !in anchors) {
            return AuditVerify(false, 0, 1, "the start of the chain was removed without an audit.pruned entry")
        }
        return AuditVerify(true, n)
    }

    private fun ensureLoaded() {
        if (loaded) return
        file.parentFile?.mkdirs()
        var dropped = 0
        var bytes = 0L
        var last: String? = null
        var count = 0
        for (f in archives()) {
            val (n, h) = scan(f)
            count += n
            if (h != null) last = h
        }
        if (file.exists()) {
            val tail = repairTail(file)
            dropped = tail.droppedBytes
            bytes = file.length()
            count += tail.entries
            if (tail.lastHash != null) last = tail.lastHash
        }
        lastHash = last ?: GENESIS
        total = count
        activeBytes = bytes
        loaded = true
        if (dropped > 0) write("audit.tail_repaired", JsonObject(mapOf("droppedBytes" to JsonPrimitive(dropped))))
        finishInterruptedPrune()
    }

    /** More archives than allowed means a prune wrote its intent and was killed before it deleted: do the deleting now. */
    private fun finishInterruptedPrune() {
        val all = archives()
        val excess = all.size - maxArchives.coerceAtLeast(1)
        if (excess > 0) prune(all.take(excess), oldestKept = all[excess], onlyIfNoIntentYet = true)
    }

    private fun write(kind: String, payload: JsonElement): AuditEntry {
        val ts = clock()
        val entry = AuditEntry(ts, kind, payload, lastHash, hashOf(lastHash, ts, kind, payload))
        val bytes = (SwarmJson.encodeToString(entry) + "\n").toByteArray(Charsets.UTF_8)
        try {
            appender(file, bytes) // one write per line
        } catch (e: Exception) {
            // a torn line would glue onto the next one and break the chain: cut the file back to the last good length
            runCatching {
                if (file.exists()) java.io.RandomAccessFile(file, "rw").use { if (it.length() > activeBytes) it.setLength(activeBytes) }
            }
            failedWrites++
            return entry.copy(hash = "", failed = true)
        }
        activeBytes += bytes.size
        lastHash = entry.hash
        total++
        if (activeBytes >= rotateBytes) rotate()
        return entry
    }

    private fun rotate() {
        val next = (archives().maxOfOrNull { archiveNumber(it) } ?: 0) + 1
        if (!file.renameTo(archiveFile(next))) return
        activeBytes = 0
        generation++
        val all = archives()
        val excess = all.size - maxArchives.coerceAtLeast(1)
        if (excess > 0) prune(all.take(excess), oldestKept = all[excess])
    }

    /**
     * Records the pruning, then deletes [drop] (the oldest files). The `audit.pruned` entry, with the hash the oldest kept entry
     * chains from, is written and synced first: a kill after it leaves a chain that still verifies (the files are still there)
     * and [finishInterruptedPrune] deletes them at the next load; a kill before it leaves everything as it was.
     */
    private fun prune(drop: List<File>, oldestKept: File, onlyIfNoIntentYet: Boolean = false) {
        val anchor = firstPrevHash(oldestKept) ?: GENESIS
        if (!(onlyIfNoIntentYet && intentWritten(anchor))) {
            var entries = 0
            for (f in drop) entries += scan(f).first
            val intent = write(
                PRUNED,
                JsonObject(mapOf("archives" to JsonPrimitive(drop.size), "entries" to JsonPrimitive(entries), "anchor" to JsonPrimitive(anchor))),
            )
            if (intent.failed) return // nothing was promised, so nothing is deleted; the next rotation or load tries again
            runCatching { java.io.FileOutputStream(file, true).use { it.fd.sync() } }
        }
        // a rotation caused by the intent line may already have pruned some of these files
        for (f in drop) {
            if (!f.exists()) continue
            val n = scan(f).first
            beforeDelete?.invoke(f)
            if (f.delete()) total -= n
        }
    }

    private fun intentWritten(anchor: String): Boolean {
        val files = archives() + listOfNotNull(file.takeIf { it.exists() })
        return files.any { f ->
            f.useLines { lines ->
                lines.any { line ->
                    line.contains(PRUNED) && parse(line)?.let { e ->
                        e.kind == PRUNED && ((e.payload as? JsonObject)?.get("anchor") as? JsonPrimitive)?.content == anchor
                    } == true
                }
            }
        }
    }

    private fun firstPrevHash(f: File): String? =
        f.useLines { lines -> lines.firstOrNull { it.isNotBlank() }?.let { parse(it)?.prevHash } }

    private fun base() = file.nameWithoutExtension
    private fun ext() = file.extension.let { if (it.isEmpty()) "" else ".$it" }
    private fun archiveFile(n: Int) = File(file.parentFile, "${base()}-$n${ext()}")
    private fun archiveNumber(f: File): Int = f.name.removePrefix("${base()}-").removeSuffix(ext()).toIntOrNull() ?: 0

    /** Rotated files, oldest first. */
    private fun archives(): List<File> {
        val pattern = Regex("""${Regex.escape(base())}-(\d+)${Regex.escape(ext())}""")
        return (file.parentFile?.listFiles() ?: emptyArray())
            .filter { pattern.matches(it.name) }
            .sortedBy { archiveNumber(it) }
    }

    /** Entry count and hash of the last line of a rotated file; its lines are not parsed beyond the last one. */
    private fun scan(f: File): Pair<Int, String?> {
        var n = 0
        var lastLine: String? = null
        f.forEachLine { if (it.isNotBlank()) { n++; lastLine = it } }
        return n to lastLine?.let { parse(it)?.hash }
    }

    private class Tail(val entries: Int, val lastHash: String?, val droppedBytes: Int)

    /**
     * Reads the active file once and cuts off a torn tail: whatever follows the last complete, self-consistent
     * entry (a half-written line, or zero bytes left by a crash), up to [MAX_REPAIR_BYTES]. Damage in the middle
     * of the file, or a bigger tail, is left alone so that [verify] reports it. Makes sure the file ends with a newline.
     */
    private fun repairTail(f: File): Tail {
        val bytes = f.readBytes()
        val nl = 10.toByte() // '\n'
        var lastGood: String? = null
        var keep = 0            // offset just after the last good line
        var lines = 0           // non-blank lines up to and including the last good one
        var seen = 0
        var garbageAfter = false
        var pos = 0
        while (pos < bytes.size) {
            var end = pos
            while (end < bytes.size && bytes[end] != nl) end++
            val next = if (end < bytes.size) end + 1 else end
            val line = String(bytes, pos, end - pos, Charsets.UTF_8)
            if (line.isNotBlank()) {
                seen++
                val e = parse(line)
                if (e != null && e.hash == hashOf(e.prevHash, e.ts, e.kind, e.payload)) {
                    lastGood = e.hash
                    keep = next
                    lines = seen
                    garbageAfter = false
                } else {
                    garbageAfter = true
                }
            }
            pos = next
        }
        val tail = bytes.size - keep
        val dropped = if (garbageAfter && tail <= MAX_REPAIR_BYTES) tail else 0
        RandomAccessFile(f, "rw").use { raf ->
            if (dropped > 0) raf.setLength(keep.toLong())
            val len = raf.length()
            if (len > 0) {
                raf.seek(len - 1)
                if (raf.read() != nl.toInt()) { raf.seek(len); raf.write(nl.toInt()) }
            }
        }
        return Tail(lines, lastGood, dropped)
    }

    private fun parse(line: String): AuditEntry? = runCatching { SwarmJson.decodeFromString<AuditEntry>(line) }.getOrNull()

    companion object {
        val GENESIS = "0".repeat(64)

        /** The active file is rotated when it reaches about 1 MB. */
        const val ROTATE_BYTES = 1_000_000L

        /** At most this many rotated files are kept; older ones are deleted and an [PRUNED] entry says so. */
        const val MAX_ARCHIVES = 5

        const val PRUNED = "audit.pruned"

        /** A torn tail bigger than this is not cut off at load; verify() reports it instead. */
        const val MAX_REPAIR_BYTES = 65_536

        fun hashOf(prevHash: String, ts: Long, kind: String, payload: JsonElement): String {
            val data = "$prevHash\n$ts\n$kind\n$payload"
            return MessageDigest.getInstance("SHA-256").digest(data.toByteArray(Charsets.UTF_8)).toHex()
        }
    }
}

/**
 * A handler for a module's long-lived coroutine scope: an exception that nobody caught writes one `scope.error` entry and the
 * process lives on, instead of the default behaviour (the thread's uncaught-exception handler, which ends the app).
 */
object AuditScope {
    fun handler(scope: String, log: () -> AuditLog): CoroutineExceptionHandler = CoroutineExceptionHandler { _, t ->
        runCatching {
            log().append(
                "scope.error",
                JsonObject(mapOf("scope" to JsonPrimitive(scope), "error" to JsonPrimitive("${t.javaClass.simpleName}: ${t.message}".take(200)))),
            )
        }
    }
}
