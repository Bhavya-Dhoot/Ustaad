package com.swarmos.app.link

import java.util.concurrent.ConcurrentHashMap

/** What a request needs from a path: low round trip (small control request) or high throughput (bulk). */
enum class Use { SMALL, BULK }

/**
 * The one place that decides which path carries traffic to a peer that has several (LAN, DIRECT, BT).
 * [PeerLinks.pathPolicy] holds the active policy; callers never pick a transport themselves.
 */
interface PathPolicy {
    /** The best path for [use], or null when the peer has none. Stateful policies remember the last choice. */
    fun choose(paths: List<PeerStatus>, use: Use = Use.SMALL): PeerStatus?

    /** Every usable path, best first (the failover order). */
    fun order(paths: List<PeerStatus>, use: Use = Use.SMALL): List<PeerStatus>
}

/** Plain rule for comparison and tests: the Wi-Fi Direct path when it is up, else any online path. */
object PreferDirectOnline : PathPolicy {
    override fun choose(paths: List<PeerStatus>, use: Use): PeerStatus? = order(paths, use).firstOrNull() ?: paths.firstOrNull()

    override fun order(paths: List<PeerStatus>, use: Use): List<PeerStatus> =
        paths.filter { it.state == LinkState.ONLINE }
            .sortedBy { when (it.link) { LinkType.DIRECT -> 0; LinkType.LAN -> 1; LinkType.BT -> 2 } }
}

/**
 * Chooses by measurement with hysteresis. SMALL requests rank paths by smoothed round trip, BULK by the last
 * measured Mbps (until a path has been measured: Wi-Fi Direct before LAN). Bluetooth is control only, so it is
 * used for SMALL requests only when no Wi-Fi path is online, and never for BULK. The remembered choice is kept
 * unless another path is better by [margin] (default 25 %) and [minHoldMs] have passed; a choice that is no longer
 * online is replaced at once.
 */
class MeasuredPolicy(
    private val margin: Double = 0.25,
    private val minHoldMs: Long = 5_000,
    private val clock: () -> Long = System::currentTimeMillis,
) : PathPolicy {
    private class Held(var key: String, var since: Long)

    private val held = ConcurrentHashMap<String, Held>()

    /** Lower is better. */
    fun score(p: PeerStatus, use: Use): Double = when (use) {
        Use.SMALL -> (p.ewmaRttMs ?: p.rttMs ?: 1_000.0).coerceAtLeast(0.1)
        Use.BULK -> 1e6 / (p.lastMbps ?: if (p.link == LinkType.DIRECT) 300.0 else 100.0).coerceAtLeast(0.1)
    }

    override fun order(paths: List<PeerStatus>, use: Use): List<PeerStatus> {
        val wifi = paths.filter { it.state == LinkState.ONLINE && it.link != LinkType.BT }.sortedBy { score(it, use) }
        if (use == Use.BULK) return wifi
        val bt = paths.filter { it.state == LinkState.ONLINE && it.link == LinkType.BT }
        return wifi + bt
    }

    override fun choose(paths: List<PeerStatus>, use: Use): PeerStatus? {
        val ordered = order(paths, use)
        val best = ordered.firstOrNull() ?: return paths.firstOrNull()
        val node = best.nodeId
        val slot = "$node/$use"
        val now = clock()
        val cur = held[slot]
        val curPath = cur?.let { c -> ordered.firstOrNull { it.key == c.key } }
        if (cur == null || curPath == null) {
            held[slot] = Held(best.key, now)
            return best
        }
        if (best.key != cur.key && now - cur.since >= minHoldMs && score(best, use) < score(curPath, use) * (1 - margin)) {
            cur.key = best.key
            cur.since = now
            return best
        }
        return curPath
    }
}
