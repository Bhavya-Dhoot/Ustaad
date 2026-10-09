package com.swarmos.app.choir

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swarmos.app.AppGraph
import com.swarmos.app.ui.AnswerChip
import com.swarmos.app.ui.Hairline
import com.swarmos.app.ui.InkCircle
import com.swarmos.app.ui.InkProgress
import com.swarmos.app.ui.Jitter
import com.swarmos.app.ui.Lane
import com.swarmos.app.ui.MetricRow
import com.swarmos.app.ui.Note
import com.swarmos.app.ui.PhoneToken
import com.swarmos.app.ui.PrimaryButton
import com.swarmos.app.ui.RoundDots
import com.swarmos.app.ui.SecondaryButton
import com.swarmos.app.ui.SectionTitle
import com.swarmos.app.ui.Skeleton
import com.swarmos.app.ui.Stamp
import com.swarmos.app.ui.StreamingText
import com.swarmos.app.ui.SwarmCard
import com.swarmos.app.ui.SwarmColors
import com.swarmos.app.ui.SwarmShape
import com.swarmos.app.ui.SwarmType
import com.swarmos.app.ui.Tag
import com.swarmos.app.ui.arrowheadPath
import com.swarmos.app.ui.drawHatch
import com.swarmos.app.ui.hatched
import com.swarmos.app.ui.inkStroke
import com.swarmos.app.ui.smoothPath
import com.swarmos.app.ui.underlinePath
import com.swarmos.app.ui.wobblePoints
import kotlinx.coroutines.delay
import kotlin.math.atan2

private val Mono11 get() = SwarmType.mono.copy(fontSize = 11.sp, lineHeight = 14.sp)
private val Mono11Dim get() = Mono11.copy(color = SwarmColors.graphite)
private val Body13 get() = SwarmType.body.copy(fontSize = 13.sp, lineHeight = 18.sp)

private fun secs(ms: Long) = "%.1f".format(ms / 1000.0)
private fun pct(k: Int, n: Int) = if (n == 0) "0%" else "%d%%".format(Math.round(100.0 * k / n))

private fun kindLabel(k: Kind) = when (k) {
    Kind.MATH -> "Math"
    Kind.CODE -> "Code"
    Kind.MCQ -> "Multiple choice"
    Kind.TEXT -> "Text"
}

private fun roleLabel(r: Role) = if (r == Role.ALT_SOLVER) "Alt solver" else "Solver"

/** Right or wrong against the problem's known answer, in words (never colour). */
private fun knownWord(b: Boolean?) = when (b) {
    true -> "right"
    false -> "wrong"
    null -> ""
}

/** Which stamp a final pick earns, and whether it is a caution (hatched) one. Only a check supplied with the problem says VERIFIED. */
internal fun finalStamp(f: FinalTrace): Pair<String, Boolean> = when {
    f.answer == null -> "No answer" to true
    f.check == CheckResult.VERIFIED && f.checkSource == PROBLEM_CHECK -> "Verified" to false
    f.check == CheckResult.VERIFIED && f.checkSource == "quote-exists" -> "Quotes found" to false
    f.check == CheckResult.VERIFIED -> "Consistent" to false
    f.check == CheckResult.REFUTED -> "Refuted" to true
    else -> "Unverified" to true
}

/** Word for a node's own tool-check next to its answer. */
private fun checkWord(r: Reply) = when {
    r.check == CheckResult.VERIFIED && r.checkSource == PROBLEM_CHECK -> "verified"
    r.check == CheckResult.VERIFIED -> "consistent"
    r.check == CheckResult.REFUTED -> "refuted"
    else -> ""
}

