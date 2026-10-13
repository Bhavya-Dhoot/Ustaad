package com.swarmos.app.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Version of the node protocol this build speaks. Builds before the field existed send none, which reads as 1, so a phone
 * on version 1 stays fully supported and is only labelled "older" on the screens.
 */
const val PROTOCOL_VERSION = 2

@Serializable
data class NodeInfo(
    val nodeId: String,
    val name: String,
    val serial: String,
    val ip: String,
    val appVersion: String,
    val chatModel: String?,
    val embedModel: String?,
    /** Node protocol version of the sender; absent (an older build) means 1. [NodeRegistry] sets this node's own to [PROTOCOL_VERSION]. */
    val protocol: Int = 1,
) {
    /** True when the sender runs an older protocol version than this build (information only, nothing is refused). */
    val older: Boolean get() = protocol < PROTOCOL_VERSION
}

@Serializable
data class NodeHealth(
    val nodeId: String,
    val thermalStatus: Int,
    val batteryPct: Int,
    val charging: Boolean,
    val ramAvailMb: Long,
    val lastDecodeTokPerSec: Double?,
    val linkStandard: String?,
    val linkSpeedMbps: Int?,
    /** True when [thermalStatus] was derived from the battery temperature (Android 8 and 9: no system status, see [BatteryHeat]), not read from the system. */
    val thermalDerived: Boolean = false,
)

/** The one Json configuration used for node API traffic and the audit log. */
val SwarmJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
