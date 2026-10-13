package com.swarmos.app.memcloud

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swarmos.app.AppGraph
import com.swarmos.app.core.HealthMonitor
import com.swarmos.app.llm.ModelStore
import com.swarmos.app.ui.EmptyHint
import com.swarmos.app.ui.ErrorNote
import com.swarmos.app.ui.Hairline
import com.swarmos.app.ui.HeroMetric
import com.swarmos.app.ui.InkSwitch
import com.swarmos.app.ui.InkProgress
import com.swarmos.app.ui.LayerStrip
import com.swarmos.app.ui.Lane
import com.swarmos.app.ui.MetricRow
import com.swarmos.app.ui.Note
import com.swarmos.app.ui.PrimaryButton
import com.swarmos.app.ui.SecondaryButton
import com.swarmos.app.ui.SectionTitle
import com.swarmos.app.ui.Stamp
import com.swarmos.app.ui.StreamingText
import com.swarmos.app.ui.SwarmCard
import com.swarmos.app.ui.SwarmColors
import com.swarmos.app.ui.SwarmShape
import com.swarmos.app.ui.SwarmTextField
import com.swarmos.app.ui.SwarmType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun gb(bytes: Long) = "%.1f".format(bytes / 1073741824.0)
private fun mbText(mb: Long?) = if (mb == null) "-" else if (mb >= 1024) "%.1f GB".format(mb / 1024.0) else "$mb MB"
private fun tok(v: Double?) = if (v == null) "-" else "%.1f".format(v)

/** The model's file name, with the GGUF name under it only when it adds something. */
@Composable
private fun ModelTitle(st: MemCloudState) {
    val file = st.file ?: st.model ?: ""
    Text(file, style = SwarmType.mono)
    MemCloudLogic.secondaryName(file, st.model)?.let { Text(it, style = SwarmType.label.copy(color = SwarmColors.graphite)) }
}

private sealed interface PlanResult {
    data class Ok(val plan: SplitPlan) : PlanResult
    data class Error(val message: String) : PlanResult
}

/** The Memory tab: pick a model, see where its layers will live, load it across phones, chat with it, and see what happens when a phone drops. */
@Composable
fun MemCloudScreen() {
    val st by MemCloud.state.collectAsStateWithLifecycle()
    val peers by AppGraph.registry.peers.collectAsStateWithLifecycle()
    val self by AppGraph.registry.self.collectAsStateWithLifecycle()
    val tick by ModelStore.tick.collectAsStateWithLifecycle()
    val lending by RpcWorker.lending.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { MemCloud.onAppStart() } // cleans up after a killed session, off the main thread
    var showExpert by rememberSaveable { mutableStateOf(false) }
    var fitsAlone by remember { mutableStateOf<Boolean?>(null) } // what the planner says about the selected model on this phone

    // peers' model servers change what the plan can count on; ask while this screen is open
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { MemCloud.refreshPeers() }
            ModelStore.refresh()
            delay(5_000)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // rpc-server has no authentication: while it listens for another phone, say so in plain words
        lending?.let { l ->
            SwarmCard {
                Text(l.sentence, style = SwarmType.body)
                Text(l.leaseSentence, style = SwarmType.label.copy(color = SwarmColors.graphite))
                Spacer(Modifier.height(8.dp))
                SecondaryButton("Stop lending", { RpcWorker.stop("stopped on this phone") }, Modifier.fillMaxWidth())
            }
        }
        if (showExpert) {
            ExpertMeshView(st, onBack = { showExpert = false })
            return@Column
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("MemoryCloud", style = SwarmType.display)
                Text(MemCloudLogic.subtitle(if (st.phase == Phase.IDLE || st.phase == Phase.FAILED || st.phase == Phase.BASELINE) fitsAlone else null), style = SwarmType.body.copy(color = SwarmColors.graphite))
            }
            when (st.phase) {
                Phase.READY -> Stamp("Split ready")
                Phase.DEGRADED -> Stamp("Degraded", caution = true)
                Phase.FAILED -> Stamp("Failed", caution = true)
                else -> {}
            }
        }
        when (st.phase) {
            Phase.IDLE, Phase.FAILED, Phase.BASELINE -> PlanView(st, peers.filter { !it.away }.sortedBy { it.info.name }.map { it.info.name }, self.name, tick, onExpert = { showExpert = true }, onFits = { fitsAlone = it })
            Phase.PLANNING, Phase.STARTING_PEERS, Phase.LOADING -> LoadingView(st)
            Phase.READY -> RunningView(st, onExpert = { showExpert = true })
            Phase.DEGRADED -> DroppedView(st, peers.filter { !it.away }.map { it.info.nodeId }.toSet())
        }
        CacheCard(lending != null)
    }
}

