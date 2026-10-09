package com.swarmos.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.Color

private val ShadowOffset = 3.dp

/**
 * The one primary action of a screen: Ink fill, Sheet text, 8 dp corners and a hard 3 dp Ink
 * offset shadow (no blur). Pressing moves the button 3 dp into its shadow.
 * Give it Modifier.fillMaxWidth() for a full-width button.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: SwarmIcon? = null,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val fill = if (enabled) SwarmColors.ink else SwarmColors.smudge
    val content = if (enabled) SwarmColors.sheet else SwarmColors.graphite
    val shadowPx = with(LocalDensity.current) { ShadowOffset.roundToPx() }
    Box(
        modifier
            .padding(end = ShadowOffset, bottom = ShadowOffset)
            .then(if (enabled) Modifier.hardShadow(ShadowOffset, SwarmShape) else Modifier),
        propagateMinConstraints = true, // a fillMaxWidth button makes the face fill the box
    ) {
        Box(
            Modifier
                .offset { if (pressed && enabled) IntOffset(shadowPx, shadowPx) else IntOffset.Zero }
                .background(fill, SwarmShape)
                .border(1.5.dp, if (enabled) SwarmColors.ink else SwarmColors.graphite, SwarmShape)
                .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
                .heightIn(min = 48.dp)
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            ButtonLabel(text, icon, content)
        }
    }
}

/** Secondary action: Sheet fill, 1.5 dp Ink outline, Ink text, no shadow. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: SwarmIcon? = null,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val content = if (enabled) SwarmColors.ink else SwarmColors.graphite
    Box(
        modifier
            .background(if (pressed && enabled) SwarmColors.smudge else SwarmColors.sheet, SwarmShape)
            .border(1.5.dp, content, SwarmShape)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        ButtonLabel(text, icon, content)
    }
}

@Composable
private fun ButtonLabel(text: String, icon: SwarmIcon?, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        if (icon != null) {
            LineIcon(icon, tint = color, size = 20.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(monoNumbers(text), style = SwarmType.body.copy(fontWeight = FontWeight.W700, color = color), maxLines = 1) // numbers in Geist Mono
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF6F7F5)
@Composable
private fun ButtonsPreview() {
    SwarmTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("Host a swarm", {}, Modifier.fillMaxWidth(), icon = SwarmIcon.Plus)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Join", {}, Modifier.weight(1f), icon = SwarmIcon.Join)
                SecondaryButton("Measure link", {}, Modifier.weight(1f), icon = SwarmIcon.Gauge)
            }
            PrimaryButton("Disabled", {}, enabled = false)
        }
    }
}
