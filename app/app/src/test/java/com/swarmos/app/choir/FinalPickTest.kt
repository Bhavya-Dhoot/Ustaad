package com.swarmos.app.choir

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinalPickTest {
    private fun v(node: String, ans: String, conf: Double = 0.8, check: CheckResult = CheckResult.NOT_CHECKABLE) =
        Vote(node, ans, conf, check)

    private fun decide(votes: List<Vote>, judge: Int? = null, tool: Boolean = true, total: Int = votes.size) =
        FinalPick.decide(Kind.MATH, votes, judge, tool, total)

    @Test fun unanimousButUnverifiedWhenNothingIsCheckable() {
        val f = decide(listOf(v("A", "42"), v("B", "42"), v("C", "42.0")))
        assertEquals("42", f.answer)
        assertEquals(PickMethod.MAJORITY, f.method)
        assertEquals("unanimous but unverified", f.label)
        assertFalse(f.verified)
    }

    @Test fun majorityTwoOfThree() {
        val f = decide(listOf(v("A", "36"), v("B", "40"), v("C", "40")))
        assertEquals("40", f.answer)
        assertEquals(PickMethod.MAJORITY, f.method)
        assertEquals("majority 2/3", f.label)
    }

    @Test fun aSingleToolVerifiedAnswerBeatsTheMajority() {
        val f = decide(listOf(
            v("A", "76128", check = CheckResult.REFUTED),
            v("B", "76128", check = CheckResult.NOT_CHECKABLE),
            v("C", "80000", check = CheckResult.VERIFIED),
        ))
        assertEquals("80000", f.answer)
        assertEquals(PickMethod.TOOL, f.method)
        assertTrue(f.verified)
    }

    @Test fun withoutTheToolTheMajorityWins() {
        val votes = listOf(
            v("A", "76128", check = CheckResult.REFUTED),
            v("B", "76128"),
            v("C", "80000", check = CheckResult.VERIFIED),
        )
        val f = decide(votes, tool = false)
        assertEquals("76128", f.answer)
        assertEquals(PickMethod.MAJORITY, f.method)
        assertFalse(f.verified)
    }

    @Test fun refutedAnswersAreEliminatedWhenOneAnswerIsLeft() {
        val f = decide(listOf(
            v("A", "1", check = CheckResult.REFUTED),
            v("B", "2"),
            v("C", "1", check = CheckResult.REFUTED),
        ))
        assertEquals("2", f.answer)
        assertEquals(PickMethod.TOOL, f.method)
        assertFalse(f.verified)
    }

    @Test fun unanimousButRefutedIsLabelled() {
        val f = decide(listOf(v("A", "5", check = CheckResult.REFUTED), v("B", "5", check = CheckResult.REFUTED)))
        assertEquals("5", f.answer)
        assertEquals("unanimous but refuted by the tool", f.label)
    }

    @Test fun twoVerifiedAnswersFallBackToMajorityAmongThem() {
        val f = decide(listOf(
            v("A", "90", check = CheckResult.VERIFIED),
            v("B", "100", check = CheckResult.VERIFIED),
            v("C", "90", check = CheckResult.VERIFIED),
        ))
        assertEquals("90", f.answer)
        assertTrue(f.verified)
    }

    @Test fun confidenceDecidesOnlyWithAClearLead() {
        val clear = decide(listOf(v("A", "1", 0.95), v("B", "2", 0.5), v("C", "3", 0.5)))
        assertEquals("1", clear.answer)
        assertEquals(PickMethod.CONFIDENCE, clear.method)

        val noLead = decide(listOf(v("A", "1", 0.9), v("B", "2", 0.85), v("C", "3", 0.9)), judge = null)
        assertEquals(PickMethod.ABSTAIN, noLead.method)
        assertNull(noLead.answer)
    }

    @Test fun summedConfidenceCountsEveryMemberOfAGroup() {
        // two nodes at 0.6 each (1.2 in total) against one at 0.9 and one at 0.5: no majority (2 of 4 is not more than half)
        val f = decide(listOf(v("A", "1", 0.6), v("B", "1", 0.6), v("C", "2", 0.9), v("D", "3", 0.5)))
        assertEquals("1", f.answer)
        assertEquals(PickMethod.CONFIDENCE, f.method)
    }

    @Test fun judgeBreaksAnExactTie() {
        val votes = listOf(v("A", "1", 0.8), v("B", "2", 0.8), v("C", "3", 0.8))
        val f = decide(votes, judge = 1)
        assertEquals("2", f.answer)
        assertEquals(PickMethod.JUDGE, f.method)
        assertEquals(PickMethod.ABSTAIN, decide(votes, judge = 7).method) // out-of-range judge pick is ignored
    }

    @Test fun judgePickOfARefutedAnswerIsIgnoredWhenTheToolIsOn() {
        val votes = listOf(v("A", "1", check = CheckResult.REFUTED), v("B", "2"), v("C", "3"))
        val f = decide(votes, judge = 0)
        assertEquals(PickMethod.ABSTAIN, f.method)
        // the same votes without the tool accept the judge's pick
        assertEquals("1", decide(votes, judge = 0, tool = false).answer)
    }

    @Test fun noVotesAbstain() {
        val f = decide(emptyList())
        assertEquals(PickMethod.ABSTAIN, f.method)
        assertNull(f.answer)
        assertEquals("no node answered", f.label)
    }

    @Test fun aLoneAnswerIsFlaggedAsLowQuorum() {
        val f = decide(listOf(v("B", "7")), total = 3)
        assertEquals("7", f.answer)
        assertEquals("only 1 of 3 nodes answered", f.label)
    }

    @Test fun twoNodesThatDisagreeHaveNoMajority() {
        assertFalse(FinalPick.hasMajority(Kind.MATH, listOf(v("A", "1"), v("B", "2"))))
        assertTrue(FinalPick.hasMajority(Kind.MATH, listOf(v("A", "1"), v("B", "1"))))
        assertFalse(FinalPick.hasMajority(Kind.MATH, emptyList()))
    }

    @Test fun answersAreGroupedAfterNormalisation() {
        val g = FinalPick.groups(Kind.MATH, listOf(v("A", "Rs 80,000"), v("B", "76128"), v("C", "80000.0")))
        assertEquals(2, g.size)
        assertEquals(2, g[0].count)
    }
}
