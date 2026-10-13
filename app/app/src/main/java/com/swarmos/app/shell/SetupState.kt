package com.swarmos.app.shell

import android.Manifest
import com.swarmos.app.core.DeviceState
import com.swarmos.app.core.ProbeVerdict
import com.swarmos.app.core.nominalRamGb
import com.swarmos.app.llm.CatalogEntry
import com.swarmos.app.llm.ModelCatalog

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

/** The Hammer button in Settings: what it says now, so that either copy of it shows whether Hammer is on. */
fun hammerButtonText(on: Boolean): String = if (on) "Hammer is on. Tap to stop." else "Hammer: maximum usage"

/** The line under the Hammer button at the top of Settings. */
const val HAMMER_HELPER_LINE =
    "Keeps this phone generating all the time it can, even when it is warm. Answers get slower and the phone gets hot. " +
        "It pauses while the phone is too hot and carries on when it has cooled. " +
        "After you stop it the phone rests for 90 seconds, then normal Keep busy continues. " +
        "Hammer is off again whenever Ustaad restarts."

/** The "Allow background start" row is only shown on vivo and iQOO phones, whose OriginOS stops background apps. */
fun isVivoFamily(manufacturer: String, brand: String): Boolean =
    listOf(manufacturer, brand).any { it.trim().lowercase() in setOf("vivo", "iqoo") }

/**
 * The reason line of the "What this phone can do" setup row: what the device check found and what this phone will do, in plain
 * words, e.g. "16 GB memory, GPU ready: runs the Qwen3.5-4B model and can lend memory." [state] null: not measured yet.
 */
fun deviceCheckLine(state: DeviceState?, checking: Boolean, catalog: List<CatalogEntry> = ModelCatalog.entries): String {
    if (state == null) return if (checking) "Checking this phone: memory, processor and GPU." else "Not checked yet."
    val p = state.profile
    val plan = state.plan
    val memory = "${nominalRamGb(p.totalRamMb)} GB memory"
    val model = plan.chatModel?.let { file -> catalog.firstOrNull { it.file == file }?.label?.substringBefore(" Q") ?: file }
        ?: return if (plan.embedModel != null) "$memory: too little for a chat model, so this phone asks the other phones to compute and still stores and searches files."
        else "$memory: too little to run any model here, so this phone uses the other phones and stores files."
    val gpu = when {
        plan.useGpu && p.gpuProbe?.verdict == ProbeVerdict.NO_GPU -> "GPU not confirmed, will be tried"
        plan.useGpu -> "GPU ready"
        p.gpuProbe?.verdict == ProbeVerdict.NO_GPU -> "GPU not usable"
        else -> "no GPU"
    }
    return "$memory, $gpu: runs the $model model" + if (plan.mayLend) " and can lend memory." else "."
}
