package com.swarmos.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One thing this phone is doing for the swarm. [onStop] is given when the user can cancel it from the banner. */
data class ActivityEntry(val owner: String, val text: String, val onStop: (() -> Unit)? = null)

/**
 * What this phone is doing right now, in plain words, for whoever picks it up. Modules call [set] when they start
 * work and again as it progresses (same owner: the text is replaced in place), and [clear] when they are done. The
 * newest owner is shown; when it clears, the one before it is shown again. The same line goes into the foreground
 * notification and into the slim banner under the status strip.
 *
 * Owners are short ids: "choir", "pdu", "sched", "models", "memcloud".
 */
object PhoneActivity {
    private var entries = listOf<ActivityEntry>()
    private val _current = MutableStateFlow<ActivityEntry?>(null)

    /** The entry to show, or null when the phone is idle. */
    val current: StateFlow<ActivityEntry?> = _current.asStateFlow()

    @Synchronized
    fun set(owner: String, text: String, onStop: (() -> Unit)? = null) {
        val entry = ActivityEntry(owner, text, onStop)
        val at = entries.indexOfFirst { it.owner == owner }
        entries = if (at < 0) entries + entry else entries.toMutableList().also { it[at] = entry }
        _current.value = entries.lastOrNull()
    }

    /** True while [owner] has an entry, whether or not it is the one on show (the shown entry is only the newest). */
    @Synchronized
    fun isActive(owner: String): Boolean = entries.any { it.owner == owner }

    @Synchronized
    fun clear(owner: String) {
        entries = entries.filterNot { it.owner == owner }
        _current.value = entries.lastOrNull()
    }

    /** Drops everything (the service stopped, so nothing is running). */
    @Synchronized
    fun clearAll() {
        entries = emptyList()
        _current.value = null
    }
}

/** The state line of the foreground notification: what is connected, whether the chat model is ready, or the current activity. */
object ServiceLine {
    enum class Chat { READY, LOADING, FAILED, NONE }

    /** "2 phones connected. Chat model ready." or, while something runs, just that activity's text. */
    fun describe(onlinePeers: Int, chat: Chat, activity: String?): String =
        activity?.takeIf { it.isNotBlank() } ?: "${peers(onlinePeers)} ${chatText(chat)}"

    fun peers(n: Int): String = when (n) {
        0 -> "No other phones connected."
        1 -> "1 phone connected."
        else -> "$n phones connected."
    }

    fun chatText(chat: Chat): String = when (chat) {
        Chat.READY -> "Chat model ready."
        Chat.LOADING -> "Chat model loading."
        Chat.FAILED -> "Chat model failed."
        Chat.NONE -> "No chat model loaded."
    }
}
