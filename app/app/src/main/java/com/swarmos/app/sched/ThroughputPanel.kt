package com.swarmos.app.sched

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.swarmos.app.core.ThermalLevel
import com.swarmos.app.ui.EmptyHint
import com.swarmos.app.ui.Hairline
import com.swarmos.app.ui.MetricRow
import com.swarmos.app.ui.PhoneToken
import com.swarmos.app.ui.PrimaryButton
import com.swarmos.app.ui.SecondaryButton
import com.swarmos.app.ui.SectionTitle
import com.swarmos.app.ui.Stamp
import com.swarmos.app.ui.SwarmColors
import com.swarmos.app.ui.SwarmShape
import com.swarmos.app.ui.SwarmType
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Text the panel derives from batch reports; pure so it can be tested. */
internal object ThroughputText {
    /**
     * "2.0 times one phone": the swarm's aggregate tokens/s over this phone's, as a plain number. Only when both runs are
     * finished and ran on the same model(s); a ratio across different models would compare the models, not the phones.
     */
    fun ratio(local: BatchReport?, swarm: BatchReport?): String? {
        if (local == null || swarm == null) return null
        if (local.state != "done" || swarm.state != "done") return null
        if (local.models.isEmpty() || local.models != swarm.models) return null
        if (local.aggTokPerSec <= 0.0 || swarm.aggTokPerSec <= 0.0) return null
        return "%.1f times one phone".format(swarm.aggTokPerSec / local.aggTokPerSec)
    }
}

/**
 * Swarm load: the last "this phone only" run and the last swarm run side by side (aggregate tokens/s over the batch wall-clock),
 * the ratio between them when it means something, and one row per phone. A placeholder model is stamped FAKE.
 *
 * It fills the width it is given, sizes itself to its content (a host's fixed height must not squeeze it) and does not scroll
 * or pad itself, so it can sit inside another screen's column. [showTitle] adds the section title "Swarm load".
 * [primaryRun] makes "Run on the swarm" the screen's one primary button; leave it off when the host screen has its own.
 */
