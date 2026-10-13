package com.swarmos.app.pdu

import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What a chunk names: its [type], and the [key] that makes two spellings of one thing the same entity. */
data class Mention(val type: String, val key: String)

object EntityType {
    const val DATE = "date"        // key 2026-03-13
    const val AMOUNT = "amount"    // key INR:1200.00
    const val EMAIL = "email"      // key priya@example.com
    const val PHONE = "phone"      // key +919876543210
    const val ID = "id"            // key pan:ABCDE1234F, gstin:..., policy:..., invoice:..., order:...
    const val PERSON = "person"    // key priya sharma
    const val ORG = "org"          // key hdfc bank
}

/**
 * Entities from the text of one chunk, by patterns and a stop list: no model, nothing leaves the phone. Each pattern's matches are
 * blanked before the next one runs, so a phone number cannot be read again as an amount. Names are capitalised word runs, which is
 * a heuristic: it finds "Priya Sharma" and "Bank of India" and some words that only look like names. Scripts without capitals
 * (Devanagari) give no names.
 */
object GraphExtract {
    const val MAX_PER_CHUNK = 24
    private const val MASK = '\u0001'

    fun entities(text: String): List<Mention> {
        val work = text.toCharArray()
        val out = LinkedHashSet<Mention>()
        fun scan(re: Regex, emit: (MatchResult) -> Mention?) {
            for (m in re.findAll(String(work))) {
                out += emit(m) ?: continue
                for (i in m.range) work[i] = MASK
            }
        }
        scan(EMAIL) { Mention(EntityType.EMAIL, it.value.lowercase()) }
        scan(GSTIN) { Mention(EntityType.ID, "gstin:" + it.value) }
        scan(PAN) { Mention(EntityType.ID, "pan:" + it.value) }
        scan(KEYED_ID) { m ->
            val token = m.groupValues[2]
            if (token.none { it.isDigit() } || DATE_LIKE.matches(token)) null
            else Mention(EntityType.ID, m.groupValues[1].lowercase() + ":" + token.uppercase().filter { it.isLetterOrDigit() })
        }
        scan(DATE_DMY_TEXT) { m -> date(m.groupValues[3].toInt(), month(m.groupValues[2]), m.groupValues[1].toInt()) }
        scan(DATE_MDY_TEXT) { m -> date(m.groupValues[3].toInt(), month(m.groupValues[1]), m.groupValues[2].toInt()) }
        scan(DATE_ISO) { m -> date(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
        scan(DATE_NUMERIC) { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            // day first, as written in India; month first only when the second number cannot be a month
            if (b > 12 && a <= 12) date(m.groupValues[3].toInt(), a, b) else date(m.groupValues[3].toInt(), b, a)
        }
        scan(AMOUNT_BEFORE) { m -> amount(m.groupValues[2], m.groupValues[1]) }
        scan(AMOUNT_AFTER) { m -> amount(m.groupValues[1], m.groupValues[2]) }
        scan(PHONE_INTL) { m -> m.value.filter { it.isDigit() }.takeIf { it.length in 8..15 }?.let { Mention(EntityType.PHONE, "+$it") } }
        scan(PHONE_IN) { m -> Mention(EntityType.PHONE, "+91" + m.groupValues[1] + m.groupValues[2]) }
        names(String(work), out)
        return out.take(MAX_PER_CHUNK)
    }

    /** The text a person sees for an entity. It is made from the type and key alone, never from the wording of any one file. */
    fun display(m: Mention): String = when (m.type) {
        EntityType.DATE -> runCatching { LocalDate.parse(m.key).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)) }.getOrDefault(m.key)
        EntityType.AMOUNT -> {
            val cur = m.key.substringBefore(':')
            val v = BigDecimal(m.key.substringAfter(':'))
            val f = DecimalFormat(if (v.stripTrailingZeros().scale() <= 0) "#,##0" else "#,##0.00", DecimalFormatSymbols(Locale.US))
            (if (cur == "INR") "₹" else "$") + f.format(v)
        }
        EntityType.ID -> m.key.substringBefore(':').let { if (it == "pan" || it == "gstin") it.uppercase() else it.replaceFirstChar(Char::uppercase) } + " " + m.key.substringAfter(':')
        EntityType.PERSON, EntityType.ORG -> m.key.split(' ').joinToString(" ") { if (it in LINK) it else it.replaceFirstChar(Char::uppercase) }
        else -> m.key
    }

