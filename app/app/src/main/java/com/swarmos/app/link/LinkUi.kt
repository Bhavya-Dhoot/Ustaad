package com.swarmos.app.link

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swarmos.app.core.HealthMonitor
import com.swarmos.app.ui.PhoneToken
import com.swarmos.app.ui.PrimaryButton
import com.swarmos.app.ui.SecondaryButton
import com.swarmos.app.ui.SwarmColors
import com.swarmos.app.ui.SwarmType

// Thin adapters from the Swarm tab to the shared kit in ui/. The screens call these names; only this file
// knows which kit component backs them.

/** Marker colour for a phone name (A, B, C). */
internal fun peerAccent(name: String): Color = SwarmColors.marker(name)

/** The kit has no status colours: colour means "which phone", state is said in words. */
internal val OkColor: Color get() = SwarmColors.ink
internal val WarnColor: Color get() = SwarmColors.ink
internal val BadColor: Color get() = SwarmColors.ink
internal val DimColor: Color get() = SwarmColors.graphite

/** Every number in tabular mono figures so changing values do not jitter. */
internal val MonoStyle: TextStyle get() = SwarmType.mono
internal val MonoBig: TextStyle get() = SwarmType.monoHero

/** "Phone B" -> "B"; anything else -> first character. */
internal fun tokenLetter(name: String): String {
    val t = name.trim()
    return Regex("""(?i)(?:^|\s)([abc])$""").find(t)?.groupValues?.get(1)?.uppercase()
        ?: t.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
}

@Composable
internal fun Token(name: String, size: Dp = 32.dp) = PhoneToken(name, size)

@Composable
internal fun PrimaryBtn(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    PrimaryButton(text, onClick, modifier.fillMaxWidth(), enabled)

@Composable
internal fun SecondaryBtn(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    SecondaryButton(text, onClick, modifier, enabled)

@Composable
internal fun MetricRow(label: String, value: String, valueColor: Color = SwarmColors.ink) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = SwarmColors.graphite)
        Text(value, style = MonoStyle, color = valueColor)
    }
}

internal fun fmt1(v: Double?): String = if (v == null) "-" else "%.1f".format(v)

internal fun thermalLabel(status: Int?): String = if (status == null) "-" else HealthMonitor.thermalName(status).lowercase()

internal fun stateColor(s: LinkState): Color = if (s == LinkState.ONLINE) SwarmColors.ink else SwarmColors.graphite

internal val SmallMono: TextStyle get() = SwarmType.mono.copy(fontSize = 12.sp)
