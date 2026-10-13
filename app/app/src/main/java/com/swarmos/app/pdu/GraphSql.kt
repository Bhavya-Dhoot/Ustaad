package com.swarmos.app.pdu

import androidx.sqlite.SQLiteStatement

/**
 * The graph's tables in the Data Union database file (same connection and lock as [PduStore]). The tables are made with IF NOT EXISTS,
 * so an index that has none gets them empty, and [backfill] then fills them from the chunks already stored, a few documents at a time.
 * A document's rows are written and removed inside the transaction that writes or removes its chunks.
 */
internal class GraphSql(private val s: PduStore) : GraphRows {
    // ---- writes (inside the caller's transaction) -----------------------------------------------------------------

    /** Replaces the graph rows of [docId] with those of its new [chunks] (chunk id, text). */
    fun replaceDoc(docId: String, chunks: List<Pair<Long, String>>) = s.inTransaction {
        dropDoc(docId)
        val plan = GraphBuild.plan(chunks)
        val ids = HashMap<Mention, Long>()
        fun id(m: Mention): Long = ids.getOrPut(m) {
            s.write("INSERT OR IGNORE INTO graph_entities(type,key,display) VALUES(?,?,?)") {
                it.bindText(1, m.type); it.bindText(2, m.key); it.bindText(3, GraphExtract.display(m))
            }
            s.query("SELECT id FROM graph_entities WHERE type=? AND key=?", { it.bindText(1, m.type); it.bindText(2, m.key) }) { it.getLong(0) }.first()
        }
        for ((m, chunk) in plan.mentions) {
            val entity = id(m)
            s.write("INSERT OR IGNORE INTO graph_mentions(entityId,chunkId,docId) VALUES(?,?,?)") {
                it.bindLong(1, entity); it.bindLong(2, chunk); it.bindText(3, docId)
            }
        }
        for ((pair, n) in plan.links) {
            val x = id(pair.first)
            val y = id(pair.second)
            s.write("INSERT OR REPLACE INTO graph_links(a,b,docId,n) VALUES(?,?,?,?)") {
                it.bindLong(1, minOf(x, y)); it.bindLong(2, maxOf(x, y)); it.bindText(3, docId); it.bindLong(4, n.toLong())
            }
        }
        s.write("INSERT OR IGNORE INTO graph_docs(docId) VALUES(?)") { it.bindText(1, docId) }
    }

    /** Removes the mentions and links of [docId], and every entity that no document mentions any more. */
    fun dropDoc(docId: String) = s.inTransaction {
        val entities = s.query("SELECT DISTINCT entityId FROM graph_mentions WHERE docId=?", { it.bindText(1, docId) }) { it.getLong(0) }
        s.write("DELETE FROM graph_mentions WHERE docId=?") { it.bindText(1, docId) }
        s.write("DELETE FROM graph_links WHERE docId=?") { it.bindText(1, docId) }
        s.write("DELETE FROM graph_docs WHERE docId=?") { it.bindText(1, docId) }
        for (e in entities) s.write("DELETE FROM graph_entities WHERE id=? AND NOT EXISTS (SELECT 1 FROM graph_mentions WHERE entityId=?)") {
            it.bindLong(1, e); it.bindLong(2, e)
        }
    }

    /** Every chunk is gone (a re-index wipes them): so is the graph. Runs inside the caller's transaction. */
    fun clear() { for (t in listOf("graph_links", "graph_mentions", "graph_entities", "graph_docs")) s.exec("DELETE FROM $t") }

    /** Builds the graph of up to [batch] stored documents that have none yet. Returns how many it did; 0 when the graph is complete. */
    fun backfill(batch: Int): Int = s.inTransaction {
        val todo = s.query("SELECT docId FROM docs WHERE chunks>0 AND docId NOT IN (SELECT docId FROM graph_docs) LIMIT ?", { it.bindLong(1, batch.toLong()) }) { it.getText(0) }
        for (d in todo) replaceDoc(d, s.query("SELECT id, text FROM chunks WHERE docId=? ORDER BY id", { it.bindText(1, d) }) { it.getLong(0) to it.getText(1) })
        todo.size
    }

    // ---- reads (facts only; see GraphAnswers) ---------------------------------------------------------------------

    private fun entityRow(st: SQLiteStatement, at: Int = 0) = EntityRow(st.getLong(at), st.getText(at + 1), st.getText(at + 2), st.getText(at + 3))

    /** Columns: docId, title, kind, dateMs, folder, isPrivate (private when the folder has no row), chunk, count. */
    private fun docRow(st: SQLiteStatement, at: Int) =
        DocRow(st.getText(at), st.getText(at + 1), st.getText(at + 2), st.getLong(at + 3), st.getText(at + 4), st.getLong(at + 5) == 1L, st.getLong(at + 6), st.getLong(at + 7).toInt())

