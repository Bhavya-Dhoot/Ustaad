package com.swarmos.app.choir

import java.math.BigDecimal
import java.math.RoundingMode

sealed interface ArithResult {
    data class Ok(val value: Double) : ArithResult
    data class Err(val message: String) : ArithResult
}

private class ArithException(msg: String) : RuntimeException(msg)

/**
 * Safe arithmetic evaluator: numbers (decimals, thousands commas), + - * / ^ (also ** and the unicode x / division / minus),
 * parentheses, unary minus and a postfix percent (50% = 0.5). Nothing else is accepted, so there is nothing to execute.
 */
object ArithEval {
    const val MAX_LEN = 200

    fun eval(expr: String): ArithResult {
        val src = expr.trim()
            .replace('×', '*').replace('÷', '/').replace('−', '-').replace('–', '-')
            .replace("**", "^")
            .replace(Regex("[₹$€£]"), "")
        if (src.isEmpty()) return ArithResult.Err("empty expression")
        if (src.length > MAX_LEN) return ArithResult.Err("expression too long")
        return try {
            ArithResult.Ok(Parser(src).parseAll())
        } catch (e: ArithException) {
            ArithResult.Err(e.message ?: "bad expression")
        }
    }

    private class Parser(val s: String) {
        var i = 0

        fun parseAll(): Double {
            val v = expr()
            skipWs()
            if (i < s.length) throw ArithException("unexpected '${s[i]}' at $i")
            if (!v.isFinite()) throw ArithException("result is not a finite number")
            return v
        }

        private fun peek(): Char? = if (i < s.length) s[i] else null
        private fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }

        private fun expr(): Double {
            var v = term()
            while (true) {
                skipWs()
                when (peek()) {
                    '+' -> { i++; v += term() }
                    '-' -> { i++; v -= term() }
                    else -> return v
                }
            }
        }

        private fun term(): Double {
            var v = unary()
            while (true) {
                skipWs()
                when (peek()) {
                    '*' -> { i++; v *= unary() }
                    '/' -> {
                        i++
                        val d = unary()
                        if (d == 0.0) throw ArithException("division by zero")
                        v /= d
                    }
                    else -> return v
                }
            }
        }

        private fun unary(): Double {
            skipWs()
            return when (peek()) {
                '-' -> { i++; -unary() }
                '+' -> { i++; unary() }
                else -> power()
            }
        }

        private fun power(): Double {
            val base = postfix()
            skipWs()
            if (peek() == '^') {
                i++
                val e = unary()
                val r = Math.pow(base, e)
                if (!r.isFinite()) throw ArithException("power is not a finite number")
                return r
            }
            return base
        }

        private fun postfix(): Double {
            var v = primary()
            while (true) {
                skipWs()
                if (peek() != '%') return v
                i++
                v /= 100.0
                skipWs()
                val nx = peek()
                if (nx != null && nx !in "+-*/^)%") throw ArithException("'%' must be followed by an operator")
            }
        }

        private fun primary(): Double {
            skipWs()
            if (peek() == '(') {
                i++
                val v = expr()
                skipWs()
                if (peek() != ')') throw ArithException("missing ')'")
                i++
                return v
            }
            return number()
        }

        private fun number(): Double {
            val start = i
            var digits = 0
            while (i < s.length && s[i].isAsciiDigit()) { i++; digits++ }
            // thousands groups: ",ddd" not followed by another digit
            while (i + 3 < s.length && s[i] == ',' &&
                s[i + 1].isAsciiDigit() && s[i + 2].isAsciiDigit() && s[i + 3].isAsciiDigit() &&
                (i + 4 >= s.length || !s[i + 4].isAsciiDigit())
            ) {
                i += 4
                digits += 3
            }
            if (peek() == '.') {
                val save = i
                i++
                var frac = 0
                while (i < s.length && s[i].isAsciiDigit()) { i++; frac++ }
                if (frac == 0 && digits == 0) { i = save; throw ArithException("expected a number at $start") }
                digits += frac
            }
            if (digits == 0) {
                val what = peek()?.let { "'$it'" } ?: "end of input"
                throw ArithException("expected a number but found $what")
            }
            return s.substring(start, i).replace(",", "").toDouble()
        }

        private fun Char.isAsciiDigit() = this in '0'..'9'
    }
}

/** Outcome of a tool check. `source` says what the check was based on (model-written expression, problem check, ...). */
data class ToolResult(val result: CheckResult, val detail: String, val source: String = "none")

