package com.swarmos.app.pdu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Entities out of the text of a chunk: what is found, and that two spellings of one thing are one entity. */
class GraphExtractTest {
    private fun keys(text: String, type: String) = GraphExtract.entities(text).filter { it.type == type }.map { it.key }
    private val rupee = "₹"

    // ---- dates -------------------------------------------------------------------------------------------------

    @Test fun aDateInEveryCommonSpellingIsOneEntity() {
        val spellings = listOf(
            "13 Mar 2026", "13 March 2026", "13th March, 2026", "March 13, 2026", "13/03/2026", "13-03-2026", "13.03.2026", "2026-03-13", "03/13/2026",
        )
        for (s in spellings) assertEquals(s, listOf("2026-03-13"), keys("paid on $s by cheque", EntityType.DATE))
    }

    @Test fun aDateThatDoesNotExistIsNotAnEntity() {
        assertTrue(keys("on 31/02/2026 and 32 Mar 2026 and 2026-13-01", EntityType.DATE).isEmpty())
    }

    @Test fun twoDatesInOneChunkAreTwoEntities() {
        assertEquals(listOf("2026-03-13", "2026-04-01"), keys("from 13 Mar 2026 to 1 April 2026", EntityType.DATE))
    }

    // ---- amounts -----------------------------------------------------------------------------------------------

    @Test fun anAmountInEverySpellingOfRupeesIsOneEntity() {
        for (s in listOf("Rs 1,200", "Rs. 1,200", "Rs.1200/-", "INR 1200", "inr 1,200.00", "${rupee}1,200", "1200 INR", "1,200 rupees")) {
            assertEquals(s, listOf("INR:1200.00"), keys("total $s only", EntityType.AMOUNT))
        }
    }

    @Test fun dollarsAreAnotherCurrency() {
        for (s in listOf("$12.50", "USD 12.50", "US$ 12.5", "12.50 dollars")) assertEquals(s, listOf("USD:12.50"), keys("fee $s", EntityType.AMOUNT))
    }

    @Test fun indianGroupingIsReadAndAWordEndingInRsIsNotACurrency() {
        assertEquals(listOf("INR:950000.00"), keys("salary Rs 9,50,000", EntityType.AMOUNT))
        assertTrue(keys("Mars 500 and Hours 20", EntityType.AMOUNT).isEmpty())
    }

    @Test fun anAmountIsShownFromItsKeyAlone() {
        assertEquals("$rupee" + "1,200", GraphExtract.display(Mention(EntityType.AMOUNT, "INR:1200.00")))
        assertEquals("${rupee}1,200.50", GraphExtract.display(Mention(EntityType.AMOUNT, "INR:1200.50")))
        assertEquals("$12.50", GraphExtract.display(Mention(EntityType.AMOUNT, "USD:12.50")))
        assertEquals("13 Mar 2026", GraphExtract.display(Mention(EntityType.DATE, "2026-03-13")))
    }

    // ---- e-mail, phone ------------------------------------------------------------------------------------------

    @Test fun anEmailAddressIsLowerCased() {
        assertEquals(listOf("priya.sharma@example.com", "x@y.in"), keys("Mail Priya.Sharma@Example.COM or x@y.in please", EntityType.EMAIL))
    }

    @Test fun anIndianPhoneNumberInEverySpellingIsOneEntity() {
        for (s in listOf("+91 98765 43210", "+91-98765-43210", "+919876543210", "098765 43210", "98765 43210", "9876543210")) {
            assertEquals(s, listOf("+919876543210"), keys("call $s today", EntityType.PHONE))
        }
    }

    @Test fun internationalNumbersKeepTheirOwnCountryCode() {
        assertEquals(listOf("+442079460958"), keys("London +44 20 7946 0958", EntityType.PHONE))
        assertEquals(listOf("+14155552671"), keys("US +1 (415) 555-2671", EntityType.PHONE))
    }

    @Test fun aPhoneNumberIsNotReadAsAnAmountAndAnAmountIsNotReadAsAPhoneNumber() {
        assertTrue(keys("fee Rs 9876543210", EntityType.PHONE).isEmpty())
        assertEquals(listOf("INR:9876543210.00"), keys("fee Rs 9876543210", EntityType.AMOUNT))
        assertTrue(keys("call 9876543210", EntityType.AMOUNT).isEmpty())
    }

    // ---- identifiers --------------------------------------------------------------------------------------------

    @Test fun panAndGstinShapedCodes() {
        val found = GraphExtract.entities("PAN ABCDE1234F, GSTIN 27ABCDE1234F1Z5").filter { it.type == EntityType.ID }.map { it.key }
        assertEquals(listOf("gstin:27ABCDE1234F1Z5", "pan:ABCDE1234F"), found.sorted()) // the PAN inside the GSTIN is not read a second time
    }