/** Plain-words status under the title, from the run itself. */
internal fun statusLine(r: RunTrace): String = when (r.phase) {
    "INIT", "R1" -> "Round 1: every phone solves alone"
    "R2" -> "Round 2: the phones check each other's answers"
    "R3" -> "Round 3: a judge picks between the answers"
    "CHECK" -> "Checking the answers with the tool"
    else -> when {
        r.error != null -> "Not run"
        r.agreement == "AGREE" && r.totals.rounds > 1 -> "Round ${r.totals.rounds} converged"
        r.agreement == "AGREE" -> "All phones agree after round 1"
        else -> "Done after round ${r.totals.rounds}"
    }
}

/** Handwritten remark on a disagreement, built from the real answers: who differs, or that nobody agrees. */
internal fun disagreeNote(r: RunTrace): String? {
    val voters = r.lanes.filter { it.latest != null }
    if (voters.size < 2) return null
    val groups = Normalizer.cluster(r.kind, voters.map { it.latest!!.answer })
    if (groups.size < 2) return null
    val top = groups.maxByOrNull { it.size }!!
    if (top.size * 2 <= voters.size) return "${groups.size} different answers"
    val odd = voters.filterIndexed { i, _ -> i !in top }
    return odd.joinToString(" and ") { "${it.name} says ${it.latest!!.answer}" }
}

