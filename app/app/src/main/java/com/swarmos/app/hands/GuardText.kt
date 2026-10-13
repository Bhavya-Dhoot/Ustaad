package com.swarmos.app.hands

import java.text.Normalizer
import java.util.Locale

/**
 * The text the guard judges an element by, and the matching of word lists against it.
 * Nothing here is shown to the model and nothing is cut: a button whose commit word sits after the 80th character is still a commit.
 */
object GuardText {
    private val CAMEL = Regex("(?<=\\p{Ll})(?=\\p{Lu})")
    private val SPACES = Regex("\\s+")
    private val ID_SEPARATORS = Regex("[._\\-]+")
    private val ID_DIGITS = Regex("(?<=\\p{L})(?=\\p{N})|(?<=\\p{N})(?=\\p{L})")
    private val TOKEN_SPLIT = Regex("[^\\p{L}\\p{N}\\p{M}]+")

    /** Letters that look like a Latin letter in another alphabet, and the small capitals; a commit word written with them still reads as the word. */
    private val LOOKALIKES: Map<Char, Char> = buildMap {
        val pairs = "аaеeоoрpсcуyхxіiјjѕsԁd" + // Cyrillic
            "οoνvαaρpıi" + // Greek, dotless i
            "ᴀaʙbᴄcᴅdᴇeꜰfɢgʜhɪiᴊjᴋkʟlᴍmɴnᴏoᴘpʀrꜱsᴛtᴜuᴠvᴡwʏyᴢz" // small capitals
        var i = 0
        while (i + 1 < pairs.length) {
            put(pairs[i], pairs[i + 1])
            i += 2
        }
    }

    /**
     * NFKC, then every format and control character out (zero-width, bidi, soft hyphen, tags), line breaks to spaces, a space before each
     * capital that follows a small letter ("PayNow" is "pay now"), lower case, look-alike letters folded, spaces collapsed.
     */
    fun norm(s: String, camel: Boolean = true): String {
        if (s.isEmpty()) return ""
        val nf = Normalizer.normalize(s, Normalizer.Form.NFKC)
        val sb = StringBuilder(nf.length)
        var i = 0
        while (i < nf.length) {
            val cp = nf.codePointAt(i)
            i += Character.charCount(cp)
            val type = Character.getType(cp)
            when {
                cp == 0x09 || cp == 0x0A || cp == 0x0B || cp == 0x0C || cp == 0x0D || Character.isSpaceChar(cp) -> sb.append(' ')
                type == Character.FORMAT.toInt() || type == Character.CONTROL.toInt() -> {}
                cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF || cp == 0x034F -> {}
                cp in 0x0300..0x036F || cp in 0x20D0..0x20FF -> {} // marks left on a letter (a line under the P of Pay)
                else -> sb.appendCodePoint(cp)
            }
        }
        val lower = (if (camel) CAMEL.replace(sb.toString(), " ") else sb.toString()).lowercase(Locale.ROOT)
        val folded = StringBuilder(lower.length)
        for (c in lower) folded.append(LOOKALIKES[c] ?: c)
        return SPACES.replace(folded, " ").trim()
    }

    /** "com.app:id/btn_PlaceOrder2" is "btn place order 2". */
    fun idWords(resId: String): String {
        val last = resId.substringAfterLast('/').substringAfterLast(':')
        if (last.isBlank()) return ""
        return norm(ID_DIGITS.replace(ID_SEPARATORS.replace(last, " "), " "))
    }

    fun join(vararg parts: String): String = SPACES.replace(parts.filter { it.isNotEmpty() }.joinToString(" "), " ").trim()

    fun tokens(guard: String): List<String> = TOKEN_SPLIT.split(guard).filter { it.isNotEmpty() }

    // ------------------------------------------------------------ prices and numbers

    private val PRICE_NOISE = Regex("[\\s\\u20b9$\\u20ac\\u00a3\\u00a5.,/+\\-]|rs\\.?|inr|usd|eur|mrp|only", RegexOption.IGNORE_CASE)

    /** "250", "rs. 250.00", "1,250": the whole text is an amount. */
    fun isPriceOnly(text: String): Boolean {
        val rest = PRICE_NOISE.replace(text, "")
        return rest.isNotEmpty() && rest.all { it.isDigit() }
    }

