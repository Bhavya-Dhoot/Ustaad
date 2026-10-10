import os
base = r"H:\UStaaf\.claude\worktrees\agent-ac34ad0bc1b4ddd46\app\app\src"
main = os.path.join(base, "main", "java", "com", "swarmos", "app", "memcloud")
test = os.path.join(base, "test", "java", "com", "swarmos", "app", "memcloud")

def edit(d, name, pairs):
    p = os.path.join(d, name)
    s = open(p, encoding="utf-8").read()
    for old, new in pairs:
        if old not in s:
            raise SystemExit("missing in %s: %s" % (name, old[:70]))
        s = s.replace(old, new, 1)
    open(p, "w", encoding="utf-8").write(s)

open(os.path.join(main, "CpuSet.kt"), "w", encoding="utf-8").write('''package com.swarmos.app.memcloud

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
''')

edit(main, "MemCloudState.kt", [(
"""    val threads: Int = 8,""", """    /** Compute threads; null = one per core the app may use right now (see [CpuSet]). */
    val threads: Int? = null,""")])
edit(main, "RpcWorker.kt", [(
"""            add("-t"); add(req.threads.toString())""", """            add("-t"); add((req.threads ?: CpuSet.threads()).toString())""")])
edit(main, "SplitPlanner.kt", [
("""    fun serverArgs(plan: SplitPlan, rpcPort: Int, ctx: Int = 4096): List<String> {""", """    fun serverArgs(plan: SplitPlan, rpcPort: Int, ctx: Int = 4096, threads: Int = 8): List<String> {"""),
("""        args += listOf("-t", "8", "-c", ctx.toString(), "-np", "1", "-fit", "off")""", """        args += listOf("-t", threads.toString(), "-c", ctx.toString(), "-np", "1", "-fit", "off")"""),
])
edit(main, "MemCloud.kt", [(
"""            val args = SplitPlanner.serverArgs(sp, Ports.RPC, SPLIT_CTX)""", """            val args = SplitPlanner.serverArgs(sp, Ports.RPC, SPLIT_CTX, CpuSet.threads())""")])

edit(test, "SplitAndLogTest.kt", [(
"""class SplitPlannerTest {""", """class CpuSetTest {
    @Test fun countsCoresInACpuList() {
        assertEquals(6, CpuSet.count("0-5"))
        assertEquals(8, CpuSet.count("0-7"))
        assertEquals(6, CpuSet.count("0-3,6-7"))
        assertEquals(1, CpuSet.count("4"))
        assertNull(CpuSet.count(""))
        assertNull(CpuSet.count("a-b"))
        assertNull(CpuSet.count("5-2"))
    }

    @Test fun threadsStayBetweenTwoAndEight() {
        val t = CpuSet.threads()
        assertTrue(t in 2..8)
    }
}

class SplitPlannerTest {"""),
("""    @Test fun twoPhoneSplitUsesNoTensorSplitFlag() {""", """    @Test fun theThreadCountIsAParameter() {
        val p = SplitPlanner.plan(info(layers = 36), devs(9000, 9000))
        val args = SplitPlanner.serverArgs(p, 50052, threads = 6)
        assertEquals("6", args[args.indexOf("-t") + 1])
    }

    @Test fun twoPhoneSplitUsesNoTensorSplitFlag() {"""),
])
print("ok")
