package com.swarmos.app.hands

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** A driver whose screens are a function of how many times it was read, so a test can change the phone between the model's read and the action. */
private class ScriptDriver(private val screenAt: (ScriptDriver, Int) -> RawScreen) : PhoneDriver {
    override val name = "script"
    override fun ready() = true
    @Volatile var flip = false
    @Volatile var reads = 0
    data class Tap(val handle: String, val cx: Int, val cy: Int)
    val taps = CopyOnWriteArrayList<Tap>()
    val typed = CopyOnWriteArrayList<String>()
    val scrolls = CopyOnWriteArrayList<Pair<String?, Boolean>>()
    var enters = 0

    override suspend fun read(): RawScreen = screenAt(this, reads++)
    override suspend fun tap(handle: String, cx: Int, cy: Int): Boolean { taps += Tap(handle, cx, cy); return true }
    override suspend fun setText(handle: String, cx: Int, cy: Int, text: String): Boolean { typed += "$handle:$text"; return true }
    override suspend fun enter(): Boolean { enters++; return true }
    override suspend fun scroll(handle: String?, forward: Boolean): Boolean { scrolls += handle to forward; return true }
    override suspend fun back() = true
    override suspend fun home() = true
    override suspend fun openApp(pkg: String) = true
    override suspend fun linkTarget(uri: String) = ""
    override suspend fun openLink(uri: String, pkg: String) = true
    override suspend fun apps(): List<AppInfo> = FAKE_APPS
}

class ToolRunnerTest {
    /** The older tests were written for fewer questions; the careful ones pass CAREFUL. */
    private fun runner(gate: HumanGate = FakeGate(), mode: Caution = Caution.FEWER_QUESTIONS, desk: FakeDesk = FakeDesk()) =
        ToolRunner(Guard(), gate, desk, { setOf(LAUNCHER) }, { 0L }, { }, { mode })

    private fun ctx(d: PhoneDriver, apps: List<AppInfo> = FAKE_APPS, memory: StepMemory = StepMemory()) =
        ToolRunner.Ctx("t", d, setOf(FOOD), Approvals({ 0L }), apps, memory = memory)

    private fun ToolRunner.see(c: ToolRunner.Ctx): Screen = runBlocking { look(c).screen }
    private fun ToolRunner.run(c: ToolRunner.Ctx, call: ToolCall, s: Screen?): ToolRunner.Outcome = runBlocking { execute(c, call, s) }

    // ------------------------------------------------------------ C3: the element is found again, and the fresh one is acted on

    @Test fun c3_aControlThatChangedItsWordsBetweenTheModelsReadAndTheTapIsNotTapped() {
        val d = ScriptDriver { _, n -> raw(button(if (n == 0) "Add" else "Pay", 300, handle = "b$n"), pkg = FOOD) }
        val r = runner(); val c = ctx(d)
        val seen = r.see(c)
        val o = r.run(c, call("tap", id = 1), seen)
        assertFalse(o.ok)
        assertTrue(o.text, o.text.contains("the screen changed"))
        assertTrue(d.taps.isEmpty())
    }

    @Test fun c3_theTapGoesToTheHandleOfTheFreshReadAndNotToTheOldOne() {
        val d = ScriptDriver { _, n -> raw(button("Add", 300, handle = "add-$n"), pkg = FOOD) }
        val r = runner(); val c = ctx(d)
        val seen = r.see(c) // read 0
        assertTrue(r.run(c, call("tap", id = 1), seen).ok)
        assertEquals(listOf("add-1"), d.taps.map { it.handle })
    }

    @Test fun c3_afterTheirYesTheControlIsFoundOnceMoreAndTheNewestHandleIsTheOneTapped() {
        // the tree is rebuilt on every read: "Pay" and "Delete" keep their words and places, their handles change
        val d = ScriptDriver { _, n -> raw(button("Pay", 300, handle = "pay-$n"), button("Delete", 700, handle = "del-$n"), pkg = FOOD) }
        val gate = FakeGate(answer = { true })
        val r = runner(gate); val c = ctx(d)
        val seen = r.see(c) // read 0
        val o = r.run(c, call("tap", id = 1), seen) // read 1 before the question, read 2 after it
        assertTrue(o.ok)
        assertEquals(Verdict.CONFIRMED, o.verdict)
        assertEquals(1, gate.prompts.size)
        assertEquals(listOf("pay-2"), d.taps.map { it.handle })
        assertEquals(3, d.reads)
    }