    private val docJoin = "JOIN docs d ON d.docId=%s LEFT JOIN folders f ON f.folder=d.folder"

    override fun entity(type: String, key: String): EntityRow? = synchronized(s.lock) {
        s.query("SELECT id,type,key,display FROM graph_entities WHERE type=? AND key=?", { it.bindText(1, type); it.bindText(2, key) }) { entityRow(it) }.firstOrNull()
    }

    override fun entitiesLike(tokens: List<String>, limit: Int): List<EntityRow> = synchronized(s.lock) {
        if (tokens.isEmpty()) return emptyList()
        s.query(
            "SELECT id,type,key,display FROM graph_entities WHERE " + tokens.joinToString(" AND ") { "key LIKE ?" } + " LIMIT ?",
            { st -> tokens.forEachIndexed { i, t -> st.bindText(i + 1, "%$t%") }; st.bindLong(tokens.size + 1, limit.toLong()) },
        ) { entityRow(it) }
    }

    override fun mentionDocs(entityId: Long): List<DocRow> = synchronized(s.lock) {
        s.query(
            "SELECT m.docId, d.title, d.kind, d.dateMs, d.folder, COALESCE(f.isPrivate,1), MIN(m.chunkId), COUNT(*) FROM graph_mentions m " +
                docJoin.format("m.docId") + " WHERE m.entityId=? GROUP BY m.docId ORDER BY COUNT(*) DESC LIMIT 500",
            { it.bindLong(1, entityId) },
        ) { docRow(it, 0) }
    }

    override fun linkDocs(entityId: Long): List<LinkRow> = synchronized(s.lock) {
        s.query(
            "SELECT e.id, e.type, e.key, e.display, l.docId, d.title, d.kind, d.dateMs, d.folder, COALESCE(f.isPrivate,1), " +
                "(SELECT MIN(chunkId) FROM graph_mentions WHERE entityId=? AND docId=l.docId), l.n " +
                "FROM graph_links l JOIN graph_entities e ON e.id=CASE WHEN l.a=? THEN l.b ELSE l.a END " + docJoin.format("l.docId") +
                " WHERE l.a=? OR l.b=? LIMIT 2000",
            { st -> for (i in 1..4) st.bindLong(i, entityId) },
        ) { LinkRow(entityRow(it), docRow(it, 4)) }
    }

    override fun commonDocs(a: Long, b: Long): List<DocRow> = synchronized(s.lock) {
        s.query(
            "SELECT m1.docId, d.title, d.kind, d.dateMs, d.folder, COALESCE(f.isPrivate,1), MIN(m1.chunkId), " +
                "COALESCE((SELECT n FROM graph_links WHERE a=? AND b=? AND docId=m1.docId),0) FROM graph_mentions m1 " +
                "JOIN graph_mentions m2 ON m2.docId=m1.docId AND m2.entityId=? " + docJoin.format("m1.docId") +
                " WHERE m1.entityId=? GROUP BY m1.docId LIMIT 500",
            { st -> st.bindLong(1, minOf(a, b)); st.bindLong(2, maxOf(a, b)); st.bindLong(3, b); st.bindLong(4, a) },
        ) { docRow(it, 0) }
    }

    override fun chunkText(chunkId: Long): String? = synchronized(s.lock) {
        s.query("SELECT text FROM chunks WHERE id=?", { it.bindLong(1, chunkId) }) { it.getText(0) }.firstOrNull()
    }

    companion object {
        val DDL = listOf(
            "CREATE TABLE IF NOT EXISTS graph_entities(id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, key TEXT NOT NULL, display TEXT NOT NULL, UNIQUE(type,key))",
            "CREATE TABLE IF NOT EXISTS graph_mentions(entityId INTEGER NOT NULL, chunkId INTEGER NOT NULL, docId TEXT NOT NULL, PRIMARY KEY(entityId,chunkId))",
            "CREATE INDEX IF NOT EXISTS graph_mentions_doc ON graph_mentions(docId)",
            "CREATE TABLE IF NOT EXISTS graph_links(a INTEGER NOT NULL, b INTEGER NOT NULL, docId TEXT NOT NULL, n INTEGER NOT NULL, PRIMARY KEY(a,b,docId))",
            "CREATE INDEX IF NOT EXISTS graph_links_b ON graph_links(b)",
            "CREATE INDEX IF NOT EXISTS graph_links_doc ON graph_links(docId)",
            "CREATE TABLE IF NOT EXISTS graph_docs(docId TEXT PRIMARY KEY)",
        )
    }
}
