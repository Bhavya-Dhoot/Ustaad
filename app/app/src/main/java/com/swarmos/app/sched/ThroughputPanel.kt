package com.swarmos.app.sched

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swarmos.app.AppGraph
import com.swarmos.app.core.HealthMonitor
import com.swarmos.app.ui.LabelStyle
import com.swarmos.app.ui.MetricStyle
import com.swarmos.app.ui.PrimaryButton
import com.swarmos.app.ui.SecondaryButton
import com.swarmos.app.ui.SwarmColors
import com.swarmos.app.ui.accentFor
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Swarm load: aggregate tokens/s as the hero number, one row per phone, and the two bench32 runs side by side
 * (this phone only, then the whole swarm). Numbers are measured from the batch just run; a fake model is labelled FAKE.
 */
@Composable
fun ThroughputPanel(modifier: Modifier = Modifier) {
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

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("AGGREGATE TOKENS/S", style = LabelStyle)
        Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
            Text(
                swarmReport?.let { "%.1f".format(it.aggTokPerSec) } ?: "-",
                style = MetricStyle.copy(fontSize = 44.sp, fontWeight = FontWeight.Bold),
            )
            if (fake) Text("  FAKE", style = MetricStyle.copy(fontSize = 16.sp), color = SwarmColors.Warn)
        }
        val sub = when {
            swarmReport == null -> "run the swarm benchmark to measure"
            swarmReport.state == "running" -> "running: ${swarmReport.done + swarmReport.failed} of ${swarmReport.total} jobs"
            else -> "${swarmReport.done} of ${swarmReport.total} jobs, %.1f s".format(swarmReport.wallMs / 1000.0)
        }
        Text(sub, style = MetricStyle, color = SwarmColors.Dim)
        if (fake) Text("The model on this phone is the placeholder; its token counts and speeds are not real.", color = SwarmColors.Warn, fontSize = 11.sp)

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Run on this phone only", { Sched.batches.start(Bench.bench32(), BatchStore.MODE_LOCAL) }, enabled = !running)
            PrimaryButton("Run on the swarm", { Sched.batches.start(Bench.bench32(), BatchStore.MODE_SWARM) }, enabled = !running)
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ResultCard("This phone only", localReport, Modifier.weight(1f))
            ResultCard("Swarm", swarmReport, Modifier.weight(1f))
        }
        if (localReport?.state == "done" && swarmReport?.state == "done" && swarmReport.wallMs > 0) {
            Text(
                "swarm finished in %.2f x less wall-clock than this phone alone".format(localReport.wallMs / swarmReport.wallMs),
                style = MetricStyle, color = SwarmColors.Dim, modifier = Modifier.padding(top = 6.dp),
            )
        }

        Spacer(Modifier.height(12.dp))
        Text("PHONES", style = LabelStyle)
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Cell("name", 2f, LabelStyle.color)
            Cell("jobs", 0.7f, LabelStyle.color)
            Cell("tok/s", 1f, LabelStyle.color)
            Cell("load", 1.2f, LabelStyle.color)
            Cell("thermal", 1.2f, LabelStyle.color)
        }
        views.forEach { v ->
            val name = nameOf(v.nodeId)
            val share = swarmReport?.perPhone?.firstOrNull { it.nodeId == v.nodeId }
            val caps = v.caps
            Row(Modifier.fillMaxWidth().background(SwarmColors.Surface).padding(vertical = 4.dp, horizontal = 4.dp)) {
                Cell(if (v.isLocal) "$name (this)" else name, 2f, accentFor(name))
                Cell((share?.jobs ?: 0).toString(), 0.7f)
                Cell(share?.let { "%.1f".format(it.tokPerSec) } ?: "-", 1f)
                if (v.up && caps != null) {
                    Cell("${caps.running}/${caps.capacity} q${caps.queued}", 1.2f)
                    val hot = caps.thermalStatus >= 2
                    Cell(HealthMonitor.thermalName(caps.thermalStatus), 1.2f, if (hot) SwarmColors.Warn else SwarmColors.Text)
                } else {
                    Cell("unreachable", 2.6f, SwarmColors.Bad)
                }
            }
            Spacer(Modifier.height(2.dp))
        }
        if (views.size <= 1) Text("No other phone is registered yet.", color = SwarmColors.Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun ResultCard(title: String, r: BatchReport?, modifier: Modifier = Modifier) {
    Column(modifier.background(SwarmColors.Surface).padding(8.dp)) {
        Text(title, color = SwarmColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        if (r == null) {
            Text("not run yet", style = MetricStyle, color = SwarmColors.Dim)
            return@Column
        }
        Text("TOKENS/S", style = LabelStyle)
        Text("%.1f".format(r.aggTokPerSec), style = MetricStyle.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold))
        Text("wall %.1f s".format(r.wallMs / 1000.0), style = MetricStyle)
        Text("tokens ${r.totalTokens}", style = MetricStyle)
        Text("jobs ${r.done}/${r.total}" + if (r.failed > 0) "  failed ${r.failed}" else "", style = MetricStyle)
        if (r.state == "running") Text("running", style = MetricStyle, color = SwarmColors.Warn)
        if (r.fake) Text("FAKE", style = MetricStyle, color = SwarmColors.Warn)
    }
}

@Composable
private fun RowScope.Cell(text: String, weight: Float, color: Color = SwarmColors.Text) {
    Text(text, style = MetricStyle.copy(fontSize = 12.sp), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(weight))
}