    @Test fun c3_aControlThatMovedALittleIsTheSameControlAndTheTapGoesToItsFreshCentre() {
        val near = ScriptDriver { _, n -> raw(button("Add", if (n == 0) 300 else 310, handle = "a$n"), pkg = FOOD) }
        val r = runner()
        val c1 = ctx(near)
        assertTrue(r.run(c1, call("tap", id = 1), r.see(c1)).ok)
        assertEquals(ScriptDriver.Tap("a1", 240, 350), near.taps.single()) // the fresh centre, not the old one
    }

    @Test fun r14_aControlThatIsTheOnlyOneOfItsKindIsFoundWhereverItLiesNow() {
        val far = ScriptDriver { _, n -> raw(button("Add", if (n == 0) 300 else 700, handle = "a$n"), pkg = FOOD) }
        val r = runner(); val c = ctx(far)
        assertTrue(r.run(c, call("tap", id = 1), r.see(c)).ok)
        assertEquals(ScriptDriver.Tap("a1", 240, 740), far.taps.single())
    }

    @Test fun r14_aControlThatHadALookalikeOnTheScreenTheModelSawIsFoundByPositionOnly() {
        // two "Add" in the model's read; in the fresh read one is left, 400 px away: it may be the other one
        val d = ScriptDriver { _, n -> if (n == 0) raw(button("Add", 300), button("Add", 600), pkg = FOOD) else raw(button("Add", 700), pkg = FOOD) }
        val r = runner(); val c = ctx(d)
        assertFalse(r.run(c, call("tap", id = 1), r.see(c)).ok)
        assertTrue(d.taps.isEmpty())
    }

    @Test fun c3_anotherControlWithTheSameWordsElsewhereIsNotMistakenForTheOneThatWasChosen() {
        val d = ScriptDriver { _, n -> if (n == 0) raw(button("Add", 300), button("Add", 600), pkg = FOOD) else raw(button("Add", 600), pkg = FOOD) }
        val r = runner(); val c = ctx(d)
        val o = r.run(c, call("tap", id = 1), r.see(c))
        assertFalse(o.ok)
        assertTrue(d.taps.isEmpty())
    }

    @Test fun c3_aScreenThatChangedWhileThePersonWasAskedStopsTheTap() {
        val d = ScriptDriver { self, _ -> raw(button(if (self.flip) "Reorder all" else "Pay", 300), pkg = FOOD) }
        val gate = FakeGate(answer = { d.flip = true; true })
        val r = runner(gate); val c = ctx(d)
        val o = r.run(c, call("tap", id = 1), r.see(c))
        assertEquals(Verdict.CONFIRMED, o.verdict)
        assertFalse(o.ok)
        assertTrue(o.text, o.text.contains("the screen changed"))
        assertTrue(d.taps.isEmpty())
    }

    @Test fun c3_theGuardRunsAgainOnTheFreshScreenSoAScreenThatBecamePaymentContextIsAsked() {
        val d = ScriptDriver { _, n -> if (n == 0) raw(button("Add", 300), pkg = FOOD) else raw(text("To pay 250", 100), button("Add", 300), pkg = FOOD) }
        val gate = FakeGate() // no
        val r = runner(gate); val c = ctx(d)
        val o = r.run(c, call("tap", id = 1), r.see(c))
        assertEquals(Verdict.DECLINED, o.verdict)
        assertTrue(gate.prompts.single().why, gate.prompts.single().why.contains("payment screen"))
        assertTrue(d.taps.isEmpty())
    }

    @Test fun c3_aScreenThatBecameAProtectedAppAfterTheirYesIsNotTapped() {
        val d = ScriptDriver { self, _ -> raw(button("Pay", 300), pkg = if (self.flip) "com.phonepe.app" else FOOD) }
        val gate = FakeGate(answer = { d.flip = true; true })
        val r = runner(gate); val c = ctx(d)
        val o = r.run(c, call("tap", id = 1), r.see(c))
        assertFalse(o.ok)
        assertTrue(d.taps.isEmpty())
    }

