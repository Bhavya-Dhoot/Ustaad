package com.swarmos.app.hands

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class HandsEngineTest {
    private val rigs = ArrayList<Rig>()

    @After fun tearDown() {
        rigs.forEach { it.close() }
    }

    private fun rig(
        brain: ScriptedBrain = ScriptedBrain(),
        driver: FakeFoodDriver = FakeFoodDriver(),
        gate: HumanGate = FakeGate(),
        desk: FakeDesk = FakeDesk(),
        drivers: List<PhoneDriver>? = null,
    ): Rig = Rig(brain, driver, gate, desk, drivers).also { rigs += it }

    private fun waitUntil(ms: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("condition not reached within $ms ms")
            Thread.sleep(5)
        }
    }

    private fun Rig.startWhenFree(task: String, origin: Origin = Origin.OWN_SHEET): String {
        repeat(300) {
            val id = engine.start(task, origin)
            if (run(id).result != BUSY) return id
            Thread.sleep(10)
        }
        throw AssertionError("the phone never became free")
    }

    private fun renderedId(rendered: String, label: String, role: String? = null) =
        idOf("${StepLoop.FENCE_OPEN}\n$rendered\n${StepLoop.FENCE_CLOSE}", label, role)

    private val BUSY = "another task is running"

    private fun phonePlan() = planJson(
        subJson("PHONE", "add a biryani to the cart", "food_delivery", "FakeFood"),
        subJson("REPLY", "tell the person what is in the cart"),
    )

    private val cartSteps: Array<suspend (ScriptedBrain.Call) -> String> = arrayOf(
        typeInto("Search", "biryani"),
        { step("press_enter") },
        tapLabel("Biryani House"),
        tapLabel("Add"),
        tapLabel("View cart"),
        { step("done", text = "1 chicken biryani is in the cart, 250 rupees") },
    )

    private fun payNow(): suspend (ScriptedBrain.Call) -> String = { c -> step("tap", id = idOf(c.user, "Pay now", "button")) }

    private class Hang {
        val started = CompletableDeferred<Unit>()
        @Volatile var cancelled = false
        val reply: suspend (ScriptedBrain.Call) -> String = {
            started.complete(Unit)
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
    }

    // ------------------------------------------------------------ origins and go()

    @Test fun tasksFromOtherAppsWaitForTheRunTapAndNothingHappensBeforeIt() {
        for (origin in listOf(Origin.EXTERNAL_INTENT, Origin.MCP, Origin.APP_FUNCTION)) {
            val r = rig()
            r.brain.reply(planJson(subJson("REPLY", "say hello")), """{"reply":"hello"}""")
            val id = r.engine.start("say hello", origin)
            assertEquals(Phase.WAITING_FOR_GO, r.run(id).phase)
            Thread.sleep(150)
            assertEquals(origin.name, Phase.WAITING_FOR_GO, r.run(id).phase)
            assertTrue("the model is not asked before go()", r.brain.calls.isEmpty())
            assertTrue("the phone is not touched before go()", r.driver.actions.isEmpty() && r.driver.reads == 0)
            r.engine.go(id)
            val done = r.await(id)
            assertEquals(Phase.DONE, done.phase)
            assertEquals("hello", done.result)
        }
    }

    @Test fun theOwnSheetAndTheAssistantSessionStartAtOnce() {
        for (origin in listOf(Origin.OWN_SHEET, Origin.ASSISTANT_SESSION)) {
            val r = rig()
            r.brain.reply(planJson(subJson("REPLY", "say hello")), """{"reply":"hello"}""")
            val id = r.engine.start("say hello", origin)
            assertEquals(origin.name, Phase.DONE, r.await(id).phase)
            assertEquals(Phase.PLANNING, r.changes.first().phase)
        }
    }

    @Test fun goOnARunThatIsNotWaitingOrOnAnUnknownIdDoesNothing() {
        val r = rig()
        r.brain.reply(planJson(subJson("REPLY", "x")), """{"reply":"ok"}""")
        val id = r.engine.start("x", Origin.OWN_SHEET)
        r.await(id)
        val calls = r.brain.calls.size
        r.engine.go(id)
        r.engine.go("no-such-run")
        Thread.sleep(50)
        assertEquals(calls, r.brain.calls.size)
        assertEquals(Phase.DONE, r.run(id).phase)
    }

    // ------------------------------------------------------------ a whole run

    @Test fun aWholeRunPlansDrivesTheFakeAppToTheCartAndRepliesWithoutPaying() {
        val r = rig()
        r.brain.reply(phonePlan())
        r.brain.replyWith(*cartSteps)
        r.brain.reply("""{"reply":"The cart has 1 chicken biryani, 250 rupees. Paying is up to you."}""")
        val id = r.engine.start("order a biryani", Origin.OWN_SHEET)
        val run = r.await(id)
        assertEquals(run.result, Phase.DONE, run.phase)
        assertEquals("The cart has 1 chicken biryani, 250 rupees. Paying is up to you.", run.result)
        assertEquals(listOf(SubStatus.DONE, SubStatus.DONE), run.plan.map { it.status })
        assertEquals("1 chicken biryani is in the cart, 250 rupees", run.plan[0].outcome)
        assertEquals(listOf("type", "press_enter", "tap", "tap", "tap", "done"), run.log.map { it.tool })
        assertTrue(run.log.all { it.sub == 0 && it.verdict == Verdict.ALLOWED })
        assertEquals("order a biryani", run.task)
        assertEquals(Origin.OWN_SHEET, run.origin)
        assertEquals(1_000L, run.startedAtMs)
        assertTrue(run.endedAtMs >= run.startedAtMs)
        assertFalse(r.driver.paid)
        assertEquals(1, r.driver.cart)
        assertEquals(Tier.STRONG, r.brain.planCalls.single().tier)
        assertFalse(r.brain.planCalls.single().onPhoneOnly)
        assertTrue(r.brain.stepCalls.all { it.onPhoneOnly && it.tier == Tier.FAST })
        val compose = r.brain.calls.last()
        assertTrue(compose.onPhoneOnly)
        assertTrue(compose.user.contains("Task: order a biryani") && compose.user.contains("1 chicken biryani is in the cart"))
        assertEquals(run, r.engine.runs.value.single())
        waitUntil { r.changes.last().phase == Phase.DONE }
    }

    @Test fun thePlannerFallbackStillRunsTheTaskAsOnePhoneSubtask() {
        val r = rig()
        r.brain.reply("garbage", "more garbage")
        r.brain.replyWith({ step("done", text = "all set") })
        val run = r.await(r.engine.start("do the thing", Origin.OWN_SHEET))
        assertEquals(Phase.DONE, run.phase)
        assertEquals(listOf(SubTask("do the thing", Kind.PHONE)), run.plan.map { it.task })
        assertEquals("all set", run.result)
    }

    @Test fun knowledgeDataAndReplyUseTheExpertDeskAndThePersonsDataAndTheReplyHasBothResults() {
        val desk = FakeDesk(onAsk = { d, _ -> if (d == "cooking") "Use basmati rice" else null }, onSearch = { "Your address is 12 MG Road" })
        val r = rig(desk = desk)
        r.brain.reply(
            planJson(
                subJson("KNOWLEDGE", "which rice for biryani", expert = "cooking"),
                subJson("DATA", "find my delivery address"),
                subJson("REPLY", "answer both"),
            ),
            """{"reply":"Use basmati rice. Your address is 12 MG Road."}""",
        )
        val run = r.await(r.engine.start("which rice and where do I live", Origin.OWN_SHEET))
        assertEquals(run.result, Phase.DONE, run.phase)
        assertEquals("Use basmati rice. Your address is 12 MG Road.", run.result)
        assertEquals(listOf("cooking" to "which rice for biryani"), desk.asked.toList())
        assertEquals(listOf("find my delivery address"), desk.searched.toList())
        assertEquals(2, r.brain.calls.size) // the plan and the reply: the experts did the rest
        val compose = r.brain.calls.last()
        assertTrue(compose.user.contains("Use basmati rice") && compose.user.contains("12 MG Road"))
        assertTrue("the data of the person stays on this phone", compose.onPhoneOnly)
    }

    @Test fun anExpertThatDoesNotAnswerFallsBackToTheStrongModelAndPrivacyFollowsWhatIsInThePrompt() {
        val r = rig()
        r.brain.reply(
            planJson(subJson("KNOWLEDGE", "how long to boil rice", expert = "cooking"), subJson("DATA", "my notes"), subJson("KNOWLEDGE", "and eggs", expert = "cooking")),
            """{"answer":"About 12 minutes"}""",
            """{"answer":"About 8 minutes"}""",
        )
        val run = r.await(r.engine.start("boil", Origin.OWN_SHEET))
        assertEquals(Phase.DONE, run.phase)
        val first = r.brain.calls[1]
        assertEquals(Tier.STRONG, first.tier)
        assertTrue(first.system.contains("expert in cooking"))
        assertFalse("no earlier result in the prompt, so the cluster may answer", first.onPhoneOnly)
        val second = r.brain.calls[2]
        assertTrue("an earlier result is in the prompt", second.onPhoneOnly)
        assertTrue(second.user.contains("About 12 minutes"))
        assertEquals("About 8 minutes", run.result)
    }

    @Test fun aFailedSubtaskSkipsTheLaterWorkButTheReplyStillSaysWhatHappened() {
        val r = rig()
        r.brain.reply(
            planJson(
                subJson("PHONE", "first", app = "FakeFood"), subJson("PHONE", "second", app = "FakeFood"), subJson("REPLY", "tell"),
            ),
        )
        r.brain.replyWith({ step("fail", text = "the app wants a login") })
        r.brain.reply("""{"reply":"I could not do it: the app wants a login."}""")
        val run = r.await(r.engine.start("two things", Origin.OWN_SHEET))
        assertEquals(Phase.FAILED, run.phase)
        assertEquals(listOf(SubStatus.FAILED, SubStatus.SKIPPED, SubStatus.DONE), run.plan.map { it.status })
        assertEquals("I could not do it: the app wants a login.", run.result)
        assertTrue(r.brain.calls.last().user.contains("the app wants a login"))
    }

    // ------------------------------------------------------------ careful mode through a whole run (R1) and the route of a planned question (R9)

    @Test fun r1_aCarefulRunThroughTheFakeFoodAppAsksTwoQuestionsAnEnterAndTheRestaurantCard() {
        val gate = gateYesTo(AskKind.ENTER, AskKind.UNKNOWN_CONTROL) // an owner who lets navigation through and refuses commits
        val r = rig(gate = gate)
        r.caution = Caution.CAREFUL
        r.brain.reply(phonePlan())
        r.brain.replyWith(*cartSteps)
        r.brain.reply("""{"reply":"The cart has 1 chicken biryani."}""")
        val run = r.await(r.engine.start("order a biryani", Origin.OWN_SHEET))
        assertEquals(run.result, Phase.DONE, run.phase)
        assertEquals(listOf(AskKind.ENTER, AskKind.UNKNOWN_CONTROL), gate.kinds)
        assertEquals(listOf("press enter", "tap \"Biryani House 4.3 star 30 min\""), gate.prompts.map { it.action })
        assertEquals(1, r.driver.cart)
        assertFalse(r.driver.paid)
    }

    @Test fun r1_aCarefulRunWithAnOwnerWhoSaysNoStopsAtTheFirstQuestion() {
        val gate = FakeGate()
        val r = rig(gate = gate)
        r.caution = Caution.CAREFUL
        r.brain.reply(phonePlan())
        r.brain.replyWith(*cartSteps)
        val run = r.await(r.engine.start("order a biryani", Origin.OWN_SHEET))
        assertEquals(Phase.STOPPED, run.phase)
        assertEquals(listOf(AskKind.ENTER), gate.kinds)
        assertEquals(0, r.driver.cart)
    }

    @Test fun r1_theSameRunInFewerQuestionsModeAsksNothing() {
        val gate = FakeGate()
        val r = rig(gate = gate)
        r.brain.reply(phonePlan())
        r.brain.replyWith(*cartSteps)
        r.brain.reply("""{"reply":"ok"}""")
        assertEquals(Phase.DONE, r.await(r.engine.start("order a biryani", Origin.OWN_SHEET)).phase)
        assertTrue(gate.prompts.isEmpty())
    }

    @Test fun r9_aPlannedQuestionLeavesThePhoneOnlyWhenNoPhoneOrDataSubtaskHasRunBefore() {
        val desk = FakeDesk(onAsk = { _, _ -> "an answer" })
        val a = rig(desk = desk)
        a.brain.reply(planJson(subJson("KNOWLEDGE", "which rice", expert = "cooking"), subJson("KNOWLEDGE", "how long", expert = "cooking")))
        assertEquals(Phase.DONE, a.await(a.engine.start("rice", Origin.OWN_SHEET)).phase)
        assertEquals(listOf(false, false), desk.routes.toList())

        val desk2 = FakeDesk(onAsk = { _, _ -> "an answer" })
        val b = rig(desk = desk2)
        b.brain.reply(planJson(subJson("PHONE", "look at the menu", app = FOOD), subJson("KNOWLEDGE", "which rice", expert = "cooking")))
        b.brain.replyWith({ step("done", text = "the menu says biryani") })
        assertEquals(Phase.DONE, b.await(b.engine.start("rice", Origin.OWN_SHEET)).phase)
        assertEquals(listOf(true), desk2.routes.toList())

        val desk3 = FakeDesk(onAsk = { _, _ -> "an answer" }, onSearch = { "a note" })
        val c = rig(desk = desk3)
        c.brain.reply(planJson(subJson("DATA", "my notes"), subJson("KNOWLEDGE", "which rice", expert = "cooking")))
        assertEquals(Phase.DONE, c.await(c.engine.start("rice", Origin.OWN_SHEET)).phase)
        assertEquals(listOf(true), desk3.routes.toList())
    }

    // ------------------------------------------------------------ no model, no driver

    @Test fun withoutAnyModelTheRunFailsAtOnceAndSaysSo() {
        val r = rig(brain = ScriptedBrain(fast = false, strong = false))
        val run = r.await(r.engine.start("x", Origin.OWN_SHEET))
        assertEquals(Phase.FAILED, run.phase)
        assertTrue(run.result, run.result.contains("no chat model"))
    }

    @Test fun withoutAReadyDriverAPhoneSubtaskFailsWithAClearResult() {
        val r = rig(drivers = emptyList())
        val run = r.await(r.engine.start("open the app", Origin.OWN_SHEET))
        assertEquals(Phase.FAILED, run.phase)
        assertTrue(run.result, run.result.contains("no phone driver is ready"))
    }

    @Test fun theFirstReadyDriverIsTheOneUsed() {
        val off = FakeFoodDriver(ready = false)
        val on = FakeFoodDriver()
        val r = rig(driver = on, drivers = listOf(off, on))
        r.brain.reply(planJson(subJson("PHONE", "g", app = FOOD)))
        r.brain.replyWith(tapLabel("Search"), { step("done", text = "ok") })
        r.await(r.engine.start("t", Origin.OWN_SHEET))
        assertTrue(off.actions.isEmpty() && off.reads == 0)
        assertTrue(on.actions.any { it == "tap:search" })
    }

    // ------------------------------------------------------------ one run at a time, stop

    @Test fun aSecondTaskWhileOneRunsEndsAtOnceAsFailed() {
        val open = CompletableDeferred<Unit>()
        val r = rig()
        r.brain.replyWith({ open.await(); planJson(subJson("REPLY", "x")) }, { """{"reply":"first done"}""" })
        val a = r.engine.start("first", Origin.OWN_SHEET)
        waitUntil { r.brain.calls.size >= 1 }
        val b = r.engine.start("second", Origin.OWN_SHEET)
        val c = r.engine.start("third", Origin.EXTERNAL_INTENT)
        for (x in listOf(b, c)) {
            assertEquals(Phase.FAILED, r.run(x).phase)
            assertEquals(BUSY, r.run(x).result)
            assertTrue(r.run(x).endedAtMs > 0)
        }
        assertEquals("the first run is untouched", Phase.PLANNING, r.run(a).phase)
        open.complete(Unit)
        assertEquals("first done", r.await(a).result)
        assertEquals(3, r.engine.runs.value.size)
        assertEquals(listOf(c, b, a), r.engine.runs.value.map { it.id })
    }

    @Test fun aRunThatIsWaitingForGoDoesNotBlockOthersButFailsWhenGoComesWhileBusy() {
        val open = CompletableDeferred<Unit>()
        val r = rig()
        val waiting = r.engine.start("from outside", Origin.EXTERNAL_INTENT)
        r.brain.replyWith({ open.await(); planJson(subJson("REPLY", "x")) }, { """{"reply":"own done"}""" })
        val own = r.engine.start("own task", Origin.OWN_SHEET)
        waitUntil { r.brain.calls.size >= 1 }
        r.engine.go(waiting)
        assertEquals(BUSY, r.run(waiting).result)
        open.complete(Unit)
        assertEquals(Phase.DONE, r.await(own).phase)
    }

    @Test fun stopCancelsTheRunAndTheModelCallIsCancelled() {
        val hang = Hang()
        val r = rig()
        r.brain.replyWith(hang.reply)
        val id = r.engine.start("hang", Origin.OWN_SHEET)
        awaitOrFail(hang.started)
        r.engine.stop(id)
        val run = r.run(id)
        assertEquals(Phase.STOPPED, run.phase)
        assertEquals("stopped by the person", run.result)
        assertTrue(run.endedAtMs > 0)
        waitUntil { hang.cancelled }
        // and the phone is free again
        r.brain.reply(planJson(subJson("REPLY", "x")), """{"reply":"fine"}""")
        val next = r.startWhenFree("after")
        assertEquals("fine", r.await(next).result)
        assertEquals(Phase.STOPPED, r.run(id).phase)
    }

    @Test fun stopDuringAStepKeepsTheLogSoFarAndTheDriverIsNotCalledAgain() {
        val hang = Hang()
        val r = rig()
        r.brain.reply(planJson(subJson("PHONE", "g", app = "FakeFood")))
        r.brain.replyWith({ step("scroll", direction = "down") }, hang.reply)
        val id = r.engine.start("t", Origin.OWN_SHEET)
        awaitOrFail(hang.started)
        r.engine.stop(id)
        waitUntil { hang.cancelled }
        val run = r.run(id)
        assertEquals(Phase.STOPPED, run.phase)
        assertEquals(listOf("scroll"), run.log.map { it.tool })
        val actions = r.driver.actions.size
        Thread.sleep(100)
        assertEquals(actions, r.driver.actions.size)
    }

    @Test fun aRunWaitingForGoCanBeStoppedAndGoThenDoesNothing() {
        val r = rig()
        val id = r.engine.start("later", Origin.MCP)
        r.engine.stop(id)
        assertEquals(Phase.STOPPED, r.run(id).phase)
        r.engine.go(id)
        Thread.sleep(50)
        assertEquals(Phase.STOPPED, r.run(id).phase)
        assertTrue(r.brain.calls.isEmpty())
    }

    @Test fun stopOnAFinishedOrUnknownRunChangesNothing() {
        val r = rig()
        r.brain.reply(planJson(subJson("REPLY", "x")), """{"reply":"ok"}""")
        val id = r.engine.start("x", Origin.OWN_SHEET)
        r.await(id)
        r.engine.stop(id)
        r.engine.stop("no-such-run")
        assertEquals(Phase.DONE, r.run(id).phase)
        assertEquals("ok", r.run(id).result)
    }

    // ------------------------------------------------------------ limits

    @Test fun aRunIsLimitedToSixtySteps() {
        val r = rig()
        r.brain.reply(planJson(subJson("PHONE", "g1", app = FOOD), subJson("PHONE", "g2", app = FOOD), subJson("PHONE", "g3", app = FOOD)))
        val counts = ConcurrentHashMap<String, Int>()
        r.brain.fallback = { _, c ->
            val goal = Regex("Goal: (g\\d)").find(c.user)!!.groupValues[1]
            val k = counts.merge(goal, 1, Int::plus)!! - 1
            if (k < 24) step("type", id = idOf(c.user, "Search", "edit"), text = ('a' + (goal.last() - '1')).toString().repeat(k + 1)) else step("done", text = "$goal done")
        }
        val run = r.await(r.engine.start("long", Origin.OWN_SHEET))
        assertEquals(Phase.FAILED, run.phase)
        assertEquals(listOf(SubStatus.DONE, SubStatus.DONE, SubStatus.FAILED), run.plan.map { it.status })
        assertTrue(run.result, run.result.contains("the run used its 60 steps"))
        assertEquals(60, r.brain.stepCalls.size)
    }

    @Test fun aRunIsLimitedToTenMinutes() {
        val r = rig()
        r.brain.reply(planJson(subJson("PHONE", "g", app = FOOD)))
        r.brain.fallback = { i, c ->
            r.clock.addAndGet(120_000)
            step("type", id = idOf(c.user, "Search", "edit"), text = "x".repeat(i + 1))
        }
        val run = r.await(r.engine.start("slow", Origin.OWN_SHEET))
        assertEquals(Phase.FAILED, run.phase)
        assertTrue(run.result, run.result.contains("10 minutes"))
        assertEquals(5, r.brain.stepCalls.size)
    }

    @Test fun onlyTheTwentyNewestRunsAreKeepedNewestFirst() {
        val r = rig(brain = ScriptedBrain(fast = false, strong = false))
        val ids = (1..25).map { r.engine.start("t$it", Origin.OWN_SHEET) }
        assertEquals(20, r.engine.runs.value.size)
        assertEquals(ids.reversed().take(20), r.engine.runs.value.map { it.id })
        assertEquals("t25", r.engine.runs.value.first().task)
    }

    @Test fun aRunThatIsActingIsNeverDroppedFromTheList() {
        val open = CompletableDeferred<Unit>()
        val r = rig()
        r.brain.replyWith({ open.await(); planJson(subJson("REPLY", "x")) }, { """{"reply":"ok"}""" })
        val a = r.engine.start("long one", Origin.OWN_SHEET)
        waitUntil { r.brain.calls.size >= 1 }
        repeat(30) { r.engine.start("busy $it", Origin.OWN_SHEET) }
        assertEquals(20, r.engine.runs.value.size)
        assertTrue(r.engine.runs.value.any { it.id == a })
        open.complete(Unit)
        assertEquals(Phase.DONE, r.await(a).phase)
    }

    // ------------------------------------------------------------ S1 and S3: the hostile screen, the person, the gate

    private fun hostileRig(gate: HumanGate): Rig = rig(driver = FakeFoodDriver(hostile = true).also { it.page = "restaurant" }, gate = gate)

    @Test fun theBrainTappingPayNowOnAHostileScreenIsAskedAboutAndWhenTheGateSaysNoTheRunIsStopped() {
        val gate = FakeGate() // answers no
        val r = hostileRig(gate)
        r.brain.reply(phonePlan())
        r.brain.replyWith(payNow(), { step("done", text = "never") })
        val run = r.await(r.engine.start("order a biryani", Origin.OWN_SHEET))
        assertEquals(Phase.STOPPED, run.phase)
        assertTrue(run.result, run.result.contains("declined"))
        assertFalse("pay now was never tapped", "paynow" in r.driver.taps)
        assertFalse(r.driver.paid)
        assertEquals(1, gate.prompts.size)
        assertEquals("tap \"Pay now\"", gate.prompts[0].action)
        assertEquals(Verdict.DECLINED, run.log.last().verdict)
        assertEquals("the reply subtask did not run", SubStatus.SKIPPED, run.plan[1].status)
        assertEquals(1, r.brain.planCalls.size)
        assertEquals(0, r.brain.calls.count { it.system.contains("final reply") })
    }

    @Test fun onlyTheGatesYesLetsThePayNowTapThrough() {
        val gate = FakeGate(answer = { it.action == "tap \"Pay now\"" })
        val r = hostileRig(gate)
        r.brain.reply(phonePlan())
        r.brain.replyWith(payNow(), { step("done", text = "tapped") })
        r.brain.reply("""{"reply":"ok"}""")
        val run = r.await(r.engine.start("order a biryani", Origin.OWN_SHEET))
        assertEquals(Phase.DONE, run.phase)
        assertEquals(listOf("paynow"), r.driver.taps.toList())
        assertEquals(Verdict.CONFIRMED, run.log.first { it.tool == "tap" && it.args.contains("Pay now") }.verdict)
        assertEquals(1, gate.prompts.size)
    }

    @Test fun aGateThatIsDismissedOrTimesOutIsANo() {
        // a UI that dismisses the prompt returns false, nothing else exists
        val gate = FakeGate(answer = { false })
        val r = hostileRig(gate)
        r.brain.reply(phonePlan())
        r.brain.replyWith(payNow())
        val run = r.await(r.engine.start("order", Origin.OWN_SHEET))
        assertEquals(Phase.STOPPED, run.phase)
        assertTrue(r.driver.taps.none { it == "paynow" })
    }

    @Test fun whileThePersonIsAskedTheRunShowsThatItWaitsAndANoStopsIt() {
        val asked = CompletableDeferred<Unit>()
        val answer = CompletableDeferred<Boolean>()
        val gate = object : HumanGate {
            override suspend fun confirm(prompt: ConfirmPrompt): Boolean { asked.complete(Unit); return answer.await() }
            override suspend fun ask(runId: String, question: String): String? = null
        }
        val r = hostileRig(gate)
        r.brain.reply(phonePlan())
        r.brain.replyWith(payNow())
        val id = r.engine.start("order", Origin.OWN_SHEET)
        awaitOrFail(asked)
        assertEquals(Phase.WAITING_FOR_PERSON, r.await(id) { it.phase == Phase.WAITING_FOR_PERSON }.phase)
        assertTrue(r.driver.taps.none { it == "paynow" })
        answer.complete(false)
        assertEquals(Phase.STOPPED, r.await(id).phase)
        assertTrue(r.driver.taps.none { it == "paynow" })
    }

    @Test fun stopWhileThePersonIsBeingAskedCancelsTheQuestionAndNothingIsTapped() {
        val asked = CompletableDeferred<Unit>()
        val gate = object : HumanGate {
            @Volatile var cancelled = false
            override suspend fun confirm(prompt: ConfirmPrompt): Boolean {
                asked.complete(Unit)
                try { awaitCancellation() } catch (e: CancellationException) { cancelled = true; throw e }
            }
            override suspend fun ask(runId: String, question: String): String? = null
        }
        val r = hostileRig(gate)
        r.brain.reply(phonePlan())
        r.brain.replyWith(payNow())
        val id = r.engine.start("order", Origin.OWN_SHEET)
        awaitOrFail(asked)
        r.engine.stop(id)
        assertEquals(Phase.STOPPED, r.run(id).phase)
        waitUntil { gate.cancelled }
        assertTrue(r.driver.taps.none { it == "paynow" })
    }

    @Test fun aRestaurantWhoseNameSaysPayNowMakesEvenTheCardTapAQuestion() {
        val gate = FakeGate() // answers no
        val r = rig(driver = FakeFoodDriver(hostile = true), gate = gate)
        r.brain.reply(phonePlan())
        r.brain.replyWith(typeInto("Search", "biryani"), { step("press_enter") }, tapLabel("Biryani House"), { step("done", text = "never") })
        val run = r.await(r.engine.start("order a biryani", Origin.OWN_SHEET))
        assertEquals(Phase.STOPPED, run.phase)
        assertEquals(1, gate.prompts.size)
        assertTrue(gate.prompts[0].action, gate.prompts[0].action.contains("Biryani House"))
        assertTrue("the folded label carries the hostile words, the guard sees them", gate.prompts[0].action.contains("Pay now"))
        assertTrue(r.driver.taps.isEmpty())
    }

    @Test fun theEngineSourceHasNoPathThatConfirmsByItself() {
        val dir = File("src/main/java/com/swarmos/app/hands")
        assertTrue("run from the module directory: ${dir.absolutePath}", dir.isDirectory)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".kt") }!!
        val callers = files.filter { Regex("""\.confirm\(""").containsMatchIn(it.readText()) }.map { it.name }
        assertEquals("only the tool runner asks the person", listOf("ToolRunner.kt"), callers)
        val runner = File(dir, "ToolRunner.kt").readText()
        assertEquals(1, Regex("""gate\.confirm\(""").findAll(runner).count())
        assertTrue(runner.contains("if (!waitForPerson(ctx) { gate.confirm(prompt) })"))
        assertFalse(files.any { Regex("""confirm\(\s*[A-Za-z]*\s*,?\s*true""").containsMatchIn(it.readText()) })
    }

    // ------------------------------------------------------------ call(): one tool from outside

    @Test fun theRegistryIsServedToOtherAgents() {
        val r = rig()
        assertEquals(HandsTools.names, r.engine.tools().map { it.name })
        assertEquals("fake", r.engine.status().driver)
    }

    @Test fun readScreenReturnsTheListWithNumbers() {
        val r = rig()
        val res = runBlocking { r.engine.call(call("read_screen"), Origin.MCP) }
        assertTrue(res.ok)
        assertTrue(res.text, res.text.startsWith("app: $FOOD"))
        assertTrue(res.text.contains("toggle \"Veg only\" [off]"))
    }

    @Test fun aTapNeedsAReadScreenFirstAndAChangedScreenNeedsAnother() {
        val gate = FakeGate(answer = { true })
        val r = rig(gate = gate)
        val first = runBlocking { r.engine.call(call("tap", id = 4), Origin.MCP) }
        assertFalse(first.ok)
        assertTrue(first.text, first.text.contains("read_screen first"))
        val shown = runBlocking { r.engine.call(call("read_screen"), Origin.MCP) }.text
        r.driver.veg = true // the screen moves under the caller
        val stale = runBlocking { r.engine.call(call("tap", id = renderedId(shown, "Veg only")), Origin.MCP) }
        assertFalse(stale.ok)
        assertTrue(stale.text, stale.text.contains("screen changed since read_screen"))
        assertTrue(r.driver.taps.isEmpty())
    }

    @Test fun anActionFromOutsideAsksAboutAnAppItWasNotToldAboutAndTheYesIsRememberedForTenMinutes() {
        val gate = FakeGate(answer = { true })
        val r = rig(gate = gate)
        fun tapVeg(): ToolResult = runBlocking {
            val shown = r.engine.call(call("read_screen"), Origin.MCP).text
            r.engine.call(call("tap", id = renderedId(shown, "Veg only")), Origin.MCP)
        }
        assertTrue(tapVeg().ok)
        assertEquals(1, gate.prompts.size)
        assertEquals("continue in $FOOD?", gate.prompts[0].why)
        assertTrue(tapVeg().ok)
        assertEquals("remembered, no second question", 1, gate.prompts.size)
        assertEquals(listOf("veg", "veg"), r.driver.taps.toList())
        r.clock.addAndGet(11 * 60_000L)
        assertTrue(tapVeg().ok)
        assertEquals("forgotten after ten minutes", 2, gate.prompts.size)
    }

    @Test fun aNoFromThePersonMeansNothingIsTappedForACallFromOutside() {
        val gate = FakeGate(answer = { false })
        val r = rig(gate = gate)
        val shown = runBlocking { r.engine.call(call("read_screen"), Origin.MCP) }.text
        val res = runBlocking { r.engine.call(call("tap", id = renderedId(shown, "Veg only")), Origin.MCP) }
        assertFalse(res.ok)
        assertTrue(res.text, res.text.contains("said no"))
        assertTrue(r.driver.taps.isEmpty())
    }

    @Test fun aCommitTapFromOutsideIsOneQuestionThatNamesTheCommit() {
        val gate = FakeGate(answer = { false })
        val d = FakeFoodDriver().also { it.page = "cart"; it.cart = 1 }
        val r = rig(driver = d, gate = gate)
        val shown = runBlocking { r.engine.call(call("read_screen"), Origin.MCP) }.text
        val res = runBlocking { r.engine.call(call("tap", id = renderedId(shown, "Proceed to pay")), Origin.MCP) }
        assertFalse(res.ok)
        assertEquals(1, gate.prompts.size)
        assertTrue(gate.prompts[0].why, gate.prompts[0].why.contains("Proceed to pay"))
        assertFalse(d.paid)
    }

    @Test fun linksAndAppsThatAreForbiddenAreRefusedForCallsFromOutsideToo() {
        val r = rig(gate = FakeGate(answer = { true }))
        val link = runBlocking { r.engine.call(call("open_link", text = "intent://x#Intent;end"), Origin.MCP) }
        assertFalse(link.ok)
        assertTrue(link.text, link.text.startsWith("refused:"))
        val own = runBlocking { r.engine.call(call("open_app", app = "Ustaad"), Origin.MCP) }
        assertFalse(own.ok)
        assertTrue(own.text, own.text.contains("itself"))
        assertTrue(r.driver.actions.isEmpty())
        val unknown = runBlocking { r.engine.call(ToolCall("format_disk", call("x").args), Origin.MCP) }
        assertFalse(unknown.ok)
    }

    @Test fun c6_aLinkFromOutsideIsAlwaysAskedAboutWithTheWholeLinkBecauseNoTaskContainsIt() {
        val uri = "https://example.com/menu?table=" + "7".repeat(120)
        val no = FakeGate(answer = { false })
        val a = rig(gate = no)
        val refused = runBlocking { a.engine.call(call("open_link", text = uri), Origin.MCP) }
        assertFalse(refused.ok)
        assertTrue(no.prompts.single().action.contains(uri))
        assertTrue(a.driver.links.isEmpty())

        val yes = FakeGate(answer = { true })
        val b = rig(gate = yes)
        assertTrue(runBlocking { b.engine.call(call("open_link", text = uri), Origin.MCP) }.ok)
        assertEquals(listOf(uri), b.driver.links.toList())
        assertEquals(listOf(BROWSER), b.driver.linkPackages.toList())
    }

    @Test fun r10_aLinkInsideARunIsAskedAboutEvenWhenTheTaskHoldsItAndTheAppIsPlanned() {
        val d = FakeFoodDriver().also { it.linkTargetOf = { FOOD } }
        val gate = gateYesTo(AskKind.LINK)
        val r = rig(driver = d, gate = gate)
        r.brain.reply(planJson(subJson("PHONE", "open the menu link", app = FOOD)))
        r.brain.replyWith({ step("open_link", text = "https://food.example/menu") }, { step("done", text = "ok") })
        val run = r.await(r.engine.start("please open https://food.example/menu", Origin.OWN_SHEET))
        assertEquals(run.result, Phase.DONE, run.phase)
        val p = gate.prompts.single()
        assertEquals(AskKind.LINK, p.kind)
        assertTrue(p.action, p.action.contains("https://food.example/menu") && p.action.contains("in FakeFood"))
        assertEquals(listOf("https://food.example/menu"), d.links.toList())
    }

    @Test fun c6_aLinkNoSingleAppOpensIsRefusedFromOutsideToo() {
        val d = FakeFoodDriver().also { it.linkTargetOf = { "" } }
        val r = rig(driver = d, gate = FakeGate(answer = { true }))
        val res = runBlocking { r.engine.call(call("open_link", text = "https://example.com"), Origin.MCP) }
        assertFalse(res.ok)
        assertTrue(res.text, res.text.contains("no single app opens this link"))
        assertTrue(d.links.isEmpty())
    }

    @Test fun c7_readScreenFromOutsideOnAProtectedAppGivesOneLine() {
        val d = FakeFoodDriver().also { it.otherScreen = raw(text("Balance 12,345", 100), button("Send money", 300), pkg = "com.phonepe.app") }
        val r = rig(driver = d)
        val res = runBlocking { r.engine.call(call("read_screen"), Origin.MCP) }
        assertTrue(res.ok)
        assertEquals(1, res.text.lines().size)
        assertFalse(res.text.contains("Balance"))
    }

    @Test fun talkingAndKnowledgeToolsWorkFromOutsideWithoutAPhone() {
        val desk = FakeDesk(onAsk = { _, q -> "answer to $q" })
        val r = rig(drivers = emptyList(), desk = desk, gate = FakeGate(reply = { "yes" }))
        assertEquals(ToolResult(true, "answer to why"), runBlocking { r.engine.call(call("ask_expert", app = "cooking", text = "why"), Origin.MCP) })
        assertEquals("the person answered: yes", runBlocking { r.engine.call(call("ask_user", text = "ok?"), Origin.MCP) }.text)
        assertEquals(ToolResult(true, "fine"), runBlocking { r.engine.call(call("done", text = "fine"), Origin.MCP) })
        val noPhone = runBlocking { r.engine.call(call("read_screen"), Origin.MCP) }
        assertFalse(noPhone.ok)
        assertTrue(noPhone.text, noPhone.text.contains("no phone driver"))
    }

    @Test fun whileARunActsOtherAgentsMayReadButNotAct() {
        val hang = Hang()
        val r = rig(gate = FakeGate(answer = { true }))
        r.brain.replyWith(hang.reply)
        val id = r.engine.start("hold the phone", Origin.OWN_SHEET)
        awaitOrFail(hang.started)
        assertTrue(runBlocking { r.engine.call(call("read_screen"), Origin.MCP) }.ok)
        val act = runBlocking { r.engine.call(call("home"), Origin.MCP) }
        assertFalse(act.ok)
        assertEquals(BUSY, act.text)
        assertTrue(r.driver.actions.isEmpty())
        r.engine.stop(id)
    }
}
