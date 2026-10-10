package com.swarmos.app.link

import java.util.concurrent.ConcurrentHashMap

/** What [DirectRendezvous] does when a paired peer is heard. */
enum class RendezvousStep {
    SKIP,
    /** Join the peer's Wi-Fi Direct group. */
    JOIN,
    /** Both phones host a group: this one (the larger node id) takes its own down, then joins the other's. */
    YIELD_AND_JOIN,
}

/**
 * The decisions behind the Wi-Fi Direct rendezvous, with no radio in them so they can be tested.
 *
 *  - A client with stored credentials used to try a join every 20 s for up to 30 s even when the host was not hosting,
 *    and every try made the radio scan, which added 300 to 670 ms spikes to LAN round trips. After [maxFailures] failed
 *    joins it stops until the host flag is heard again (the host's advertisement says it is hosting).
 *  - Two phones that both hold host credentials each kept their own group and no Direct path formed. The smaller
 *    node id hosts; the other one yields and joins when it hears the host flag.
 */
class RendezvousPolicy(
    private val retryMs: Long = 20_000,
    private val maxFailures: Int = 2,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lastTry = ConcurrentHashMap<String, Long>()
    private val failures = ConcurrentHashMap<String, Int>()

    /** The phone with the smaller node id hosts; the other yields. */
    fun mustYield(selfId: String, peerId: String): Boolean = selfId > peerId

    /**
     * [role] and [phase] are this phone's [DirectState]; [hostsDirect] says the peer advertises a hosted group (Bluetooth
     * flag), false when the call comes from the periodic check; [storedClient] is true when client credentials are stored.
     * Does not change any state: call [noteTry] once the attempt really starts.
     */
    fun decide(
        selfId: String, peerId: String, role: String, phase: String, hostsDirect: Boolean, storedClient: Boolean,
    ): RendezvousStep {
        if (hostsDirect) failures.remove(peerId) // the host flag is heard again: a join is worth trying afresh
        val now = clock()
        val gated = now - (lastTry[peerId] ?: 0) < retryMs
        val iHost = role == "host" && (phase == "up" || phase == "starting")
        if (iHost && hostsDirect && mustYield(selfId, peerId)) return if (gated) RendezvousStep.SKIP else RendezvousStep.YIELD_AND_JOIN
        if (phase == "up" || phase == "starting") return RendezvousStep.SKIP
        if (!hostsDirect && !storedClient) return RendezvousStep.SKIP
        if (!hostsDirect && (failures[peerId] ?: 0) >= maxFailures) return RendezvousStep.SKIP
        return if (gated) RendezvousStep.SKIP else RendezvousStep.JOIN
    }

    fun noteTry(peerId: String) { lastTry[peerId] = clock() }

    fun onJoined(peerId: String) { failures.remove(peerId) }

    fun onJoinFailed(peerId: String) { failures.merge(peerId, 1, Int::plus) }

    fun failuresOf(peerId: String): Int = failures[peerId] ?: 0
}
