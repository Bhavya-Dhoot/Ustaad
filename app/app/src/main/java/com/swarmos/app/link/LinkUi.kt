package com.swarmos.app.link

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swarmos.app.core.HealthMonitor
import com.swarmos.app.ui.accentFor

// Plain building blocks for the Swarm tab. They lean on the Material theme so the later visual pass only has
// to restyle this file; the one theme-specific call is peerAccent().

/** Marker colour for a phone name (A, B, C). Single hook into ui/Theme.kt. */
internal fun peerAccent(name: String): Color = accentFor(name)

internal val OkColor = Color(0xFF2E9E6B)
internal val WarnColor = Color(0xFFC98A00)
internal val BadColor = Color(0xFFD04545)

/** Every number in tabular figures so changing values do not jitter. */
internal val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum")
internal val MonoBig = MonoStyle.copy(fontSize = 44.sp, fontWeight = FontWeight.Bold)

/** "Phone B" -> "B"; anything else -> first character. */
internal fun tokenLetter(name: String): String {
    val t = name.trim()
    return Regex("""(?i)(?:^|\s)([abc])$""").find(t)?.groupValues?.get(1)?.uppercase()
        ?: t.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
}

@Composable
internal fun Token(name: String, size: Dp = 32.dp) {
    val c = peerAccent(name)
    Box(Modifier.size(size).background(c, CircleShape).border(1.dp, Color(0x33000000), CircleShape), contentAlignment = Alignment.Center) {
        Text(tokenLetter(name), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.45f).sp, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun PrimaryBtn(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, modifier.fillMaxWidth(), enabled = enabled, shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors()) {
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun SecondaryBtn(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick, modifier, enabled = enabled, shape = RoundedCornerShape(8.dp)) { Text(text, fontWeight = FontWeight.Medium) }
}

@Composable
internal fun MetricRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MonoStyle, color = valueColor)
    }
}

internal fun fmt1(v: Double?): String = if (v == null) "-" else "%.1f".format(v)

internal fun thermalLabel(status: Int?): String = if (status == null) "-" else HealthMonitor.thermalName(status).lowercase()

internal fun stateColor(s: LinkState): Color = if (s == LinkState.ONLINE) OkColor else BadColor
