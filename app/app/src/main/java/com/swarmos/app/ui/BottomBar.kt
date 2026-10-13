package com.swarmos.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One bottom bar destination. [id] is the module id ("link", "models", "choir", "pdu", "memcloud"). */
data class NavItem(val id: String, val label: String)

/** Bottom bar label for a module id, per DESIGN.md: Swarm, Models, Choir, Data, Memory. */
fun destinationLabel(moduleId: String): String? = when (moduleId) {
    "link" -> "Swarm"
    "models" -> "Models"
    "choir" -> "Choir"
    "pdu" -> "Data"
    "memcloud" -> "Memory"
    "hands" -> "Hands"
    else -> null
}

/**
 * Five destinations with line icons on a Sheet bar under a 1.5 dp Ink line. The active one has a
 * bold Ink label and a hand-drawn underline; the others are Graphite. Targets are 56 dp tall.
 */
@Composable
fun SwarmBottomBar(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(SwarmColors.sheet)
            .drawBehind {
                val w = 1.5.dp.toPx()
                drawLine(SwarmColors.ink, Offset(0f, w / 2), Offset(size.width, w / 2), w)
            }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(horizontal = 4.dp)
            .selectableGroup(),
    ) {
        items.forEachIndexed { index, item ->
            val active = index == selected
            val color = if (active) SwarmColors.ink else SwarmColors.graphite
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = 58.dp)
                    .selectable(
                        selected = active,
                        onClick = { onSelect(index) },
                        role = Role.Tab,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    )
                    .padding(top = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LineIcon(iconForModule(item.id), tint = color, size = 24.dp)
                Spacer(Modifier.height(2.dp))
                Text(
                    item.label,
                    style = SwarmType.label.copy(
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                        fontWeight = if (active) FontWeight.W700 else FontWeight.W500,
                        color = color,
                    ),
                    maxLines = 1,
                )
                // the underline's space is always reserved so nothing jumps when the tab changes
                Canvas(Modifier.padding(top = 2.dp).size(width = 34.dp, height = 6.dp)) {
                    if (active) {
                        val path = underlinePath(2.dp.toPx(), size.width - 2.dp.toPx(), size.height / 2f, 1.6.dp.toPx(), seed = 20L + index)
                        drawPath(path, SwarmColors.ink, style = inkStroke(2.dp.toPx()))
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF6F7F5, widthDp = 384)
@Composable
private fun BottomBarPreview() {
    SwarmTheme {
        val items = listOf("link", "models", "choir", "pdu", "memcloud").map { NavItem(it, destinationLabel(it)!!) }
        Column {
            SwarmBottomBar(items, 0, {})
            Spacer(Modifier.height(12.dp))
            SwarmBottomBar(items, 2, {})
        }
    }
}
