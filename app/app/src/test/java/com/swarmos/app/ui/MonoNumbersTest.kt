package com.swarmos.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonoNumbersTest {
    private fun monoRuns(text: String): List<String> {
        val a = monoNumbers(text)
        // adjacent spans are one number, so merge touching ranges before reading them back
        val merged = ArrayList<IntRange>()
        for (s in a.spanStyles.sortedBy { it.start }) {
            val last = merged.lastOrNull()
            if (last != null && last.last + 1 == s.start) merged[merged.size - 1] = last.first until s.end
            else merged.add(s.start until s.end)
        }
        return merged.map { a.text.substring(it) }
    }

    @Test
    fun textIsNeverChanged() {
        val t = "Run all 20 problems in 8.4 s at 79%, Rs 62,400"
        assertEquals(t, monoNumbers(t).text)
    }

    @Test
    fun numbersWithSeparatorsAreOneRun() {
        assertEquals(listOf("20"), monoRuns("Run all 20"))
        assertEquals(listOf("62,400", "22%"), monoRuns("Rs 62,400 after 22%"))
        assertEquals(listOf("8.4", "10:30"), monoRuns("8.4 tok/s at 10:30"))
    }

    @Test
    fun textWithoutNumbersHasNoSpans() {
        assertTrue(monoNumbers("Host a swarm").spanStyles.isEmpty())
    }
}