@Composable
fun ChoirScreen() {
    val run by ChoirController.run.collectAsStateWithLifecycle()
    val eval by ChoirController.eval.collectAsStateWithLifecycle()
    val busy by ChoirController.busy.collectAsStateWithLifecycle()
    val peers by AppGraph.registry.peers.collectAsStateWithLifecycle()
    val self by AppGraph.registry.self.collectAsStateWithLifecycle()
    val problems = remember { ChoirController.problems }

    var text by rememberSaveable { mutableStateOf("") }
    var kindName by rememberSaveable { mutableStateOf(Kind.MATH.name) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var useTest by rememberSaveable { mutableStateOf(false) }
    val kind = Kind.valueOf(kindName)

    // dictation (on-device recognizer only; the mic is disabled, with a line saying why, when the phone has none)
    val context = LocalContext.current
    var voice by remember { mutableStateOf(VoiceState.IDLE) }
    var voiceMessage by remember { mutableStateOf<String?>(null) }
    var dictationBase by remember { mutableStateOf("") }
    val voiceInput = remember {
        VoiceInput(
            context,
            onText = { heard -> text = (dictationBase + " " + heard).trim(); selectedId = null },
            onState = { s, msg -> voice = s; voiceMessage = msg },
        )
    }
    val voiceAvailable = remember { voiceInput.available }
    DisposableEffect(Unit) { onDispose { voiceInput.destroy() } }
    fun startDictation() {
        dictationBase = text
        voiceMessage = null
        voiceInput.start()
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startDictation() else voiceMessage = "Microphone permission was not given"
    }
    val onMic: () -> Unit = {
        when {
            voice == VoiceState.LISTENING -> voiceInput.stop()
            !voiceAvailable -> voiceMessage = "No on-device speech recognition on this phone"
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> startDictation()
            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(busy) {
        while (busy) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }

    val listState = rememberLazyListState()
    val showRun = run != null
    val showBoard = eval.running || eval.rows.isNotEmpty() || eval.error != null
    // a new run (or eval) brings its section to the top: items 0 and 1 are the header and the input
    LaunchedEffect(run?.taskId) { if (run != null) listState.animateScrollToItem(2) }
    LaunchedEffect(eval.running) { if (eval.running && showBoard) listState.animateScrollToItem(if (showRun) 3 else 2) }

    val fakeModel = AppGraph.llm.modelName == "FAKE"
    val peersOnline = peers.filter { !it.away }.sortedBy { it.info.name }

    Column(Modifier.fillMaxWidth().background(SwarmColors.whiteboard)) {
        // title and round dots stay in view whatever is scrolled
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Agent Choir", Modifier.weight(1f), style = SwarmType.display)
            val r = run
            if (r != null) RoundDots(current = if (r.phase == "DONE") r.totals.rounds else r.round)
            else if (fakeModel) Stamp("Fake", caution = true)
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "header") {
                Text("Each phone answers alone, then they compare.", style = SwarmType.body.copy(color = SwarmColors.graphite))
            }
            item(key = "input") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ProblemField(
                        value = text,
                        onValueChange = { text = it; selectedId = null },
                        helper = voiceMessage ?: if (voice == VoiceState.LISTENING) "Listening, speak the problem" else if (kind == Kind.MCQ) "For multiple choice, put the options in the text as A) ... B) ..." else null,
                        micListening = voice == VoiceState.LISTENING,
                        micEnabled = voiceAvailable,
                        onMic = onMic,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Kind.entries.forEach { k -> KindChip(kindLabel(k), k == kind) { kindName = k.name } }
                    }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { useTest = !useTest },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Check against the problem's test", style = SwarmType.label)
                            Text(
                                if (useTest) "On: built-in problems are checked against their own test" else "Off: only the models' written steps are checked",
                                style = SwarmType.label.copy(fontSize = 12.sp, color = SwarmColors.graphite),
                            )
                        }
                        Switch(checked = useTest, onCheckedChange = { useTest = it })
                    }
                    if (!showRun && !showBoard && text.isBlank()) EmptyHint()
                }
            }

            if (showRun) item(key = "run") { RunSection(run!!, now) }
            if (showBoard) item(key = "board") { ScoreboardSection(eval) }

            item(key = "phones") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionTitle("Phones in this run")
                    PhonesCard(
                        listOf(Triple(self.name, "Solver (coordinator)", self.chatModel ?: AppGraph.llm.modelName)) +
                            peersOnline.mapIndexed { i, p -> Triple(p.info.name, roleLabel(ChoirCoordinator.roleForPeer(i)), p.info.chatModel ?: "model unknown") },
                    )
                    if (peersOnline.isEmpty()) Text("No peers online: the coordinator runs alone.", style = SwarmType.label.copy(fontSize = 12.sp, color = SwarmColors.graphite))
                }
            }
            item(key = "try") {
                Row(verticalAlignment = Alignment.Bottom) {
                    SectionTitle("Try one", Modifier.weight(1f))
                    Text("${problems.size} benchmark items", style = Mono11Dim, modifier = Modifier.padding(bottom = 3.dp))
                }
            }
            item(key = "problems") {
                Column(Modifier.fillMaxWidth().clip(SwarmShape).background(SwarmColors.sheet).border(1.5.dp, SwarmColors.ink, SwarmShape)) {
                    problems.forEachIndexed { i, p ->
                        if (i > 0) Hairline()
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .background(if (p.id == selectedId) SwarmColors.smudge else Color.Transparent)
                                .clickable { text = p.prompt(); kindName = p.kind.name; selectedId = p.id }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(p.id, style = Mono11Dim, modifier = Modifier.width(30.dp))
                            Text(p.text.lines().first(), style = SwarmType.body.copy(fontSize = 14.sp, lineHeight = 18.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            KindTag(kindLabel(p.kind))
                        }
                    }
                }
            }
        }

        // the two actions stay in reach whatever is scrolled into view
        Hairline()
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(
                "Run",
                onClick = {
                    val builtIn = problems.firstOrNull { it.id == selectedId && it.prompt() == text }
                    ChoirController.startRun(builtIn ?: ChoirController.customProblem(text, kind), RunOptions(useProblemCheck = useTest))
                },
                modifier = Modifier.weight(1f),
                enabled = !busy && text.isNotBlank(),
            )
            SecondaryButton(
                "Run all ${problems.size}",
                onClick = { ChoirController.startEval(RunOptions(useProblemCheck = useTest)) },
                modifier = Modifier.weight(1f).padding(bottom = 3.dp),
                enabled = !busy && problems.isNotEmpty(),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------------
// compose parts
// ---------------------------------------------------------------------------------------------------

/**
 * The problem box. The kit's text field has no minimum height and no room for a button, so this is a local copy of its
 * look (label above, Sheet fill, 1.5 dp Ink outline, 8 dp corners) with three lines of height and the mic inside.
 */
@Composable
private fun ProblemField(
    value: String,
    onValueChange: (String) -> Unit,
    helper: String?,
    micListening: Boolean,
    micEnabled: Boolean,
    onMic: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text("Problem", style = SwarmType.label.copy(color = SwarmColors.graphite))
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.fillMaxWidth().heightIn(min = 124.dp)
                .background(SwarmColors.sheet, SwarmShape)
                .border(1.5.dp, SwarmColors.ink, SwarmShape),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 60.dp),
                textStyle = SwarmType.body,
                cursorBrush = SolidColor(SwarmColors.ink),
            )
            MicButton(micListening, micEnabled, onMic, Modifier.align(Alignment.BottomEnd).padding(8.dp))
        }
        if (helper != null) Text(helper, Modifier.padding(top = 4.dp), style = SwarmType.label.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SwarmColors.graphite))
    }
}

