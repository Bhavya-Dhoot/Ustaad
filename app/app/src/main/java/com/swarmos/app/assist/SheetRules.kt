package com.swarmos.app.assist

import com.swarmos.app.hands.Origin

/** The decisions of the task sheet that matter for safety or for what is spoken. Pure, tested on the JVM. */
object SheetRules {
    /**
     * Origin of the run that a tap on Run starts. Text that another app put in the field keeps EXTERNAL_INTENT until the person
     * empties the field. A task the person wrote in the sheet is OWN_SHEET, or ASSISTANT_SESSION when the assistant session opened the sheet.
     */
    fun runOrigin(seededFromOutside: Boolean, fromSession: Boolean): Origin = when {
        seededFromOutside -> Origin.EXTERNAL_INTENT
        fromSession -> Origin.ASSISTANT_SESSION
        else -> Origin.OWN_SHEET
    }

    /** Runs of these origins wait in WAITING_FOR_GO after start(); the tap on Run that follows calls go(). */
    fun needsGo(origin: Origin): Boolean = origin != Origin.OWN_SHEET && origin != Origin.ASSISTANT_SESSION

    /** The field stays marked as coming from another app until it is emptied (then what is typed next is the person's own). */
    fun stillSeeded(seeded: Boolean, text: String): Boolean = seeded && text.isNotEmpty()

    fun canRun(task: String, handsReady: Boolean, busy: Boolean): Boolean = handsReady && !busy && task.isNotBlank()

    /** The result as it is read aloud: one line, no control characters, at most [max] characters, cut at a word where there is one. */
    fun spokenText(result: String, max: Int = 400): String {
        val line = AssistIntents.clean(result, max + 1)
        if (line.length <= max) return line
        val cut = line.substring(0, max)
        val space = cut.lastIndexOf(' ')
        return (if (space > max / 2) cut.substring(0, space) else cut).trimEnd()
    }
}
