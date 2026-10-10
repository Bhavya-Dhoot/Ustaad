package com.swarmos.app.choir

/**
 * Is a model's `answer` field a usable answer at all? The schema already constrains it per kind (a number, an option
 * letter, a short non-empty string); this is the check after parsing, because a constrained model can still produce
 * "}" or an empty string wherever the grammar allows it. An answer that fails is never voted on.
 *
 * Measured on Qwen3.5-4B (docs/benchmarks/2026-10-10-choir-answer-quality.md): with `answer` typed as a free string the
 * model wrote "", "}", ":" or "," there while its key_steps held the right number.
 */
object AnswerCheck {
    private val OPTION_LINE = Regex("""(?m)^\s*\(?([A-Fa-f])[).:]""")
    private val ALNUM = Regex("""[A-Za-z0-9]""")
    private val BRACKETS = Regex("""^(\[\s*\]|\{\s*\}|\(\s*\)|""|'')$""")

    /** Option letters offered by a problem text ("A) ... B) ..."), or A to D when it shows none. */
    fun optionLetters(problemText: String): List<Char> =
        OPTION_LINE.findAll(problemText).map { it.groupValues[1].uppercase()[0] }.distinct().toList().ifEmpty { listOf('A', 'B', 'C', 'D') }

    fun isValid(kind: Kind, answer: String, letters: List<Char> = emptyList()): Boolean {
        val a = answer.trim()
        if (a.isEmpty()) return false
        return when (kind) {
            Kind.MATH -> Normalizer.parseNumberStrict(a) != null
            Kind.MCQ -> Normalizer.mcqLetterStrict(a)?.let { letters.isEmpty() || it in letters } == true
            Kind.CODE, Kind.TEXT -> a.length >= MIN_TEXT_CHARS && (ALNUM.containsMatchIn(a) || (kind == Kind.CODE && BRACKETS.matches(a)))
        }
    }

    const val MIN_TEXT_CHARS = 1
}
