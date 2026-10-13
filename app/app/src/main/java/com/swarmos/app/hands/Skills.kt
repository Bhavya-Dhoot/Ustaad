package com.swarmos.app.hands

/**
 * Skill cards held in memory. A card is a text file:
 *
 *     category: food_delivery
 *     apps: in.swiggy.android, com.application.zomato
 *     labels: swiggy, zomato
 *     ---
 *     hints for the step model ...
 *
 * [cards] maps a card name (the file name) to its text. The Android side reads the assets and passes them in.
 * A card for one app lists one package; a category card lists every app of the category. forApp prefers the card that
 * lists the fewest apps (the most specific), forCategory the one that lists the most.
 */
class MapSkillBook(cards: Map<String, String>) : SkillBook {
    private class Card(val name: String, val category: String, val apps: List<String>, val labels: List<String>, val body: String)

    private val all: List<Card> = cards.toSortedMap().map { (name, text) -> parse(name, text) }

    override fun categories(): List<String> = all.map { it.category }.filter { it.isNotEmpty() }.distinct().sorted()

    override fun forApp(pkg: String): String? =
        all.filter { c -> c.apps.any { it.equals(pkg, ignoreCase = true) } && c.body.isNotEmpty() }
            .minByOrNull { it.apps.size }?.body

    override fun forCategory(category: String): String? =
        all.filter { it.category.equals(category, ignoreCase = true) && it.body.isNotEmpty() }
            .maxByOrNull { it.apps.size }?.body

    override fun categoryOf(app: AppInfo): String? {
        all.firstOrNull { c -> c.category.isNotEmpty() && c.apps.any { it.equals(app.pkg, ignoreCase = true) } }?.let { return it.category }
        val label = app.label.lowercase()
        if (label.isBlank()) return null
        return all.firstOrNull { c -> c.category.isNotEmpty() && c.labels.any { it.isNotEmpty() && label.contains(it) } }?.category
    }

    companion object {
        const val BODY_MAX = 600

        private fun list(v: String) = v.split(',').map { it.trim() }.filter { it.isNotEmpty() }

        private fun parse(name: String, text: String): Card {
            val lines = text.replace("\r\n", "\n").lines()
            val sep = lines.indexOfFirst { it.trim() == "---" }
            if (sep < 0) return Card(name, "", emptyList(), emptyList(), text.trim().take(BODY_MAX))
            var category = ""
            var apps = emptyList<String>()
            var labels = emptyList<String>()
            for (l in lines.subList(0, sep)) {
                val i = l.indexOf(':')
                if (i < 0) continue
                val v = l.substring(i + 1)
                when (l.substring(0, i).trim().lowercase()) {
                    "category" -> category = v.trim()
                    "apps" -> apps = list(v)
                    "labels" -> labels = list(v.lowercase())
                }
            }
            val body = lines.subList(sep + 1, lines.size).joinToString("\n").trim().take(BODY_MAX)
            return Card(name, category, apps, labels, body)
        }
    }
}
