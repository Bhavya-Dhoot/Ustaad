package com.swarmos.app.assist

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Reads the result of a spoken task aloud with the phone's TextToSpeech. Only a voice that works without the network is used
 * (this app makes no cloud calls): when the phone has none, nothing is spoken. One engine per sheet, shut down with it.
 */
class Speaker(context: Context) {
    private val app = context.applicationContext
    private var tts: TextToSpeech? = null
    private var ready = false
    private var failed = false
    private var queued: String? = null
    private var closing = false

    @Synchronized
    fun speak(text: String) {
        if (text.isBlank() || failed || closing) return
        queued = text
        val engine = tts
        if (engine == null) tts = TextToSpeech(app) { status -> onInit(status) } else if (ready) flush()
    }

    /** Cuts the speech off (the person tapped Stop or started a new task). */
    @Synchronized
    fun stop() {
        queued = null
        tts?.stop()
    }

    /** The sheet is closing: a sentence that is being read is finished first, then the engine is shut down. */
    @Synchronized
    fun close() {
        queued = null
        closing = true
        if (tts?.isSpeaking != true) release()
    }

    @Synchronized
    private fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS || !useOfflineVoice(engine)) {
            failed = true
            release()
            return
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finished()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished()
            override fun onStop(utteranceId: String?, interrupted: Boolean) = finished()
        })
        ready = true
        flush()
    }

    @Synchronized
    private fun finished() {
        if (closing) release()
    }

    private fun flush() {
        val text = queued ?: return
        queued = null
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE)
    }

    private fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    private fun useOfflineVoice(engine: TextToSpeech): Boolean {
        val locale = Locale.getDefault()
        engine.language = locale
        val current = engine.voice
        if (current != null && !current.isNetworkConnectionRequired) return true
        val offline = engine.voices.orEmpty().filter { !it.isNetworkConnectionRequired && it.locale.language == locale.language }
        val best = offline.maxByOrNull { it.quality } ?: return false
        return engine.setVoice(best) == TextToSpeech.SUCCESS
    }

    private companion object {
        const val UTTERANCE = "ustaad-result"
    }
}
