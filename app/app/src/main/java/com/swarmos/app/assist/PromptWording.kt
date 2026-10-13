package com.swarmos.app.assist

import com.swarmos.app.hands.AskKind

/** The reason line of the confirmation prompt, worded by the kind of the question. Plain words, no guard jargon. */
object PromptWording {
    const val UNKNOWN_CONTROL = "Ustaad does not know this control"
    const val COMMIT = "This looks like a step that pays, orders, sends or deletes"
    const val CHECKOUT_SCREEN = "This is a payment screen, so every tap here is asked"

    /** [why] is the guard's own sentence (already cleaned); it is kept for the kinds that have no fixed wording. Empty: nothing to show. */
    fun reason(kind: AskKind, why: String): String = when (kind) {
        AskKind.UNKNOWN_CONTROL -> UNKNOWN_CONTROL
        AskKind.COMMIT -> COMMIT
        AskKind.CHECKOUT_SCREEN -> CHECKOUT_SCREEN
        AskKind.APP_SCOPE, AskKind.LINK, AskKind.ENTER, AskKind.SCROLL, AskKind.OTHER -> why
    }
}
