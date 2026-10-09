package com.swarmos.app.pdu

import org.junit.Assert.assertEquals
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

class SnippetsTest {
    @Test fun shortTextIsReturnedWhole() {
        assertEquals("a b c", Snippets.best("a  b\nc", listOf("b")))
    }

    @Test fun neverLongerThan400() {
        val s = Snippets.best("lorem ipsum ".repeat(300), listOf("ipsum"))
        assertTrue(s.length <= 400)
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
