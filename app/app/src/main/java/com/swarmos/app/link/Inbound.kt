package com.swarmos.app.link

/** What a verified signed request from a peer tells us about the paths we hold to it. */
sealed interface InboundAction {
    /** The request came over the path with this key: that path is alive. */
    data class Beat(val key: String) : InboundAction
    data object Ignore : InboundAction
}

/** Pure decision behind [PeerLinks.noteInbound]. */
object Inbound {
    /** Requests that Bluetooth replays against the node server arrive from loopback. */
    fun isLoopback(remote: String): Boolean = remote.startsWith("127.") || remote == "::1" || remote == "0:0:0:0:0:0:0:1"

    /**
     * [sessions] are the paths held to the sender, [remote] the address the request came from. A request from a
     * path's own address proves that path; a loopback request is a Bluetooth replay and proves the Bluetooth path.
     */
    fun decide(sessions: List<PeerStatus>, remote: String): InboundAction {
        if (isLoopback(remote)) return sessions.firstOrNull { it.link == LinkType.BT }?.let { InboundAction.Beat(it.key) } ?: InboundAction.Ignore
        val same = sessions.firstOrNull { it.link != LinkType.BT && it.ip == remote } ?: return InboundAction.Ignore
        return InboundAction.Beat(same.key)
    }
}
