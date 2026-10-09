package com.swarmos.app.ui

import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Flat on/off switch: Smudge track with an Ink outline when off, Ink when on, round Sheet knob.
 * 52 by 48 dp so it can be tapped. Pass [onCheckedChange] null when a surrounding row is the
 * toggle (the switch then only draws the state).
 */
@Composable
fun InkSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier) {
    val animate = rememberAnimationsEnabled()
    val knobX by animateDpAsState(if (checked) 20.dp else 0.dp, if (animate) tween(120) else snap(), label = "knob")
    Box(
        modifier
            .size(width = 52.dp, height = 48.dp)
            .then(if (onCheckedChange != null) Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 44.dp, height = 26.dp)
                .background(if (checked) SwarmColors.ink else SwarmColors.smudge, CircleShape)
                .border(1.5.dp, SwarmColors.ink, CircleShape)
                .padding(2.5.dp),
        ) {
            Box(Modifier.offset(x = knobX).size(18.dp).background(SwarmColors.sheet, CircleShape).border(1.5.dp, SwarmColors.ink, CircleShape))
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF6F7F5)
@Composable
private fun SwitchPreview() {
    SwarmTheme {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            InkSwitch(false, {}); Text("off   "); InkSwitch(true, {}); Text("on")
        }
    }
}
