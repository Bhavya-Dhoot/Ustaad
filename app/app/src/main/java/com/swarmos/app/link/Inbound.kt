package com.swarmos.app.link

import java.util.concurrent.ConcurrentHashMap

/** What a verified signed request from a peer tells us about the paths we hold to it. */
sealed interface InboundAction {
    /** The request came over the path with this key: that path is alive. */
    data class Beat(val key: String) : InboundAction
    /** The peer reached us from a private address none of our paths to it uses: it has moved, go and find it there. */
    data class Reconnect(val host: String, val port: Int) : InboundAction
    data object Ignore : InboundAction
}

/** Lets one thing happen at most once per [gapMs] for each key. */
class RateGate(private val gapMs: Long, private val clock: () -> Long = System::currentTimeMillis) {
    private val last = ConcurrentHashMap<String, Long>()

    fun tryAcquire(key: String): Boolean {
        val now = clock()
        var granted = false
        last.compute(key) { _, at -> if (at == null || now - at >= gapMs) { granted = true; now } else at }
        return granted
    }
}

/** Pure decision behind [PeerLinks.noteInbound]. */
object Inbound {
    /** A phone that got a new address is reconnected at most this often. */
    const val RECONNECT_GAP_MS = 10_000L

    /** Requests that Bluetooth replays against the node server arrive from loopback. */
    fun isLoopback(remote: String): Boolean = remote.startsWith("127.") || remote == "::1" || remote == "0:0:0:0:0:0:0:1"

    /**
     * [sessions] are the paths held to the sender, [remote] the address the request came from. A request from a
     * path's own address proves that path; a loopback request is a Bluetooth replay and proves the Bluetooth path; a
     * request from another private address means the phone moved, so connect to where it is now (same node port as
     * the paths we hold, else [defaultPort]).
     */
    fun decide(
        sessions: List<PeerStatus>, remote: String, defaultPort: Int = 8787, isPrivate: (String) -> Boolean = LinkRoute::isLocalAddress,
    ): InboundAction {
        if (isLoopback(remote)) return sessions.firstOrNull { it.link == LinkType.BT }?.let { InboundAction.Beat(it.key) } ?: InboundAction.Ignore
        sessions.firstOrNull { it.link != LinkType.BT && it.ip == remote }?.let { return InboundAction.Beat(it.key) }
        if (!isPrivate(remote)) return InboundAction.Ignore
        val port = sessions.firstOrNull { it.link != LinkType.BT && it.port > 0 }?.port ?: defaultPort
        return InboundAction.Reconnect(remote, port)
    }
}