    private fun date(y: Int, m: Int, d: Int): Mention? =
        if (y !in 1900..2100 || m !in 1..12 || d !in 1..YearMonth.of(y, m).lengthOfMonth()) null
        else Mention(EntityType.DATE, "%04d-%02d-%02d".format(y, m, d))

    private fun month(name: String) = MONTHS.indexOf(name.take(3).lowercase()) + 1

    private fun amount(num: String, currency: String): Mention? {
        val digits = num.trimEnd(',').replace(",", "")
        if (digits.length > 15) return null
        val v = BigDecimal(digits)
        if (v.signum() <= 0) return null
        val cur = if (currency.lowercase().replace(".", "") in setOf("rs", "inr", "rupees", "₹")) "INR" else "USD"
        return Mention(EntityType.AMOUNT, cur + ":" + v.setScale(2).toPlainString())
    }

    // ---- names -----------------------------------------------------------------------------------------------

    private class Word(val text: String, val start: Int, val end: Int, val kind: Char) // C capitalised, A acronym, I initial, L link, O other

    private fun names(s: String, out: MutableSet<Mention>) {
        var run = ArrayList<Word>()
        fun flush() { name(run)?.let { out += it }; run = ArrayList() }
        for (m in WORD.findAll(s)) {
            val t = m.value
            val after = s.getOrNull(m.range.last + 1)
            val kind = when {
                t.length > 1 && t[0].isUpperCase() && t[1].isLowerCase() -> 'C'
                t.length in 2..6 && t.all { it.isUpperCase() } -> 'A'
                t.length == 1 && t[0].isUpperCase() && after == '.' -> 'I'
                t in LINK -> 'L'
                else -> 'O'
            }
            val w = Word(t, m.range.first, m.range.last + 1, kind)
            val prev = run.lastOrNull()
            if (kind == 'O' || prev == null && kind == 'L') { flush(); continue }
            if (prev != null) {
                val gap = s.substring(prev.end, w.start)
                val dotted = (prev.kind == 'I' || prev.text.lowercase() in TITLES) && gap == ". "
                if (!(gap.isNotEmpty() && gap.all { it == ' ' || it == '\t' } || gap.trim() == "&" || dotted)) flush()
            }
            run.add(w)
        }
        flush()
    }

    private fun name(run: List<Word>): Mention? {
        val words = run.toMutableList()
        while (words.lastOrNull()?.kind == 'L') words.removeAt(words.lastIndex)
        var titled = false
        while (words.isNotEmpty() && (words.first().text.lowercase() in TITLES || words.first().text.lowercase() in STOP)) {
            if (words.first().text.lowercase() in TITLES) titled = true
            words.removeAt(0)
        }
        while (words.isNotEmpty() && (words.last().text.lowercase() in STOP || words.last().kind == 'L')) words.removeAt(words.lastIndex)
        if (words.size > 5 || words.none { it.kind == 'C' } || words.none { it.text.length >= 3 }) return null
        if (words.size < 2 && !titled) return null
        val tokens = words.map { it.text.lowercase().removeSuffix("'s").removeSuffix("’s").trim('\'', '’', '-') }
        val org = tokens.any { it in ORG_WORDS }
        return Mention(if (org) EntityType.ORG else EntityType.PERSON, tokens.joinToString(" "))
    }

    // ---- patterns ----------------------------------------------------------------------------------------------

    private const val MON = "jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?"
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private const val NUM = """(\d[\d,]*(?:\.\d{1,2})?)"""