/** The tensor cache this phone keeps for lending: its size against the budget, and the button that clears it (refused while lending, as the route is). */
@Composable
private fun CacheCard(lending: Boolean) {
    var report by remember { mutableStateOf<RpcCacheKeeper.Report?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(lending) {
        while (true) {
            report = withContext(Dispatchers.IO) { runCatching { RpcWorker.cacheReport() }.getOrNull() }
            delay(5_000)
        }
    }
    report?.let { r ->
        SwarmCard {
            Text("Lending cache", style = SwarmType.body)
            Text("${gb(r.bytes)} GB of ${gb(r.budgetBytes)} GB used, ${r.files} files. When it is over the budget the model used longest ago goes first.", style = SwarmType.label.copy(color = SwarmColors.graphite))
            message?.let { Note(it) }
            Spacer(Modifier.height(8.dp))
            SecondaryButton("Clear cache", {
                scope.launch { message = withContext(Dispatchers.IO) { RpcCache.clearMessage(RpcWorker.clearCache()) } }
            }, Modifier.fillMaxWidth())
        }
    }
}

// ---------- plan ----------

@Composable
private fun PlanView(st: MemCloudState, onlineNames: List<String>, selfName: String, tick: Int, onExpert: () -> Unit, onFits: (Boolean?) -> Unit) {
    val present = remember(tick) { ModelStore.present().filter { it.complete } }
    var file by rememberSaveable { mutableStateOf("") }
    val chosen = present.firstOrNull { it.file == file } ?: present.maxByOrNull { it.bytes }
    var byRam by rememberSaveable { mutableStateOf(false) }
    var excluded by rememberSaveable { mutableStateOf("") } // names switched off, comma separated
    var counts by remember { mutableStateOf<List<Int>?>(null) }
    var force by remember { mutableStateOf(false) } // start although the plan does not fit a phone's free memory
    val selected = onlineNames.filter { n -> n !in excluded.split(',') }
    val key = listOf(chosen?.file, selected, byRam, onlineNames)

    val result by produceState<PlanResult?>(null, key, counts) {
        value = if (chosen == null) null else withContext(Dispatchers.IO) {
            try {
                PlanResult.Ok(MemCloud.previewPlan(chosen.file, selected, if (byRam) SplitMode.RAM else SplitMode.EQUAL, counts?.takeIf { c -> c.size == selected.size + 1 }, emptyMeansNone = true))
            } catch (e: Exception) {
                PlanResult.Error(e.message ?: e.toString())
            }
        }
    }
    val plan = (result as? PlanResult.Ok)?.plan
    val alone = plan?.let { MemCloudLogic.fitsOnOnePhone(it.info.fileBytes, it.driver.device.ramAvailMb) }
    LaunchedEffect(alone) { onFits(alone) }

    if (present.isEmpty()) {
        ErrorNote("No model file on this phone yet. Put a .gguf in the models folder or download one on the Models tab.")
    } else {
        SectionTitle("Model")
        for (m in present) {
            val sel = m.file == chosen?.file
            Row(
                Modifier.fillMaxWidth().clickable { file = m.file; counts = null; force = false }
                    .background(if (sel) SwarmColors.sheet else SwarmColors.whiteboard, SwarmShape)
                    .border(if (sel) 2.dp else 1.dp, if (sel) SwarmColors.ink else SwarmColors.smudge, SwarmShape).padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(m.file, Modifier.weight(1f), style = SwarmType.mono)
                Text("${gb(m.bytes)} GB", style = SwarmType.mono.copy(color = SwarmColors.graphite))
            }
        }
    }

    if (plan != null) {
        val info = plan.info
        val ownAvail = plan.driver.device.ramAvailMb
        SwarmCard {
            Column(Modifier.fillMaxWidth()) {
                Text(info.file, style = SwarmType.mono)
                Text(listOfNotNull(MemCloudLogic.secondaryName(info.file, info.name), info.architecture).joinToString("  "), style = SwarmType.label.copy(color = SwarmColors.graphite))
                if (!MemCloudLogic.fitsOnOnePhone(info.fileBytes, ownAvail)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Stamp("Too big for one phone", caution = true) }
            }
            Spacer(Modifier.height(8.dp))
            MetricRow("File size", gb(info.fileBytes), "GB")
            MetricRow("Layers", info.blockCount.toString())
            if (info.isMoe) MetricRow("Experts per layer, used per token", "${info.expertCount} / ${info.expertUsedCount}")
            MetricRow("Free on this phone", gb(ownAvail * 1048576), "GB")
        }
        if (MemCloudLogic.fitsOnOnePhone(info.fileBytes, ownAvail)) ErrorNote(MemCloudLogic.SPLIT_COSTS_SPEED)
        SectionTitle("Where the layers go")
        // the strip shows the plan in the phones' colours; what a screen reader hears says nothing is loaded yet
        Box(Modifier.clearAndSetSemantics { contentDescription = MemCloudLogic.planDescription(shareViewsOf(plan)) }) {
            LayerStrip(plan.assignments(), List(plan.nLayers) { true })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (s in plan.shares) Text("${s.device.name}: ${s.range}", style = SwarmType.mono.copy(fontSize = 11.sp, color = SwarmColors.graphite))
        }
        plan.shares.forEachIndexed { i, s ->
            val fit = plan.fits()[i]
            Lane(s.device.name, MemCloudLogic.laneRole(s.device.isDriver), trailing = {
                Text("layers ${s.range}", style = SwarmType.mono.copy(fontSize = 12.sp))
            }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        MetricRow("Weights", gb(s.weightBytes), "GB")
                        MetricRow("Free memory", gb(fit.haveMb * 1048576), "GB")
                    }
                    Spacer(Modifier.width(12.dp))
                    Stepper(s.layerCount, onMinus = { counts = SplitPlanner.shift(plan.counts(), i, -1) }, onPlus = { counts = SplitPlanner.shift(plan.counts(), i, +1) })
                }
                if (!fit.ok) ErrorNote("May not fit: the planning estimate is ${gb(fit.needMb * 1048576)} GB (weights plus a ${SplitPlanner.OVERHEAD_MB / 1024.0} GB allowance), this phone can give ${gb(fit.haveMb * 1048576)} GB.")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(if (byRam) "Split by free memory" else "Split equally", { byRam = !byRam; counts = null }, Modifier.weight(1f))
            if (info.isMoe) SecondaryButton("ExpertMesh", onExpert, Modifier.weight(1f))
        }
    }
    (result as? PlanResult.Error)?.let { ErrorNote(it.message) }

    if (onlineNames.isNotEmpty()) {
        SectionTitle("Phones to use")
        onlineNames.forEach { n ->
            val on = n in selected
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (on) "$n, lending" else "$n, not used", style = SwarmType.body)
                InkSwitch(on, { want ->
                    val ex = excluded.split(',').filter { it.isNotBlank() }.toMutableList()
                    if (want) ex.remove(n) else ex += n
                    excluded = ex.joinToString(","); counts = null
                })
            }
        }
    } else {
        EmptyHint("No other phone is online. Join the swarm on the Swarm tab.")
    }

    st.failReason?.let { ErrorNote(it) }
    for (n in st.notices) ErrorNote(n)
    val tight = plan != null && !plan.fitsAll()
    if (tight && (st.phase == Phase.IDLE || st.phase == Phase.FAILED)) {
        Note("Memory is tight on at least one phone, so the load is refused")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Try anyway", style = SwarmType.body)
            InkSwitch(force, { force = it })
        }
    }
    val (loadText, canPick) = MemCloudLogic.loadButton(selected.size)
    PrimaryButton(
        loadText,
        { if (chosen != null) MemCloud.start(StartRequest(chosen.file, selected, if (byRam) "ram" else "equal", counts?.takeIf { c -> c.size == selected.size + 1 }, force = force && tight)) },
        Modifier.fillMaxWidth(),
        enabled = chosen != null && canPick && plan != null,
    )
    if (st.pendingRestore.isNotEmpty()) {
        Text("Stopped to make room: ${st.pendingRestore.joinToString("; ")}", style = SwarmType.label.copy(color = SwarmColors.graphite))
        SecondaryButton("Restore phones", { MemCloud.stop() }, Modifier.fillMaxWidth())
    }
    BaselinePanel(st, file = chosen?.file, enabled = chosen != null && st.phase != Phase.BASELINE)
    if (st.steps.isNotEmpty()) StepsList(st)
}

