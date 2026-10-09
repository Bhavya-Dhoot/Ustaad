package com.swarmos.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Right or wrong in a table, with no colour: [ok] is an Ink tick, not ok is a box hatched in Ink.
 * [boxed] draws the outline around the tick too and puts a cross in the hatched box (matrices).
 */
@Composable
fun TickMark(ok: Boolean, modifier: Modifier = Modifier, size: Dp = 18.dp, boxed: Boolean = false) {
    val shape = RoundedCornerShape(if (boxed) 4.dp else 3.dp)
    val frame = when {
        boxed -> Modifier.border(1.5.dp, SwarmColors.ink, shape)
        !ok -> Modifier.border(1.dp, SwarmColors.ink, shape)
        else -> Modifier
    }
    val hatch = if (ok) Modifier else Modifier.drawBehind { drawHatch(alpha = if (boxed) 0.35f else 0.55f, spacing = 3.dp) }
    Canvas(
        modifier
            .size(size)
            .semantics { contentDescription = if (ok) "right" else "wrong" }
            .then(hatch)
            .then(frame),
    ) {
        val s = this.size.width
        val stroke = inkStroke(2.dp.toPx())
        if (ok) {
            val p = Path().apply {
                moveTo(s * 0.2f, s * 0.54f); lineTo(s * 0.42f, s * 0.76f); lineTo(s * 0.82f, s * 0.24f)
            }
            drawPath(p, SwarmColors.ink, style = stroke)
        } else if (boxed) {
            val p = Path().apply {
                moveTo(s * 0.3f, s * 0.3f); lineTo(s * 0.7f, s * 0.7f)
                moveTo(s * 0.7f, s * 0.3f); lineTo(s * 0.3f, s * 0.7f)
            }
            drawPath(p, SwarmColors.ink, style = stroke)
        }
    }
}

/**
 * A strip of label and value pairs ("embed" "31 ms"): labels in Graphite, values in Ink mono, pairs
 * separated by spacing (wraps onto a second line when it must), a hairline above and below. No dots.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimingStrip(items: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Hairline()
        FlowRow(
            Modifier.padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for ((label, value) in items) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = SwarmColors.graphite)) { append("$label ") }
                        append(value)
                    },
                    style = SwarmType.mono,
                )
            }
        }
        Hairline()
    }
}

/**
 * One result as a bar: [label] left, "[count] of [total]" in mono right, a solid Ink bar of
 * count/total with no track behind it, the percentage in Graphite below, and an optional [note]
 * on the right of that line ("mean 3.2 s"). [emphasised] adds a hand-drawn underline and bold label.
 */
@Composable
fun ScoreBar(label: String, count: Int, total: Int, emphasised: Boolean = false, modifier: Modifier = Modifier, note: String? = null) {
    val fraction = if (total <= 0) 0f else (count.toFloat() / total).coerceIn(0f, 1f)
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                Modifier.weight(1f),
                style = SwarmType.body.copy(fontWeight = if (emphasised) FontWeight.W700 else FontWeight.W400),
            )
            Text("$count of $total", style = SwarmType.mono.copy(fontWeight = FontWeight.W700))
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.fillMaxWidth().height(14.dp).drawBehind {
                drawRect(SwarmColors.ink, size = Size(size.width * fraction, size.height))
                if (emphasised) {
                    val path = underlinePath(0f, size.width, size.height + 4.dp.toPx(), 2.dp.toPx(), seed = 21)
                    drawPath(path, SwarmColors.ink, style = Stroke(2.2.dp.toPx()))
                }
            },
        )
        Spacer(Modifier.height(if (emphasised) 10.dp else 4.dp))
        Row {
            val dim = SwarmType.mono.copy(fontSize = 11.sp, lineHeight = 14.sp, color = SwarmColors.graphite)
            Text(if (total <= 0) "0%" else "${Math.round(fraction * 100)}%", Modifier.weight(1f), style = dim)
            if (note != null) Text(note, style = dim)
        }
    }
}

/**
 * Who agrees with whom: a phone token on each axis and a [TickMark] in every cell. [cells][i][j]
 * is true when phone i and phone j gave the same answer (the diagonal is normally true).
 */
@Composable
fun AgreementMatrix(names: List<String>, cells: List<List<Boolean>>, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(34.dp))
            names.forEach { Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) { PhoneToken(it, 24.dp) } }
        }
        names.forEachIndexed { i, name ->
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(34.dp)) { PhoneToken(name, 24.dp) }
                names.indices.forEach { j ->
                    Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) {
                        TickMark(cells.getOrNull(i)?.getOrNull(j) ?: false, size = 26.dp, boxed = true)
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF6F7F5, widthDp = 360)
@Composable
private fun TablesPreview() {
    SwarmTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TickMark(true); TickMark(false); TickMark(true, boxed = true); TickMark(false, boxed = true)
            }
            TimingStrip(listOf("embed" to "31 ms", "search" to "18 ms", "Phone B" to "64 ms", "first word" to "0.9 s"))
            ScoreBar("One phone alone", 14, 20, note = "mean 8.1 s")
            ScoreBar("Choir with tool check", 19, 20, emphasised = true, note = "mean 21.4 s")
            AgreementMatrix(
                listOf("Phone A", "Phone B", "Phone C"),
                listOf(listOf(true, false, true), listOf(false, true, false), listOf(true, false, true)),
            )
        }
    }
}
