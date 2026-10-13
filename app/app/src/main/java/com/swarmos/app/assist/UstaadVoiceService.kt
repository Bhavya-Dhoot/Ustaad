package com.swarmos.app.assist

import android.content.ComponentName
import android.content.Context
import android.service.voice.VoiceInteractionService

/**
 * The service that makes Ustaad appear under Settings > Apps > Default apps > Digital assistant app. Once the person chooses it,
 * the assist gesture (long press on power, corner swipe) opens a session ([UstaadSessionService]) and that opens the task sheet.
 *
 * Ustaad does not read the screen of the app underneath: no assist structure, no screenshot (see [UstaadSession]), no hotword,
 * no use from the lock screen (res/xml/voice_interaction.xml).
 */
class UstaadVoiceService : VoiceInteractionService() {
    companion object {
        /** True when the person has chosen Ustaad as the digital assistant. False also when the system cannot say. */
        fun isChosen(context: Context): Boolean =
            runCatching { isActiveService(context, ComponentName(context, UstaadVoiceService::class.java)) }.getOrDefault(false)
    }
}