    @Test fun c3_aTypeIsDoneOnTheFreshFieldToo() {
        val d = ScriptDriver { _, n -> raw(rn(cls = "android.widget.EditText", hint = "Search", editable = true, clickable = true, l = 40, t = 200, r = 1040, b = 300, handle = "f$n"), pkg = FOOD) }
        val r = runner(); val c = ctx(d)
        assertTrue(r.run(c, call("type", id = 1, text = "biryani"), r.see(c)).ok)
        assertEquals(listOf("f1:biryani"), d.typed.toList())
    }

    // ------------------------------------------------------------ C4: enter

    @Test fun c4_enterNeedsNoQuestionDirectlyAfterATypeIntoASearchFieldAndOnlyThen() {
        val d = FakeFoodDriver()
        val gate = FakeGate()
        val r = runner(gate); val c = ctx(d)
        val typed = r.run(c, call("type", id = 3, text = "biryani"), r.see(c))
        assertTrue(typed.text, typed.ok)
        assertTrue(r.run(c, call("press_enter"), r.see(c)).ok)
        assertEquals("results", d.page)
        assertTrue(gate.prompts.isEmpty())
        // the chain is used up: a second enter is a question
        val second = r.run(c, call("press_enter"), r.see(c))
        assertEquals(Verdict.DECLINED, second.verdict)
        assertEquals(1, gate.prompts.size)
    }

    @Test fun c4_anyOtherStepBetweenTheTypeAndTheEnterBreaksTheChain() {
        val d = FakeFoodDriver()
        val gate = FakeGate()
        val r = runner(gate); val c = ctx(d)
        assertTrue(r.run(c, call("type", id = 3, text = "biryani"), r.see(c)).ok)
        assertTrue(r.run(c, call("tap", id = 4), r.see(c)).ok) // the Veg only toggle
        assertEquals(Verdict.DECLINED, r.run(c, call("press_enter"), r.see(c)).verdict)
        assertEquals(0, d.actions.count { it == "enter" })
    }

    @Test fun c4_aStepThatDidNotSucceedAndAReadLeaveTheChainAlone() {
        val d = FakeFoodDriver()
        val r = runner(); val c = ctx(d)
        assertTrue(r.run(c, call("type", id = 3, text = "biryani"), r.see(c)).ok)
        assertTrue(r.run(c, call("wait", id = 1), null).ok)
        assertTrue(r.run(c, call("press_enter"), r.see(c)).ok)
    }

    // ------------------------------------------------------------ C5: scroll

    private fun lists(withSlider: Boolean = true, withBig: Boolean = true): (ScriptDriver, Int) -> RawScreen = { _, n ->
        val kids = ArrayList<RawNode>()
        kids += rn(cls = "androidx.recyclerview.widget.RecyclerView", scrollable = true, l = 0, t = 200, r = 500, b = 400, handle = "small-$n")
        if (withSlider) kids += rn(desc = "Slide to pay", scrollable = true, l = 0, t = 500, r = W, b = 2300, handle = "slider-$n")
        if (withBig) kids += rn(cls = "androidx.recyclerview.widget.RecyclerView", scrollable = true, l = 0, t = 500, r = W, b = 1900, handle = "big-$n")
        raw(*kids.toTypedArray(), pkg = FOOD)
    }

    @Test fun c5_withoutAnIdTheLargestPlainListIsScrolledByItsFreshHandleAndNeverTheSlider() {
        val d = ScriptDriver(lists())
        val r = runner(); val c = ctx(d)
        assertTrue(r.run(c, call("scroll", direction = "down"), r.see(c)).ok)
        assertEquals(listOf("big-1" to true), d.scrolls.toList())
    }

    @Test fun r8_withNoListThereIsNoSwipeAndTheDriverIsNeverAskedToScroll() {
        val d = ScriptDriver { _, _ -> raw(text("Menu", 100), pkg = FOOD) }
        val gate = FakeGate(answer = { true })
        val r = runner(gate); val c = ctx(d)
        val o = r.run(c, call("scroll", direction = "up"), r.see(c))
        assertEquals(Verdict.REFUSED, o.verdict)
        assertTrue(o.text, o.text.contains("no blind swipes"))
        assertTrue(d.scrolls.isEmpty())
        assertTrue(gate.prompts.isEmpty())
    }