    private val EMAIL = Regex("""\b[A-Za-z0-9._%+\-]+@[A-Za-z0-9\-]+(?:\.[A-Za-z0-9\-]+)*\.[A-Za-z]{2,}\b""")
    private val GSTIN = Regex("""\b\d{2}[A-Z]{5}\d{4}[A-Z][1-9A-Z]Z[0-9A-Z]\b""")
    private val PAN = Regex("""\b[A-Z]{5}\d{4}[A-Z]\b""")
    private val KEYED_ID = Regex("""(?i)\b(policy|invoice|order)\b[\s.:#\-]*(?:(?:no|number|num|id)\b[\s.:#\-]*)?([A-Za-z0-9][A-Za-z0-9/\-]{3,24})""")
    private val DATE_LIKE = Regex("""\d{1,4}[/.\-]\d{1,2}[/.\-]\d{1,4}""")
    private val DATE_DMY_TEXT = Regex("""(?i)\b(\d{1,2})(?:st|nd|rd|th)?[\s\-/.]+($MON)\b\.?,?[\s\-/.]*(\d{4})\b""")
    private val DATE_MDY_TEXT = Regex("""(?i)\b($MON)\b\.?[\s\-/.]*(\d{1,2})(?:st|nd|rd|th)?\b,?[\s\-/.]*(\d{4})\b""")
    private val DATE_ISO = Regex("""\b(\d{4})-(\d{2})-(\d{2})\b""")
    private val DATE_NUMERIC = Regex("""\b(\d{1,2})[/.\-](\d{1,2})[/.\-](\d{4})\b""")
    private val AMOUNT_BEFORE = Regex("""(?i)(?<![A-Za-z])(₹|rs\.?|inr|us\$|usd|\$)\s*$NUM""")
    private val AMOUNT_AFTER = Regex("""(?i)(?<![\d,.])$NUM\s*(inr|usd|rupees|dollars)\b""")
    private val PHONE_INTL = Regex("""(?<![\w+])\+\d{1,3}(?:[ \-.]?\(?\d{1,5}\)?){2,6}""")
    private val PHONE_IN = Regex("""(?<![\d\w])(?:(?:\+?91|0)[ \-]?)?([6-9]\d{4})[ \-]?(\d{5})(?![\d\w])""")
    private val WORD = Regex("""\p{L}[\p{L}'’\-]*""")

    private val TITLES = setOf("mr", "mrs", "ms", "miss", "dr", "shri", "smt", "sri", "prof")
    private val LINK = setOf("of", "de", "van", "von", "bin", "al")
    private val ORG_WORDS = setOf(
        "ltd", "limited", "pvt", "private", "inc", "llc", "llp", "corp", "corporation", "company", "bank", "insurance", "hospital",
        "university", "college", "school", "technologies", "solutions", "services", "industries", "enterprises", "associates",
        "foundation", "trust", "society", "group", "hotel", "restaurant", "stores", "pharmacy", "clinic", "airlines", "motors",
        "finance", "capital", "systems", "labs",
    )
    private val STOP = setOf(
        "the", "a", "an", "this", "that", "these", "those", "and", "or", "of", "for", "from", "to", "in", "on", "at", "by", "with", "as",
        "if", "when", "what", "where", "who", "why", "how", "is", "are", "was", "it", "we", "you", "your", "our", "my", "i", "he", "she",
        "they", "dear", "hello", "hi", "thanks", "thank", "regards", "sincerely", "yours", "please", "kindly", "find", "attached",
        "did", "do", "does", "has", "have", "had", "can", "could", "will", "would", "should", "might", "must", "shall", "be", "been", "not",
        "yes", "also", "but", "so", "then", "than", "there", "here", "which", "whom", "whose", "contact", "subject", "regarding", "reference",
        "issued", "received", "sent", "signed",
        "invoice", "bill", "receipt", "total", "subtotal", "amount", "date", "due", "paid", "payment", "balance", "statement", "account",
        "number", "no", "order", "policy", "premium", "tax", "gst", "pan", "page", "address", "name", "phone", "mobile", "email", "mail",
        "customer", "client", "sir", "madam", "note", "notes", "terms", "conditions", "summary", "details", "description", "item",
        "items", "qty", "quantity", "price", "rate", "discount", "cgst", "sgst", "igst", "hsn", "sac", "signature", "authorised",
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december",
        "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec",
    )
}

/** What storing one document's chunks adds to the graph: who is mentioned in which chunk, and how often two share a chunk. */
class GraphPlan(val mentions: List<Pair<Mention, Long>>, val links: Map<Pair<Mention, Mention>, Int>)

object GraphBuild {
    /** [chunks] are (chunk id, text). A link joins two entities that share a chunk, its count is the number of chunks they share. */
    fun plan(chunks: List<Pair<Long, String>>): GraphPlan {
        val mentions = ArrayList<Pair<Mention, Long>>()
        val links = LinkedHashMap<Pair<Mention, Mention>, Int>()
        for ((chunkId, text) in chunks) {
            val found = runCatching { GraphExtract.entities(text) }.getOrDefault(emptyList()) // a text that breaks a pattern gives no entities, not a failed index run
            found.forEach { mentions += it to chunkId }
            val sorted = found.sortedWith(compareBy({ it.type }, { it.key }))
            for (i in sorted.indices) for (j in i + 1 until sorted.size) links.merge(sorted[i] to sorted[j], 1, Int::plus)
        }
        return GraphPlan(mentions, links)
    }
}
