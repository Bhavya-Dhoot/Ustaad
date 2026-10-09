package com.swarmos.app.choir

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swarmos.app.AppGraph
import com.swarmos.app.ui.LabelStyle
import com.swarmos.app.ui.MetricStyle
import com.swarmos.app.ui.SwarmColors
import com.swarmos.app.ui.accentFor
import kotlinx.coroutines.delay

/** The only place this screen touches the shared theme, so a restyle by the UI kit is a few lines here. */
private object C {
    val Bg = SwarmColors.Bg
    val Surface = SwarmColors.Surface
    val Line = SwarmColors.Line
    val Text = SwarmColors.Text
    val Dim = SwarmColors.Dim
    val Ok = SwarmColors.Ok
    val Warn = SwarmColors.Warn
    val Bad = SwarmColors.Bad
    fun accent(name: String): Color = accentFor(name)
}

private fun secs(ms: Long) = "%.1f s".format(ms / 1000.0)

private fun kindLabel(k: Kind) = when (k) {
    Kind.MATH -> "Math"
    Kind.CODE -> "Code"
    Kind.MCQ -> "Multiple choice"
    Kind.TEXT -> "Text"
}

private fun roleLabel(r: Role) = if (r == Role.ALT_SOLVER) "Alt solver" else "Solver"

