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
 * The calls this app has in flight against its llama-servers (chat streams and embedding requests), each with the moment its
 * own timeout runs out. [LlamaServerClient] registers every call. The hang watch uses it to tell "idle or past its timeout"
 * from "busy"; [LlamaRuntime.working] (the wake lock) and the memory-pressure handler use it to tell idle from busy.
 */
internal object InFlight {
    private class Call(val port: Int, val deadlineNs: Long)

    private val calls = ConcurrentHashMap<Call, Boolean>()
    private val lastEndNs = ConcurrentHashMap<Int, Long>()
    private val _count = MutableStateFlow(0)

    /** Calls in flight now, over all ports. */
    val count: StateFlow<Int> = _count.asStateFlow()

    /** Registers a call on [port] whose own timeout is [timeoutMs]; hand the token to [end] when the call is over, however it ends. */
    fun begin(port: Int, timeoutMs: Long, nowNs: Long = System.nanoTime()): Any {
        val c = Call(port, nowNs + timeoutMs * 1_000_000)
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

    /** True when a call on [port] is in flight and has not run past its own timeout. */
    fun busyWithinTimeout(port: Int, nowNs: Long = System.nanoTime()): Boolean = calls.keys.any { it.port == port && it.deadlineNs > nowNs }

    /** True when any call on [port] is in flight, past its timeout or not. */
    fun active(port: Int): Boolean = calls.keys.any { it.port == port }

    /** Milliseconds since the last call on [port] ended; [Long.MAX_VALUE] when none has run. Meaningless while [active]. */
    fun idleForMs(port: Int, nowNs: Long = System.nanoTime()): Long = lastEndNs[port]?.let { (nowNs - it) / 1_000_000 } ?: Long.MAX_VALUE
}
