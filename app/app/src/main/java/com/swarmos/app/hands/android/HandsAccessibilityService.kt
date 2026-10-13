package com.swarmos.app.hands.android

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * The accessibility service behind the Hands driver ("Ustaad Hands" in system settings). The owner turns it on there;
 * the app never does. It holds no logic: AccessibilityDriver uses the connected instance to read and act, and
 * this class only keeps that instance and the class name of the window that came to the front last.
 * Its config (what it may read, events; it declares no gestures) is res/xml/hands_accessibility.xml. Screen content is never logged.
 */
class HandsAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString().orEmpty()
        val cls = event.className?.toString().orEmpty()
        // our own windows (the Stop chip, our screens) must not replace the app the task works in
        if (pkg.isEmpty() || cls.isEmpty() || pkg == packageName) return
        front = Front(pkg, cls)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        release()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    private fun release() {
        StopOverlay.hide()
        if (instance === this) instance = null
        front = Front("", "")
    }

    /** The class named by the last window-state-changed event, and the package it came from. */
    data class Front(val pkg: String, val cls: String)

    companion object {
        /** The connected service; null while the owner has it off. */
        @Volatile
        var instance: HandsAccessibilityService? = null
            private set

        @Volatile
        var front: Front = Front("", "")
            private set

        /** The class name of the last window-state-changed event (an activity, or a dialog or popup in it). */
        val currentActivity: String get() = front.cls
    }
}