/** Ink outline circle with a drawn microphone; filled Ink while listening; greyed when the phone has no on-device recognizer. */
@Composable
private fun MicButton(listening: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ink = if (enabled) SwarmColors.ink else SwarmColors.graphite
    val fg = if (listening) SwarmColors.sheet else ink
    Box(
        modifier.size(44.dp).clickable(onClick = onClick)
            .background(if (listening) SwarmColors.ink else if (enabled) SwarmColors.sheet else SwarmColors.smudge, CircleShape)
            .border(1.5.dp, ink, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(22.dp)) {
            val w = size.width
            val stroke = inkStroke(1.6.dp.toPx())
            // capsule
            drawRoundRect(fg, Offset(w * 0.34f, w * 0.06f), androidx.compose.ui.geometry.Size(w * 0.32f, w * 0.5f), androidx.compose.ui.geometry.CornerRadius(w * 0.16f), style = stroke)
            // cradle
            drawArc(fg, 0f, 180f, false, Offset(w * 0.2f, w * 0.2f), androidx.compose.ui.geometry.Size(w * 0.6f, w * 0.56f), style = stroke)
            // stem and foot
            drawLine(fg, Offset(w * 0.5f, w * 0.76f), Offset(w * 0.5f, w * 0.92f), 1.6.dp.toPx())
            drawLine(fg, Offset(w * 0.34f, w * 0.94f), Offset(w * 0.66f, w * 0.94f), 1.6.dp.toPx())
        }
    }
}

