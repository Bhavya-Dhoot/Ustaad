package com.swarmos.app.choir

import com.swarmos.app.core.PhoneActivity

/** The "choir" line of [PhoneActivity]: what this phone says it is doing while the Agent Choir works. */
internal object ChoirActivity {
    const val OWNER = "choir"

    fun asking(phones: Int): String = "Asking ${if (phones == 1) "1 phone" else "$phones phones"} to solve one problem."

    fun check(done: Int, total: Int): String = "Running an Agent Choir check ($done of $total)."

    fun solvingFor(name: String?): String = "Solving a problem for ${name ?: "another phone"}."

    fun set(text: String, onStop: (() -> Unit)? = null) = PhoneActivity.set(OWNER, text, onStop)

    fun clear() = PhoneActivity.clear(OWNER)

    /** Shows [text] while [block] runs and takes it off again when it ends, also when it throws or is cancelled. */
    inline fun <T> during(text: String, block: () -> T): T {
        set(text)
        try {
            return block()
        } finally {
            clear()
        }
    }
}
