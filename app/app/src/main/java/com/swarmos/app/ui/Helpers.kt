package com.swarmos.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2

/**
 * Empty state: one handwritten line and a hand-drawn arrow pointing down-right, at the action that
 * fills the screen. Put it directly above that action, at the right edge.
 */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.End) {
        Note(text, Modifier.weight(1f, fill = false).padding(bottom = 6.dp))
        Spacer(Modifier.width(6.dp))
        Canvas(Modifier.size(width = 40.dp, height = 34.dp)) {
            val w = size.width
            val h = size.height
            val points = listOf(
                Offset(2.dp.toPx(), 2.dp.toPx()),
                Offset(w * 0.30f, h * 0.10f),
                Offset(w * 0.66f, h * 0.38f),
                Offset(w - 5.dp.toPx(), h - 5.dp.toPx()),
            )
            val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawPath(smoothPath(points), SwarmColors.ink, style = stroke)
            val tip = points.last()
            val before = points[points.size - 2]
            val dir = atan2((tip.y - before.y).toDouble(), (tip.x - before.x).toDouble())
            drawPath(arrowheadPath(tip, dir, 8.dp.toPx(), Jitter(7)), SwarmColors.ink, style = stroke)
        }
    }
}

/** An error or refusal in plain words: Ink text with a hatched bar on its left. No red. */
@Composable
fun ErrorNote(text: String, modifier: Modifier = Modifier) {
    Row(modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(6.dp).fillMaxHeight().hatched(0.6f).border(1.dp, SwarmColors.ink))
        Spacer(Modifier.width(8.dp))
        Text(text, style = SwarmType.body.copy(fontSize = 14.sp, lineHeight = 20.sp))
    }
}

/**
 * Screen change: [content] fades in over 180 ms while rising 8 dp, once, whenever [key] changes
 * (use the selected tab). With animations removed (system or in-app) it is simply there.
 */
@Composable
fun ScreenTransition(key: Any?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val animate = rememberAnimationsEnabled()
    key(key) {
        val progress = remember { Animatable(if (animate) 0f else 1f) }
        LaunchedEffect(Unit) {
            if (animate) progress.animateTo(1f, tween(180))
        }
        Box(
            modifier.graphicsLayer {
                alpha = progress.value
                translationY = (1f - progress.value) * 8.dp.toPx()
            },
        ) { content() }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF6F7F5, widthDp = 360)
@Composable
private fun HelpersPreview() {
    SwarmTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            EmptyHint("No files yet. Scan one.", Modifier.fillMaxWidth())
            ErrorNote("The other phone did not answer.")
        }
    }
}
