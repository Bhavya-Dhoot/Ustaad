package com.swarmos.app.choir

/** One node's current answer, with the tool-check of that answer. */
data class Vote(
    val node: String,
    val answer: String,
    val confidence: Double,
    val check: CheckResult = CheckResult.NOT_CHECKABLE,
    val keySteps: List<String> = emptyList(),
    val checkSource: String = "none",
)

/** Votes that normalise to the same answer. */
data class Group(val answer: String, val members: List<Vote>) {
    val count: Int get() = members.size
    val confSum: Double get() = members.sumOf { it.confidence }

    /** VERIFIED if any member's own check verified the answer; REFUTED only if every member was refuted. */
    val check: CheckResult
        get() = when {
            members.any { it.check == CheckResult.VERIFIED } -> CheckResult.VERIFIED
            members.all { it.check == CheckResult.REFUTED } -> CheckResult.REFUTED
            else -> CheckResult.NOT_CHECKABLE
        }

    /** What a VERIFIED group was verified against; a test supplied with the problem wins over a model-written expression. */
    val checkSource: String
        get() {
            val sources = members.filter { it.check == CheckResult.VERIFIED }.map { it.checkSource }
            return if (PROBLEM_CHECK in sources) PROBLEM_CHECK else sources.firstOrNull() ?: "none"
        }

    /** The most confident member's reasoning, used when this answer is shown to others. */
    val best: Vote get() = members.maxByOrNull { it.confidence } ?: members.first()
}

/**
 * Final pick, pure logic. Order: tool-verified, majority of normalised answers, highest summed confidence,
 * judge, abstain. `judgePick` is an index into [groups] (distinct answers in order of first appearance).
 */
const val PROBLEM_CHECK = "problem-check"

object FinalPick {
    /** A confidence lead smaller than this does not decide anything: small models report 0.85 or 0.9 for everything. */
    const val CONF_MARGIN = 0.10

    fun groups(kind: Kind, votes: List<Vote>): List<Group> =
        Normalizer.cluster(kind, votes.map { it.answer }).map { idx -> Group(votes[idx[0]].answer, idx.map { votes[it] }) }

    fun hasMajority(kind: Kind, votes: List<Vote>): Boolean =
        votes.isNotEmpty() && groups(kind, votes).any { it.count * 2 > votes.size }

    fun decide(kind: Kind, votes: List<Vote>, judgePick: Int?, useTool: Boolean, totalNodes: Int = votes.size): FinalTrace {
        if (votes.isEmpty()) return FinalTrace(null, PickMethod.ABSTAIN, false, "no node answered")
        val gs = groups(kind, votes)
        val rows = gs.map { VoteRow(it.answer, it.count, it.confSum, it.check) }
        val n = votes.size
        val unanimous = gs.size == 1 && n >= 2

        fun result(g: Group, method: PickMethod, label: String) =
            FinalTrace(g.best.answer, method, useTool && g.check == CheckResult.VERIFIED, label, rows, g.check, g.checkSource)

        var elig = gs
        if (useTool) {
            val verified = gs.filter { it.check == CheckResult.VERIFIED }
            if (verified.size == 1) {
                return result(verified[0], PickMethod.TOOL, if (unanimous) "tool-verified, unanimous" else "tool-verified")
            }
            if (verified.size > 1) {
                elig = verified
            } else {
                val live = gs.filter { it.check != CheckResult.REFUTED }
                if (live.size == 1 && gs.size > 1) {
                    return result(live[0], PickMethod.TOOL, "other answers refuted by the tool")
                }
                if (live.isNotEmpty()) elig = live
            }
        }

        elig.firstOrNull { it.count * 2 > n }?.let { g ->
            val label = when {
                n == 1 -> "only 1 of $totalNodes nodes answered"
                unanimous && !useTool -> "unanimous"
                unanimous && g.check == CheckResult.REFUTED -> "unanimous but refuted by the tool"
                unanimous && g.check == CheckResult.NOT_CHECKABLE -> "unanimous but unverified"
                unanimous -> "unanimous"
                else -> "majority ${g.count}/$n"
            }
            return result(g, PickMethod.MAJORITY, label)
        }

        val byConf = elig.sortedByDescending { it.confSum }
        if (byConf.size == 1 || byConf[0].confSum - byConf[1].confSum >= CONF_MARGIN) {
            return result(byConf[0], PickMethod.CONFIDENCE, "no majority; highest summed confidence")
        }

        judgePick?.takeIf { it in gs.indices }?.let { gs[it] }?.takeIf { it in elig }?.let {
            return result(it, PickMethod.JUDGE, "no majority; judge pick")
        }
        return FinalTrace(null, PickMethod.ABSTAIN, false, "no majority, no decisive confidence, no judge pick: abstain", rows)
    }
}
