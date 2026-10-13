package com.swarmos.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The one line-icon family of the app: drawn on a 24 unit grid with a 1.5 dp round stroke. */
enum class SwarmIcon {
    Swarm, Models, Choir, Data, Memory, Plus, Join, Gauge, Nearby, Bell, Battery, Check, Play,
    Mic, Lock, Folder, Camera, Scan, Photo, Tick, Cross, Back, Settings, Download, Stop, Refresh,
}

/** Bottom bar icon for a module id ("link", "models", "choir", "pdu", "memcloud"). */
fun iconForModule(id: String): SwarmIcon = when (id) {
    "link" -> SwarmIcon.Swarm
    "models" -> SwarmIcon.Models
    "choir" -> SwarmIcon.Choir
    "pdu" -> SwarmIcon.Data
    "memcloud" -> SwarmIcon.Memory
    "hands" -> SwarmIcon.Play
    else -> SwarmIcon.Swarm
}

@Composable
fun LineIcon(icon: SwarmIcon, modifier: Modifier = Modifier, tint: Color = SwarmColors.ink, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) { drawLineIcon(icon, tint) }
}

private fun DrawScope.drawLineIcon(icon: SwarmIcon, tint: Color) {
    val s = size.minDimension / 24f
    val stroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * s, y * s)
    fun line(x0: Float, y0: Float, x1: Float, y1: Float) = drawLine(tint, p(x0, y0), p(x1, y1), stroke.width, StrokeCap.Round)
    fun box(x: Float, y: Float, w: Float, h: Float, r: Float = 1.2f) =
        drawRoundRect(tint, p(x, y), Size(w * s, h * s), CornerRadius(r * s), stroke)
    fun poly(vararg pts: Float, close: Boolean = false) {
        val path = Path().apply {
            moveTo(pts[0] * s, pts[1] * s)
            for (i in 2 until pts.size step 2) lineTo(pts[i] * s, pts[i + 1] * s)
            if (close) close()
        }
        drawPath(path, tint, style = stroke)
    }
    when (icon) {
        SwarmIcon.Swarm -> {
            box(9f, 3f, 6f, 5f)
            box(2.5f, 16f, 6f, 5f)
            box(15.5f, 16f, 6f, 5f)
            poly(12f, 8f, 12f, 12f)
            poly(5.5f, 16f, 5.5f, 12f, 18.5f, 12f, 18.5f, 16f)
        }
        SwarmIcon.Models -> {
            poly(12f, 2.5f, 20f, 7f, 20f, 17f, 12f, 21.5f, 4f, 17f, 4f, 7f, close = true)
            poly(4f, 7f, 12f, 11.5f, 20f, 7f)
            poly(12f, 11.5f, 12f, 21.5f)
        }
        SwarmIcon.Choir -> {
            line(3.5f, 10f, 3.5f, 14f)
            line(7.75f, 6f, 7.75f, 18f)
            line(12f, 3f, 12f, 21f)
            line(16.25f, 7f, 16.25f, 17f)
            line(20.5f, 10f, 20.5f, 14f)
        }
        SwarmIcon.Data -> {
            box(3.5f, 3.5f, 17f, 17f, 3f)
            box(7.5f, 7.5f, 3.5f, 3.5f, 0.6f)
            box(13f, 7.5f, 3.5f, 3.5f, 0.6f)
            box(7.5f, 13f, 3.5f, 3.5f, 0.6f)
            box(13f, 13f, 3.5f, 3.5f, 0.6f)
        }
        SwarmIcon.Memory -> {
            box(6.5f, 6.5f, 11f, 11f, 1.5f)
            box(10f, 10f, 4f, 4f, 0.6f)
            for (x in listOf(9.5f, 12f, 14.5f)) {
                line(x, 3f, x, 6.5f)
                line(x, 17.5f, x, 21f)
            }
            for (y in listOf(9.5f, 12f, 14.5f)) {
                line(3f, y, 6.5f, y)
                line(17.5f, y, 21f, y)
            }
        }
        SwarmIcon.Plus -> {
            drawCircle(tint, 9f * s, p(12f, 12f), style = stroke)
            line(12f, 7.5f, 12f, 16.5f)
            line(7.5f, 12f, 16.5f, 12f)
        }
        SwarmIcon.Join -> {
            line(3f, 12f, 14f, 12f)
            poly(10f, 8f, 14f, 12f, 10f, 16f)
            poly(14f, 4f, 20f, 4f, 20f, 20f, 14f, 20f)
        }
        SwarmIcon.Gauge -> {
            drawArc(
                tint, startAngle = 150f, sweepAngle = 240f, useCenter = false,
                topLeft = p(2.5f, 3f), size = Size(19f * s, 19f * s), style = stroke,
            )
            line(12f, 12.5f, 16.5f, 7.5f)
        }
        SwarmIcon.Nearby -> {
            drawCircle(tint, 1.6f * s, p(12f, 12f), style = stroke)
            for (r in listOf(5.5f, 9.5f)) {
                for (start in listOf(-50f, 130f)) {
                    drawArc(
                        tint, startAngle = start, sweepAngle = 100f, useCenter = false,
                        topLeft = p(12f - r, 12f - r), size = Size(2f * r * s, 2f * r * s), style = stroke,
                    )
                }
            }
        }
        SwarmIcon.Bell -> {
            val bell = Path().apply {
                moveTo(5f * s, 17.5f * s)
                lineTo(6.8f * s, 15.8f * s)
                lineTo(6.8f * s, 10.5f * s)
                cubicTo(6.8f * s, 7.5f * s, 9f * s, 5.5f * s, 12f * s, 5.5f * s)
                cubicTo(15f * s, 5.5f * s, 17.2f * s, 7.5f * s, 17.2f * s, 10.5f * s)
                lineTo(17.2f * s, 15.8f * s)
                lineTo(19f * s, 17.5f * s)
                close()
            }
            drawPath(bell, tint, style = stroke)
            line(12f, 3.2f, 12f, 5.5f)
            poly(10f, 19.8f, 10.6f, 20.8f, 13.4f, 20.8f, 14f, 19.8f)
        }
        SwarmIcon.Battery -> {
            box(2.5f, 7f, 16f, 10f, 2f)
            line(21.5f, 10.5f, 21.5f, 13.5f)
            poly(11.8f, 9f, 8.5f, 12.6f, 12.5f, 12.6f, 9.5f, 15.5f)
        }
        SwarmIcon.Check -> poly(5f, 12.5f, 10f, 17.5f, 19f, 7f)
        SwarmIcon.Play -> {
            drawCircle(tint, 9f * s, p(12f, 12f), style = stroke)
            poly(10f, 8f, 10f, 16f, 16.5f, 12f, close = true)
        }
        SwarmIcon.Mic -> {
            box(9f, 3f, 6f, 11f, 3f)
            drawArc(tint, startAngle = 0f, sweepAngle = 180f, useCenter = false, topLeft = p(5.5f, 5f), size = Size(13f * s, 13f * s), style = stroke)
            line(12f, 18.5f, 12f, 21f)
            line(9f, 21f, 15f, 21f)
        }
        SwarmIcon.Lock -> {
            box(5f, 11f, 14f, 9f, 2f)
            val shackle = Path().apply {
                moveTo(8f * s, 11f * s)
                lineTo(8f * s, 8f * s)
                arcTo(Rect(p(8f, 4f), Size(8f * s, 8f * s)), 180f, 180f, false)
                lineTo(16f * s, 11f * s)
            }
            drawPath(shackle, tint, style = stroke)
            line(12f, 14.5f, 12f, 16.5f)
        }
        SwarmIcon.Folder -> poly(3f, 7f, 9f, 7f, 11f, 9.5f, 21f, 9.5f, 21f, 19f, 3f, 19f, close = true)
        SwarmIcon.Camera -> {
            box(3f, 7f, 18f, 12f, 2f)
            poly(8f, 7f, 9.5f, 4.5f, 14.5f, 4.5f, 16f, 7f)
            drawCircle(tint, 3.2f * s, p(12f, 13f), style = stroke)
        }
        SwarmIcon.Scan -> {
            poly(4f, 8f, 4f, 4f, 8f, 4f)
            poly(16f, 4f, 20f, 4f, 20f, 8f)
            poly(20f, 16f, 20f, 20f, 16f, 20f)
            poly(8f, 20f, 4f, 20f, 4f, 16f)
            line(8f, 10f, 16f, 10f)
            line(8f, 14f, 14f, 14f)
        }
        SwarmIcon.Photo -> {
            box(3f, 4f, 18f, 16f, 2f)
            drawCircle(tint, 1.6f * s, p(9f, 10f), style = stroke)
            poly(3f, 18f, 9f, 13f, 13f, 16.5f, 16f, 13.5f, 21f, 18f)
        }
        SwarmIcon.Tick -> poly(5f, 12.5f, 10f, 17.5f, 19f, 7f)
        SwarmIcon.Cross -> {
            line(6f, 6f, 18f, 18f)
            line(18f, 6f, 6f, 18f)
        }
        SwarmIcon.Back -> {
            line(19.5f, 12f, 5f, 12f)
            poly(11f, 5.5f, 4.5f, 12f, 11f, 18.5f)
        }
        SwarmIcon.Settings -> {
            // three sliders: a line and a round knob each
            line(4f, 7f, 20f, 7f); drawCircle(tint, 2f * s, p(15f, 7f), style = stroke)
            line(4f, 12f, 20f, 12f); drawCircle(tint, 2f * s, p(8.5f, 12f), style = stroke)
            line(4f, 17f, 20f, 17f); drawCircle(tint, 2f * s, p(13f, 17f), style = stroke)
        }
        SwarmIcon.Download -> {
            line(12f, 4f, 12f, 15f)
            poly(7.5f, 10.5f, 12f, 15f, 16.5f, 10.5f)
            poly(4f, 16f, 4f, 20f, 20f, 20f, 20f, 16f)
        }
        SwarmIcon.Stop -> box(6.5f, 6.5f, 11f, 11f, 1.5f)
        SwarmIcon.Refresh -> {
            drawArc(tint, startAngle = 30f, sweepAngle = 290f, useCenter = false, topLeft = p(4f, 4f), size = Size(16f * s, 16f * s), style = stroke)
            poly(19.5f, 3.5f, 19.5f, 8.5f, 14.5f, 8.5f)
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF6F7F5)
@Composable
private fun IconsPreview() {
    SwarmTheme {
        Row { SwarmIcon.entries.forEach { LineIcon(it, Modifier.size(32.dp), size = 32.dp) } }
    }
}
