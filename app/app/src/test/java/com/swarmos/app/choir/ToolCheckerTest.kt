package com.swarmos.app.choir

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCheckerTest {
    private val math = Problem(kind = Kind.MATH, text = "word problem")

    private fun ok(expr: String): Double = (ArithEval.eval(expr) as ArithResult.Ok).value
    private fun bad(expr: String) = assertTrue("expected an error for '$expr'", ArithEval.eval(expr) is ArithResult.Err)

    // ---- the evaluator --------------------------------------------------------------------------

    @Test fun evaluatorHandlesOperatorsPrecedenceAndPercent() {
        assertEquals(14.0, ok("2+3*4"), 1e-9)
        assertEquals(20.0, ok("(2+3)*4"), 1e-9)
        assertEquals(512.0, ok("2^3^2"), 1e-9)       // right associative
        assertEquals(-4.0, ok("-2^2"), 1e-9)
        assertEquals(0.5, ok("2^-1"), 1e-9)
        assertEquals(8.0, ok("2**3"), 1e-9)
        assertEquals(0.5, ok("50%"), 1e-9)
        assertEquals(30.0, ok("200*15%"), 1e-9)
        assertEquals(1002.5, ok("1,000 + 2.5"), 1e-9)
        assertEquals(2.5, ok("10/4"), 1e-9)
        assertEquals(-6.0, ok("−3 × 4 ÷ 2"), 1e-9)
        assertEquals(80000.0, ok("62400/(1-22/100)"), 1e-6)
    }

    @Test fun malformedExpressionsAreErrorsNotCrashes() {
        for (e in listOf("", "   ", "(3+4", "3)", "3++", "2*(", "1/0", "abc", "5 5", "2^^3", "1,2", "10 % 3", "*3", "3 4 +", "2(3)", "1e5")) bad(e)
        bad("9^9^9^9")                 // overflow
        bad("1+".repeat(150) + "1")     // longer than the 200 character cap
    }

    @Test fun nothingButArithmeticIsAccepted() {
        for (e in listOf("System.exit(0)", "Runtime.getRuntime()", "1; 2", "__import__('os')", "\$HOME", "x=1")) bad(e)
    }

    // ---- MATH -----------------------------------------------------------------------------------

    @Test fun mathVerifiedWhenStepsReachTheAnswer() {
        val r = ToolChecker.check(math, "40", listOf("12*3=36", "36+4=40"))
        assertEquals(CheckResult.VERIFIED, r.result)
    }

    @Test fun mathRefutedOnAnArithmeticSlip() {
        val r = ToolChecker.check(math, "381", listOf("17*23=381"))
        assertEquals(CheckResult.REFUTED, r.result)
        assertTrue(r.detail, r.detail.contains("391"))
    }

    @Test fun mathSingleExpressionFromAltSolver() {
        assertEquals(CheckResult.VERIFIED, ToolChecker.check(math, "90", listOf("(48/6)*12.5*0.9")).result)
        assertEquals(CheckResult.REFUTED, ToolChecker.check(math, "100", listOf("(48/6)*12.5*0.9")).result)
    }

    @Test fun mathFinalStepDisagreeingWithTheAnswerIsRefuted() {
        assertEquals(CheckResult.REFUTED, ToolChecker.check(math, "41", listOf("12*3=36", "36+4=40")).result)
    }

    @Test fun mathIntermediateStepsOnlyAreNotCheckableNotRefuted() {
        val r = ToolChecker.check(math, "24", listOf("Rate = 12/4 = 3 per hour", "Hours = 8"))
        assertEquals(CheckResult.NOT_CHECKABLE, r.result)
    }

    @Test fun mathNothingToCheck() {
        assertEquals(CheckResult.NOT_CHECKABLE, ToolChecker.check(math, "7", listOf("I added the numbers")).result)
        assertEquals(CheckResult.NOT_CHECKABLE, ToolChecker.check(math, "7", emptyList()).result)
        assertEquals(CheckResult.NOT_CHECKABLE, ToolChecker.check(math, "seven", listOf("3+4=7")).result)
    }

    @Test fun mathMalformedStepIsNotCheckable() {
        assertEquals(CheckResult.NOT_CHECKABLE, ToolChecker.check(math, "7", listOf("(3+4")).result)
        assertEquals(CheckResult.NOT_CHECKABLE, ToolChecker.check(math, "7", listOf("3+*4")).result)
    }

    @Test fun mathPercentStep() {
        val p = Problem(kind = Kind.MATH, text = "p1")
        assertEquals(CheckResult.VERIFIED, ToolChecker.check(p, "800", listOf("800*(1+25/100)*(1-20/100)=800")).result)
    }

    @Test fun mathProblemCheckOnlyWhenAsked() {
        val p = Problem(kind = Kind.MATH, text = "laptop", check = "62400/(1-22/100)")
        assertEquals(CheckResult.VERIFIED, ToolChecker.check(p, "Rs 80,000", emptyList(), useProblemCheck = true).result)
        assertEquals(CheckResult.REFUTED, ToolChecker.check(p, "76128", listOf("62400*1.22=76128"), useProblemCheck = true).result)
        // without the flag the problem's own check is ignored and only the model's steps count
        assertEquals(CheckResult.NOT_CHECKABLE, ToolChecker.check(p, "80000", emptyList(), useProblemCheck = false).result)
    }

    @Test fun mathUncomputableProblemCheckIsNotCheckable() {
        val p = Problem(kind = Kind.MATH, text = "x", check = "1/0")
        assertEquals(CheckResult.NOT_CHECKABLE, ToolChecker.check(p, "5", emptyList(), useProblemCheck = true).result)
    }

    // ---- MCQ ------------------------------------------------------------------------------------

    @Test fun mcqLetterRules() {
        val p = Problem(kind = Kind.MCQ, text = "q", options = listOf("a", "b", "c", "d"), expected = listOf("B"))
        assertEquals(CheckResult.NOT_CHECKABLE, ToolChecker.check(p, "B", emptyList()).result)   // valid letter proves nothing
        assertEquals(CheckResult.REFUTED, ToolChecker.check(p, "E", emptyList()).result)         // not an option
        assertEquals(CheckResult.REFUTED, ToolChecker.check(p, "no idea", emptyList()).result)
        assertEquals(CheckResult.VERIFIED, ToolChecker.check(p, "b) b", emptyList(), useProblemCheck = true).result)
        assertEquals(CheckResult.REFUTED, ToolChecker.check(p, "C", emptyList(), useProblemCheck = true).result)
    }

    @Test fun mcqOptionsParsedFromFreeText() {
        val p = Problem(kind = Kind.MCQ, text = "Pick one\nA) red\nB) blue")
        assertEquals(setOf('A', 'B'), p.optionLetters())
        assertEquals(CheckResult.REFUTED, ToolChecker.check(p, "C", emptyList()).result)
    }

    // ---- CODE -----------------------------------------------------------------------------------

    @Test fun codeComparesWithExpectedOutputsAndNeverExecutes() {
        val p = Problem(kind = Kind.CODE, text = "print", expected = listOf("4 13"))
        assertEquals(CheckResult.NOT_CHECKABLE, ToolChecker.check(Problem(kind = Kind.CODE, text = "print"), "4 13", emptyList(), true).result)
        assertEquals(CheckResult.NOT_CHECKABLE, ToolChecker.check(p, "4 13", emptyList(), useProblemCheck = false).result)
        assertEquals(CheckResult.VERIFIED, ToolChecker.check(p, "4  13", emptyList(), true).result)
        assertEquals(CheckResult.REFUTED, ToolChecker.check(p, "5 13", emptyList(), true).result)
        val multi = Problem(kind = Kind.CODE, text = "two cases", expected = listOf("1", "[2, 3]"))
        assertEquals(CheckResult.VERIFIED, ToolChecker.check(multi, "1\n[2,3]", emptyList(), true).result)
        assertEquals(CheckResult.REFUTED, ToolChecker.check(multi, "1\n[2,4]", emptyList(), true).result)
    }

    // ---- TEXT -----------------------------------------------------------------------------------

    @Test fun textQuoteExistsCheck() {
        val p = Problem(kind = Kind.TEXT, text = "Summarise.", source = "The tenant shall pay rent on the first day of each month. Late fees apply after five days.")
        assertEquals(CheckResult.VERIFIED, ToolChecker.check(p, "Rent is due \"on the first day of each month\".", emptyList()).result)
        assertEquals(CheckResult.REFUTED, ToolChecker.check(p, "The text says \"rent is waived in december\".", emptyList()).result)
        assertEquals(CheckResult.REFUTED, ToolChecker.check(p, "Late fees.", listOf("evidence: \"fees double after ten days\"")).result)
        assertEquals(CheckResult.NOT_CHECKABLE, ToolChecker.check(p, "Rent is monthly.", emptyList()).result)
    }

    @Test fun textSourceDefaultsToTheProblemText() {
        val p = Problem(kind = Kind.TEXT, text = "Alice met Bob in Paris. Question: where?")
        assertEquals(CheckResult.VERIFIED, ToolChecker.check(p, "\"in Paris\"", emptyList()).result)
    }
}