    @Test fun anInvoiceNumberInEverySpellingIsOneEntity() {
        for (s in listOf("Invoice No. INV-2026-001", "invoice INV2026001", "INVOICE NUMBER: inv-2026-001", "Invoice # INV2026001")) {
            assertEquals(s, listOf("invoice:INV2026001"), keys(s, EntityType.ID))
        }
    }

    @Test fun policyAndOrderNumbers() {
        assertEquals(listOf("policy:LIC12345678"), keys("Policy No: LIC-12345678", EntityType.ID))
        assertEquals(listOf("order:OD123456789"), keys("Order ID: OD123456789", EntityType.ID))
        assertEquals(listOf("order:4521"), keys("Order #4521", EntityType.ID))
    }

    @Test fun aKeywordWithNoNumberAfterItIsNotAnIdentifier() {
        assertTrue(keys("The order of the court, policy holder, invoice dated", EntityType.ID).isEmpty())
    }

    @Test fun aDateAfterAKeywordIsADateNotAnIdentifier() {
        assertEquals(listOf("2026-03-13"), keys("invoice number 13/03/2026", EntityType.DATE))
        assertTrue(keys("invoice number 13/03/2026", EntityType.ID).isEmpty())
    }

    // ---- names ---------------------------------------------------------------------------------------------------

    @Test fun peopleAreCapitalisedWordRuns() {
        assertEquals(listOf("priya sharma", "rahul verma"), keys("Priya Sharma paid Rahul Verma yesterday", EntityType.PERSON))
    }

    @Test fun aTitleMakesASingleWordAName() {
        assertEquals(listOf("sharma"), keys("Dear Mr. Sharma, your payment", EntityType.PERSON))
        assertEquals(listOf("priya sharma"), keys("Dr. Priya Sharma", EntityType.PERSON))
    }

    @Test fun initialsAreKept() {
        assertEquals(listOf("r k narayan"), keys("by R. K. Narayan", EntityType.PERSON))
    }

    @Test fun organisationsAreToldFromPeople() {
        assertEquals(listOf("reliance industries ltd"), keys("Invoice from Reliance Industries Ltd", EntityType.ORG))
        assertEquals(listOf("bank of india"), keys("account with Bank of India", EntityType.ORG))
        assertEquals(listOf("hdfc bank"), keys("HDFC Bank statement", EntityType.ORG))
        assertTrue(keys("Reliance Industries Ltd", EntityType.PERSON).isEmpty())
    }

    @Test fun headingsAndSentenceStartsAreNotNames() {
        val none = listOf("Total Amount Paid: 5", "TAX INVOICE", "Thank you Priya", "Priya", "Thanks and Regards", "March Invoice", "Monday Report")
        for (s in none) assertTrue(s, GraphExtract.entities(s).none { it.type == EntityType.PERSON || it.type == EntityType.ORG })
    }

    @Test fun aNameDoesNotRunAcrossALineOrAComma() {
        assertTrue(keys("Priya\nSharma", EntityType.PERSON).isEmpty())
        assertEquals(listOf("priya sharma"), keys("Priya Sharma, Mumbai", EntityType.PERSON))
    }

    @Test fun aNameIsShownFromItsKeyAlone() {
        assertEquals("Priya Sharma", GraphExtract.display(Mention(EntityType.PERSON, "priya sharma")))
        assertEquals("Bank of India", GraphExtract.display(Mention(EntityType.ORG, "bank of india")))
        assertEquals("PAN ABCDE1234F", GraphExtract.display(Mention(EntityType.ID, "pan:ABCDE1234F")))
        assertEquals("Invoice INV2026001", GraphExtract.display(Mention(EntityType.ID, "invoice:INV2026001")))
    }

    // ---- limits --------------------------------------------------------------------------------------------------

    @Test fun aChunkNamesAtMostTwentyFourEntities() {
        val dates = (1..28).joinToString(" ") { "%02d/03/2026".format(it) } + " " + (1..12).joinToString(" ") { "%02d/04/2026".format(it) }
        assertEquals(GraphExtract.MAX_PER_CHUNK, GraphExtract.entities(dates).size)
    }

    @Test fun textWithNothingInItNamesNothing() {
        assertTrue(GraphExtract.entities("").isEmpty())
        assertTrue(GraphExtract.entities("the quick brown fox jumps over the lazy dog 42 times").isEmpty())
    }

    @Test fun aLongTextOfOddCharactersIsReadInReasonableTime() {
        val odd = "+9 ".repeat(1_500) + "A.".repeat(1_500) + "Rs".repeat(1_500) + "@".repeat(500)
        val t0 = System.nanoTime()
        GraphExtract.entities(odd)
        assertTrue("took ${(System.nanoTime() - t0) / 1_000_000} ms", (System.nanoTime() - t0) / 1_000_000 < 3_000)
    }
}
