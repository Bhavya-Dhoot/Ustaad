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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.withContext

private fun gb(bytes: Long) = "%.1f".format(bytes / 1073741824.0)
private fun mbText(mb: Long?) = if (mb == null) "-" else if (mb >= 1024) "%.1f GB".format(mb / 1024.0) else "$mb MB"
private fun tok(v: Double?) = if (v == null) "-" else "%.1f".format(v)

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
    var showExpert by rememberSaveable { mutableStateOf(false) }

    // peers' model servers change what the plan can count on; ask while this screen is open
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { MemCloud.refreshPeers() }
            ModelStore.refresh()
            delay(5_000)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (showExpert) {
            ExpertMeshView(st, onBack = { showExpert = false })
            return@Column
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("MemoryCloud", style = SwarmType.display)
                Text("A model too big for one phone, split by layers.", style = SwarmType.body.copy(color = SwarmColors.graphite))
            }
            when (st.phase) {
                Phase.READY -> Stamp("Split ready")
                Phase.DEGRADED -> Stamp("Degraded", caution = true)
                Phase.FAILED -> Stamp("Failed", caution = true)
                else -> {}
            }
        }
        when (st.phase) {
            Phase.IDLE, Phase.FAILED, Phase.BASELINE -> PlanView(st, peers.filter { !it.away }.sortedBy { it.info.name }.map { it.info.name }, self.name, tick, onExpert = { showExpert = true })
            Phase.PLANNING, Phase.STARTING_PEERS, Phase.LOADING -> LoadingView(st)
            Phase.READY -> RunningView(st, onExpert = { showExpert = true })
            Phase.DEGRADED -> DroppedView(st)
        }
    }
}

// ---------- plan ----------

