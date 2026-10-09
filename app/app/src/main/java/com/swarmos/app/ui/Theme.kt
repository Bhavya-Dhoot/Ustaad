package com.swarmos.app.ui

import android.provider.Settings
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * "Whiteboard" palette from docs/design/DESIGN.md section 2. Colour in this product means
 * "which phone did this come from" and nothing else: the three markers are data colours,
 * never decoration, buttons, links or status.
 */
object SwarmColors {
    val whiteboard = Color(0xFFF6F7F5)
    val sheet = Color(0xFFFCFDFB)
    val ink = Color(0xFF15171A)
    val graphite = Color(0xFF5E636B)
    val smudge = Color(0xFFE4E7E3)
    val markerA = Color(0xFF1E9BB0)
    val markerB = Color(0xFFE08A3C)
    val markerC = Color(0xFFC8457E)

    /** Marker colour for "Phone A/B/C" (a trailing A, B or C word, any case), graphite for anything else. */
    fun marker(nodeName: String): Color = when (phoneLetter(nodeName)) {
        'A' -> markerA
        'B' -> markerB
        'C' -> markerC
        else -> graphite
    }

    /** Marker colour at 12 % over the whiteboard: header tint of that phone's lane. */
    fun tint(nodeName: String): Color = marker(nodeName).copy(alpha = 0.12f).compositeOver(whiteboard)

    // Legacy names from the scaffold so branches written against it still compile.
    // They are mapped onto the new palette; there are no status colours (Ok/Warn/Bad are Ink).
    val Bg get() = whiteboard
    val Surface get() = sheet
    val Line get() = smudge
    val Text get() = ink
    val Dim get() = graphite
    val Ok get() = ink
    val Warn get() = ink
    val Bad get() = ink
    val Cyan get() = markerA
    val Amber get() = markerB
    val Magenta get() = markerC
}

private val trailingPhoneLetter = Regex("""(?i)(?:^|\s)([abc])$""")

/** 'A', 'B' or 'C' when the name ends in that standalone letter ("Phone A"), otherwise null. */
internal fun phoneLetter(nodeName: String): Char? =
    trailingPhoneLetter.find(nodeName.trim())?.groupValues?.get(1)?.uppercase()?.first()

/** Kept from the scaffold: accent colour for a node name. Same as [SwarmColors.marker]. */
fun accentFor(name: String): Color = SwarmColors.marker(name)

