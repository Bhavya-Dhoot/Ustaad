package com.swarmos.app.choir

import com.swarmos.app.llm.ChatMessage

/**
 * Prompts and JSON schemas. Schema property order matters: llama.cpp builds the grammar in property order, so
 * `key_steps` comes first (the model reasons briefly) and `answer` after it.
 */
object ChoirPrompts {
    const val SOLVE_SCHEMA = """{"type":"object","properties":{"key_steps":{"type":"array","items":{"type":"string"},"maxItems":3},"answer":{"type":"string"},"confidence":{"type":"number"}},"required":["key_steps","answer","confidence"]}"""

    const val JUDGE_SCHEMA = """{"type":"object","properties":{"reason":{"type":"string"},"pick":{"type":"integer"}},"required":["reason","pick"]}"""

    const val TEMP_SOLVER = 0.3
    const val TEMP_ALT_SOLVER = 0.6
    const val TEMP_REVISE = 0.2
    const val TEMP_JUDGE = 0.0

    fun temperature(role: Role) = if (role == Role.ALT_SOLVER) TEMP_ALT_SOLVER else TEMP_SOLVER

    private const val FORMAT =
        "Reply with one JSON object only: key_steps (at most 3 short steps, each under 20 words), " +
            "answer (the final answer only, no explanation), confidence (a number from 0 to 1)."

    private fun answerRule(kind: Kind) = when (kind) {
        Kind.MATH -> "The answer is a plain number: no units, no commas."
        Kind.MCQ -> "The answer is only the letter of the correct option, for example B."
        Kind.CODE -> "The answer is exactly what the program prints or returns, nothing else."
        Kind.TEXT -> "Keep the answer short. Copy any quotation from the text exactly and put it in double quotes."
    }

    private fun method(kind: Kind, role: Role) = when (kind to role) {
        Kind.MATH to Role.SOLVER -> "Work step by step. Write each calculation in key_steps like 12*3=36."
        Kind.MATH to Role.ALT_SOLVER ->
            "Do not walk through the story. Write the whole computation as ONE arithmetic expression using only numbers, + - * / ^ and parentheses, " +
                "put it as key_steps[0], then give its value as the answer."
        Kind.MCQ to Role.SOLVER -> "Rule out options one by one in key_steps, then choose."
        Kind.MCQ to Role.ALT_SOLVER -> "Test each option against the statements and say which single option is consistent."
        Kind.CODE to Role.SOLVER -> "Trace the code step by step; track variable values in key_steps."
        Kind.CODE to Role.ALT_SOLVER -> "Pick one concrete test case and trace it by hand; write each variable change in key_steps."
        else -> "Answer from the text only."
    }

    fun solve(req: SolveRequest): List<ChatMessage> = listOf(
        ChatMessage("system", "You are one of several independent solvers on separate phones. ${method(req.kind, req.role)} ${answerRule(req.kind)} $FORMAT"),
        ChatMessage("user", req.problem),
    )

    fun revise(kind: Kind, problem: String, own: Candidate?, others: List<Candidate>): List<ChatMessage> {
        val sb = StringBuilder()
        sb.append("Problem:\n").append(problem).append("\n\n")
        if (own != null) sb.append("Your earlier answer: ").append(own.answer).append(" (").append(own.keySteps.joinToString("; ")).append(")\n\n")
        sb.append("Other solvers answered (authors unknown):\n")
        others.forEachIndexed { i, c -> sb.append(i + 1).append(". ").append(c.answer).append(" (").append(c.keySteps.joinToString("; ")).append(")\n") }
        return listOf(
            ChatMessage(
                "system",
                "You check answers. Find the single step where the answers differ, check that step yourself, then keep or change your answer. " +
                    "Do not change your answer just because others disagree. ${answerRule(kind)} $FORMAT",
            ),
            ChatMessage("user", sb.toString()),
        )
    }

    fun judge(kind: Kind, problem: String, candidates: List<Candidate>): List<ChatMessage> {
        val sb = StringBuilder("Problem:\n").append(problem).append("\n\nCandidate answers:\n")
        candidates.forEachIndexed { i, c -> sb.append(i).append(". ").append(c.answer).append(" (").append(c.keySteps.joinToString("; ")).append(")\n") }
        return listOf(
            ChatMessage(
                "system",
                "You are the judge. Re-check the reasoning and pick the candidate that is correct. " +
                    "Reply with one JSON object only: reason (one short sentence), pick (the candidate number).",
            ),
            ChatMessage("user", sb.toString()),
        )
    }
}
