package com.swarmos.app.hands.android

import com.swarmos.app.hands.Hands
import com.swarmos.app.hands.Origin

/**
 * Starts a run from Ustaad's own UI with the foreground service already up, so the phone does not kill the process mid-run.
 * Only code that is in front when the person taps may call this (the task sheet and the Hands screen): a foreground service
 * must not be started from the background, and nothing here does it by itself. [startService] is the existing `SwarmService.start`.
 */
class RunLauncher(private val serviceRunning: () -> Boolean, private val startService: () -> Unit) {
    fun start(hands: Hands, task: String, origin: Origin): String {
        ensureService()
        return hands.start(task, origin)
    }

    fun go(hands: Hands, runId: String) {
        ensureService()
        hands.go(runId)
    }

    private fun ensureService() {
        if (serviceRunning()) return
        try {
            startService()
        } catch (e: Exception) {
            // the system may refuse (restrictions on starting a foreground service); the person's task still runs
        }
    }
}
