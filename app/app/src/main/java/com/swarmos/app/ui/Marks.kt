package com.swarmos.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val StampShape = RoundedCornerShape(4.dp)
private val ChipShape = RoundedCornerShape(4.dp)

/** 28 dp circle in the phone's marker colour with its letter; the mark of "this came from that phone". */
@Composable
fun PhoneToken(name: String, size: Dp = 28.dp, modifier: Modifier = Modifier) {
    val letter = phoneLetter(name)?.toString() ?: name.trim().take(1).uppercase().ifEmpty { "?" }
    Box(
        modifier
            .size(size)
            .background(SwarmColors.marker(name), CircleShape)
            .border(1.5.dp, SwarmColors.ink, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            letter,
            style = SwarmType.title.copy(
                fontSize = (size.value * 0.5f).sp,
                lineHeight = (size.value * 0.5f).sp,
                fontWeight = FontWeight.W700,
            ),
        )
    }
}

/** A token with the phone's name under it, as drawn on the topology panel. */
@Composable
fun PhoneNode(name: String, size: Dp = 34.dp, away: Boolean = false, modifier: Modifier = Modifier) {
    Column(modifier.alpha(if (away) 0.75f else 1f), horizontalAlignment = Alignment.CenterHorizontally) {
        PhoneToken(name, size)
        Spacer(Modifier.height(2.dp))
        Text(name, style = SwarmType.mono.copy(fontSize = 11.sp, fontWeight = FontWeight.W700), maxLines = 1)
    }
}

/**
 * Rubber stamp: double Ink border, mono caps, rotated -4 degrees. Outline only for good states
 * (VERIFIED, CHAIN OK), diagonal hatching for cautions (DISAGREE, OFFLINE, FAKE). It lands once
 * with a 120 ms scale from 1.15 and a 2 degree settle; with system animations removed it is static.
 * Put it in reserved space at the top right of its container, at most one per card.
 */
@Composable
fun Stamp(text: String, caution: Boolean = false, modifier: Modifier = Modifier, small: Boolean = false) {
    val animate = rememberAnimationsEnabled()
    val scale = remember { Animatable(if (animate) 1.15f else 1f) }
    val rotation = remember { Animatable(if (animate) -6f else -4f) }
    LaunchedEffect(Unit) {
        if (animate) {
            launch { scale.animateTo(1f, tween(120)) }
            rotation.animateTo(-4f, tween(120))
        }
    }
    val fontSize = if (small) 9.sp else 11.sp
    Box(
        modifier
            .padding(4.dp)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                rotationZ = rotation.value
            }
            .background(SwarmColors.sheet, StampShape)
            .then(if (caution) Modifier.hatchedClipped(StampShape) else Modifier)
            .border(1.5.dp, SwarmColors.ink, StampShape)
            .padding(2.5.dp)
            .border(1.5.dp, SwarmColors.ink, RoundedCornerShape(2.dp))
            .padding(horizontal = if (small) 5.dp else 8.dp, vertical = if (small) 2.dp else 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.uppercase(),
            style = SwarmType.mono.copy(
                fontSize = fontSize,
                lineHeight = fontSize,
                fontWeight = FontWeight.W700,
                letterSpacing = 0.06.em,
            ),
            maxLines = 1,
        )
    }
}

private fun Modifier.hatchedClipped(shape: RoundedCornerShape): Modifier =
    drawBehind { drawHatchInShape(shape) }

private fun DrawScope.drawHatchInShape(shape: RoundedCornerShape) {
    val outline = shape.createOutline(size, layoutDirection, this)
    val path = Path().apply { addOutline(outline) }
    clipPath(path) { drawHatch() }
}

/** A value a phone proposes: Sheet chip, 1.5 dp Ink outline, small hard shadow, mono bold. */
@Composable
fun AnswerChip(text: String, modifier: Modifier = Modifier) {
    Box(modifier.padding(end = 2.dp, bottom = 2.dp).hardShadow(2.dp, ChipShape)) {
        Box(
            Modifier
                .background(SwarmColors.sheet, ChipShape)
                .border(1.5.dp, SwarmColors.ink, ChipShape)
                .padding(horizontal = 10.dp, vertical = 3.dp),
        ) {
            Text(text, style = SwarmType.mono.copy(fontWeight = FontWeight.W700), maxLines = 1)
        }
    }
}

/** Small mono tag: a state or kind next to a name ("online", "CHAT"). Not a stamp, not coloured. */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier
            .background(SwarmColors.smudge, ChipShape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = SwarmType.mono.copy(fontSize = 11.sp, lineHeight = 14.sp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Round indicators R1..Rn: done are outlined, [current] is filled Ink, upcoming are faint. */
@Composable
fun RoundDots(current: Int, total: Int = 3, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        for (r in 1..total) {
            val isCurrent = r == current
            val upcoming = r > current
            Box(
                Modifier
                    .size(26.dp)
                    .then(if (isCurrent) Modifier.hardShadow(1.dp, CircleShape) else Modifier)
                    .alpha(if (upcoming) 0.55f else 1f)
                    .background(if (isCurrent) SwarmColors.ink else SwarmColors.sheet, CircleShape)
                    .border(if (upcoming) 1.dp else 1.5.dp, if (upcoming) SwarmColors.graphite else SwarmColors.ink, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "R$r",
                    style = SwarmType.mono.copy(
                        fontSize = 10.sp,
                        lineHeight = 10.sp,
                        fontWeight = FontWeight.W700,
                        color = if (isCurrent) SwarmColors.sheet else SwarmColors.graphite,
                    ),
                )
            }
        }
    }
}

/** Handwritten annotation (Shantell Sans), rotated a few degrees. At most two per screen. */
@Composable
fun Note(text: String, modifier: Modifier = Modifier, color: Color = SwarmColors.ink, rotation: Float = -2f) {
    Text(
        text,
        modifier.graphicsLayer { rotationZ = rotation },
        style = SwarmType.note.copy(color = color),
    )
}

/** Plain section name, Bricolage Grotesque 20. Use only where a section needs a name. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = SwarmType.title)
}

/** One Eraser Smudge hairline. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(SwarmColors.smudge))
}

/** Placeholder block for content that is still loading: Smudge, final height, no shimmer, no spinner. */
@Composable
fun Skeleton(height: Dp, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(height).background(SwarmColors.smudge, SwarmShape))
}

