package com.swarmos.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorGeometryTest {
    // PhoneNode: 34 dp token, 2 dp gap, a 16 dp caption line: the caption ends 36 dp below the token centre
    private val captionBottom = 36f
    private val halfWidth = 40f
    private val margin = 4f

    @Test
    fun aLineGoingStraightDownStartsBelowTheCaption() {
        val gap = clearanceBelowCaption(0f, 1f, captionBottom, halfWidth, margin)
        assertEquals(captionBottom + margin, gap, 0.001f)
    }

    @Test
    fun aSteepDiagonalStartsBelowTheCaptionAlongTheLine() {
        // 20 degrees off vertical: the line leaves the caption's bottom edge, which is further along the line
        val ux = Math.sin(Math.toRadians(20.0)).toFloat()
        val uy = Math.cos(Math.toRadians(20.0)).toFloat()
        val gap = clearanceBelowCaption(ux, uy, captionBottom, halfWidth, margin)
        assertEquals(captionBottom / uy + margin, gap, 0.001f)
        // the point where it starts is below the caption
        assertTrue(gap * uy >= captionBottom + margin * uy - 0.001f)
    }

    @Test
    fun aShallowLineLeavesThroughTheSideOfTheCaption() {
        val ux = Math.cos(Math.toRadians(70.0)).toFloat() // mostly sideways
        val uy = Math.sin(Math.toRadians(70.0)).toFloat() * 0.3f
        val len = Math.sqrt((ux * ux + uy * uy).toDouble()).toFloat()
        val gap = clearanceBelowCaption(ux / len, uy / len, captionBottom, halfWidth, margin)
        assertEquals(halfWidth / (ux / len) + margin, gap, 0.01f)
    }

    @Test
    fun linesLeavingUpwardOrSidewaysNeedNoCaptionClearance() {
        assertEquals(0f, clearanceBelowCaption(0f, -1f, captionBottom, halfWidth, margin), 0f)
        assertEquals(0f, clearanceBelowCaption(1f, 0f, captionBottom, halfWidth, margin), 0f)
    }

    @Test
    fun noCaptionMeansNoExtraClearance() {
        assertEquals(0f, clearanceBelowCaption(0f, 1f, 0f, halfWidth, margin), 0f)
    }

    @Test
    fun theWobbleIsVisibleOnANormalLineAndSmallerOnAShortOne() {
        assertEquals(3.2f, wobbleAmplitudeDp(240f), 0.001f)
        assertEquals(3.2f, wobbleAmplitudeDp(60f), 0.001f) // the 60 dp visible part of a short Swarm connector
        assertEquals(2.5f, wobbleAmplitudeDp(40f), 0.001f)
        assertEquals(1.8f, wobbleAmplitudeDp(10f), 0.001f)
    }
}
