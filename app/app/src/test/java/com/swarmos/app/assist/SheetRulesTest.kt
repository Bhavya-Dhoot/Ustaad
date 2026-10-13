package com.swarmos.app.assist

import com.swarmos.app.hands.Origin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SheetRulesTest {
    @Test
    fun textFromAnotherAppKeepsTheExternalOriginWhatTheSession() {
        assertEquals(Origin.EXTERNAL_INTENT, SheetRules.runOrigin(seededFromOutside = true, fromSession = false))
        assertEquals(Origin.EXTERNAL_INTENT, SheetRules.runOrigin(seededFromOutside = true, fromSession = true))
    }

    @Test
    fun aTaskWrittenInTheSheetIsOwnOrAssistantSession() {
        assertEquals(Origin.OWN_SHEET, SheetRules.runOrigin(seededFromOutside = false, fromSession = false))
        assertEquals(Origin.ASSISTANT_SESSION, SheetRules.runOrigin(seededFromOutside = false, fromSession = true))
    }

    @Test
    fun onlyOutsideOriginsNeedTheGoCall() {
        assertFalse(SheetRules.needsGo(Origin.OWN_SHEET))
        assertFalse(SheetRules.needsGo(Origin.ASSISTANT_SESSION))
        assertTrue(SheetRules.needsGo(Origin.EXTERNAL_INTENT))
        assertTrue(SheetRules.needsGo(Origin.MCP))
        assertTrue(SheetRules.needsGo(Origin.APP_FUNCTION))
    }

    @Test
    fun theFieldStaysMarkedUntilItIsEmptied() {
        assertTrue(SheetRules.stillSeeded(true, "order food"))
        assertTrue(SheetRules.stillSeeded(true, "o")) // a few characters changed is still their text
        assertFalse(SheetRules.stillSeeded(true, ""))
        assertFalse(SheetRules.stillSeeded(false, "typed by the person"))
    }

    @Test
    fun aTaskTypedAfterEmptyingTheFieldIsTheirOwn() {
        var seeded = true
        seeded = SheetRules.stillSeeded(seeded, "")
        seeded = SheetRules.stillSeeded(seeded, "my own words")
        assertEquals(Origin.OWN_SHEET, SheetRules.runOrigin(seeded, fromSession = false))
    }

    @Test
    fun runNeedsATaskReadyHandsAndNoRunActing() {
        assertTrue(SheetRules.canRun("order food", handsReady = true, busy = false))
        assertFalse(SheetRules.canRun("   ", handsReady = true, busy = false))
        assertFalse(SheetRules.canRun("order food", handsReady = false, busy = false))
        assertFalse(SheetRules.canRun("order food", handsReady = true, busy = true))
    }

    @Test
    fun spokenTextIsOneShortLine() {
        assertEquals("Done. Two items are in the cart.", SheetRules.spokenText("Done.\n\nTwo items are in the cart.\n"))
        val long = "word ".repeat(300)
        val spoken = SheetRules.spokenText(long)
        assertTrue(spoken.length <= 400)
        assertTrue(spoken.endsWith("word"))
        assertEquals("", SheetRules.spokenText("   "))
    }
}

class SessionPassTest {
    private var now = 0L
    private var next = 0
    private fun pass() = SessionPass({ now }, { "token-${next++}" })

    @Test
    fun aTokenWorksOnce() {
        val p = pass()
        val t = p.issue()
        assertTrue(p.redeem(t))
        assertFalse(p.redeem(t))
    }

    @Test
    fun aWrongOrMissingTokenIsRefusedAndDoesNotBurnTheRealOne() {
        val p = pass()
        val t = p.issue()
        assertFalse(p.redeem(null))
        assertFalse(p.redeem("guess"))
        assertFalse(p.redeem(""))
        assertTrue(p.redeem(t))
    }

    @Test
    fun nothingIssuedMeansNothingRedeems() {
        val p = pass()
        assertFalse(p.redeem("token-0"))
        assertFalse(p.redeem(null))
    }

    @Test
    fun anOldTokenExpires() {
        val p = pass()
        val t = p.issue()
        now = SessionPass.LIFETIME_MS + 1
        assertFalse(p.redeem(t))
        now = 0
        assertFalse("an expired token stays dead", p.redeem(t))
    }

    @Test
    fun aNewTokenReplacesTheOldOne() {
        val p = pass()
        val old = p.issue()
        val fresh = p.issue()
        assertFalse(p.redeem(old))
        assertTrue(p.redeem(fresh))
    }

    @Test
    fun theRealGeneratorGivesLongDistinctTokens() {
        val p = SessionPass()
        val a = p.issue()
        val b = p.issue()
        assertEquals(32, a.length)
        assertFalse(a == b)
    }
}
