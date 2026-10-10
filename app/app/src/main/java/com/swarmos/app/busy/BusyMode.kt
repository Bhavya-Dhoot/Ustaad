package com.swarmos.app.busy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/**
 * The owner's "Keep busy" switch (off by default), the "Hammer" switch under it (off by default; it keeps the loop at full duty
 * on a warm phone), the state the loop reports and its counters. The Keep busy switch is stored through the function given to
 * [restore] (AppGraph.init); Hammer is not stored: it is off whenever Ustaad starts. The loop itself runs in the service
 * ([serve]) and stops with it.
 *
 * Audit entries: `busy.on` and `busy.off` when the switch changes, `busy.hammer_on` and `busy.hammer_off` when Hammer changes,
 * and one `busy.summary` every [SUMMARY_MS] while it is on. Never one entry per filler job.
 */
object BusyMode {
    private val _enabled = MutableStateFlow(false)
    private val _hammer = MutableStateFlow(false)
    private val _state = MutableStateFlow(BusyState.Off)

    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    /** Hammer: in memory only, on only while [enabled] is on. */
    val hammer: StateFlow<Boolean> = _hammer.asStateFlow()
    val state: StateFlow<BusyState> = _state.asStateFlow()

    internal val counters = BusyCounters()

    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveLock = Mutex()

    /** Audit entries wait here; one coroutine in [saveScope] writes them one after another, in the order they were queued. */
    private val auditQueue = Channel<() -> Unit>(Channel.UNLIMITED)
    private var store: (suspend (Boolean) -> Unit)? = null
    @Volatile private var audit: (String, JsonObject) -> Unit = { _, _ -> }
    private var onSince = 0L
    private val systemClockMs: () -> Long = { System.nanoTime() / 1_000_000L }

    init {
        saveScope.launch {
            for (write in auditQueue) {
                try {
                    write()
                } catch (_: Exception) {
                    // a failed audit write must not end the writer
                }
            }
        }
    }

    /** The millisecond clock the loop runs on (BusyApp gives it to the loop) and [hammerOffAtMs] is taken from. Tests replace it after [restore]. */
    @Volatile internal var clock: () -> Long = systemClockMs

    /** When Hammer was last switched off by its own button or the debug extra, on [clock]; null if it was not, or Hammer was switched on since. */
    @Volatile var hammerOffAtMs: Long? = null
        private set

    /** Loads the stored switch. Nothing is audited and the counters start at zero. Hammer starts off, with no cool-down running. */
    @Synchronized
    fun restore(on: Boolean, store: suspend (Boolean) -> Unit, audit: (String, JsonObject) -> Unit) {
        this.store = store
        this.audit = audit
        clock = systemClockMs
        counters.reset()
        onSince = System.nanoTime()
        _state.value = BusyState.Off
        _enabled.value = on
        _hammer.value = false
        hammerOffAtMs = null
    }

    /** Turns the mode on or off (Settings, or the debug intent extra `keepBusy`). On starts the counters again. */
    @Synchronized
    fun setEnabled(on: Boolean) {
        if (on == _enabled.value) return
        if (on) {
            counters.reset()
            onSince = System.nanoTime()
        }
        _enabled.value = on
        if (!on) _state.value = BusyState.Off
        save(on)
        record(if (on) "busy.on" else "busy.off", if (on) JsonObject(emptyMap()) else report().toAudit(secondsOn()))
        if (!on) switchHammer(false, coolDown = false)
    }

    /**
     * Turns Hammer on or off (Settings, or the debug intent extra `hammer`). On also turns Keep busy on and ends a cool-down;
     * off leaves Keep busy on and starts the cool-down ([BusyPolicy.HAMMER_COOLDOWN_MS], no filler). Keep busy going off turns
     * Hammer off without a cool-down.
     */
    @Synchronized
    fun setHammer(on: Boolean) {
        if (on) setEnabled(true)
        switchHammer(on, coolDown = true)
    }

    /** The service stopped ("Stop Ustaad"): Hammer is off when it starts again in this process, with no cool-down and no rest left over. */
    @Synchronized
    internal fun hammerOffForStop() {
        switchHammer(false, coolDown = false)
    }

    private fun switchHammer(on: Boolean, coolDown: Boolean) {
        if (on == _hammer.value) {
            if (!on && !coolDown) hammerOffAtMs = null   // Keep busy going off ends a rest that is still running, though Hammer was off already
            return
        }
        // the moment first: the loop reads the flag before it reads the moment, so it never sees "Hammer off" without it
        hammerOffAtMs = if (!on && coolDown) clock() else null
        _hammer.value = on
        record(if (on) "busy.hammer_on" else "busy.hammer_off", JsonObject(emptyMap()))
    }

    /** Queues an audit entry, so the caller (the UI thread, for the Settings buttons) never waits for the file. */
    private fun record(kind: String, payload: JsonObject) {
        val write = audit   // the function in force now: a restore() after this call must not redirect the entry
        auditQueue.trySend { write(kind, payload) }
    }

    /** The switches, the state and the counters: what `GET /v1/sched/busy` answers and Settings shows. */
    fun report(): BusyReport = counters.report(_enabled.value, _state.value, _hammer.value)

    internal fun publish(state: BusyState) {
        _state.value = state
    }

    private fun secondsOn(): Long = (System.nanoTime() - onSince) / 1_000_000_000L

    private fun save(on: Boolean) {
        val s = store ?: return
        saveScope.launch { saveLock.withLock { s(_enabled.value) } }
    }

    /**
     * Runs [loop] (normally `BusyApp.loop().run()`) while the switch is on and writes the 5-minute summaries; returns only when
     * cancelled (the service stops). Turning the switch off cancels the loop at once, which stops the filler job in flight and
     * gives the slot back.
     */
    suspend fun serve(summaryMs: Long = SUMMARY_MS, loop: suspend () -> Unit) {
        try {
            _enabled.collectLatest { on ->
                if (!on) {
                    _state.value = BusyState.Off
                    return@collectLatest
                }
                coroutineScope {
                    launch { loop() }
                    launch {
                        while (true) {
                            delay(summaryMs)
                            audit("busy.summary", report().toAudit(secondsOn()))
                        }
                    }
                }
            }
        } finally {
            _state.value = BusyState.Off
        }
    }

    const val SUMMARY_MS = 5 * 60 * 1000L
}
