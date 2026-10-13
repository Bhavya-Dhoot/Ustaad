package com.swarmos.app.hands.android

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
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class RunTrackerTest {
    private val secretTask = "order biryani for Asha at 12 Rose Lane"
    private val secretLabel = "Deliver to Asha, 12 Rose Lane"
    private val secretTyped = "hunter2-typed-text"
    private val secretResult = "your OTP 482913 was read"

    private fun line(verdict: Verdict, tool: String = "tap") =
        StepLine(1, 0, Tier.FAST, tool, "id=3 \"$secretLabel\" $secretTyped", verdict, secretResult)

    private fun run(
        id: String = "r1",
        phase: Phase = Phase.RUNNING,
        log: List<StepLine> = emptyList(),
        started: Long = 1_000,
        ended: Long = 0,
        origin: Origin = Origin.OWN_SHEET,
    ) = RunState(
        id, secretTask, origin, phase,
        plan = listOf(SubTaskState(SubTask("goal: $secretLabel", Kind.PHONE, "food_delivery", "com.fake.food"), SubStatus.DONE, "outcome $secretTyped")),
        log = log, result = secretResult, startedAtMs = started, endedAtMs = ended,
    )

    // ------------------------------------------------------------ the audit record

    @Test
    fun theRecordCountsStepsAndVerdictsAndTheDuration() {
        val log = listOf(
            line(Verdict.ALLOWED), line(Verdict.ALLOWED), line(Verdict.ALLOWED),
            line(Verdict.CONFIRMED), line(Verdict.DECLINED), line(Verdict.DECLINED), line(Verdict.REFUSED),
        )
        val a = RunAudit.of(run(phase = Phase.FAILED, log = log, started = 1_000, ended = 4_500))
        assertEquals(RunAudit(7, 3, 1, 2, 1, 3_500, Phase.FAILED, Origin.OWN_SHEET), a)
    }

    @Test
    fun theJsonHasNumbersAndTwoEnumNamesOnly() {
        val json = RunAudit.of(run(phase = Phase.DONE, log = listOf(line(Verdict.ALLOWED)), ended = 2_000, origin = Origin.EXTERNAL_INTENT)).toJson()
        assertEquals(setOf("steps", "allowed", "confirmed", "declined", "refused", "ms", "phase", "origin"), json.keys)
        assertEquals("DONE", (json["phase"] as JsonPrimitive).content)
        assertEquals("EXTERNAL_INTENT", (json["origin"] as JsonPrimitive).content)
        for ((k, v) in json) if (k != "phase" && k != "origin") assertFalse("$k must be a number", (v as JsonPrimitive).isString)
    }

    @Test
    fun noTextOfTheRunReachesTheRecord() {
        val log = listOf(line(Verdict.ALLOWED, "type"), line(Verdict.CONFIRMED, "tap"))
        val text = RunAudit.of(run(phase = Phase.DONE, log = log, ended = 9_000)).toJson().toString()
        for (secret in listOf(secretTask, secretLabel, secretTyped, secretResult, "Asha", "Rose", "482913", "com.fake.food", "food_delivery")) {
            assertFalse("the record must not hold '$secret': $text", text.contains(secret))
        }
    }

    @Test
    fun theRecordTypeCannotHoldText() {
        val fields = RunAudit::class.java.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
        assertTrue(fields.isNotEmpty())
        val allowed = setOf<Class<*>>(Int::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!, Phase::class.java, Origin::class.java)
        for (f in fields) assertTrue("${f.name} is a ${f.type.simpleName}: only counts and enums may be recorded", f.type in allowed)
    }

    @Test
    fun aRunThatNeverEndedHasADurationOfZeroNotNegative() {
        assertEquals(0L, RunAudit.of(run(phase = Phase.STOPPED, started = 5_000, ended = 0)).durationMs)
    }

    // ------------------------------------------------------------ what the runtime does

    @Test
    fun aRunShowsTheChipOnceAndTheNoticeWhenItsLineChanges() {
        val t = RunTracker()
        assertEquals(listOf(RunEffect.ShowStop("r1"), RunEffect.Notice("Planning the task")), t.onChange(run(phase = Phase.PLANNING)))
        assertEquals(listOf(RunEffect.Notice("Working on your task")), t.onChange(run(phase = Phase.RUNNING)))
        assertTrue("a step line changes nothing on screen", t.onChange(run(phase = Phase.RUNNING, log = listOf(line(Verdict.ALLOWED)))).isEmpty())
        assertEquals(listOf(RunEffect.Notice("Waiting for your answer")), t.onChange(run(phase = Phase.WAITING_FOR_PERSON)))
        assertEquals(listOf(RunEffect.Notice("Working on your task")), t.onChange(run(phase = Phase.RUNNING)))
    }

    @Test
    fun theEndRemovesTheChipAndTheNoticeAndWritesOneRecord() {
        val t = RunTracker()
        t.onChange(run(phase = Phase.PLANNING))
        val end = t.onChange(run(phase = Phase.DONE, log = listOf(line(Verdict.ALLOWED)), ended = 3_000))
        assertEquals(listOf(RunEffect.HideStop, RunEffect.ClearNotice), end.take(2))
        val audit = end[2] as RunEffect.Audit
        assertEquals(Phase.DONE, audit.counts.phase)
        assertEquals(3, end.size)
        assertTrue("a repeated end event writes nothing more", t.onChange(run(phase = Phase.DONE, ended = 3_000)).isEmpty())
    }

    @Test
    fun everyEndingPhaseWritesARecordAndAnEventAfterTheEndShowsNothing() {
        for (p in listOf(Phase.DONE, Phase.FAILED, Phase.STOPPED)) {
            val t = RunTracker()
            t.onChange(run(id = "x", phase = Phase.RUNNING))
            val end = t.onChange(run(id = "x", phase = p, ended = 2_000))
            assertTrue(p.name, end.any { it is RunEffect.Audit })
            assertTrue(p.name, end.contains(RunEffect.HideStop))
        }
    }

    @Test
    fun aTaskWaitingForGoShowsNothingUntilItRuns() {
        val t = RunTracker()
        assertTrue(t.onChange(run(id = "w", phase = Phase.WAITING_FOR_GO, origin = Origin.EXTERNAL_INTENT)).isEmpty())
        assertEquals(listOf(RunEffect.ShowStop("w"), RunEffect.Notice("Planning the task")), t.onChange(run(id = "w", phase = Phase.PLANNING, origin = Origin.EXTERNAL_INTENT)))
    }

    @Test
    fun aDiscardedTaskIsRecordedWithoutTouchingTheChip() {
        val t = RunTracker()
        t.onChange(run(id = "w", phase = Phase.WAITING_FOR_GO, origin = Origin.MCP))
        val end = t.onChange(run(id = "w", phase = Phase.STOPPED, origin = Origin.MCP, ended = 1_500))
        assertEquals(1, end.size)
        assertEquals(Origin.MCP, (end.single() as RunEffect.Audit).counts.origin)
    }

    @Test
    fun aTurnedAwayStartDoesNotHideTheChipOfTheRunThatIsActing() {
        val t = RunTracker()
        t.onChange(run(id = "a", phase = Phase.RUNNING))
        val turnedAway = t.onChange(run(id = "b", phase = Phase.FAILED, ended = 1_000))
        assertTrue(turnedAway.none { it == RunEffect.HideStop || it == RunEffect.ClearNotice })
        assertEquals(1, turnedAway.filterIsInstance<RunEffect.Audit>().size)
        assertTrue("the chip of run a is still its own", t.onChange(run(id = "a", phase = Phase.RUNNING)).isEmpty())
    }

    @Test
    fun theNoticeLineNamesThePhaseNeverTheTask() {
        for (p in Phase.values()) assertFalse(p.name, RunTracker.noticeLine(p).contains("biryani"))
        assertEquals("Planning the task", RunTracker.noticeLine(Phase.PLANNING))
    }
}
