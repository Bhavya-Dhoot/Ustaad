package com.swarmos.app.core

/** When the service may keep the phone awake. Pure so it can be unit-tested. */
object ServicePolicy {
    /** The partial wake lock and the low-latency Wi-Fi lock are held only while the app is in front or at least one peer is online. */
    fun holdLocks(appInForeground: Boolean, onlinePeers: Int): Boolean = appInForeground || onlinePeers > 0

    /** Peers that count as online: not marked away by the heartbeat. */
    fun onlinePeers(peers: List<PeerState>): Int = peers.count { !it.away }
}
