package com.swarmos.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A full-width block for one phone: 6 dp marker band on the left edge, a header row (token, name,
 * [role] in Graphite, [trailing] content such as mono metrics or a stamp) on the marker tint,
 * and the body on the Whiteboard. 1.5 dp Ink outline, 8 dp corners. Lanes stack in phone order.
 */
@Composable
fun Lane(
    name: String,
    role: String,
    trailing: @Composable RowScope.() -> Unit = {},
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(SwarmShape)
            .background(SwarmColors.whiteboard)
            .border(1.5.dp, SwarmColors.ink, SwarmShape),
    ) {
        Spacer(Modifier.width(6.dp).fillMaxHeight().background(SwarmColors.marker(name)))
        Column(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth().background(SwarmColors.tint(name)).padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PhoneToken(name, 22.dp)
                Spacer(Modifier.width(8.dp))
                Text(name, style = SwarmType.title.copy(fontSize = 14.sp, lineHeight = 18.sp), maxLines = 1)
                Spacer(Modifier.width(8.dp))
                Text(
                    role,
                    Modifier.weight(1f),
                    style = SwarmType.label.copy(fontSize = 12.sp, color = SwarmColors.graphite),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                trailing()
            }
            Spacer(Modifier.fillMaxWidth().height(1.dp).background(SwarmColors.ink))
            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp), content = content)
        }
    }
}

/**
 * A thing that can be picked up as a unit (a source snippet, a model file, a peer): Sheet fill,
 * 1.5 dp Ink outline, no shadow. Pass a phone name as [owner] for a marker band on the left edge.
 */
@Composable
fun SwarmCard(
    modifier: Modifier = Modifier,
    owner: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(SwarmShape)
            .background(SwarmColors.sheet)
            .border(1.5.dp, SwarmColors.ink, SwarmShape),
    ) {
        if (owner != null) Spacer(Modifier.width(6.dp).fillMaxHeight().background(SwarmColors.marker(owner)))
        Column(Modifier.weight(1f).padding(12.dp), content = content)
    }
}

/**
 * The model drawn as ONE row of small cells, one per layer, filled with the marker colour of the
 * phone that holds it ([assignments] holds node names, null = nobody yet). Cells whose weights are
 * still on their way ([loaded] false) are diagonally hatched in the holder's colour. The cell width
 * shrinks (about 6 dp for 48 layers on a phone) so the row never wraps unless it would get unreadable.
 */
@Composable
fun LayerStrip(assignments: List<String?>, loaded: List<Boolean>, modifier: Modifier = Modifier) {
    val done = assignments.indices.count { loaded.getOrElse(it) { false } && assignments[it] != null }
    val description = "Layer strip, $done of ${assignments.size} layers loaded"
    BoxWithConstraints(modifier.fillMaxWidth().semantics { contentDescription = description }) {
        val n = assignments.size.coerceAtLeast(1)
        val gap = 1.5.dp
        val naturalW = ((maxWidth - gap * (n - 1)) / n)
        val cellW = naturalW.coerceAtMost(10.dp)
        val perRow = if (naturalW >= 4.dp) n else ((maxWidth + gap) / (4.dp + gap)).toInt().coerceAtLeast(1)
        val cw = if (naturalW >= 4.dp) cellW else 4.dp
        val rows = (n + perRow - 1) / perRow
        val cellH = 16.dp
        val totalH = cellH * rows + gap * (rows - 1)
        Canvas(Modifier.fillMaxWidth().height(totalH)) {
            val w = cw.toPx()
            val h = cellH.toPx()
            val g = gap.toPx()
            val line = 1.dp.toPx()
            for (i in 0 until assignments.size) {
                val holder = assignments[i]
                val col = i % perRow
                val row = i / perRow
                val x = col * (w + g)
                val y = row * (h + g)
                val markerColor = holder?.let { SwarmColors.marker(it) }
                val isLoaded = loaded.getOrElse(i) { false }
                val fill: Color = when {
                    markerColor == null -> SwarmColors.smudge
                    isLoaded -> markerColor
                    else -> SwarmColors.sheet
                }
                drawRect(fill, Offset(x, y), Size(w, h))
                if (markerColor != null && !isLoaded) {
                    clipRect(x, y, x + w, y + h) {
                        var sx = x - h
                        while (sx < x + w) {
                            drawLine(markerColor, Offset(sx, y + h), Offset(sx + h, y), 1.4.dp.toPx())
                            sx += 3.2.dp.toPx()
                        }
                    }
                }
                drawRect(SwarmColors.ink, Offset(x + line / 2, y + line / 2), Size(w - line, h - line), style = Stroke(line))
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF6F7F5)
@Composable
private fun LanesPreview() {
    SwarmTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Lane("Phone A", "Solver", trailing = { Text("212 tok  9.4 s", style = SwarmType.mono.copy(fontSize = 11.sp, color = SwarmColors.graphite)) }) {
                Text("Let list price = x. 0.78x = 62,400, so x = 80,000.", style = SwarmType.body.copy(fontSize = 13.sp, lineHeight = 18.sp))
                AnswerChip("80,000")
            }
            SwarmCard(owner = "Phone B") { Text("warranty-certificate.pdf", style = SwarmType.mono, fontWeight = FontWeight.W500) }
            val holders = List(32) { if (it < 16) "Phone A" else if (it < 26) "Phone B" else "Phone C" }
            LayerStrip(holders, List(32) { it < 20 })
        }
    }
}
