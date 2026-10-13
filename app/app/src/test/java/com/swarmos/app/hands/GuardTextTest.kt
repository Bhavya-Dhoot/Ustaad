package com.swarmos.app.hands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardTextTest {
    @Test fun invisibleAndControlCharactersAreRemoved() {
        assertEquals("pay", GuardText.norm("P​a‌y‍⁠﻿"))
        assertEquals("pay", GuardText.norm("‮pay‬"))
        assertEquals("pay", GuardText.norm("⁦pay⁩‎‏"))
        assertEquals("pay", GuardText.norm("p­ay"))
        assertEquals("pay", GuardText.norm("pa\u0000\u0007y"))
        assertEquals("pay", GuardText.norm("pa️y"))
        assertEquals("pay", GuardText.norm("pa󠁰y")) // a tag character
    }

    @Test fun lineBreaksAndOddSpacesBecomeOneSpace() {
        assertEquals("pay now", GuardText.norm("pay\nnow"))
        assertEquals("pay now", GuardText.norm("  pay  now \t"))
    }

    @Test fun compatibilityFormsAreFolded() {
        assertEquals("pay", GuardText.norm("ＰＡＹ"))
        assertEquals("pay", GuardText.norm("ⓟⓐⓨ")) // circled letters
        assertEquals("pay", GuardText.norm("ᴘᴀʏ")) // small capitals
        assertEquals("pay", GuardText.norm("pаy")) // a Cyrillic a
    }

    @Test fun aCapitalAfterASmallLetterStartsANewWordButAllCapsStaysWhole() {
        assertEquals("pay now", GuardText.norm("PayNow"))
        assertEquals("place order", GuardText.norm("placeOrder"))
        assertEquals("paynow", GuardText.norm("PAYNOW"))
        assertEquals("phonepe", GuardText.norm("PhonePe", camel = false))
    }

    @Test fun normalisingTwiceChangesNothing() {
        for (s in listOf("PayNow", "P​ay  now", "अभी नहीं", "Rs. 250.00", "é")) {
            assertEquals(s, GuardText.norm(s), GuardText.norm(GuardText.norm(s)))
        }
    }

    @Test fun theWordsOfAResourceIdAreSplit() {
        assertEquals("btn place order", GuardText.idWords("com.app:id/btn_place_order"))
        assertEquals("pay now button", GuardText.idWords("x:id/PayNowButton"))
        assertEquals("btn pay 2", GuardText.idWords("btn_pay2"))
        assertEquals("", GuardText.idWords(""))
    }

    private val commit = GuardText.WordSet(listOf("pay", "place order", "reorder", "install"), squeeze = true, fillers = listOf("now", "to"))

    @Test fun adjacentPiecesOfACommitWordCount() {
        for (t in listOf("p ay", "p a y", "pa y", "place-order", "pl ace order", "re order", "paynow", "pay now", "payto", "proceed to p ay now", "in stall")) {
            assertTrue(t, commit.matches(t))
        }
    }

    @Test fun aLongerWordThatContainsAShortOneDoesNot() {
        for (t in listOf("display", "repay later", "payee", "spay", "installation", "reordering", "add to cart", "to now", "")) {
            assertFalse(t, commit.matches(t))
        }
    }

    @Test fun withoutSqueezeOnlyWholeWordsMatch() {
        val plain = GuardText.WordSet(listOf("pay"))
        assertTrue(plain.matches("pay now"))
        assertFalse(plain.matches("p ay"))
        assertFalse(plain.matches("paynow"))
    }

    @Test fun matchesWholeIgnoresPunctuationAtTheEndsAndKeepsCombiningMarks() {
        val w = GuardText.WordSet(listOf("not now", "बंद करें"))
        assertTrue(w.matchesWhole("Not now!"))
        assertTrue(w.matchesWhole("बंद करें"))
        assertFalse(w.matchesWhole("not now please"))
    }

    @Test fun aCardNumberIsFoundInGroupsOrRunOnTogetherWithOtherText() {
        assertTrue(GuardText.hasCardNumber("4111111111111111"))
        assertTrue(GuardText.hasCardNumber("pay with 4111 1111 1111 1111 now"))
        assertTrue(GuardText.hasCardNumber("4111.1111.1111.1111"))
        assertTrue("two numbers one after the other", GuardText.hasCardNumber("1234 5678 9012 3456 4111 1111 1111 1111"))
        assertFalse(GuardText.hasCardNumber("4111 1111 1111 1112"))
        assertFalse(GuardText.hasCardNumber("12 34 56"))
        assertFalse(GuardText.hasCardNumber("9876543210"))
    }

    @Test fun maskCodesHidesRunsOfFourToEightDigitsOnly() {
        val found = HashSet<String>()
        assertEquals("a **** b **** c 123 d 123456789", GuardText.maskCodes("a 1234 b 12345678 c 123 d 123456789", found))
        assertEquals(setOf("1234", "12345678"), found)
    }

    @Test fun aPriceIsRecognisedWithCurrencyMarksAndSeparators() {
        for (p in listOf("₹250", "rs. 250.00", "250", "₹ 1,250", "inr 99", "$10.50", "250 only")) assertTrue(p, GuardText.isPriceOnly(p))
        for (p in listOf("", "pay 250", "250 off", "₹", "abc")) assertFalse(p, GuardText.isPriceOnly(p))
    }
}
