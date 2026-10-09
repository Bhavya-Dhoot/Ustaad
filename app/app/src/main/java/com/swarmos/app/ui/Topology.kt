package com.swarmos.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private val PillShape = RoundedCornerShape(4.dp)

/**
 * The swarm topology drawing board: Sheet fill, 1.5 dp Ink outline, 8 dp corners and the faint
 * 16 dp dot grid. Give it a height (about 220 dp), then place [PhoneNode]s with Modifier.offset
 * and draw [DoodleConnector]s that fill it. Tokens go after the connectors so they sit on top.
 */
@Composable
fun TopologyPanel(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(SwarmShape)
            .background(SwarmColors.sheet)
            .dotGrid()
            .border(1.5.dp, SwarmColors.ink, SwarmShape),
        content = content,
    )
}

/**
 * Wobbly 2 dp Ink line with a hand-drawn arrowhead from [from] to [to] (coordinates inside the
 * box this composable fills, in dp). Pass the two token centres and set [gap] to the token radius
 * plus a little so the line stops short of the tokens. [label] is a handwritten pill at the
 * middle of the line (throughput and round trip time). [dashed] when the far phone is away.
 * Changing [heartbeat] thickens the line for 150 ms (skipped when system animations are removed).
 * The wobble is seeded by [seed], so the line never shimmers.
 */
@Composable
fun DoodleConnector(
    from: DpOffset,
    to: DpOffset,
    modifier: Modifier = Modifier,
    label: String? = null,
    dashed: Boolean = false,
    heartbeat: Int = 0,
    gap: Dp = 0.dp,
    seed: Int = 7,
) {
    val animate = rememberAnimationsEnabled()
    val thickness = remember { Animatable(2f) }
    LaunchedEffect(heartbeat) {
        if (animate && heartbeat != 0) {
            thickness.animateTo(3.5f, tween(75))
            thickness.animateTo(2f, tween(75))
        }
    }
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val a = Offset(from.x.toPx(), from.y.toPx())
            val b = Offset(to.x.toPx(), to.y.toPx())
            val len = hypot(b.x - a.x, b.y - a.y)
            if (len < 1f) return@Canvas
            val ux = (b.x - a.x) / len
            val uy = (b.y - a.y) / len
            val g = gap.toPx().coerceAtMost(len / 3f)
            val start = Offset(a.x + ux * g, a.y + uy * g)
            val end = Offset(b.x - ux * g, b.y - uy * g)
            val jitter = Jitter(seed.toLong())
            val line = smoothPath(wobblePoints(start, end, amp = 1.6.dp.toPx(), step = 34.dp.toPx(), jitter = jitter))
            val widthPx = thickness.value.dp.toPx()
            drawPath(line, SwarmColors.ink, style = inkStroke(widthPx, dashed, dashPx = 7.dp.toPx()))
            val dir = atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())
            drawPath(arrowheadPath(end, dir, 10.dp.toPx(), jitter), SwarmColors.ink, style = inkStroke(2.dp.toPx()))
        }
        if (label != null) {
            val mx = (from.x + to.x) / 2
            val my = (from.y + to.y) / 2
            val dx = (to.x - from.x).value
            val dy = (to.y - from.y).value
            val len = hypot(dx, dy).coerceAtLeast(1f)
            var nx = -dy / len
            var ny = dx / len
            if (ny > 0f) { nx = -nx; ny = -ny }          // pill sits above the line
            val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
            val tilt = (angle * 0.35f).coerceIn(-9f, 9f)
            val lift = 16f
            Box(
                Modifier
                    .offset(mx + (nx * lift).dp, my + (ny * lift).dp)
                    .layout { measurable, constraints ->
                        val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                        layout(0, 0) { p.place(-p.width / 2, -p.height / 2) } // centred on the anchor point
                    }
                    .graphicsLayer { rotationZ = tilt }
                    .background(SwarmColors.sheet, PillShape)
                    .border(1.dp, SwarmColors.smudge, PillShape)
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            ) {
                Text(label, style = SwarmType.note.copy(fontSize = 13.sp, lineHeight = 16.sp))
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF6F7F5, widthDp = 360)
@Composable
private fun TopologyPreview() {
    SwarmTheme {
        TopologyPanel(Modifier.padding(16.dp).height(200.dp)) {
            DoodleConnector(DpOffset(70.dp, 60.dp), DpOffset(190.dp, 140.dp), Modifier.fillMaxSize(), label = "56 Mbps, 11 ms", gap = 26.dp)
            DoodleConnector(DpOffset(190.dp, 140.dp), DpOffset(280.dp, 140.dp), Modifier.fillMaxSize(), dashed = true, gap = 26.dp, seed = 3)
            PhoneNode("Phone A", modifier = Modifier.offset(53.dp, 43.dp))
            PhoneNode("Phone B", modifier = Modifier.offset(173.dp, 123.dp))
            PhoneNode("Phone C", away = true, modifier = Modifier.offset(263.dp, 123.dp))
        }
    }
}
