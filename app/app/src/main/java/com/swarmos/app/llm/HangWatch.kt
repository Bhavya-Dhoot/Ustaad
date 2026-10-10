package com.swarmos.app.llm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * Decides when a READY llama-server that is still alive as a process has stopped answering, from one `/health` probe at a time
 * (every [LlamaRuntime]'s ping interval). Pure, for unit tests.
 *
 * What `/health` proves (llama.cpp third_party tools/server, `get_health` in server-context.cpp): it answers 200 `{"status":"ok"}`
 * straight from the HTTP thread pool without touching the inference loop (the handler even declares `bool ctx_server; // do NOT
 * delete this line` so nobody adds a use), and the pool has `max(np + 4, cores - 1)` fixed threads plus dynamic ones. A server
 * that is merely busy (a long prefill, a generation, queued requests) therefore still answers at once, so a miss is never
 * "busy". It does NOT prove the decode loop is alive: a wedged GPU kernel leaves `/health` at 200. `/slots` goes through the
 * task queue and would, but it waits for the batch in progress (a CPU prefill batch can take tens of seconds), so it is not used.
 * What a miss does prove: the HTTP layer is dead or frozen (process stopped, listener closed, deadlock in the server threads).
 *
 * A miss counts only while no request is in flight within its own timeout ([InFlight.busyWithinTimeout]); [MAX_MISSES] misses in
 * a row mean "kill it, the restart path takes over".
 */
class HangWatch(private val maxMisses: Int = MAX_MISSES) {
    var misses = 0
        private set

    /** Feeds one probe; true when the server should be killed now. A probe made while a request is within its timeout neither counts nor resets. */
    fun record(answered: Boolean, busyWithinTimeout: Boolean): Boolean {
        if (answered) {
            misses = 0
            return false
        }
        if (busyWithinTimeout) return false
        misses++
        return misses >= maxMisses
    }

    companion object {
        const val MAX_MISSES = 3
    }
}

/**
 * The ping loop of one READY server: every [periodMs] it asks [endedBecause] (a reason when the server was stopped, replaced or died:
 * the loop ends; null while it is still the one to watch), runs the blocking [probe] (true = `/health` answered 200 within its own
 * connect and read timeouts) and feeds [HangWatch]; after the last allowed miss it calls [kill] once and ends. [busy] says whether a
 * call is in flight that excuses silence ([InFlight.busyWithinTimeout]). Every decision goes to [log], so one logcat line says why a
 * frozen server was or was not killed. Its own class so a stand-in server can drive it in a unit test with a short period.
 */
internal class HealthSupervisor(
    private val periodMs: Long,
    private val probe: () -> Boolean,
    private val busy: () -> Boolean,
    private val endedBecause: () -> String?,
    private val kill: () -> Unit,
    private val log: (String) -> Unit = {},
    private val watch: HangWatch = HangWatch(),
    /** Wall clock in ms that keeps counting while the phone sleeps (SystemClock.elapsedRealtime); the coroutine timers do not. */
    private val wallClockMs: () -> Long = System::currentTimeMillis,
) {
    suspend fun run() {
        log("health watch armed, ping every $periodMs ms")
        var last = wallClockMs()
        while (true) {
            kotlinx.coroutines.delay(periodMs)
            val now = wallClockMs()
            // delay() counts time the CPU is awake: when the phone slept or the vendor OS froze the app, a tick comes much later than its period
            if (now - last > periodMs * LATE_FACTOR) log("health tick came ${(now - last) / 1000} s after the last one (period ${periodMs / 1000} s): the phone slept or the app was frozen")
            last = now
            endedBecause()?.let { log("health watch ended: $it"); return }
            val answered = probe()
            val excused = busy()
            val dead = watch.record(answered, excused)
            if (!answered) log("health ping missed (${watch.misses} of ${HangWatch.MAX_MISSES} counted${if (excused) "; not counted now: a call is in flight within its time" else ""})")
            if (dead) {
                log("health watch: ${HangWatch.MAX_MISSES} misses in a row, killing the server")
                kill()
                return
            }
        }
    }

    private companion object {
        /** A tick later than this many periods is logged as late. */
        const val LATE_FACTOR = 2
    }
}

/**
 * The calls this app has in flight against its llama-servers (chat streams and embedding requests), each with the moment its
 * own timeout runs out. [LlamaServerClient] registers every call. The hang watch uses it to tell "idle or past its timeout"
 * from "busy"; [LlamaRuntime.working] (the wake lock) and the memory-pressure handler use it to tell idle from busy.
 */
internal object InFlight {
    /**
     * How long a call excuses a silent `/health`, at most, whatever its own timeout is (a stream's is 10 minutes). A server that is merely
     * busy still answers `/health` in milliseconds (see [HangWatch]), so silence for three pings is a frozen server even under a call;
     * without this cap a frozen server with one call in flight stayed READY until that call's 10 minute read timeout.
     */
    const val MAX_EXCUSE_MS = 60_000L

    private class Call(val port: Int, val startNs: Long, val deadlineNs: Long)

    private val calls = ConcurrentHashMap<Call, Boolean>()
    private val lastEndNs = ConcurrentHashMap<Int, Long>()
    private val _count = MutableStateFlow(0)

    /** Calls in flight now, over all ports. */
    val count: StateFlow<Int> = _count.asStateFlow()

    /** Registers a call on [port] whose own timeout is [timeoutMs]; hand the token to [end] when the call is over, however it ends. */
    fun begin(port: Int, timeoutMs: Long, nowNs: Long = System.nanoTime()): Any {
        val c = Call(port, nowNs, nowNs + timeoutMs * 1_000_000)
        calls[c] = true
        _count.update { it + 1 }
        return c
    }

    fun end(token: Any, nowNs: Long = System.nanoTime()) {
        val c = token as? Call ?: return
        if (calls.remove(c) != null) {
            lastEndNs[c.port] = nowNs
            _count.update { it - 1 }
        }
    }

    /** True when a call on [port] is in flight, has not run past its own timeout and has not run for [MAX_EXCUSE_MS] yet. */
    fun busyWithinTimeout(port: Int, nowNs: Long = System.nanoTime()): Boolean =
        calls.keys.any { it.port == port && it.deadlineNs > nowNs && nowNs - it.startNs < MAX_EXCUSE_MS * 1_000_000 }

    /** True when any call on [port] is in flight, past its timeout or not. */
    fun active(port: Int): Boolean = calls.keys.any { it.port == port }

    /** Milliseconds since the last call on [port] ended; [Long.MAX_VALUE] when none has run. Meaningless while [active]. */
    fun idleForMs(port: Int, nowNs: Long = System.nanoTime()): Long = lastEndNs[port]?.let { (nowNs - it) / 1_000_000 } ?: Long.MAX_VALUE
}
