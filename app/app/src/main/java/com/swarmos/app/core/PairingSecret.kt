package com.swarmos.app.core

/** Which pairing secret the app uses, in one place so the rule can be unit-tested. */
object PairingSecret {
    /** Debug builds trust each other on this fixed secret so dev phones need no QR. Never used in the demo build. */
    const val DEV = "swarmos-dev-secret"

    /**
     * The secret held in memory before the stored one has loaded: a random value nobody knows, in every build.
     * Nothing can be signed with it, so a request that arrives early is refused instead of being checked against [DEV].
     */
    fun placeholder(random: () -> String): String = random()

    /**
     * The secret to use once the stored one is known, and whether it has to be saved. A stored secret always wins;
     * with nothing stored, debug builds use [DEV] (not saved) and every other build makes a random one and saves it.
     */
    fun afterLoad(stored: String?, debug: Boolean, random: () -> String): Pair<String, Boolean> = when {
        stored != null -> stored to false
        debug -> DEV to false
        else -> random() to true
    }
}
