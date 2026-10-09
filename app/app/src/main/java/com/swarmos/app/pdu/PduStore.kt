package com.swarmos.app.pdu

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** One indexed file (or scan). `chunks == 0` means "looked at, nothing worth indexing". */
data class DocRecord(
    val docId: String,
    val uri: String,
    val title: String,
    val kind: String,
    val folder: String,
    val lastModified: Long,
    val dateMs: Long,
)

data class ChunkRow(val text: String, val emb: FloatArray)

data class StoreCounts(
    val photos: Int = 0,
    val documents: Int = 0,
    val scans: Int = 0,
    val skipped: Int = 0,
    val chunks: Int = 0,
    val lastRunMs: Long = 0,
)

data class FolderInfo(val folder: String, val isPrivate: Boolean, val docs: Int)

data class DocInfo(val docId: String, val uri: String, val title: String, val kind: String, val isPrivate: Boolean)

/**
 * The one SQLite file of this phone's Data Union (bundled androidx.sqlite driver, FTS5 for BM25).
 * Embeddings (256 x float32, unit length) sit in the `emb` BLOB and in an in-memory matrix loaded at open;
 * cosine is brute force over that matrix. All access is serialised on [lock].
 */
class PduStore(path: String) {
    private val lock = Any()
    private val db: SQLiteConnection = BundledSQLiteDriver().open(path)

    private var ids = LongArray(0)
    private var matrix = FloatArray(0)
    private var rows = 0

    init {
        synchronized(lock) {
            exec("PRAGMA journal_mode=WAL")
            exec("PRAGMA synchronous=NORMAL")
            exec(
                """CREATE TABLE IF NOT EXISTS chunks(
                    id INTEGER PRIMARY KEY AUTOINCREMENT, docId TEXT NOT NULL, uri TEXT NOT NULL, title TEXT NOT NULL,
                    kind TEXT NOT NULL, dateMs INTEGER NOT NULL, folder TEXT NOT NULL, isPrivate INTEGER NOT NULL,
                    text TEXT NOT NULL, emb BLOB NOT NULL)""",
            )
            exec("CREATE INDEX IF NOT EXISTS chunks_doc ON chunks(docId)")
            exec("CREATE INDEX IF NOT EXISTS chunks_folder ON chunks(folder)")
            exec(
                "CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts5(text, title, tokenize='porter unicode61 remove_diacritics 2')",
            )
            exec(
                """CREATE TABLE IF NOT EXISTS docs(
                    docId TEXT PRIMARY KEY, uri TEXT NOT NULL, title TEXT NOT NULL, kind TEXT NOT NULL, folder TEXT NOT NULL,
                    lastModified INTEGER NOT NULL, dateMs INTEGER NOT NULL, chunks INTEGER NOT NULL, indexedAt INTEGER NOT NULL)""",
            )
            exec("CREATE TABLE IF NOT EXISTS folders(folder TEXT PRIMARY KEY, isPrivate INTEGER NOT NULL)")
            exec("CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v TEXT NOT NULL)")
            loadMatrix()
        }
    }

    // ---- meta ---------------------------------------------------------------------------------------------

    fun meta(key: String): String? = synchronized(lock) {
        query("SELECT v FROM meta WHERE k=?", { it.bindText(1, key) }) { it.getText(0) }.firstOrNull()
    }

    fun setMeta(key: String, value: String) = synchronized(lock) {
        write("INSERT OR REPLACE INTO meta(k,v) VALUES(?,?)") { it.bindText(1, key); it.bindText(2, value) }
    }

    // ---- writes -------------------------------------------------------------------------------------------

    /** True when this exact document version (same uri, same lastModified) is already indexed. */
    fun isIndexed(docId: String, lastModified: Long): Boolean = synchronized(lock) {
        query("SELECT 1 FROM docs WHERE docId=? AND lastModified=?", { it.bindText(1, docId); it.bindLong(2, lastModified) }) { 1 }
            .isNotEmpty()
    }