    /**
     * True when [text] holds a 13 to 19 digit number that passes the Luhn check. Digits are joined across any separator (spaces, dots, dashes,
     * slashes, line breaks, anything that is neither a letter nor a digit), digits of any script count, and any run of neighbouring digit
     * groups that adds up to 13 to 19 digits is tried, so two numbers written one after the other are both seen. A letter ends the run.
     */
    fun hasCardNumber(text: String): Boolean {
        val groups = ArrayList<String>()
        val cur = StringBuilder()
        fun flush() {
            if (cur.isNotEmpty()) groups += cur.toString()
            cur.setLength(0)
        }
        fun tried(): Boolean {
            flush()
            val list = groups.take(24)
            groups.clear()
            for (from in list.indices) {
                var digits = ""
                for (to in from until list.size) {
                    digits += list[to]
                    if (digits.length > 19) break
                    if (digits.length >= 13 && luhn(digits)) return true
                }
            }
            return false
        }
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val d = Character.digit(cp, 10)
            when {
                d >= 0 -> cur.append(('0' + d))
                Character.isLetter(cp) -> if (tried()) return true
                else -> flush()
            }
        }
        return tried()
    }

    private val DIGITS_4_OR_MORE = Regex("""\p{Nd}{4,}""")

    /** Every run of four or more digits becomes four stars: what is written to the run log never holds a number worth stealing. */
    fun maskDigits(s: String): String = DIGITS_4_OR_MORE.replace(s, "****")

    private fun luhn(d: String): Boolean {
        var sum = 0
        var dbl = false
        for (i in d.indices.reversed()) {
            var n = d[i] - '0'
            if (dbl) {
                n *= 2
                if (n > 9) n -= 9
            }
            sum += n
            dbl = !dbl
        }
        return sum % 10 == 0
    }

    private val CODE_RUN = Regex("(?<!\\d)\\d{4,8}(?!\\d)")

    /** Replaces each run of 4 to 8 digits by stars and adds the digits to [found]. */
    fun maskCodes(s: String, found: MutableSet<String>? = null): String = CODE_RUN.replace(s) {
        found?.add(it.value)
        "****"
    }

    // ------------------------------------------------------------ word lists

    /** Whole words and phrases, not parts of longer words. A space inside a phrase matches a space or a dash. Entries are normalised like the text. */
    fun wordRegex(words: List<String>): Regex {
        val alt = words.joinToString("|") { Regex.escape(it).replace(" ", "\\E[\\s\\-]+\\Q") }
        return Regex("(?<![\\p{L}\\p{N}\\p{M}_])(?:$alt)(?![\\p{L}\\p{N}\\p{M}_])")
    }

    /**
     * A list of words that are matched on normalised text. With [squeeze] the tokens next to each other also count when their
     * letters put together are one of the words ("p" "ay", "place-order", "paynow", "proceed to pay" written without spaces),
     * but a longer word that merely contains a short one ("display", "repay later") never does.
     */
    class WordSet(entries: List<String>, private val squeeze: Boolean = false, fillers: List<String> = emptyList()) {
        private val words = entries.map { norm(it) }.filter { it.isNotEmpty() }.distinct()
        private val regex = wordRegex(words)
        private val joined: Set<String> = words.map { it.replace(" ", "") }.toSet()
        private val pieces: Map<Char, List<String>> = (joined + fillers.map { norm(it) }).filter { it.isNotEmpty() }.groupBy { it[0] }

        fun matches(text: String): Boolean {
            if (text.isEmpty()) return false
            if (regex.containsMatchIn(text)) return true
            return squeeze && squeezed(text)
        }

        /** The whole text, ignoring punctuation at its ends, is one of the words. */
        fun matchesWhole(text: String): Boolean {
            val t = norm(text).trim { !(it.isLetterOrDigit() || Character.getType(it) == Character.NON_SPACING_MARK.toInt() || Character.getType(it) == Character.COMBINING_SPACING_MARK.toInt()) }
            return words.any { it == t }
        }

        private fun squeezed(text: String): Boolean {
            val tokens = tokens(text)
            for (i in tokens.indices) {
                val sb = StringBuilder()
                for (j in i until minOf(tokens.size, i + MAX_TOKENS)) {
                    sb.append(tokens[j])
                    if (sb.length > MAX_JOINED) break
                    if (segments(sb.toString())) return true
                }
            }
            return false
        }

        /** Can [s] be written as pieces (words and fillers) with at least one word among them? */
        private fun segments(s: String): Boolean {
            val n = s.length
            val plain = BooleanArray(n + 1)
            val withWord = BooleanArray(n + 1)
            plain[0] = true
            for (p in 0 until n) {
                if (!plain[p] && !withWord[p]) continue
                for (piece in pieces[s[p]].orEmpty()) {
                    if (!s.startsWith(piece, p)) continue
                    val e = p + piece.length
                    if (piece in joined) {
                        withWord[e] = true
                    } else {
                        if (plain[p]) plain[e] = true
                        if (withWord[p]) withWord[e] = true
                    }
                }
            }
            return withWord[n]
        }

        private companion object {
            const val MAX_TOKENS = 10
            const val MAX_JOINED = 40
        }
    }
}
