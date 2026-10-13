package com.swarmos.app.hands

import com.swarmos.app.hands.GuardText.WordSet

/**
 * The word lists of the guard. Easy to read, easy to extend: add a word to a list, and the matching follows.
 * Entries are written the way people write them; the matching normalises them like the screen text (lower case, no invisible characters).
 */
object GuardWords {

    // ---------------------------------------------------------------- secrets (S2)

    /** A field with one of these in its guard text is never typed into, and its value is never read. Hindi: password, passcode, OTP, PIN, one time. */
    val SECRET = WordSet(
        listOf(
            "password", "passcode", "passphrase", "pin", "mpin", "otp", "one time", "cvv", "cvv2", "cvc", "card number",
            "upi pin", "security code", "verification code", "authentication code", "2fa code",
            "पासवर्ड", "पासकोड", "ओटीपी", "पिन", "वन टाइम",
        ),
    )

    /** A screen where one element says one of these asks for a code or a card: it is not read to the model and not typed on. Narrower than SECRET ("Forgot password?" is everywhere). */
    val SCREEN_ASKS_CODE = WordSet(
        listOf("otp", "one time", "cvv", "cvc", "card number", "upi pin", "security code", "verification code", "ओटीपी", "वन टाइम"),
    )

    /** An element with one of these in its text and a run of 4 to 8 digits in it has the digits masked. Hindi: OTP, code, verification, passcode. */
    val CODE = WordSet(listOf("otp", "code", "verification", "passcode", "one time", "ओटीपी", "कोड", "सत्यापन", "पासकोड"))

    // ---------------------------------------------------------------- what a field is for

    /** Typing in a field like this and then pressing enter is a search. Hindi: search, find. */
    val SEARCH = WordSet(listOf("search", "find", "look for", "खोज", "खोजें", "खोजिए", "सर्च", "ढूंढ", "ढूंढें", "तलाश"))

    /** Things that move under a finger: scrolling one of these may commit. Hindi: slide, swipe. */
    val SLIDER = WordSet(listOf("slide", "swipe", "drag", "स्लाइड", "स्वाइप"))

    // ---------------------------------------------------------------- the controls known to be harmless (careful mode)

    /**
     * Careful mode: a tap is free only when the control's own visible text is exactly one of these (and its full guard text has no
     * committing word, and the screen is not a payment screen). Exact phrases only. Not on it, on purpose: continue, next, ok, done,
     * yes, proceed, cancel, apply, save: each of them is the last button of something. A count in brackets or a digit is ignored
     * ("View cart (2)" is "view cart"). Hindi in the second part: add, add to cart, view cart, cart, search, view all, skip, not now,
     * later, close, back, menu, home, filter, sort.
     */
    private val HARMLESS_PHRASES = listOf(
        "add", "add +", "+ add", "add to cart", "add item", "add to bag", "plus", "+", "minus", "-", "−", "increase", "decrease", "search", "view cart",
        "cart", "bag", "basket", "see all", "view all", "show more", "more", "filter", "sort", "skip", "not now", "later", "close", "dismiss", "back",
        "navigate up", "home", "menu",
        "जोड़ें", "कार्ट में जोड़ें", "कार्ट देखें", "कार्ट", "खोजें", "खोज", "सभी देखें", "छोड़ें", "अभी नहीं", "बाद में", "बंद करें", "वापस", "मेन्यू", "होम",
        "फ़िल्टर", "फिल्टर", "क्रमबद्ध करें",
    )

    private val HARMLESS: Set<String> = HARMLESS_PHRASES.map { harmlessKey(it) }.toSet()

