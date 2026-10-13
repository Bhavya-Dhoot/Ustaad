package com.swarmos.app.memcloud

import com.swarmos.app.core.DeviceFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The rpc tensor cache of a lender kept within a budget, whole models at a time, the one used longest ago first. */
class RpcCacheKeeperTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var dir: File
    private lateinit var state: File
    private var now = 1_000L
    private var budget = 1_000L

    @Before fun setUp() {
        dir = tmp.newFolder("rpc")
        state = tmp.newFolder("state")
    }

    private fun keeper() = RpcCacheKeeper(dir, state, { budget }, { now })

    /** A tensor file of [bytes] bytes. */
    private fun file(name: String, bytes: Int) = File(dir, name).also { it.writeBytes(ByteArray(bytes)) }

    private fun names() = dir.listFiles()!!.map { it.name }.sorted()

    /** One whole loan for [model]: it starts, rpc-server caches [files], it ends. The clock moves on first. */
    private fun RpcCacheKeeper.loan(model: String?, vararg files: Pair<String, Int>) {
        now += 100
        loanStarted(model)
        for ((n, b) in files) file(n, b)
        loanEnded()
    }

    @Test fun theFilesACreatedDuringALoanBelongToItsModelAndSurviveARestart() {
        val k = keeper()
        k.loan("a.gguf", "a1" to 100, "a2" to 100)
        k.loan("b.gguf", "b1" to 100)
        budget = 250 // 300 bytes: the model used longest ago goes, whole
        assertEquals(listOf("a.gguf"), keeper().enforce()) // a new keeper reads the record from disk
        assertEquals(listOf("b1"), names())
    }

    @Test fun anOverBudgetCacheDropsTheModelUsedLongestAgoAndNotMoreThanNeeded() {
        val k = keeper()
        k.loan("old.gguf", "o1" to 400)
        k.loan("mid.gguf", "m1" to 400)
        k.loan("new.gguf", "n1" to 400)
        // the end of the last loan kept 1200 bytes within the 1000 budget already: the oldest went
        assertEquals(listOf("m1", "n1"), names())
        budget = 500
        assertEquals(listOf("mid.gguf"), k.enforce())
        assertEquals(listOf("n1"), names())
    }

    @Test fun usingAModelAgainMakesItTheLastToGo() {
        val k = keeper()
        k.loan("a.gguf", "a1" to 400)
        k.loan("b.gguf", "b1" to 400)
        k.loan("a.gguf") // a is used again: no new files, but it is the newest now
        k.loan("c.gguf", "c1" to 400) // 1200 > 1000: b is the one used longest ago
        assertEquals(listOf("a1", "c1"), names())
    }

    @Test fun theModelOfARunningLoanIsNeverDroppedWhateverItsAgeOrSizeSoFar() {
        val k = keeper()
        k.loan("old.gguf", "o1" to 600)
        now += 100
        k.loanStarted("new.gguf") // running; the files it caches now are not recorded until it ends
        file("n1", 600)
        budget = 100
        assertEquals("only the old model goes; the running loan's file is its own, not of unknown origin", listOf("old.gguf"), k.enforce())
        assertEquals(listOf("n1"), names())
        k.loanEnded() // recorded for its model; the loan is over, so the group may now go like any other
        assertEquals(emptyList<String>(), names())
    }

    @Test fun theGroupOfTheRunningLoanIsKeptEvenWhenItAloneIsOverTheBudget() {
        val k = keeper()
        k.loan("big.gguf", "g1" to 600, "g2" to 600) // 1200 over a 1000 budget, alone and not running: it goes
        assertEquals(emptyList<String>(), names())
        now += 100
        k.loanStarted("big.gguf")
        file("h1", 800)
        k.loanEnded() // the same 800 bytes recorded for big.gguf, within the budget
        assertEquals(listOf("h1"), names())
        now += 100
        k.loanStarted("big.gguf") // running again: pinned
        budget = 10
        assertEquals(emptyList<String>(), k.enforce())
        assertEquals(listOf("h1"), names())
    }

    @Test fun filesOfUnknownOriginFormOneGroupThatIsTheFirstToGo() {
        file("legacy1", 300); file("legacy2", 300) // from before the record existed
        val k = keeper()
        k.loan("a.gguf", "a1" to 300)
        k.loan("b.gguf", "b1" to 300) // 1200 > 1000: the unknown group goes before any model, though it is bigger than needed
        assertEquals(listOf("a1", "b1"), names())
    }

    @Test fun aBudgetOfZeroDropsEverythingButARunningLoansGroup() {
        val k = keeper()
        k.loan("a.gguf", "a1" to 100)
        budget = 0
        assertEquals(listOf("a.gguf"), k.enforce())
        assertEquals(emptyList<String>(), names())
    }

    @Test fun aCacheWithinTheBudgetIsLeftAlone() {
        val k = keeper()
        k.loan("a.gguf", "a1" to 400, "a2" to 400)
        assertEquals(emptyList<String>(), k.enforce())
        assertEquals(listOf("a1", "a2"), names())
        assertEquals(RpcCacheKeeper.Report(800, 2, 1_000), k.report())
    }

    @Test fun aLoanNobodyClosedIsClosedByTheNextOneAndItsFilesAreRecorded() {
        val k = keeper()
        now += 100
        k.loanStarted("a.gguf")
        file("a1", 700) // the app died, or the start failed: loanEnded never came
        k.loan("b.gguf", "b1" to 700) // 1400 > 1000: a was recorded when b started, and it is the oldest
        assertEquals(listOf("b1"), names())
    }

    @Test fun aLoanWithoutAModelNameHasAGroupOfItsOwn() {
        val k = keeper()
        k.loan(null, "u1" to 300)
        k.loan("   ", "u2" to 300) // blank counts as no name
        budget = 400
        assertEquals(listOf(RpcCacheKeeper.UNNAMED), k.enforce())
        assertEquals(emptyList<String>(), names())
    }

    @Test fun clearingTheCacheForgetsTheRecordSoNothingStaleIsEvictedLater() {
        val k = keeper()
        k.loan("a.gguf", "a1" to 400)
        dir.listFiles()!!.forEach { it.delete() } // the Clear button
        k.forgetAll()
        assertFalse(File(state, "rpc-cache-groups.json").readText().contains("a.gguf"))
        file("x1", 400) // a file of the same name appears later (a new loan of another model)
        assertEquals(emptyList<String>(), keeper().enforce())
        assertTrue(File(dir, "x1").exists())
    }

    @Test fun aGroupWhoseFilesAreAllGoneIsPrunedFromTheRecord() {
        val k = keeper()
        k.loan("a.gguf", "a1" to 100)
        File(dir, "a1").delete()
        assertEquals(emptyList<String>(), k.enforce())
        k.loan("b.gguf", "b1" to 100)
        assertFalse(File(state, "rpc-cache-groups.json").readText().contains("a.gguf"))
    }

    @Test fun theClearButtonSaysWhatWentOrWhyNot() {
        val none = RpcCache.Stats(0, 0)
        assertTrue(RpcCache.clearMessage(RpcCache.ClearResult.Cleared(RpcCache.Stats(1_610_612_736L, 12), none)).let { it.startsWith("Cleared 1") && it.endsWith("GB (12 files).") })
        assertEquals(
            "This phone is lending memory to Phone A; stop the loan first.",
            RpcCache.clearMessage(RpcCache.ClearResult.Refused("this phone is lending memory to Phone A; stop the loan first")),
        )
    }

    // ---- the budget from the device profile ----

    private val gib = 1024L * 1024 * 1024

    @Test fun theBudgetIsATenthOfTheFreeStorageWithinTwoAndSixteenGigabytes() {
        assertEquals(4_000L * 1024 * 1024, RpcCacheKeeper.budgetFor(40_000))
        assertEquals("exactly the lower bound", 2 * gib, RpcCacheKeeper.budgetFor(20_480))
        assertEquals("below it, one 4B model's tensors still fit", 2 * gib, RpcCacheKeeper.budgetFor(8_000))
        assertEquals(2 * gib, RpcCacheKeeper.budgetFor(1))
        assertEquals("exactly the upper bound", 16 * gib, RpcCacheKeeper.budgetFor(163_840))
        assertEquals(16 * gib, RpcCacheKeeper.budgetFor(500_000))
    }

    @Test fun withoutAProfileOrAFreeStorageFigureTheBudgetIsEightGigabytes() {
        assertEquals(8 * gib, RpcCacheKeeper.budgetFor(null))
        assertEquals("the profile could not read the storage", 8 * gib, RpcCacheKeeper.budgetFor(0))
        assertEquals(8 * gib, RpcCacheKeeper.budgetFor(-5))
        assertEquals(RpcCacheKeeper.DEFAULT_BUDGET_BYTES, 8 * gib)
    }

    @Test fun theBudgetOfTheFixtureDevices() {
        assertEquals("iQOO 15, 200 GB free", 16 * gib, RpcCacheKeeper.budgetFor(DeviceFixtures.iqoo15.freeStorageMb))
        assertEquals("8 GB phone, 40 GB free", 4_000L * 1024 * 1024, RpcCacheKeeper.budgetFor(DeviceFixtures.phone8gb.freeStorageMb))
        assertEquals("4 GB phone, 8 GB free", 2 * gib, RpcCacheKeeper.budgetFor(DeviceFixtures.phone4gb.freeStorageMb))
        assertEquals("desktop", 16 * gib, RpcCacheKeeper.budgetFor(DeviceFixtures.desktop32.freeStorageMb))
    }

    @Test fun theBudgetIsAskedEachTimeSoAProfileCanChangeIt() {
        val k = keeper()
        k.loan("a.gguf", "a1" to 400)
        k.loan("b.gguf", "b1" to 400)
        budget = 10_000
        assertEquals(emptyList<String>(), k.enforce())
        budget = 500
        assertEquals(listOf("a.gguf"), k.enforce())
    }
}
