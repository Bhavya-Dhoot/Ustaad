package com.swarmos.app.assist

import java.security.SecureRandom

/**
 * Proof that the intent which opened the task sheet came from Ustaad's own assistant session. AssistActivity is exported, so an
 * extra such as "from_session=true" could be sent by any app; a random one-time token cannot be guessed. The session issues a token
 * and puts it in the intent it starts; the sheet redeems it. A token works once and for [LIFETIME_MS] only; the sheet being open
 * as the assistant's sheet changes only the origin label of a task the person writes there, never what runs without a tap.
 */
class SessionPass(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val random: () -> String = ::secureToken,
) {
    private var token: String? = null
    private var issuedAt = 0L

    @Synchronized
    fun issue(): String {
        val t = random()
        token = t
        issuedAt = clock()
        return t
    }

    @Synchronized
    fun redeem(presented: String?): Boolean {
        val expected = token ?: return false
        if (clock() - issuedAt > LIFETIME_MS) {
            token = null
            return false
        }
        if (presented == null || !java.security.MessageDigest.isEqual(presented.toByteArray(), expected.toByteArray())) return false
        token = null // works once; a wrong guess does not burn it, so another app cannot use up the session's token
        return true
    }

    companion object {
        const val LIFETIME_MS = 30_000L
        val shared = SessionPass()

        private fun secureToken(): String {
            val b = ByteArray(16)
            SecureRandom().nextBytes(b)
            return b.joinToString("") { "%02x".format(it) }
        }
    }
}