/** Thin Ink bar for [fraction] (0..1). No track behind it. */
@Composable
fun InkProgress(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(3.dp)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(3.dp).background(SwarmColors.ink))
    }
}

/** Plain two-column metric line: label left in Graphite, mono value right, unit in Graphite. */
@Composable
fun MetricRow(label: String, value: String, unit: String? = null, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(label, style = SwarmType.label.copy(color = SwarmColors.graphite))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = SwarmType.mono)
            if (unit != null) {
                Spacer(Modifier.width(4.dp))
                Text(unit, style = SwarmType.mono.copy(color = SwarmColors.graphite))
            }
        }
    }
}

/**
 * The one number that matters on a screen: mono 44 sp with a hand-drawn Ink underline, its unit
 * in Graphite, and at most one handwritten [note].
 */
@Composable
fun HeroMetric(value: String, unit: String, note: String? = null, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                Modifier
                    .drawWithCache {
                        val x0 = -2.dp.toPx()
                        val x1 = size.width + 6.dp.toPx()
                        val path = underlinePath(x0, x1, size.height - 4.dp.toPx(), 2.dp.toPx(), seed = 11)
                        val stroke = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round)
                        onDrawBehind { drawPath(path, SwarmColors.ink, style = stroke) }
                    }
                    .padding(bottom = 6.dp),
                style = SwarmType.monoHero,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                unit,
                Modifier.padding(bottom = 10.dp),
                style = SwarmType.mono.copy(fontSize = 15.sp, color = SwarmColors.graphite),
            )
        }
        if (note != null) Note(note, Modifier.padding(start = 6.dp, top = 2.dp))
    }
}

/** A hand-drawn Ink oval around [content]: the final answer. Drawn once from a fixed seed. */
@Composable
fun InkCircle(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .drawWithCache {
                val inset = 3.dp.toPx()
                val rx = size.width / 2f - inset
                val ry = size.height / 2f - inset
                val path = smoothPath(ovalPoints(size.width / 2f, size.height / 2f, rx * 0.97f, ry * 0.95f, Jitter(5)))
                val stroke = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                onDrawBehind { drawPath(path, SwarmColors.ink, style = stroke) }
            }
            .padding(horizontal = 26.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * Text that is still being generated: [text] followed by a 2 dp Ink caret while [streaming]. The
 * caret blinks, or stays solid when system animations are removed. Pass the same style the lane uses.
 */
@Composable
fun StreamingText(text: String, streaming: Boolean, modifier: Modifier = Modifier, style: TextStyle = SwarmType.body) {
    val blink = rememberCaretBlink(streaming)
    Text(
        buildAnnotatedString {
            append(text)
            if (streaming) appendInlineContent("caret", "|")
        },
        modifier,
        style = style,
        inlineContent = mapOf("caret" to caretContent(style, blink)),
    )
}

/** 1 -> 0 -> 1 blink phase of the stream caret; solid (1) when not streaming or when animations are removed. */
@Composable
internal fun rememberCaretBlink(streaming: Boolean): Float {
    val animate = rememberAnimationsEnabled()
    return if (streaming && animate) {
        rememberInfiniteTransition(label = "caret").animateFloat(
            initialValue = 1f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                keyframes { durationMillis = 900; 1f at 0; 1f at 450; 0f at 451; 0f at 900 },
            ),
            label = "caret",
        ).value
    } else 1f
}

/** The 2 dp Ink caret as inline text content (append it with appendInlineContent("caret", "|")). */
@Composable
internal fun caretContent(style: TextStyle, blink: Float): InlineTextContent {
    val caretHeight = with(LocalDensity.current) { (style.fontSize.toPx() * 0.95f).toSp() }
    return InlineTextContent(
        Placeholder(width = 6.sp, height = caretHeight, placeholderVerticalAlign = PlaceholderVerticalAlign.Center),
    ) {
        Box(Modifier.fillMaxHeight().padding(start = 4.dp).width(2.dp).alpha(blink).background(SwarmColors.ink))
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF6F7F5)
@Composable
private fun MarksPreview() {
    SwarmTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PhoneToken("Phone A"); PhoneToken("Phone B"); PhoneToken("Phone C"); PhoneToken("Other")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Stamp("Verified"); Stamp("Disagree", caution = true); AnswerChip("80,000"); Tag("online")
            }
            RoundDots(current = 2)
            HeroMetric("8.4", "tok/s", "measured on Phone A")
            InkCircle { Text("Rs 80,000", style = SwarmType.monoHero.copy(fontSize = 30.sp)) }
            MetricRow("Prefill", "148", "tok/s")
            InkProgress(0.4f)
            Skeleton(20.dp)
            Hairline()
            SectionTitle("Section")
            Note("a handwritten note", color = SwarmColors.markerB)
            Text("ink", Modifier.fillMaxWidth(), textAlign = TextAlign.End)
        }
    }
}