@Composable
private fun PlanView(st: MemCloudState, onlineNames: List<String>, selfName: String, tick: Int, onExpert: () -> Unit) {
    val present = remember(tick) { ModelStore.present().filter { it.complete } }
    var file by rememberSaveable { mutableStateOf("") }
    val chosen = present.firstOrNull { it.file == file } ?: present.maxByOrNull { it.bytes }
    var byRam by rememberSaveable { mutableStateOf(false) }
    var excluded by rememberSaveable { mutableStateOf("") } // names switched off, comma separated
    var counts by remember { mutableStateOf<List<Int>?>(null) }
    val selected = onlineNames.filter { n -> n !in excluded.split(',') }
    val key = listOf(chosen?.file, selected, byRam, onlineNames)

    val result by produceState<PlanResult?>(null, key, counts) {
        value = if (chosen == null || selected.isEmpty()) null else withContext(Dispatchers.IO) {
            try {
                PlanResult.Ok(MemCloud.previewPlan(chosen.file, selected, if (byRam) SplitMode.RAM else SplitMode.EQUAL, counts?.takeIf { c -> c.size == selected.size + 1 }))
            } catch (e: Exception) {
                PlanResult.Error(e.message ?: e.toString())
            }
        }
    }
    val plan = (result as? PlanResult.Ok)?.plan

    if (present.isEmpty()) {
        ErrorNote("No model file on this phone yet. Put a .gguf in the models folder or download one on the Models tab.")
    } else {
        SectionTitle("Model")
        for (m in present) {
            val sel = m.file == chosen?.file
            Row(
                Modifier.fillMaxWidth().clickable { file = m.file; counts = null }
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(info.name, style = SwarmType.mono)
                    Text(info.architecture, style = SwarmType.label.copy(color = SwarmColors.graphite))
                }
                if (info.fileBytes / 1048576 > ownAvail) Stamp("Too big for one phone", caution = true)
            }
            Spacer(Modifier.height(8.dp))
            MetricRow("File size", gb(info.fileBytes), "GB")
            MetricRow("Layers", info.blockCount.toString())
            if (info.isMoe) MetricRow("Experts per layer, used per token", "${info.expertCount} / ${info.expertUsedCount}")
            MetricRow("Free on this phone", gb(ownAvail * 1048576), "GB")
        }
        SectionTitle("Where the layers go")
        LayerStrip(plan.assignments(), List(plan.nLayers) { true })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (s in plan.shares) Text("${s.device.name}: ${s.range}", style = SwarmType.mono.copy(fontSize = 11.sp, color = SwarmColors.graphite))
        }
        plan.shares.forEachIndexed { i, s ->
            val fit = plan.fits()[i]
            Lane(s.device.name, if (s.device.isDriver) "this phone, drives the model" else "worker", trailing = {
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
                if (!fit.ok) ErrorNote("May not fit: needs ${gb(fit.needMb * 1048576)} GB with buffers, has ${gb(fit.haveMb * 1048576)} GB.")
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            onlineNames.forEach { n ->
                val on = n in selected
                SecondaryButton((if (on) "Use " else "Skip ") + n, {
                    val ex = excluded.split(',').filter { it.isNotBlank() }.toMutableList()
                    if (on) ex += n else ex.remove(n)
                    excluded = ex.joinToString(","); counts = null
                }, Modifier.weight(1f))
            }
        }
    } else {
        EmptyHint("No other phone is online. Join the swarm on the Swarm tab.")
    }

    st.failReason?.let { ErrorNote(it) }
    plan?.let { p -> if (!p.fitsAll() && st.phase == Phase.IDLE) Note("Memory is tight on at least one phone") }
    PrimaryButton(
        "Load across ${selected.size + 1} phones",
        { if (chosen != null) MemCloud.start(StartRequest(chosen.file, selected, if (byRam) "ram" else "equal", counts?.takeIf { c -> c.size == selected.size + 1 })) },
        Modifier.fillMaxWidth(),
        enabled = chosen != null && selected.isNotEmpty() && plan != null,
    )
    if (st.pendingRestore.isNotEmpty()) {
        Text("Stopped to make room: ${st.pendingRestore.joinToString("; ")}", style = SwarmType.label.copy(color = SwarmColors.graphite))
        SecondaryButton("Restore phones", { MemCloud.stop() }, Modifier.fillMaxWidth())
    }
    BaselinePanel(st, enabled = chosen != null && st.phase != Phase.BASELINE)
    if (st.steps.isNotEmpty()) StepsList(st)
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
        Text(st.model ?: st.file ?: "", style = SwarmType.mono)
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
        Lane(p.name, if (p.isDriver) "this phone, drives the model" else "worker", trailing = {
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
            MetricRow("Memory in use", mbText(p.rssMb))
            p.thermalStatus?.let { MetricRow("Temperature", HealthMonitor.thermalWord(it), "status $it") }
            p.rttMs?.let { MetricRow("Round trip from this phone", "%.1f".format(it), "ms") }
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
        Text(st.model ?: "", style = SwarmType.mono)
        Spacer(Modifier.height(6.dp))
        HeroMetric(tok(g?.decodeTokPerSec), "tok/s", note = g?.ttftMs?.let { "first word after ${"%.1f".format(it / 1000.0)} s" })
        Spacer(Modifier.height(6.dp))
        st.loadMs?.let { MetricRow("Load time", "%.0f".format(it / 1000.0), "s") }
        g?.ttftMs?.let { MetricRow("Time to first token", "%.2f".format(it / 1000.0), "s") }
        for (p in st.phones.filter { !it.isDriver }) p.rttMs?.let { MetricRow("Round trip to ${p.name}", "%.1f".format(it), "ms") }
        st.boundariesPerToken?.let { MetricRow("Phone boundaries per token", it.toString()) }
    }
    if (n > 0) LayerStrip(shareAssignments(st.shares, n), List(n) { true })
    SwarmTextField(prompt, { prompt = it }, "Ask the split model", singleLine = false)
    PrimaryButton("Ask", { MemCloud.ask(prompt, 200) }, Modifier.fillMaxWidth(), enabled = g?.running != true && prompt.isNotBlank())
    if (g != null) {
        SwarmCard {
            StreamingText(g.text.ifEmpty { "..." }, streaming = g.running)
            g.error?.let { Spacer(Modifier.height(6.dp)); ErrorNote(it) }
            if (!g.running && g.tokens > 0) Text("${g.tokens} tokens", style = SwarmType.mono.copy(fontSize = 11.sp, color = SwarmColors.graphite))
        }
    }
    PhoneLanes(st, showProgress = false)
    BaselinePanel(st, enabled = true)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (st.experts != null) SecondaryButton("ExpertMesh", onExpert, Modifier.weight(1f))
        SecondaryButton("Stop and restore phones", { MemCloud.stop() }, Modifier.weight(1f))
    }
    StepsList(st)
}

