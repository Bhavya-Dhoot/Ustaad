package com.swarmos.app.choir

import kotlin.math.abs
import kotlin.math.max

/**
 * Answer normalisation, in Kotlin (never by a model).
 * Numbers: strip units, currency and commas, compare as decimals with a tolerance. MCQ: the option letter.
 * Text: lowercase, collapsed whitespace. Code output: whitespace-insensitive, number-aware.
 */
object Normalizer {
    private const val ABS_TOL = 0.005
    private const val REL_TOL = 1e-6

    private val NUMBER = Regex("""[-+]?\d+(?:\.\d+)?|[-+]?\.\d+""")
    private val FRACTION = Regex("""([-+]?\d+(?:\.\d+)?)\s*/\s*(\d+(?:\.\d+)?)""")
    private val COMMA_IN_NUMBER = Regex("""(?<=\d),(?=\d)""")
    private val CURRENCY = Regex("""(?i)\b(?:rs|inr|usd)\b\.?|[₹$€£]""")
    private val MCQ_EXPLICIT = Regex("""(?i)\b(?:option|answer|choice)\s*(?:is|:)?\s*[(\[]?([A-F])\b""")
    private val MCQ_LEAD = Regex("""^\s*[(\[]?([A-Fa-f])\s*[).:\]]""")
    private val MCQ_SINGLE = Regex("""^\s*[(\[]?([A-Fa-f])[)\].:]?\s*$""")
    private val MCQ_ANY = Regex("""\b([A-F])\b""")

    /** First usable number of [raw] (after the last '=' if there is one), or null. A simple fraction a/b is evaluated. */
    fun parseNumber(raw: String): Double? {
        var s = raw.trim().replace('−', '-').replace('–', '-')
        if (s.isEmpty()) return null
        s = CURRENCY.replace(s, " ")
        s = COMMA_IN_NUMBER.replace(s, "")
        if (s.contains('=')) s = s.substringAfterLast('=')
        FRACTION.find(s)?.let {
            val num = it.groupValues[1].toDoubleOrNull()
            val den = it.groupValues[2].toDoubleOrNull()
            if (num != null && den != null && den != 0.0 && NUMBER.find(s)?.range?.first == it.range.first) return num / den
        }
        return NUMBER.find(s)?.value?.toDoubleOrNull()
    }

    fun sameNumber(a: Double, b: Double): Boolean = abs(a - b) <= max(ABS_TOL, REL_TOL * max(abs(a), abs(b)))

    /** The MCQ option letter in [raw] ("B", "b)", "(B) 42", "Option B", "The answer is B"), or null. */
    fun mcqLetter(raw: String): Char? {
        MCQ_SINGLE.find(raw)?.let { return it.groupValues[1].uppercase()[0] }
        MCQ_EXPLICIT.find(raw)?.let { return it.groupValues[1].uppercase()[0] }
        MCQ_LEAD.find(raw)?.let { return it.groupValues[1].uppercase()[0] }
        return MCQ_ANY.find(raw)?.groupValues?.get(1)?.get(0)
    }

    fun normText(raw: String): String =
        raw.lowercase().replace(Regex("""\s+"""), " ").trim().trim('.', ',', ';', ':', '!', '?', '"', '\'', '`', ' ')

    private fun normCode(raw: String): String {
        val t = raw.trim().trim('`', '"', '\'')
        val compact = t.replace(Regex("""\s+"""), "").lowercase()
        // a purely numeric output compares as a number
        if (compact.matches(Regex("""[-+]?\d+(?:\.\d+)?"""))) return numKey(compact.toDouble())
        return compact
    }

    private fun numKey(v: Double): String =
        if (!v.isFinite()) "inf"
        else java.math.BigDecimal(v).setScale(4, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    /** Canonical key for [raw]; two answers with the same key count as the same answer (numbers also match within tolerance, see [same]). */
    fun normalize(kind: Kind, raw: String): String = when (kind) {
        Kind.MATH -> parseNumber(raw)?.let { "n:" + numKey(it) } ?: ("t:" + normText(raw))
        Kind.MCQ -> mcqLetter(raw)?.let { "l:$it" } ?: ("t:" + normText(raw))
        Kind.CODE -> "c:" + normCode(raw)
        Kind.TEXT -> "t:" + normText(raw)
    }

    fun same(kind: Kind, a: String, b: String): Boolean {
        if (kind == Kind.MATH) {
            val x = parseNumber(a)
            val y = parseNumber(b)
            if (x != null && y != null) return sameNumber(x, y)
        }
        return normalize(kind, a) == normalize(kind, b)
    }

    /** Cluster answers into groups of equal answers; returns lists of indices, groups ordered by first appearance. */
    fun cluster(kind: Kind, answers: List<String>): List<List<Int>> {
        val groups = mutableListOf<MutableList<Int>>()
        for ((i, a) in answers.withIndex()) {
            val g = groups.firstOrNull { same(kind, answers[it[0]], a) }
            if (g != null) g.add(i) else groups.add(mutableListOf(i))
        }
        return groups
    }
}
