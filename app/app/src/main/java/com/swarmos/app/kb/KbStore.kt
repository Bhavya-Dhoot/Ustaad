package com.swarmos.app.kb

import androidx.sqlite.SQLiteStatement
import com.swarmos.app.core.SwarmJson
import com.swarmos.app.pdu.PduStore
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * The knowledge bank's tables in the Data Union database file (same connection, same lock as [PduStore]).
 * `kb_cards.json` is the card as it travels; tier, revocation and the embedding are this phone's own columns.
 */
class KbStore(private val pdu: PduStore) : KbBacking {
    init {
        synchronized(pdu.lock) {
            exec(
                """CREATE TABLE IF NOT EXISTS kb_cards(
                    id TEXT PRIMARY KEY, json TEXT NOT NULL, tier TEXT NOT NULL, revokedMs INTEGER, everShared INTEGER NOT NULL,
                    savedMs INTEGER NOT NULL, emb BLOB)""",
            )
            exec("CREATE TABLE IF NOT EXISTS kb_tombstones(id TEXT PRIMARY KEY, json TEXT NOT NULL)")
        }
    }

    // the card vectors are read from the database once, and again after anything that changes them (guarded by the same lock)
    private val vectorCache = VectorCache { loadVectors() }

    override fun <T> atomic(block: () -> T): T = pdu.inTransaction(block)

    override fun countUsable(): Int = synchronized(pdu.lock) {
        query("SELECT COUNT(*) FROM kb_cards WHERE revokedMs IS NULL", {}) { it.getLong(0).toInt() }.first()
    }

    override fun replaceCard(card: KbCard) { synchronized(pdu.lock) {
        val st = pdu.db.prepare("UPDATE kb_cards SET json=? WHERE id=?")
        try { st.bindText(1, SwarmJson.encodeToString(card)); st.bindText(2, card.id); st.step() } finally { st.close() }
    } }

    override fun deleteTombstones(ids: Collection<String>) { synchronized(pdu.lock) {
        pdu.inTransaction {
            for (id in ids) {
                val st = pdu.db.prepare("DELETE FROM kb_tombstones WHERE id=?")
                try { st.bindText(1, id); st.step() } finally { st.close() }
            }
        }
    } }

    override fun entry(id: String): KbEntry? = synchronized(pdu.lock) {
        query("SELECT json, tier, revokedMs, everShared FROM kb_cards WHERE id=?", { it.bindText(1, id) }, ::row).firstOrNull()
    }

    override fun all(): List<KbEntry> = synchronized(pdu.lock) {
        query("SELECT json, tier, revokedMs, everShared FROM kb_cards ORDER BY savedMs DESC", {}, ::row)
    }

    /** Insert, or change the tier / revocation of a card already held. The embedding is kept. */
    override fun put(e: KbEntry) { synchronized(pdu.lock) {
        val st = pdu.db.prepare(
            "INSERT INTO kb_cards(id,json,tier,revokedMs,everShared,savedMs) VALUES(?,?,?,?,?,?) " +
                "ON CONFLICT(id) DO UPDATE SET tier=excluded.tier, revokedMs=excluded.revokedMs, everShared=excluded.everShared",
        )
        try {
            st.bindText(1, e.card.id); st.bindText(2, SwarmJson.encodeToString(e.card)); st.bindText(3, e.tier)
            if (e.revokedMs == null) st.bindNull(4) else st.bindLong(4, e.revokedMs)
            st.bindLong(5, if (e.everShared) 1 else 0); st.bindLong(6, System.currentTimeMillis())
            st.step()
        } finally { st.close() }
        vectorCache.invalidate()
    } }

    override fun tombstone(id: String): KbTombstone? = synchronized(pdu.lock) {
        query("SELECT json FROM kb_tombstones WHERE id=?", { it.bindText(1, id) }) { SwarmJson.decodeFromString<KbTombstone>(it.getText(0)) }.firstOrNull()
    }

    override fun putTombstone(t: KbTombstone) {
        synchronized(pdu.lock) {
            val st = pdu.db.prepare("INSERT OR REPLACE INTO kb_tombstones(id,json) VALUES(?,?)")
            try { st.bindText(1, t.id); st.bindText(2, SwarmJson.encodeToString(t)); st.step() } finally { st.close() }
        }
    }

    override fun tombstones(): List<KbTombstone> = synchronized(pdu.lock) {
        query("SELECT json FROM kb_tombstones", {}) { SwarmJson.decodeFromString<KbTombstone>(it.getText(0)) }
    }

    // ---- embeddings (this phone only; never sent) ---------------------------------------------------------

    fun vectors(): Map<String, FloatArray> = synchronized(pdu.lock) { vectorCache.get() }

    private fun loadVectors(): Map<String, FloatArray> =
        query("SELECT id, emb FROM kb_cards WHERE emb IS NOT NULL", {}) { it.getText(0) to PduStore.fromBytes(it.getBlob(1)) }.toMap()

    fun withoutVector(): List<KbCard> = synchronized(pdu.lock) {
        query("SELECT json FROM kb_cards WHERE emb IS NULL", {}) { SwarmJson.decodeFromString<KbCard>(it.getText(0)) }
    }

    fun setVector(id: String, v: FloatArray) = synchronized(pdu.lock) {
        val st = pdu.db.prepare("UPDATE kb_cards SET emb=? WHERE id=?")
        try { st.bindBlob(1, PduStore.toBytes(v)); st.bindText(2, id); st.step() } finally { st.close(); vectorCache.invalidate() }
    }

    fun clearVectors() = synchronized(pdu.lock) { exec("UPDATE kb_cards SET emb=NULL"); vectorCache.invalidate() }

    // ---- internals ----------------------------------------------------------------------------------------

    private fun row(st: SQLiteStatement) = KbEntry(
        SwarmJson.decodeFromString<KbCard>(st.getText(0)), st.getText(1), if (st.isNull(2)) null else st.getLong(2), st.getLong(3) == 1L,
    )

    private fun exec(sql: String) {
        val st = pdu.db.prepare(sql)
        try { st.step() } finally { st.close() }
    }

    private fun <T> query(sql: String, bind: (SQLiteStatement) -> Unit, read: (SQLiteStatement) -> T): List<T> {
        val st = pdu.db.prepare(sql)
        try {
            bind(st)
            val out = ArrayList<T>()
            while (st.step()) out += read(st)
            return out
        } finally { st.close() }
    }
}

/** A value that is read once and read again only after [invalidate]. The caller serialises access (KbStore does, with the store lock). */
class VectorCache<T>(private val load: () -> T) {
    private var value: T? = null
    private var loaded = false

    fun get(): T {
        if (!loaded) { value = load(); loaded = true }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    fun invalidate() {
        loaded = false
        value = null
    }
}
