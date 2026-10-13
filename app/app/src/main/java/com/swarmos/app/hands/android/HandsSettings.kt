package com.swarmos.app.hands.android

import android.content.Context
import com.swarmos.app.BuildConfig
import com.swarmos.app.core.SwarmPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The two Hands settings, kept in memory for the brain (which cannot wait for DataStore) and stored in [SwarmPrefs].
 * Both start at their safe value: sharing off, test brain off. [load] runs once at app start, before the Hands screen can show.
 *
 * The test brain exists only in debug builds. The test phone has too little memory for a chat model, so a debug build can send
 * both tiers to a llama-server on the PC through `adb reverse`. A release or demo build never reads, keeps or follows the
 * stored port.
 */
object HandsSettings {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val share = MutableStateFlow(false)
    private val testPort = MutableStateFlow(0)

    /** Whether screen text may go to another device of the owner's cluster during a task (rule S5). */
    val shareScreens: StateFlow<Boolean> = share.asStateFlow()

    /** The port of the test brain in force: 0 in every build that is not a debug build. */
    val testBrainPort: StateFlow<Int> = testPort.asStateFlow()

    suspend fun load(ctx: Context) {
        val prefs = SwarmPrefs(ctx.applicationContext)
        share.value = prefs.handsShareScreens()
        testPort.value = effectivePort(prefs.handsTestBrainPort(), BuildConfig.DEBUG)
    }

    fun setShareScreens(on: Boolean) {
        share.value = on
        persist { it.setHandsShareScreens(on) }
    }

    /** Debug builds only; ignored elsewhere. 0 turns the test brain off. */
    fun setTestBrainPort(port: Int) {
        if (!BuildConfig.DEBUG) return
        val p = effectivePort(port, true)
        testPort.value = p
        persist { it.setHandsTestBrainPort(p) }
    }

    private fun persist(write: suspend (SwarmPrefs) -> Unit) {
        val ctx = runCatching { com.swarmos.app.AppGraph.appContext }.getOrNull() ?: return
        scope.launch { runCatching { write(SwarmPrefs(ctx)) } }
    }

    /** A stored port counts only in a debug build and only if it is a real port number. */
    fun effectivePort(stored: Int, debug: Boolean): Int = if (debug && stored in 1..65535) stored else 0
}
