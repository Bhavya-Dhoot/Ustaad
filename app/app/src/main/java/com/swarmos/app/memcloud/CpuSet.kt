package com.swarmos.app.memcloud

import java.io.File

/**
 * How many cores this process may run on. A child process started with ProcessBuilder keeps the cpuset its parent had at
 * the moment it was forked: an app that is in the background on this phone has cores 0-5 only, and llama.cpp's thread
 * barriers spin, so 8 threads on 6 cores (measured: 0.3 tok/s against 3 tok/s) make every graph wait for a descheduled thread.
 * rpc-server and the split's llama-server therefore get as many threads as cores they are allowed to use.
 */
object CpuSet {
    /** "0-5" -> 6, "0-3,6-7" -> 6, "0-7" -> 8; null when [list] is not a cpu list. */
    fun count(list: String): Int? {
        var n = 0
        for (part in list.trim().split(',')) {
            val ends = part.trim().split('-')
            val a = ends.getOrNull(0)?.toIntOrNull() ?: return null
            val b = if (ends.size > 1) ends[1].toIntOrNull() ?: return null else a
            if (b < a) return null
            n += b - a + 1
        }
        return n.takeIf { it > 0 }
    }

    /** Cores the app process may use now (children forked from here get the same set), 8 when it cannot be read. */
    fun allowed(): Int = runCatching {
        File("/proc/self/status").readLines().firstOrNull { it.startsWith("Cpus_allowed_list:") }
            ?.substringAfter(':')?.let { count(it) }
    }.getOrNull() ?: 8

    /** Thread count for a llama.cpp process forked now: one per allowed core, between 2 and 8. */
    fun threads(): Int = allowed().coerceIn(2, 8)
}
