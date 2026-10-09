package com.swarmos.app.pdu

/** At most [max] events per [windowMs] for each key (here: each asking phone). */
class RateLimiter(private val max: Int, private val windowMs: Long, private val now: () -> Long = System::currentTimeMillis) {
    private val seen = HashMap<String, ArrayDeque<Long>>()

    @Synchronized
    fun tryAcquire(key: String): Boolean {
        val t = now()
        val q = seen.getOrPut(key) { ArrayDeque() }
        while (q.isNotEmpty() && t - q.first() >= windowMs) q.removeFirst()
        if (q.size >= max) return false
        q.addLast(t)
        return true
    }
}