    @Test fun r8_withOnlyASliderOnTheScreenNothingIsScrolledInAnyMode() {
        for (mode in Caution.entries) {
            val d = ScriptDriver { _, n -> raw(rn(desc = "Delete", scrollable = true, l = 0, t = 500, r = W, b = 2300, handle = "del-$n"), pkg = FOOD) }
            val gate = FakeGate(answer = { true })
            val r = runner(gate, mode); val c = ctx(d)
            val o = r.run(c, call("scroll", direction = "down"), r.see(c))
            assertEquals(mode.name, Verdict.REFUSED, o.verdict)
            assertTrue(d.scrolls.isEmpty())
            assertTrue(gate.prompts.isEmpty())
        }
    }

    @Test fun c5_scrollingAnElementByIdUsesItsFreshHandleAndAPaymentSliderByIdAsks() {
        val d = ScriptDriver(lists())
        val gate = FakeGate()
        val r = runner(gate); val c = ctx(d)
        val seen = r.see(c)
        val plain = seen.elements.first { it.handle.startsWith("big") }
        assertTrue(r.run(c, call("scroll", direction = "down", id = plain.id), seen).ok)
        assertEquals(listOf("big-1" to true), d.scrolls.toList()) // read 0 was the model's, read 1 the check just before the scroll
        val slider = seen.elements.first { it.handle.startsWith("slider") }
        val before = d.scrolls.size
        assertEquals(Verdict.DECLINED, r.run(c, call("scroll", direction = "down", id = slider.id), seen).verdict)
        assertEquals(before, d.scrolls.size)
    }

    // ------------------------------------------------------------ C7: what the model and the log see

    @Test fun c7_readScreenOnAProtectedAppIsOneLineWithNoElements() {
        val d = ScriptDriver { _, _ -> raw(text("Balance 12,345", 100), button("Send money", 300), pkg = "com.phonepe.app") }
        val r = runner(); val c = ctx(d)
        val o = r.run(c, call("read_screen"), null)
        assertTrue(o.ok)
        assertEquals(1, o.text.lines().size)
        assertTrue(o.text, o.text.contains("Ustaad does not read it"))
        assertFalse(o.text.contains("Balance"))
        assertTrue(o.screen!!.elements.isEmpty())
    }

    @Test fun c7_aScreenOfAnAppBlockedByItsLabelIsNotReadEither() {
        val d = ScriptDriver { _, _ -> raw(text("Balance 12,345", 100), pkg = "com.example.cash") }
        val r = runner()
        val shown = r.run(ctx(d, apps = FAKE_APPS + AppInfo("com.example.cash", "Bank")), call("read_screen"), null)
        assertTrue(shown.screen!!.unreadable)
        assertFalse(shown.text.contains("Balance"))
        assertTrue(r.run(ctx(d), call("read_screen"), null).text.contains("Balance"))
    }

    @Test fun c7_readScreenWithACodeInItShowsStarsAndTheCodeIsRememberedForTheAnswers() {
        val d = ScriptDriver { _, _ -> raw(text("Your code is 482913", 100), button("Add", 300), pkg = FOOD) }
        val mem = StepMemory()
        val r = runner(); val c = ctx(d, memory = mem)
        val o = r.run(c, call("read_screen"), null)
        assertFalse(o.text, o.text.contains("482913"))
        assertTrue(o.text.contains("Your code is ****"))
        assertEquals("the code is ****", r.run(c, call("done", text = "the code is 482913"), null).text)
        assertEquals("it said ****", mem.scrub("it said 482913"))
    }

    @Test fun c7_aQuestionThatRepeatsACodeIsScrubbedBeforeThePersonSeesIt() {
        val d = ScriptDriver { _, _ -> raw(text("Your code is 482913", 100), pkg = FOOD) }
        val gate = FakeGate(reply = { "ok" })
        val r = runner(gate); val c = ctx(d)
        r.run(c, call("read_screen"), null)
        r.run(c, call("ask_user", text = "Shall I use 482913?"), null)
        assertEquals(listOf("Shall I use ****?"), gate.questions.toList())
    }

    // ------------------------------------------------------------ C9: the label of the app that was resolved

