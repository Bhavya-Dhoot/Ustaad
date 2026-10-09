package com.swarmos.app.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Deterministic pseudo-random source (SplitMix64). Every hand-drawn shape in the kit takes its
 * wobble from one of these, seeded by a constant, so a shape looks identical on every
 * recomposition and on every phone: it is drawn once by a "hand", it does not shimmer.
 */
internal class Jitter(seed: Long) {
    private var state = seed

    private fun nextLong(): Long {
        state += -7046029254386353131L
        var z = state
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293110705685L
        return z xor (z ushr 31)
    }

    /** Next value in [-1, 1). */
    fun next(): Float = (nextLong() ushr 40).toFloat() / 8388608f - 1f
}

/**
 * Points from [from] to [to] with a gentle sideways drift of at most [amp] px every [step] px.
 * The two end points are exact, so lines still meet the things they join.
 */
internal fun wobblePoints(from: Offset, to: Offset, amp: Float, step: Float, jitter: Jitter): List<Offset> {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val len = hypot(dx, dy)
    if (len < 1f) return listOf(from, to)
    val n = max(2, (len / step).roundToInt())
    val nx = -dy / len
    val ny = dx / len
    return List(n + 1) { i ->
        val t = i / n.toFloat()
        val k = if (i == 0 || i == n) 0f else jitter.next() * amp
        Offset(from.x + dx * t + nx * k, from.y + dy * t + ny * k)
    }
}

/** Points of a hand-drawn oval: starts upper-left, overshoots its start by about 8 % and ends slightly outside it. */
internal fun ovalPoints(cx: Float, cy: Float, rx: Float, ry: Float, jitter: Jitter): List<Offset> {
    val steps = 22
    val start = -PI * 0.78
    val sweep = 2 * PI * 1.08
    val phase = jitter.next() * PI
    return List(steps + 1) { i ->
        val f = i / steps.toDouble()
        val a = start + sweep * f
        val k = 1.0 + 0.035 * sin(2 * a + phase) + 0.03 * jitter.next() + 0.05 * f
        Offset((cx + rx * k * cos(a)).toFloat(), (cy + ry * k * sin(a)).toFloat())
    }
}

/** Smooth open path through [points] (quadratic segments between mid points). */
internal fun smoothPath(points: List<Offset>): Path = Path().apply {
    if (points.isEmpty()) return@apply
    moveTo(points[0].x, points[0].y)
    if (points.size == 2) {
        lineTo(points[1].x, points[1].y)
        return@apply
    }
    for (i in 1 until points.size - 1) {
        val p = points[i]
        val q = points[i + 1]
        quadraticTo(p.x, p.y, (p.x + q.x) / 2f, (p.y + q.y) / 2f)
    }
    val last = points.last()
    lineTo(last.x, last.y)
}

/** A pen-stroke underline: slight bow, rising a little to the right, from x0 to x1 at height y. */
internal fun underlinePath(x0: Float, x1: Float, y: Float, bow: Float, seed: Long): Path {
    val j = Jitter(seed)
    val w = x1 - x0
    val pts = listOf(
        Offset(x0, y + bow * 0.2f),
        Offset(x0 + w * 0.3f, y + bow * (0.75f + 0.12f * j.next())),
        Offset(x0 + w * 0.68f, y + bow * (0.45f + 0.12f * j.next())),
        Offset(x1, y - bow * (0.55f + 0.1f * j.next())),
    )
    return smoothPath(pts)
}

/** Arrowhead: two short strokes back from [tip] along the direction [dir] (radians), a little uneven. */
internal fun arrowheadPath(tip: Offset, dir: Double, len: Float, jitter: Jitter): Path {
    val spread = 0.52
    val l1 = len * (1f + 0.1f * jitter.next())
    val l2 = len * (1f + 0.1f * jitter.next())
    val a1 = dir + PI - spread
    val a2 = dir + PI + spread
    return Path().apply {
        moveTo((tip.x + l1 * cos(a1)).toFloat(), (tip.y + l1 * sin(a1)).toFloat())
        lineTo(tip.x, tip.y)
        lineTo((tip.x + l2 * cos(a2)).toFloat(), (tip.y + l2 * sin(a2)).toFloat())
    }
}

/** Diagonal Ink hatching ("/" strokes) over the current clip: the cautionary fill. */
internal fun DrawScope.drawHatch(
    color: Color = SwarmColors.ink,
    alpha: Float = 0.2f,
    spacing: Dp = 4.dp,
    strokeWidth: Dp = 1.2.dp,
) {
    val gap = spacing.toPx()
    val h = size.height
    val w = size.width
    clipRect {
        var x = -h
        while (x < w) {
            drawLine(
                color = color.copy(alpha = alpha),
                start = Offset(x, h),
                end = Offset(x + h, 0f),
                strokeWidth = strokeWidth.toPx(),
            )
            x += gap
        }
    }
}

/** Hatched fill behind the content. */
internal fun Modifier.hatched(alpha: Float = 0.2f): Modifier = drawBehind { drawHatch(alpha = alpha) }

/** Faint Eraser Smudge dot grid (16 dp). Only ever used behind the swarm topology drawing. */
fun Modifier.dotGrid(spacing: Dp = 16.dp, radius: Dp = 1.25.dp, color: Color = SwarmColors.smudge): Modifier =
    drawBehind {
        val s = spacing.toPx()
        val r = radius.toPx()
        var y = s / 2f
        while (y < size.height) {
            var x = s / 2f
            while (x < size.width) {
                drawCircle(color, r, Offset(x, y))
                x += s
            }
            y += s
        }
    }

/** Solid Ink offset shadow with no blur, drawn [offset] right and down of the element. */
internal fun Modifier.hardShadow(offset: Dp, shape: Shape, color: Color = SwarmColors.ink): Modifier =
    drawBehind {
        val o = offset.toPx()
        translate(o, o) {
            drawOutline(shape.createOutline(size, layoutDirection, this), color)
        }
    }

/** Round-capped ink stroke used by every hand-drawn line. */
internal fun inkStroke(widthPx: Float, dashed: Boolean = false, dashPx: Float = 0f) = Stroke(
    width = widthPx,
    cap = StrokeCap.Round,
    join = StrokeJoin.Round,
    pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(dashPx, dashPx * 0.9f)) else null,
)