    /** Replaces every chunk of [doc] (a changed file) and records it. [chunks] may be empty for a skipped file. */
    fun putDoc(doc: DocRecord, chunks: List<ChunkRow>, nowMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        exec("BEGIN")
        try {
            val replaced = deleteChunks(doc.docId)
            write("INSERT OR IGNORE INTO folders(folder,isPrivate) VALUES(?,?)") {
                it.bindText(1, doc.folder); it.bindLong(2, if (SharePolicy.isPrivateByDefault(doc.folder)) 1 else 0)
            }
            val priv = query("SELECT isPrivate FROM folders WHERE folder=?", { it.bindText(1, doc.folder) }) { it.getLong(0) }
                .firstOrNull() ?: 0L
            val newIds = ArrayList<Long>(chunks.size)
            for (c in chunks) {
                write("INSERT INTO chunks(docId,uri,title,kind,dateMs,folder,isPrivate,text,emb) VALUES(?,?,?,?,?,?,?,?,?)") {
                    it.bindText(1, doc.docId); it.bindText(2, doc.uri); it.bindText(3, doc.title); it.bindText(4, doc.kind)
                    it.bindLong(5, doc.dateMs); it.bindText(6, doc.folder); it.bindLong(7, priv)
                    it.bindText(8, c.text); it.bindBlob(9, toBytes(c.emb))
                }
                val id = query("SELECT last_insert_rowid()", {}) { it.getLong(0) }.first()
                write("INSERT INTO chunks_fts(rowid,text,title) VALUES(?,?,?)") {
                    it.bindLong(1, id); it.bindText(2, c.text); it.bindText(3, doc.title)
                }
                newIds += id
            }
            write("INSERT OR REPLACE INTO docs(docId,uri,title,kind,folder,lastModified,dateMs,chunks,indexedAt) VALUES(?,?,?,?,?,?,?,?,?)") {
                it.bindText(1, doc.docId); it.bindText(2, doc.uri); it.bindText(3, doc.title); it.bindText(4, doc.kind)
                it.bindText(5, doc.folder); it.bindLong(6, doc.lastModified); it.bindLong(7, doc.dateMs)
                it.bindLong(8, chunks.size.toLong()); it.bindLong(9, nowMs)
            }
            exec("COMMIT")
            if (replaced) loadMatrix() else appendToMatrix(newIds, chunks)
        } catch (t: Throwable) {
            runCatching { exec("ROLLBACK") }
            throw t
        }
    }

    /** Owner's per-folder switch. Applies to chunks already indexed and to files indexed later. */
    fun setFolderPrivate(folder: String, isPrivate: Boolean) = synchronized(lock) {
        val v = if (isPrivate) 1L else 0L
        write("INSERT OR REPLACE INTO folders(folder,isPrivate) VALUES(?,?)") { it.bindText(1, folder); it.bindLong(2, v) }
        write("UPDATE chunks SET isPrivate=? WHERE folder=?") { it.bindLong(1, v); it.bindText(2, folder) }
    }

    /** Forget every document so the next run re-reads everything (used when the embedder changes). */
    fun wipe() = synchronized(lock) {
        exec("DELETE FROM chunks"); exec("DELETE FROM chunks_fts"); exec("DELETE FROM docs")
        loadMatrix()
    }

    // ---- reads --------------------------------------------------------------------------------------------

    fun counts(): StoreCounts = synchronized(lock) {
        fun n(sql: String) = query(sql, {}) { it.getLong(0).toInt() }.first()
        StoreCounts(
            photos = n("SELECT COUNT(*) FROM docs WHERE kind='photo' AND chunks>0"),
            documents = n("SELECT COUNT(*) FROM docs WHERE kind IN ('pdf','docx','text') AND chunks>0"),
            scans = n("SELECT COUNT(*) FROM docs WHERE kind='scan' AND chunks>0"),
            skipped = n("SELECT COUNT(*) FROM docs WHERE chunks=0"),
            chunks = rows,
            lastRunMs = meta("lastRunMs")?.toLongOrNull() ?: 0,
        )
    }

    fun folders(): List<FolderInfo> = synchronized(lock) {
        query(
            "SELECT f.folder, f.isPrivate, (SELECT COUNT(*) FROM docs d WHERE d.folder=f.folder AND d.chunks>0) FROM folders f ORDER BY f.folder",
            {},
        ) { FolderInfo(it.getText(0), it.getLong(1) == 1L, it.getLong(2).toInt()) }.filter { it.docs > 0 }
    }

    fun doc(docId: String): DocInfo? = synchronized(lock) {
        query(
            "SELECT d.docId, d.uri, d.title, d.kind, COALESCE(f.isPrivate,0) FROM docs d LEFT JOIN folders f ON f.folder=d.folder WHERE d.docId=?",
            { it.bindText(1, docId) },
        ) { DocInfo(it.getText(0), it.getText(1), it.getText(2), it.getText(3), it.getLong(4) == 1L) }.firstOrNull()
    }

    val chunkCount: Int get() = synchronized(lock) { rows }

