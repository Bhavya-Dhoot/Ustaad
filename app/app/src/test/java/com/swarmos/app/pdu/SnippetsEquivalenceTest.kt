package com.swarmos.app.pdu

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/** Follow-up 3c: counting term hits with a loop instead of a substring per window must give exactly the same snippet. */
class SnippetsEquivalenceTest {
    /** The implementation before the change, kept here as the reference. */
    private fun reference(text: String, terms: Collection<String>, maxLen: Int = Snippets.MAX): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        if (flat.length <= maxLen) return flat
        val lower = flat.lowercase()
        val budget = maxLen - 2
        var bestStart = 0
        var bestScore = -1
        var s = 0
        while (s < flat.length) {
            val e = minOf(s + budget, flat.length)
            val window = lower.substring(s, e)
            val score = terms.count { it in window } * 1000 + terms.sumOf { t -> window.windowed(t.length).count { it == t } }
            if (score > bestScore) { bestScore = score; bestStart = s }
            if (e == flat.length) break
            s = nextWordStart(flat, s + 40)
        }
        var end = minOf(bestStart + budget, flat.length)
        if (end < flat.length) {
            val sp = flat.lastIndexOf(' ', end)
            if (sp > bestStart) end = sp
        }
        val body = flat.substring(bestStart, end).trim()
        return (if (bestStart > 0) "…" else "") + body + (if (end < flat.length) "…" else "")
    }

    private fun nextWordStart(t: String, from: Int): Int {
        var i = from
        while (i < t.length && !t[i].isWhitespace()) i++
        while (i < t.length && t[i].isWhitespace()) i++
        return i
    }

    @Test fun randomTextsAndTermsGiveTheSameSnippetAsBefore() {
        val r = Random(20261010)
        val words = listOf("bill", "laptop", "warranty", "aa", "aaa", "electricity", "due", "the", "of", "रसीद", "a", "ab", "ba", "abab")
        repeat(400) {
            val n = r.nextInt(30, 400)
            val text = buildString { repeat(n) { append(words[r.nextInt(words.size)]); append(if (r.nextInt(7) == 0) "\n" else " ") } }
            val terms = List(r.nextInt(0, 5)) { words[r.nextInt(words.size)].lowercase() }.distinct()
            val maxLen = listOf(120, 400, 40 + r.nextInt(300))[r.nextInt(3)]
            assertEquals("terms $terms maxLen $maxLen", reference(text, terms, maxLen), Snippets.best(text, terms, maxLen))
        }
    }

    @Test fun overlappingOccurrencesAreCountedLikeBefore() {
        val text = "aaaa aaaa ".repeat(80) + "needle needle needle " + "zz ".repeat(200)
        for (terms in listOf(listOf("aa"), listOf("aaa", "needle"), listOf("needle", "zz"), listOf("ee"))) {
            assertEquals("terms $terms", reference(text, terms), Snippets.best(text, terms))
        }
    }

    @Test fun aTermLongerThanTheWindowIsHandled() {
        val text = "word ".repeat(200)
        assertEquals(reference(text, listOf("x".repeat(500))), Snippets.best(text, listOf("x".repeat(500))))
    }
}
