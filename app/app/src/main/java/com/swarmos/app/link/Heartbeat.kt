package com.swarmos.app.link

import com.swarmos.app.core.NodeHealth
import com.swarmos.app.core.NodeInfo

enum class LinkState { ONLINE, AWAY }

/** Everything the UI and /v1/link/peers say about one peer on one link. */
data class PeerStatus(
    val nodeId: String,
    val name: String,
    val ip: String,
    val port: Int,
    val link: LinkType,
    val state: LinkState,
    val rttMs: Double?,
    val lastSeenMs: Long,
    val misses: Int,
    val health: NodeHealth?,
    val info: NodeInfo,
) {
    val key: String get() = "$nodeId/${link.name}"
    val hostPort: String get() = "$ip:$port"
}

/**
 * Pure state machine behind the 1 Hz heartbeat: [missesToAway] consecutive misses mark the peer away,
 * the next success brings it back. Both calls return true when the state changed.
 */
class HeartbeatTracker(private val missesToAway: Int = 3, start: LinkState = LinkState.ONLINE) {
    var state: LinkState = start
        private set
    var misses: Int = 0
        private set
    var rttMs: Double? = null
        private set
    var lastSeenMs: Long = 0
        private set

    fun onSuccess(rttMs: Double, nowMs: Long): Boolean {
        val changed = state != LinkState.ONLINE
        state = LinkState.ONLINE
        misses = 0
        this.rttMs = rttMs
        lastSeenMs = nowMs
        return changed
    }

    fun onMiss(): Boolean {
        misses++
        if (state == LinkState.ONLINE && misses >= missesToAway) {
            state = LinkState.AWAY
            rttMs = null
            return true
        }
        return false
    }

    /** The peer told us it is leaving (POST /link/bye). */
    fun onBye(): Boolean {
        misses = missesToAway
        rttMs = null
        val changed = state != LinkState.AWAY
        state = LinkState.AWAY
        return changed
    }
}
