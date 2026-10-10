package com.swarmos.app.choir

/** What the Choir tab decides, kept out of the composables so it can be tested without a device. */
object ChoirScreenLogic {
    /** The problem list shows this many rows until "Show all" is pressed. */
    const val COLLAPSED_ROWS = 5

    /** Shown under "Phones in this run" when no other phone is online. */
    const val SOLO_NOTICE = "No other phone is online, running on this phone only."

    /** Shown on the scoreboard of a run in which no other phone answered. */
    const val SOLO_BOARD_NOTE = "No other phone took part, so only the two one-phone results are shown. Choir needs a second phone."

    const val TEST_SWITCH_LABEL = "Check answers against each problem's own test"

    fun testSwitchHelp(on: Boolean) =
        if (on) "On: the sample problems below are checked with a test that comes with them"
        else "Off: answers are only checked against the working each phone writes down"

    /** The problems of one kind, in list order. [total] counts all of that kind, [items] only those currently shown. */
    class ProblemGroup(val kind: Kind, val total: Int, val items: List<Problem>)

    /**
     * The list grouped by kind (in order of first appearance). Collapsed it keeps the first [COLLAPSED_ROWS] problems, so a
     * group that starts after them is not shown at all; expanded it keeps everything.
     */
    fun problemGroups(problems: List<Problem>, expanded: Boolean): List<ProblemGroup> {
        val shown = if (expanded) problems else problems.take(COLLAPSED_ROWS)
        val order = problems.map { it.kind }.distinct()
        return order.mapNotNull { k ->
            val items = shown.filter { it.kind == k }
            if (items.isEmpty()) null else ProblemGroup(k, problems.count { it.kind == k }, items)
        }
    }

    fun canExpand(problems: List<Problem>) = problems.size > COLLAPSED_ROWS

    fun soloNotice(onlinePeers: Int): String? = if (onlinePeers == 0) SOLO_NOTICE else null

    /** True when no other phone answered any problem of the run, so the choir bars would only repeat "one phone alone". */
    fun isSoloRun(rows: List<EvalRow>) = rows.isNotEmpty() && rows.all { it.nodesAnswered <= 1 }

    /**
     * The model name shown on this phone's row. The registry keeps the value it had at start-up ("FAKE") until something
     * refreshes it, so a stale or empty entry gives way to the live model client, which is what a run actually uses.
     */
    fun selfModelLabel(registryName: String?, liveName: String): String {
        val registry = registryName?.takeIf { it.isNotBlank() && it != FAKE }
        if (registry != null) return registry
        return liveName.ifBlank { registryName?.takeIf { it.isNotBlank() } ?: "model unknown" }
    }

    private const val FAKE = "FAKE"
}
