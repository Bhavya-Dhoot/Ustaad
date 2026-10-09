package com.swarmos.app.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.security.MessageDigest

@Serializable
data class AuditEntry(
    val ts: Long,
    val kind: String,
    val payload: JsonElement,
    val prevHash: String,
    val hash: String,
)

/** Result of [AuditLog.verify]. `badLine` is 1-based, null when the chain is intact. */
data class AuditVerify(val ok: Boolean, val entries: Int, val badLine: Int? = null, val reason: String? = null)

/**
 * Append-only, hash-chained JSONL log. Each line: {ts, kind, payload, prevHash, hash} with
 * hash = SHA-256 hex of "$prevHash\n$ts\n$kind\n$payload" (payload as compact JSON text).
 * The first entry chains from [GENESIS].
 */
class AuditLog(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    private var lastHash: String = GENESIS

    init {
        file.parentFile?.mkdirs()
        lastHash = readEntries().lastOrNull()?.hash ?: GENESIS
    }

    @Synchronized
    fun append(kind: String, payload: JsonElement = JsonObject(emptyMap())): AuditEntry {
        val ts = clock()
        val entry = AuditEntry(ts, kind, payload, lastHash, hashOf(lastHash, ts, kind, payload))
        file.appendText(SwarmJson.encodeToString(entry) + "\n")
        lastHash = entry.hash
        return entry
    }

    @Synchronized
    fun entries(): List<AuditEntry> = readEntries()

    @Synchronized
    fun verify(): AuditVerify {
        if (!file.exists()) return AuditVerify(true, 0)
        var prev = GENESIS
        var n = 0
        file.readLines().forEachIndexed { i, line ->
            if (line.isBlank()) return@forEachIndexed
            val e = runCatching { SwarmJson.decodeFromString<AuditEntry>(line) }.getOrNull()
                ?: return AuditVerify(false, n, i + 1, "unparseable line")
            if (e.prevHash != prev) return AuditVerify(false, n, i + 1, "prevHash does not match previous entry")
            if (e.hash != hashOf(e.prevHash, e.ts, e.kind, e.payload)) return AuditVerify(false, n, i + 1, "hash mismatch")
            prev = e.hash
            n++
        }
        return AuditVerify(true, n)
    }

    private fun readEntries(): List<AuditEntry> {
        if (!file.exists()) return emptyList()
        return file.readLines().filter { it.isNotBlank() }.mapNotNull {
            runCatching { SwarmJson.decodeFromString<AuditEntry>(it) }.getOrNull()
        }
    }

    companion object {
        val GENESIS = "0".repeat(64)

        fun hashOf(prevHash: String, ts: Long, kind: String, payload: JsonElement): String {
            val data = "$prevHash\n$ts\n$kind\n$payload"
            return MessageDigest.getInstance("SHA-256").digest(data.toByteArray(Charsets.UTF_8)).toHex()
        }
    }
}
