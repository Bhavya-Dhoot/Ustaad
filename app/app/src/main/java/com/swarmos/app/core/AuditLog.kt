package com.swarmos.app.core

import kotlinx.serialization.Serializable
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
)

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
 */
class AuditLog(
    private val file: File,
    private val rotateBytes: Long = ROTATE_BYTES,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var loaded = false
    private var lastHash: String = GENESIS
    private var total = 0
    private var activeBytes = 0L

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

    /** Every entry of every file, oldest first. Parses the files; use [count] for the size. */
    @Synchronized
    fun entries(): List<AuditEntry> = files().flatMap { readEntries(it) }

    @Synchronized
    fun verify(): AuditVerify {
        var prev = GENESIS
        var n = 0
        for (f in files()) {
            for (line in f.readLines()) {
                if (line.isBlank()) continue
                val e = parse(line) ?: return AuditVerify(false, n, n + 1, "unparseable line in ${f.name}")
                if (e.prevHash != prev) return AuditVerify(false, n, n + 1, "prevHash does not match previous entry in ${f.name}")
                if (e.hash != hashOf(e.prevHash, e.ts, e.kind, e.payload)) return AuditVerify(false, n, n + 1, "hash mismatch in ${f.name}")
                prev = e.hash
                n++
            }
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
    }

    private fun write(kind: String, payload: JsonElement): AuditEntry {
        val ts = clock()
        val entry = AuditEntry(ts, kind, payload, lastHash, hashOf(lastHash, ts, kind, payload))
        val bytes = (SwarmJson.encodeToString(entry) + "\n").toByteArray(Charsets.UTF_8)
        java.io.FileOutputStream(file, true).use { it.write(bytes) } // one write per line
        activeBytes += bytes.size
        lastHash = entry.hash
        total++
        if (activeBytes >= rotateBytes) rotate()
        return entry
    }

    private fun rotate() {
        val next = (archives().maxOfOrNull { archiveNumber(it) } ?: 0) + 1
        if (file.renameTo(archiveFile(next))) activeBytes = 0
    }

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

    /** All files of the log in chain order: rotated files, then the active one. */
    private fun files(): List<File> = archives() + listOfNotNull(file.takeIf { it.exists() })

    private fun readEntries(f: File): List<AuditEntry> =
        f.readLines().filter { it.isNotBlank() }.mapNotNull { parse(it) }

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

        /** A torn tail bigger than this is not cut off at load; verify() reports it instead. */
        const val MAX_REPAIR_BYTES = 65_536

        fun hashOf(prevHash: String, ts: Long, kind: String, payload: JsonElement): String {
            val data = "$prevHash\n$ts\n$kind\n$payload"
            return MessageDigest.getInstance("SHA-256").digest(data.toByteArray(Charsets.UTF_8)).toHex()
        }
    }
}
