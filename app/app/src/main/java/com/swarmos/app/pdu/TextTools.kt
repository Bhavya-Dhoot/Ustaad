package com.swarmos.app.pdu

import java.io.InputStream
import java.util.zip.ZipInputStream

/** Fixed-window chunker: about 250 tokens (1000 characters) with 15 % overlap, split on natural boundaries. */
object Chunker {
    const val CHUNK_CHARS = 1000
    const val OVERLAP = 0.15

    /** Chunk bodies. Embedding and display add the title with [withTitle]. */
    fun split(raw: String, size: Int = CHUNK_CHARS, overlap: Double = OVERLAP): List<String> {
        val text = normalise(raw)
        if (text.isEmpty()) return emptyList()
        if (text.length <= size) return listOf(text)

        val overlapChars = (size * overlap).toInt()
        val out = ArrayList<String>()
        var start = 0
        while (true) {
            var end = minOf(start + size, text.length)
            if (end < text.length) end = breakPoint(text, start + (size * 0.6).toInt(), end)
            val piece = text.substring(start, end).trim()
            if (piece.isNotEmpty()) out += piece
            if (end >= text.length) break
            var next = maxOf(end - overlapChars, start + 1)
            // do not begin a chunk in the middle of a word
            while (next < end && !text[next - 1].isWhitespace()) next++
            start = next
        }
        return out
    }

    /** The text that is embedded and full-text indexed: title line, then the chunk. */
    fun withTitle(title: String, body: String): String = "$title\n$body"

    private fun normalise(s: String): String =
        s.replace("\r\n", "\n").replace('\r', '\n')
            .replace(Regex("[ \\t\\u00A0]+"), " ")
            .replace(Regex(" ?\\n ?"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

    /** Last paragraph break, else sentence end, else whitespace in [from, end]; end itself if none. */
    private fun breakPoint(t: String, from: Int, end: Int): Int {
        val para = t.lastIndexOf("\n\n", end - 2)
        if (para >= from) return para + 2
        var i = end - 1
        while (i >= from) {
            val c = t[i]
            if ((c == '.' || c == '!' || c == '?' || c == '\n') && i + 1 < t.length && t[i + 1].isWhitespace()) return i + 1
            i--
        }
        i = end - 1
        while (i >= from) {
            if (t[i].isWhitespace()) return i + 1
            i--
        }
        return end
    }
}

/** Query words for BM25. */
object QueryTerms {
    private val STOP = setOf(
        "a", "an", "the", "and", "or", "of", "to", "in", "on", "at", "for", "from", "by", "with", "about", "as",
        "is", "are", "was", "were", "be", "been", "am", "do", "does", "did", "have", "has", "had",
        "i", "me", "my", "mine", "we", "our", "you", "your", "he", "she", "it", "its", "they", "them", "their",
        "this", "that", "these", "those", "there", "here", "what", "which", "who", "whom", "whose",
        "where", "when", "why", "how", "can", "could", "should", "would", "will", "shall", "may", "might",
        "not", "no", "so", "if", "than", "then", "any", "all", "some", "please", "tell", "show", "find", "get",
    )
    private val TOKEN = Regex("[\\p{L}\\p{N}\\p{M}]+")

    fun terms(question: String, max: Int = 12): List<String> =
        TOKEN.findAll(question.lowercase()).map { it.value }
            .filter { it.length > 1 && it !in STOP }
            .distinct().take(max).toList()

    /** FTS5 MATCH expression, every term quoted so punctuation cannot break it. Null if there is nothing to match. */
    fun ftsMatch(terms: List<String>): String? =
        if (terms.isEmpty()) null else terms.joinToString(" OR ") { "\"" + it.replace("\"", "") + "\"" }
}

/** Picks the 400-character window of a chunk that mentions the most query terms. */
object Snippets {
    const val MAX = 400

    fun best(text: String, terms: Collection<String>, maxLen: Int = MAX): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        if (flat.length <= maxLen) return flat
        val lower = flat.lowercase()
        val budget = maxLen - 2 // room for the ellipsis marks
        var bestStart = 0
        var bestScore = -1
        var s = 0
        while (s < flat.length) {
            val e = minOf(s + budget, flat.length)
            val window = lower.substring(s, e)
            val score = terms.count { it in window }
            if (score > bestScore) { bestScore = score; bestStart = s }
            if (e == flat.length) break
            s = nextWordStart(flat, s + 40)
        }
        var end = minOf(bestStart + budget, flat.length)
        if (end < flat.length) {
            val sp = flat.lastIndexOf(' ', end)
            if (sp > bestStart) end = sp
        }
        val body = flat.substring(bestStart, end).trim()
        return (if (bestStart > 0) "…" else "") + body + (if (end < flat.length) "…" else "")
    }

    private fun nextWordStart(t: String, from: Int): Int {
        var i = from
        while (i < t.length && !t[i].isWhitespace()) i++
        while (i < t.length && t[i].isWhitespace()) i++
        return i
    }
}

/** .docx text: unzip word/document.xml and strip tags. */
object DocxText {
    fun extract(stream: InputStream): String {
        ZipInputStream(stream).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: return ""
                if (e.name == "word/document.xml") return fromXml(zip.readBytes().toString(Charsets.UTF_8))
            }
        }
    }

    fun fromXml(xml: String): String {
        val withBreaks = xml
            .replace(Regex("</w:p>"), "\n")
            .replace(Regex("<w:br\\s*/>|<w:cr\\s*/>"), "\n")
            .replace(Regex("<w:tab\\s*/>"), "\t")
        val stripped = withBreaks.replace(Regex("<[^>]+>"), "")
        return unescape(stripped).replace(Regex("[ \\t]+"), " ").replace(Regex(" ?\\n ?"), "\n").trim()
    }

    private fun unescape(s: String): String = Regex("&(#x?[0-9a-fA-F]+|amp|lt|gt|quot|apos);").replace(s) { m ->
        when (val g = m.groupValues[1]) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            else -> {
                val cp = if (g.startsWith("#x")) g.drop(2).toIntOrNull(16) else g.drop(1).toIntOrNull()
                if (cp != null && cp in 1..0x10FFFF) String(Character.toChars(cp)) else m.value
            }
        }
    }
}
