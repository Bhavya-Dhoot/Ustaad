package com.swarmos.app.pdu

import com.swarmos.app.llm.LlmClient

/**
 * The last [capacity] query vectors, least recently used out first, keyed by embedder name, query prefix and text. An ask with saved
 * cards embeds the same text twice in a row (once for the knowledge bank, once for the files); this makes the second one free.
 * A different embedder empties the whole cache, so a vector made by another model is never handed back.
 */
class QueryVectorCache(private val capacity: Int = 64) {
    private data class Key(val prefix: String, val text: String)

    private var embedder: String? = null
    private val map = object : LinkedHashMap<Key, FloatArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, FloatArray>?) = size > capacity
    }

    @Synchronized
    fun get(embedder: String, prefix: String, text: String): FloatArray? {
        switchTo(embedder)
        return map[Key(prefix, text)]
    }

    @Synchronized
    fun put(embedder: String, prefix: String, text: String, v: FloatArray) {
        switchTo(embedder)
        map[Key(prefix, text)] = v
    }

    @Synchronized
    fun clear() = map.clear()

    @get:Synchronized
    val size: Int get() = map.size

    private fun switchTo(name: String) {
        if (embedder != name) {
            map.clear()
            embedder = name
        }
    }
}

/** The one cache of this phone's query vectors, used by the file search and the knowledge bank search. */
object QueryVectors {
    private val cache = QueryVectorCache()

    /** The vector for [text] (query prefix applied, cut to the store's dimension), from the cache or from [client]. */
    suspend fun of(client: LlmClient, text: String): FloatArray {
        val prefix = PduEmbedConfig.queryPrefix
        cache.get(client.modelName, prefix, text)?.let { return it }
        val v = PduStore.fit(client.embed(listOf(PduEmbedConfig.query(text))).first())
        cache.put(client.modelName, prefix, text, v)
        return v
    }
}
