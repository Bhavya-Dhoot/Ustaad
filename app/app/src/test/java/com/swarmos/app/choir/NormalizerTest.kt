package com.swarmos.app.choir

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NormalizerTest {
    @Test fun numbersStripUnitsCommasAndCurrency() {
        assertEquals(1234.5, Normalizer.parseNumber("Rs. 1,234.50")!!, 1e-9)
        assertEquals(3654.0, Normalizer.parseNumber("3654 rupees")!!, 1e-9)
        assertEquals(40.0, Normalizer.parseNumber("\$40")!!, 1e-9)
        assertEquals(80000.0, Normalizer.parseNumber("Rs 80,000")!!, 1e-9)
        assertEquals(-5.0, Normalizer.parseNumber("−5")!!, 1e-9)
        assertEquals(15.0, Normalizer.parseNumber("15%")!!, 1e-9)
        assertEquals(12.0, Normalizer.parseNumber("x = 12")!!, 1e-9)
        assertEquals(0.75, Normalizer.parseNumber("3/4")!!, 1e-9)
        assertEquals(164.5, Normalizer.parseNumber("164.5 rupees left")!!, 1e-9)
        assertNull(Normalizer.parseNumber("none"))
        assertNull(Normalizer.parseNumber(""))
    }

    @Test fun numbersCompareWithTolerance() {
        assertTrue(Normalizer.same(Kind.MATH, "80000", "80,000.00"))
        assertTrue(Normalizer.same(Kind.MATH, "33.33", "33.333"))
        assertFalse(Normalizer.same(Kind.MATH, "76128", "80000"))
        assertFalse(Normalizer.same(Kind.MATH, "33.3", "33.34"))
    }

    @Test fun mcqLetters() {
        assertEquals('B', Normalizer.mcqLetter("B"))
        assertEquals('B', Normalizer.mcqLetter(" b) "))
        assertEquals('C', Normalizer.mcqLetter("(C) Chitra"))
        assertEquals('D', Normalizer.mcqLetter("Option D"))
        assertEquals('A', Normalizer.mcqLetter("The answer is A"))
        assertNull(Normalizer.mcqLetter("nothing here"))
        assertTrue(Normalizer.same(Kind.MCQ, "b", "B) Box 2"))
        assertFalse(Normalizer.same(Kind.MCQ, "B", "C"))
    }

    @Test fun textIsLowercasedAndTrimmed() {
        assertTrue(Normalizer.same(Kind.TEXT, "  The  Cat Sat. ", "the cat sat"))
        assertFalse(Normalizer.same(Kind.TEXT, "the cat", "the cats"))
    }

    @Test fun codeOutputIgnoresWhitespaceAndNumberFormat() {
        assertTrue(Normalizer.same(Kind.CODE, "[1, 2, 3]", "[1,2,3]"))
        assertTrue(Normalizer.same(Kind.CODE, "4  13", "4 13"))
        assertTrue(Normalizer.same(Kind.CODE, "37.0", "37"))
        assertFalse(Normalizer.same(Kind.CODE, "mra", "mrw"))
    }

    @Test fun clusterGroupsEqualAnswersInOrderOfFirstAppearance() {
        val g = Normalizer.cluster(Kind.MATH, listOf("36", "40", "40.0", "Rs 36"))
        assertEquals(listOf(listOf(0, 3), listOf(1, 2)), g)
    }
}
