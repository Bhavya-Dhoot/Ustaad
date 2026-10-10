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
        // the commonest Hindi function words, so a Hindi question matches on its content words
        "का", "की", "के", "को", "से", "में", "पर", "है", "हैं", "था", "थी", "थे", "और", "यह", "वह", "कि", "ने", "भी", "तो", "क्या", "कब", "कहाँ", "कहां",
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

/** Combines the Latin and Devanagari recognisers' texts. */
object OcrMerge {
    /** A Latin result with fewer letters and digits than this is suspect: the Latin model skips Devanagari lines silently. */
    const val SHORT_LATIN_CHARS = 150

    fun needsDevanagari(latin: String): Boolean = latin.count { it.isLetterOrDigit() } < SHORT_LATIN_CHARS

    /**
     * The Latin text, then the Devanagari recogniser's lines that contain Devanagari script and that the Latin text
     * does not already have. Both texts are kept; the second pass re-reads Latin lines slightly differently, and those
     * variants add nothing, so they are not appended.
     */
    fun merge(latin: String, devanagari: String?): String {
        if (devanagari.isNullOrBlank()) return latin
        val known = latin.lineSequence().map(::key).filter { it.isNotEmpty() }.toList()
        val extra = devanagari.lineSequence().map { it.trim() }.filter { line ->
            val k = key(line)
            hasDevanagari(line) && k.isNotEmpty() && known.none { it == k || it.contains(k) || k.contains(it) }
        }.toList()
        return if (extra.isEmpty()) latin else latin.trimEnd() + "\n" + extra.joinToString("\n")
    }

    private fun hasDevanagari(line: String): Boolean = line.any { it.code in 0x0900..0x097F }

    private fun key(line: String): String = line.lowercase().filter { it.isLetterOrDigit() }
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
            // distinct query words first, then how many times they occur; counted in place, no substring per window
            var distinct = 0
            var total = 0
            for (t in terms) {
                val n = hits(lower, t, s, e)
                if (n > 0 || t.isEmpty()) distinct++
                total += n
            }
            val score = distinct * 1000 + total
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

    /** How many times [t] occurs inside text[from, to), overlapping occurrences included. */
    private fun hits(text: String, t: String, from: Int, to: Int): Int {
        if (t.isEmpty()) return 0
        val c0 = t[0]
        val last = to - t.length
        var n = 0
        var i = from
        while (i <= last) {
            if (text[i] == c0 && text.startsWith(t, i)) n++
            i++
        }
        return n
    }

    private fun nextWordStart(t: String, from: Int): Int {
        var i = from
        while (i < t.length && !t[i].isWhitespace()) i++
        while (i < t.length && t[i].isWhitespace()) i++
        return i
    }
}

/** Reads at most a cap of bytes from a stream: a file bigger than the cap is not read whole just to be cut afterwards. */
object CappedRead {
    fun bytes(s: InputStream, cap: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(minOf(cap, 64 * 1024))
        val buf = ByteArray(16 * 1024)
        var left = cap
        while (left > 0) {
            val n = s.read(buf, 0, minOf(buf.size, left))
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
        return out.toByteArray()
    }
}

/** .docx text: unzip word/document.xml and strip tags. */
object DocxText {
    /** The XML of a document is much longer than its text; a bigger one (or a zip bomb) is read up to this and cut. */
    const val MAX_XML_BYTES = 8_000_000

    fun extract(stream: InputStream): String {
        ZipInputStream(stream).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: return ""
                if (e.name == "word/document.xml") return fromXml(CappedRead.bytes(zip, MAX_XML_BYTES).toString(Charsets.UTF_8))
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
