package com.swarmos.app.busy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 * The owner's "Keep busy" switch (off by default), the state the loop reports and its counters. The switch is stored through
 * the function given to [restore] (AppGraph.init); the loop itself runs in the service ([serve]) and stops with it.
 *
 * Audit entries: `busy.on` and `busy.off` when the switch changes, and one `busy.summary` every [SUMMARY_MS] while it is on.
 * Never one entry per filler job.
 */
object BusyMode {
    private val _enabled = MutableStateFlow(false)
    private val _state = MutableStateFlow(BusyState.Off)

    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    val state: StateFlow<BusyState> = _state.asStateFlow()

    internal val counters = BusyCounters()

    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveLock = Mutex()
    private var store: (suspend (Boolean) -> Unit)? = null
    @Volatile private var audit: (String, JsonObject) -> Unit = { _, _ -> }
    private var onSince = 0L

    /** Loads the stored switch. Nothing is audited and the counters start at zero. */
    @Synchronized
    fun restore(on: Boolean, store: suspend (Boolean) -> Unit, audit: (String, JsonObject) -> Unit) {
        this.store = store
        this.audit = audit
        counters.reset()
        onSince = System.nanoTime()
        _state.value = BusyState.Off
        _enabled.value = on
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
        audit(if (on) "busy.on" else "busy.off", if (on) JsonObject(emptyMap()) else report().toAudit(secondsOn()))
    }

    /** The switch, the state and the counters: what `GET /v1/sched/busy` answers and Settings shows. */
    fun report(): BusyReport = counters.report(_enabled.value, _state.value)

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
