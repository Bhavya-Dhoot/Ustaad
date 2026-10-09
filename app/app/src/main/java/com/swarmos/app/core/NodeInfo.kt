package com.swarmos.app.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class NodeInfo(
    val nodeId: String,
    val name: String,
    val serial: String,
    val ip: String,
    val appVersion: String,
    val chatModel: String?,
    val embedModel: String?,
)

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
)

/** The one Json configuration used for node API traffic and the audit log. */
val SwarmJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
