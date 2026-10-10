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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swarmos.app.kb.KbCard
import com.swarmos.app.kb.KbDraft
import com.swarmos.app.kb.KbHitCard
import com.swarmos.app.kb.KbText
import com.swarmos.app.kb.SaveCardPanel
import com.swarmos.app.kb.SavedAnswerBlock
import com.swarmos.app.ui.CitedText
import com.swarmos.app.ui.LineIcon
import com.swarmos.app.ui.SwarmIcon
import com.swarmos.app.ui.SwarmTextField
import com.swarmos.app.ui.Hairline
import com.swarmos.app.ui.hardShadow
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
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current

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
        // two lines, so a long question shows its start instead of scrolling it out of the box
        SwarmTextField(
            text, { text = it }, "Question",
            helper = voiceHelper, error = if (listening || !voiceOk) null else voiceMessage,
            singleLine = false, minLines = 2, maxLines = 4,
            trailing = { MicButton(listening, voiceOk, ::onMic) },
        )
        PrimaryButton(
            if (busy) "Searching" else "Ask",
            {
                val t = text.trim()
                if (t.isNotEmpty()) {
                    keyboard?.hide() // the answer and the sources are the point now, not the keyboard
                    focus.clearFocus()
                    PduEngine.scope.launch { PduEngine.ask(t) }
                }
            },
            Modifier.fillMaxWidth(), enabled = !busy && text.isNotBlank(),
        )

        val cur = q
        if (cur != null) {
            TimingStrip(cur)
            if (cur.lexicalOnly) Text("Keyword search only: this phone's embedding model is not loaded.", style = SwarmType.label.copy(color = SwarmColors.graphite))

            // the answer first, directly under the timings; the sources it cites follow
            val savedHit = cur.savedAnswer
            // a tap on a marker of a saved answer names that source (the file is not on this phone)
            val citeSaved = { card: KbCard, n: Int -> KbText.citeLine(card, n)?.let { opened = it }; Unit }
            if (savedHit != null) SavedAnswerBlock(savedHit, { n -> citeSaved(savedHit.entry.card, n) }) { PduEngine.scope.launch { PduEngine.ask(cur.text, useSaved = false) } }
            else AnswerBlock(cur) { n ->
                cur.sources.getOrNull(n - 1)?.let { s ->
                    scope.launch { opened = PduEngine.openSource(s).let { r -> if (r.opened) "opened on ${if (s.local) "this phone" else s.deviceName}" else "could not open: ${r.error}" } }
                }
            }
            val draft = KbDraft.of(cur)
            var saving by remember(cur.qid) { mutableStateOf(false) }
            var savedMessage by remember(cur.qid) { mutableStateOf<String?>(null) }
            if (draft != null && savedMessage == null) {
                if (saving) SaveCardPanel(draft, onSaved = { savedMessage = it; saving = false }, onCancel = { saving = false })
                else SecondaryButton("Save to knowledge bank", { saving = true }, Modifier.fillMaxWidth())
            }
            savedMessage?.let { Tag(it) }

            val otherCards = cur.cards.filter { it.entry.card.id != savedHit?.entry?.card?.id }
            if (otherCards.isNotEmpty()) {
                SectionTitle("From the knowledge bank")
                for (h in otherCards) KbHitCard(h) { n -> citeSaved(h.entry.card, n) }
            }
            val open = { s: SourceUi ->
                scope.launch { opened = PduEngine.openSource(s).let { r -> if (r.opened) "opened on ${if (s.local) "this phone" else s.deviceName}" else "could not open: ${r.error}" } }
                Unit
            }
            // once the answer is done the sources it cites come first; the others are listed as searched, not as support
            val split = SourceSplit.of(cur)
            if (cur.sources.isEmpty()) {
                SectionTitle("Sources")
                if (cur.phase == "searching") { Skeleton(96.dp); Skeleton(96.dp) }
                else Text("No phone found anything relevant.", style = SwarmType.body.copy(color = SwarmColors.graphite))
            }
            if (split.cited.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Sources")
                    Spacer(Modifier.width(10.dp))
                    Tag(
                        when {
                            savedHit != null -> "cited by the saved answer"
                            cur.phase != "done" -> if (split.cited.size == 1) "1 match" else "${split.cited.size} matches"
                            else -> "${split.cited.size} cited"
                        },
                    )
                }
                for (s in split.cited) SourceCard(s) { open(s) }
            }
            if (split.rest.isNotEmpty()) {
                SectionTitle("Also searched")
                for (s in split.rest) SourceCard(s) { open(s) }
            }
            if (cur.incomplete && savedHit == null) IncompleteNotice(cur)
            opened?.let { Text(it, style = SwarmType.mono.copy(color = SwarmColors.graphite)) }
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
            Text(pair("embed", if (q.lexicalOnly) "-" else ms(t.embedMs)), style = SwarmType.mono)
            Text(pair("search", ms(t.localSearchMs)), style = SwarmType.mono)
            for (d in q.devices.filter { !it.local }) Text(pair(d.name, PeerTiming.text(d)), style = SwarmType.mono)
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
        if (s.hit.snippet.isNotBlank()) Text(s.hit.snippet, style = SwarmType.body.copy(fontSize = 13.sp, lineHeight = 18.sp, color = SwarmColors.graphite), maxLines = 3, overflow = TextOverflow.Ellipsis)
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
    // the answer carries the emphasis: larger text and a hard offset shadow like the primary button; the sources below are quieter
    SwarmCard(Modifier.padding(end = 3.dp, bottom = 3.dp).hardShadow(3.dp, SwarmShape)) {
        val note = q.note
        val missing = MissingPhones.line(q.devices)
        // which phone is missing comes before the answer: a gap in the sources is not "nothing there"
        if (missing != null) {
            Text(missing, style = SwarmType.body.copy(fontSize = 15.sp, fontWeight = FontWeight.W500, color = SwarmColors.ink))
            Spacer(Modifier.height(8.dp))
        }
        if (q.answer.isEmpty()) {
            when {
                note != null -> Text(note, style = SwarmType.body.copy(color = SwarmColors.graphite))
                q.phase == "done" -> Text("no answer", style = SwarmType.body.copy(color = SwarmColors.graphite))
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Skeleton(18.dp); Skeleton(18.dp); Skeleton(18.dp) }
            }
        } else {
            CitedText(
                q.answer, streaming = q.phase == "answering", onCite = onCite, cites = 1..q.sources.size,
                style = SwarmType.body.copy(fontSize = 19.sp, lineHeight = 27.sp),
            )
            if (note != null) {
                Spacer(Modifier.height(8.dp))
                Text(note, style = SwarmType.label.copy(color = SwarmColors.graphite))
            }
        }
    }
}

