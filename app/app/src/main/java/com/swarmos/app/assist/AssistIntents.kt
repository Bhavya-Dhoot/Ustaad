package com.swarmos.app.assist

import android.content.Intent
import com.swarmos.app.hands.Origin
import java.net.URLDecoder

/**
 * What an incoming intent asks for. [origin] is the origin of the TEXT in [task]. Nothing that comes from another app
 * starts by itself: [autoStart] is false for every intent this file reads, and the sheet still waits for the person's tap on Run.
 */
data class Incoming(val task: String, val origin: Origin, val autoStart: Boolean)

/**
 * Turns the parts of an incoming intent into a task for the sheet. Pure: no Android types in the signature, so it is tested on the JVM.
 * The text of another app is data. It is cleaned (control and invisible formatting characters out, cut at [MAX_TASK]) and shown to
 * the person, and a text that says "skip the confirmation" is just text.
 */
object AssistIntents {
    const val SCHEME = "ustaad"
    const val HOST = "do"
    const val MAX_TASK = 500

    /** Bound on the work done on a hostile text before it is cut. */
    private const val MAX_RAW = 4000

    private val DeepLink = Regex("""^ustaad://do/?(?:\?([^#]*))?(?:#.*)?$""", RegexOption.IGNORE_CASE)

    /**
     * [action] the intent action, [dataUri] its data as a string, [mimeType] its type, [extras] the text extras by key
     * (Intent.EXTRA_TEXT for a share, Intent.EXTRA_PROCESS_TEXT for selected text). An action that is not understood gives an empty task.
     */
    fun parse(action: String?, dataUri: String?, mimeType: String?, extras: Map<String, String>): Incoming {
        val text = when (action) {
            Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND -> ""
            Intent.ACTION_SEND -> if (isPlainText(mimeType)) extras[Intent.EXTRA_TEXT].orEmpty() else ""
            Intent.ACTION_PROCESS_TEXT -> extras[Intent.EXTRA_PROCESS_TEXT].orEmpty()
            Intent.ACTION_VIEW -> deepLinkTask(dataUri)
            else -> ""
        }
        return Incoming(clean(text), Origin.EXTERNAL_INTENT, autoStart = false)
    }

    private fun isPlainText(mimeType: String?): Boolean =
        mimeType != null && mimeType.substringBefore(';').trim().equals("text/plain", ignoreCase = true)

    /** The decoded q of ustaad://do?q=..., or "" when the link is another link, has no q, or is not valid percent-encoding. */
    private fun deepLinkTask(dataUri: String?): String {
        if (dataUri == null) return ""
        val query = DeepLink.matchEntire(dataUri.take(MAX_RAW * 3))?.groupValues?.get(1).orEmpty()
        for (pair in query.split('&')) {
            val key = pair.substringBefore('=')
            if (key != "q" || !pair.contains('=')) continue
            return try {
                URLDecoder.decode(pair.substringAfter('='), "UTF-8")
            } catch (_: IllegalArgumentException) {
                ""
            }
        }
        return ""
    }

    /**
     * Text safe to show in a field or a prompt. Tab and line breaks become one space, other control characters and invisible
     * formatting characters (bidirectional overrides, zero width space, byte order mark) are removed, runs of white space collapse to one,
     * and the result is cut at [max] characters without splitting a surrogate pair.
     */
    fun clean(raw: String, max: Int = MAX_TASK): String {
        val s = raw.take(MAX_RAW)
        val out = StringBuilder(minOf(s.length, max + 2))
        var gap = false
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            val type = Character.getType(cp)
            when {
                cp == 0x09 || cp == 0x0A || cp == 0x0B || cp == 0x0C || cp == 0x0D || cp == 0x85 -> gap = true
                type == Character.LINE_SEPARATOR.toInt() || type == Character.PARAGRAPH_SEPARATOR.toInt() -> gap = true
                type == Character.SPACE_SEPARATOR.toInt() -> gap = true
                Character.isISOControl(cp) -> {}
                // zero width (non-)joiner stay: Devanagari and other Indic scripts need them to form letters
                type == Character.FORMAT.toInt() && cp != 0x200C && cp != 0x200D -> {}
                type == Character.SURROGATE.toInt() -> {}
                else -> {
                    if (gap && out.isNotEmpty()) out.append(' ')
                    gap = false
                    out.appendCodePoint(cp)
                }
            }
            if (out.length > max + 1) break
        }
        var end = minOf(out.length, max)
        if (end > 0 && Character.isHighSurrogate(out[end - 1])) end--
        return out.substring(0, end).trimEnd()
    }
}
