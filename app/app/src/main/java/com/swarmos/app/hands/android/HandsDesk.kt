package com.swarmos.app.hands.android

import com.swarmos.app.core.SwarmJson
import com.swarmos.app.hands.Brain
import com.swarmos.app.hands.ExpertDesk
import com.swarmos.app.hands.Tier
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One knowledge expert: a domain name, one line on when to use it, and the system prompt it answers with. */
class ExpertCard(val domain: String, val useFor: String, val prompt: String)

object ExpertCards {
    const val PROMPT_MAX = 1500

    /**
     * Cards are text files: `domain: x`, `use_for: ...`, a line `---`, then the system prompt. [cards] maps a card name to its text.
     * A card with no `---` line, no domain or no prompt is left out; a second card for a domain already taken is left out, so
     * the first by card name wins. Domains are compared without case.
     */
    fun parse(cards: Map<String, String>): List<ExpertCard> {
        val out = LinkedHashMap<String, ExpertCard>()
        for ((_, text) in cards.toSortedMap()) {
            val card = parseOne(text) ?: continue
            out.putIfAbsent(key(card.domain), card)
        }
        return out.values.toList()
    }

    internal fun key(domain: String) = domain.trim().lowercase()

    private fun parseOne(text: String): ExpertCard? {
        val lines = text.replace("\r\n", "\n").lines()
        val sep = lines.indexOfFirst { it.trim() == "---" }
        if (sep < 0) return null
        var domain = ""
        var useFor = ""
        for (l in lines.subList(0, sep)) {
            val i = l.indexOf(':')
            if (i < 0) continue
            val v = l.substring(i + 1).trim()
            when (l.substring(0, i).trim().lowercase()) {
                "domain" -> domain = v
                "use_for" -> useFor = v
            }
        }
        val prompt = lines.subList(sep + 1, lines.size).joinToString("\n").trim().take(PROMPT_MAX)
        if (domain.isEmpty() || prompt.isEmpty()) return null
        return ExpertCard(domain, useFor, prompt)
    }
}

/**
 * Knowledge experts and the person's own data for Hands. An expert answers with one STRONG completion; the caller says whether
 * the question may hold screen text (a planned question before any phone work does not, a question the step model wrote does).
 * [data] is the Data Union's answer to a question, or null when it has none or is not ready.
 */
class HandsDesk(
    cards: List<ExpertCard>,
    private val brain: Brain,
    private val data: suspend (String) -> String?,
) : ExpertDesk {
    private val byDomain = cards.associateBy { ExpertCards.key(it.domain) }
    private val names = cards.map { it.domain }

    override fun domains(): List<String> = names

    /** A question whose route is not stated is treated as screen-bound: it stays on this phone unless sharing is on. */
    override suspend fun ask(domain: String, question: String): String? = ask(domain, question, onPhoneOnly = true)

    /** [onPhoneOnly] is passed to the brain as it is: a question the step model wrote itself may hold screen text (rule S5). */
    override suspend fun ask(domain: String, question: String, onPhoneOnly: Boolean): String? {
        val card = byDomain[ExpertCards.key(domain)] ?: return null
        if (!brain.has(Tier.STRONG)) return null
        return try {
            answerOf(brain.complete(Tier.STRONG, card.prompt, question.take(QUESTION_MAX), ANSWER_SCHEMA, ANSWER_TOKENS, onPhoneOnly = onPhoneOnly))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun searchMyData(query: String): String? = try {
        data(query.take(QUESTION_MAX))?.trim()?.take(ANSWER_MAX)?.ifEmpty { null }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        const val ANSWER_TOKENS = 300
        const val QUESTION_MAX = 1000
        const val ANSWER_MAX = 1500

        val ANSWER_SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("answer", buildJsonObject { put("type", "string") }) })
            put("required", JsonArray(listOf(JsonPrimitive("answer"))))
        }

        /** The `answer` field of the model's JSON; null when the reply is not that or the answer is blank. */
        fun answerOf(raw: String): String? {
            val a = raw.indexOf('{')
            val b = raw.lastIndexOf('}')
            if (a < 0 || b <= a) return null
            val v = runCatching { (SwarmJson.parseToJsonElement(raw.substring(a, b + 1)) as? JsonObject)?.get("answer") as? JsonPrimitive }.getOrNull()
            if (v == null || !v.isString) return null
            return v.content.trim().take(ANSWER_MAX).ifEmpty { null }
        }

        /**
         * The text the Data Union gives back for a question: a saved answer that matched on its own comes first (that is what its
         * screen shows), then the written answer. Null when the ask did not end or neither has text (no chat model loaded gives sources only).
         */
        fun dataText(phase: String, savedAnswer: String?, answer: String): String? {
            if (phase != "done") return null
            return savedAnswer?.trim()?.ifEmpty { null } ?: answer.trim().ifEmpty { null }
        }
    }
}

/** Where a Hands question about the person's data may go. */
enum class DataPath { FEDERATED, LOCAL, NONE }

/**
 * The one decision about `search_my_data` (rule S5). The query is written by the model and may carry screen text, so with the
 * sharing switch off it never reaches the federated ask, which sends the query to the other paired devices: this phone's own index
 * answers instead. A question the person is asking at this moment is never touched: the federated ask would cancel it.
 */
class DataLookup(
    private val shareScreens: () -> Boolean,
    private val askInProgress: () -> Boolean,
    private val federated: suspend (String) -> String?,
    private val local: suspend (String) -> String?,
) {
    suspend fun answer(query: String): String? = when (pathFor(shareScreens(), askInProgress())) {
        DataPath.NONE -> null
        DataPath.LOCAL -> local(query)
        DataPath.FEDERATED -> federated(query)
    }

    companion object {
        fun pathFor(shareScreens: Boolean, askInProgress: Boolean): DataPath = when {
            askInProgress -> DataPath.NONE
            shareScreens -> DataPath.FEDERATED
            else -> DataPath.LOCAL
        }
    }
}

/** The top passages of this phone's own index as text for the model: each passage with its source (the document title), at most [MAX_CHARS] in all. */
object DataPassages {
    const val MAX_CHARS = 600
    const val MAX_PASSAGES = 3
    private const val TITLE_MAX = 60

    class Passage(val title: String, val text: String, val score: Double)

    fun text(found: List<Passage>): String? {
        val top = found.sortedByDescending { it.score }
            .map { Passage(clean(it.title), clean(it.text), it.score) }
            .filter { it.text.isNotEmpty() }
            .take(MAX_PASSAGES)
        if (top.isEmpty()) return null
        val budget = (MAX_CHARS - (top.size - 1)) / top.size // the newlines between entries count too
        return top.joinToString("\n") { p ->
            val head = "[" + p.title.ifEmpty { "untitled" }.take(TITLE_MAX) + "] "
            val room = budget - head.length
            head + if (p.text.length <= room) p.text else p.text.take((room - 1).coerceAtLeast(0)) + "…"
        }
    }

    private fun clean(s: String) = s.replace(Regex("\\s+"), " ").trim()
}
