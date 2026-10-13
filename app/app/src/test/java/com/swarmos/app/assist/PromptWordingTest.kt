package com.swarmos.app.assist

import com.swarmos.app.hands.AskKind
import com.swarmos.app.hands.ConfirmPrompt
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PromptWordingTest {
    @Test
    fun anUnknownControlIsWordedAsNotKnownNotAsDanger() {
        assertEquals("Ustaad does not know this control", PromptWording.reason(AskKind.UNKNOWN_CONTROL, "\"Go\" is not a control known to be harmless"))
    }

    @Test
    fun aCommitAndACheckoutScreenHaveTheirOwnWords() {
        assertEquals(PromptWording.COMMIT, PromptWording.reason(AskKind.COMMIT, "\"Place order\" commits something (pay, order ...)"))
        assertEquals(PromptWording.CHECKOUT_SCREEN, PromptWording.reason(AskKind.CHECKOUT_SCREEN, "this is a payment screen"))
        assertNotEquals(PromptWording.COMMIT, PromptWording.UNKNOWN_CONTROL)
        assertNotEquals(PromptWording.COMMIT, PromptWording.CHECKOUT_SCREEN)
    }

    @Test
    fun theWordedKindsShowTheirWordsEvenWithoutTheGuardsSentence() {
        assertEquals(PromptWording.UNKNOWN_CONTROL, PromptWording.reason(AskKind.UNKNOWN_CONTROL, ""))
        assertEquals(PromptWording.COMMIT, PromptWording.reason(AskKind.COMMIT, ""))
    }

    @Test
    fun otherKindsKeepTheGuardsSentence() {
        for (k in listOf(AskKind.APP_SCOPE, AskKind.LINK, AskKind.ENTER, AskKind.SCROLL, AskKind.OTHER)) {
            assertEquals(k.name, "the guard's sentence", PromptWording.reason(k, "the guard's sentence"))
            assertEquals(k.name, "", PromptWording.reason(k, ""))
        }
    }

    @Test
    fun theBoardCarriesTheKindToThePromptTheWindowShows() = runBlocking {
        for (kind in AskKind.values()) {
            val b = PromptBoard({ 0L }, 10_000, 700)
            val answer = async(Dispatchers.Default, CoroutineStart.DEFAULT) { b.confirm(ConfirmPrompt("r", "App", "tap \"X\"", "why", kind)) }
            val shown = withTimeout(5_000) {
                var p = b.pending.value
                while (p == null) { delay(5); p = b.pending.value }
                p
            }
            assertEquals(kind, shown.kind)
            b.answerNo(shown.id)
            answer.await()
        }
    }
}
