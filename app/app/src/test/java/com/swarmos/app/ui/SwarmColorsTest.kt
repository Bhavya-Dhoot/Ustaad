package com.swarmos.app.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwarmColorsTest {
    @Test
    fun markerMapsPhoneNamesToTheirMarkerColour() {
        assertEquals(Color(0xFF1E9BB0), SwarmColors.marker("Phone A"))
        assertEquals(Color(0xFFE08A3C), SwarmColors.marker("Phone B"))
        assertEquals(Color(0xFFC8457E), SwarmColors.marker("Phone C"))
    }

    @Test
    fun markerIsCaseAndSpaceTolerant() {
        assertEquals(SwarmColors.markerA, SwarmColors.marker("phone a"))
        assertEquals(SwarmColors.markerB, SwarmColors.marker("  Phone B  "))
        assertEquals(SwarmColors.markerC, SwarmColors.marker("C"))
    }

    @Test
    fun markerIsGraphiteForAnythingElse() {
        assertEquals(SwarmColors.graphite, SwarmColors.marker("Phone D"))
        assertEquals(SwarmColors.graphite, SwarmColors.marker("Phone 3F2A")) // generated default name ends in a letter but is not "A"
        assertEquals(SwarmColors.graphite, SwarmColors.marker("Baseline"))
        assertEquals(SwarmColors.graphite, SwarmColors.marker(""))
    }

    @Test
    fun phoneLetterOnlyForStandaloneTrailingLetter() {
        assertEquals('A', phoneLetter("Phone A"))
        assertNull(phoneLetter("Phone AB"))
        assertNull(phoneLetter("Phone"))
    }

    @Test
    fun paletteMatchesDesignDocHex() {
        assertEquals(Color(0xFFF6F7F5), SwarmColors.whiteboard)
        assertEquals(Color(0xFFFCFDFB), SwarmColors.sheet)
        assertEquals(Color(0xFF15171A), SwarmColors.ink)
        assertEquals(Color(0xFF5E636B), SwarmColors.graphite)
        assertEquals(Color(0xFFE4E7E3), SwarmColors.smudge)
    }

    @Test
    fun tintIsTwelvePercentOfMarkerOverWhiteboard() {
        val t = SwarmColors.tint("Phone A")
        assertEquals(1f, t.alpha, 0f)
        // lighter than the marker, close to the whiteboard
        assertTrue(t.green > SwarmColors.markerA.green)
        assertNotEquals(SwarmColors.whiteboard, t)
    }

    @Test
    fun jitterIsDeterministicAndBounded() {
        val a = Jitter(7)
        val b = Jitter(7)
        repeat(1000) {
            val x = a.next()
            assertEquals(x, b.next(), 0f)
            assertTrue(x >= -1f && x < 1f)
        }
        assertNotEquals(Jitter(7).next(), Jitter(8).next())
    }

    @Test
    fun wobbleKeepsEndPointsExactAndRepeats() {
        val from = Offset(10f, 20f)
        val to = Offset(310f, 120f)
        val first = wobblePoints(from, to, 6f, 40f, Jitter(3))
        val second = wobblePoints(from, to, 6f, 40f, Jitter(3))
        assertEquals(first, second)
        assertEquals(from, first.first())
        assertEquals(to, first.last())
        assertTrue(first.size >= 3)
    }

    @Test
    fun ovalOvershootsItsStartAndRepeats() {
        val first = ovalPoints(100f, 50f, 80f, 30f, Jitter(5))
        assertEquals(first, ovalPoints(100f, 50f, 80f, 30f, Jitter(5)))
        // it overshoots by about 8 %, so the last point is near but not at the first
        val gap = (first.last() - first.first()).getDistance()
        assertTrue(gap > 0f && gap < 40f)
    }
}
