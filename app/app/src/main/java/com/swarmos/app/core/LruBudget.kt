package com.swarmos.app.core

import kotlinx.serialization.encodeToString
import java.io.File

/**
 * A cache budget keeper, least recently used first. It only decides: the owner of the cache deletes what it names. Pure, so every edge is a unit test.
 */
object LruBudget {
    /** One thing in a cache that is kept or evicted whole: a group of files, a copy, a report. [pinned]: in use now, never evicted. */
    data class Entry(val key: String, val bytes: Long, val lastUsedMs: Long, val pinned: Boolean = false)

    /**
     * What to evict so that the total of [entries] is at most [budgetBytes]: the least recently used unpinned entries first (equal times: by key, so
     * the answer does not depend on the order given), stopping at once when the total fits. Pinned entries count in the total and are never
     * evicted, so when only they are left the total may stay above the budget. A budget of zero (or less) evicts every unpinned entry that holds
     * anything (an empty one frees nothing and is left to its owner).
     */
    fun evict(entries: List<Entry>, budgetBytes: Long): List<Entry> {
        var total = entries.sumOf { it.bytes }
        val budget = budgetBytes.coerceAtLeast(0L)
        if (total <= budget) return emptyList()
        val out = ArrayList<Entry>()
        for (e in entries.filter { !it.pinned && it.bytes > 0 }.sortedWith(compareBy<Entry> { it.lastUsedMs }.thenBy { it.key })) {
            if (total <= budget) break
            out += e
            total -= e.bytes
        }
        return out
    }
}

/**
 * When each key of a cache was last used, in a small JSON file `name` under `dir` (so it survives a restart). An unreadable file is an empty record:
 * every key then reads as never used, which makes it the first to go. [lastUsed] is 0 for a key never touched.
 */
class UseLog(dir: File, name: String = "cache-use.json") {
    private val file = File(dir, name)
    private var map: MutableMap<String, Long>? = null

    @Synchronized private fun loaded(): MutableMap<String, Long> = map ?: (runCatching {
        if (file.isFile) SwarmJson.decodeFromString<Map<String, Long>>(file.readText()).toMutableMap() else null
    }.getOrNull() ?: mutableMapOf()).also { map = it }

    @Synchronized private fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(SwarmJson.encodeToString(loaded().toMap()))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        }
    }

    @Synchronized fun lastUsed(key: String): Long = loaded()[key] ?: 0L

    @Synchronized fun touch(key: String, nowMs: Long) { loaded()[key] = nowMs; save() }

    @Synchronized fun forget(keys: Collection<String>) { if (loaded().keys.removeAll(keys.toSet())) save() }

    @Synchronized fun clear() { loaded().clear(); save() }
}
