package com.swarmos.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LruBudgetTest {
    private fun e(key: String, bytes: Long, used: Long, pinned: Boolean = false) = LruBudget.Entry(key, bytes, used, pinned)
    private fun keys(l: List<LruBudget.Entry>) = l.map { it.key }

    @Test fun nothingIsEvictedWhileTheTotalIsWithinTheBudget() {
        assertEquals(emptyList<LruBudget.Entry>(), LruBudget.evict(emptyList(), 100))
        assertEquals(emptyList<LruBudget.Entry>(), LruBudget.evict(listOf(e("a", 60, 1), e("b", 40, 2)), 100)) // exactly the budget
        assertEquals(emptyList<LruBudget.Entry>(), LruBudget.evict(listOf(e("a", 10, 1)), 100))
    }

    @Test fun theLeastRecentlyUsedGoFirstAndOnlyAsManyAsNeeded() {
        val all = listOf(e("new", 50, 30), e("old", 50, 10), e("mid", 50, 20))
        assertEquals(listOf("old"), keys(LruBudget.evict(all, 100)))          // 150 -> 100
        assertEquals(listOf("old", "mid"), keys(LruBudget.evict(all, 99)))    // one byte under: the next goes too
        assertEquals(listOf("old", "mid", "new"), keys(LruBudget.evict(all, 0)))
    }

    @Test fun aBudgetOfZeroOrLessEvictsEveryUnpinnedEntryAndNeverAPinnedOne() {
        val all = listOf(e("a", 10, 1), e("b", 10, 2, pinned = true), e("c", 10, 3))
        assertEquals(listOf("a", "c"), keys(LruBudget.evict(all, 0)))
        assertEquals(listOf("a", "c"), keys(LruBudget.evict(all, -5)))
    }

    @Test fun everythingPinnedMeansNothingIsEvictedEvenOverTheBudget() {
        assertEquals(emptyList<LruBudget.Entry>(), LruBudget.evict(listOf(e("a", 90, 1, true), e("b", 90, 2, true)), 10))
    }

    @Test fun aPinnedEntryCountsInTheTotalSoOthersGoUntilTheRestFits() {
        val all = listOf(e("running", 80, 1, pinned = true), e("x", 30, 2), e("y", 30, 3))
        assertEquals("140 over 100: x goes, then 110 still over: y goes", listOf("x", "y"), keys(LruBudget.evict(all, 100)))
        assertEquals(listOf("x"), keys(LruBudget.evict(listOf(e("running", 80, 1, true), e("x", 30, 2)), 100)))
        // the pinned one alone is over the budget: the unpinned still go, the pinned stays, and the answer is everything it may take
        assertEquals(listOf("x"), keys(LruBudget.evict(listOf(e("running", 200, 1, true), e("x", 30, 2)), 100)))
    }

    @Test fun equalTimesAreBrokenByKeySoTheAnswerDoesNotDependOnTheOrderGiven() {
        val a = listOf(e("b", 50, 5), e("a", 50, 5), e("c", 50, 5))
        val b = a.reversed()
        assertEquals(listOf("a", "b"), keys(LruBudget.evict(a, 50)))
        assertEquals(keys(LruBudget.evict(a, 50)), keys(LruBudget.evict(b, 50)))
    }

    @Test fun anEntryNeverUsedIsTheFirstToGo() {
        assertEquals(listOf("unknown"), keys(LruBudget.evict(listOf(e("used", 60, 1_000), e("unknown", 60, 0)), 60)))
    }

    @Test fun aZeroByteEntryIsHarmlessAndTheInputIsNotChanged() {
        val all = listOf(e("z", 0, 1), e("a", 50, 2))
        assertEquals(listOf("a"), keys(LruBudget.evict(all, 10)).also { assertEquals(listOf("z", "a"), keys(all)) })
    }

    // ---- the record of last use ----

    @get:Rule val tmp = TemporaryFolder()

    @Test fun lastUseSurvivesARestart() {
        val dir = tmp.newFolder()
        UseLog(dir).also { it.touch("model-a", 100); it.touch("model-b", 200); it.touch("model-a", 300) }
        val again = UseLog(dir)
        assertEquals(300L, again.lastUsed("model-a"))
        assertEquals(200L, again.lastUsed("model-b"))
        assertEquals("a key never touched reads as never used", 0L, again.lastUsed("model-c"))
    }

    @Test fun forgetAndClearAreKept() {
        val dir = tmp.newFolder()
        val log = UseLog(dir)
        log.touch("a", 1); log.touch("b", 2); log.touch("c", 3)
        log.forget(listOf("a", "zzz"))
        assertEquals(0L, UseLog(dir).lastUsed("a"))
        assertEquals(2L, UseLog(dir).lastUsed("b"))
        log.clear()
        assertEquals(0L, UseLog(dir).lastUsed("b"))
    }

    @Test fun anUnreadableOrMissingFileIsAnEmptyRecord() {
        val dir = tmp.newFolder()
        File(dir, "cache-use.json").writeText("{ not json")
        val log = UseLog(dir)
        assertEquals(0L, log.lastUsed("a"))
        log.touch("a", 5) // and it is written again, whole
        assertEquals(5L, UseLog(dir).lastUsed("a"))
        assertFalse(File(dir, "cache-use.json.tmp").exists())
        assertTrue(UseLog(File(dir, "not-yet-there")).lastUsed("x") == 0L)
    }

    @Test fun aFolderThatDoesNotExistYetIsMadeOnTheFirstWrite() {
        val dir = File(tmp.newFolder(), "state/deeper")
        UseLog(dir, "u.json").touch("k", 9)
        assertEquals(9L, UseLog(dir, "u.json").lastUsed("k"))
    }
}
