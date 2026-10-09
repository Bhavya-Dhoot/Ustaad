package com.swarmos.app.sched

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.swarmos.app.core.HealthMonitor
import com.swarmos.app.ui.Hairline
import com.swarmos.app.ui.HeroMetric
import com.swarmos.app.ui.MetricRow
import com.swarmos.app.ui.PhoneToken
import com.swarmos.app.ui.PrimaryButton
import com.swarmos.app.ui.SectionTitle
import com.swarmos.app.ui.SecondaryButton
import com.swarmos.app.ui.Stamp
import com.swarmos.app.ui.SwarmColors
import com.swarmos.app.ui.SwarmShape
import com.swarmos.app.ui.SwarmType
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Swarm load: aggregate tokens/s as the hero number, the two bench32 runs side by side (this phone only, then the whole
 * swarm) and one row per phone. Numbers are measured from the batch just run; a placeholder model is stamped FAKE.
 *
 * It fills the width it is given and does not scroll or pad itself, so it can sit inside another screen's column.
 * [showTitle] adds the section title "Swarm load"; leave it off when the host screen already names the section.
 */
@Composable
fun ThroughputPanel(modifier: Modifier = Modifier, showTitle: Boolean = true) {
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

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (showTitle) SectionTitle("Swarm load")

        Box(Modifier.fillMaxWidth()) {
            Column {
                HeroMetric(swarmReport?.let { "%.1f".format(it.aggTokPerSec) } ?: "-", "tok/s")
                Text(
                    when {
                        swarmReport == null -> "run the swarm benchmark to measure"
                        swarmReport.state == "running" -> "running: ${swarmReport.done + swarmReport.failed} of ${swarmReport.total} jobs"
                        else -> "${swarmReport.done} of ${swarmReport.total} jobs in %.1f s".format(swarmReport.wallMs / 1000.0)
                    },
                    style = SwarmType.mono.copy(color = SwarmColors.graphite),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (fake) Stamp("FAKE", caution = true, modifier = Modifier.align(Alignment.TopEnd))
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PrimaryButton("Run on the swarm", { Sched.batches.start(Bench.bench32(), BatchStore.MODE_SWARM) }, Modifier.fillMaxWidth(), enabled = !running)
            SecondaryButton("Run on this phone only", { Sched.batches.start(Bench.bench32(), BatchStore.MODE_LOCAL) }, Modifier.fillMaxWidth(), enabled = !running)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ResultCard("This phone only", localReport, Modifier.weight(1f))
            ResultCard("Swarm", swarmReport, Modifier.weight(1f))
        }
        if (localReport?.state == "done" && swarmReport?.state == "done" && swarmReport.wallMs > 0) {
            MetricRow("wall-clock, this phone / swarm", "%.2f".format(localReport.wallMs / swarmReport.wallMs), "x")
        }

        Column {
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                Cell("phone", 2.6f, header = true)
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
                    Row(Modifier.weight(2.6f), verticalAlignment = Alignment.CenterVertically) {
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
                            Cell(HealthMonitor.thermalWord(caps.thermalStatus), 1.3f, bold = HealthMonitor.isHot(caps.thermalStatus))
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

/** Standalone screen: the panel with page padding and its own scrolling. */
@Composable
fun ThroughputScreen() {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
        ThroughputPanel(showTitle = true)
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = SwarmType.label.copy(fontWeight = FontWeight.W600), modifier = Modifier.weight(1f))
            if (r?.fake == true) Stamp("FAKE", caution = true, small = true)
        }
        if (r == null) {
            Text("not run yet", style = SwarmType.mono.copy(color = SwarmColors.graphite), modifier = Modifier.padding(top = 4.dp))
            return@Column
        }
        MetricRow("tok/s", "%.1f".format(r.aggTokPerSec))
        MetricRow("wall", "%.1f".format(r.wallMs / 1000.0), "s")
        MetricRow("tokens", r.totalTokens.toString())
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