    /**
     * Hybrid search over every chunk of this phone, private ones included (the caller applies [SharePolicy]).
     * Dense cosine top-[topN] and FTS5 BM25 top-[topN] fused with RRF, one chunk kept per document.
     */
    fun search(queryEmb: FloatArray, terms: List<String>, topN: Int = 30): List<Candidate> = synchronized(lock) {
        if (rows == 0) return emptyList()
        val dense = Cosine.topK(matrix, DIM, rows, queryEmb, topN).map { ids[it.row] to it.score }
        val match = QueryTerms.ftsMatch(terms)
        val bm25 = if (match == null) emptyList() else query(
            "SELECT rowid FROM chunks_fts WHERE chunks_fts MATCH ? ORDER BY bm25(chunks_fts, 1.0, 2.0) LIMIT ?",
            { it.bindText(1, match); it.bindLong(2, topN.toLong()) },
        ) { it.getLong(0) }
        val fused = HybridRanker.fuse(dense, bm25)
        if (fused.isEmpty()) return emptyList()

        val marks = fused.joinToString(",") { "?" }
        val byId = query(
            "SELECT id, docId, title, kind, dateMs, folder, isPrivate, text FROM chunks WHERE id IN ($marks)",
            { st -> fused.forEachIndexed { i, f -> st.bindLong(i + 1, f.chunkId) } },
        ) { Row(it.getLong(0), it.getText(1), it.getText(2), it.getText(3), it.getLong(4), it.getText(5), it.getLong(6) == 1L, it.getText(7)) }
            .associateBy { it.id }
        val cands = fused.mapNotNull { f ->
            val r = byId[f.chunkId] ?: return@mapNotNull null
            val coverage = Coverage.of(terms, r.title + " " + r.text)
            if (!HybridRanker.passes(f.cosine, coverage)) return@mapNotNull null
            // RRF alone says "first on this phone"; mixing in query-word coverage makes scores comparable between phones
            val score = if (terms.isEmpty()) f.score else 0.5 * f.score + 0.5 * coverage
            Candidate(r.id, r.docId, r.title, r.kind, r.dateMs, r.folder, r.priv, r.text, score)
        }
        HybridRanker.collapseByDoc(cands)
    }


    /**
     * Calibration view of one question (debug route only): for the strongest chunks by dense cosine and by BM25,
     * the raw cosine, the raw FTS5 bm25() value (negative, more negative is better), ranks, query-word coverage,
     * the normalised RRF score and whether the chunk would be returned. Also the cosine spread over every chunk,
     * which shows where unrelated text sits for the current embedder. Titles of private chunks are not named.
     */
    fun explain(queryEmb: FloatArray, terms: List<String>, topN: Int = 10): Pair<CosineSpread?, List<ScoreRow>> = synchronized(lock) {
        if (rows == 0) return null to emptyList()
        val all = FloatArray(rows) { r ->
            var dot = 0f
            val base = r * DIM
            for (d in 0 until DIM) dot += matrix[base + d] * queryEmb[d]
            dot
        }
        val sorted = all.sortedArray()
        val spread = CosineSpread(
            sorted.first().toDouble(), sorted[sorted.size / 2].toDouble(), sorted[(sorted.size * 9) / 10].toDouble(), sorted.last().toDouble(),
        )
        val denseTop = Cosine.topK(matrix, DIM, rows, queryEmb, topN)
        val denseRank = denseTop.mapIndexed { i, sc -> ids[sc.row] to i + 1 }.toMap()
        val match = QueryTerms.ftsMatch(terms)
        val bm25 = if (match == null) emptyList() else query(
            "SELECT rowid, bm25(chunks_fts, 1.0, 2.0) FROM chunks_fts WHERE chunks_fts MATCH ? ORDER BY bm25(chunks_fts, 1.0, 2.0) LIMIT ?",
            { it.bindText(1, match); it.bindLong(2, topN.toLong()) },
        ) { it.getLong(0) to it.getDouble(1) }
        val bm25Rank = bm25.mapIndexed { i, (id, _) -> id to i + 1 }.toMap()
        val bm25Raw = bm25.toMap()
        val fusedScore = HybridRanker.fuse(denseTop.map { ids[it.row] to it.score }, bm25.map { it.first }).associate { it.chunkId to it.score }
        val union = (denseRank.keys + bm25Rank.keys).toList()
        if (union.isEmpty()) return spread to emptyList()
        val rowOf = HashMap<Long, Int>().also { m -> for (r in 0 until rows) m[ids[r]] = r }
        val info = query(
            "SELECT id, title, kind, isPrivate, text FROM chunks WHERE id IN (${union.joinToString(",") { "?" }})",
            { st -> union.forEachIndexed { i, id -> st.bindLong(i + 1, id) } },
        ) { listOf(it.getLong(0), it.getText(1), it.getText(2), it.getLong(3), it.getText(4)) }.associateBy { it[0] as Long }
        val out = union.mapNotNull { id ->
            val r = info[id] ?: return@mapNotNull null
            val priv = (r[3] as Long) == 1L
            val cos = all[rowOf[id]!!]
            val cov = Coverage.of(terms, (r[1] as String) + " " + (r[4] as String))
            ScoreRow(
                if (priv) "(private file)" else r[1] as String, r[2] as String, priv, cos.toDouble(), denseRank[id], bm25Raw[id], bm25Rank[id],
                cov, fusedScore[id] ?: 0.0, id in fusedScore && HybridRanker.passes(cos, cov),
            )
        }.sortedWith(compareByDescending<ScoreRow> { it.passes }.thenByDescending { it.rrf }.thenByDescending { it.cosine })
        spread to out
    }