@Composable
private fun KindChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 48.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(
            label,
            style = SwarmType.mono.copy(fontSize = 12.sp, lineHeight = 14.sp, color = if (selected) SwarmColors.sheet else SwarmColors.ink),
            maxLines = 1,
            modifier = Modifier
                .background(if (selected) SwarmColors.ink else SwarmColors.sheet, RoundedCornerShape(50))
                .border(1.5.dp, SwarmColors.ink, RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun KindTag(label: String) {
    Text(
        label,
        style = Mono11,
        maxLines = 1,
        modifier = Modifier.border(1.dp, SwarmColors.ink, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Empty state: one handwritten hint and an arrow down at the Run button. */
@Composable
private fun EmptyHint() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Note("pick one below or type your own, then Run", Modifier.weight(1f))
        HandArrowDown(Modifier.size(width = 36.dp, height = 44.dp))
    }
}

@Composable
private fun HandArrowDown(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val from = Offset(size.width * 0.35f, 2.dp.toPx())
        val to = Offset(size.width * 0.7f, size.height - 3.dp.toPx())
        val line = smoothPath(wobblePoints(from, to, 1.4.dp.toPx(), 9.dp.toPx(), Jitter(7)))
        val stroke = inkStroke(2.dp.toPx())
        drawPath(line, SwarmColors.ink, style = stroke)
        val dir = atan2((to.y - from.y).toDouble(), (to.x - from.x).toDouble())
        drawPath(arrowheadPath(to, dir, 8.dp.toPx(), Jitter(9)), SwarmColors.ink, style = stroke)
    }
}

@Composable
private fun PhonesCard(rows: List<Triple<String, String, String>>) {
    Column(Modifier.fillMaxWidth().clip(SwarmShape).background(SwarmColors.sheet).border(1.5.dp, SwarmColors.ink, SwarmShape)) {
        rows.forEachIndexed { i, (name, role, model) ->
            if (i > 0) Hairline()
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(6.dp).fillMaxHeight().background(SwarmColors.marker(name)))
                Row(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    PhoneToken(name, 28.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, style = SwarmType.title.copy(fontSize = 16.sp, lineHeight = 20.sp))
                        Text(role, style = SwarmType.label.copy(color = SwarmColors.graphite))
                    }
                    Text(model, style = Mono11, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------
// live run and converged
// ---------------------------------------------------------------------------------------------------

@Composable
private fun RunSection(r: RunTrace, now: Long) {
    val elapsed = if (r.phase == "DONE") r.totals.wallMs else (now - r.startedMs).coerceAtLeast(0)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SwarmCard {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("Problem ${r.problemId ?: ""}, ${kindLabel(r.kind)}", style = Mono11Dim)
                    Spacer(Modifier.height(4.dp))
                    Text(r.problem, style = SwarmType.body.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.W500))
                }
                if (r.fake) Stamp("Fake", caution = true, small = true)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(statusLine(r), style = SwarmType.label.copy(color = SwarmColors.graphite), modifier = Modifier.weight(1f))
            Text("${secs(elapsed)} s of ${secs(r.deadlineMs)} s", style = Mono11Dim)
        }
        r.error?.let { e -> Text(e, style = SwarmType.label) }

        r.lanes.forEach { LaneCard(it) }

        if (r.agreement == "DISAGREE") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Stamp("Disagree", caution = true)
                Spacer(Modifier.weight(1f))
                disagreeNote(r)?.let { Note(it, Modifier.padding(start = 8.dp), rotation = -2f) }
            }
        }

        r.baseline?.let { b ->
            Row(
                Modifier.fillMaxWidth().background(SwarmColors.sheet, SwarmShape).border(1.dp, SwarmColors.smudge, SwarmShape).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Baseline: ${b.nodeName} alone", style = Mono11Dim, modifier = Modifier.weight(1f))
                if (b.answer != null) {
                    Tag(b.answer)
                    Spacer(Modifier.width(10.dp))
                    Text("${secs(b.wallMs)} s", style = Mono11Dim)
                    if (b.correct != null) Text("  " + knownWord(b.correct), style = Mono11)
                } else Text(b.status.name.lowercase().replace('_', ' '), style = Mono11Dim)
            }
        }

        r.judge?.let { j ->
            SwarmCard(owner = j.name) {
                Text("Judge: ${j.name}, round 3", style = Mono11Dim)
                Spacer(Modifier.height(2.dp))
                Text(
                    j.error ?: "picked ${j.candidates.getOrNull(j.pick)?.answer ?: "?"}: ${j.reason}",
                    style = Body13,
                )
                Text("${j.tokens} tok  ${secs(j.ms)} s", style = Mono11Dim, modifier = Modifier.padding(top = 2.dp))
            }
        }

        r.finalPick?.let { f -> FinalCard(r, f) }

        r.matrix?.let { m -> MatrixCard(r, m) }

        Column {
            MetricRow("Rounds", r.totals.rounds.toString())
            MetricRow("Tokens", r.totals.tokens.toString())
            MetricRow("Wall clock", secs(elapsed), "s")
            r.baseline?.takeIf { it.answer != null }?.let { b ->
                MetricRow("Baseline alone", secs(b.wallMs), "s" + (b.correct?.let { "  (${knownWord(it)}: ${b.answer})" } ?: ""))
            }
            r.finalNoTool?.let { f -> MetricRow("Choir without tool", f.answer ?: "no answer", f.method.name.lowercase()) }
        }
    }
}

