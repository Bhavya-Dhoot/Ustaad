package com.swarmos.app.choir

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

enum class VoiceState { IDLE, LISTENING }

/**
 * Dictation for the problem box with Android's own SpeechRecognizer. Only the ON-DEVICE recognizer is used
 * (SpeechRecognizer.isOnDeviceRecognitionAvailable, API 33+): the default recognizer may send audio to a server,
 * and this app makes no cloud calls. When the phone has no on-device recognizer, [available] is false and the
 * screen says so instead of listening.
 */
class VoiceInput(
    private val context: Context,
    /** Partial and final transcripts, each replacing the previous one. */
    private val onText: (String) -> Unit,
    private val onState: (VoiceState, String?) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null

    val available: Boolean
        get() = Build.VERSION.SDK_INT >= 33 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)

    fun start() {
        if (!available) {
            onState(VoiceState.IDLE, "No on-device speech recognition on this phone")
            return
        }
        if (Build.VERSION.SDK_INT < 33) return // not reached: [available] is false below 33; the lint check cannot read through it
        destroy()
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onState(VoiceState.LISTENING, null)
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) = deliver(partialResults)
            override fun onResults(results: Bundle?) {
                deliver(results)
                onState(VoiceState.IDLE, null)
            }
            override fun onError(error: Int) = onState(VoiceState.IDLE, describe(error))
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        r.startListening(intent)
    }

    fun stop() {
        recognizer?.stopListening()
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun deliver(b: Bundle?) {
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(onText)
    }

    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Did not hear a problem, try again"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission was not given"
        SpeechRecognizer.ERROR_AUDIO -> "Could not read the microphone"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognition is busy"
        else -> "Speech recognition stopped (error $error)"
    }
}
