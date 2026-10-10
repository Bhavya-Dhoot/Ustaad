package com.swarmos.app.busy

import com.swarmos.app.core.NodeHealth
import com.swarmos.app.llm.ChatChunk
import com.swarmos.app.llm.ChatRequest
import com.swarmos.app.llm.ChatStats
import com.swarmos.app.sched.JobExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

/**
 * The chat server as the loop sees it. [instance] changes at every start or restart of the server; [readyForMs] counts from
 * the moment that instance became READY.
 */
data class ChatServer(val instance: Long, val readyForMs: Long)

/**
 * The "Keep busy" loop of one phone. When the switch is on, a real chat model has been READY for a while, Ustaad is in front
 * (or the phone charges with battery optimisations ignored) and the phone has had no real model work for
 * [BusyPolicy.IDLE_BEFORE_MS], it runs short filler jobs one after another through [stream]: it borrows the slot from [exec]
 * (so real work waits for at most the time the filler needs to stop), re-reads heat and battery before every job
 * ([BusyPolicy.stateFor]) and stops the job in flight the moment real work asks for the slot. Filler goes only through this
 * phone's model, and its replies are dropped: nothing here calls `noteStats`, the benchmark, Choir or the batch runner.
 *
 * [step] is one pass of the loop and returns how long to wait before the next one, so tests can drive it without time.
 */