@Composable
private fun LaneCard(lane: LaneTrace) {
    val tokens = (lane.r1?.tokens ?: 0) + (lane.r2?.tokens ?: 0)
    val wall = lane.latest?.wallMs
    val failed = lane.status == LaneStatus.TIMED_OUT || lane.status == LaneStatus.ERROR
    val streaming = lane.status == LaneStatus.SOLVING || lane.status == LaneStatus.REVISING || lane.status == LaneStatus.JUDGING
    Lane(
        name = lane.name,
        role = roleLabel(lane.role),
        trailing = {
            if (failed) Stamp(if (lane.status == LaneStatus.TIMED_OUT) "Timed out" else "Error", caution = true, small = true)
            else {
                Text("$tokens tok", style = Mono11Dim)
                Spacer(Modifier.width(8.dp))
                Text(if (wall != null) "${secs(wall)} s" else "...", style = Mono11Dim)
            }
        },
    ) {
        val word = when (lane.status) {
            LaneStatus.WAITING -> "waiting"
            LaneStatus.SOLVING -> "solving"
            LaneStatus.REVISING -> "comparing answers"
            LaneStatus.JUDGING -> "judging"
            else -> ""
        }
        if (word.isNotEmpty()) Text(word, style = Mono11Dim, modifier = Modifier.padding(bottom = 4.dp))
        val steps = (lane.r2 ?: lane.r1)?.keySteps.orEmpty().joinToString("\n")
        when {
            steps.isNotEmpty() || streaming -> StreamingText(steps, streaming, style = Body13)
            lane.status == LaneStatus.WAITING -> { Skeleton(14.dp); Spacer(Modifier.height(6.dp)); Skeleton(14.dp) }
        }
        lane.error?.let { Text(it, style = Mono11, modifier = Modifier.padding(top = 4.dp)) }
        lane.r1?.let { AnswerRow(if (lane.r2 != null) "R1 answer" else "Proposal", it) }
        lane.r2?.let { AnswerRow("R2 revised", it) }
        if (lane.r1 == null && !failed) Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) { Skeleton(26.dp, Modifier.width(90.dp)) }
    }
}

@Composable
private fun AnswerRow(label: String, r: Reply) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$label:", style = Mono11Dim, modifier = Modifier.weight(1f))
        val w = checkWord(r)
        if (w.isNotEmpty()) Text(w, style = Mono11, modifier = Modifier.padding(end = 8.dp))
        Text("conf %.2f".format(r.confidence), style = Mono11Dim, modifier = Modifier.padding(end = 10.dp))
        AnswerChip(r.answer)
    }
}