    @Test fun c9_anAppNamedBankIsNotOpenedEvenThoughItsPackageIsHarmless() {
        val d = ScriptDriver { _, _ -> raw(text("Home", 100), pkg = LAUNCHER) }
        val apps = FAKE_APPS + AppInfo("com.example.cash", "Bank")
        val r = runner(FakeGate(answer = { true })); val c = ToolRunner.Ctx("t", d, setOf("com.example.cash"), Approvals({ 0L }), apps)
        val o = r.run(c, call("open_app", app = "Bank"), null)
        assertEquals(Verdict.REFUSED, o.verdict)
        assertTrue(o.text, o.text.contains("protected"))
        val byPackage = r.run(c, call("open_app", app = "com.example.cash"), null)
        assertEquals(Verdict.REFUSED, byPackage.verdict)
        assertNull(d.taps.firstOrNull())
    }

    @Test fun r8_aListThatIsGoneBeforeTheScrollIsNotReplacedByASwipe() {
        val d = ScriptDriver { _, n -> if (n == 0) raw(rn(cls = "androidx.recyclerview.widget.RecyclerView", scrollable = true, l = 0, t = 200, r = W, b = 2000, handle = "list"), pkg = FOOD) else raw(text("Menu", 100), pkg = FOOD) }
        val r = runner(); val c = ctx(d)
        val o = r.run(c, call("scroll", direction = "down"), r.see(c))
        assertEquals(Verdict.REFUSED, o.verdict)
        assertTrue(d.scrolls.isEmpty())
    }

    // ------------------------------------------------------------ after the answer: our own window is in front, the app below has moved on (fix round 3)

    private fun carefulRunner(gate: HumanGate, delays: MutableList<Long>) =
        ToolRunner(Guard(), gate, FakeDesk(), { setOf(LAUNCHER) }, { 0L }, { delays += it }, { Caution.CAREFUL })

    private fun results(): FakeFoodDriver = FakeFoodDriver().also { it.page = "results" }

    private fun cardId(s: Screen) = s.elements.first { it.label.startsWith("Biryani House") }.id

    @Test fun r14_theActionIsDoneAfterTheAppHasComeBackToTheFront() {
        val d = results()
        val gate = FakeGate(answer = { d.ownWindowReads = 3; true }) // the window of the question is still in front for three reads
        val delays = CopyOnWriteArrayList<Long>()
        val r = carefulRunner(gate, delays); val c = ctx(d)
        val seen = r.see(c)
        val o = r.run(c, call("tap", id = cardId(seen)), seen)
        assertTrue(o.text, o.ok)
        assertEquals(listOf(400L, 400L, 400L), delays.toList())
        assertEquals(listOf("res:0"), d.taps.toList())
        assertEquals(1, gate.prompts.size)
    }

    @Test fun r14_aTargetThatMovedBeyondTheRadiusWhileTheQuestionWasInFrontIsActedOnAndNotAskedAgain() {
        val d = results()
        val gate = FakeGate(answer = { d.ownWindowReads = 2; d.shiftY = 150; true })
        val r = carefulRunner(gate, CopyOnWriteArrayList()); val c = ctx(d)
        val seen = r.see(c)
        val o = r.run(c, call("tap", id = cardId(seen)), seen)
        assertTrue(o.text, o.ok)
        assertEquals(listOf("res:0"), d.taps.toList())
        assertEquals("the same kind for the same target is asked once, only the position changed", 1, gate.prompts.size)
    }

    @Test fun r14_aMovedTargetThatNowHasATwinIsRefusedAndNothingIsDone() {
        val d = results()
        val gate = FakeGate(answer = { d.ownWindowReads = 1; d.shiftY = 150; d.twinCard = true; true })
        val r = carefulRunner(gate, CopyOnWriteArrayList()); val c = ctx(d)
        val seen = r.see(c)
        val o = r.run(c, call("tap", id = cardId(seen)), seen)
        assertFalse(o.ok)
        assertTrue(o.text, o.text.contains("ambiguous") && o.text.contains("nothing was done"))
        assertTrue(d.taps.isEmpty())
    }

    @Test fun r14_anAppThatNeverComesBackGivesTheClearResultAndNothingIsDone() {
        val d = results()
        val gate = FakeGate(answer = { d.ownWindowForever = true; true })
        val delays = CopyOnWriteArrayList<Long>()
        val r = carefulRunner(gate, delays); val c = ctx(d)
        val seen = r.see(c)
        val o = r.run(c, call("tap", id = cardId(seen)), seen)
        assertFalse(o.ok)
        assertEquals(Verdict.CONFIRMED, o.verdict)
        assertTrue(o.text, o.text.contains("did not come back") && o.text.contains("nothing was done"))
        assertEquals("eight reads, seven gaps", 7, delays.size)
        assertTrue(d.taps.isEmpty())
    }