    // ---- internals ----------------------------------------------------------------------------------------

    private class Row(val id: Long, val docId: String, val title: String, val kind: String, val dateMs: Long, val folder: String, val priv: Boolean, val text: String)

    private fun deleteChunks(docId: String): Boolean {
        val old = query("SELECT id FROM chunks WHERE docId=?", { it.bindText(1, docId) }) { it.getLong(0) }
        if (old.isEmpty()) return false
        for (id in old) write("DELETE FROM chunks_fts WHERE rowid=?") { it.bindLong(1, id) }
        write("DELETE FROM chunks WHERE docId=?") { it.bindText(1, docId) }
        return true
    }

    private fun loadMatrix() {
        val list = ArrayList<Long>()
        val buf = ArrayList<FloatArray>()
        val st = db.prepare("SELECT id, emb FROM chunks ORDER BY id")
        try {
            while (st.step()) { list += st.getLong(0); buf += fromBytes(st.getBlob(1)) }
        } finally { st.close() }
        ids = list.toLongArray()
        rows = ids.size
        matrix = FloatArray(rows * DIM)
        buf.forEachIndexed { r, v -> System.arraycopy(v, 0, matrix, r * DIM, DIM) }
    }

    private fun appendToMatrix(newIds: List<Long>, chunks: List<ChunkRow>) {
        if (newIds.isEmpty()) return
        val need = (rows + newIds.size)
        if (need * DIM > matrix.size) matrix = matrix.copyOf(maxOf(need * DIM, matrix.size * 2))
        if (need > ids.size) ids = ids.copyOf(maxOf(need, ids.size * 2))
        newIds.forEachIndexed { i, id ->
            ids[rows + i] = id
            System.arraycopy(chunks[i].emb, 0, matrix, (rows + i) * DIM, DIM)
        }
        rows = need
    }

    private fun exec(sql: String) {
        val st = db.prepare(sql)
        try { st.step() } finally { st.close() }
    }

    private inline fun write(sql: String, bind: (SQLiteStatement) -> Unit) {
        val st = db.prepare(sql)
        try { bind(st); st.step() } finally { st.close() }
    }

    private inline fun <T> query(sql: String, bind: (SQLiteStatement) -> Unit, read: (SQLiteStatement) -> T): List<T> {
        val st = db.prepare(sql)
        try {
            bind(st)
            val out = ArrayList<T>()
            while (st.step()) out += read(st)
            return out
        } finally { st.close() }
    }

    companion object {
        const val DIM = 256

        fun docIdOf(uri: String): String =
            MessageDigest.getInstance("SHA-256").digest(uri.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

        fun toBytes(v: FloatArray): ByteArray {
            val b = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (f in v) b.putFloat(f)
            return b.array()
        }

        fun fromBytes(b: ByteArray): FloatArray {
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(b.size / 4) { bb.getFloat() }
        }

        /** The phone's embedder should give 256 unit-length dims; if it gives more, keep the first 256 and renormalise (MRL). */
        fun fit(v: FloatArray): FloatArray {
            if (v.size == DIM) return v
            val out = v.copyOf(DIM)
            var n = 0.0
            for (x in out) n += x * x
            val norm = Math.sqrt(n).toFloat()
            if (norm > 0f) for (i in out.indices) out[i] /= norm
            return out
        }
    }
}
