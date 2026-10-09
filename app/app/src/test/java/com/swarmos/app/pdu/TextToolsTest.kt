package com.swarmos.app.pdu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class QueryTermsTest {
    @Test fun stopwordsAreDropped() {
        assertEquals(listOf("warranty", "bill", "laptop"), QueryTerms.terms("Where is the warranty bill for my laptop?"))
    }

    @Test fun ftsExpressionQuotesEveryTerm() {
        assertEquals("\"warranty\" OR \"bill\"", QueryTerms.ftsMatch(listOf("warranty", "bill")))
        assertNull(QueryTerms.ftsMatch(emptyList()))
    }

    @Test fun punctuationCannotBreakTheExpression() {
        val t = QueryTerms.terms("what's \"AND\" (salary) -slip* NEAR/2")
        assertTrue(t.all { it.all { c -> c.isLetterOrDigit() } })
    }

    @Test fun devanagariWordsSurvive() {
        assertEquals(listOf("बिजली", "बिल"), QueryTerms.terms("बिजली बिल"))
    }
}

class OcrMergeTest {
    @Test fun shortLatinResultTriggersTheSecondPass() {
        assertTrue(OcrMerge.needsDevanagari("Meeting 28-Oct 5 pm"))
        assertTrue(OcrMerge.needsDevanagari(""))
        assertFalse(OcrMerge.needsDevanagari("word ".repeat(60)))
    }

    @Test fun devanagariLinesAreAppendedAndKnownLinesDropped() {
        val latin = "Meeting 28-Oct 5 pm\nSYNTHETIC DEMO DATA"
        val deva = "दूध वाले को 1200 रुपये देने हैं\nMeeting 28-Oct 5 pm\nसीमा जी को फोन करना है\nsynthetic demo data"
        assertEquals(
            "Meeting 28-Oct 5 pm\nSYNTHETIC DEMO DATA\nदूध वाले को 1200 रुपये देने हैं\nसीमा जी को फोन करना है",
            OcrMerge.merge(latin, deva),
        )
    }

    @Test fun latinLinesReReadBySecondPassAreNotAppended() {
        val latin = "Due date 12-Oct-2 026\nBill amount INR 599.00"
        val deva = "Due date 12-Oct-2026\nBill amount INR 599.00\nबिल की राशि 599 रुपये"
        assertEquals(latin + "\nबिल की राशि 599 रुपये", OcrMerge.merge(latin, deva))
    }

    @Test fun nothingFromTheSecondPassKeepsTheLatinText() {
        assertEquals("abc", OcrMerge.merge("abc", null))
        assertEquals("abc", OcrMerge.merge("abc", "  \n "))
        assertEquals("abc def", OcrMerge.merge("abc def", "ABC DEF"))
    }

    @Test fun hindiQuestionKeepsItsContentWords() {
        assertEquals(listOf("दूध", "वाले", "कितने", "रुपये", "देने"), QueryTerms.terms("दूध वाले को कितने रुपये देने हैं"))
    }
}

class CoverageTest {
    private val terms = listOf("warranty", "bill", "laptop")

    @Test fun fullAndPartialCoverage() {
        assertEquals(1.0, Coverage.of(terms, "Laptop WARRANTY bill, keep it"), 1e-12)
        assertEquals(1.0 / 3, Coverage.of(terms, "Electricity bill September"), 1e-12)
        assertEquals(0.0, Coverage.of(terms, "Yoga class schedule"), 1e-12)
    }

    @Test fun stemVariantsCount() {
        assertEquals(1.0, Coverage.of(terms, "billing for laptops under warranties"), 1e-12)
    }

    @Test fun shortWordsNeedAnExactMatch() {
        assertEquals(0.0, Coverage.of(listOf("tax"), "taxi receipt"), 1e-12)
    }

    @Test fun noTermsNoCoverage() {
        assertEquals(0.0, Coverage.of(emptyList(), "anything"), 1e-12)
    }
}

class SnippetsTest {
    @Test fun shortTextIsReturnedWhole() {
        assertEquals("a b c", Snippets.best("a  b\nc", listOf("b")))
    }

    @Test fun neverLongerThan400() {
        val s = Snippets.best("lorem ipsum ".repeat(300), listOf("ipsum"))
        assertTrue(s.length <= 400)
    }

    @Test fun denserWindowWinsOverEarlierWindow() {
        val head = "warranty bill laptop " + "filler words ".repeat(60)
        val tail = "warranty valid until 13-Mar-2026 warranty claims need the bill for the laptop. " + "z".repeat(1)
        val s = Snippets.best(head + "padding ".repeat(80) + tail, listOf("warranty", "bill", "laptop"))
        assertTrue(s, s.contains("valid until 13-Mar-2026"))
    }

    @Test fun windowFollowsTheQueryTerms() {
        val text = "filler words ".repeat(120) + "EXPIRY DATE 13 March 2028 policy number EW-2025-004417 " + "more filler ".repeat(120)
        val s = Snippets.best(text, listOf("expiry", "policy"))
        assertTrue(s, s.contains("EXPIRY DATE 13 March 2028"))
        assertTrue(s.length <= 400)
    }
}

class DocxTextTest {
    private fun docx(xml: String): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("[Content_Types].xml")); z.write("<x/>".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("word/document.xml")); z.write(xml.toByteArray()); z.closeEntry()
        }
        return bos.toByteArray()
    }

    @Test fun runsAndParagraphsBecomeText() {
        val xml = """<w:document><w:body><w:p><w:r><w:t>Rent &amp; deposit</w:t></w:r></w:p>""" +
            """<w:p><w:r><w:t xml:space="preserve">Monthly </w:t></w:r><w:r><w:t>INR 18,500</w:t></w:r></w:p></w:body></w:document>"""
        assertEquals("Rent & deposit\nMonthly INR 18,500", DocxText.extract(ByteArrayInputStream(docx(xml))))
    }

    @Test fun missingDocumentXmlGivesEmpty() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> z.putNextEntry(ZipEntry("other.xml")); z.write("x".toByteArray()); z.closeEntry() }
        assertEquals("", DocxText.extract(ByteArrayInputStream(bos.toByteArray())))
    }

    @Test fun numericEntitiesAreDecoded() {
        assertEquals("café ₹", DocxText.fromXml("<w:t>caf&#233; &#x20B9;</w:t>"))
    }
}
