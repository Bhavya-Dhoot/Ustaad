package com.swarmos.app.hands

import kotlinx.serialization.json.JsonPrimitive

/** What the guard says about one tool call. Allow: do it. Confirm: only if the person taps yes. Refuse: never, and the model is told. */
sealed interface Decision {
    data object Allow : Decision

    /**
     * [app]: the package to remember as approved for this task if the person says yes (set when the stop was about leaving the plan's apps).
     * [kind] is the main reason (the gravest one), [kinds] all of them: a yes covers exactly these kinds, a new kind asks again.
     */
    data class Confirm(val why: String, val app: String = "", val kind: AskKind = AskKind.OTHER, val kinds: Set<AskKind> = setOf(kind)) : Decision

    data class Refuse(val why: String) : Decision
}

/**
 * The part of Hands that does not depend on the model (safety rules S1 to S3). It sees the tool call, the screen the call was
 * decided on and the element it points at; it never sees the model's note. Elements are judged by [UiElement.guard], the full
 * normalised text, never by the short label the model reads. A word list cannot establish that a tap is harmless, so in
 * [Caution.CAREFUL] (the default) a tap is free only on a control whose own visible text is on the short list of harmless ones; in
 * [Caution.FEWER_QUESTIONS] a tap is asked about when it is recognised as committing. Rules run in this order; a refusal ends it,
 * the reasons to ask are collected and asked as one question: G1 own UI, G2 protected apps, links, G4 unreadable screen, G3 secrets,
 * scroll, enter, G5 commit and unknown controls, payment screen, G6 app scope.
 */
class Guard(private val ownPackages: Set<String> = OWN_PACKAGES) {

    /** True for a package of Ustaad itself: the window of a question, the task sheet. */
    fun isOwn(pkg: String): Boolean = ownPackages.any { it.equals(pkg, ignoreCase = true) }

    /** What the guard needs besides the call. */
    class Context(
        /** The installed apps: a package is protected when its package name or its label is. */
        val apps: List<AppInfo> = emptyList(),
        /** The guard text of the field the previous successful step typed into, null if the previous step was something else. */
        val lastTypedGuard: String? = null,
        /** For open_link: the package the driver says would open the link, empty if none, null if it was not asked. */
        val linkTarget: String? = null,
        val caution: Caution = Caution.CAREFUL,
        /** True when the complete tree of the screen, before the cap, has payment context; null: judge from the elements that are left. */
        val checkout: Boolean? = null,
    )

