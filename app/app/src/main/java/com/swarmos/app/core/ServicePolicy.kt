package com.swarmos.app.core

/** When the service may keep the phone awake. Pure so it can be unit-tested. */
object ServicePolicy {
    /**
     * The partial wake lock and the low-latency Wi-Fi lock are held while the app is in front, at least one peer is online, or model
     * work is running on this phone ([modelWorkRunning]: a call in flight on a llama-server, or a server loading). The last one
     * is what lets a local job finish with the screen off and no peer around.
     */
    fun holdLocks(appInForeground: Boolean, onlinePeers: Int, modelWorkRunning: Boolean = false): Boolean =
        appInForeground || onlinePeers > 0 || modelWorkRunning

    /** Peers that count as online: not marked away by the heartbeat. */
    fun onlinePeers(peers: List<PeerState>): Int = peers.count { !it.away }

    /**
     * The wake lock is acquired with this timeout, so a lock whose owner is gone cannot keep the phone awake for ever (it used to
     * be taken with no timeout). While [holdLocks] stays true the service renews it every [WAKE_LOCK_RENEW_MS].
     */
    const val WAKE_LOCK_TIMEOUT_MS = 10 * 60_000L

    /** Renewal period: half the timeout, so one missed renewal still leaves the lock held. */
    const val WAKE_LOCK_RENEW_MS = WAKE_LOCK_TIMEOUT_MS / 2

    /** "Model work running" ends this long after the last model call ended: the calls of one job (Choir, an index run) have gaps. */
    const val WORK_LINGER_MS = 15_000L
}
