package com.swarmos.app.choir

import com.swarmos.app.llm.ChatMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Prompts and JSON schemas.
 *
 * Schema property order matters: llama.cpp builds the grammar in property order, so `key_steps` comes first (the model
 * reasons briefly) and `answer` after it. The `answer` property is typed per kind so the model cannot put prose,
 * braces or nothing there: MATH a JSON number, MCQ an enum of the option letters, CODE and TEXT a short non-empty string.
 * Every key step has a hard length cap and there are at most three, so the answer always has room inside the token budget.
 * (Measured with the real model: replies end by themselves at 54 to 118 tokens of the 300 allowed; the failures were
 * an unconstrained `answer`, not truncation. See docs/benchmarks/2026-10-10-choir-answer-quality.md.)
 */
object ChoirPrompts {
    /** The solve and revise schema for [kind]. [short] is the retry shape: one short key step. */
    fun solveSchema(kind: Kind, optionLetters: List<Char> = emptyList(), short: Boolean = false): String {
        val answer: JsonObject = when (kind) {
            Kind.MATH -> buildJsonObject { put("type", "number") }
            Kind.MCQ -> buildJsonObject {
                put("enum", JsonArray((optionLetters.ifEmpty { listOf('A', 'B', 'C', 'D') }).map { JsonPrimitive(it.toString()) }))
            }
            Kind.CODE, Kind.TEXT -> buildJsonObject {
                put("type", "string")
                put("minLength", AnswerCheck.MIN_TEXT_CHARS)
                put("maxLength", ChoirLimits.ANSWER_MAX_CHARS)
            }
        }
        return buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("key_steps", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject {
                        put("type", "string")
                        put("maxLength", if (short) SHORT_STEP_CHARS else ChoirLimits.STEP_MAX_CHARS)
                    })
                    put("maxItems", if (short) 1 else ChoirLimits.MAX_STEPS)
                })
                put("answer", answer)
                put("confidence", buildJsonObject { put("type", "number") })
            })
            put("required", buildJsonArray { add(JsonPrimitive("key_steps")); add(JsonPrimitive("answer")); add(JsonPrimitive("confidence")) })
        }.toString()
    }

    const val JUDGE_SCHEMA = """{"type":"object","properties":{"reason":{"type":"string"},"pick":{"type":"integer"}},"required":["reason","pick"]}"""

    private const val SHORT_STEP_CHARS = 60

    const val TEMP_SOLVER = 0.3
    const val TEMP_ALT_SOLVER = 0.6
    const val TEMP_REVISE = 0.2
    const val TEMP_JUDGE = 0.0

    fun temperature(role: Role) = if (role == Role.ALT_SOLVER) TEMP_ALT_SOLVER else TEMP_SOLVER

    private const val FORMAT =
        "Reply with one JSON object only: key_steps (at most 3 short steps; the last step states the final result), " +
            "answer (only the final result), confidence (a number from 0 to 1)."

    /** The retry: the first reply had no usable answer, so ask for the least that is needed. */
    private const val FORMAT_SHORT =
        "Reply with one JSON object only: key_steps (one short line), answer (only the final result, never empty), confidence (a number from 0 to 1)."

    private fun answerRule(kind: Kind) = when (kind) {
        Kind.MATH -> "The answer is one plain number: no units, no commas, no words."
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

    fun solve(req: SolveRequest, short: Boolean = false): List<ChatMessage> = listOf(
        ChatMessage(
            "system",
            if (short) "You are one of several independent solvers on separate phones. Solve the problem. ${answerRule(req.kind)} $FORMAT_SHORT"
            else "You are one of several independent solvers on separate phones. ${method(req.kind, req.role)} ${answerRule(req.kind)} $FORMAT",
        ),
        ChatMessage("user", req.problem),
    )

    /** One answer as a clean value with its steps, never as JSON: "Answer: 3654" and, indented, "Steps: ...". */
    private fun StringBuilder.candidate(label: String, c: Candidate) {
        append(label).append("Answer: ").append(c.answer.trim())
        if (c.keySteps.isNotEmpty()) append("\n   Steps: ").append(c.keySteps.joinToString("; "))
        append('\n')
    }

    fun revise(kind: Kind, problem: String, own: Candidate?, others: List<Candidate>, short: Boolean = false): List<ChatMessage> {
        val sb = StringBuilder()
        sb.append("Problem:\n").append(problem).append("\n\n")
        if (own != null) {
            sb.candidate("Your earlier answer. ", own)
            sb.append('\n')
        }
        sb.append("Other solvers answered (authors unknown):\n")
        others.forEachIndexed { i, c -> sb.candidate("${i + 1}. ", c) }
        return listOf(
            ChatMessage(
                "system",
                "You check answers. Find the single step where the answers differ, check that step yourself, then keep or change your answer. " +
                    "Do not change your answer just because others disagree. ${answerRule(kind)} ${if (short) FORMAT_SHORT else FORMAT}",
            ),
            ChatMessage("user", sb.toString()),
        )
    }

    fun judge(kind: Kind, problem: String, candidates: List<Candidate>): List<ChatMessage> {
        val sb = StringBuilder("Problem:\n").append(problem).append("\n\nCandidate answers:\n")
        candidates.forEachIndexed { i, c -> sb.candidate("$i. ", c) }
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