    fun check(
        call: ToolCall,
        screen: Screen?,
        element: UiElement?,
        planApps: Set<String>,
        approvedApps: Set<String>,
        launcherPackages: Set<String>,
        ctx: Context = Context(),
    ): Decision {
        val tool = call.tool
        val spec = HandsTools.spec(tool) ?: return Decision.Refuse("unknown tool '$tool'")
        if (spec.risk == Risk.READ) return Decision.Allow // reading and talking never touch the phone

        val pkg = screen?.pkg.orEmpty()
        val target = (call.args["app"] as? JsonPrimitive)?.content.orEmpty().trim()
        val text = (call.args["text"] as? JsonPrimitive)?.content.orEmpty()
        val asks = ArrayList<Pair<AskKind, String>>()
        var scopePkg = ""

        // G1: the agent never acts inside Ustaad. Leaving it (home, open another app, open a link) is how a run starts from the task sheet.
        if (tool == "open_app" && ownPackages.any { it.equals(target, ignoreCase = true) }) {
            return Decision.Refuse("Ustaad cannot open itself")
        }
        if (pkg in ownPackages && tool !in LEAVING) {
            return Decision.Refuse("the screen is Ustaad's own; the agent never acts there")
        }

        // G2: banking, UPI, wallets, password managers, authenticators, permission dialogs, installers, root, Settings: by package or by label
        if (screen != null && blockedApp(pkg, ctx)) return Decision.Refuse("$pkg is a protected app (money, passwords, permissions or settings); the agent does not act there")
        if (tool == "open_app" && blockedApp(target, ctx)) return Decision.Refuse("$target is a protected app (money, passwords, permissions or settings)")

        if (tool == "open_link") {
            if (!HandsTools.linkAllowed(text)) {
                return Decision.Refuse("this link is not allowed (${HandsTools.linkScheme(text) ?: "no scheme"}); only ${HandsTools.LINK_SCHEMES.joinToString(", ")} may be opened")
            }
            val lt = ctx.linkTarget
            if (lt.isNullOrEmpty()) return Decision.Refuse("no single app opens this link")
            if (ownPackages.any { it.equals(lt, ignoreCase = true) }) return Decision.Refuse("Ustaad cannot open itself")
            if (blockedApp(lt, ctx)) return Decision.Refuse("$lt is a protected app (money, passwords, permissions or settings); no link is sent to it")
            // Every link is asked about, whoever wrote it: the task text may come from another app, and the app that opens it may do anything on arrival.
            asks += AskKind.LINK to "open the link ${text.trim()} in ${ctx.apps.firstOrNull { it.pkg == lt }?.label ?: lt}"
            if (lt !in planApps && lt !in approvedApps) scopePkg = lt
        }

        // G4: no blind taps, and a tap or a type needs an element that exists on this screen
        val blind = screen == null || screen.unreadable
        if (blind && (tool in BLIND_REFUSED || tool == "scroll")) return Decision.Refuse("the screen cannot be read; no blind taps, typing or scrolling")
        val needsElement = tool == "tap" || tool == "type" || (tool == "scroll" && call.args["id"] != null)
        if (needsElement && element == null) return Decision.Refuse("there is no such element on this screen")

        val checkout = ctx.checkout ?: (screen != null && !screen.unreadable && isPaymentScreen(screen))

        // G3: secrets
        if (tool == "type" && element != null) {
            if (Flag.PASSWORD in element.flags) return Decision.Refuse("the agent never types into a password field")
            if (GuardWords.SECRET.matches(element.guard)) return Decision.Refuse("the agent never types a password, PIN, one-time code or card number")
            if (screen != null && screen.elements.any { GuardWords.SCREEN_ASKS_CODE.matches(it.guard) }) {
                return Decision.Refuse("this screen asks for a code or a card number; the agent does not type here")
            }
            // A card number typed into any field is refused by its digits, across any separator. What cannot be recognised: a password or PIN
            // field that is neither flagged as a password nor named like one (the label says "Search"). The guard cannot see purpose, only words.
            if (GuardText.hasCardNumber(text)) return Decision.Refuse("the text holds a card number; the agent never types one")
            if (element.guard.isEmpty()) asks += AskKind.UNKNOWN_CONTROL to "the field has no name, so nothing says what it is for"
        }

        // scroll: it can commit too ("slide to pay"), and a swipe with no checked target is not made at all
        if (tool == "scroll") {
            if (element != null) {
                if (Flag.SCROLLABLE !in element.flags) return Decision.Refuse("that element does not scroll")
                if (movesOrCommits(element.guard)) asks += AskKind.SCROLL to "\"${shown(element)}\" may commit when it is moved"
            } else if (screen == null || screen.elements.none { Flag.SCROLLABLE in it.flags && !movesOrCommits(it.guard) }) {
                return Decision.Refuse("nothing on this screen can be scrolled safely; there are no blind swipes")
            }
        }

        // enter sends what a focused field's keyboard action sends. Careful: always asked. Fewer questions: free only after a type into a search field.
        if (tool == "press_enter" && !checkout) {
            val search = ctx.lastTypedGuard?.let { GuardWords.SEARCH.matches(it) } == true
            if (ctx.caution == Caution.CAREFUL || !search) asks += AskKind.ENTER to "enter may send or submit what is typed"
        }

        // G5: commit words, prices, unknown controls
        if (tool == "tap" && element != null) {
            val g = element.guard
            val committing = isCommitText(g)
            val price = Flag.CLICKABLE in element.flags && GuardText.isPriceOnly(element.visible)
            when {
                committing -> asks += AskKind.COMMIT to "\"${shown(element)}\" commits something (pay, order, send, delete, install, sign in ...)"
                price -> asks += AskKind.COMMIT to "a price on a button may be the button that pays"
                checkout -> {} // everything on a payment screen is asked below
                ctx.caution == Caution.CAREFUL -> {
                    val edit = element.role == Role.EDIT || Flag.EDITABLE in element.flags
                    if (!edit && !GuardWords.isHarmless(element.visible)) {
                        asks += AskKind.UNKNOWN_CONTROL to "\"${shown(element)}\" is not a control known to be harmless"
                    }
                }
                g.isEmpty() -> asks += AskKind.UNKNOWN_CONTROL to "an unlabeled control: nothing says what it does"
            }
        }

        // a payment screen: every tap, type and enter is asked, with no exception (the back action stays free)
        if (checkout && tool in PAYMENT_ASKED) asks += AskKind.CHECKOUT_SCREEN to "this is a payment screen"

        // G6: app scope. Ustaad's own screen is only left, never judged.
        val known = planApps + approvedApps + launcherPackages
        if (screen != null && pkg.isNotEmpty() && pkg !in known && pkg !in ownPackages) {
            asks += AskKind.APP_SCOPE to "continue in $pkg?"
            scopePkg = pkg
        }
        if (tool == "open_app" && target.isNotEmpty() && target !in planApps && target !in approvedApps) {
            asks += AskKind.APP_SCOPE to "open $target?"
            scopePkg = target
        }

        if (asks.isEmpty()) return Decision.Allow
        val kinds = asks.map { it.first }.toSet()
        val main = KIND_ORDER.first { it in kinds }
        return Decision.Confirm(asks.map { it.second }.distinct().joinToString("; "), scopePkg, main, kinds)
    }

    /**
     * What the model and the log may be shown of a screen: the screen of a protected app, or one that asks for a code or a card,
     * is shown as a screen with no elements. Package and activity stay, so the guard still knows where it is. [asksForCode] is the
     * answer from the complete tree, taken before the cap cut anything.
     */
    fun shield(s: Screen, apps: List<AppInfo>, asksForCode: Boolean = false): Screen {
        if (s.unreadable) return s
        val protectedScreen = asksForCode || blockedApp(s.pkg, Context(apps = apps)) || s.elements.any { GuardWords.SCREEN_ASKS_CODE.matches(it.guard) }
        return if (protectedScreen) Screen(s.pkg, s.activity, emptyList(), true, "protected-" + s.hash) else s
    }