    /** Normalised, with digits and brackets taken out. */
    fun harmlessKey(s: String): String {
        val n = GuardText.norm(s)
        val sb = StringBuilder(n.length)
        for (c in n) sb.append(if (Character.isDigit(c) || c in "()[]{}·•|") ' ' else c)
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    fun isHarmless(visible: String): Boolean = harmlessKey(visible).let { it.isNotEmpty() && it in HARMLESS }

    /** A screen whose texts, put together, have one of these is a payment screen (C2 f). Hindi: to pay, amount payable, total payable, total amount, bill details, order summary, payment method, UPI, cash on delivery, checkout. */
    val PAYMENT_CONTEXT = WordSet(
        listOf(
            "to pay", "amount payable", "total payable", "grand total", "bill details", "order summary", "payment method", "pay using",
            "upi", "card ending", "cash on delivery", "place order", "checkout",
            "भुगतान करने के लिए", "देय राशि", "कुल देय", "कुल राशि", "बिल विवरण", "ऑर्डर सारांश", "भुगतान विधि", "यूपीआई", "कैश ऑन डिलीवरी", "चेकआउट",
        ),
    )

    // ---------------------------------------------------------------- commit words (S1)

    val COMMIT_EN = listOf(
        "pay", "payment", "place order", "place your order", "order now", "reorder", "buy", "purchase", "checkout", "check out",
        "book", "send", "post", "publish", "share", "delete", "remove", "uninstall", "install", "subscribe", "transfer", "donate",
        "submit", "sign in", "signin", "sign up", "signup", "log in", "login", "log out", "logout", "agree", "accept", "allow", "grant",
        "confirm", "slide to pay", "swipe to pay", "slide to", "swipe to", "drag to",
    )

    // The languages below: pay, order, buy, send, delete, book, install, confirm. Only words that are certain; each language in its own list.

    val COMMIT_HI = listOf(
        "भुगतान", "पे", "पेमेंट", "ऑर्डर करें", "ऑर्डर दें", "ऑर्डर प्लेस करें", "आर्डर करें", "खरीदें", "खरीदिए",
        "भेजें", "भेजिए", "बुक करें", "बुक", "हटाएं", "हटाएँ", "डिलीट", "मिटाएं", "इंस्टॉल", "इंस्टाल",
    )

    val COMMIT_BN = listOf(
        "পেমেন্ট", "পে করুন", "অর্ডার করুন", "অর্ডার দিন", "কিনুন", "পাঠান", "মুছুন", "ডিলিট", "বুক করুন", "ইনস্টল", "নিশ্চিত করুন",
    )

    val COMMIT_TA = listOf(
        "பணம் செலுத்து", "செலுத்து", "செலுத்துங்கள்", "ஆர்டர் செய்", "ஆர்டர் செய்யுங்கள்", "வாங்கு", "வாங்குங்கள்", "அனுப்பு", "அனுப்புங்கள்",
        "நீக்கு", "நீக்குங்கள்", "முன்பதிவு", "நிறுவு", "நிறுவவும்", "உறுதிப்படுத்து", "உறுதிப்படுத்தவும்",
    )

    val COMMIT_TE = listOf(
        "చెల్లించండి", "చెల్లింపు", "ఆర్డర్ చేయండి", "కొనండి", "పంపండి", "తొలగించండి", "బుక్ చేయండి", "ఇన్‌స్టాల్ చేయండి", "నిర్ధారించండి",
    )

    val COMMIT_MR = listOf(
        "पैसे भरा", "पेमेंट करा", "ऑर्डर करा", "खरेदी करा", "पाठवा", "हटवा", "डिलीट करा", "बुक करा", "इंस्टॉल करा", "पुष्टी करा",
    )

    val COMMIT_GU = listOf(
        "ચૂકવો", "ચુકવણી", "ઓર્ડર કરો", "ખરીદો", "મોકલો", "કાઢી નાખો", "ડિલીટ કરો", "બુક કરો", "ઇન્સ્ટોલ કરો", "પુષ્ટિ કરો",
    )

    val COMMIT_KN = listOf(
        "ಪಾವತಿಸಿ", "ಆರ್ಡರ್ ಮಾಡಿ", "ಖರೀದಿಸಿ", "ಕಳುಹಿಸಿ", "ಅಳಿಸಿ", "ಬುಕ್ ಮಾಡಿ", "ಸ್ಥಾಪಿಸಿ", "ದೃಢೀಕರಿಸಿ",
    )

    val COMMIT_ML = listOf(
        "പണമടയ്ക്കുക", "പേയ്മെന്റ്", "ഓർഡർ ചെയ്യുക", "വാങ്ങുക", "അയയ്ക്കുക", "ഇല്ലാതാക്കുക", "ഡിലീറ്റ്", "ബുക്ക് ചെയ്യുക", "ഇൻസ്റ്റാൾ ചെയ്യുക", "സ്ഥിരീകരിക്കുക",
    )

    val COMMIT_PA = listOf(
        "ਭੁਗਤਾਨ", "ਭੁਗਤਾਨ ਕਰੋ", "ਆਰਡਰ ਕਰੋ", "ਖਰੀਦੋ", "ਭੇਜੋ", "ਮਿਟਾਓ", "ਬੁੱਕ ਕਰੋ", "ਇੰਸਟਾਲ ਕਰੋ", "ਪੁਸ਼ਟੀ ਕਰੋ",
    )

    val COMMIT_UR = listOf(
        "ادائیگی", "ادائیگی کریں", "آرڈر کریں", "خریدیں", "بھیجیں", "حذف کریں", "بک کریں", "انسٹال کریں", "تصدیق کریں",
    )

    val COMMIT_ES = listOf(
        "pagar", "pagar ahora", "pago", "comprar", "comprar ahora", "realizar pedido", "hacer pedido", "finalizar compra", "enviar", "eliminar", "borrar",
        "reservar", "instalar", "desinstalar", "confirmar", "suscribirse", "aceptar", "permitir", "donar", "transferir",
    )

    val COMMIT_PT = listOf(
        "pagar", "pagar agora", "pagamento", "comprar", "comprar agora", "fazer pedido", "finalizar pedido", "finalizar compra", "enviar", "excluir", "apagar",
        "reservar", "instalar", "desinstalar", "confirmar", "assinar", "aceitar", "permitir", "doar", "transferir",
    )

    val COMMIT_FR = listOf(
        "payer", "paiement", "acheter", "commander", "passer la commande", "valider la commande", "envoyer", "supprimer", "réserver", "installer",
        "désinstaller", "confirmer", "s'abonner", "accepter", "autoriser", "valider",
    )

    val COMMIT_DE = listOf(
        "bezahlen", "jetzt bezahlen", "zahlen", "zahlung", "kaufen", "jetzt kaufen", "bestellen", "zahlungspflichtig bestellen", "senden", "absenden",
        "löschen", "buchen", "installieren", "deinstallieren", "bestätigen", "abonnieren", "akzeptieren", "erlauben", "überweisen", "spenden",
    )

    val COMMIT_ID = listOf(
        "bayar", "bayar sekarang", "pembayaran", "beli", "beli sekarang", "buat pesanan", "pesan sekarang", "kirim", "hapus", "instal",
        "konfirmasi", "setuju", "izinkan", "berlangganan", "donasi",
    )

    /** Every language together. */
    val COMMIT_ALL: List<String> = COMMIT_EN + COMMIT_HI + COMMIT_BN + COMMIT_TA + COMMIT_TE + COMMIT_MR + COMMIT_GU + COMMIT_KN + COMMIT_ML +
        COMMIT_PA + COMMIT_UR + COMMIT_ES + COMMIT_PT + COMMIT_FR + COMMIT_DE + COMMIT_ID

    /** Words that may sit between the words of a commit written in one piece: "paynow", "proceedtopay". */
    private val FILLERS = listOf("now", "to", "your", "the", "my", "button", "btn", "proceed", "and")

    val COMMIT = WordSet(COMMIT_ALL, squeeze = true, fillers = FILLERS)
}
