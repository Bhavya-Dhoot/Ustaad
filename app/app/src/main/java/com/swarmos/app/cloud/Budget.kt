package com.swarmos.app.cloud

/**
 * A sliding-window counter per key (a source address, a member id) with a hard cap on the number of keys. Rules (docs 5.2, Codex
 * Session.kt:275): expired keys are removed before the table can be called full; a key that is not in the table when it is full is
 * refused without being inserted; a refusal never leaves a record behind; an inserted key always holds at least one time.
 */
internal class WindowBudget(private val limit: Int, private val windowMs: Long = 60_000L, private val maxKeys: Int = 4096) {
    init { require(limit >= 1 && maxKeys >= 1) { "budget" } }

    private val times = HashMap<String, ArrayDeque<Long>>()

    val size: Int
        @Synchronized get() = times.size

    /** True and counted, or false and nothing changed except that expired records may have been removed. */
    @Synchronized fun allow(key: String, now: Long): Boolean {
        if (times.size >= maxKeys / 2) times.entries.removeAll { it.value.isEmpty() || now - it.value.last() >= windowMs }
        val q = times[key]
        if (q == null) {
            if (times.size >= maxKeys) return false
            times[key] = ArrayDeque<Long>().also { it.addLast(now) }
            return true
        }
        while (q.isNotEmpty() && now - q.first() >= windowMs) q.removeFirst()
        if (q.size >= limit) return false
        q.addLast(now)
        return true
    }
}
