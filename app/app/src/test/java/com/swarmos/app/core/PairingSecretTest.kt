package com.swarmos.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PairingSecretTest {
    private var n = 0
    private val random = { "random-${n++}" }

    @Test
    fun thePlaceholderBeforeLoadIsNeverTheDevSecret() {
        assertNotEquals(PairingSecret.DEV, PairingSecret.placeholder(random))
    }

    @Test
    fun aStoredSecretAlwaysWinsAndIsNotSavedAgain() {
        assertEquals("stored" to false, PairingSecret.afterLoad("stored", debug = true, random = random))
        assertEquals("stored" to false, PairingSecret.afterLoad("stored", debug = false, random = random))
    }

    @Test
    fun debugBuildsWithNothingStoredUseTheDevSecretAndDoNotSaveIt() {
        assertEquals(PairingSecret.DEV to false, PairingSecret.afterLoad(null, debug = true, random = random))
    }

    @Test
    fun theDemoBuildWithNothingStoredMakesARandomSecretAndSavesIt() {
        val (secret, save) = PairingSecret.afterLoad(null, debug = false, random = random)
        assertNotEquals(PairingSecret.DEV, secret)
        assertEquals(true, save)
        assertFalse(secret.isEmpty())
    }
}