class BusyLoop(
    private val exec: JobExecutor,
    /** The filler request to the phone's own chat model (raw: the slot is held through [exec], not through the gate). */
    private val stream: (ChatRequest) -> Flow<ChatChunk>,
    private val prompts: () -> List<ChatRequest>,
    private val health: () -> NodeHealth?,
    /** The chat server, or null while no real chat model is READY. */
    private val chat: () -> ChatServer?,
    private val enabled: () -> Boolean,
    private val counters: BusyCounters,
    private val publish: (BusyState) -> Unit,
    private val appInFront: () -> Boolean = { true },
    private val batteryOptimizationIgnored: () -> Boolean = { false },
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    /**
     * True while other work that holds the model or the phone's memory runs without going through [exec]: a benchmark, the
     * self-test, a MemoryCloud split or loan, a Choir run, an index run, a model download. It counts as real work.
     */
    private val externalBusy: () -> Boolean = { false },
    /** Called before a job; the function it returns is called after the job, before the slot is given back. Undoes shared numbers a stream writes. */
    private val protect: () -> () -> Unit = { {} },
) {
    private var next = 0
    private var lastExternalMs = -1L
    private var strictSettle = false   // the chat server died under a filler job: wait longer before the next one

    /** Runs until cancelled. */
    suspend fun run() {
        while (true) delay(step())
    }

    /** One pass: decide the state, run one filler job when allowed. Returns the wait before the next pass, in milliseconds. */
    suspend fun step(): Long {
        val now = clock()
        val h = health()
        if (externalBusy()) lastExternalMs = now
        val externalIdle = if (lastExternalMs < 0) Long.MAX_VALUE else now - lastExternalMs
        val idle = minOf(exec.realIdleMs(), externalIdle)
        val server = chat()
        if (strictSettle && server != null && server.readyForMs >= BusyPolicy.SETTLE_AFTER_DEATH_MS) strictSettle = false
        val state = BusyPolicy.stateFor(
            BusyConditions(
                switchOn = enabled(),
                modelReady = server != null && exec.capacity > 0,
                modelReadyForMs = server?.readyForMs ?: 0L,
                modelSettleMs = if (strictSettle) BusyPolicy.SETTLE_AFTER_DEATH_MS else BusyPolicy.SETTLE_MS,
                appInFront = appInFront(),
                batteryOptimizationIgnored = batteryOptimizationIgnored(),
                idleMs = idle,
                thermalStatus = h?.thermalStatus ?: 0,
                batteryPct = h?.batteryPct ?: -1,
                charging = h?.charging ?: true,
            ),
        )
        publish(state)
        return when (state) {
            BusyState.Off -> OFF_POLL_MS
            BusyState.NoModel -> NO_MODEL_POLL_MS
            BusyState.PausedModelRestarted, BusyState.PausedBackground -> SETTLE_POLL_MS
            BusyState.WaitingForIdle -> (BusyPolicy.IDLE_BEFORE_MS - idle).coerceIn(MIN_POLL_MS, MAX_POLL_MS)
            BusyState.PausedHot, BusyState.PausedBattery -> BusyPolicy.PAUSE_RECHECK_MS
            BusyState.Running, BusyState.HalfDuty -> {
                val ran = runOne(server ?: return RETRY_MS) ?: return RETRY_MS
                when (ran.outcome) {
                    BusyOutcome.Done -> BusyPolicy.restAfterMs(state, ran.ms).coerceAtLeast(MIN_GAP_MS)
                    BusyOutcome.Failed -> FAILURE_BACKOFF_MS
                    BusyOutcome.Cancelled, BusyOutcome.Stopped -> 0L
                }
            }
        }
    }

    private class Ran(val outcome: BusyOutcome, val ms: Long)

    /** Null when no slot could be borrowed (real work got there first, or there is nothing to ask). */
    private suspend fun runOne(server: ChatServer): Ran? {
        val list = prompts()
        if (list.isEmpty()) return null
        val req = list[next++ % list.size].let { it.copy(maxTokens = minOf(it.maxTokens, BusyPolicy.MAX_TOKENS)) }
        return supervisorScope {
            var chunks = 0L
            var stats: ChatStats? = null
            // lazy, so the hook given to the executor can cancel it even before it has started
            val worker = async(start = CoroutineStart.LAZY) {
                stream(req).collect { c ->
                    if (c.delta.isNotEmpty()) chunks++
                    if (c.done) stats = c.stats
                }
            }
            val slot = exec.tryAcquireFiller { worker.cancel(CancellationException(PREEMPTED)) }
            if (slot == null) {
                worker.cancel()
                return@supervisorScope null
            }
            val restore = protect()
            val t0 = clock()
            counters.jobStarted()
            val watcher = launch {
                while (true) {
                    delay(WATCH_MS)
                    if (externalBusy() || serverChanged(server)) {
                        worker.cancel(CancellationException(PREEMPTED))
                        break
                    }
                }
            }
            var outcome = BusyOutcome.Stopped
            try {
                worker.start()
                outcome = try {
                    worker.await()
                    BusyOutcome.Done
                } catch (e: CancellationException) {
                    if (!currentCoroutineContext().isActive) throw e   // the loop itself is stopping
                    BusyOutcome.Cancelled                               // real work asked for the slot
                } catch (e: Exception) {
                    BusyOutcome.Failed
                }
            } finally {
                withContext(NonCancellable) {
                    watcher.cancel()
                    worker.cancelAndJoin()      // the slot goes back only when the model call has really ended
                    restore()
                    counters.jobEnded(outcome, stats?.completionTokens?.toLong() ?: chunks)
                    // a server that died or restarted under the job, whatever stopped the job: wait longer before the next one
                    if (outcome != BusyOutcome.Stopped && serverChanged(server)) strictSettle = true
                    slot.release()
                }
            }
            Ran(outcome, clock() - t0)
        }
    }

    private fun serverChanged(started: ChatServer): Boolean = chat()?.instance != started.instance

    companion object {
        private const val PREEMPTED = "real work arrived"
        const val OFF_POLL_MS = 1_000L
        const val NO_MODEL_POLL_MS = 2_000L
        const val SETTLE_POLL_MS = 2_000L
        const val MIN_POLL_MS = 50L
        const val MAX_POLL_MS = 500L
        const val RETRY_MS = 250L
        const val MIN_GAP_MS = 10L   // keeps a model that answers instantly from turning the loop into a spin
        const val FAILURE_BACKOFF_MS = 10_000L
        private const val WATCH_MS = 250L
    }
}