    @Test fun r14_afterAnAnswerToAskUserTheRunnerWaitsForTheAppToo() {
        val d = results()
        val gate = FakeGate(reply = { d.ownWindowReads = 2; "veg" })
        val delays = CopyOnWriteArrayList<Long>()
        val r = carefulRunner(gate, delays); val c = ctx(d)
        val o = r.run(c, call("ask_user", text = "veg or non-veg?"), null)
        assertTrue(o.ok)
        assertEquals("the person answered: veg", o.text)
        assertEquals(listOf(400L, 400L), delays.toList())
    }

    @Test fun r14_anAnswerToAskUserIsStillReturnedWhenTheAppDoesNotComeBack() {
        val d = results()
        val gate = FakeGate(reply = { d.ownWindowForever = true; "veg" })
        val r = carefulRunner(gate, CopyOnWriteArrayList()); val c = ctx(d)
        val o = r.run(c, call("ask_user", text = "veg or non-veg?"), null)
        assertTrue(o.text, o.text.startsWith("the person answered: veg") && o.text.contains("did not come back"))
    }

    @Test fun r14_aRunnerWithoutAQuestionNeverWaits() {
        val d = results()
        val delays = CopyOnWriteArrayList<Long>()
        val r = ToolRunner(Guard(), FakeGate(), FakeDesk(), { setOf(LAUNCHER) }, { 0L }, { delays += it }, { Caution.FEWER_QUESTIONS }); val c = ctx(d)
        val seen = r.see(c)
        assertTrue(r.run(c, call("tap", id = cardId(seen)), seen).ok)
        assertTrue(delays.isEmpty())
    }

    // ------------------------------------------------------------ R6: the target must be the only one of its kind

    @Test fun r6_aTwinWithTheSameWordsWithinReachMeansNothingIsDoneAndTheModelIsToldTheScreenIsAmbiguous() {
        // two "Continue" buttons 10 px apart in the fresh read: which one was chosen cannot be told
        val d = ScriptDriver { _, n -> if (n == 0) raw(button("Add", 300), pkg = FOOD) else raw(button("Add", 300, handle = "a"), button("Add", 310, handle = "b"), pkg = FOOD) }
        val r = runner(); val c = ctx(d)
        val o = r.run(c, call("tap", id = 1), r.see(c))
        assertFalse(o.ok)
        assertTrue(o.text, o.text.contains("ambiguous") && o.text.contains("nothing was done"))
        assertTrue(d.taps.isEmpty())
    }

    @Test fun r6_aTwinThatMovedCloserToTheOldCentreIsNotChosenEither() {
        val d = ScriptDriver { _, n -> if (n == 0) raw(button("Add", 300, handle = "x"), pkg = FOOD) else raw(button("Add", 315, handle = "x"), button("Add", 300, handle = "y"), pkg = FOOD) }
        val r = runner(); val c = ctx(d)
        assertFalse(r.run(c, call("tap", id = 1), r.see(c)).ok)
        assertTrue(d.taps.isEmpty())
    }

    @Test fun r6_aTwinNextToTheFoundControlIsAmbiguousToo() {
        // the found control is 20 px from the old centre, its twin 40 px from it but only 20 px from the found one
        val d = ScriptDriver { _, n -> if (n == 0) raw(button("Add", 300), pkg = FOOD) else raw(button("Add", 320, handle = "a"), button("Add", 340, handle = "b"), pkg = FOOD) }
        val r = runner(); val c = ctx(d)
        val o = r.run(c, call("tap", id = 1), r.see(c))
        assertFalse(o.ok)
        assertTrue(o.text, o.text.contains("ambiguous"))
        assertTrue(d.taps.isEmpty())
    }

    @Test fun r6_twoControlsWithTheSameWordsFarApartAreNotTwins() {
        val d = ScriptDriver { _, _ -> raw(button("Add", 300, handle = "a"), button("Add", 700, handle = "b"), pkg = FOOD) }
        val r = runner(); val c = ctx(d)
        val seen = r.see(c)
        assertTrue(r.run(c, call("tap", id = seen.elements.first { it.handle == "b" }.id), seen).ok)
        assertEquals(listOf("b"), d.taps.map { it.handle })
    }