private fun shareViewsOf(plan: SplitPlan): List<ShareView> = plan.shares.map { s ->
    ShareView(s.device.nodeId, s.device.name, s.device.isDriver, s.firstLayer, s.layerCount, s.range, s.weightBytes / (1024 * 1024), 0, 0, true)
}

@Composable
private fun Stepper(value: Int, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        Modifier.border(1.5.dp, SwarmColors.ink, SwarmShape).background(SwarmColors.sheet, SwarmShape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("-", Modifier.clickable(onClick = onMinus).padding(horizontal = 14.dp, vertical = 10.dp), style = SwarmType.mono.copy(fontSize = 18.sp))
        Text(value.toString(), Modifier.width(34.dp), style = SwarmType.mono.copy(fontSize = 15.sp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text("+", Modifier.clickable(onClick = onPlus).padding(horizontal = 14.dp, vertical = 10.dp), style = SwarmType.mono.copy(fontSize = 18.sp))
    }
}

// ---------- loading ----------

@Composable
private fun LoadingView(st: MemCloudState) {
    val n = st.layers ?: 0
    SwarmCard {
        ModelTitle(st)
        Text(
            when (st.phase) {
                Phase.PLANNING -> "Planning"
                Phase.STARTING_PEERS -> "Starting the workers"
                else -> "Loading weights"
            },
            style = SwarmType.label.copy(color = SwarmColors.graphite),
        )
        if (st.fileBytes != null) MetricRow("File size", gb(st.fileBytes), "GB")
        if (st.phase == Phase.LOADING) {
            val pct = MemCloudLogic.overallPercent(st.shares, st.phones)
            MetricRow("Received by the workers", "$pct", "%")
            ElapsedRow(st.loadStartedAtMs)
        }
    }
    if (n > 0 && st.shares.isNotEmpty()) {
        val assignments = remember(st.shares, n) { shareAssignments(st.shares, n) }
        LayerStrip(assignments, MemCloudLogic.loadedLayers(st.shares, st.phones, ready = false, nLayers = n))
        Note("first load sends the weights, later loads come from the cache")
    }
    PhoneLanes(st, showProgress = true)
    StepsList(st)
    SecondaryButton("Cancel", { MemCloud.stop() }, Modifier.fillMaxWidth())
}

@Composable
private fun ElapsedRow(startMs: Long) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startMs) { while (true) { now = System.currentTimeMillis(); delay(500) } }
    MetricRow("Elapsed", "%.0f".format((now - startMs).coerceAtLeast(0) / 1000.0), "s")
}