/**
 * The verifier. Pure Kotlin, no model, nothing is executed on the device.
 * VERIFIED / REFUTED / NOT_CHECKABLE per (problem, answer, key_steps).
 */
object ToolChecker {

    fun check(problem: Problem, answer: String, keySteps: List<String>, useProblemCheck: Boolean = false): ToolResult =
        when (problem.kind) {
            Kind.MATH -> checkMath(problem, answer, keySteps, useProblemCheck)
            Kind.MCQ -> checkMcq(problem, answer, useProblemCheck)
            Kind.CODE -> checkCode(problem, answer, useProblemCheck)
            Kind.TEXT -> checkText(problem, answer, keySteps)
        }

    // ---- MATH ----------------------------------------------------------------------------------

    private fun checkMath(p: Problem, answer: String, steps: List<String>, useProblemCheck: Boolean): ToolResult {
        val ans = Normalizer.parseNumber(answer)
            ?: return ToolResult(CheckResult.NOT_CHECKABLE, "answer is not a number")

        if (useProblemCheck && p.check != null) {
            return when (val r = ArithEval.eval(p.check)) {
                is ArithResult.Err -> ToolResult(CheckResult.NOT_CHECKABLE, "problem check not computable: ${r.message}", "problem-check")
                is ArithResult.Ok ->
                    if (Normalizer.sameNumber(r.value, ans)) ToolResult(CheckResult.VERIFIED, "${p.check} = ${fmt(r.value)}", "problem-check")
                    else ToolResult(CheckResult.REFUTED, "${p.check} = ${fmt(r.value)}, not ${fmt(ans)}", "problem-check")
            }
        }

        val found = mutableListOf<Pair<Int, Seg>>()
        for ((idx, step) in steps.withIndex()) {
            val segs = segments(step)
            for (k in 0 until segs.size - 1) {
                val l = segs[k]
                val r = segs[k + 1]
                if (l != null && r != null && l.hasOp && l.atEnd && r.atStart && !Normalizer.sameNumber(l.value, r.value)) {
                    return ToolResult(
                        CheckResult.REFUTED,
                        "step ${idx + 1}: ${l.text} = ${fmt(l.value)}, not ${fmt(r.value)}",
                        "model-expression",
                    )
                }
            }
            segs.filterNotNull().forEach { found.add(idx to it) }
        }
        val ops = found.filter { it.second.hasOp }
        if (ops.isEmpty()) return ToolResult(CheckResult.NOT_CHECKABLE, "no arithmetic expression in key_steps", "none")
        ops.firstOrNull { Normalizer.sameNumber(it.second.value, ans) }?.let {
            return ToolResult(CheckResult.VERIFIED, "${it.second.text} = ${fmt(it.second.value)}", "model-expression")
        }
        // Refute only the clean case: ONE computation that is meant to produce the answer and does not. With several
        // steps (or an answer that is an operand, as in a back-check "80000*0.78=62400") a mismatch proves nothing,
        // and a false REFUTED would throw a right answer out of the vote.
        val lastIdx = steps.indexOfLast { it.isNotBlank() }
        if (ops.size == 1 && ops[0].first == lastIdx) {
            val o = ops[0].second
            val operandIsAnswer = NUMBER_IN_TEXT.findAll(o.text).any { m ->
                m.value.replace(",", "").toDoubleOrNull()?.let { Normalizer.sameNumber(it, ans) } == true
            }
            if (!operandIsAnswer) {
                return ToolResult(CheckResult.REFUTED, "${o.text} = ${fmt(o.value)}, answer is ${fmt(ans)}", "model-expression")
            }
        }
        return ToolResult(CheckResult.NOT_CHECKABLE, "key_steps never reach the answer", "model-expression")
    }

    private class Seg(val text: String, val value: Double, val hasOp: Boolean, val atStart: Boolean, val atEnd: Boolean)

    private val RUN = Regex("""\(*[-+]?\s*[$₹€£]?\s*(?:\d|\.\d)[\d\s.,+\-*/^()%×÷−]*""")
    private val BINARY_OP = Regex("""[\d)%]\s*[-+*/^×÷−]""")
    private val NUMBER_IN_TEXT = Regex("""\d+(?:,\d{3})*(?:\.\d+)?""")

    /** One entry per '='-separated part of [step]: its best arithmetic run, or null. */
    private fun segments(step: String): List<Seg?> =
        step.split('=').mapIndexed { idx, part -> bestRun(part, right = idx > 0) }

