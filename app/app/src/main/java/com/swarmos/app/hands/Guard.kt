package com.swarmos.app.hands

import kotlinx.serialization.json.JsonPrimitive

/** What the guard says about one tool call. Allow: do it. Confirm: only if the person taps yes. Refuse: never, and the model is told. */
sealed interface Decision {
    data object Allow : Decision

    /** [app]: the package to remember as approved for this task if the person says yes (set when the stop was about leaving the plan's apps). */
    data class Confirm(val why: String, val app: String = "") : Decision

    data class Refuse(val why: String) : Decision
}

/**
 * The part of Hands that does not depend on the model (safety rules S1 to S3). It sees the tool call, the screen the call was
 * decided on and the element it points at; it never sees the model's note. Rules run in this order, the first that fires wins:
 * G1 own UI, G2 blocked apps, links, G3 secrets, G4 unreadable screen, G5 commit words, G6 app scope.
 */
class Guard(private val ownPackages: Set<String> = OWN_PACKAGES) {

    fun check(
        call: ToolCall,
        screen: Screen?,
        element: UiElement?,
        planApps: Set<String>,
        approvedApps: Set<String>,
        launcherPackages: Set<String>,
    ): Decision {
        val tool = call.tool
        val spec = HandsTools.spec(tool) ?: return Decision.Refuse("unknown tool '$tool'")
        if (spec.risk == Risk.READ) return Decision.Allow // reading and talking never touch the phone

        val pkg = screen?.pkg.orEmpty()
        val target = (call.args["app"] as? JsonPrimitive)?.content.orEmpty().trim()
        val text = (call.args["text"] as? JsonPrimitive)?.content.orEmpty()

        // G1: the agent never acts inside Ustaad. Leaving it (home, open another app, open a link) is how a run starts from the task sheet.
        if (tool == "open_app" && ownPackages.any { it.equals(target, ignoreCase = true) }) {
            return Decision.Refuse("Ustaad cannot open itself")
        }
        if (pkg in ownPackages && tool !in LEAVING) {
            return Decision.Refuse("the screen is Ustaad's own; the agent never acts there")
        }

        // G2: banking, UPI, wallets, password managers, authenticators, permission dialogs, installers, root, Settings
        if (screen != null && isBlocked(pkg)) return Decision.Refuse("$pkg is a protected app (money, passwords, permissions or settings); the agent does not act there")
        if (tool == "open_app" && isBlocked(target)) return Decision.Refuse("$target is a protected app (money, passwords, permissions or settings)")

        if (tool == "open_link" && !HandsTools.linkAllowed(text)) {
            return Decision.Refuse("this link is not allowed (${HandsTools.linkScheme(text) ?: "no scheme"}); only ${HandsTools.LINK_SCHEMES.joinToString(", ")} may be opened")
        }

        // G4 first for the facts it needs: no blind taps, and a tap or a type needs an element that exists on this screen
        val blind = screen == null || screen.unreadable
        if (blind && tool in BLIND_REFUSED) return Decision.Refuse("the screen cannot be read; no blind taps or typing")
        val needsElement = tool == "tap" || tool == "type" || (tool == "scroll" && call.args["id"] != null)
        if (needsElement && element == null) return Decision.Refuse("there is no such element on this screen")

        // G3: secrets
        if (tool == "type" && element != null) {
            if (Flag.PASSWORD in element.flags) return Decision.Refuse("the agent never types into a password field")
            if (SECRET_LABEL.containsMatchIn(element.label)) return Decision.Refuse("the agent never types a password, PIN, one-time code or card number")
            if (screen != null && screen.elements.any { SCREEN_ASKS_CODE.containsMatchIn(it.label) }) {
                return Decision.Refuse("this screen asks for a code or a card number; the agent does not type here")
            }
        }

        // G5: commit words
        var commit: String? = null
        if (tool == "tap" && element != null && isCommitLabel(element.label)) {
            commit = "\"${element.label}\" commits something (pay, order, send, delete, install, sign in ...)"
        }
        if (tool == "press_enter" && screen != null) {
            val b = screen.elements.firstOrNull { it.role == Role.BUTTON && isWholeCommit(it.label) }
            if (b != null) commit = "enter may press \"${b.label}\", which commits something"
        }

        // G6: app scope. Ustaad's own screen is only left, never judged.
        val known = planApps + approvedApps + launcherPackages
        var outside: String? = null
        var outsidePkg = ""
        if (screen != null && pkg.isNotEmpty() && pkg !in known && pkg !in ownPackages) {
            outside = "continue in $pkg?"
            outsidePkg = pkg
        }
        if (tool == "open_app" && target.isNotEmpty() && target !in planApps && target !in approvedApps) {
            outside = "open $target?"
            outsidePkg = target
        }

        return when {
            commit != null && outside != null -> Decision.Confirm("$commit; also outside the planned apps ($outsidePkg)", outsidePkg)
            commit != null -> Decision.Confirm(commit)
            outside != null -> Decision.Confirm(outside, outsidePkg)
            else -> Decision.Allow
        }
    }

