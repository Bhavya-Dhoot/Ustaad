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
) {
    val key: String get() = "$nodeId/${link.name}"
    val hostPort: String get() = "$ip:$port"
}

/**
 * The one place that decides which path carries traffic to a peer that has several (LAN, DIRECT, later BT).
 * [PeerLinks.pathPolicy] holds the active policy; callers never pick a transport themselves.
 */
fun interface PathPolicy {
    fun choose(paths: List<PeerStatus>): PeerStatus?
}

/** Default: the Wi-Fi Direct path when it is up, else any online path, else whatever exists. */
object PreferDirectOnline : PathPolicy {
    override fun choose(paths: List<PeerStatus>): PeerStatus? =
        paths.firstOrNull { it.link == LinkType.DIRECT && it.state == LinkState.ONLINE }
            ?: paths.firstOrNull { it.state == LinkState.ONLINE }
            ?: paths.firstOrNull()
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
    var beats: Int = 0
        private set
    var missesTotal: Int = 0
        private set
    var awayCount: Int = 0
        private set
    /** Longest time between two successful heartbeats. */
    var maxGapMs: Long = 0
        private set

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
    fun onBye(): Boolean {
        misses = missesToAway
        rttMs = null
        val changed = state != LinkState.AWAY
        if (changed) awayCount++
        state = LinkState.AWAY
        return changed
    }
}
