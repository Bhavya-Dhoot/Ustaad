package com.swarmos.app.link

import com.swarmos.app.core.ThermalLevel

// Small formatters shared by the Swarm tab. All components come from the UI kit in ui/ (PhoneToken, Tag, Stamp,
// MetricRow, PrimaryButton, ...); nothing here wraps them any more.

internal fun fmt1(v: Double?): String = if (v == null) "-" else "%.1f".format(v)

internal fun thermalLabel(status: Int?): String = if (status == null) "-" else ThermalLevel.of(status).word

/** "Phone B" -> "B"; anything else -> its first character (for "Link A to B"). */
internal fun tokenLetter(name: String): String {
    val t = name.trim()
    return Regex("""(?i)(?:^|\s)([abc])$""").find(t)?.groupValues?.get(1)?.uppercase()
        ?: t.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
}