private fun mark(b: Boolean?) = when (b) {
    true -> "right"
    false -> "wrong"
    null -> ""
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
    val kind = Kind.valueOf(kindName)

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(busy) {
        while (busy) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }

    val fakeModel = AppGraph.llm.modelName == "FAKE"

    LazyColumn(Modifier.fillMaxWidth().background(C.Bg), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ---- compose --------------------------------------------------------------------------
        item {
            Text("Agent Choir", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = C.Text)
            Text("Each phone answers alone, then they compare.", color = C.Dim)
            if (fakeModel) Text("FAKE model on this phone: every answer below is canned, not a real model.", color = C.Warn, fontSize = 12.sp)
        }
        item {
            Text("Problem", style = LabelStyle)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; selectedId = null },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 8,
                placeholder = { Text("Type a problem, or pick one below") },
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Kind.entries.forEach { k ->
                    FilterChip(selected = k == kind, onClick = { kindName = k.name }, label = { Text(kindLabel(k), fontSize = 12.sp, maxLines = 1) })
                }
            }
            if (kind == Kind.MCQ) Text("For multiple choice, put the options in the text as A) ... B) ...", style = LabelStyle)
        }
        item {
            Text("Phones in this run", fontWeight = FontWeight.SemiBold, color = C.Text)
            Spacer(Modifier.height(4.dp))
            Column(Modifier.fillMaxWidth().border(1.dp, C.Line)) {
                PhoneRow(self.name, "Solver (coordinator)", self.chatModel ?: AppGraph.llm.modelName)
                peers.filter { !it.away }.sortedBy { it.info.name }.forEachIndexed { i, p ->
                    PhoneRow(p.info.name, roleLabel(ChoirCoordinator.roleForPeer(i)), p.info.chatModel ?: "model unknown")
                }
                if (peers.none { !it.away }) Text("No peers online: the coordinator runs alone.", color = C.Dim, modifier = Modifier.padding(8.dp), fontSize = 12.sp)
            }
        }
        item {
            Text("Try one", fontWeight = FontWeight.SemiBold, color = C.Text)
            Text("${problems.size} built-in problems with known answers", style = LabelStyle)
        }
        items(problems, key = { it.id }) { p ->
            Row(
                Modifier.fillMaxWidth().border(1.dp, if (p.id == selectedId) C.Text else C.Line).clickable {
                    text = p.prompt(); kindName = p.kind.name; selectedId = p.id
                }.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(p.id, style = MetricStyle, color = C.Dim, modifier = Modifier.width(32.dp))
                Text(p.text.lines().first(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                Text(kindLabel(p.kind), style = LabelStyle)
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !busy && text.isNotBlank(),
                    onClick = {
                        val builtIn = problems.firstOrNull { it.id == selectedId && it.prompt() == text }
                        ChoirController.startRun(builtIn ?: ChoirController.customProblem(text, kind))
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Run") }
                OutlinedButton(enabled = !busy && problems.isNotEmpty(), onClick = { ChoirController.startEval() }, modifier = Modifier.weight(1f)) {
                    Text("Run all ${problems.size}")
                }
            }
        }

        // ---- live run / converged -------------------------------------------------------------
        run?.let { r -> runItems(r, now, busy) }

        // ---- scoreboard -------------------------------------------------------------------------
        if (eval.running || eval.rows.isNotEmpty() || eval.error != null) scoreboardItems(eval)
    }
}

@Composable
private fun PhoneRow(name: String, role: String, model: String) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(6.dp).height(32.dp).background(C.accent(name)))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontWeight = FontWeight.Bold, color = C.accent(name))
            Text(role, color = C.Dim, fontSize = 12.sp)
        }
        Text(model, style = MetricStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.runItems(r: RunTrace, now: Long, busy: Boolean) {
    item {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (r.phase == "DONE") "Result" else "Live run", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = C.Text, modifier = Modifier.weight(1f))
            listOf(1, 2, 3).forEach { n ->
                val state = when {
                    r.phase == "DONE" && n <= r.totals.rounds -> "done"
                    r.round == n && r.phase != "DONE" -> "now"
                    r.round > n -> "done"
                    else -> "off"
                }
                Text(
                    "R$n",
                    style = MetricStyle,
                    color = when (state) { "now" -> C.Bg; "done" -> C.Text; else -> C.Dim },
                    modifier = Modifier.padding(start = 6.dp)
                        .background(if (state == "now") C.Text else Color.Transparent)
                        .border(1.dp, if (state == "off") C.Line else C.Text)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        val elapsed = if (r.phase == "DONE") r.totals.wallMs else (now - r.startedMs).coerceAtLeast(0)
        Text(
            "${r.phase}  ${secs(elapsed)} of ${secs(r.deadlineMs)} deadline" + if (r.fake) "  FAKE model" else "",
            style = LabelStyle,
            color = if (r.fake) C.Warn else C.Dim,
        )
    }
    item {
        Column(Modifier.fillMaxWidth().border(1.dp, C.Line).padding(10.dp)) {
            Text("Problem ${r.problemId ?: ""}  ${kindLabel(r.kind)}", style = LabelStyle)
            Text(r.problem, color = C.Text)
        }
    }
    r.error?.let { e -> item { Text(e, color = C.Bad) } }
    items(r.lanes, key = { it.nodeId }) { lane -> LaneCard(lane) }

    item {
        val dis = r.agreement == "DISAGREE"
        val agree = r.agreement == "AGREE"
        if (dis || agree) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (dis) "DISAGREE" else "AGREE",
                    style = MetricStyle,
                    color = if (dis) C.Bad else C.Ok,
                    modifier = Modifier.border(2.dp, if (dis) C.Bad else C.Ok).padding(horizontal = 10.dp, vertical = 4.dp),
                )
                Spacer(Modifier.width(10.dp))
                val answers = r.lanes.mapNotNull { it.latest?.answer }.distinct()
                Text("answers: " + answers.joinToString("  |  "), style = LabelStyle)
            }
        }
    }
    r.baseline?.let { b ->
        item {
            Row(Modifier.fillMaxWidth().border(1.dp, C.Line).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Baseline: ${b.nodeName} alone", modifier = Modifier.weight(1f), color = C.Text)
                if (b.answer != null) {
                    Text(b.answer, style = MetricStyle, modifier = Modifier.padding(horizontal = 8.dp))
                    Text(secs(b.wallMs), style = MetricStyle, color = C.Dim)
                    if (b.correct != null) Text("  " + mark(b.correct), style = MetricStyle, color = if (b.correct) C.Ok else C.Bad)
                } else Text(b.status.name.lowercase(), color = C.Dim)
            }
        }
    }
    r.judge?.let { j ->
        item {
            Column(Modifier.fillMaxWidth().border(1.dp, C.Line).padding(10.dp)) {
                Text("Judge (${j.name}, R3)  ${j.tokens} tok  ${secs(j.ms)}", style = LabelStyle)
                Text(
                    j.error ?: ("picked ${j.candidates.getOrNull(j.pick)?.answer ?: "?"}: ${j.reason}"),
                    color = if (j.error != null) C.Bad else C.Text,
                    fontSize = 13.sp,
                )
            }
        }
    }
    r.finalPick?.let { f ->
        item {
            val chosenCheck = f.votes.firstOrNull { v -> f.answer != null && Normalizer.same(r.kind, v.answer, f.answer) }
            val lane = r.lanes.firstOrNull { l -> l.latest != null && f.answer != null && Normalizer.same(r.kind, l.latest!!.answer, f.answer) }
            Column(Modifier.fillMaxWidth().border(1.dp, C.Text).padding(12.dp)) {
                val tool = lane?.latest
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Tool check: " + (tool?.checkDetail?.ifBlank { null } ?: "nothing to check"),
                        modifier = Modifier.weight(1f), style = MetricStyle, fontSize = 12.sp,
                    )
                    val cr = chosenCheck?.check ?: CheckResult.NOT_CHECKABLE
                    Text(
                        cr.name.replace('_', ' '),
                        style = MetricStyle,
                        color = when (cr) { CheckResult.VERIFIED -> C.Ok; CheckResult.REFUTED -> C.Bad; else -> C.Warn },
                        modifier = Modifier.border(1.dp, C.Dim).padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("Choir answer (${f.method.name.lowercase()}: ${f.label})", style = LabelStyle)
                Text(f.answer ?: "no answer (abstain)", fontSize = 28.sp, fontWeight = FontWeight.Bold, style = MetricStyle, color = if (f.answer == null) C.Bad else C.Text)
                if (r.choirToolCorrect != null) Text("known answer ${r.knownAnswer}: choir " + mark(r.choirToolCorrect), style = LabelStyle, color = if (r.choirToolCorrect) C.Ok else C.Bad)
            }
        }
    }
    r.matrix?.let { m ->
        item {
            Column(Modifier.fillMaxWidth().border(1.dp, C.Line).padding(10.dp)) {
                Text("Agreement matrix (latest answers)", style = LabelStyle)
                Row {
                    Text("", modifier = Modifier.width(72.dp))
                    m.names.forEach { Text(it.takeLast(1), style = MetricStyle, color = C.accent(it), modifier = Modifier.width(32.dp)) }
                }
                m.names.forEachIndexed { i, n ->
                    Row {
                        Text(n, style = MetricStyle, color = C.accent(n), modifier = Modifier.width(72.dp), maxLines = 1)
                        m.same[i].forEach { s -> Text(if (s) "=" else "x", style = MetricStyle, color = if (s) C.Ok else C.Bad, modifier = Modifier.width(32.dp)) }
                    }
                }
            }
        }
    }
    item {
        Column(Modifier.fillMaxWidth()) {
            Stat("Rounds", r.totals.rounds.toString())
            Stat("Tokens", r.totals.tokens.toString())
            Stat("Wall clock", secs(if (r.phase == "DONE") r.totals.wallMs else (now - r.startedMs).coerceAtLeast(0)))
            r.baseline?.let { b ->
                if (b.answer != null) Stat("Baseline alone", secs(b.wallMs) + "  " + b.tokens + " tok" + (b.correct?.let { "  (${mark(it)}: ${b.answer})" } ?: ""))
            }
            r.finalNoTool?.let { f -> Stat("Choir without tool", (f.answer ?: "abstain") + "  " + f.method.name.lowercase()) }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = C.Dim, modifier = Modifier.weight(1f))
        Text(value, style = MetricStyle)
    }
}

