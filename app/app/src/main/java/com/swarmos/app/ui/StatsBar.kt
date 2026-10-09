package com.swarmos.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swarmos.app.AppGraph
import com.swarmos.app.core.HealthMonitor

/**
 * The one compact status strip at the top of every screen: phone token and name (tap to rename),
 * thermal state, battery, free memory, link standard and speed, and an internet stamp: outline "No internet" when no network has
 * validated internet, otherwise hatched "Online" with [wanBytes] (the model downloader's byte count) beside it when above zero.
 * (The old "0 B to internet" wording claimed more than the counter measures.)
 * From thermal status 3 up ([hot]) a hatched HOT stamp replaces it.
 */
@Composable
fun StatsBar(
    onEditName: () -> Unit,
    modifier: Modifier = Modifier,
    // model downloads (llm/ModelStore); 0 unless a model was downloaded in this session
    wanBytes: Long = com.swarmos.app.llm.ModelStore.wanBytes.collectAsStateWithLifecycle().value,
) {
    val self by AppGraph.registry.self.collectAsStateWithLifecycle()
    val internet by com.swarmos.app.link.LinkModule.internetValidated.collectAsStateWithLifecycle()
    val h by AppGraph.health.state.collectAsStateWithLifecycle()
    val thermal = HealthMonitor.thermalWord(h.thermalStatus)
    val battery = if (h.batteryPct < 0) "?" else "${h.batteryPct}%" + if (h.charging) " chg" else ""
    val ram = "%.1f GB".format(h.ramAvailMb / 1024.0)
    val link = HealthMonitor.linkLabel(h.linkStandard, h.linkSpeedMbps)
    StatusStrip(
        name = self.name,
        thermal = thermal,
        battery = battery,
        freeMemory = ram,
        link = link,
        wanBytes = wanBytes,
        internet = internet,
        onEditName = onEditName,
        modifier = modifier,
        hot = HealthMonitor.isHot(h.thermalStatus),
    )
}

/** State-free version of [StatsBar], used by previews and the gallery. */
@Composable
fun StatusStrip(
    name: String,
    thermal: String,
    battery: String,
    freeMemory: String,
    link: String,
    wanBytes: Long,
    onEditName: () -> Unit,
    modifier: Modifier = Modifier,
    hot: Boolean = false,
    internet: Boolean = false,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(SwarmColors.sheet)
            .drawBehind {
                drawLine(SwarmColors.smudge, Offset(0f, size.height - 0.5.dp.toPx()), Offset(size.width, size.height - 0.5.dp.toPx()), 1.dp.toPx())
            }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClickLabel = "Open settings", onClick = onEditName),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PhoneToken(name, 28.dp)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(name, style = SwarmType.title.copy(fontSize = 15.sp, lineHeight = 18.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        val style = SwarmType.mono.copy(fontSize = 11.sp, lineHeight = 14.sp, color = SwarmColors.graphite)
                        Text(thermal, style = if (hot) style.copy(color = SwarmColors.ink, fontWeight = FontWeight.W700) else style, maxLines = 1)
                        Text(battery, style = style, maxLines = 1)
                        Text(freeMemory, style = style, maxLines = 1)
                        Text(link, style = style, maxLines = 1)
                    }
                }
            }
            if (hot) Stamp("Hot", caution = true, small = true)
            else if (!internet) Stamp("No internet", small = true) // outline: no network has validated internet
            else {
                Stamp("Online", caution = true, small = true) // hatched: some network reaches the internet
                if (wanBytes > 0L) Text(formatWan(wanBytes), style = SwarmType.mono.copy(fontSize = 11.sp, color = SwarmColors.graphite), modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

/** Downloaded bytes in plain units (the model downloader's counter, shown next to the Online stamp). */
private fun formatWan(b: Long): String = when {
    b >= 1_000_000_000 -> "%.1f GB".format(b / 1e9)
    b >= 1_000_000 -> "%.1f MB".format(b / 1e6)
    b >= 1_000 -> "%.0f kB".format(b / 1e3)
    else -> "$b B"
}

@Preview(showBackground = true, backgroundColor = 0xFFF6F7F5, widthDp = 384)
@Composable
private fun StatusStripPreview() {
    SwarmTheme {
        StatusStrip("Phone A", "Cool", "79%", "8.6 GB", "11ac 433 Mbps", 0L, {})
    }
}