/** The one line in the answer area when a phone did not answer: which one, so a gap in the sources is not mistaken for "nothing there". */
internal object MissingPhones {
    fun line(devices: List<DeviceResult>): String? {
        val names = devices.filter { !it.local && it.status != "ok" && it.status != "waiting" }.map { it.name }
        return when (names.size) {
            0 -> null
            1 -> "${names[0]} did not answer"
            else -> names.dropLast(1).joinToString(", ") + " and " + names.last() + " did not answer"
        }
    }
}

/** The sources a finished answer cites ([n] markers) and the rest, which were searched but not cited. Before the answer is done, and for a saved answer, every source counts as cited. */
internal object SourceSplit {
    class Split(val cited: List<SourceUi>, val rest: List<SourceUi>)

    fun of(q: QueryUi): Split {
        if (q.phase != "done" || q.savedAnswer != null) return Split(q.sources, emptyList())
        val numbers = KbDraft.cited(q.answer, q.sources.size).toSet()
        return Split(q.sources.filter { it.n in numbers }, q.sources.filter { it.n !in numbers })
    }
}

/** What the timing strip prints for a peer: its round trip only when it answered, otherwise why there is none. */
internal object PeerTiming {
    fun text(d: DeviceResult): String = when (d.status) {
        "ok" -> "${d.rttMs} ms"
        "waiting" -> "-"
        "offline" -> "away"
        else -> "no reply"
    }
}
