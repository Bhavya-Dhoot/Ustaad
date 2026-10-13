package com.swarmos.app.hands.android

import com.swarmos.app.hands.Tier

/** Where one model call goes. NONE: nowhere this call may go (no model, or only a model it may not use). */
enum class Route { LOCAL, SWARM, TEST, NONE }

/**
 * The one place that decides where a Hands model call runs (rule S5: screen text stays on the phone unless the owner turned
 * sharing on). Pure: no clock, no network, no Android.
 */
object BrainRoute {
    /**
     * [onPhoneOnly]: the prompt holds screen text. [localReady]: this phone has a chat model that takes JSON-schema requests.
     * [peerHasModel]: a phone of the owner's cluster has one. [peerIsStronger]: one of them has a larger model than this phone,
     * known for certain. [shareScreens]: the owner's switch. [testBrain]: the debug-only stand-in server is on.
     */
    fun pick(
        tier: Tier,
        onPhoneOnly: Boolean,
        localReady: Boolean,
        peerHasModel: Boolean,
        peerIsStronger: Boolean,
        shareScreens: Boolean,
        testBrain: Boolean,
    ): Route {
        if (testBrain) return Route.TEST
        // a screen never leaves the phone unless the owner said so, whatever the tier: no model here means no answer
        if (onPhoneOnly && !shareScreens) return if (localReady) Route.LOCAL else Route.NONE
        return when (tier) {
            Tier.FAST -> when {
                localReady -> Route.LOCAL
                peerHasModel -> Route.SWARM
                else -> Route.NONE
            }
            Tier.STRONG -> when {
                peerHasModel && (peerIsStronger || !localReady) -> Route.SWARM
                localReady -> Route.LOCAL
                else -> Route.NONE
            }
        }
    }
}
