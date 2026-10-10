package com.swarmos.app.pdu

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.swarmos.app.choir.VoiceInput
import com.swarmos.app.choir.VoiceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swarmos.app.ui.CitedText
import com.swarmos.app.ui.LineIcon
import com.swarmos.app.ui.SwarmIcon
import com.swarmos.app.ui.SwarmTextField
import com.swarmos.app.ui.Hairline
import com.swarmos.app.ui.PhoneToken
import com.swarmos.app.ui.PrimaryButton
import com.swarmos.app.ui.SecondaryButton
import com.swarmos.app.ui.SectionTitle
import com.swarmos.app.ui.Skeleton
import com.swarmos.app.ui.Stamp
import com.swarmos.app.ui.SwarmCard
import com.swarmos.app.ui.SwarmColors
import com.swarmos.app.ui.SwarmShape
import com.swarmos.app.ui.SwarmType
import com.swarmos.app.ui.Tag
import kotlinx.coroutines.launch

/** "Back" as plain underlined text, the way the kit has no header bar to carry it. */
@Composable
internal fun BackLink(onClick: () -> Unit) {
    Text(
        "Back",
        Modifier.heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically).clickable(onClick = onClick).padding(end = 16.dp),
        style = SwarmType.label.copy(textDecoration = TextDecoration.Underline),
    )
}

@Composable
internal fun PageHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BackLink(onBack)
        Text(title, style = SwarmType.display)
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun AskPage(onBack: () -> Unit, onAudit: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val q by PduEngine.query.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    var opened by remember { mutableStateOf<String?>(null) }
    val busy = q != null && q?.phase != "done"

    // voice: tap the mic to speak the question. Only the on-device recognizer is used (same class as Agent Choir); no network fallback.
    var listening by remember { mutableStateOf(false) }
    var voiceMessage by remember { mutableStateOf<String?>(null) }
    val voice = remember { VoiceInput(ctx, onText = { text = it }, onState = { s, msg -> listening = s == VoiceState.LISTENING; voiceMessage = msg }) }
    val voiceOk = voice.available
    DisposableEffect(Unit) { onDispose { voice.destroy() } }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) voice.start() else voiceMessage = "microphone permission was not granted"
    }
    fun onMic() {
        when {
            listening -> voice.stop()
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> voice.start()
            else -> askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val voiceHelper = when {
        listening -> "listening, on this phone"
        !voiceOk -> "No on-device speech recognition on this phone"
        else -> null
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PageHeader("Data Union", onBack)
        SwarmTextField(
            text, { text = it }, "Question",
            helper = voiceHelper, error = if (listening || !voiceOk) null else voiceMessage,
            trailing = { MicButton(listening, voiceOk, ::onMic) },
        )
        PrimaryButton(
            if (busy) "Searching" else "Ask",
            { val t = text.trim(); if (t.isNotEmpty()) PduEngine.scope.launch { PduEngine.ask(t) } },
            Modifier.fillMaxWidth(), enabled = !busy && text.isNotBlank(),
        )

        val cur = q
        if (cur != null) {
            TimingStrip(cur)
            if (cur.lexicalOnly) Tag("keyword search only on this phone, embedder not usable")

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Sources")
                Spacer(Modifier.width(10.dp))
                if (cur.sources.isNotEmpty()) Tag(if (cur.sources.size == 1) "1 match" else "${cur.sources.size} matches")
            }
            if (cur.sources.isEmpty()) {
                if (cur.phase == "searching") { Skeleton(96.dp); Skeleton(96.dp) }
                else Text("No phone found anything relevant.", style = SwarmType.body.copy(color = SwarmColors.graphite))
            }
            for (s in cur.sources) SourceCard(s) {
                scope.launch { opened = PduEngine.openSource(s).let { r -> if (r.opened) "opened on ${if (s.local) "this phone" else s.deviceName}" else "could not open: ${r.error}" } }
            }
            if (cur.incomplete) IncompleteNotice(cur)
            opened?.let { Text(it, style = SwarmType.mono.copy(color = SwarmColors.graphite)) }

            AnswerBlock(cur) { n ->
                cur.sources.getOrNull(n - 1)?.let { s ->
                    scope.launch { opened = PduEngine.openSource(s).let { r -> if (r.opened) "opened on ${if (s.local) "this phone" else s.deviceName}" else "could not open: ${r.error}" } }
                }
            }
            SecondaryButton("Query audit", onAudit, Modifier.fillMaxWidth())
        }
    }
}

