package com.swarmos.app.hands.android

import com.swarmos.app.hands.AppInfo
import com.swarmos.app.hands.PhoneDriver
import com.swarmos.app.hands.RawScreen
import kotlinx.coroutines.delay

/**
 * A driver that does not act while the person has no way to stop the run: neither the Stop chip nor the notification is showing
 * ([surface]). Reading stays free. An acting call waits for a surface for up to [maxWaitMs] (the chip is put back when the
 * accessibility service reconnects), polling every [pollMs] and staying cancellable, and returns false if none came; the step loop
 * then treats the action as one that did not work. Nothing else about the driver changes.
 */
class StopGatedDriver(
    private val inner: PhoneDriver,
    private val surface: () -> Boolean,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val maxWaitMs: Long = MAX_WAIT_MS,
    private val pollMs: Long = POLL_MS,
) : PhoneDriver {
    override val name: String get() = inner.name
    override fun ready(): Boolean = inner.ready()

    private suspend fun allowed(): Boolean {
        var waited = 0L
        while (!surface()) {
            if (waited >= maxWaitMs) return false
            pause(pollMs)
            waited += pollMs
        }
        return true
    }

    override suspend fun read(): RawScreen = inner.read()
    override suspend fun tap(handle: String, cx: Int, cy: Int): Boolean = allowed() && inner.tap(handle, cx, cy)
    override suspend fun setText(handle: String, cx: Int, cy: Int, text: String): Boolean = allowed() && inner.setText(handle, cx, cy, text)
    override suspend fun enter(): Boolean = allowed() && inner.enter()
    override suspend fun scroll(handle: String?, forward: Boolean): Boolean = allowed() && inner.scroll(handle, forward)
    override suspend fun back(): Boolean = allowed() && inner.back()
    override suspend fun home(): Boolean = allowed() && inner.home()
    override suspend fun openApp(pkg: String): Boolean = allowed() && inner.openApp(pkg)
    override suspend fun linkTarget(uri: String): String = inner.linkTarget(uri)
    override suspend fun openLink(uri: String, pkg: String): Boolean = allowed() && inner.openLink(uri, pkg)
    override suspend fun apps(): List<AppInfo> = inner.apps()

    companion object {
        const val MAX_WAIT_MS = 10_000L
        const val POLL_MS = 250L
    }
}