private val NoTrim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun style(
    family: FontFamily,
    size: Float,
    line: Float,
    weight: FontWeight,
    tracking: Float = 0f,
    tnum: Boolean = false,
) = TextStyle(
    fontFamily = family,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = tracking.em,
    fontFeatureSettings = if (tnum) "tnum" else null,
    color = SwarmColors.ink,
    lineHeightStyle = NoTrim,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

/** Type roles from DESIGN.md section 3. Every number in the app uses [mono] or [monoHero]. */
object SwarmType {
    /** Screen titles: Bricolage Grotesque 800, 28/32, tight. */
    val display = style(BricolageFamily, 28f, 32f, FontWeight.W800, -0.02f)

    /** Section titles: Bricolage Grotesque 700, 20/24. */
    val title = style(BricolageFamily, 20f, 24f, FontWeight.W700, -0.01f)

    /** Body copy: Geist 15/22. */
    val body = style(GeistFamily, 15f, 22f, FontWeight.W400)

    /** UI labels: Geist medium 13/16. */
    val label = style(GeistFamily, 13f, 16f, FontWeight.W500)

    /** Inline metrics, file and model names, stamps: Geist Mono medium 13/16, tabular. */
    val mono = style(GeistMonoFamily, 13f, 16f, FontWeight.W500, tnum = true)

    /** The one hero number on a screen: Geist Mono medium 44/48. */
    val monoHero = style(GeistMonoFamily, 44f, 48f, FontWeight.W500, -0.02f, tnum = true)

    /** Handwritten annotation: Shantell Sans 15/20, rotate it a few degrees at the call site. */
    val note = style(ShantellFamily, 15f, 20f, FontWeight.W500)
}

// Legacy scaffold names, mapped onto the new type roles.
val MetricStyle: TextStyle get() = SwarmType.mono
val LabelStyle: TextStyle get() = SwarmType.label.copy(color = SwarmColors.graphite)

/** Every container, button and input is 8 dp. Chips and tokens are pills or circles (see CircleShape). */
val SwarmShape = RoundedCornerShape(8.dp)

private val SwarmShapes = Shapes(
    extraSmall = SwarmShape,
    small = SwarmShape,
    medium = SwarmShape,
    large = SwarmShape,
    extraLarge = SwarmShape,
)

private val SwarmTypography = Typography(
    displayLarge = SwarmType.display,
    displayMedium = SwarmType.display,
    displaySmall = SwarmType.display,
    headlineLarge = SwarmType.display,
    headlineMedium = SwarmType.title,
    headlineSmall = SwarmType.title,
    titleLarge = SwarmType.title,
    titleMedium = SwarmType.title.copy(fontSize = 16.sp, lineHeight = 20.sp),
    titleSmall = SwarmType.label.copy(fontWeight = FontWeight.W600),
    bodyLarge = SwarmType.body,
    bodyMedium = SwarmType.body.copy(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = SwarmType.label.copy(fontWeight = FontWeight.W400),
    labelLarge = SwarmType.label.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.W600),
    labelMedium = SwarmType.label,
    labelSmall = SwarmType.label.copy(fontSize = 11.sp, lineHeight = 14.sp),
)

/** Material 3 mapped onto the palette so stock components are Ink on Sheet, never purple. */
private val SwarmColorScheme = lightColorScheme(
    primary = SwarmColors.ink,
    onPrimary = SwarmColors.sheet,
    primaryContainer = SwarmColors.smudge,
    onPrimaryContainer = SwarmColors.ink,
    inversePrimary = SwarmColors.sheet,
    secondary = SwarmColors.ink,
    onSecondary = SwarmColors.sheet,
    secondaryContainer = SwarmColors.smudge,
    onSecondaryContainer = SwarmColors.ink,
    tertiary = SwarmColors.ink,
    onTertiary = SwarmColors.sheet,
    tertiaryContainer = SwarmColors.smudge,
    onTertiaryContainer = SwarmColors.ink,
    background = SwarmColors.whiteboard,
    onBackground = SwarmColors.ink,
    surface = SwarmColors.sheet,
    onSurface = SwarmColors.ink,
    surfaceVariant = SwarmColors.smudge,
    onSurfaceVariant = SwarmColors.graphite,
    surfaceTint = SwarmColors.sheet,
    inverseSurface = SwarmColors.ink,
    inverseOnSurface = SwarmColors.sheet,
    error = SwarmColors.ink,
    onError = SwarmColors.sheet,
    errorContainer = SwarmColors.smudge,
    onErrorContainer = SwarmColors.ink,
    outline = SwarmColors.ink,
    outlineVariant = SwarmColors.smudge,
    scrim = SwarmColors.ink,
    surfaceBright = SwarmColors.sheet,
    surfaceDim = SwarmColors.whiteboard,
    surfaceContainerLowest = SwarmColors.sheet,
    surfaceContainerLow = SwarmColors.sheet,
    surfaceContainer = SwarmColors.sheet,
    surfaceContainerHigh = SwarmColors.sheet,
    surfaceContainerHighest = SwarmColors.smudge,
)

/** Light only. Wrap the whole app (and every preview) in this. */
@Composable
fun SwarmTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SwarmColorScheme,
        typography = SwarmTypography,
        shapes = SwarmShapes,
        content = content,
    )
}

/**
 * False when the system "remove animations" setting is on (animator duration scale 0) and in
 * previews: stamps and connectors then show their final frame only.
 */
@Composable
fun rememberAnimationsEnabled(): Boolean {
    if (LocalInspectionMode.current) return false
    val resolver = LocalContext.current.contentResolver
    return remember {
        runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }
            .getOrDefault(1f) != 0f
    }
}
