package com.swarmos.app.shell

import android.Manifest

/**
 * Which of the three required first-run rows are done, and which the user has already been asked about (the system
 * dialog was shown and dismissed or denied). Continue is enabled once every row is done or has been answered, so a
 * denied or dismissed dialog never leaves the user stuck; "Skip for now" needs no condition at all.
 */
data class SetupState(
    val nearby: Boolean,
    val notifications: Boolean,
    val battery: Boolean,
    val nearbyAsked: Boolean = false,
    val notificationsAsked: Boolean = false,
    val batteryAsked: Boolean = false,
) {
    val canContinue: Boolean get() = (nearby || nearbyAsked) && (notifications || notificationsAsked) && (battery || batteryAsked)
    val doneCount: Int get() = listOf(nearby, notifications, battery).count { it }
}

/**
 * Runtime permissions behind "Allow nearby devices". NEARBY_WIFI_DEVICES only exists from Android 13;
 * on 12 the Wi-Fi Direct calls are covered by location.
 */
fun nearbyPermissions(sdkInt: Int): List<String> = buildList {
    if (sdkInt >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.BLUETOOTH_SCAN)
    add(Manifest.permission.BLUETOOTH_ADVERTISE)
    add(Manifest.permission.BLUETOOTH_CONNECT)
}

/** The helper line under the "Keep busy" switch: whether the mode can also run with the screen off, which needs the battery exemption. */
fun keepBusyScreenOffLine(batteryExempt: Boolean): String =
    if (batteryExempt) "Also runs with the screen off while charging." else "Runs only while Ustaad is on screen."

/** The "Allow background start" row is only shown on vivo and iQOO phones, whose OriginOS stops background apps. */
fun isVivoFamily(manufacturer: String, brand: String): Boolean =
    listOf(manufacturer, brand).any { it.trim().lowercase() in setOf("vivo", "iqoo") }