@Composable
fun ThroughputPanel(modifier: Modifier = Modifier, showTitle: Boolean = true, primaryRun: Boolean = false) {
    val reports by Sched.batches.latest.collectAsStateWithLifecycle()
    val self by AppGraph.registry.self.collectAsStateWithLifecycle()
    val peers by AppGraph.registry.peers.collectAsStateWithLifecycle()
    val chatModel = AppGraph.llm.modelName
    var views by remember { mutableStateOf<List<PhoneView>>(emptyList()) }

    LaunchedEffect(Unit) {
        while (isActive) {
            runCatching { Sched.swarm.refreshCaps() }
            views = Sched.swarm.phoneViews()
            delay(1000)
        }
    }

    val swarmReport = reports[BatchStore.MODE_SWARM]
    val localReport = reports[BatchStore.MODE_LOCAL]
    val running = swarmReport?.state == "running" || localReport?.state == "running"
    val fake = chatModel == "FAKE" || swarmReport?.fake == true || localReport?.fake == true
    fun nameOf(nodeId: String): String =
        if (nodeId == self.nodeId) self.name else peers.firstOrNull { it.info.nodeId == nodeId }?.info?.name ?: nodeId

    Column(
        modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (showTitle || fake) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (showTitle) SectionTitle("Swarm load")
                Spacer(Modifier.weight(1f))
                if (fake) Stamp("FAKE", caution = true)
            }
        }

        if (swarmReport != null || localReport != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ResultCard("This phone only", localReport, Modifier.weight(1f))
                ResultCard("Swarm", swarmReport, Modifier.weight(1f))
            }
            ThroughputText.ratio(localReport, swarmReport)?.let {
                Text(it, style = SwarmType.mono)
            }
        } else {
            EmptyHint("Run the swarm benchmark to measure tokens per second", Modifier.fillMaxWidth())
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val runSwarm = { Sched.batches.startInBackground(Bench.bench32(), BatchStore.MODE_SWARM) }
            if (primaryRun) PrimaryButton("Run on the swarm", runSwarm, Modifier.fillMaxWidth(), enabled = !running)
            else SecondaryButton("Run on the swarm", runSwarm, Modifier.fillMaxWidth(), enabled = !running)
            SecondaryButton("Run on this phone only", { Sched.batches.startInBackground(Bench.bench32(), BatchStore.MODE_LOCAL) }, Modifier.fillMaxWidth(), enabled = !running)
        }

        Column {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                Cell("phone", 3.4f, header = true)
                Cell("jobs", 0.8f, header = true)
                Cell("tok/s", 1.1f, header = true)
                Cell("load", 1.3f, header = true)
                Cell("thermal", 1.3f, header = true)
            }
            Hairline()
            views.forEach { v ->
                val name = nameOf(v.nodeId)
                val share = swarmReport?.perPhone?.firstOrNull { it.nodeId == v.nodeId }
                val caps = v.caps
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(3.4f), verticalAlignment = Alignment.CenterVertically) {
                        PhoneToken(name, 22.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(if (v.isLocal) "$name (this)" else name, style = SwarmType.mono.copy(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Cell((share?.jobs ?: 0).toString(), 0.8f)
                    Cell(share?.let { "%.1f".format(it.tokPerSec) } ?: "-", 1.1f)
                    when {
                        !v.up || caps == null -> Cell("no answer", 2.6f, dim = true)
                        caps.capacity == 0 -> Cell("no model", 2.6f, dim = true)
                        else -> {
                            Cell("${caps.running}/${caps.capacity} q${caps.queued}", 1.3f)
                            val level = ThermalLevel.of(caps.thermalStatus)
                            Cell(level.word, 1.3f, bold = level.degraded)
                        }
                    }
                }
                Hairline()
            }
            if (views.size <= 1) {
                Text("No other phone is connected yet.", style = SwarmType.label.copy(color = SwarmColors.graphite), modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

/** Standalone screen: the panel with page padding, its own scrolling and its run button as the one primary button. */
@Composable
fun ThroughputScreen() {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
        ThroughputPanel(showTitle = true, primaryRun = true)
    }
}

@Composable
private fun ResultCard(title: String, r: BatchReport?, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(SwarmColors.sheet, SwarmShape)
            .border(1.dp, SwarmColors.smudge, SwarmShape)
            .padding(10.dp),
    ) {
        Text(title, style = SwarmType.label.copy(fontWeight = FontWeight.W600))
        if (r == null) {
            Text("not run yet", style = SwarmType.mono.copy(color = SwarmColors.graphite), modifier = Modifier.padding(top = 4.dp))
            return@Column
        }
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
            Text("%.1f".format(r.aggTokPerSec), style = SwarmType.monoHero.copy(fontSize = 32.sp, lineHeight = 36.sp))
            Spacer(Modifier.width(4.dp))
            Text("tok/s", style = SwarmType.mono.copy(color = SwarmColors.graphite), modifier = Modifier.padding(bottom = 4.dp))
        }
        MetricRow("wall", "%.1f".format(r.wallMs / 1000.0), "s")
        MetricRow("jobs", "${r.done}/${r.total}")
        if (r.failed > 0) MetricRow("failed", r.failed.toString())
        if (r.state == "running") Text("running", style = SwarmType.mono.copy(color = SwarmColors.graphite))
    }
}

@Composable
private fun RowScope.Cell(text: String, weight: Float, header: Boolean = false, dim: Boolean = false, bold: Boolean = false) {
    val color: Color = if (header || dim) SwarmColors.graphite else SwarmColors.ink
    val style = if (header) SwarmType.label.copy(fontSize = 12.sp, color = color)
    else SwarmType.mono.copy(fontSize = 12.sp, color = color, fontWeight = if (bold) FontWeight.W700 else FontWeight.W500)
    Text(text, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(weight))
}
