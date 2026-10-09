package com.swarmos.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Text input per DESIGN.md: label above (never floating, never a placeholder), Sheet fill,
 * 1.5 dp Ink outline, 8 dp corners, 48 dp tall. [helper] sits below in Graphite; [error] replaces
 * it, in Ink with a hatched bar on its left (no red). Set [mono] for file names, IPs and numbers.
 * Pass [minLines] (or [maxLines]) for a multi-line box. [trailing] is a slot inside the box at its
 * right end (a 48 dp mic button, say); in a multi-line box it sits at the bottom.
 */
@Composable
fun SwarmTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    helper: String? = null,
    error: String? = null,
    singleLine: Boolean = true,
    mono: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    trailing: (@Composable () -> Unit)? = null,
) {
    val oneLine = singleLine && minLines <= 1 && maxLines == Int.MAX_VALUE
    Column(modifier.fillMaxWidth()) {
        Text(label, style = SwarmType.label.copy(color = SwarmColors.graphite))
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .background(SwarmColors.sheet, SwarmShape)
                .border(1.5.dp, SwarmColors.ink, SwarmShape),
            verticalAlignment = if (oneLine) Alignment.CenterVertically else Alignment.Bottom,
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .padding(start = 12.dp, end = if (trailing != null) 4.dp else 12.dp, top = 12.dp, bottom = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = if (mono) SwarmType.mono.copy(fontSize = 14.sp, lineHeight = 20.sp) else SwarmType.body,
                    singleLine = oneLine,
                    minLines = minLines,
                    maxLines = if (oneLine) 1 else maxLines,
                    keyboardOptions = keyboardOptions,
                    cursorBrush = SolidColor(SwarmColors.ink),
                )
            }
            if (trailing != null) trailing()
        }
        if (error != null) {
            Row(Modifier.padding(top = 4.dp).height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(6.dp).fillMaxHeight().hatched(0.6f).border(1.dp, SwarmColors.ink))
                Spacer(Modifier.width(6.dp))
                Text(error, style = SwarmType.label.copy(fontSize = 12.sp, lineHeight = 16.sp))
            }
        } else if (helper != null) {
            Text(helper, Modifier.padding(top = 4.dp), style = SwarmType.label.copy(fontSize = 12.sp, lineHeight = 16.sp, color = SwarmColors.graphite))
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF6F7F5, widthDp = 360)
@Composable
private fun InputsPreview() {
    SwarmTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SwarmTextField("Where is the warranty bill for my laptop?", {}, "Question", helper = "Searches the index on all three phones")
            SwarmTextField("192.168.242.7", {}, "Peer address", error = "Not a valid address", mono = true)
        }
    }
}
