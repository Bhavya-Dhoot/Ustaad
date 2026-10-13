package com.swarmos.app.assist

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * The recognition service that the digital assistant role requires. Ustaad offers no recognition of its own (it uses the
 * phone's on-device recognizer from its own sheet), so every request ends at once with an error and no audio is read.
 */
class UstaadRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        try {
            listener?.error(SpeechRecognizer.ERROR_CLIENT)
        } catch (_: Exception) {
            // the caller is gone
        }
    }

    override fun onStopListening(listener: Callback?) {}

    override fun onCancel(listener: Callback?) {}
}