    companion object {
        val OWN_PACKAGES = setOf("com.swarmos.app", "com.ustaad.app")

        /** Actions that take the agent out of the current app; allowed from Ustaad's own screen. */
        private val LEAVING = setOf("home", "open_app", "open_link")
        private val BLIND_REFUSED = setOf("tap", "type", "press_enter")

        // ---------------------------------------------------------------- G2: protected apps (extend freely)

        /** Exact package names. */
        val BLOCKED_PACKAGES = setOf(
            // UPI, wallets, banking
            "com.phonepe.app", "com.google.android.apps.nbu.paisa.user", "net.one97.paytm", "in.org.npci.upiapp",
            "com.dreamplug.androidapp", "com.mobikwik_new", "com.freecharge.android", "com.samsung.android.spay",
            // Amazon Pay is a part of the Amazon shopping app, not a separate package: its pay buttons are caught by G5.
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
            val s = pkgOrName.trim().lowercase()
            if (s.isEmpty()) return false
            if (BLOCKED_PACKAGES.any { it.lowercase() == s }) return true
            val words = s.split(Regex("[^a-z0-9_]+")).filter { it.isNotEmpty() }
            return words.any { w -> w in BLOCKED_NAME_WORDS || BLOCKED_NAME_PARTS.any { w.contains(it) } }
        }

        // ---------------------------------------------------------------- word lists

        /** Matches a whole word or phrase, not part of a longer word. A space inside a phrase matches a space or a dash. */
        private fun wordRegex(words: List<String>): Regex {
            val alt = words.joinToString("|") { Regex.escape(it).replace(" ", "\\E[\\s\\-]+\\Q") }
            return Regex("(?<![\\p{L}\\p{N}\\p{M}_])(?:$alt)(?![\\p{L}\\p{N}\\p{M}_])", RegexOption.IGNORE_CASE)
        }

        /** G3: fields the agent never types into (English; Hindi: password, passcode, OTP, PIN). */
        val SECRET_WORDS = listOf(
            "password", "passcode", "passphrase", "pin", "mpin", "otp", "one time", "cvv", "cvv2", "cvc", "card number",
            "upi pin", "security code", "verification code", "authentication code", "2fa code",
            "पासवर्ड", "पासकोड", "ओटीपी", "पिन", "वन टाइम",
        )
        private val SECRET_LABEL = wordRegex(SECRET_WORDS)

        /** G3: a screen that asks for one of these is not typed on at all (narrower than SECRET_WORDS: "Forgot password?" is everywhere). */
        private val SCREEN_ASKS_CODE = wordRegex(
            listOf("otp", "one time", "cvv", "cvc", "card number", "upi pin", "security code", "verification code", "ओटीपी", "वन टाइम"),
        )

        /** G5: a tap on an element whose label has one of these needs the person's yes. */
        val COMMIT_WORDS = listOf(
            "pay", "payment", "place order", "place your order", "order now", "reorder", "buy", "purchase", "checkout", "check out",
            "book", "send", "post", "publish", "share", "delete", "remove", "uninstall", "install", "subscribe", "transfer", "donate",
            "submit", "sign in", "signin", "sign up", "signup", "log in", "login", "log out", "logout", "agree", "accept", "allow", "grant",
            "confirm", "slide to pay", "swipe to pay",
            // Hindi: pay, order, buy, send, book, delete, install
            "भुगतान", "पे", "पेमेंट", "ऑर्डर करें", "ऑर्डर दें", "ऑर्डर प्लेस करें", "आर्डर करें", "खरीदें", "खरीदिए",
            "भेजें", "भेजिए", "बुक करें", "बुक", "हटाएं", "हटाएँ", "डिलीट", "मिटाएं", "इंस्टॉल", "इंस्टाल",
        )
        private val COMMIT_IN_LABEL = wordRegex(COMMIT_WORDS)
        private val COMMIT_WHOLE = Regex("^(?:" + COMMIT_WORDS.joinToString("|") { Regex.escape(it).replace(" ", "\\E[\\s\\-]+\\Q") } + ")$", RegexOption.IGNORE_CASE)

        private val PRICE_NOISE = Regex("[\\s₹$€£.,/+\\-]|rs\\.?|inr|usd|eur", RegexOption.IGNORE_CASE)

        /** "₹250", "Rs. 250.00": a price is a number, not a commit. */
        fun isPriceOnly(label: String): Boolean {
            val rest = PRICE_NOISE.replace(label, "")
            return rest.isNotEmpty() && rest.all { it.isDigit() }
        }

        fun isSecretLabel(label: String): Boolean = SECRET_LABEL.containsMatchIn(label)

        fun isCommitLabel(label: String): Boolean = !isPriceOnly(label) && COMMIT_IN_LABEL.containsMatchIn(label)

        /** A label that is nothing but a commit word or phrase ("Pay", "Place order!"). */
        fun isWholeCommit(label: String): Boolean = COMMIT_WHOLE.matches(label.trim().trim { !it.isLetterOrDigit() }.replace(Regex("\\s+"), " "))
    }
}