private fun shareAssignments(shares: List<ShareView>, n: Int): List<String?> {
    val out = arrayOfNulls<String>(n)
    for (s in shares) for (i in 0 until s.layers) if (s.firstLayer + i < n) out[s.firstLayer + i] = s.name
    return out.toList()
}

@Composable
private fun PhoneLanes(st: MemCloudState, showProgress: Boolean) {
    for (p in st.phones) {
        val share = st.shares.firstOrNull { it.nodeId == p.nodeId }
        Lane(p.name, MemCloudLogic.laneRole(p.isDriver), trailing = {
            Text("layers ${share?.range ?: "-"}", style = SwarmType.mono.copy(fontSize = 12.sp))
        }) {
            Text(p.step, style = SwarmType.body.copy(fontSize = 14.sp, lineHeight = 20.sp))
            if (p.freed.isNotEmpty()) Text("Stopped here: ${p.freed.joinToString("; ")}", style = SwarmType.label.copy(color = SwarmColors.graphite))
            if (showProgress && !p.isDriver) {
                Spacer(Modifier.height(6.dp))
                InkProgress(p.loadFraction)
                MetricRow("Received", mbText(p.receivedMb), "of ${mbText(share?.weightMb)}")
                MetricRow("On-disk cache", mbText(p.cachedMb))
            }
            MetricRow("Memory in use", mbText(p.rssMb), "peak ${mbText(p.peakMb)}")
            p.thermalStatus?.let { MetricRow("Temperature", HealthMonitor.thermalWord(it), "status $it") }
            p.rttMs?.let { MetricRow("Heartbeat round trip (HTTP)", "%.1f".format(it), "ms") }
            p.tcpRttMs?.let { MetricRow("TCP round trip to rpc-server", "%.1f".format(it), "ms") }
        }
    }
}