@Composable
private fun FinalCard(r: RunTrace, f: FinalTrace) {
    val chosen = r.lanes.firstOrNull { l -> l.latest != null && f.answer != null && Normalizer.same(r.kind, l.latest!!.answer, f.answer) }?.latest
    val detail = chosen?.checkDetail?.ifBlank { null } ?: "nothing to check"
    val (stampText, caution) = finalStamp(f)
    SwarmCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Tool check: $detail", style = SwarmType.mono.copy(fontSize = 12.sp, lineHeight = 15.sp), modifier = Modifier.weight(1f))
            Stamp(stampText, caution = caution)
        }
        Hairline(Modifier.padding(vertical = 8.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (f.answer != null) InkCircle { Text(f.answer, style = SwarmType.monoHero.copy(fontSize = 34.sp, lineHeight = 38.sp)) }
            else Text("no answer", style = SwarmType.monoHero.copy(fontSize = 24.sp, lineHeight = 28.sp, color = SwarmColors.graphite))
        }
        Text(f.label, style = Mono11Dim, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        if (r.knownAnswer != null && r.choirToolCorrect != null) {
            Text("known answer ${r.knownAnswer}: choir ${knownWord(r.choirToolCorrect)}", style = Mono11, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
        }
    }
}

@Composable
private fun MatrixCard(r: RunTrace, m: Matrix) {
    val n = m.names.size
    val best = m.same.maxOfOrNull { row -> row.count { it } } ?: 0
    SwarmCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Agreement matrix", style = Mono11Dim, modifier = Modifier.weight(1f))
            Text(if (best == n) "Unanimous $n/$n" else "$best of $n agree", style = Mono11)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(34.dp))
            m.names.forEach { Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) { PhoneToken(it, 24.dp) } }
        }
        m.names.forEachIndexed { i, name ->
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(34.dp)) { PhoneToken(name, 24.dp) }
                m.same[i].forEach { same -> Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) { MatrixCell(same) } }
            }
        }
    }
}

@Composable
private fun MatrixCell(agree: Boolean) {
    Canvas(Modifier.size(26.dp).border(1.5.dp, SwarmColors.ink, RoundedCornerShape(4.dp)).then(if (agree) Modifier else Modifier.hatched(0.35f))) {
        val s = size.width
        val stroke = inkStroke(2.dp.toPx())
        if (agree) {
            val p = Path().apply { moveTo(s * 0.24f, s * 0.54f); lineTo(s * 0.43f, s * 0.72f); lineTo(s * 0.77f, s * 0.28f) }
            drawPath(p, SwarmColors.ink, style = stroke)
        } else {
            val p = Path().apply { moveTo(s * 0.3f, s * 0.3f); lineTo(s * 0.7f, s * 0.7f); moveTo(s * 0.7f, s * 0.3f); lineTo(s * 0.3f, s * 0.7f) }
            drawPath(p, SwarmColors.ink, style = stroke)
        }
    }
}

// ---------------------------------------------------------------------------------------------------
// scoreboard
// ---------------------------------------------------------------------------------------------------

@Composable
private fun ScoreboardSection(e: EvalProgress) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (e.running) "Running ${e.done} of ${e.total}" else "${e.total} problems", style = SwarmType.display, modifier = Modifier.weight(1f))
            if (e.fake) Stamp("Fake", caution = true)
        }
        if (e.running) {
            InkProgress(if (e.total == 0) 0f else e.done.toFloat() / e.total)
            e.current?.let { Text("now on problem $it", style = Mono11Dim) }
        }
        e.error?.let { Text(it, style = SwarmType.label) }
        val s = e.summary
        if (s != null && s.n > 0) {
            SwarmCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ScoreBar("One phone alone", s.baselineCorrect, s.n, s.baselineMeanMs)
                    if (s.samplesN > 0) ScoreBar("One phone, three samples", s.samplesCorrect, s.samplesN, s.samplesMeanMs)
                    ScoreBar("Choir", s.choirCorrect, s.n, s.choirMeanMs)
                    ScoreBar("Choir + tool check", s.choirToolCorrect, s.n, s.choirMeanMs, bold = true)
                    Note("n = ${s.n}, same token caps", Modifier.align(Alignment.End), rotation = -2f)
                }
            }
            Column {
                MetricRow("Verified by the problem's test", s.verifiedByTest.toString())
                MetricRow("Consistent with the models' own steps", s.consistent.toString())
                MetricRow("Abstained", s.abstained.toString())
                MetricRow("Mean rounds", "%.1f".format(s.meanRounds))
            }
            Text(
                if (e.useProblemCheck) "Tool check used the problems' own tests, so choir + tool check is scored with an answer key."
                else "Tool check used only the models' own written steps.",
                style = Mono11Dim,
            )
            ScoreTable(e.rows, withSamples = s.samplesN > 0)
        }
        e.lastFile?.let { Text("Saved: $it", style = Mono11Dim) }
    }
}

