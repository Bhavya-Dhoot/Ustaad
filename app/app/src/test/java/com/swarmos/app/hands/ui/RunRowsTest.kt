package com.swarmos.app.hands.ui

import com.swarmos.app.hands.Kind
import com.swarmos.app.hands.Origin
import com.swarmos.app.hands.Phase
import com.swarmos.app.hands.RunState
import com.swarmos.app.hands.StepLine
import com.swarmos.app.hands.SubStatus
import com.swarmos.app.hands.SubTask
import com.swarmos.app.hands.SubTaskState
import com.swarmos.app.hands.Tier
import com.swarmos.app.hands.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class RunRowsTest {
    private val utc = ZoneId.of("UTC")
    private val noon = 1_760_356_800_000L // 2025-10-13 12:00:00 UTC

    private fun run(
        phase: Phase = Phase.RUNNING,
        task: String = "order two coffees",
        plan: List<SubTaskState> = emptyList(),
        log: List<StepLine> = emptyList(),
        result: String = "",
        id: String = "r1",
    ) = RunState(id, task, Origin.OWN_SHEET, phase, plan, log, result, startedAtMs = noon)

    private fun step(at: Long, tool: String = "tap", args: String = "", verdict: Verdict = Verdict.ALLOWED, result: String = "ok", sub: Int = 0) =
        StepLine(at, sub, Tier.FAST, tool, args, verdict, result)

    @Test
    fun everyPhaseHasADistinctWordWithNoEmojiOrDash() {
        val words = Phase.entries.map { phaseWord(it) }
        assertEquals(Phase.entries.size, words.toSet().size)
        for (w in words) {
            assertTrue(w.isNotBlank())
            assertFalse(w.contains(0x2014.toChar()))
            assertFalse(w.any { it.code > 127 })
        }
        assertEquals("Waiting for Run", phaseWord(Phase.WAITING_FOR_GO))
        assertEquals("Waiting for you", phaseWord(Phase.WAITING_FOR_PERSON))
    }

    @Test
    fun actingAndEndedPartitionThePhasesExceptWaitingForGo() {
        for (p in Phase.entries) {
            val acting = isActing(p)
            val ended = isEnded(p)
            assertFalse("$p is not both", acting && ended)
            assertEquals("$p", p == Phase.WAITING_FOR_GO, !acting && !ended)
        }
        assertTrue(isActing(Phase.WAITING_FOR_PERSON))
        assertTrue(isEnded(Phase.STOPPED))
    }

    @Test
    fun actingRunIsTheNewestRunThatActs() {
        val runs = listOf(
            run(Phase.WAITING_FOR_GO, id = "a"),
            run(Phase.RUNNING, id = "b"),
            run(Phase.DONE, id = "c"),
            run(Phase.PLANNING, id = "d"),
        )
        assertEquals("b", actingRun(runs)?.id)
        assertNull(actingRun(runs.filter { it.phase != Phase.RUNNING && it.phase != Phase.PLANNING }))
        assertNull(actingRun(emptyList()))
    }

    @Test
    fun runRowsKeepTheOrderAndShowPhaseAndTime() {
        val rows = runRows(listOf(run(Phase.DONE, "first", id = "x"), run(Phase.FAILED, "second", id = "y")), utc)
        assertEquals(listOf("x", "y"), rows.map { it.id })
        assertEquals("Done", rows[0].phase)
        assertEquals("12:00:00", rows[0].started)
        assertTrue(rows[0].ended)
        assertTrue(rows[1].ended)
        assertFalse(runRows(listOf(run(Phase.RUNNING)), utc)[0].ended)
    }

    @Test
    fun aLongTaskIsCutToOneLineInTheList() {
        val row = runRows(listOf(run(task = "buy " + "milk ".repeat(100) + "\n\n and eggs")), utc)[0]
        assertTrue(row.title.length <= 80)
        assertTrue(row.title.endsWith("..."))
        assertFalse(row.title.contains('\n'))
    }

    @Test
    fun oneLineCutsAtTheLimitWithoutSplittingAPair() {
        assertEquals("abc", oneLine("  abc  ", 10))
        assertEquals("a b", oneLine("a \n\t b", 10))
        val cut = oneLine("a".repeat(6) + "😀" + "tail", 10) // the pair would straddle the cut at 7
        assertTrue(cut.endsWith("..."))
        assertFalse(Character.isHighSurrogate(cut[cut.length - 4]))
    }

    @Test
    fun clockTimeIsBlankWithoutATime() {
        assertEquals("", clockTime(0, utc))
        assertEquals("12:00:05", clockTime(noon + 5_000, utc))
        assertEquals("17:30:00", clockTime(noon, ZoneId.of("Asia/Kolkata")))
    }

    @Test
    fun planRowsNumberTheSubtasksAndShowAStatusWord() {
        val plan = listOf(
            SubTaskState(SubTask("open the food app", Kind.PHONE, app = "in.swiggy"), SubStatus.DONE, "opened"),
            SubTaskState(SubTask("add two coffees", Kind.PHONE), SubStatus.RUNNING),
            SubTaskState(SubTask("pay", Kind.PHONE), SubStatus.TODO),
        )
        val rows = planRows(run(plan = plan))
        assertEquals(listOf(1, 2, 3), rows.map { it.number })
        assertEquals(listOf("done", "running", "to do"), rows.map { it.status })
        assertEquals("opened", rows[0].outcome)
        assertEquals("", rows[1].outcome)
        assertEquals("add two coffees", rows[1].goal)
    }

    @Test
    fun everySubStatusAndVerdictHasAWordThatIsNotAColourName() {
        val words = SubStatus.entries.map { statusWord(it) } + Verdict.entries.map { verdictWord(it) }
        for (w in words) {
            assertTrue(w.isNotBlank())
            assertFalse(listOf("green", "red", "yellow").any { w.contains(it) })
        }
        assertEquals("you said yes", verdictWord(Verdict.CONFIRMED))
        assertEquals("refused", verdictWord(Verdict.REFUSED))
    }

    @Test
    fun stepRowsShowTheToolWithItsArgumentsAndTheLastOnesInOrder() {
        val log = (1..6).map { step(noon + it * 1000L, "tap", "\"Add $it\"", result = "tapped $it", sub = it % 2) }
        val last = stepRows(run(log = log), utc, last = 3)
        assertEquals(3, last.size)
        assertEquals(listOf("12:00:04", "12:00:05", "12:00:06"), last.map { it.time })
        assertEquals("tap \"Add 4\"", last[0].what)
        assertEquals("tapped 4", last[0].result)
        assertEquals(listOf(1, 2, 1), last.map { it.subtask })
        assertEquals(6, stepRows(run(log = log), utc).size)
        assertEquals(0, stepRows(run(log = log), utc, last = 0).size)
        assertEquals(0, stepRows(run(log = log), utc, last = -4).size)
    }

    @Test
    fun aStepWithoutArgumentsShowsJustTheTool() {
        val row = stepRows(run(log = listOf(step(noon, "back", ""))), utc)[0]
        assertEquals("back", row.what)
        assertEquals("allowed", row.verdict)
        assertEquals("fast", row.tier)
    }

    @Test
    fun aLongStepResultIsCut() {
        val row = stepRows(run(log = listOf(step(noon, result = "x".repeat(1000)))), utc)[0]
        assertTrue(row.result.length <= 160)
    }

    @Test
    fun theEndNoteIsTheResultOrAPlainLine() {
        assertEquals("", endNote(run(Phase.RUNNING, result = "partial")))
        assertEquals("", endNote(run(Phase.WAITING_FOR_PERSON)))
        assertEquals("Two coffees are in the cart.", endNote(run(Phase.DONE, result = "  Two coffees are in the cart.  ")))
        assertEquals("Done.", endNote(run(Phase.DONE)))
        assertEquals("Stopped.", endNote(run(Phase.STOPPED)))
        assertEquals("Ustaad could not finish this task.", endNote(run(Phase.FAILED)))
        assertEquals("The app asked for a login.", endNote(run(Phase.FAILED, result = "The app asked for a login.")))
    }
}