@Composable
private fun StepsList(st: MemCloudState) {
    if (st.steps.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (s in st.steps.takeLast(8)) Text(s, style = SwarmType.mono.copy(fontSize = 11.sp, lineHeight = 15.sp, color = SwarmColors.graphite))
    }
}

// ---------- running ----------

@Composable
private fun RunningView(st: MemCloudState, onExpert: () -> Unit) {
    val g = st.generation
    var prompt by rememberSaveable { mutableStateOf("Explain how a heat pump works, in about 200 words.") }
    val n = st.layers ?: 0
    SwarmCard {
        ModelTitle(st)
        Spacer(Modifier.height(6.dp))
        HeroMetric(tok(g?.decodeTokPerSec), "tok/s", note = g?.ttftMs?.let { "first word after ${"%.1f".format(it / 1000.0)} s" })
        Spacer(Modifier.height(6.dp))
        st.loadMs?.let { MetricRow("Load time", "%.0f".format(it / 1000.0), "s") }
        g?.ttftMs?.let { MetricRow("Time to first token", "%.2f".format(it / 1000.0), "s") }
        for (p in st.phones.filter { !it.isDriver }) p.tcpRttMs?.let { MetricRow("TCP round trip to ${p.name}", "%.1f".format(it), "ms") }
        st.boundariesPerToken?.let { MetricRow("Phone boundaries per token", it.toString()) }
    }
    val alone = st.shares.firstOrNull()?.let { MemCloudLogic.fitsOnOnePhone(st.fileBytes ?: Long.MAX_VALUE, it.haveMb) } == true
    if (alone) {
        ErrorNote(MemCloudLogic.SPLIT_COSTS_SPEED)
        st.smallModel?.let { Text("Alone on this phone: ${tok(it.tokPerSec)} tok/s (${it.name}).", style = SwarmType.mono.copy(fontSize = 12.sp)) }
    }
    if (n > 0) LayerStrip(shareAssignments(st.shares, n), List(n) { true })
    SwarmTextField(prompt, { prompt = it }, "Ask the split model", singleLine = false)
    PrimaryButton("Ask", { MemCloud.ask(prompt, 200) }, Modifier.fillMaxWidth(), enabled = g?.running != true && prompt.isNotBlank())
    if (g != null) {
        SwarmCard {
            StreamingText(g.text.ifEmpty { "..." }, streaming = g.running)
            g.error?.let { Spacer(Modifier.height(6.dp)); ErrorNote(it) }
            if (!g.running && g.tokens > 0) Text(MemCloudLogic.plural(g.tokens, "token"), style = SwarmType.mono.copy(fontSize = 11.sp, color = SwarmColors.graphite))
        }
    }
    PhoneLanes(st, showProgress = false)
    BaselinePanel(st, file = st.file, enabled = true)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (st.experts != null) SecondaryButton("ExpertMesh", onExpert, Modifier.weight(1f))
        SecondaryButton("Stop and restore phones", { MemCloud.stop() }, Modifier.weight(1f))
    }
    StepsList(st)
}

