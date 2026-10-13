package com.swarmos.app.hands.android

import com.swarmos.app.hands.ScriptedBrain
import com.swarmos.app.hands.Tier
import com.swarmos.app.hands.readCards
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HandsDeskTest {
    private val cooking = "domain: cooking\nuse_for: Recipes and fixes\n---\nYou are a cooking expert.\nLimits: none."
    private val money = "domain: money_basics\nuse_for: Budgets\n---\nYou are a money guide."

    private fun desk(brain: ScriptedBrain, data: suspend (String) -> String? = { null }, cards: Map<String, String> = mapOf("cooking" to cooking, "money_basics" to money)) =
        HandsDesk(ExpertCards.parse(cards), brain, data)

    // ------------------------------------------------------------ the card parser

    @Test
    fun aCardGivesDomainUseForAndPrompt() {
        val c = ExpertCards.parse(mapOf("cooking" to cooking)).single()
        assertEquals("cooking", c.domain)
        assertEquals("Recipes and fixes", c.useFor)
        assertEquals("You are a cooking expert.\nLimits: none.", c.prompt)
    }

    @Test
    fun cardsAreReadInTheOrderOfTheirNames() {
        val list = ExpertCards.parse(mapOf("b" to money, "a" to cooking))
        assertEquals(listOf("cooking", "money_basics"), list.map { it.domain })
    }

    @Test
    fun windowsLineEndingsAndSpacesAroundTheColonAreFine() {
        val c = ExpertCards.parse(mapOf("x" to "domain :  tech_help \r\nuse_for:  Phones\r\n---\r\nBody line one.\r\nLine two.\r\n")).single()
        assertEquals("tech_help", c.domain)
        assertEquals("Phones", c.useFor)
        assertEquals("Body line one.\nLine two.", c.prompt)
    }

    @Test
    fun aCardWithoutTheSeparatorTheDomainOrThePromptIsLeftOut() {
        val list = ExpertCards.parse(
            mapOf(
                "nosep" to "domain: a\nuse_for: b\nYou are a.",
                "nodomain" to "use_for: b\n---\nYou are a.",
                "emptydomain" to "domain:   \n---\nYou are a.",
                "noprompt" to "domain: c\nuse_for: d\n---\n   \n",
                "ok" to cooking,
            ),
        )
        assertEquals(listOf("cooking"), list.map { it.domain })
    }

    @Test
    fun theFirstCardOfADomainWinsAndCaseDoesNotMatter() {
        val list = ExpertCards.parse(mapOf("a" to "domain: Cooking\n---\nFirst.", "b" to "domain: cooking\n---\nSecond."))
        assertEquals(1, list.size)
        assertEquals("First.", list.single().prompt)
    }

    @Test
    fun aLongPromptIsCut() {
        val c = ExpertCards.parse(mapOf("x" to "domain: x\n---\n" + "a".repeat(5000))).single()
        assertEquals(ExpertCards.PROMPT_MAX, c.prompt.length)
    }

    @Test
    fun theRealExpertCardsAllParse() {
        val files = readCards("experts")
        val cards = ExpertCards.parse(files)
        assertEquals("every file gives one card", files.size, cards.size)
        assertEquals(files.keys, cards.map { it.domain }.toSet())
        assertTrue(cards.all { it.useFor.isNotEmpty() && it.prompt.length > 40 })
    }

    // ------------------------------------------------------------ ask

    @Test
    fun domainsComeFromTheCards() {
        assertEquals(listOf("cooking", "money_basics"), desk(ScriptedBrain()).domains())
    }

    @Test
    fun anUnknownDomainGivesNullAndAsksNoModel() {
        val brain = ScriptedBrain()
        assertNull(runBlocking { desk(brain).ask("astrology", "Will it rain?") })
        assertTrue(brain.calls.isEmpty())
    }

    @Test
    fun aKnownDomainMakesOneStrongCompletionWithTheCardAsSystemPrompt() {
        val brain = ScriptedBrain().reply("""{"answer":"  Add salt later.  "}""")
        val answer = runBlocking { desk(brain).ask("Cooking", "How do I fix a salty dal?") }
        assertEquals("Add salt later.", answer)
        val c = brain.calls.single()
        assertEquals(Tier.STRONG, c.tier)
        assertEquals("You are a cooking expert.\nLimits: none.", c.system)
        assertEquals("How do I fix a salty dal?", c.user)
        assertEquals(300, c.maxTokens)
        assertFalse("an expert question is never marked as holding screen text", c.onPhoneOnly)
        assertEquals(HandsDesk.ANSWER_SCHEMA, c.schema)
    }

    @Test
    fun anyFailureGivesNull() {
        assertNull(runBlocking { desk(ScriptedBrain()).ask("cooking", "q") }) // the script has no reply: the brain throws
        assertNull(runBlocking { desk(ScriptedBrain().reply("not json at all")).ask("cooking", "q") })
        assertNull(runBlocking { desk(ScriptedBrain().reply("""{"answer":"   "}""")).ask("cooking", "q") })
        assertNull(runBlocking { desk(ScriptedBrain().reply("""{"other":"x"}""")).ask("cooking", "q") })
        assertNull(runBlocking { desk(ScriptedBrain().reply("""{"answer":42}""")).ask("cooking", "q") })
    }

    @Test
    fun withoutAStrongModelTheDeskDoesNotAsk() {
        val brain = ScriptedBrain(strong = false).reply("""{"answer":"x"}""")
        assertNull(runBlocking { desk(brain).ask("cooking", "q") })
        assertTrue(brain.calls.isEmpty())
    }

    @Test
    fun aCancelledAskStaysCancelled() {
        val brain = ScriptedBrain().replyWith({ throw CancellationException("stopped") })
        try {
            runBlocking { desk(brain).ask("cooking", "q") }
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("stopped", e.message)
        }
    }

    @Test
    fun aLongQuestionIsCutBeforeItGoesToTheModel() {
        val brain = ScriptedBrain().reply("""{"answer":"ok"}""")
        runBlocking { desk(brain).ask("cooking", "q".repeat(5000)) }
        assertEquals(HandsDesk.QUESTION_MAX, brain.calls.single().user.length)
    }

    // ------------------------------------------------------------ the answer and the Data Union

    @Test
    fun answerOfFindsTheFieldEvenInsideOtherText() {
        assertEquals("a", HandsDesk.answerOf("""Sure: {"answer":"a"} done"""))
        assertEquals("a", HandsDesk.answerOf("""{"answer":" a "}"""))
        assertNull(HandsDesk.answerOf(""))
        assertNull(HandsDesk.answerOf("{}"))
        assertNull(HandsDesk.answerOf("""{"answer":null}"""))
        assertEquals(HandsDesk.ANSWER_MAX, HandsDesk.answerOf("""{"answer":"${"z".repeat(4000)}"}""")!!.length)
    }

    @Test
    fun searchMyDataGivesTheDataUnionsAnswerTrimmed() {
        val asked = ArrayList<String>()
        val d = desk(ScriptedBrain(), data = { q -> asked += q; "  Your flight is at 9.  " })
        assertEquals("Your flight is at 9.", runBlocking { d.searchMyData("flight time") })
        assertEquals(listOf("flight time"), asked)
    }

    @Test
    fun searchMyDataGivesNullWhenThereIsNoAnswerOrItFails() {
        assertNull(runBlocking { desk(ScriptedBrain(), data = { null }).searchMyData("x") })
        assertNull(runBlocking { desk(ScriptedBrain(), data = { "   " }).searchMyData("x") })
        assertNull(runBlocking { desk(ScriptedBrain(), data = { throw IllegalStateException("not ready") }).searchMyData("x") })
    }

    @Test
    fun dataTextPrefersTheSavedAnswerAndNeedsAFinishedAsk() {
        assertEquals("saved", HandsDesk.dataText("done", "saved", "written"))
        assertEquals("written", HandsDesk.dataText("done", null, "written"))
        assertEquals("written", HandsDesk.dataText("done", "  ", "written"))
        assertNull("sources only: no chat model", HandsDesk.dataText("done", null, ""))
        assertNull(HandsDesk.dataText("answering", null, "half an ans"))
        assertNull(HandsDesk.dataText("searching", "saved", "written"))
    }
}
