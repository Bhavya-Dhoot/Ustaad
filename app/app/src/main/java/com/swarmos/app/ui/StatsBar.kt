package com.swarmos.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swarmos.app.AppGraph
import com.swarmos.app.core.HealthMonitor
import com.swarmos.app.llm.ModelStore
import com.swarmos.app.llm.fmtBytes

/** Persistent top bar: this node's name, thermal, battery, free RAM, link, chat model, WAN bytes. */
@Composable
fun StatsBar(onEditName: () -> Unit, modifier: Modifier = Modifier) {
    val self by AppGraph.registry.self.collectAsStateWithLifecycle()
    val h by AppGraph.health.state.collectAsStateWithLifecycle()
    val accent = accentFor(self.name)
    val wan by ModelStore.wanBytes.collectAsStateWithLifecycle()

    val thermalColor = when {
        h.thermalStatus >= 3 -> SwarmColors.Bad
        h.thermalStatus == 2 -> SwarmColors.Warn
        else -> SwarmColors.Text
    }
    val battery = if (h.batteryPct < 0) "?" else "${h.batteryPct}%" + if (h.charging) " chg" else ""
    val ram = "%.1f GB".format(h.ramAvailMb / 1024.0)
    val link = if (h.linkSpeedMbps == null) "no wifi" else "${h.linkStandard ?: "wifi"} ${h.linkSpeedMbps} Mbps"

    Column(
        modifier
            .fillMaxWidth()
            .background(SwarmColors.Surface)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(accent, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(self.name, color = accent, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = onEditName) { Text("edit", color = SwarmColors.Dim, fontSize = 11.sp) }
            Spacer(Modifier.weight(1f))
            Text(AppGraph.llm.modelName, style = MetricStyle, color = SwarmColors.Dim)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Cell("THERMAL", HealthMonitor.thermalName(h.thermalStatus), thermalColor)
            Cell("BATTERY", battery)
            Cell("RAM FREE", ram)
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Cell("LINK", link, weight = 2f)
            // placeholder until the link layer counts bytes; contract: must read 0
            Cell("WAN BYTES", if (wan == 0L) "0" else fmtBytes(wan))
        }
    }
}

@Composable
private fun RowScope.Cell(label: String, value: String, color: Color = SwarmColors.Text, weight: Float = 1f) {
    Column(Modifier.weight(weight)) {
        Text(label, style = LabelStyle)
        Text(value, style = MetricStyle, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
