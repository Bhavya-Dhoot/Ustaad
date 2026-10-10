package com.swarmos.app.link

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Pair button adopts the code the dialog showed, never one that arrived after the dialog was drawn. */
class PairingConfirmTest {
    private fun payload(name: String, secret: String) = PairingPayload(name, "id-$name", listOf("192.168.1.2"), 8787, secret)

    @After fun tearDown() = LinkRuntime.cancelPairing()

    @Test fun aTapOnAnOlderDialogAdoptsNothingAndTheNewerCodeStaysPending() {
        val a = payload("Phone A", "a".repeat(64))
        val b = payload("Phone B", "b".repeat(64))
        LinkRuntime.handleDeepLink(Pairing.toLink(a))
        LinkRuntime.handleDeepLink(Pairing.toLink(b))   // a second link arrives before the dialog is redrawn
        LinkRuntime.confirmPairing(a)                    // the tap lands on the dialog that still showed Phone A
        assertEquals(b, LinkRuntime.pendingPairing.value)
        assertEquals("Waiting for you to confirm pairing with Phone B", LinkRuntime.message.value)
    }
}