    private fun blockedApp(pkgOrName: String, ctx: Context): Boolean {
        if (isBlocked(pkgOrName)) return true
        val label = ctx.apps.firstOrNull { it.pkg.equals(pkgOrName, ignoreCase = true) }?.label ?: return false
        return isBlocked(label)
    }

    private fun shown(e: UiElement) = e.label.ifEmpty { e.guard }

    /** Payment context in what a person can read on the elements that are left; the runner passes the answer from the complete tree when it has it. */
    private fun isPaymentScreen(s: Screen): Boolean = GuardWords.PAYMENT_CONTEXT.matches(GuardText.join(*s.elements.map { it.visible }.toTypedArray()))

    companion object {
        val OWN_PACKAGES = setOf("com.swarmos.app", "com.ustaad.app")

        /** Actions that take the agent out of the current app; allowed from Ustaad's own screen. */
        private val LEAVING = setOf("home", "open_app", "open_link")
        private val BLIND_REFUSED = setOf("tap", "type", "press_enter")
        private val PAYMENT_ASKED = setOf("tap", "type", "press_enter")

        /** The gravest reason first: it names the question. */
        private val KIND_ORDER = listOf(
            AskKind.COMMIT, AskKind.CHECKOUT_SCREEN, AskKind.LINK, AskKind.ENTER, AskKind.SCROLL, AskKind.UNKNOWN_CONTROL, AskKind.APP_SCOPE, AskKind.OTHER,
        )

        // ---------------------------------------------------------------- G2: protected apps (extend freely)

        /** Exact package names. */
        val BLOCKED_PACKAGES = setOf(
            // UPI, wallets, banking
            "com.phonepe.app", "com.google.android.apps.nbu.paisa.user", "net.one97.paytm", "in.org.npci.upiapp",
            "com.dreamplug.androidapp", "com.mobikwik_new", "com.freecharge.android", "com.samsung.android.spay",
            // Amazon Pay is a part of the Amazon shopping app, not a separate package: its pay buttons are caught by the commit words.
            // authenticators
            "com.google.android.apps.authenticator2", "com.azure.authenticator", "com.authy.authy", "com.twofasapp",
            "com.beemdevelopment.aegis", "org.fedorahosted.freeotp",
            // password managers
            "com.x8bit.bitwarden", "com.agilebits.onepassword", "com.lastpass.lpandroid", "com.dashlane",
            "keepass2android.keepass2android", "keepass2android.keepass2android_nonet",
            "com.kunzisoft.keepass.free", "com.kunzisoft.keepass.libre", "org.keepassdroid", "com.android.credentialmanager",
            // permission dialogs, installers, root, Settings
            "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.topjohnwu.magisk", "com.android.settings",
        )

        /** A package or an app name is blocked if one of its words (split at dots, spaces, dashes ...) contains one of these. */
        val BLOCKED_NAME_PARTS = listOf(
            "bank", "wallet", "yono", "kotak", "icici", "hdfc", "phonepe", "paytm", "bhim", "npci", "gpay", "paisa", "mobikwik", "freecharge",
            "authenticator", "authy", "keepass", "bitwarden", "onepassword", "lastpass", "dashlane",
            "permissioncontroller", "packageinstaller", "magisk", "credentialmanager",
        )

        /** Short words that would hit innocent names as substrings (axis in taxis): blocked only when a whole word. */
        val BLOCKED_NAME_WORDS = setOf("sbi", "axis", "upi", "cred", "settings")

        fun isBlocked(pkgOrName: String): Boolean {
            val s = GuardText.norm(pkgOrName, camel = false)
            if (s.isEmpty()) return false
            if (BLOCKED_PACKAGES.any { it.lowercase() == s }) return true
            val words = s.split(Regex("[^a-z0-9_]+")).filter { it.isNotEmpty() }
            return words.any { w -> w in BLOCKED_NAME_WORDS || BLOCKED_NAME_PARTS.any { w.contains(it) } }
        }

        // ---------------------------------------------------------------- matching

        fun isSecretLabel(text: String): Boolean = GuardWords.SECRET.matches(GuardText.norm(text))

        /** The words of every language, on normalised text; adjacent pieces count ("p" "ay", "paynow"), longer words that contain a short one do not. */
        fun isCommitText(guard: String): Boolean = GuardWords.COMMIT.matches(guard)

        /** For a label as people write it: normalises first. */
        fun isCommitLabel(label: String): Boolean = isCommitText(GuardText.norm(label))

        fun isPriceOnly(text: String): Boolean = GuardText.isPriceOnly(text)

        /** Something that moves under a finger or commits when touched: scrolling it is not a plain scroll. */
        fun movesOrCommits(guard: String): Boolean = isCommitText(guard) || GuardWords.SLIDER.matches(guard)
    }
}
