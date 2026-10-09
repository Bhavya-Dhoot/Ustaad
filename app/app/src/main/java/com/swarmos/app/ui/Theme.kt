package com.swarmos.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object SwarmColors {
    val Bg = Color(0xFF0B0F14)
    val Surface = Color(0xFF121821)
    val Line = Color(0xFF243041)
    val Text = Color(0xFFE6EDF3)
    val Dim = Color(0xFF8B98A9)
    val Ok = Color(0xFF34D399)
    val Warn = Color(0xFFFBBF24)
    val Bad = Color(0xFFF87171)

    // per-node accents
    val Cyan = Color(0xFF22D3EE)      // Phone A
    val Amber = Color(0xFFFBBF24)     // Phone B
    val Magenta = Color(0xFFE879F9)   // Phone C
}

private val nodeLetter = Regex("""(?i)(?:^|\s)([abc])$""")

/** Accent colour from the node name: "... A" cyan, "... B" amber, "... C" magenta, anything else neutral. */
fun accentFor(name: String): Color = when (nodeLetter.find(name.trim())?.groupValues?.get(1)?.uppercase()) {
    "A" -> SwarmColors.Cyan
    "B" -> SwarmColors.Amber
    "C" -> SwarmColors.Magenta
    else -> SwarmColors.Dim
}

/** Monospace with tabular figures so changing metrics do not jitter. */
val MetricStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    fontWeight = FontWeight.Medium,
    fontFeatureSettings = "tnum",
    color = SwarmColors.Text,
)

val LabelStyle = TextStyle(fontSize = 10.sp, letterSpacing = 0.8.sp, color = SwarmColors.Dim)

private val DenseTypography = Typography(
    bodyLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 17.sp),
    bodySmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelLarge = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

private val SwarmColorScheme = darkColorScheme(
    primary = SwarmColors.Cyan,
    onPrimary = SwarmColors.Bg,
    background = SwarmColors.Bg,
    onBackground = SwarmColors.Text,
    surface = SwarmColors.Surface,
    onSurface = SwarmColors.Text,
    surfaceVariant = SwarmColors.Surface,
    onSurfaceVariant = SwarmColors.Dim,
    outline = SwarmColors.Line,
    error = SwarmColors.Bad,
)

@Composable
fun SwarmTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SwarmColorScheme, typography = DenseTypography, content = content)
}
