package com.swarmos.app.choir

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChoirScreenLogicTest {
    // the real set order: 8 m, 4 p (all Math), 4 q (multiple choice), 4 c (code)
    private val problems = buildList {
        for (i in 1..12) add(Problem(id = if (i <= 8) "m$i" else "p${i - 8}", kind = Kind.MATH, text = "math $i"))
        for (i in 1..4) add(Problem(id = "q$i", kind = Kind.MCQ, text = "mcq $i"))
        for (i in 1..4) add(Problem(id = "c$i", kind = Kind.CODE, text = "code $i"))
    }

    @Test fun theListStartsWithFiveProblems() {
        val groups = ChoirScreenLogic.problemGroups(problems, expanded = false)
        assertEquals(1, groups.size)
        assertEquals(Kind.MATH, groups[0].kind)
        assertEquals(listOf("m1", "m2", "m3", "m4", "m5"), groups[0].items.map { it.id })
        assertEquals("the header still says how many of the kind exist", 12, groups[0].total)
        assertTrue(ChoirScreenLogic.canExpand(problems))
    }

    @Test fun showAllGroupsTheTwentyByKindInOrder() {
        val groups = ChoirScreenLogic.problemGroups(problems, expanded = true)
        assertEquals(listOf(Kind.MATH, Kind.MCQ, Kind.CODE), groups.map { it.kind })
        assertEquals(listOf(12, 4, 4), groups.map { it.items.size })
        assertEquals(listOf(12, 4, 4), groups.map { it.total })
        assertEquals(20, groups.sumOf { it.items.size })
        assertEquals(problems.map { it.id }, groups.flatMap { g -> g.items.map { it.id } })
    }

    @Test fun aShortListHasNothingToExpand() {
        val few = problems.take(5)
        assertFalse(ChoirScreenLogic.canExpand(few))
        assertEquals(5, ChoirScreenLogic.problemGroups(few, expanded = false).sumOf { it.items.size })
        assertTrue(ChoirScreenLogic.problemGroups(emptyList(), expanded = false).isEmpty())
    }

    @Test fun theSwitchReadsInPlainWords() {
        assertEquals("Check answers against each problem's own test", ChoirScreenLogic.TEST_SWITCH_LABEL)
        assertTrue(ChoirScreenLogic.testSwitchHelp(true).startsWith("On:"))
        assertTrue(ChoirScreenLogic.testSwitchHelp(false).startsWith("Off:"))
        for (w in listOf("tool", "steps are checked", "built-in")) {
            assertFalse(w, ChoirScreenLogic.TEST_SWITCH_LABEL.contains(w) || ChoirScreenLogic.testSwitchHelp(true).contains(w) || ChoirScreenLogic.testSwitchHelp(false).contains(w))
        }
    }

    @Test fun withNoOtherPhoneOnlineTheScreenSaysItRunsOnThisPhoneOnly() {
        assertEquals("No other phone is online, running on this phone only.", ChoirScreenLogic.soloNotice(0))
        assertNull(ChoirScreenLogic.soloNotice(1))
        assertNull(ChoirScreenLogic.soloNotice(3))
    }

    private fun row(id: String, answered: Int, total: Int = answered) = EvalRow(problemId = id, kind = Kind.MATH, group = "m", expected = "1", nodesAnswered = answered, nodesTotal = total)

    @Test fun aRunWhereNoOtherPhoneAnsweredIsASoloRun() {
        assertTrue(ChoirScreenLogic.isSoloRun(listOf(row("m1", 1), row("m2", 1))))
        assertTrue("an away phone is a lane but never answered", ChoirScreenLogic.isSoloRun(listOf(row("m1", 1, total = 2))))
        assertFalse(ChoirScreenLogic.isSoloRun(listOf(row("m1", 1), row("m2", 2))))
        assertFalse(ChoirScreenLogic.isSoloRun(emptyList()))
    }

    @Test fun thisPhonesRowShowsTheLiveModelWhenTheRegistryIsStaleOrEmpty() {
        assertEquals("Qwen3.5-4B-Q4_K_M", ChoirScreenLogic.selfModelLabel("FAKE", "Qwen3.5-4B-Q4_K_M"))
        assertEquals("Qwen3.5-4B-Q4_K_M", ChoirScreenLogic.selfModelLabel(null, "Qwen3.5-4B-Q4_K_M"))
        assertEquals("Qwen3.5-4B-Q4_K_M", ChoirScreenLogic.selfModelLabel("  ", "Qwen3.5-4B-Q4_K_M"))
        assertEquals("Gemma-3-4B", ChoirScreenLogic.selfModelLabel("Gemma-3-4B", "Qwen3.5-4B-Q4_K_M"))
        assertEquals("FAKE", ChoirScreenLogic.selfModelLabel(null, "FAKE"))
        assertEquals("model unknown", ChoirScreenLogic.selfModelLabel(null, ""))
    }
}
