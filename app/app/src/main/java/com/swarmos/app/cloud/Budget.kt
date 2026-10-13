package com.swarmos.app.cloud

/**
 * A sliding-window counter per key (a source address, a member id) with a hard cap on the number of keys. Rules (docs 5.2, Codex
 * Session.kt:275): expired keys are removed before the table can be called full; a key that is not in the table when it is full is
 * refused without being inserted; a refusal never leaves a record behind; an inserted key always holds at least one time.
 */
internal class WindowBudget(private val limit: Int, private val windowMs: Long = 60_000L, private val maxKeys: Int = 4096) {
    init { require(limit >= 1 && maxKeys >= 1) { "budget" } }

    private class Rec { val times = ArrayDeque<Long>(); val costs = ArrayDeque<Int>(); var sum = 0L }

    private val recs = HashMap<String, Rec>()

    val size: Int
        @Synchronized get() = recs.size

    private fun trim(r: Rec, now: Long) {
        while (r.times.isNotEmpty() && now - r.times.first() >= windowMs) { r.times.removeFirst(); r.sum -= r.costs.removeFirst() }
    }

    /** True and counted ([cost] units of the limit), or false and nothing changed except that expired records may have been removed. */
    @Synchronized fun allow(key: String, now: Long, cost: Int = 1): Boolean {
        require(cost >= 1)
        if (cost > limit) return false
        if (recs.size >= maxKeys / 2) recs.entries.removeAll { trim(it.value, now); it.value.times.isEmpty() }
        val r = recs[key]
        if (r == null) {
            if (recs.size >= maxKeys) return false
            recs[key] = Rec().also { it.times.addLast(now); it.costs.addLast(cost); it.sum = cost.toLong() }
            return true
        }
        trim(r, now)
        if (r.sum + cost > limit) return false
        r.times.addLast(now); r.costs.addLast(cost); r.sum += cost
        return true
    }
}