    @Test fun r6_aTwinAppearingAfterTheirYesStopsTheTap() {
        val d = ScriptDriver { self, _ -> if (self.flip) raw(button("Pay", 300, handle = "a"), button("Pay", 305, handle = "b"), pkg = FOOD) else raw(button("Pay", 300, handle = "a"), pkg = FOOD) }
        val gate = FakeGate(answer = { d.flip = true; true })
        val r = runner(gate); val c = ctx(d)
        val o = r.run(c, call("tap", id = 1), r.see(c))
        assertFalse(o.ok)
        assertTrue(o.text, o.text.contains("ambiguous"))
        assertTrue(d.taps.isEmpty())
    }

    // ------------------------------------------------------------ R7: a yes covers one kind

    @Test fun r7_aYesToTheAppScopeDoesNotCoverACheckoutScreenThatShowsUpAfterIt() {
        // the app is not in the plan: APP_SCOPE. After the yes the screen turns into a payment screen: that is asked on its own.
        val d = ScriptDriver { _, n -> if (n < 2) raw(button("Continue", 300), pkg = "com.fake.maps") else raw(text("To pay 250", 100), button("Continue", 300), pkg = "com.fake.maps") }
        val gate = gateYesTo(AskKind.APP_SCOPE) // no to everything else
        val r = runner(gate); val c = ctx(d)
        val o = r.run(c, call("tap", id = 1), r.see(c))
        assertEquals(Verdict.DECLINED, o.verdict)
        assertEquals(listOf(AskKind.APP_SCOPE, AskKind.CHECKOUT_SCREEN), gate.kinds)
        assertTrue(d.taps.isEmpty())
    }

    @Test fun r7_whenTheOwnerSaysYesToBothKindsTheTapHappensAfterTwoQuestions() {
        val d = ScriptDriver { _, n -> if (n < 2) raw(button("Continue", 300), pkg = "com.fake.maps") else raw(text("To pay 250", 100), button("Continue", 300, handle = "late"), pkg = "com.fake.maps") }
        val gate = gateYesTo(AskKind.APP_SCOPE, AskKind.CHECKOUT_SCREEN)
        val r = runner(gate); val c = ctx(d)
        val o = r.run(c, call("tap", id = 1), r.see(c))
        assertTrue(o.text, o.ok)
        assertEquals(listOf(AskKind.APP_SCOPE, AskKind.CHECKOUT_SCREEN), gate.kinds)
        assertEquals(listOf("late"), d.taps.map { it.handle })
    }

    @Test fun r7_aYesToTheSameKindIsNotAskedTwice() {
        val d = ScriptDriver { _, _ -> raw(button("Pay now", 300), pkg = FOOD) }
        val gate = gateYesTo(AskKind.COMMIT)
        val r = runner(gate); val c = ctx(d)
        assertTrue(r.run(c, call("tap", id = 1), r.see(c)).ok)
        assertEquals(1, gate.prompts.size)
    }

    // ------------------------------------------------------------ careful mode through the runner (R1) and the question's kind

    @Test fun r1_theRunnerAsksAboutAnUnknownControlInCarefulModeAndTheQuestionNamesItsKind() {
        val d = ScriptDriver { _, _ -> raw(button("Continue", 300), pkg = FOOD) }
        val gate = FakeGate()
        val r = runner(gate, Caution.CAREFUL); val c = ctx(d)
        val o = r.run(c, call("tap", id = 1), r.see(c))
        assertEquals(Verdict.DECLINED, o.verdict)
        assertEquals(AskKind.UNKNOWN_CONTROL, gate.prompts.single().kind)
        assertTrue(d.taps.isEmpty())
        val free = ScriptDriver { _, _ -> raw(button("Add", 300), pkg = FOOD) }
        val c2 = ctx(free)
        assertTrue(r.run(c2, call("tap", id = 1), r.see(c2)).ok)
        assertEquals(1, gate.prompts.size)
    }

    @Test fun r1_theModeIsReadAtEveryStepSoTheSwitchTakesEffectForTheNextAction() {
        var mode = Caution.CAREFUL
        val d = ScriptDriver { _, _ -> raw(button("Continue", 300), pkg = FOOD) }
        val gate = FakeGate()
        val r = ToolRunner(Guard(), gate, FakeDesk(), { setOf(LAUNCHER) }, { 0L }, { }, { mode })
        val c = ctx(d)
        assertEquals(Verdict.DECLINED, r.run(c, call("tap", id = 1), r.see(c)).verdict)
        mode = Caution.FEWER_QUESTIONS
        assertTrue(r.run(c, call("tap", id = 1), r.see(c)).ok)
    }

