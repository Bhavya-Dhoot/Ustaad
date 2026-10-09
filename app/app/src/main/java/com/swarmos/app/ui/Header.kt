package com.swarmos.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Title of a screen or sub-page: Bricolage 28 with an optional one-line [subtitle] in Graphite,
 * and [trailing] content at the right (round dots, a stamp) sitting on the title's baseline row.
 */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(title, style = SwarmType.display)
            if (subtitle != null) {
                Text(
                    subtitle,
                    Modifier.padding(top = 2.dp),
                    style = SwarmType.label.copy(fontSize = 14.sp, lineHeight = 20.sp, color = SwarmColors.graphite),
                )
            }
        }
        trailing()
    }
}

/** "Back" as plain underlined text, 48 dp tall to tap. Put it above a [ScreenHeader] on a sub-page. */
@Composable
fun BackLink(onClick: () -> Unit, modifier: Modifier = Modifier, label: String = "Back") {
    Text(
        label,
        modifier
            .heightIn(min = 48.dp)
            .wrapContentHeight(Alignment.CenterVertically)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(end = 16.dp),
        style = SwarmType.label.copy(textDecoration = TextDecoration.Underline),
    )
}

@Preview(showBackground = true, backgroundColor = 0xFFF6F7F5, widthDp = 360)
@Composable
private fun HeaderPreview() {
    SwarmTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BackLink({})
            ScreenHeader("Agent Choir", "three phones answer, one checks") { RoundDots(current = 2) }
        }
    }
}
