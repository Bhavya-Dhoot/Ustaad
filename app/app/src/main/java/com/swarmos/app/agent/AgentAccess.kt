package com.swarmos.app.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The owner's choice to let other apps on this phone use Ustaad's model: the switch, the key, and what Settings shows
 * about it. Off by default. Turning it on makes a new key, turning it off forgets the key, so a key never outlives the
 * switch. The listener ([AgentServer]) follows [enabled] and asks for [key] on every request, so a new key works at once.
 *
 * The state lives in memory and is written to [com.swarmos.app.core.SwarmPrefs] through the store given to [restore]
 * (AppGraph.init). Each write saves the state as it is when the write runs, so quick changes cannot save an old one last.
 */
object AgentAccess {
    private val _enabled = MutableStateFlow(false)
    private val _key = MutableStateFlow<String?>(null)
    private val _served = MutableStateFlow(0)
    private val _error = MutableStateFlow<String?>(null)
    private val _refused = MutableStateFlow(0)

    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** The key while access is on; null while it is off. */
    val key: StateFlow<String?> = _key.asStateFlow()

    /** Requests answered since the app process started. */
    val served: StateFlow<Int> = _served.asStateFlow()

    /** Requests turned away with a 4xx (wrong key, bad request, too many at once) since the app process started. Not on screen; refusals are not audited. */
    val refused: StateFlow<Int> = _refused.asStateFlow()

    /** Why the listener could not open its port, or null. */
    val error: StateFlow<String?> = _error.asStateFlow()

    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveLock = Mutex()
    private var store: (suspend (Boolean, String?) -> Unit)? = null

    /** Loads the stored state. A switch that is on without a key (a damaged store) gets a key now. */
    @Synchronized
    fun restore(on: Boolean, key: String?, store: suspend (Boolean, String?) -> Unit) {
        this.store = store
        _served.value = 0
        _refused.value = 0
        _error.value = null
        val stored = if (on) key?.takeIf { it.isNotBlank() } else null
        _key.value = stored ?: if (on) AgentKey.generate() else null
        _enabled.value = on
        if (on && stored == null) save()
    }

    @Synchronized
    fun setEnabled(on: Boolean) {
        if (on == _enabled.value) return
        _key.value = if (on) AgentKey.generate() else null
        _enabled.value = on
        save()
    }

    /** Replaces the key; apps that hold the old one stop working. Does nothing while access is off. */
    @Synchronized
    fun newKey() {
        if (!_enabled.value) return
        _key.value = AgentKey.generate()
        save()
    }

    /** Debug builds only (intent extra `agentKey`): a known key for tests. Does nothing while access is off. */
    @Synchronized
    fun setKey(key: String) {
        if (!_enabled.value || key.isBlank()) return
        _key.value = key
        save()
    }

    fun noteServed() {
        _served.update { it + 1 }
    }

    fun noteRefused() {
        _refused.update { it + 1 }
    }

    fun reportError(message: String?) {
        _error.value = message
    }

    private fun save() {
        val s = store ?: return
        saveScope.launch { saveLock.withLock { s(_enabled.value, _key.value) } }
    }
}
