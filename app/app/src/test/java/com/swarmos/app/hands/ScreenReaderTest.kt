package com.swarmos.app.hands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenReaderTest {
    private fun card(top: Int, vararg texts: String, handle: String = "card$top") = rn(
        cls = "android.view.ViewGroup", clickable = true, l = 0, t = top, r = W, b = top + 200, handle = handle,
        children = texts.mapIndexed { i, s -> text(s, top + 10 + i * 60) },
    )

    // ------------------------------------------------------------ folding

    @Test fun clickableContainerFoldsItsTextChildrenIntoOneButton() {
        val s = ScreenReader.compact(raw(card(400, "Biryani House", "4.3 star", "30 min")))
        assertEquals(1, s.elements.size)
        val e = s.elements[0]
        assertEquals(Role.BUTTON, e.role)
        assertEquals("Biryani House 4.3 star 30 min", e.label)
        assertTrue(Flag.CLICKABLE in e.flags)
        assertEquals("card400", e.handle)
    }

    @Test fun joinedLabelIsCutAtEightyCharacters() {
        val long = "x".repeat(60)
        val s = ScreenReader.compact(raw(card(400, long, long)))
        assertEquals(80, s.elements[0].label.length)
    }

    @Test fun ownTextOfAButtonBeatsItsChildren() {
        val b = rn(text = "Add", cls = "android.widget.Button", clickable = true, l = 0, t = 500, r = 300, b = 580, children = listOf(text("inner", 510)))
        val s = ScreenReader.compact(raw(b))
        assertEquals(listOf("Add"), s.elements.map { it.label })
    }

    @Test fun contentDescriptionLabelsAnIconButton() {
        val b = rn(desc = "Open cart", cls = "android.widget.ImageButton", clickable = true, l = 0, t = 50, r = 100, b = 150)
        assertEquals("Open cart", ScreenReader.compact(raw(b)).elements[0].label)
    }

    @Test fun nestedClickableIsListedOnceAndNotFoldedIntoTheCard() {
        val inner = button("Add", 450, l = 800, r = 1000, handle = "inner")
        val outer = rn(cls = "android.view.ViewGroup", clickable = true, l = 0, t = 400, r = W, b = 600, handle = "outer", children = listOf(text("Biryani House", 410), inner))
        val s = ScreenReader.compact(raw(outer))
        assertEquals(listOf("Biryani House", "Add"), s.elements.map { it.label })
    }

    @Test fun emptyClickableWrapperAroundAControlIsNotListed() {
        val wrapper = rn(cls = "android.widget.FrameLayout", clickable = true, l = 0, t = 400, r = W, b = 600, handle = "wrap", children = listOf(button("Add", 450, handle = "inner")))
        val s = ScreenReader.compact(raw(wrapper))
        assertEquals(listOf("inner"), s.elements.map { it.handle })
    }

    @Test fun unlabelledButtonFallsBackToItsResourceName() {
        val b = rn(cls = "android.widget.ImageButton", resId = "com.demo:id/cart_icon", clickable = true, l = 0, t = 50, r = 100, b = 150)
        assertEquals("cart icon", ScreenReader.compact(raw(b)).elements[0].label)
    }

    @Test fun textLeafOutsideAClickableIsListedAsText() {
        val s = ScreenReader.compact(raw(text("Your cart", 100), button("Pay", 200)))
        assertEquals(listOf(Role.TEXT, Role.BUTTON), s.elements.map { it.role })
    }

    @Test fun scrollableContainerIsAListAndItsTextsAreStillListed() {
        val list = rn(cls = "androidx.recyclerview.widget.RecyclerView", scrollable = true, l = 0, t = 300, r = W, b = 2000, children = listOf(text("Item one", 320), text("Item two", 420)))
        val s = ScreenReader.compact(raw(list))
        assertEquals(listOf(Role.LIST, Role.TEXT, Role.TEXT), s.elements.map { it.role })
        assertTrue(Flag.SCROLLABLE in s.elements[0].flags)
        assertEquals("", s.elements[0].label)
        assertTrue(ScreenReader.render(s).contains("1 list [scroll]"))
    }

    // ------------------------------------------------------------ skipping

    @Test fun invisibleZeroAreaAndOffScreenNodesAreSkipped() {
        val s = ScreenReader.compact(raw(
            text("shown", 100),
            rn(text = "hidden", visible = false, l = 0, t = 200, r = 300, b = 260),
            rn(text = "flat", l = 0, t = 300, r = 300, b = 300),
            rn(text = "below", l = 0, t = H + 10, r = 300, b = H + 80),
            rn(text = "right", l = W + 5, t = 400, r = W + 300, b = 460),
            rn(text = "above", l = 0, t = -200, r = 300, b = -10),
            button("Skipped child holder", 500).copy(visible = false, children = listOf(text("inside hidden", 510))),
        ))
        assertEquals(listOf("shown"), s.elements.map { it.label })
    }

    @Test fun nodeOnlyPartlyOnScreenIsKeptAndItsCentreIsInsideTheScreen() {
        val list = rn(cls = "androidx.recyclerview.widget.RecyclerView", scrollable = true, l = 0, t = -500, r = W, b = 3000)
        val e = ScreenReader.compact(raw(list)).elements[0]
        assertTrue(e.cy in 0..H)
        assertTrue(e.cx in 0..W)
    }

    // ------------------------------------------------------------ edit fields and passwords

    @Test fun editShowsItsHintAsLabelAndTypedTextAsValue() {
        val edit = rn(cls = "android.widget.EditText", hint = "Search", text = "biryani", editable = true, clickable = true, l = 40, t = 200, r = 1040, b = 300)
        val s = ScreenReader.compact(raw(edit))
        assertEquals(Role.EDIT, s.elements[0].role)
        assertEquals("Search", s.elements[0].label)
        assertEquals("biryani", s.elements[0].value)
        assertTrue(ScreenReader.render(s).lines()[1] == "1 edit \"Search\" = \"biryani\"")
    }

    @Test fun editShowingOnlyItsHintHasAnEmptyValue() {
        val edit = rn(cls = "android.widget.EditText", hint = "Search", text = "Search", editable = true, l = 40, t = 200, r = 1040, b = 300)
        assertEquals("", ScreenReader.compact(raw(edit)).elements[0].value)
    }

    @Test fun passwordFieldHasBlankValueAndTheTextIsNowhere() {
        val pw = rn(cls = "android.widget.EditText", text = "hunter2secret", hint = "Password", editable = true, password = true, clickable = true, l = 40, t = 300, r = 1040, b = 400)
        val s = ScreenReader.compact(raw(text("Sign in", 100), pw))
        val e = s.elements.first { Flag.PASSWORD in it.flags }
        assertEquals("", e.value)
        assertEquals("Password", e.label)
        assertTrue(Flag.EDITABLE in e.flags)
        val rendered = ScreenReader.render(s)
        assertFalse(rendered.contains("hunter2secret"))
        assertTrue(rendered.contains("[password]"))
        assertFalse(s.elements.any { it.label.contains("hunter2") || it.value.contains("hunter2") })
    }

    @Test fun passwordTextDoesNotReachTheHash() {
        fun hashWith(secret: String) = ScreenReader.compact(raw(
            rn(cls = "android.widget.EditText", text = secret, hint = "Password", editable = true, password = true, l = 40, t = 300, r = 1040, b = 400),
        )).hash
        assertEquals(hashWith("one"), hashWith("a completely different password"))
    }

    @Test fun passwordFieldWithoutHintGetsAGenericLabelNotItsText() {
        val pw = rn(cls = "android.widget.EditText", text = "topsecret", editable = true, password = true, l = 40, t = 300, r = 1040, b = 400)
        val e = ScreenReader.compact(raw(pw)).elements[0]
        assertEquals("password", e.label)
    }

    @Test fun passwordFieldInsideAClickableContainerAddsNothingToItsLabel() {
        val pw = rn(cls = "android.widget.EditText", text = "topsecret", editable = true, password = true, l = 40, t = 320, r = 1040, b = 400, handle = "pw")
        val box = rn(cls = "android.view.ViewGroup", clickable = true, l = 0, t = 300, r = W, b = 600, handle = "box", children = listOf(text("Login form", 310), pw))
        val s = ScreenReader.compact(raw(box))
        val all = s.elements.joinToString(" ") { it.label + it.value } + ScreenReader.render(s)
        assertFalse(all.contains("topsecret"))
        assertEquals("Login form", s.elements.first { it.handle == "box" }.label)
    }

    // ------------------------------------------------------------ cap, order, ids

    @Test fun overTheCapEditableComesFirstThenClickableThenText() {
        val nodes = mutableListOf<RawNode>()
        for (i in 0 until 3) nodes += text("t$i", 100 + i * 100)
        for (i in 0 until 3) nodes += button("b$i", 500 + i * 100)
        for (i in 0 until 2) nodes += rn(cls = "android.widget.EditText", hint = "e$i", editable = true, l = 40, t = 900 + i * 100, r = 1040, b = 980 + i * 100)
        val five = ScreenReader.compact(raw(*nodes.toTypedArray()), max = 5)
        assertEquals(5, five.elements.size)
        assertEquals(2, five.elements.count { it.role == Role.EDIT })
        assertEquals(3, five.elements.count { it.role == Role.BUTTON })
        assertEquals(0, five.elements.count { it.role == Role.TEXT })
        val seven = ScreenReader.compact(raw(*nodes.toTypedArray()), max = 7)
        assertEquals(listOf("t0", "t1"), seven.elements.filter { it.role == Role.TEXT }.map { it.label })
    }

    @Test fun idsAreOneToNInReadingOrderTopThenLeft() {
        val s = ScreenReader.compact(raw(
            text("bottom", 900),
            text("right", 100, l = 600, r = 900),
            text("left", 100, l = 40, r = 300),
            text("middle", 500),
        ))
        assertEquals(listOf("left", "right", "middle", "bottom"), s.elements.map { it.label })
        assertEquals(listOf(1, 2, 3, 4), s.elements.map { it.id })
    }

    @Test fun idsAfterTheCapAreStillOneToN() {
        val nodes = (0 until 10).map { button("b$it", 100 + it * 100) } + (0 until 10).map { text("t$it", 1200 + it * 60) }
        val s = ScreenReader.compact(raw(*nodes.toTypedArray()), max = 12)
        assertEquals((1..12).toList(), s.elements.map { it.id })
    }

    @Test fun sameInputGivesTheSameIdsAndHash() {
        val a = ScreenReader.compact(raw(text("x", 100), button("y", 200)))
        val b = ScreenReader.compact(raw(text("x", 100), button("y", 200)))
        assertEquals(a.hash, b.hash)
        assertEquals(a.elements.map { it.id to it.label }, b.elements.map { it.id to it.label })
    }

    // ------------------------------------------------------------ hash

    private fun editScreen(value: String, checked: Boolean = false, pkg: String = "com.demo") = ScreenReader.compact(raw(
        rn(cls = "android.widget.EditText", hint = "Search", text = value, editable = true, l = 40, t = 200, r = 1040, b = 300),
        rn(text = "Veg only", cls = "android.widget.Switch", checkable = true, checked = checked, clickable = true, l = 40, t = 400, r = 1040, b = 480),
        pkg = pkg,
    ))

    @Test fun hashChangesWhenAnEditValueChanges() {
        assertNotEquals(editScreen("biryan").hash, editScreen("biryani").hash)
    }

    @Test fun hashChangesWhenAToggleFlips() {
        assertNotEquals(editScreen("a", checked = false).hash, editScreen("a", checked = true).hash)
    }

    @Test fun hashChangesWithThePackageAndIsStableOtherwise() {
        assertNotEquals(editScreen("a").hash, editScreen("a", pkg = "com.other").hash)
        assertEquals(editScreen("a").hash, editScreen("a").hash)
        assertEquals(64, editScreen("a").hash.length)
    }

    // ------------------------------------------------------------ render

    @Test fun renderShowsToggleStateDisabledAndScrollMarks() {
        val s = ScreenReader.compact(raw(
            rn(text = "Veg only", cls = "android.widget.Switch", checkable = true, checked = true, clickable = true, l = 40, t = 100, r = 1040, b = 180),
            rn(text = "Jain", cls = "android.widget.Switch", checkable = true, checked = false, clickable = true, l = 40, t = 200, r = 1040, b = 280),
            rn(text = "Pay", cls = "android.widget.Button", clickable = true, enabled = false, l = 40, t = 300, r = 400, b = 380),
            rn(cls = "android.widget.ScrollView", scrollable = true, l = 0, t = 400, r = W, b = 2000),
        ))
        val lines = ScreenReader.render(s).lines()
        assertEquals("app: com.demo", lines[0])
        assertEquals("1 toggle \"Veg only\" [on]", lines[1])
        assertEquals("2 toggle \"Jain\" [off]", lines[2])
        assertEquals("3 button \"Pay\" [disabled]", lines[3])
        assertEquals("4 list [scroll]", lines[4])
    }

    @Test fun renderLineForAButtonHasIdRoleAndLabel() {
        val s = ScreenReader.compact(raw(button("Add to cart", 100)))
        assertEquals("1 button \"Add to cart\"", ScreenReader.render(s).lines()[1])
    }

    @Test fun quotesInALabelCannotBreakOutOfTheLine() {
        val s = ScreenReader.compact(raw(text("say \"hi\" now", 100)))
        assertEquals("1 text \"say 'hi' now\"", ScreenReader.render(s).lines()[1])
    }

    @Test fun renderDropsTextBeforeControlsWhenTooLong() {
        val nodes = mutableListOf<RawNode>(button("Pay", 100))
        for (i in 0 until 40) nodes += text("a long line of text number $i that takes room", 300 + i * 40)
        val s = ScreenReader.compact(raw(*nodes.toTypedArray()))
        val out = ScreenReader.render(s, maxChars = 500)
        assertTrue(out.length <= 500)
        assertTrue(out.contains("1 button \"Pay\""))
        assertTrue(out.contains("more elements not shown"))
    }

    @Test fun unreadableScreenCompactsToNothingAndRendersOneLine() {
        val s = ScreenReader.compact(RawScreen("com.bank", "Login", W, H, null, true))
        assertTrue(s.unreadable)
        assertTrue(s.elements.isEmpty())
        val out = ScreenReader.render(s)
        assertEquals(1, out.lines().size)
        assertTrue(out.contains("cannot be read"))
        assertTrue(out.contains("com.bank"))
    }

    @Test fun unreadableAndEmptyReadableScreensHaveDifferentHashes() {
        val u = ScreenReader.compact(RawScreen("p", "a", W, H, null, true))
        val e = ScreenReader.compact(RawScreen("p", "a", W, H, root(), false))
        assertNotEquals(u.hash, e.hash)
    }

    // ------------------------------------------------------------ the guard text (C1)

    private fun first(vararg kids: RawNode): UiElement = ScreenReader.compact(raw(*kids)).elements.first()

    @Test fun c1_aClickablesGuardTextHoldsEveryDescendantTextAndTheWordsOfItsId() {
        val card = rn(
            cls = "android.view.ViewGroup", clickable = true, l = 0, t = 400, r = W, b = 600, resId = "com.demo:id/btn_place_order",
            children = listOf(text("P", 410), rn(text = "ay", l = 100, t = 410, r = 300, b = 470, desc = "now", hint = "x", resId = "a:id/child_label")),
        )
        val g = first(card).guard
        for (w in listOf("btn place order", "p", "ay", "now", "child label")) assertTrue("'$w' in '$g'", g.contains(w))
    }

    @Test fun c1_theGuardTextIsNormalised() {
        assertEquals("pay now", first(button("P​ay­‮  NOW\u0000", 100)).guard)
        assertEquals("pay", first(button("Ｐａｙ", 100)).guard) // full-width letters
        assertEquals("pay", first(button("pаy", 100)).guard) // a Cyrillic a inside
        assertEquals("pay now button", first(rn(cls = "android.widget.ImageButton", clickable = true, resId = "x:id/PayNowButton", l = 0, t = 50, r = 100, b = 150)).guard)
    }

    @Test fun c1_theGuardTextIsNeverCutAndNeverShown() {
        val long = "word ".repeat(40) + "pay"
        val e = first(button(long, 100))
        assertEquals(80, e.label.length.coerceAtMost(80))
        assertFalse(e.label.contains("pay"))
        assertTrue(e.guard.endsWith("pay"))
        val hidden = first(rn(text = "Add", cls = "android.widget.Button", clickable = true, resId = "a:id/secret_sauce_pay", l = 0, t = 50, r = 400, b = 150))
        assertTrue(hidden.guard.contains("secret sauce pay"))
        val out = ScreenReader.render(ScreenReader.compact(raw(rn(text = "Add", cls = "android.widget.Button", clickable = true, resId = "a:id/secret_sauce_pay", l = 0, t = 50, r = 400, b = 150))))
        assertFalse(out.contains("sauce"))
    }

    @Test fun c1_aTouchOnAChildReachesTheNearestClickableAncestorSoItsTextIsInTheChildsGuard() {
        val amount = rn(cls = "android.widget.EditText", hint = "Amount", editable = true, l = 40, t = 320, r = 1040, b = 400, handle = "amount")
        val inner = rn(cls = "android.view.ViewGroup", clickable = true, text = "Inner", l = 0, t = 300, r = W, b = 600, handle = "inner", children = listOf(amount))
        val outer = rn(cls = "android.view.ViewGroup", clickable = true, text = "Pay", l = 0, t = 250, r = W, b = 700, handle = "outer", children = listOf(inner))
        val s = ScreenReader.compact(raw(outer))
        val child = s.elements.first { it.handle == "amount" }
        assertTrue(child.guard, child.guard.contains("amount") && child.guard.contains("inner"))
        assertFalse("only the nearest clickable ancestor", child.guard.contains("pay"))
        val ownerOfTouch = s.elements.first { it.handle == "inner" }
        assertEquals("a clickable is its own touch target: its ancestors do not add to it", "inner amount", ownerOfTouch.guard)
    }

    @Test fun c1_aNonClickableEditUnderAPayButtonIsJudgedAsThePayButton() {
        val amount = rn(cls = "android.widget.EditText", hint = "Amount", editable = true, l = 40, t = 320, r = 1040, b = 400, handle = "amount")
        val pay = rn(cls = "android.view.ViewGroup", clickable = true, text = "Pay", l = 0, t = 300, r = W, b = 600, handle = "pay", children = listOf(amount))
        val e = ScreenReader.compact(raw(pay)).elements.first { it.handle == "amount" }
        assertTrue(Guard.isCommitText(e.guard))
    }

    @Test fun c1_aPasswordFieldsTextIsNotInAnyGuardText() {
        val pw = rn(cls = "android.widget.EditText", text = "hunter2", editable = true, password = true, hint = "Password", l = 40, t = 320, r = 1040, b = 400)
        val box = rn(cls = "android.view.ViewGroup", clickable = true, l = 0, t = 300, r = W, b = 600, children = listOf(text("Login", 310), pw))
        val s = ScreenReader.compact(raw(box))
        assertTrue(s.elements.none { it.guard.contains("hunter2") })
        assertTrue(s.elements.any { it.guard.contains("password") })
    }

    // ------------------------------------------------------------ secrets on screen (C7)

    @Test fun c7_whatAnUnflaggedFieldCalledOtpHoldsIsNotRead() {
        fun screen(v: String) = ScreenReader.compact(raw(rn(cls = "android.widget.EditText", hint = "OTP", text = v, editable = true, l = 40, t = 300, r = 1040, b = 400)))
        val s = screen("482913")
        assertEquals("", s.elements[0].value)
        assertFalse(ScreenReader.render(s).contains("482913"))
        assertEquals(s.hash, screen("111111").hash)
        val pw = ScreenReader.compact(raw(rn(cls = "android.widget.EditText", desc = "Password", text = "hunter2", editable = true, l = 40, t = 300, r = 1040, b = 400)))
        assertEquals("", pw.elements[0].value)
    }

    @Test fun c7_aCodeInATextThatTalksAboutACodeIsMaskedBeforeAnythingSeesIt() {
        fun full(code: String) = ScreenReader.compactFull(raw(text("Your verification code is $code. Do not share.", 100), button("Copy code $code", 300)))
        val c = full("482913")
        assertEquals("Your verification code is ****. Do not share.", c.screen.elements[0].label)
        assertEquals("Copy code ****", c.screen.elements[1].label)
        assertEquals(setOf("482913"), c.masked)
        assertFalse(ScreenReader.render(c.screen).contains("482913"))
        assertEquals("the hash input holds no code", c.screen.hash, full("555555").screen.hash)
        assertFalse(c.screen.elements.any { it.guard.contains("482913") })
    }

    @Test fun c7_onlyRunsOfFourToEightDigitsAndOnlyNextToACodeWord() {
        val s = ScreenReader.compact(raw(text("Order 482913 confirmed", 100), text("Total 1250", 200), text("OTP 123", 300), text("code 123456789", 400), text("code 1234 and 12345678", 500)))
        assertEquals(listOf("Order 482913 confirmed", "Total 1250", "OTP 123", "code 123456789", "code **** and ****"), s.elements.map { it.label })
    }

    @Test fun c7_anAnswerThatRepeatsAMaskedCodeOrNamesOneIsScrubbed() {
        val m = StepMemory()
        m.remember(setOf("482913"))
        assertEquals("It is ****", m.scrub("It is 482913"))
        assertEquals("Your OTP is ****", m.scrub("Your OTP is 777777"))
        assertEquals("Total 111111", m.scrub("Total 111111"))
    }

    // ------------------------------------------------------------ what a person can read, and the whole screen (R11, R12, R15)

    @Test fun r12_theVisibleTextHasNoResourceIdWordsNoAncestorsAndNothingHidden() {
        val hidden = rn(text = "Checkout", visible = false, l = 0, t = 410, r = 300, b = 470)
        val card = rn(
            cls = "android.view.ViewGroup", clickable = true, l = 0, t = 400, r = W, b = 600, resId = "a:id/btn_place_order",
            children = listOf(text("Add", 410), hidden),
        )
        val e = first(card)
        assertEquals("add", e.visible)
        assertTrue(e.guard, e.guard.contains("btn place order") && e.guard.contains("checkout"))
        // a child under a clickable parent: the parent is in its guard text, not in what it shows
        val amount = rn(cls = "android.widget.EditText", hint = "Amount", editable = true, l = 40, t = 320, r = 1040, b = 400, handle = "amount")
        val pay = rn(cls = "android.view.ViewGroup", clickable = true, text = "Pay", l = 0, t = 300, r = W, b = 600, handle = "pay", children = listOf(amount))
        val child = ScreenReader.compact(raw(pay)).elements.first { it.handle == "amount" }
        assertEquals("amount", child.visible)
        assertTrue(child.guard.contains("pay"))
    }

    @Test fun r15_aHiddenDescendantSayingCheckoutDoesNotMakeAPaymentScreen() {
        val hidden = rn(text = "Checkout", visible = false, l = 0, t = 410, r = 300, b = 470)
        val list = rn(cls = "androidx.recyclerview.widget.RecyclerView", scrollable = true, l = 0, t = 300, r = W, b = 2000, children = listOf(hidden, text("Menu", 320)))
        val c = ScreenReader.compactFull(raw(list, button("Add", 100)))
        assertFalse(c.checkout)
        assertTrue(ScreenReader.compactFull(raw(text("Checkout", 100), button("Add", 300))).checkout)
        assertFalse(Guard().check(call("tap", id = 1), c.screen, c.screen.elements.first { it.label == "Add" }, setOf("com.demo"), emptySet(), emptySet(), Guard.Context(caution = Caution.CAREFUL, checkout = c.checkout)).let { it is Decision.Confirm && it.kind == AskKind.CHECKOUT_SCREEN })
    }

    @Test fun r11_paymentContextAndACodeCaptionAreFoundOnTheCompleteTreeBeforeTheCapCutsAnything() {
        val nodes = ArrayList<RawNode>()
        for (i in 0 until 70) nodes += button("b$i", 100 + i * 30)
        nodes += text("To pay 250", 2300)
        nodes += text("Verification code", 2340)
        val c = ScreenReader.compactFull(raw(*nodes.toTypedArray()), max = 60)
        assertTrue("the captions were cut", c.screen.elements.none { it.label.contains("To pay") || it.label.contains("Verification") })
        assertTrue(c.checkout)
        assertTrue(c.asksForCode)
    }

    @Test fun r11_aScreenThatSaysNothingAboutPaymentOrACodeHasNeitherFlag() {
        val c = ScreenReader.compactFull(raw(text("Chicken Biryani", 100), button("Add", 300)))
        assertFalse(c.checkout)
        assertFalse(c.asksForCode)
    }

    @Test fun r11_aHiddenCodeCaptionDoesNotMakeAProtectedScreen() {
        val c = ScreenReader.compactFull(raw(rn(text = "Enter OTP", visible = false, l = 0, t = 100, r = 300, b = 160), button("Add", 300)))
        assertFalse(c.asksForCode)
    }
}
