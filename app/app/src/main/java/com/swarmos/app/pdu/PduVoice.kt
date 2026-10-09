package com.swarmos.app.pdu

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/** Where speech is recognised. On-device is preferred; the system service may use the network, which the UI says. */
enum class VoiceMode { OnDevice, System, None }

sealed interface VoiceState {
    data object Idle : VoiceState
    data object Listening : VoiceState
    data class Failed(val message: String) : VoiceState
}

/**
 * Voice input for the question box (Android SpeechRecognizer). Create and use it on the main thread. [onText] gets
 * partial and final transcripts; the caller puts them in the field. RECORD_AUDIO must be granted before [start].
 */
class VoiceInput(
    private val ctx: Context,
    private val onText: (text: String, final: Boolean) -> Unit,
    private val onState: (VoiceState) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null

    fun start() {
        stop()
        val mode = mode(ctx)
        if (mode == VoiceMode.None) { onState(VoiceState.Failed("speech recognition is not available on this phone")); return }
        val r = if (mode == VoiceMode.OnDevice && Build.VERSION.SDK_INT >= 33) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        else SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onState(VoiceState.Listening)
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                first(partialResults)?.let { onText(it, false) }
            }
            override fun onResults(results: Bundle?) {
                first(results)?.let { onText(it, true) }
                onState(VoiceState.Idle)
                release()
            }
            override fun onError(error: Int) {
                onState(if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) VoiceState.Failed("no speech heard") else VoiceState.Failed(describe(error)))
                release()
            }
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag()),
        )
    }

    /** Stops listening and still delivers what was heard. */
    fun stop() {
        recognizer?.stopListening()
    }

    fun release() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun first(b: Bundle?): String? = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    companion object {
        fun mode(ctx: Context): VoiceMode = when {
            Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) -> VoiceMode.OnDevice
            SpeechRecognizer.isRecognitionAvailable(ctx) -> VoiceMode.System
            else -> VoiceMode.None
        }

        fun describe(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "audio recording failed"
            SpeechRecognizer.ERROR_CLIENT -> "speech service stopped"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "microphone permission is missing"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "speech service needs the network"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "speech service is busy"
            SpeechRecognizer.ERROR_SERVER -> "speech service error"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "language not available offline"
            else -> "speech error $error"
        }
    }
}