@Composable
private fun LaneCard(lane: LaneTrace) {
    val accent = C.accent(lane.name)
    val tokens = (lane.r1?.tokens ?: 0) + (lane.r2?.tokens ?: 0)
    val wall = lane.latest?.wallMs
    Row(Modifier.fillMaxWidth().border(1.dp, C.Line)) {
        Box(Modifier.width(6.dp).height(IntrinsicHeightDp).background(accent))
        Column(Modifier.weight(1f)) {
            Row(Modifier.fillMaxWidth().background(accent.copy(alpha = 0.15f)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(lane.name, fontWeight = FontWeight.Bold, color = accent)
                Spacer(Modifier.width(8.dp))
                Text(roleLabel(lane.role), color = C.Dim, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("$tokens tok", style = MetricStyle, color = C.Dim)
                Spacer(Modifier.width(8.dp))
                Text(if (wall != null) secs(wall) else "...", style = MetricStyle, color = C.Dim)
            }
            Column(Modifier.padding(8.dp)) {
                val statusColor = when (lane.status) {
                    LaneStatus.TIMED_OUT, LaneStatus.ERROR -> C.Bad
                    else -> C.Dim
                }
                Text(
                    lane.status.name.replace('_', ' ').lowercase() + (if (lane.fake) "  FAKE" else "") + (if (lane.model.isNotBlank()) "  ${lane.model}" else ""),
                    style = LabelStyle, color = if (lane.fake) C.Warn else statusColor,
                )
                lane.error?.let { Text(it, color = C.Bad, fontSize = 12.sp) }
                val steps = (lane.r2 ?: lane.r1)?.keySteps.orEmpty()
                steps.forEach { Text(it, fontSize = 13.sp, color = C.Text) }
                lane.r1?.let { AnswerRow(if (lane.r2 != null) "R1 answer" else "Proposal", it) }
                lane.r2?.let { AnswerRow("R2 revised", it) }
            }
        }
    }
}

private val IntrinsicHeightDp = 96.dp

@Composable
private fun AnswerRow(label: String, r: Reply) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = LabelStyle, modifier = Modifier.weight(1f))
        Text("conf %.2f".format(r.confidence), style = MetricStyle, color = C.Dim, modifier = Modifier.padding(end = 8.dp))
        Text(
            when (r.check) { CheckResult.VERIFIED -> "ok"; CheckResult.REFUTED -> "refuted"; else -> "" },
            style = LabelStyle, color = if (r.check == CheckResult.REFUTED) C.Bad else C.Ok, modifier = Modifier.padding(end = 8.dp),
        )
        Text(r.answer, style = MetricStyle, modifier = Modifier.border(1.dp, C.Text).padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.scoreboardItems(e: EvalProgress) {
    item {
        val n = e.total
        Text(if (e.running) "Running ${e.done} of $n" + (e.current?.let { " ($it)" } ?: "") else "$n problems", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = C.Text)
        if (e.fake) Text("FAKE model: these accuracies are not from a real model.", color = C.Warn, fontSize = 12.sp)
        e.error?.let { Text(it, color = C.Bad) }
    }
    e.summary?.takeIf { it.n > 0 }?.let { s ->
        item {
            Column(Modifier.fillMaxWidth().border(1.dp, C.Line).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Bar("One phone alone", s.baselineCorrect, s.n)
                Bar("Choir", s.choirCorrect, s.n)
                Bar("Choir + tool check", s.choirToolCorrect, s.n, bold = true)
                Text("n = ${s.n}, same token caps; baseline = the coordinator's own R1 answer. Mean ${"%.1f".format(s.baselineMeanMs / 1000)} s alone, ${"%.1f".format(s.choirMeanMs / 1000)} s choir, ${"%.1f".format(s.meanRounds)} rounds.", style = LabelStyle)
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                listOf("#" to 0.9f, "Kind" to 1.2f, "Alone" to 1f, "Choir" to 1f, "+Tool" to 1f, "Rounds" to 1f, "Seconds" to 1.2f).forEach { (h, w) ->
                    Text(h, style = LabelStyle, modifier = Modifier.weight(w))
                }
            }
        }
    }
    items(e.rows, key = { it.problemId }) { row ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text(row.problemId, style = MetricStyle, modifier = Modifier.weight(0.9f))
            Text(row.kind.name.lowercase(), fontSize = 12.sp, color = C.Dim, modifier = Modifier.weight(1.2f))
            Text(if (row.baselineCorrect) "yes" else "no", style = MetricStyle, color = if (row.baselineCorrect) C.Ok else C.Bad, modifier = Modifier.weight(1f))
            Text(if (row.choirCorrect) "yes" else "no", style = MetricStyle, color = if (row.choirCorrect) C.Ok else C.Bad, modifier = Modifier.weight(1f))
            Text(if (row.choirToolCorrect) "yes" else "no", style = MetricStyle, color = if (row.choirToolCorrect) C.Ok else C.Bad, modifier = Modifier.weight(1f))
            Text("${row.rounds} R", style = MetricStyle, modifier = Modifier.weight(1f))
            Text("%.1f s".format(row.choirMs / 1000.0), style = MetricStyle, modifier = Modifier.weight(1.2f))
        }
    }
    e.lastFile?.let { f -> item { Text("Saved: $f", style = LabelStyle) } }
}

@Composable
private fun Bar(label: String, k: Int, n: Int, bold: Boolean = false) {
    Column {
        Row {
            Text(label, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, color = C.Text, modifier = Modifier.weight(1f))
            Text("$k of $n", style = MetricStyle)
        }
        Box(Modifier.fillMaxWidth().height(10.dp).background(C.Line)) {
            Box(Modifier.fillMaxWidth(if (n == 0) 0f else k.toFloat() / n).height(10.dp).background(C.Text))
        }
    }
}
