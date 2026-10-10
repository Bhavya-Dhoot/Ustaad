package com.swarmos.app.llm

import com.swarmos.app.core.PhoneActivity

/**
 * What the Models module is doing, announced through the shared [PhoneActivity] banner under the owner id "models":
 * "Loading chat model.", "Loading embedder.", "Running a benchmark (minute 3 of 10).", "Downloading a model (1.9 of 18.6 GB).".
 * Several tasks can run at once (the chat model and the embedder load together at service start); the newest one is shown
 * with its stop action, and when it ends the one before it is shown again. The owner clears when the last task ends.
 */
internal object ModelsActivity {
    private const val OWNER = "models"

    private class Task(val text: String, val onStop: (() -> Unit)?)

    private val tasks = LinkedHashMap<String, Task>()

    /** Starts or replaces task [key]; [onStop] is the banner's Stop action for it (null: nothing to stop). */
    @Synchronized
    fun set(key: String, text: String, onStop: (() -> Unit)? = null) {
        tasks[key] = Task(text, onStop)
        publish()
    }

    /** Changes the text of a running task and keeps its stop action. A task that already ended is not revived. */
    @Synchronized
    fun update(key: String, text: String) {
        val old = tasks[key] ?: return
        tasks[key] = Task(text, old.onStop)
        publish()
    }

    @Synchronized
    fun end(key: String) {
        if (tasks.remove(key) != null) publish()
    }

    private fun publish() {
        val newest = tasks.entries.lastOrNull()
        if (newest == null) PhoneActivity.clear(OWNER) else PhoneActivity.set(OWNER, newest.value.text, newest.value.onStop)
    }

    fun loadKey(role: Role) = "load-${role.tag}"

    fun loadText(role: Role): String? = when (role) {
        Role.CHAT -> "Loading chat model."
        Role.EMBED -> "Loading embedder."
        Role.RPC -> null // MemoryCloud announces its own work
    }

    /** "1.9 of 18.6 GB" for big files, "120 of 310 MB" for small ones; total 0 (unknown) shows only what arrived. */
    fun sizePair(done: Long, total: Long): String {
        val gb = 1024.0 * 1024 * 1024
        val mb = 1024.0 * 1024
        return when {
            total <= 0 -> fmtBytes(done)
            total >= gb -> String.format(java.util.Locale.US, "%.1f of %.1f GB", done / gb, total / gb)
            else -> String.format(java.util.Locale.US, "%.0f of %.0f MB", done / mb, total / mb)
        }
    }

    fun downloadText(done: Long, total: Long) = "Downloading a model (${sizePair(done, total)})."

    fun benchText(minute: Int?, minutes: Int) = if (minute == null) "Running a benchmark." else "Running a benchmark (minute $minute of $minutes)."
}