// ---------- dropped ----------

@Composable
private fun DroppedView(st: MemCloudState, onlineIds: Set<String>) {
    val n = st.layers ?: 0
    val lost = st.lostPhone
    SwarmCard {
        Text("${lost ?: "A phone"} stopped answering.", style = SwarmType.title)
        Text("Generation stopped and the model server was shut down. ${st.failReason ?: ""}", style = SwarmType.body.copy(color = SwarmColors.graphite))
    }
    for (n in st.notices) ErrorNote(n)
    if (n > 0) {
        val holders = shareAssignments(st.shares, n)
        LayerStrip(holders, holders.map { it != lost })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (s in st.shares) Text("${s.name}: ${s.range}${if (s.name == lost) " (lost)" else ""}", style = SwarmType.mono.copy(fontSize = 11.sp, color = SwarmColors.graphite))
        }
    }
    val g = st.generation
    if (g != null && g.text.isNotEmpty()) {
        SwarmCard {
            Text("Interrupted answer", style = SwarmType.label.copy(color = SwarmColors.graphite))
            Text(g.text, style = SwarmType.body)
            Text("${MemCloudLogic.plural(g.tokens, "token")} streamed before the phone dropped", style = SwarmType.mono.copy(fontSize = 11.sp, color = SwarmColors.graphite))
        }
    }
    // phones that are online right now (the lost one counts again as soon as its heartbeat is back)
    val remaining = onlineIds.size
    PrimaryButton("Re-plan with ${MemCloudLogic.plural(remaining + 1, "phone")}", { MemCloud.replan() }, Modifier.fillMaxWidth(), enabled = MemCloudLogic.canReplan(remaining))
    if (remaining == 0) Note("no other phone is online yet")
    SecondaryButton("Stop and restore phones", { MemCloud.stop() }, Modifier.fillMaxWidth())
    PhoneLanes(st, showProgress = false)
    StepsList(st)
}

// ---------- baseline ----------

@Composable
private fun BaselinePanel(st: MemCloudState, file: String?, enabled: Boolean) {
    val b = st.baseline
    val target = file ?: st.file
    SwarmCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("This phone alone", style = SwarmType.title)
            if (b != null && !b.running) Stamp(if (b.outcome == "OK") "Loaded" else b.outcome, caution = b.outcome != "OK", small = true)
        }
        Text("Same model, no other phone, no RPC.", style = SwarmType.label.copy(color = SwarmColors.graphite))
        // say what pressing the button does, so it never looks dead
        Text(
            if (target == null) "Pick a model above first."
            else "Loads $target on this phone alone and waits up to 120 s, then generates 32 tokens and reports what happened. This phone's chat and embedding servers stop meanwhile and come back afterwards.",
            style = SwarmType.label.copy(fontSize = 12.sp, lineHeight = 16.sp),
        )
        st.smallModel?.let { MetricRow("For scale: ${it.name}", "%.1f".format(it.tokPerSec), "tok/s") }
        if (b != null) {
            Spacer(Modifier.height(6.dp))
            Text(b.detail, style = SwarmType.body.copy(fontSize = 14.sp, lineHeight = 20.sp))
            MetricRow("Time", "%.0f".format(b.elapsedMs / 1000.0), "s")
            b.tokPerSec?.let { MetricRow("Decode", "%.2f".format(it), "tok/s") }
        }
        Spacer(Modifier.height(8.dp))
        SecondaryButton("Try on this phone alone", { MemCloud.baseline(120, target) }, Modifier.fillMaxWidth(), enabled = enabled && target != null && b?.running != true)
    }
}

