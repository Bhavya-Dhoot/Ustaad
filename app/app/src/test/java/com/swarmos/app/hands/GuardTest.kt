package com.swarmos.app.hands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardTest {
    private val own = setOf("com.swarmos.app", "com.ustaad.app")
    private val guard = Guard(own)

    private fun check(
        c: ToolCall,
        s: Screen?,
        e: UiElement? = null,
        plan: Set<String> = setOf(FOOD),
        approved: Set<String> = emptySet(),
    ): Decision = guard.check(c, s, e, plan, approved, setOf(LAUNCHER))

    private fun btn(label: String) = el(1, Role.BUTTON, label, Flag.CLICKABLE)
    private fun tapIn(pkg: String, label: String, plan: Set<String> = setOf(pkg), approved: Set<String> = emptySet()): Decision {
        val e = btn(label)
        return check(call("tap", id = 1), screenOf(pkg, e), e, plan, approved)
    }

    private fun assertRefuse(d: Decision, fragment: String = "") {
        assertTrue("expected Refuse, got $d", d is Decision.Refuse)
        if (fragment.isNotEmpty()) assertTrue((d as Decision.Refuse).why, d.why.contains(fragment, ignoreCase = true))
    }

    private fun assertConfirm(d: Decision) = assertTrue("expected Confirm, got $d", d is Decision.Confirm)
    private fun assertAllow(d: Decision) = assertEquals(Decision.Allow, d)

    // ------------------------------------------------------------ G1 own UI

    @Test fun g1_noTapTypeEnterScrollOrBackInUstaadsOwnScreen() {
        for (pkg in own) {
            val yes = btn("Yes")
            val field = el(2, Role.EDIT, "Note", Flag.EDITABLE)
            val s = screenOf(pkg, yes, field)
            assertRefuse(check(call("tap", id = 1), s, yes, plan = setOf(pkg)), "Ustaad")
            assertRefuse(check(call("type", id = 2, text = "x"), s, field, plan = setOf(pkg)), "Ustaad")
            assertRefuse(check(call("press_enter"), s, plan = setOf(pkg)), "Ustaad")
            assertRefuse(check(call("scroll", direction = "down"), s, plan = setOf(pkg)), "Ustaad")
            assertRefuse(check(call("back"), s, plan = setOf(pkg)), "Ustaad")
        }
    }

    @Test fun g1_theConfirmationPromptCannotBeTappedByTheAgent() {
        val yes = el(1, Role.BUTTON, "Yes, pay", Flag.CLICKABLE)
        assertRefuse(check(call("tap", id = 1), screenOf("com.swarmos.app", yes), yes))
    }

    @Test fun g1_leavingUstaadIsAllowedSoARunCanStartFromTheTaskSheet() {
        val s = screenOf("com.swarmos.app", btn("Run"))
        assertAllow(check(call("home"), s))
        assertAllow(check(call("open_app", app = FOOD), s))
        assertAllow(check(call("open_link", text = "https://example.com"), s))
    }

    @Test fun g1_openingUstaadItselfIsRefusedFromAnywhere() {
        assertRefuse(check(call("open_app", app = "com.swarmos.app"), screenOf(FOOD), plan = setOf("com.swarmos.app", FOOD)), "itself")
        assertRefuse(check(call("open_app", app = "com.ustaad.app"), null, plan = setOf("com.ustaad.app")))
        assertRefuse(check(call("open_app", app = "COM.SWARMOS.APP"), screenOf(LAUNCHER)))
    }

    @Test fun g1_actionsInAnOrdinaryAppAreNotAffected() {
        assertAllow(tapIn(FOOD, "Add to cart"))
    }

    // ------------------------------------------------------------ G2 protected apps

    private val blockedPackages = listOf(
        "com.phonepe.app", "com.google.android.apps.nbu.paisa.user", "net.one97.paytm", "in.org.npci.upiapp", "com.dreamplug.androidapp",
        "com.google.android.apps.authenticator2", "com.azure.authenticator", "com.authy.authy",
        "com.x8bit.bitwarden", "com.agilebits.onepassword", "com.lastpass.lpandroid", "keepass2android.keepass2android",
        "keepass2android.keepass2android_nonet", "com.kunzisoft.keepass.free", "com.dashlane",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller", "com.android.packageinstaller",
        "com.google.android.packageinstaller", "com.topjohnwu.magisk", "com.android.settings",
    )
    private val blockedByName = listOf(
        "com.sbi.lotusintouch", "com.snapwork.hdfc", "com.axis.mobile", "com.csam.icici.bank.imobile", "com.infrasofttech.centralbank",
        "com.msf.kbank.mobile", "com.kotak.mobile", "com.sbi.SBIFreedomPlus", "com.vivo.wallet", "com.google.android.apps.walletnfcrel",
        "com.example.upi.pay", "com.samsung.android.packageinstaller", "com.android.settings.intelligence",
    )

    @Test fun g2_noActionInAProtectedApp() {
        for (pkg in blockedPackages + blockedByName) {
            val e = btn("Search")
            val s = screenOf(pkg, e)
            assertRefuse(check(call("tap", id = 1), s, e, plan = setOf(pkg)), "protected")
            assertRefuse(check(call("press_enter"), s, plan = setOf(pkg)), "protected")
            assertRefuse(check(call("scroll", direction = "down"), s, plan = setOf(pkg)), "protected")
            assertRefuse(check(call("back"), s, plan = setOf(pkg)), "protected")
            assertRefuse(check(call("home"), s, plan = setOf(pkg)), "protected")
        }
    }

    @Test fun g2_openingAProtectedAppIsRefused() {
        for (pkg in blockedPackages + blockedByName) assertRefuse(check(call("open_app", app = pkg), screenOf(LAUNCHER), plan = setOf(pkg)), "protected")
        assertRefuse(check(call("open_app", app = "HDFC Bank"), screenOf(LAUNCHER), plan = setOf("HDFC Bank")), "protected")
        assertRefuse(check(call("open_app", app = "Paytm"), screenOf(LAUNCHER), plan = setOf("Paytm")), "protected")
    }

    @Test fun g2_readingAndTalkingStayAllowedEverywhere() {
        val s = screenOf("com.phonepe.app", btn("Pay"))
        for (c in listOf(call("read_screen"), call("ask_user", text = "ok?"), call("find_app", text = "x"), call("wait", id = 2), call("done", text = "x"), call("fail", text = "x"), call("search_my_data", text = "x"), call("ask_expert", text = "q", app = "cooking"))) {
            assertAllow(check(c, s))
        }
    }

    @Test fun g2_ordinaryAppsAreNotProtected() {
        for (pkg in listOf(FOOD, "in.swiggy.android", "com.application.zomato", "com.android.chrome", "com.google.android.apps.maps", "com.whatsapp", "com.example.taxis", "com.android.vending", "com.google.android.dialer", "com.swarmos.app", LAUNCHER, "")) {
            assertFalse(pkg, Guard.isBlocked(pkg))
        }
        assertAllow(tapIn("in.swiggy.android", "Add"))
    }

    // ------------------------------------------------------------ links

    @Test fun links_onlyTheAllowedSchemesPassTheGuard() {
        val s = screenOf(FOOD, btn("x"))
        for (ok in listOf("https://example.com", "http://example.com", "geo:0,0?q=food", "tel:+911234567890", "mailto:a@b.c", "sms:1234", "market://details?id=x")) {
            assertAllow(check(call("open_link", text = ok), s))
        }
        for (bad in listOf("intent://x#Intent;end", "file:///sdcard/a", "content://contacts/1", "javascript:alert(1)", "ustaad://do?q=pay", "upi://pay?pa=x@y")) {
            assertRefuse(check(call("open_link", text = bad), s), "not allowed")
        }
    }

    // ------------------------------------------------------------ G3 secrets

    @Test fun g3_noTypingIntoAPasswordField() {
        val e = el(1, Role.EDIT, "Search", Flag.EDITABLE, Flag.PASSWORD)
        assertRefuse(check(call("type", id = 1, text = "x"), screenOf(FOOD, e), e), "password")
    }

    @Test fun g3_noTypingIntoAFieldWhoseLabelAsksForASecret() {
        val labels = listOf(
            "Password", "Confirm password", "Passcode", "PIN", "Enter PIN", "mPIN", "OTP", "Enter OTP", "One-time code", "one time password", "CVV", "CVC",
            "Card number", "UPI PIN", "Security code", "Verification code",
            "पासवर्ड", "ओटीपी", "पिन",
        )
        for (l in labels) {
            val e = el(1, Role.EDIT, l, Flag.EDITABLE)
            assertRefuse(check(call("type", id = 1, text = "1234"), screenOf(FOOD, e), e), "never types")
        }
    }

    @Test fun g3_ordinaryFieldsCanBeTypedInto() {
        for (l in listOf("Search", "Search for restaurants", "Shipping address", "Opinion", "Spinach", "Cardinal", "Add a note", "Delivery instructions")) {
            val e = el(1, Role.EDIT, l, Flag.EDITABLE)
            assertAllow(check(call("type", id = 1, text = "biryani"), screenOf(FOOD, e), e))
        }
    }

    @Test fun g3_aScreenThatAsksForACodeIsNotTypedOnAtAll() {
        val field = el(2, Role.EDIT, "Phone", Flag.EDITABLE)
        val asks = screenOf(FOOD, el(1, Role.TEXT, "Enter the OTP sent to your phone"), field)
        assertRefuse(check(call("type", id = 2, text = "1"), asks, field), "code")
        val forgot = screenOf(FOOD, el(1, Role.TEXT, "Forgot password?"), field)
        assertAllow(check(call("type", id = 2, text = "1"), forgot, field))
    }

    // ------------------------------------------------------------ G4 blind

    @Test fun g4_noBlindTapsTypingOrEnterOnAnUnreadableScreen() {
        val s = screenOf(FOOD, unreadable = true)
        val e = btn("Add")
        assertRefuse(check(call("tap", id = 1), s, e), "cannot be read")
        assertRefuse(check(call("type", id = 1, text = "x"), s, e), "cannot be read")
        assertRefuse(check(call("press_enter"), s), "cannot be read")
        assertRefuse(check(call("tap", id = 1), null, e), "cannot be read")
        assertRefuse(check(call("press_enter"), null), "cannot be read")
    }

    @Test fun g4_goingBackScrollingAndHomeAreStillAllowedOnAnUnreadableScreen() {
        val s = screenOf(FOOD, unreadable = true)
        assertAllow(check(call("back"), s))
        assertAllow(check(call("home"), s))
        assertAllow(check(call("scroll", direction = "down"), s))
    }

    @Test fun g4_aTapOrTypeNeedsAnElementThatExistsOnThisScreen() {
        assertRefuse(check(call("tap", id = 9), screenOf(FOOD, btn("Add")), null), "no such element")
        assertRefuse(check(call("type", id = 9, text = "x"), screenOf(FOOD, btn("Add")), null), "no such element")
        assertRefuse(check(call("scroll", direction = "down", id = 9), screenOf(FOOD, btn("Add")), null), "no such element")
        assertAllow(check(call("scroll", direction = "down"), screenOf(FOOD, btn("Add"))))
    }

    // ------------------------------------------------------------ G5 commit words

    @Test fun g5_tappingACommitButtonNeedsTheirYes() {
        val labels = listOf(
            "Proceed to pay", "Pay now", "Pay", "Payment", "Place order", "Place your order", "Order now", "Reorder", "Buy", "Buy now", "Purchase", "Checkout",
            "Book", "Book table", "Send", "Post", "Publish", "Share", "Delete", "Remove", "Uninstall", "Install", "Subscribe", "Transfer", "Donate", "Submit",
            "Sign in", "Sign up", "Log in", "Login", "Log out", "Agree", "Accept", "Allow", "Grant", "Confirm", "Confirm order", "Slide to pay", "Swipe to pay",
            "PAY NOW", "pay ₹250", "Pay ₹ 250.00",
        )
        for (l in labels) assertConfirm(tapIn(FOOD, l))
    }

    @Test fun g5_hindiCommitWordsToo() {
        val labels = listOf(
            "भुगतान करें", "पेमेंट", "ऑर्डर करें",
            "खरीदें", "भेजें", "बुक करें", "हटाएं", "इंस्टॉल",
        )
        for (l in labels) assertConfirm(tapIn(FOOD, l))
    }

    @Test fun g5_ordinaryShoppingButtonsAreNotCommits() {
        val labels = listOf(
            "Add", "ADD", "Add to cart", "View cart", "View cart (2)", "Orders", "Order history", "Order details", "Order status", "My orders", "Search", "Cancel", "Back",
            "Cart", "Veg only", "Biryani House", "Bookmarks", "Notebook", "Payments", "₹250", "Rs. 250.00", "250", "₹ 1,250",
        )
        for (l in labels) assertAllow(tapIn(FOOD, l))
    }

    @Test fun g5_aPriceAloneIsNotACommitButAPriceWithPayIs() {
        assertTrue(Guard.isPriceOnly("₹250"))
        assertTrue(Guard.isPriceOnly("Rs. 250.00"))
        assertFalse(Guard.isPriceOnly("Pay ₹250"))
        assertFalse(Guard.isPriceOnly(""))
        assertFalse(Guard.isCommitLabel("₹250"))
        assertTrue(Guard.isCommitLabel("Pay ₹250"))
    }

    @Test fun g5_aCommitConfirmInThePlannedAppAsksAboutTheActionOnly() {
        val d = tapIn(FOOD, "Place order") as Decision.Confirm
        assertEquals("", d.app)
        assertTrue(d.why, d.why.contains("Place order"))
    }

    @Test fun g5_enterNeedsTheirYesWhenAButtonOnTheScreenIsJustACommitWord() {
        val s = screenOf(FOOD, btn("Place order"))
        assertConfirm(check(call("press_enter"), s))
        assertConfirm(check(call("press_enter"), screenOf(FOOD, el(1, Role.BUTTON, "Pay!", Flag.CLICKABLE))))
        assertAllow(check(call("press_enter"), screenOf(FOOD, btn("Add to cart"), btn("Search"))))
        assertAllow(check(call("press_enter"), screenOf(FOOD, el(1, Role.TEXT, "Pay"))))
        assertTrue(Guard.isWholeCommit("Place order!"))
        assertFalse(Guard.isWholeCommit("Proceed to pay"))
    }

    // ------------------------------------------------------------ G6 app scope

    @Test fun g6_actingInAnAppOutsideThePlanNeedsTheirYes() {
        val d = tapIn("com.fake.maps", "Search", plan = setOf(FOOD))
        assertTrue(d is Decision.Confirm)
        assertEquals("continue in com.fake.maps?", (d as Decision.Confirm).why)
        assertEquals("com.fake.maps", d.app)
    }

    @Test fun g6_plannedApprovedAndLauncherAppsNeedNoQuestion() {
        assertAllow(tapIn(FOOD, "Search", plan = setOf(FOOD)))
        assertAllow(tapIn("com.fake.maps", "Search", plan = setOf(FOOD), approved = setOf("com.fake.maps")))
        assertAllow(tapIn(LAUNCHER, "Search", plan = setOf(FOOD)))
    }

    @Test fun g6_openingAnAppThatIsNotInThePlanOrApprovedNeedsTheirYes() {
        val s = screenOf(FOOD, btn("x"))
        val d = check(call("open_app", app = "com.fake.maps"), s)
        assertTrue(d is Decision.Confirm)
        assertEquals("com.fake.maps", (d as Decision.Confirm).app)
        assertAllow(check(call("open_app", app = "com.fake.maps"), s, approved = setOf("com.fake.maps")))
        assertAllow(check(call("open_app", app = FOOD), s))
        assertConfirm(check(call("open_app", app = LAUNCHER), s))
    }

    @Test fun g6_aCommitOutsideThePlanIsOneQuestionThatNamesBoth() {
        val d = tapIn("com.fake.maps", "Pay now", plan = setOf(FOOD)) as Decision.Confirm
        assertEquals("com.fake.maps", d.app)
        assertTrue(d.why, d.why.contains("Pay now") && d.why.contains("outside the planned apps"))
    }

    @Test fun g6_ustaadsOwnScreenIsNeverJudgedAsOutsideThePlan() {
        assertAllow(check(call("home"), screenOf("com.swarmos.app"), plan = emptySet()))
    }

    @Test fun g6_withNoPlanEveryActionOutsideTheLauncherNeedsTheirYes() {
        assertConfirm(tapIn(FOOD, "Search", plan = emptySet()))
        assertAllow(check(call("read_screen"), screenOf(FOOD), plan = emptySet()))
    }

    // ------------------------------------------------------------ order and the rest

    @Test fun refusalsComeBeforeQuestions() {
        // a commit word inside a protected app is a refusal, not a question
        assertRefuse(tapIn("com.phonepe.app", "Pay now"), "protected")
        // typing a password in an unplanned app is a refusal, not a question
        val e = el(1, Role.EDIT, "Password", Flag.EDITABLE, Flag.PASSWORD)
        assertRefuse(check(call("type", id = 1, text = "x"), screenOf("com.fake.maps", e), e))
    }

    @Test fun anUnknownToolIsRefused() {
        assertRefuse(check(ToolCall("format_disk", call("x").args), screenOf(FOOD)), "unknown tool")
    }
}