    // ------------------------------------------------------------ R9: a question the step model wrote stays on the phone

    @Test fun r9_askExpertFromTheStepModelIsAlwaysMarkedPhoneOnly() {
        val desk = FakeDesk(onAsk = { _, _ -> "an answer" })
        val d = ScriptDriver { _, _ -> raw(text("Menu", 100), pkg = FOOD) }
        val r = runner(desk = desk); val c = ctx(d)
        assertEquals("an answer", r.run(c, call("ask_expert", app = "cooking", text = "how do I cook rice?"), null).text)
        assertEquals(listOf(true), desk.routes.toList())
    }

    // ------------------------------------------------------------ R11: the protected-screen decision is taken on the complete tree

    @Test fun r11_aCodeCaptionBeyondTheCapStillProtectsTheScreenAndTheModelSeesNoElements() {
        val nodes = ArrayList<RawNode>()
        for (i in 0 until 70) nodes += button("b$i", 100 + i * 30)
        nodes += rn(cls = "android.widget.EditText", hint = "Value", text = "482913", editable = true, l = 40, t = 2250, r = 1040, b = 2300)
        nodes += text("Verification code", 2340)
        val d = ScriptDriver { _, _ -> raw(*nodes.toTypedArray(), pkg = FOOD) }
        val r = runner(); val c = ctx(d)
        val looked = runBlocking { r.look(c) }
        assertTrue(looked.screen.unreadable)
        assertTrue(looked.screen.elements.isEmpty())
        assertFalse(ScreenReader.render(looked.screen).contains("482913"))
    }

    @Test fun r11_paymentContextBeyondTheCapStillMakesItAPaymentScreenForTheRunner() {
        val nodes = ArrayList<RawNode>()
        for (i in 0 until 70) nodes += button("b$i", 100 + i * 30)
        nodes += text("To pay 250", 2340)
        val d = ScriptDriver { _, _ -> raw(*nodes.toTypedArray(), pkg = FOOD) }
        val gate = FakeGate()
        val r = runner(gate, Caution.FEWER_QUESTIONS); val c = ctx(d)
        val seen = r.see(c)
        val o = r.run(c, call("tap", id = seen.elements.first { it.label == "b0" }.id), seen)
        assertEquals(Verdict.DECLINED, o.verdict)
        assertEquals(AskKind.CHECKOUT_SCREEN, gate.prompts.single().kind)
        assertTrue(d.taps.isEmpty())
    }

    // ------------------------------------------------------------ R12 and R13: numbers in what is typed and in the log

    @Test fun r12_aCardNumberWrittenOverSeveralLinesIsRefusedWhateverTheFieldIsCalledAndNeverReachesTheLog() {
        val d = ScriptDriver { _, _ -> raw(rn(cls = "android.widget.EditText", hint = "Search", editable = true, clickable = true, l = 40, t = 200, r = 1040, b = 300), pkg = FOOD) }
        val r = runner(); val c = ctx(d)
        val o = r.run(c, call("type", id = 1, text = "4111\n1111\n1111\n1111"), r.see(c))
        assertEquals(Verdict.REFUSED, o.verdict)
        assertTrue(d.typed.isEmpty())
        assertFalse(o.args.contains("4111") || o.args.contains("1111"))
    }

    @Test fun r13_everyRunOfFourOrMoreDigitsInATypeArgumentIsMaskedInTheLog() {
        val d = ScriptDriver { _, _ -> raw(rn(cls = "android.widget.EditText", hint = "Search", editable = true, clickable = true, l = 40, t = 200, r = 1040, b = 300), pkg = FOOD) }
        val r = runner(); val c = ctx(d)
        val o = r.run(c, call("type", id = 1, text = "table 12 for 98765432 or 1234"), r.see(c))
        assertTrue(o.text, o.ok)
        assertEquals("1 \"table 12 for **** or ****\"", o.args)
        assertTrue("what is typed is what the model wrote; only the log is masked", d.typed.single().endsWith(":table 12 for 98765432 or 1234"))
    }
}
