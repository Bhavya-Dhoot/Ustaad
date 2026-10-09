package com.swarmos.app.shell

import android.Manifest

/** Which of the three required first-run rows are done. Continue is enabled only when all of them are. */
data class SetupState(val nearby: Boolean, val notifications: Boolean, val battery: Boolean) {
    val canContinue: Boolean get() = nearby && notifications && battery
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

/** The "Allow background start" row is only shown on vivo and iQOO phones, whose OriginOS stops background apps. */
fun isVivoFamily(manufacturer: String, brand: String): Boolean =
    listOf(manufacturer, brand).any { it.trim().lowercase() in setOf("vivo", "iqoo") }
