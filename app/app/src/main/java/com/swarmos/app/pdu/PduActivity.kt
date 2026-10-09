package com.swarmos.app.pdu

import com.swarmos.app.core.PhoneActivity

/** The "pdu" line of [PhoneActivity]: what this phone says it is doing for the Private Data Union. */
internal object PduActivity {
    const val OWNER = "pdu"

    fun listing(): String = "Looking for files to index."

    fun indexing(files: Int, done: Int): String = "Indexing ${count(files, "file")} ($done done)."

    fun indexingScan(): String = "Indexing a scanned page."

    fun searching(otherPhones: Int): String = when (otherPhones) {
        0 -> "Searching this phone."
        1 -> "Searching this phone and 1 other."
        else -> "Searching this phone and $otherPhones others."
    }

    fun answering(sources: Int): String = "Writing an answer from ${count(sources, "source")}."

    fun servingPeer(name: String?): String = "Searching this phone for ${name ?: "another phone"}."

    private fun count(n: Int, noun: String): String = if (n == 1) "1 $noun" else "$n ${noun}s"

    fun set(text: String) = PhoneActivity.set(OWNER, text)

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