    private fun bestRun(part: String, right: Boolean): Seg? {
        val runs = RUN.findAll(part).mapNotNull { m ->
            val raw = m.value
            val cleaned = raw.trimEnd(' ', '+', '-', '*', '/', '^', '(', ',', '.', '\t').trim()
            if (cleaned.isEmpty()) return@mapNotNull null
            val v = (ArithEval.eval(cleaned) as? ArithResult.Ok)?.value ?: return@mapNotNull null
            val startIdx = m.range.first
            val endIdx = m.range.first + raw.trimEnd().length
            Seg(
                text = cleaned,
                value = v,
                hasOp = BINARY_OP.containsMatchIn(cleaned),
                atStart = part.substring(0, startIdx).isBlank(),
                atEnd = part.substring(endIdx.coerceAtMost(part.length)).isBlank(),
            )
        }.toList()
        if (runs.isEmpty()) return null
        runs.filter { it.hasOp }.maxByOrNull { it.text.length }?.let { return it }
        return if (right) runs.first() else runs.last()
    }

    private fun fmt(v: Double): String =
        if (!v.isFinite()) v.toString()
        else BigDecimal(v).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    // ---- MCQ -----------------------------------------------------------------------------------

    /**
     * A valid option letter alone proves nothing about correctness, so this never returns VERIFIED by itself:
     * a letter that is not an option is REFUTED; a valid letter is NOT_CHECKABLE unless the problem supplies the key
     * (`expected`, one letter) and the run uses it.
     */
    private fun checkMcq(p: Problem, answer: String, useProblemCheck: Boolean): ToolResult {
        val letter = Normalizer.mcqLetter(answer)
        val letters = p.optionLetters()
        if (letter == null) return ToolResult(CheckResult.REFUTED, "answer has no option letter", "option-letters")
        if (letters.isNotEmpty() && letter !in letters) {
            return ToolResult(CheckResult.REFUTED, "$letter is not an option (${letters.sorted().joinToString("")})", "option-letters")
        }
        val key = p.expected.firstOrNull()?.let { Normalizer.mcqLetter(it) }
        if (useProblemCheck && key != null) {
            return if (key == letter) ToolResult(CheckResult.VERIFIED, "matches the problem's key $key", "problem-check")
            else ToolResult(CheckResult.REFUTED, "problem's key is $key, not $letter", "problem-check")
        }
        return ToolResult(CheckResult.NOT_CHECKABLE, "valid option letter $letter; correctness not checkable", "option-letters")
    }

    // ---- CODE ----------------------------------------------------------------------------------

    /** Nothing is executed on the device. Compares the returned outputs with the problem's expected outputs when supplied. */
    private fun checkCode(p: Problem, answer: String, useProblemCheck: Boolean): ToolResult {
        if (!useProblemCheck || p.expected.isEmpty()) {
            return ToolResult(CheckResult.NOT_CHECKABLE, "no expected outputs supplied; nothing is executed on device", "none")
        }
        val gotLines = answer.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val ok = if (p.expected.size > 1 && gotLines.size == p.expected.size) {
            gotLines.indices.all { Normalizer.same(Kind.CODE, gotLines[it], p.expected[it]) }
        } else {
            Normalizer.same(Kind.CODE, answer, p.expected.joinToString("\n"))
        }
        return if (ok) ToolResult(CheckResult.VERIFIED, "output matches expected", "problem-check")
        else ToolResult(CheckResult.REFUTED, "expected ${p.expected.joinToString(" | ")}, got ${answer.take(40)}", "problem-check")
    }

    // ---- TEXT ----------------------------------------------------------------------------------

    private val QUOTE = Regex("""["“]([^"“”]{2,})["”]""")

    /** Every quoted span in the answer and key_steps must appear in the source text. No quotes: NOT_CHECKABLE. */
    private fun checkText(p: Problem, answer: String, steps: List<String>): ToolResult {
        val source = squash(p.source ?: p.text)
        val spans = (listOf(answer) + steps).flatMap { s -> QUOTE.findAll(s).map { it.groupValues[1] }.toList() }
        if (spans.isEmpty()) return ToolResult(CheckResult.NOT_CHECKABLE, "no quoted span to check", "quote-exists")
        val missing = spans.firstOrNull { !source.contains(squash(it).trimEnd('.', ',', ';', ':', '!', '?')) }
        return if (missing == null) ToolResult(CheckResult.VERIFIED, "${spans.size} quoted span(s) found in the source", "quote-exists")
        else ToolResult(CheckResult.REFUTED, "quote not in the source: \"${missing.take(40)}\"", "quote-exists")
    }

    private fun squash(s: String) = s.lowercase().replace(Regex("""\s+"""), " ").trim()
}
