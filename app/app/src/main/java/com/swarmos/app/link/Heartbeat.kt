package com.swarmos.app.link

import com.swarmos.app.core.NodeHealth
import com.swarmos.app.core.NodeInfo

enum class LinkState { ONLINE, AWAY }

/** Everything the UI and /v1/link/peers say about one peer on one link (one "path"). */
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
    /** Soak counters since this session was created. */
    val beats: Int = 0,
    val missesTotal: Int = 0,
    val awayCount: Int = 0,
    val maxGapMs: Long = 0,
    /** Last known state of the peer app: true in front, false in the background (from X-Swarm-Fg and POST /link/fg). */
    val peerForeground: Boolean? = null,
    /** Why the path is away, when known: the peer left the foreground (OriginOS freezes the app shortly after). */
    val awayReason: String? = null,
    /** Smoothed heartbeat round trip (EWMA, alpha 0.3); the path policy ranks small requests on this. */
    val ewmaRttMs: Double? = null,
    /** Transport failures seen on this path (requests that had to move to another path, probes that broke). */
    val lossCount: Int = 0,
    /** Receiver-timed Mbps of the last bulk probe on this path. */
    val lastMbps: Double? = null,
) {
    val key: String get() = "$nodeId/${link.name}"
    val hostPort: String get() = "$ip:$port"
}


/**
 * Pure state machine behind the 1 Hz heartbeat: [missesToAway] consecutive misses mark the peer away,
 * the next success brings it back. Both calls return true when the state changed.
 */
class HeartbeatTracker(private val missesToAway: Int = 3, start: LinkState = LinkState.ONLINE) {
    @Volatile
    var state: LinkState = start
        private set
    @Volatile
    var misses: Int = 0
        private set
    @Volatile
    var rttMs: Double? = null
        private set
    @Volatile
    var lastSeenMs: Long = 0
        private set
    @Volatile
    var beats: Int = 0
        private set
    @Volatile
    var missesTotal: Int = 0
        private set
    @Volatile
    var awayCount: Int = 0
        private set
    /** Longest time between two successful heartbeats. */
    @Volatile
    var maxGapMs: Long = 0
        private set

    @Synchronized
    fun onSuccess(rttMs: Double, nowMs: Long): Boolean {
        val changed = state != LinkState.ONLINE
        if (lastSeenMs > 0) maxGapMs = maxOf(maxGapMs, nowMs - lastSeenMs)
        state = LinkState.ONLINE
        misses = 0
        beats++
        this.rttMs = rttMs
        lastSeenMs = nowMs
        return changed
    }

    @Synchronized
    fun onMiss(): Boolean {
        misses++
        missesTotal++
        if (state == LinkState.ONLINE && misses >= missesToAway) {
            state = LinkState.AWAY
            awayCount++
            rttMs = null
            return true
        }
        return false
    }

    /** The peer told us it is leaving (POST /link/bye). */
    @Synchronized
    fun onBye(): Boolean {
        misses = missesToAway
        rttMs = null
        val changed = state != LinkState.AWAY
        if (changed) awayCount++
        state = LinkState.AWAY
        return changed
    }
}
