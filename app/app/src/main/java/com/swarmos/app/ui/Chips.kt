package com.swarmos.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

private val NumberRun = Regex("""\d+(?:[.,:/]\d+)*%?""")
private val CiteMarker = Regex("""\[(\d{1,2})]""")
private val CiteShape = RoundedCornerShape(4.dp)

/**
 * [text] with every number (62,400  8.4  79%  10:30) set in Geist Mono, the rest unchanged. Weight and
 * colour are inherited. The mono separators (comma, point, colon) are pulled in so "80,000" stays one number.
 */
fun monoNumbers(text: String): AnnotatedString {
    val digits = SpanStyle(fontFamily = GeistMonoFamily, fontFeatureSettings = "tnum", fontSize = 0.95.em)
    val separator = digits.copy(letterSpacing = (-0.3).em)
    return buildAnnotatedString {
        var last = 0
        for (m in NumberRun.findAll(text)) {
            append(text.substring(last, m.range.first))
            for (c in m.value) withStyle(if (c.isDigit() || c == '%') digits else separator) { append(c) }
            last = m.range.last + 1
        }
        append(text.substring(last))
    }
}

/** Body text in which the numbers are mono: "Ask 3 phones", "The list price was Rs 80,000". */
@Composable
fun MonoText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = SwarmType.body,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
) {
    Text(monoNumbers(text), modifier, style = style, maxLines = maxLines, overflow = overflow, textAlign = textAlign)
}

/** A choice among a few: a pill, Ink fill when [selected], Sheet with an Ink outline otherwise. 48 dp tall to tap. */
@Composable
fun SelectChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            monoNumbers(text),
            Modifier
                .background(if (selected) SwarmColors.ink else SwarmColors.sheet, CircleShape)
                .border(1.5.dp, SwarmColors.ink, CircleShape)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            style = SwarmType.mono.copy(fontSize = 12.sp, lineHeight = 14.sp, color = if (selected) SwarmColors.sheet else SwarmColors.ink),
            maxLines = 1,
        )
    }
}

/** A citation: the small mono chip "[n]" that points at source n. 48 dp to tap, the chip itself is smaller. */
@Composable
fun CiteChip(n: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = "Open source $n", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "[$n]",
            Modifier
                .background(SwarmColors.sheet, CiteShape)
                .border(1.5.dp, SwarmColors.ink, CiteShape)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            style = SwarmType.mono.copy(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.W700),
            maxLines = 1,
        )
    }
}

/**
 * Streamed answer text whose "[1]" "[2]" markers are drawn as small tappable chips inside the
 * sentence ([onCite] gets the number), with the caret after the last word while [streaming].
 * Markers outside [cites] stay plain text. The chips are smaller than 48 dp because they sit in
 * a line of text; list the sources as tappable cards or [CiteChip]s as well.
 */
@Composable
fun CitedText(
    text: String,
    streaming: Boolean,
    onCite: (Int) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = SwarmType.body,
    cites: IntRange = 1..99,
) {
    val blink = rememberCaretBlink(streaming)
    val used = remember(text, cites) {
        CiteMarker.findAll(text).map { it.groupValues[1].toInt() }.filter { it in cites }.distinct().toList()
    }
    val inline = HashMap<String, InlineTextContent>()
    inline["caret"] = caretContent(style, blink)
    for (n in used) {
        val chars = "[$n]".length
        inline["cite$n"] = InlineTextContent(
            Placeholder(width = (chars * 6.6f + 12f).sp, height = 18.sp, placeholderVerticalAlign = PlaceholderVerticalAlign.Center),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 1.dp, vertical = 1.dp)
                    .background(SwarmColors.sheet, CiteShape)
                    .border(1.dp, SwarmColors.ink, CiteShape)
                    .semantics { contentDescription = "Source $n" }
                    .clickable(role = Role.Button) { onCite(n) },
                contentAlignment = Alignment.Center,
            ) {
                Text("[$n]", style = SwarmType.mono.copy(fontSize = 11.sp, lineHeight = 11.sp, fontWeight = FontWeight.W700), maxLines = 1)
            }
        }
    }
    Text(
        buildAnnotatedString {
            var last = 0
            for (m in CiteMarker.findAll(text)) {
                val n = m.groupValues[1].toInt()
                append(text.substring(last, m.range.first))
                if (n in cites) appendInlineContent("cite$n", m.value) else append(m.value)
                last = m.range.last + 1
            }
            append(text.substring(last))
            if (streaming) appendInlineContent("caret", "|")
        },
        modifier,
        style = style,
        inlineContent = inline,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Preview(showBackground = true, backgroundColor = 0xFFF6F7F5, widthDp = 360)
@Composable
private fun ChipsPreview() {
    SwarmTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectChip("Arithmetic", true, {}); SelectChip("Logic", false, {}); SelectChip("Run all 20", false, {})
            }
            Text(monoNumbers("Run all 20 problems in 8.4 s"), style = SwarmType.body)
            CiteChip(2, {})
            CitedText("Your laptop is covered until 14 Mar 2028 [1], with the extended policy active through 13 Mar 2029 [2].", streaming = true, onCite = {})
        }
    }
}