// ---------- dropped ----------

@Composable
private fun DroppedView(st: MemCloudState) {
    val n = st.layers ?: 0
    val lost = st.lostPhone
    SwarmCard {
        Text("${lost ?: "A phone"} stopped answering.", style = SwarmType.title)
        Text("Generation stopped and the model server was shut down. ${st.failReason ?: ""}", style = SwarmType.body.copy(color = SwarmColors.graphite))
    }
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
            Text("${g.tokens} tokens streamed before the phone dropped", style = SwarmType.mono.copy(fontSize = 11.sp, color = SwarmColors.graphite))
        }
    }
    val remaining = st.phones.count { !it.isDriver && it.online }
    PrimaryButton("Re-plan with ${remaining + 1} phones", { MemCloud.replan() }, Modifier.fillMaxWidth(), enabled = MemCloudLogic.canReplan(remaining))
    SecondaryButton("Stop and restore phones", { MemCloud.stop() }, Modifier.fillMaxWidth())
    PhoneLanes(st, showProgress = false)
    StepsList(st)
}

// ---------- baseline ----------

@Composable
private fun BaselinePanel(st: MemCloudState, enabled: Boolean) {
    val b = st.baseline
    SwarmCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("This phone alone", style = SwarmType.title)
            if (b != null && !b.running) Stamp(if (b.outcome == "OK") "Loaded" else b.outcome, caution = b.outcome != "OK", small = true)
        }
        Text("Same model, no other phone, no RPC.", style = SwarmType.label.copy(color = SwarmColors.graphite))
        st.smallModel?.let { MetricRow("For scale: ${it.name}", "%.1f".format(it.tokPerSec), "tok/s") }
        if (b != null) {
            Spacer(Modifier.height(6.dp))
            Text(b.detail, style = SwarmType.body.copy(fontSize = 14.sp, lineHeight = 20.sp))
            MetricRow("Time", "%.0f".format(b.elapsedMs / 1000.0), "s")
            b.tokPerSec?.let { MetricRow("Decode", "%.2f".format(it), "tok/s") }
        }
        Spacer(Modifier.height(8.dp))
        SecondaryButton("Try on this phone alone", { MemCloud.baseline() }, Modifier.fillMaxWidth(), enabled = enabled && b?.running != true)
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
        val rtt = st.rttMs
        val boundaries = st.boundariesPerToken
        SwarmCard {
            Text(m.name, style = SwarmType.mono)
            Spacer(Modifier.height(6.dp))
            MetricRow("Experts per layer", m.expertCount.toString())
            MetricRow("Experts used per token, per layer", m.expertUsedCount.toString())
            MetricRow("Layers with experts", m.moeLayers.toString())
        }
        if (live.isNotEmpty()) {
            SectionTitle("Layer ranges")
            for (s in live) {
                Lane(s.name, if (s.isDriver) "this phone" else "worker", trailing = { Text("layers ${s.range}", style = SwarmType.mono.copy(fontSize = 12.sp)) }) {
                    Text("${s.layers} layers x ${m.expertCount} experts = ${ExpertMesh.expertsOnPhone(s.layers, m.expertCount)} experts, all on this phone.", style = SwarmType.body.copy(fontSize = 14.sp, lineHeight = 20.sp))
                }
            }
        } else {
            Note("load a split to see the layer ranges")
        }
        Hairline()
        if (rtt == null) {
            ErrorNote("No round trip measured yet: load a split with at least one other phone.")
        } else {
            val remoteMs = ExpertMesh.remoteExpertMsPerToken(m.moeLayers, rtt)
            val ceiling = ExpertMesh.remoteExpertCeilingTokPerSec(m.moeLayers, rtt)
            MetricRow("Round trip (heartbeat)", "%.1f".format(rtt), "ms")
            MetricRow("Experts on other phones: ${m.moeLayers} layers x %.1f ms".format(rtt), "%.0f".format(remoteMs), "ms per token")
            MetricRow("Ceiling with remote experts", tok(ceiling), "tok/s")
            if (boundaries != null) {
                val rangeMs = ExpertMesh.layerRangeMsPerToken(boundaries, rtt)
                MetricRow("Layer ranges: $boundaries boundaries x %.1f ms".format(rtt), "%.0f".format(rangeMs), "ms per token")
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
