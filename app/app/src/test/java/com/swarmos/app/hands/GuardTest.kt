package com.swarmos.app.hands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardTest {
    private val own = setOf("com.swarmos.app", "com.ustaad.app")
    private val guard = Guard(own)

    /** The older tests were written for fewer questions; the careful ones pass caution = CAREFUL. */
    private fun gc(
        apps: List<AppInfo> = emptyList(),
        lastTypedName: String? = null,
        linkTarget: String? = null,
        caution: Caution = Caution.FEWER_QUESTIONS,
        checkout: Boolean? = null,
        task: String = "",
    ) = Guard.Context(apps, lastTypedName, linkTarget, caution, checkout, task)

    private fun check(
        c: ToolCall,
        s: Screen?,
        e: UiElement? = null,
        plan: Set<String> = setOf(FOOD),
        approved: Set<String> = emptySet(),
        ctx: Guard.Context = gc(),
    ): Decision = guard.check(c, s, e, plan, approved, setOf(LAUNCHER), ctx)

    private fun btn(label: String) = el(1, Role.BUTTON, label, Flag.CLICKABLE)
    private fun tapIn(pkg: String, label: String, plan: Set<String> = setOf(pkg), approved: Set<String> = emptySet(), ctx: Guard.Context = gc()): Decision {
        val e = btn(label)
        return check(call("tap", id = 1), screenOf(pkg, e), e, plan, approved, ctx)
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
        assertAllow(check(call("open_app", app = FOOD), s, ctx = gc(task = "add a biryani in com.fake.food")))
        assertConfirm(check(call("open_link", text = "https://example.com"), s, plan = setOf(BROWSER), ctx = gc(linkTarget = BROWSER)))
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
        assertRefuse(check(call("open_app", app = "PhonePe"), screenOf(LAUNCHER), plan = setOf("PhonePe")), "protected")
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

    @Test fun c9_aPackageIsBlockedWhenItsLabelIsEvenIfItsNameIsHarmless() {
        val apps = listOf(AppInfo("com.example.cash", "Bank"), AppInfo("com.example.fine", "Cashier"))
        val ctx = gc(apps = apps)
        assertRefuse(tapIn("com.example.cash", "Search", ctx = ctx), "protected")
        assertAllow(tapIn("com.example.fine", "Search", ctx = ctx))
        assertAllow(tapIn("com.example.cash", "Search")) // without the installed apps only the package name is known
        assertRefuse(check(call("open_app", app = "com.example.cash"), screenOf(LAUNCHER), plan = setOf("com.example.cash"), ctx = ctx), "protected")
        assertAllow(check(call("open_app", app = "com.example.fine"), screenOf(LAUNCHER), plan = setOf("com.example.fine"), approved = setOf("com.example.fine"), ctx = ctx))
        val link = gc(apps = apps, linkTarget = "com.example.cash")
        assertRefuse(check(call("open_link", text = "https://x.example"), screenOf(FOOD, btn("x")), ctx = link, plan = setOf("com.example.cash")), "protected")
    }

    @Test fun c9_aLabelWrittenInCamelCaseIsStillBlocked() {
        val ctx = gc(apps = listOf(AppInfo("com.a.b", "PhonePe"), AppInfo("com.c.d", "HDFCBank Mobile")))
        assertRefuse(tapIn("com.a.b", "x", ctx = ctx), "protected")
        assertRefuse(tapIn("com.c.d", "x", ctx = ctx), "protected")
    }

    // ------------------------------------------------------------ shield: protected screens are not read (C7)

    @Test fun c7_aScreenOfAProtectedAppHasNoElementsForTheModel() {
        val s = screenOf("com.phonepe.app", el(1, Role.TEXT, "Balance 12,345"), btn("Send money"))
        val shown = guard.shield(s, emptyList())
        assertTrue(shown.unreadable)
        assertTrue(shown.elements.isEmpty())
        assertEquals("com.phonepe.app", shown.pkg)
        val line = ScreenReader.render(shown)
        assertEquals(1, line.lines().size)
        assertTrue(line, line.contains("Ustaad does not read it"))
        assertFalse(line.contains("Balance"))
    }

    @Test fun c7_aScreenOfAnAppBlockedByItsLabelIsAlsoProtected() {
        val s = screenOf("com.example.cash", el(1, Role.TEXT, "Balance 12,345"))
        assertTrue(guard.shield(s, listOf(AppInfo("com.example.cash", "Bank"))).unreadable)
        assertFalse(guard.shield(s, emptyList()).unreadable)
    }

    @Test fun c7_aScreenThatAsksForACodeOrACardHasNoElementsEither() {
        for (ask in listOf("Enter OTP", "Enter the one-time code", "Card number", "CVV", "Security code", "Verification code", "UPI PIN")) {
            val s = screenOf(FOOD, el(1, Role.TEXT, ask), el(2, Role.EDIT, "Box", Flag.EDITABLE))
            val shown = guard.shield(s, emptyList())
            assertTrue(ask, shown.unreadable && shown.elements.isEmpty())
        }
        val ordinary = screenOf(FOOD, el(1, Role.TEXT, "Forgot password?"), btn("Add"))
        assertEquals(ordinary, guard.shield(ordinary, emptyList()))
    }

    @Test fun c7_aProtectedScreenCannotBeActedOnBlindly() {
        val shown = guard.shield(screenOf(FOOD, el(1, Role.TEXT, "Enter OTP")), emptyList())
        assertRefuse(check(call("tap", id = 1), shown, null), "cannot be read")
        assertRefuse(check(call("scroll", direction = "down"), shown), "cannot be read")
        assertAllow(check(call("back"), shown))
    }

    // ------------------------------------------------------------ links (C6)

    private fun link(uri: String, target: String? = BROWSER, plan: Set<String> = setOf(FOOD, BROWSER), approved: Set<String> = emptySet(), apps: List<AppInfo> = emptyList()): Decision =
        check(call("open_link", text = uri), screenOf(FOOD, btn("x")), plan = plan, approved = approved, ctx = gc(apps = apps, linkTarget = target))

    @Test fun links_onlyTheAllowedSchemesPassTheGuardAndEachOfThemIsAskedAbout() {
        for (ok in listOf("https://example.com", "http://example.com", "geo:0,0?q=food", "tel:+911234567890", "mailto:a@b.c", "sms:1234", "market://details?id=x")) {
            val d = link(ok)
            assertTrue("$ok: $d", d is Decision.Confirm && d.kind == AskKind.LINK)
        }
        for (bad in listOf("intent://x#Intent;end", "file:///sdcard/a", "content://contacts/1", "javascript:alert(1)", "ustaad://do?q=pay", "upi://pay?pa=x@y")) {
            assertRefuse(link(bad), "not allowed")
        }
    }

    @Test fun c6_aLinkNoSingleAppOpensIsRefused() {
        assertRefuse(link("https://example.com", target = ""), "no single app")
        assertRefuse(link("https://example.com", target = null), "no single app")
    }

    @Test fun c6_aLinkWhoseHandlerIsProtectedOrUstaadIsRefused() {
        assertRefuse(link("https://example.com", target = "com.phonepe.app", plan = setOf("com.phonepe.app")), "protected")
        assertRefuse(link("https://example.com", target = "com.android.settings", plan = setOf("com.android.settings")), "protected")
        assertRefuse(link("https://example.com", target = "com.swarmos.app", plan = setOf("com.swarmos.app")), "itself")
    }

    @Test fun r10_everyLinkIsAskedAboutWithTheFullLinkAndTheAppThatOpensIt() {
        val uri = "https://attacker.example/collect?code=123456&x=" + "a".repeat(200)
        val d = link(uri, apps = listOf(AppInfo(BROWSER, "FakeBrowser"))) as Decision.Confirm
        assertEquals(AskKind.LINK, d.kind)
        assertTrue(d.why.contains(uri))
        assertTrue(d.why, d.why.contains("FakeBrowser"))
    }

    @Test fun r10_aLinkIsAskedAboutEvenWhenTheAppIsInThePlanOrApproved() {
        assertEquals(AskKind.LINK, (link("https://example.com", plan = setOf(FOOD, BROWSER)) as Decision.Confirm).kind)
        assertEquals(AskKind.LINK, (link("https://example.com", plan = setOf(FOOD), approved = setOf(BROWSER)) as Decision.Confirm).kind)
    }

    @Test fun c6_aLinkOpenedByAnAppOutsideThePlanRemembersThatAppForTheYes() {
        val d = link("https://example.com", plan = setOf(FOOD)) as Decision.Confirm
        assertEquals(BROWSER, d.app)
        assertEquals(setOf(AskKind.LINK), d.kinds)
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

    @Test fun g3_aFieldIsJudgedByItsGuardTextNotByTheLabelTheModelSees() {
        val e = el(1, Role.EDIT, "Search", Flag.EDITABLE, guard = "search enter your otp")
        assertRefuse(check(call("type", id = 1, text = "1234"), screenOf(FOOD, e), e), "never types")
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

    @Test fun c8_aCardNumberIsNeverTypedWhateverTheFieldIsCalled() {
        val e = el(1, Role.EDIT, "Search", Flag.EDITABLE)
        val s = screenOf(FOOD, e)
        for (n in listOf("4111111111111111", "4111 1111 1111 1111", "4111-1111-1111-1111", "my card 5500005555555559 ok", "378282246310005", "6011111111111117")) {
            assertRefuse(check(call("type", id = 1, text = n), s, e), "card number")
        }
        for (n in listOf("4111 1111 1111 1112", "123456789012", "biryani", "9876543210", "order 12345", "1234567890123 0")) {
            assertAllow(check(call("type", id = 1, text = n), s, e))
        }
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

    @Test fun g4_goingBackAndHomeAreStillAllowedOnAnUnreadableScreen() {
        val s = screenOf(FOOD, unreadable = true)
        assertAllow(check(call("back"), s))
        assertAllow(check(call("home"), s))
    }

    @Test fun g4_aTapOrTypeNeedsAnElementThatExistsOnThisScreen() {
        assertRefuse(check(call("tap", id = 9), screenOf(FOOD, btn("Add")), null), "no such element")
        assertRefuse(check(call("type", id = 9, text = "x"), screenOf(FOOD, btn("Add")), null), "no such element")
        assertRefuse(check(call("scroll", direction = "down", id = 9), screenOf(FOOD, btn("Add")), null), "no such element")
    }

    // ------------------------------------------------------------ G5 commit words (C2 a, b, e)

    @Test fun g5_tappingACommitButtonNeedsTheirYes() {
        val labels = listOf(
            "Proceed to pay", "Pay now", "Pay", "Payment", "Place order", "Place your order", "Order now", "Reorder", "Buy", "Buy now", "Purchase", "Checkout",
            "Book", "Book table", "Send", "Post", "Publish", "Share", "Delete", "Remove", "Uninstall", "Install", "Subscribe", "Transfer", "Donate", "Submit",
            "Sign in", "Sign up", "Log in", "Login", "Log out", "Agree", "Accept", "Allow", "Grant", "Confirm", "Confirm order", "Slide to pay", "Swipe to pay",
            "Slide to unlock", "Drag to confirm", "PAY NOW", "pay ₹250", "Pay ₹ 250.00",
        )
        for (l in labels) assertConfirm(tapIn(FOOD, l))
    }

    @Test fun g5_hindiCommitWordsToo() {
        for (l in listOf("भुगतान करें", "पेमेंट", "ऑर्डर करें", "खरीदें", "भेजें", "बुक करें", "हटाएं", "इंस्टॉल")) assertConfirm(tapIn(FOOD, l))
    }

    @Test fun c2e_theCommitWordsOfOtherLanguages() {
        val samples = mapOf(
            "bengali" to listOf("পেমেন্ট করুন", "কিনুন", "অর্ডার করুন", "পাঠান", "মুছুন", "বুক করুন"),
            "tamil" to listOf("பணம் செலுத்து", "வாங்கு", "ஆர்டர் செய்", "அனுப்பு", "நீக்கு", "நிறுவு"),
            "telugu" to listOf("చెల్లించండి", "కొనండి", "ఆర్డర్ చేయండి", "పంపండి", "తొలగించండి", "బుక్ చేయండి"),
            "marathi" to listOf("पैसे भरा", "खरेदी करा", "ऑर्डर करा", "पाठवा", "हटवा", "बुक करा"),
            "gujarati" to listOf("ચૂકવો", "ખરીદો", "ઓર્ડર કરો", "મોકલો", "કાઢી નાખો", "બુક કરો"),
            "kannada" to listOf("ಪಾವತಿಸಿ", "ಖರೀದಿಸಿ", "ಆರ್ಡರ್ ಮಾಡಿ", "ಕಳುಹಿಸಿ", "ಅಳಿಸಿ", "ಬುಕ್ ಮಾಡಿ"),
            "malayalam" to listOf("പണമടയ്ക്കുക", "വാങ്ങുക", "ഓർഡർ ചെയ്യുക", "അയയ്ക്കുക", "ഇല്ലാതാക്കുക", "ബുക്ക് ചെയ്യുക"),
            "punjabi" to listOf("ਭੁਗਤਾਨ ਕਰੋ", "ਖਰੀਦੋ", "ਆਰਡਰ ਕਰੋ", "ਭੇਜੋ", "ਮਿਟਾਓ", "ਬੁੱਕ ਕਰੋ"),
            "urdu" to listOf("ادائیگی کریں", "خریدیں", "آرڈر کریں", "بھیجیں", "حذف کریں", "بک کریں"),
            "spanish" to listOf("Pagar ahora", "Comprar", "Realizar pedido", "Enviar", "Eliminar", "Reservar", "Instalar"),
            "portuguese" to listOf("Pagar agora", "Comprar", "Fazer pedido", "Enviar", "Excluir", "Reservar", "Instalar"),
            "french" to listOf("Payer", "Acheter", "Commander", "Passer la commande", "Envoyer", "Supprimer", "Réserver", "Installer"),
            "german" to listOf("Jetzt bezahlen", "Kaufen", "Bestellen", "Senden", "Löschen", "Buchen", "Installieren"),
            "indonesian" to listOf("Bayar sekarang", "Beli", "Pesan sekarang", "Kirim", "Hapus", "Instal"),
        )
        for ((lang, labels) in samples) for (l in labels) assertConfirm(tapIn(FOOD, l).also { assertTrue("$lang: $l -> $it", it is Decision.Confirm) })
    }

    @Test fun c2e_everyLanguageHasItsOwnListAndNoListIsEmpty() {
        val lists = listOf(
            GuardWords.COMMIT_EN, GuardWords.COMMIT_HI, GuardWords.COMMIT_BN, GuardWords.COMMIT_TA, GuardWords.COMMIT_TE, GuardWords.COMMIT_MR, GuardWords.COMMIT_GU,
            GuardWords.COMMIT_KN, GuardWords.COMMIT_ML, GuardWords.COMMIT_PA, GuardWords.COMMIT_UR, GuardWords.COMMIT_ES, GuardWords.COMMIT_PT, GuardWords.COMMIT_FR,
            GuardWords.COMMIT_DE, GuardWords.COMMIT_ID,
        )
        assertEquals(16, lists.size)
        assertTrue(lists.all { it.isNotEmpty() })
        for (w in lists.flatten()) assertTrue("'$w' must match itself", Guard.isCommitLabel(w))
    }

    @Test fun g5_ordinaryShoppingButtonsAreNotCommits() {
        val labels = listOf(
            "Add", "ADD", "Add to cart", "View cart", "View cart (2)", "Orders", "Order history", "Order details", "Order status", "My orders", "Search", "Cancel", "Back",
            "Cart", "Veg only", "Biryani House", "Bookmarks", "Notebook", "Payments", "Display", "Display settings", "Repay later", "Spaghetti", "Postcard", "Shareholder",
        )
        for (l in labels) assertAllow(tapIn(FOOD, l))
    }

    @Test fun c2b_aCommitWordBrokenUpOrDisguisedIsStillACommit() {
        val labels = listOf("P ay", "p ay", "P a y", "P.a.y", "pa-y", "place-order", "Place order", "PayNow", "paynow", "proceedtopay", "PlaceOrder", "Re order", "P​ay", "P­ay", "‮Pay", "Ｐａｙ", "pаy", "ᴘᴀʏ")
        for (l in labels) assertConfirm(tapIn(FOOD, GuardText.norm(l)).also { assertTrue("'$l' -> $it", it is Decision.Confirm) })
    }

    @Test fun c2b_aLongerWordThatOnlyContainsAShortCommitWordIsNotACommit() {
        for (l in listOf("display", "repay later", "spay", "payee list", "company", "Bookmark", "outbox", "resend later is off", "postal code")) {
            assertFalse(l, Guard.isCommitLabel(l))
        }
    }

    @Test fun g5_aCommitConfirmInThePlannedAppAsksAboutTheActionOnly() {
        val d = tapIn(FOOD, "Place order") as Decision.Confirm
        assertEquals("", d.app)
        assertTrue(d.why, d.why.contains("Place order"))
    }

    @Test fun g5_aCommitIsJudgedByTheGuardTextNotByTheShortLabel() {
        val e = el(1, Role.BUTTON, "Continue", Flag.CLICKABLE, guard = "continue place order for 250")
        assertConfirm(check(call("tap", id = 1), screenOf(FOOD, e), e))
        val far = el(2, Role.BUTTON, "x".repeat(80), Flag.CLICKABLE, guard = ("x ".repeat(60)) + "pay")
        assertConfirm(check(call("tap", id = 2), screenOf(FOOD, far), far))
    }

    @Test fun c2c_aClickableWhoseWholeTextIsAPriceNeedsTheirYes() {
        for (l in listOf("₹250", "Rs. 250.00", "250", "₹ 1,250")) assertConfirm(tapIn(FOOD, l))
        // text that is not clickable is only text
        val t = el(1, Role.TEXT, "₹250")
        assertAllow(check(call("tap", id = 1), screenOf(FOOD, t), t))
        assertTrue(Guard.isPriceOnly("₹250"))
        assertFalse(Guard.isPriceOnly("₹250 off"))
        assertFalse(Guard.isPriceOnly(""))
    }

    @Test fun c2d_anElementWithNoTextAtAllNeedsTheirYes() {
        val e = el(1, Role.BUTTON, "", Flag.CLICKABLE)
        assertConfirm(check(call("tap", id = 1), screenOf(FOOD, e), e))
        val f = el(2, Role.EDIT, "", Flag.EDITABLE)
        assertConfirm(check(call("type", id = 2, text = "x"), screenOf(FOOD, f), f))
    }

    // ------------------------------------------------------------ payment screen (C2 f)

    private val contexts = listOf(
        "To pay ₹250", "Amount payable", "Total payable", "Grand total 250", "Bill details", "Order summary", "Payment method", "Pay using", "UPI", "Card ending 4242",
        "Cash on delivery", "Place order", "Checkout", "भुगतान करने के लिए", "देय राशि", "कुल राशि", "बिल विवरण", "ऑर्डर सारांश", "भुगतान विधि", "कैश ऑन डिलीवरी", "चेकआउट",
    )

    private fun paymentScreen(context: String): Screen = screenOf(
        FOOD,
        el(1, Role.TEXT, context), el(2, Role.BUTTON, "Add", Flag.CLICKABLE), el(3, Role.EDIT, "Coupon", Flag.EDITABLE), el(4, Role.BUTTON, "Back", Flag.CLICKABLE),
    )

    @Test fun c2f_onAPaymentScreenEveryTapTypeAndEnterNeedsTheirYes() {
        for (c in contexts) {
            val s = paymentScreen(c)
            assertConfirm(check(call("tap", id = 2), s, s.elements[1]).also { assertTrue("$c: $it", it is Decision.Confirm) })
            assertConfirm(check(call("type", id = 3, text = "SAVE10"), s, s.elements[2]))
            assertConfirm(check(call("press_enter"), s, ctx = gc(lastTypedName = "Search")))
        }
    }

    @Test fun r2_thereIsNoWayOutExceptionOnAPaymentScreenButTheBackActionStaysFree() {
        for (mode in Caution.entries) {
            for (out in listOf("Back", "Close", "Cancel", "Navigate up", "Not now", "वापस", "बंद करें")) {
                val e = el(2, Role.BUTTON, out, Flag.CLICKABLE)
                val s = screenOf(FOOD, el(1, Role.TEXT, "Bill details"), e)
                val d = check(call("tap", id = 2), s, e, ctx = gc(caution = mode)) as Decision.Confirm
                assertEquals("$mode $out", AskKind.CHECKOUT_SCREEN, d.kind)
            }
        }
        assertAllow(check(call("back"), paymentScreen("To pay 250")))
    }

    @Test fun c2f_aScreenWithoutPaymentContextIsNotAskedAbout() {
        val s = screenOf(FOOD, el(1, Role.TEXT, "Chicken Biryani"), el(2, Role.BUTTON, "Add", Flag.CLICKABLE))
        assertAllow(check(call("tap", id = 2), s, s.elements[1]))
        assertFalse(GuardWords.PAYMENT_CONTEXT.matches("display settings and the weather"))
    }

    // ------------------------------------------------------------ enter (C4)

    @Test fun t7_enterIsAlwaysAskedAboutInBothModesAndTheQuestionNamesTheFieldTypedIntoLast() {
        val s = screenOf(FOOD, btn("Add to cart"))
        for (mode in Caution.entries) {
            for (name in listOf(null, "Search for restaurants", "Write a reply", "खोजें")) {
                val d = check(call("press_enter"), s, ctx = gc(caution = mode, lastTypedName = name)) as Decision.Confirm
                assertEquals("$mode $name", AskKind.ENTER, d.kind)
                if (name != null) assertTrue(d.why, d.why.contains("\"$name\""))
            }
        }
    }

    @Test fun c4_enterOnAPaymentScreenAlwaysNeedsTheirYes() {
        val s = paymentScreen("To pay 250")
        assertConfirm(check(call("press_enter"), s, ctx = gc(lastTypedName = "Search")))
    }

    // ------------------------------------------------------------ scroll (C5)

    private fun listEl(id: Int, label: String, vararg flags: Flag) = el(id, Role.LIST, label, Flag.SCROLLABLE, *flags)

    @Test fun c5_scrollingAnUnreadableScreenIsRefused() {
        assertRefuse(check(call("scroll", direction = "down"), screenOf(FOOD, unreadable = true)), "cannot be read")
        assertRefuse(check(call("scroll", direction = "down"), null), "cannot be read")
    }

    @Test fun c5_anElementThatDoesNotScrollIsNotScrolled() {
        val b = btn("Add")
        assertRefuse(check(call("scroll", direction = "down", id = 1), screenOf(FOOD, b), b), "does not scroll")
    }

    @Test fun c5_aScrollableThatIsAPaymentControlOrASliderNeedsTheirYes() {
        for (g in listOf("slide to pay", "swipe to confirm order", "drag the handle", "pay now", "place order")) {
            val l = listEl(1, "x").let { UiElement(it.id, it.role, it.label, "", it.flags, "h", 1, 1, g) }
            val d = check(call("scroll", direction = "down", id = 1), screenOf(FOOD, l), l)
            assertTrue("$g: $d", d is Decision.Confirm && d.kind == AskKind.SCROLL)
        }
        val plain = listEl(1, "Menu")
        assertAllow(check(call("scroll", direction = "down", id = 1), screenOf(FOOD, plain), plain))
    }

    @Test fun r8_withoutAnIdAndWithoutACheckedListNothingIsScrolledInAnyMode() {
        for (mode in Caution.entries) {
            val c = gc(caution = mode)
            assertRefuse(check(call("scroll", direction = "down"), screenOf(FOOD, btn("Add")), ctx = c), "no blind swipes")
            assertRefuse(check(call("scroll", direction = "down"), paymentScreen("To pay 250"), ctx = c), "no blind swipes")
            val slider = UiElement(1, Role.LIST, "", "", setOf(Flag.SCROLLABLE), "h", 1, 1, "slide to pay")
            assertRefuse(check(call("scroll", direction = "down"), screenOf(FOOD, slider), ctx = c), "no blind swipes")
            val delete = UiElement(1, Role.LIST, "Delete", "", setOf(Flag.SCROLLABLE), "h", 1, 1, "delete")
            assertRefuse(check(call("scroll", direction = "down"), screenOf(FOOD, delete), ctx = c), "no blind swipes")
        }
    }

    @Test fun r8_aPlainListIsScrolledEvenNextToASlider() {
        val slider = UiElement(1, Role.LIST, "", "", setOf(Flag.SCROLLABLE), "h", 1, 1, "slide to pay")
        for (mode in Caution.entries) {
            val c = gc(caution = mode)
            assertAllow(check(call("scroll", direction = "down"), screenOf(FOOD, listEl(1, "Menu")), ctx = c))
            assertAllow(check(call("scroll", direction = "down"), screenOf(FOOD, listEl(2, "Menu"), UiElement(1, Role.LIST, "", "", setOf(Flag.SCROLLABLE), "h", 1, 1, "slide to pay")), ctx = c))
        }
    }

    @Test fun t8_everyScrollOnAPaymentScreenIsAskedAbout() {
        val pay = screenOf(FOOD, el(1, Role.TEXT, "To pay 250"), listEl(2, "Items"))
        for (mode in Caution.entries) {
            val c = gc(caution = mode)
            assertEquals(AskKind.CHECKOUT_SCREEN, kindOf(check(call("scroll", direction = "down"), pay, ctx = c)))
            assertEquals(AskKind.CHECKOUT_SCREEN, kindOf(check(call("scroll", direction = "down", id = 2), pay, pay.elements[1], ctx = c)))
        }
    }

    @Test fun t8_aScrollTargetIsFreeOnlyWhenItIsAList() {
        val onSwitch = el(1, Role.TOGGLE, "Menu", Flag.SCROLLABLE, Flag.CLICKABLE)
        val d = check(call("scroll", direction = "down", id = 1), screenOf(FOOD, onSwitch), onSwitch) as Decision.Confirm
        assertEquals(AskKind.SCROLL, d.kind)
        assertAllow(check(call("scroll", direction = "down", id = 2), screenOf(FOOD, listEl(2, "Menu")), listEl(2, "Menu")))
        // nothing on the screen but buttons that scroll: no target is picked for a scroll without an id
        assertRefuse(check(call("scroll", direction = "down"), screenOf(FOOD, el(1, Role.BUTTON, "Menu", Flag.SCROLLABLE, Flag.CLICKABLE))), "no blind swipes")
    }

    @Test fun t8_eraseClearAllWipeResetAndRemoveAllAreCommitWords() {
        for (l in listOf("Erase", "Clear all", "Wipe", "Reset", "Remove all", "Erase history")) {
            assertTrue(l, Guard.isCommitLabel(l))
            val e = listEl(1, "x").let { UiElement(it.id, it.role, l, "", it.flags, "h", 1, 1, GuardText.norm(l)) }
            val d = check(call("scroll", direction = "down", id = 1), screenOf(FOOD, e), e)
            assertTrue("$l: $d", d is Decision.Confirm && d.kind == AskKind.SCROLL)
            assertEquals(AskKind.COMMIT, kindOf(tapIn(FOOD, l)))
        }
        assertFalse(Guard.isCommitLabel("Preset menu"))
        assertFalse(Guard.isCommitLabel("Resetting is hard"))
    }

    @Test fun c5_slideToSwipeToAndDragToAreCommitWords() {
        for (l in listOf("Slide to unlock", "Swipe to start", "Drag to send")) assertTrue(l, Guard.isCommitLabel(l))
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

    @Test fun g6_openingAnAppThatIsNotApprovedNeedsTheirYes() {
        val s = screenOf(FOOD, btn("x"))
        val d = check(call("open_app", app = "com.fake.maps"), s)
        assertTrue(d is Decision.Confirm)
        assertEquals("com.fake.maps", (d as Decision.Confirm).app)
        assertAllow(check(call("open_app", app = "com.fake.maps"), s, approved = setOf("com.fake.maps")))
        assertConfirm(check(call("open_app", app = LAUNCHER), s))
    }

    private val installed = listOf(AppInfo(FOOD, "FakeFood"), AppInfo("com.fake.maps", "FakeMaps"), AppInfo("in.swiggy.android", "Swiggy: Food, Grocery Delivery"))

    @Test fun t1_anAppThePlannerNamedIsAskedAboutBeforeItsFirstLaunchEvenWhenTheOwnersTaskDoesNotNameIt() {
        val s = screenOf(LAUNCHER, btn("x"))
        for (mode in Caution.entries) {
            val d = check(call("open_app", app = FOOD), s, plan = setOf(FOOD), ctx = gc(apps = installed, caution = mode, task = "order a biryani")) as Decision.Confirm
            assertEquals("$mode", AskKind.APP_SCOPE, d.kind)
            assertEquals("open FakeFood?", d.why)
            assertEquals(FOOD, d.app)
        }
    }

    @Test fun t1_noQuestionWhenTheOwnersTaskNamesTheAppAsAWholeWord() {
        val s = screenOf(LAUNCHER, btn("x"))
        fun open(task: String) = check(call("open_app", app = FOOD), s, plan = setOf(FOOD), ctx = gc(apps = installed, task = task))
        assertAllow(open("order a biryani in FakeFood"))
        assertAllow(open("order a biryani in fakefood please"))
        assertAllow(open("Use com.fake.food to order"))
        assertConfirm(open("order a biryani in FakeFoods"))   // part of a longer word
        assertConfirm(open("order a biryani in Fake Food"))
        assertConfirm(open(""))
        val swiggy = { task: String -> check(call("open_app", app = "in.swiggy.android"), s, plan = setOf("in.swiggy.android"), ctx = gc(apps = installed, task = task)) }
        assertAllow(swiggy("get me a pizza on Swiggy"))   // the label before the colon
        assertConfirm(swiggy("get me a pizza"))
    }

    @Test fun t1_aYesCoversTheAppForTheRestOfTheRunSoItIsNotAskedTwice() {
        val s = screenOf(LAUNCHER, btn("x"))
        assertConfirm(check(call("open_app", app = FOOD), s, plan = setOf(FOOD), ctx = gc(apps = installed)))
        assertAllow(check(call("open_app", app = FOOD), s, plan = setOf(FOOD), approved = setOf(FOOD), ctx = gc(apps = installed)))
    }

    @Test fun g6_aCommitOutsideThePlanIsOneQuestionThatNamesBoth() {
        val d = tapIn("com.fake.maps", "Pay now", plan = setOf(FOOD)) as Decision.Confirm
        assertEquals("com.fake.maps", d.app)
        assertTrue(d.why, d.why.contains("Pay now") && d.why.contains("continue in com.fake.maps"))
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

    // ------------------------------------------------------------ careful mode (R1)

    private val careful = gc(caution = Caution.CAREFUL)

    private fun tapCareful(visible: String, guard: String = GuardText.norm(visible), extra: List<UiElement> = emptyList(), vararg flags: Flag): Decision {
        val e = el(1, Role.BUTTON, visible, Flag.CLICKABLE, *flags, guard = guard, visible = GuardText.norm(visible))
        return check(call("tap", id = 1), screenOf(FOOD, e, *extra.toTypedArray()), e, ctx = careful)
    }

    private fun kindOf(d: Decision): AskKind = (d as Decision.Confirm).kind

    @Test fun r1_inCarefulModeATapIsFreeOnlyOnAControlOnTheHarmlessList() {
        val harmless = listOf(
            "add", "ADD", "Add to cart", "Add item", "Add to bag", "plus", "+", "minus", "-", "Increase", "Decrease", "Search", "View cart", "View Cart (2)", "Cart", "Bag", "Basket",
            "See all", "View all", "Show more", "More", "Filter", "Sort", "Skip", "Not now", "Later", "Close", "Dismiss", "Back", "Navigate up", "Home", "Menu",
            "जोड़ें", "कार्ट में जोड़ें", "कार्ट देखें", "खोजें", "सभी देखें", "छोड़ें", "अभी नहीं", "वापस", "बंद करें",
        )
        for (l in harmless) assertAllow(tapCareful(l))
    }

    @Test fun r1_theLastButtonOfSomethingIsNotOnTheListAndIsAskedAboutAsAnUnknownControl() {
        for (l in listOf("Continue", "Next", "OK", "Done", "Yes", "Proceed", "Cancel", "Apply", "Save", "Orders", "Biryani House", "Veg only", "Explore", "Confirm location", "Erase", "payonline", "payermaintenant")) {
            val d = tapCareful(l)
            assertTrue("$l: $d", d is Decision.Confirm && (d.kind == AskKind.UNKNOWN_CONTROL || d.kind == AskKind.COMMIT))
        }
        for (l in listOf("Continue", "Next", "OK", "Done", "Yes", "Proceed", "Cancel", "Apply", "Save", "payonline", "payermaintenant")) {
            assertEquals(l, AskKind.UNKNOWN_CONTROL, kindOf(tapCareful(l)))
        }
    }

    @Test fun r1_aPriceOnAButtonIsAskedAboutWhateverItsResourceIdSaysInBothModes() {
        for (mode in Caution.entries) {
            val e = el(1, Role.BUTTON, "₹250", Flag.CLICKABLE, guard = GuardText.norm("₹250 btn primary"), visible = GuardText.norm("₹250"))
            val d = check(call("tap", id = 1), screenOf(FOOD, e), e, ctx = gc(caution = mode))
            assertEquals(mode.name, AskKind.COMMIT, kindOf(d))
        }
    }

    @Test fun r1_aTapOnAnEditFieldIsFreeInCarefulModeUnlessTheFieldSitsUnderAPayControl() {
        val edit = el(1, Role.EDIT, "Anything", Flag.EDITABLE)
        assertAllow(check(call("tap", id = 1), screenOf(FOOD, edit), edit, ctx = careful))
        val underPay = el(1, Role.EDIT, "Amount", Flag.EDITABLE, guard = "amount pay")
        assertEquals(AskKind.COMMIT, kindOf(check(call("tap", id = 1), screenOf(FOOD, underPay), underPay, ctx = careful)))
    }

    @Test fun r1_aHarmlessWordWithACommittingWordInTheFullGuardTextIsStillACommit() {
        val d = tapCareful("Add", guard = "add btn pay now")
        assertEquals(AskKind.COMMIT, kindOf(d))
    }

    @Test fun r12_theHarmlessCheckLooksAtTheVisibleTextOnlyNotAtResourceIdWords() {
        // visible "continue", but the id says add to cart: still an unknown control
        assertEquals(AskKind.UNKNOWN_CONTROL, kindOf(tapCareful("Continue", guard = "continue btn add to cart")))
        // nothing visible at all, the id says add: not established as harmless
        val e = UiElement(1, Role.BUTTON, "add", "", setOf(Flag.CLICKABLE), "h", 1, 1, guard = "add", visible = "")
        assertEquals(AskKind.UNKNOWN_CONTROL, kindOf(check(call("tap", id = 1), screenOf(FOOD, e), e, ctx = careful)))
    }

    @Test fun r1_onAPaymentScreenNothingIsHarmlessInCarefulMode() {
        val d = check(call("tap", id = 2), paymentScreen("To pay 250"), paymentScreen("To pay 250").elements[1], ctx = careful)
        assertEquals(AskKind.CHECKOUT_SCREEN, kindOf(d))
    }

    @Test fun r1_inFewerQuestionsModeTodaysRulesHold() {
        assertAllow(tapIn(FOOD, "Continue"))
        assertAllow(tapIn(FOOD, "Orders"))
        assertEquals(AskKind.COMMIT, kindOf(tapIn(FOOD, "Pay")))
        assertEquals(AskKind.UNKNOWN_CONTROL, kindOf(check(call("tap", id = 1), screenOf(FOOD, el(1, Role.BUTTON, "", Flag.CLICKABLE)), el(1, Role.BUTTON, "", Flag.CLICKABLE))))
    }

    // ------------------------------------------------------------ the kind of every question (R2 of the plan)

    @Test fun everyConfirmCarriesItsKind() {
        val screen = screenOf(FOOD, btn("Pay"))
        assertEquals(AskKind.COMMIT, kindOf(tapIn(FOOD, "Pay now")))
        assertEquals(AskKind.UNKNOWN_CONTROL, kindOf(tapCareful("Next")))
        assertEquals(AskKind.APP_SCOPE, kindOf(tapIn("com.fake.maps", "Search", plan = setOf(FOOD))))
        assertEquals(AskKind.APP_SCOPE, kindOf(check(call("open_app", app = "com.fake.maps"), screen)))
        assertEquals(AskKind.LINK, kindOf(link("https://example.com")))
        assertEquals(AskKind.ENTER, kindOf(check(call("press_enter"), screen)))
        val slider = UiElement(1, Role.LIST, "", "", setOf(Flag.SCROLLABLE), "h", 1, 1, "slide to pay")
        assertEquals(AskKind.SCROLL, kindOf(check(call("scroll", direction = "down", id = 1), screenOf(FOOD, slider), slider)))
        assertEquals(AskKind.CHECKOUT_SCREEN, kindOf(check(call("tap", id = 2), paymentScreen("To pay 250"), paymentScreen("To pay 250").elements[1])))
    }

    @Test fun aQuestionWithTwoReasonsIsNamedByTheGraverAndCarriesBoth() {
        val d = tapIn("com.fake.maps", "Pay now", plan = setOf(FOOD)) as Decision.Confirm
        assertEquals(AskKind.COMMIT, d.kind)
        assertEquals(setOf(AskKind.COMMIT, AskKind.APP_SCOPE), d.kinds)
    }

    // ------------------------------------------------------------ enter (R3)

    @Test fun r3_enterIsAlwaysAskedAboutEvenDirectlyAfterASearchType() {
        val s = screenOf(FOOD, btn("Add to cart"))
        assertEquals(AskKind.ENTER, kindOf(check(call("press_enter"), s, ctx = gc(caution = Caution.CAREFUL, lastTypedName = "Search for restaurants"))))
        assertEquals(AskKind.ENTER, kindOf(check(call("press_enter"), s, ctx = gc(caution = Caution.FEWER_QUESTIONS, lastTypedName = "Search for restaurants"))))
    }

    // ------------------------------------------------------------ harmless words on content (T2)

    private fun tapRole(role: Role, visible: String): Decision {
        val e = el(1, role, visible, Flag.CLICKABLE)
        return check(call("tap", id = 1), screenOf(FOOD, e), e, ctx = careful)
    }

    @Test fun t2_aListedPhraseIsFreeOnlyOnAButtonOrAToggle() {
        for (w in listOf("Close", "Add", "Back", "View cart")) {
            assertAllow(tapRole(Role.BUTTON, w))
            assertAllow(tapRole(Role.TOGGLE, w))
            for (content in listOf(Role.TEXT, Role.IMAGE, Role.LIST, Role.OTHER)) assertEquals("$content $w", AskKind.UNKNOWN_CONTROL, kindOf(tapRole(content, w)))
        }
    }

    @Test fun t2_onlyOneCountInBracketsFollowsTheListedPhrase() {
        for (w in listOf("View cart (2)", "Cart [12]", "Add (1)", "Cart ( 3 )")) assertAllow(tapRole(Role.BUTTON, w))
        for (w in listOf("Close 123 [456]", "Close 123", "Add 1", "Cart · 3", "Close (1) (2)", "Close (12345)", "Close (x)", "Cart 3 items")) {
            assertEquals(w, AskKind.UNKNOWN_CONTROL, kindOf(tapRole(Role.BUTTON, w)))
        }
    }

    // ------------------------------------------------------------ typing that commits (T3)

    @Test fun t3_typingIntoAFieldThatNamesACommittingActionIsAskedAboutInBothModes() {
        for (mode in Caution.entries) {
            for (name in listOf("Send message as typed", "Post a comment", "Share with friends", "Pay by typing the amount", "Delete after typing")) {
                val f = el(1, Role.EDIT, name, Flag.EDITABLE)
                val d = check(call("type", id = 1, text = "hello"), screenOf(FOOD, f), f, ctx = gc(caution = mode))
                assertTrue("$mode $name: $d", d is Decision.Confirm && d.kind == AskKind.COMMIT)
            }
            val search = el(1, Role.EDIT, "Search for restaurants", Flag.EDITABLE)
            assertAllow(check(call("type", id = 1, text = "biryani"), screenOf(FOOD, search), search, ctx = gc(caution = mode)))
        }
    }

    @Test fun t3_aFieldUnderACommittingParentIsAskedAboutToo() {
        val f = el(1, Role.EDIT, "Note", Flag.EDITABLE, guard = "note send")
        assertEquals(AskKind.COMMIT, kindOf(check(call("type", id = 1, text = "hi"), screenOf(FOOD, f), f)))
    }
}