// ---------- ExpertMesh ----------

@Composable
private fun ExpertMeshView(st: MemCloudState, onBack: () -> Unit) {
    val tick by ModelStore.tick.collectAsStateWithLifecycle()
    // the file in use, or the biggest one on the phone, read from its header
    val info by produceState<GgufInfo?>(null, st.file, tick) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val f = st.file ?: ModelStore.present().filter { it.complete }.maxByOrNull { it.bytes }?.file
                f?.let { MemCloud.header(it) }
            }.getOrNull()
        }
    }
    val live = st.shares
    Text("ExpertMesh", style = SwarmType.display)
    Text("Why experts are grouped by layer range, not scattered.", style = SwarmType.body.copy(color = SwarmColors.graphite))
    val m = info
    if (m == null) {
        ErrorNote("No model file to read. Put a mixture-of-experts .gguf on this phone.")
    } else if (!m.isMoe) {
        ErrorNote("${m.name} has no experts (dense model), so there is nothing to place by expert.")
    } else {
        val rtt = st.tcpRttMs
        val boundaries = st.boundariesPerToken
        SwarmCard {
            Text(m.file, style = SwarmType.mono)
            Spacer(Modifier.height(6.dp))
            MetricRow("Experts per layer", m.expertCount.toString())
            MetricRow("Experts used per token, per layer", m.expertUsedCount.toString())
            MetricRow("Layers with experts", m.moeLayers.toString())
        }
        if (live.isNotEmpty()) {
            SectionTitle("Layer ranges")
            for (s in live) {
                Lane(s.name, MemCloudLogic.laneRole(s.isDriver), trailing = { Text("layers ${s.range}", style = SwarmType.mono.copy(fontSize = 12.sp)) }) {
                    Text("${MemCloudLogic.plural(s.layers, "layer")} x ${m.expertCount} experts = ${ExpertMesh.expertsOnPhone(s.layers, m.expertCount)} experts, all on this phone.", style = SwarmType.body.copy(fontSize = 14.sp, lineHeight = 20.sp))
                }
            }
        } else {
            Note("load a split to see the layer ranges")
        }
        Hairline()
        if (rtt == null) {
            ErrorNote("No round trip measured yet: load a split with at least one other phone (it is a TCP connect to the rpc-server port).")
        } else {
            val remoteMs = ExpertMesh.remoteExpertMsPerToken(m.moeLayers, rtt)
            val ceiling = ExpertMesh.remoteExpertCeilingTokPerSec(m.moeLayers, rtt)
            MetricRow("Round trip, TCP connect to rpc-server", "%.1f".format(rtt), "ms")
            MetricRow("Estimate if experts were remote: ${MemCloudLogic.plural(m.moeLayers, "layer")} x %.1f ms".format(rtt), "%.0f".format(remoteMs), "ms per token")
            MetricRow("Estimated ceiling, network wait only", tok(ceiling), "tok/s")
            if (boundaries != null) {
                val rangeMs = ExpertMesh.layerRangeMsPerToken(boundaries, rtt)
                MetricRow("Estimate with layer ranges: $boundaries boundaries x %.1f ms".format(rtt), "%.0f".format(rangeMs), "ms per token")
            }
        }
        st.generation?.decodeTokPerSec?.let { MetricRow("Measured on this split", "%.1f".format(it), "tok/s") }
        Text(
            "A layer picks its experts only after the layer before it has finished, so an expert on another phone costs one network round trip in every layer of every token. " +
                "A whole range of layers on one phone crosses the network only where the range changes.",
            style = SwarmType.body.copy(fontSize = 14.sp, lineHeight = 20.sp),
        )
    }
    SecondaryButton("Back to MemoryCloud", onBack, Modifier.fillMaxWidth())
}
