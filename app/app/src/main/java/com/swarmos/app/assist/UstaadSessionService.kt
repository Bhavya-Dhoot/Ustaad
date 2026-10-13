package com.swarmos.app.assist

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log

/** Creates the session when the person invokes the assistant. */
class UstaadSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = UstaadSession(this)
}

/**
 * One invocation of the assistant. It has no window of its own: it opens the task sheet in the assistant's task through
 * startAssistantActivity, with a one-time token that tells the sheet this came from here (origin ASSISTANT_SESSION).
 * The sheet still waits for the person to type or speak a task and tap Run.
 */
class UstaadSession(context: Context) : VoiceInteractionSession(context) {
    override fun onCreate() {
        super.onCreate()
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val intent = Intent(context, AssistActivity::class.java)
            .setAction(Intent.ACTION_ASSIST)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(AssistActivity.EXTRA_SESSION_TOKEN, SessionPass.shared.issue())
        try {
            startAssistantActivity(intent)
        } catch (e: RuntimeException) {
            Log.w("UstaadSession", "the task sheet could not be opened: ${e.javaClass.simpleName}")
            hide()
        }
    }

    // Ustaad does not use what is on the screen below it. These handlers are empty on purpose: the structure of the other app's
    // window, its content and the screenshot are dropped here and never stored, logged or sent anywhere.
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {}

    override fun onHandleScreenshot(screenshot: Bitmap?) {}
}