/** The mic at the right end of the question box: 48 dp, filled Ink while listening, grey and inert when the phone has no on-device recognizer. */
@Composable
private fun MicButton(listening: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .background(if (listening) SwarmColors.ink else Color.Transparent, SwarmShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = if (listening) "Stop listening" else "Speak the question" },
        contentAlignment = Alignment.Center,
    ) {
        LineIcon(SwarmIcon.Mic, tint = if (listening) SwarmColors.sheet else if (enabled) SwarmColors.ink else SwarmColors.graphite)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimingStrip(q: QueryUi) {
    fun ms(v: Long) = if (v < 0) "-" else "$v ms"
    fun pair(label: String, value: String) = buildAnnotatedString {
        withStyle(SpanStyle(color = SwarmColors.graphite)) { append("$label ") }
        append(value)
    }
    val t = q.timings
    Column(Modifier.fillMaxWidth()) {
        Hairline()
        FlowRow(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(pair("embed", ms(t.embedMs)), style = SwarmType.mono)
            Text(pair("search", ms(t.localSearchMs)), style = SwarmType.mono)
            for (d in q.devices.filter { !it.local }) Text(pair(d.name, if (d.status == "waiting") "-" else ms(d.rttMs)), style = SwarmType.mono)
            Text(pair("first word", ms(t.ttftMs)), style = SwarmType.mono)
            Text(pair("total", ms(t.totalMs)), style = SwarmType.mono)
        }
        Hairline()
    }
}

@Composable
private fun SourceCard(s: SourceUi, onOpen: () -> Unit) {
    SwarmCard(owner = s.deviceName, modifier = Modifier.clickable(onClick = onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("[${s.n}]", style = SwarmType.mono)
            Spacer(Modifier.width(8.dp))
            PhoneToken(s.deviceName, 24.dp)
            Spacer(Modifier.width(8.dp))
            Text(s.deviceName, style = SwarmType.title.copy(fontSize = 15.sp, lineHeight = 20.sp), maxLines = 1)
            Spacer(Modifier.weight(1f))
            Text(PromptBuilder.day(s.hit.dateMs), style = SwarmType.mono.copy(color = SwarmColors.graphite))
        }
        Spacer(Modifier.height(6.dp))
        Tag(s.hit.title)
        Spacer(Modifier.height(8.dp))
        Hairline()
        Spacer(Modifier.height(8.dp))
        Text(s.hit.snippet, style = SwarmType.body.copy(fontSize = 14.sp, lineHeight = 20.sp), maxLines = 6, overflow = TextOverflow.Ellipsis)
        if (s.localPrivate) {
            Spacer(Modifier.height(6.dp))
            Tag("private folder, this phone only")
        }
    }
}

@Composable
private fun IncompleteNotice(q: QueryUi) {
    val missing = q.devices.filter { !it.local && it.status != "ok" && it.status != "waiting" }
    val answered = q.devices.count { it.status == "ok" }
    Row(Modifier.fillMaxWidth().border(1.5.dp, SwarmColors.ink, SwarmShape).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Stamp("Incomplete", caution = true)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (d in missing) {
                Text(if (d.status == "error") "${d.name} answered with an error" else "${d.name} did not answer", style = SwarmType.body.copy(fontSize = 14.sp, lineHeight = 20.sp))
                d.detail?.let { Text(it, style = SwarmType.mono.copy(fontSize = 12.sp, color = SwarmColors.graphite), maxLines = 2) }
            }
            Text("$answered of ${q.devices.size} phones answered", style = SwarmType.mono.copy(color = SwarmColors.graphite))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnswerBlock(q: QueryUi, onCite: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SectionTitle("Answer")
        Spacer(Modifier.width(10.dp))
        // a model name only when a real chat model wrote (or is writing) the answer; there is no placeholder text to label any more
        if (q.model.isNotEmpty()) Tag(q.model)
    }
    SwarmCard {
        val note = q.note
        if (q.answer.isEmpty()) {
            when {
                note != null -> Text(note, style = SwarmType.body.copy(color = SwarmColors.graphite))
                q.phase == "done" -> Text("no answer", style = SwarmType.body.copy(color = SwarmColors.graphite))
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Skeleton(18.dp); Skeleton(18.dp); Skeleton(18.dp) }
            }
        } else {
            CitedText(q.answer, streaming = q.phase == "answering", onCite = onCite, cites = 1..q.sources.size)
            if (note != null) {
                Spacer(Modifier.height(8.dp))
                Text(note, style = SwarmType.label.copy(color = SwarmColors.graphite))
            }
        }
    }
}