@Composable
private fun ScoreBar(label: String, k: Int, n: Int, meanMs: Double, bold: Boolean = false) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = SwarmType.body.copy(fontWeight = if (bold) FontWeight.W700 else FontWeight.W400), modifier = Modifier.weight(1f))
            Text("$k of $n", style = SwarmType.mono.copy(fontWeight = FontWeight.W700))
        }
        Spacer(Modifier.height(4.dp))
        // solid Ink, no track behind it; the bold row gets a hand-drawn underline
        Box(
            Modifier.fillMaxWidth().height(14.dp).drawBehind {
                val w = size.width * (if (n == 0) 0f else k.toFloat() / n)
                drawRect(SwarmColors.ink, size = androidx.compose.ui.geometry.Size(w, size.height))
                if (bold) {
                    val path = underlinePath(0f, size.width, size.height + 4.dp.toPx(), 2.dp.toPx(), seed = 21)
                    drawPath(path, SwarmColors.ink, style = Stroke(2.2.dp.toPx()))
                }
            },
        )
        Spacer(Modifier.height(if (bold) 10.dp else 4.dp))
        Row {
            Text(pct(k, n), style = Mono11Dim, modifier = Modifier.weight(1f))
            Text("mean ${secs(meanMs.toLong())} s", style = Mono11Dim)
        }
    }
}

@Composable
private fun ScoreTable(rows: List<EvalRow>, withSamples: Boolean) {
    val cols = buildList {
        add("#" to 0.9f); add("Kind" to 1.1f); add("Alone" to 1f)
        if (withSamples) add("3x" to 0.8f)
        add("Choir" to 1f); add("+Tool" to 1f); add("R" to 0.6f); add("Secs" to 1f)
    }
    Column(Modifier.fillMaxWidth().clip(SwarmShape).background(SwarmColors.sheet).border(1.5.dp, SwarmColors.ink, SwarmShape)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
            cols.forEach { (h, w) -> Text(h, style = SwarmType.label.copy(fontSize = 11.sp, color = SwarmColors.graphite), modifier = Modifier.weight(w)) }
        }
        rows.forEach { row ->
            Hairline()
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(row.problemId, style = Mono11.copy(fontWeight = FontWeight.W700), modifier = Modifier.weight(0.9f))
                Text(kindLabel(row.kind).take(5), style = SwarmType.label.copy(fontSize = 11.sp, color = SwarmColors.graphite), modifier = Modifier.weight(1.1f))
                Box(Modifier.weight(1f)) { MarkCell(row.baselineCorrect) }
                if (withSamples) Box(Modifier.weight(0.8f)) { if (row.samplesAnswered != null) MarkCell(row.samplesCorrect) }
                Box(Modifier.weight(1f)) { MarkCell(row.choirCorrect) }
                Box(Modifier.weight(1f)) { MarkCell(row.choirToolCorrect) }
                Text("${row.rounds}", style = Mono11, modifier = Modifier.weight(0.6f))
                Text(secs(row.choirMs), style = Mono11, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Right is a tick, wrong is a hatched box: no colour. */
@Composable
private fun MarkCell(right: Boolean) {
    Canvas(Modifier.size(18.dp).then(if (right) Modifier else Modifier.border(1.dp, SwarmColors.ink, RoundedCornerShape(3.dp)).drawBehind { drawHatch(alpha = 0.55f, spacing = 3.dp) })) {
        if (right) {
            val s = size.width
            val p = Path().apply { moveTo(s * 0.12f, s * 0.55f); lineTo(s * 0.4f, s * 0.82f); lineTo(s * 0.9f, s * 0.2f) }
            drawPath(p, SwarmColors.ink, style = inkStroke(2.dp.toPx()))
        }
    }
}
