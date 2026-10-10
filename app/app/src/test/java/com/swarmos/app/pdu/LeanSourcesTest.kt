package com.swarmos.app.pdu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Follow-up 1: the lean gate on the sources given to the model (behind the pref `pdu_lean_sources`, off by default). */
class LeanSourcesTest {
    private fun src(n: Int, score: Double, snippet: String = "text of source $n", kind: String = "text", device: String = "Phone A") =
        MergedHit("d", device, false, PduHit("title $n", "h$n", kind, 0, snippet, score, n), 0.0)

    private fun numbers(r: List<IndexedValue<MergedHit>>) = r.map { it.index + 1 }

    @Test fun aTwoFileQuestionKeepsBothFiles() {
        // scores of a real two-file question: 0.586 and 0.552 (docs: the warranty bill and the extended warranty certificate)
        val sources = listOf(src(1, 0.586), src(2, 0.552))
        assertEquals(listOf(1, 2), numbers(PromptBuilder.leanSelect(sources)))
    }

    @Test fun oneRelevantSourceOutOfSixKeepsOnlyThatOne() {
        val sources = listOf(src(1, 0.80), src(2, 0.30), src(3, 0.25), src(4, 0.20), src(5, 0.20), src(6, 0.10))
        assertEquals(listOf(1), numbers(PromptBuilder.leanSelect(sources)))
    }

    @Test fun theBoundaryIsSixTenthsOfTheTopScoreInclusive() {
        val sources = listOf(src(1, 1.0), src(2, 0.6), src(3, 0.59))
        assertEquals(listOf(1, 2), numbers(PromptBuilder.leanSelect(sources)))
    }

    @Test fun aSourceWithNoSnippetIsDroppedAndDoesNotSetTheBar() {
        val sources = listOf(src(1, 0.95, snippet = ""), src(2, 0.50), src(3, 0.45))
        assertEquals(listOf(2, 3), numbers(PromptBuilder.leanSelect(sources)))
    }

    @Test fun atMostFourSourcesAreKeptInTheirOriginalOrder() {
        val sources = (1..6).map { src(it, 0.9 - it * 0.01) }
        assertEquals(listOf(1, 2, 3, 4), numbers(PromptBuilder.leanSelect(sources)))
    }

    @Test fun savedAnswersCountTowardsTheFour() {
        val sources = listOf(src(1, 0.9), src(2, 0.85), src(3, 0.8), src(4, 0.8, kind = "kb"), src(5, 0.75, kind = "kb"))
        assertEquals(listOf(1, 2, 3, 4), numbers(PromptBuilder.leanSelect(sources)))
    }

    @Test fun aSavedAnswerIsCutToTheSnippetMaximum() {
        val card = src(1, 0.9, snippet = "word ".repeat(200), kind = "kb")
        val kept = PromptBuilder.leanSelect(listOf(card)).single().value.hit.snippet
        assertTrue(kept.length <= Snippets.MAX)
        assertTrue(kept.endsWith("…"))
    }

    @Test fun nothingUsableMeansNothingIsGiven() {
        assertTrue(PromptBuilder.leanSelect(emptyList()).isEmpty())
        assertTrue(PromptBuilder.leanSelect(listOf(src(1, 0.9, snippet = " "))).isEmpty())
    }

    @Test fun theNumbersPrintedForTheModelStayTheOriginalOnes() {
        val sources = listOf(src(1, 0.80), src(2, 0.10), src(3, 0.75), src(4, 0.05))
        val text = PromptBuilder.build("q?", sources, lean = true).messages[1].content
        assertTrue(text, text.contains("[1] Phone A"))
        assertTrue(text, text.contains("[3] Phone A"))
        assertFalse(text, text.contains("[2] "))
        assertFalse(text, text.contains("[4] "))
    }

    @Test fun withTheSwitchOffTheRequestIsExactlyWhatItWas() {
        val sources = listOf(src(1, 0.80), src(2, 0.10), src(3, 0.75))
        val off = PromptBuilder.build("q?", sources)
        assertEquals(off, PromptBuilder.build("q?", sources, lean = false))
        val body = sources.mapIndexed { i, m -> PromptBuilder.sourceLine(i + 1, m.deviceName, m.hit) }.joinToString("\n")
        assertEquals("Sources:\n$body\n\nQuestion: q?", off.messages[1].content)
        assertEquals(160, off.maxTokens)
    }
}
