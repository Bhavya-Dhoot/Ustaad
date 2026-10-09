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
) {
    Column(modifier.fillMaxWidth()) {
        Text(label, style = SwarmType.label.copy(color = SwarmColors.graphite))
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .background(SwarmColors.sheet, SwarmShape)
                .border(1.5.dp, SwarmColors.ink, SwarmShape)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = if (mono) SwarmType.mono.copy(fontSize = 14.sp, lineHeight = 20.sp) else SwarmType.body,
                singleLine = singleLine,
                keyboardOptions = keyboardOptions,
                cursorBrush = SolidColor(SwarmColors.ink),
            )
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
