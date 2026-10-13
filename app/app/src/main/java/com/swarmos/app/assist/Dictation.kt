package com.swarmos.app.assist

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.swarmos.app.choir.VoiceInput
import com.swarmos.app.choir.VoiceState
import com.swarmos.app.ui.LineIcon
import com.swarmos.app.ui.SwarmColors
import com.swarmos.app.ui.SwarmIcon

/** Dictation into a task field with the phone's on-device recognizer (the same [VoiceInput] as Agent Choir and the Data Union). */
@Stable
class Dictation internal constructor(
    private val text: () -> String,
    private val onText: (String) -> Unit,
    private val hasPermission: () -> Boolean,
    private val askPermission: () -> Unit,
) {
    var listening by mutableStateOf(false)
        internal set

    /** A line for under the field: why it is not listening, or that it is. Null when there is nothing to say. */
    var message by mutableStateOf<String?>(null)
        internal set

    internal lateinit var voice: VoiceInput
    private var base = ""

    val available: Boolean get() = voice.available

    internal fun heard(heard: String) {
        onText((base + " " + heard).trim())
    }

    internal fun begin() {
        base = text()
        message = null
        voice.start()
    }

    /** The mic button. */
    fun toggle() {
        when {
            listening -> voice.stop()
            !available -> message = "No on-device speech recognition on this phone"
            hasPermission() -> begin()
            else -> askPermission()
        }
    }

    /** Starts listening when the microphone permission is already given and the field is empty; never asks for the permission. */
    fun startIfAllowed() {
        if (!listening && available && hasPermission() && text().isEmpty()) begin()
    }

    internal fun denied() {
        message = "Microphone permission was not given"
    }
}

@Composable
fun rememberDictation(text: () -> String, onText: (String) -> Unit): Dictation {
    val context = LocalContext.current
    val currentText = rememberUpdatedState(text)
    val currentOnText = rememberUpdatedState(onText)
    lateinit var dictation: Dictation
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) dictation.begin() else dictation.denied()
    }
    dictation = remember {
        Dictation(
            { currentText.value() }, { currentOnText.value(it) },
            hasPermission = { ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
            askPermission = { permission.launch(Manifest.permission.RECORD_AUDIO) },
        ).also { d ->
            d.voice = VoiceInput(
                context,
                onText = { heard -> d.heard(heard) },
                onState = { s, msg ->
                    d.listening = s == VoiceState.LISTENING
                    d.message = msg
                },
            )
        }
    }
    DisposableEffect(dictation) { onDispose { dictation.voice.destroy() } }
    return dictation
}

/** Mic icon in a 40 dp circle (48 dp to tap): filled Ink while listening, greyed when the phone has no on-device recognizer. */
@Composable
fun DictationButton(dictation: Dictation, modifier: Modifier = Modifier) {
    val ink = if (dictation.available) SwarmColors.ink else SwarmColors.graphite
    Box(modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(40.dp)
                .clickable(role = Role.Button, onClick = dictation::toggle)
                .background(if (dictation.listening) SwarmColors.ink else if (dictation.available) SwarmColors.sheet else SwarmColors.smudge, CircleShape)
                .border(1.5.dp, ink, CircleShape),
            contentAlignment = Alignment.Center,
        ) { LineIcon(SwarmIcon.Mic, tint = if (dictation.listening) SwarmColors.sheet else ink, size = 22.dp) }
    }
}
