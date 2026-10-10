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
    /** Files waiting to be read again after a re-index that did not finish. */
    val pending: Int = 0,
    val chunks: Int = 0,
    val lastRunMs: Long = 0,
)

data class FolderInfo(val folder: String, val isPrivate: Boolean, val docs: Int)

data class DocInfo(val docId: String, val uri: String, val title: String, val kind: String, val isPrivate: Boolean)

/** A file row as the sweep after an index run sees it. */
class StoredDoc(val docId: String, val uri: String, val kind: String, val folder: String)

/**
 * The one SQLite file of this phone's Data Union (bundled androidx.sqlite driver, FTS5 for BM25).
 * Embeddings (256 x float32, unit length) sit in the `emb` BLOB and in an in-memory matrix loaded at open;
 * cosine is brute force over that matrix. All access is serialised on [lock].
 */
class PduStore(path: String) : IndexBacking {
    // internal: the knowledge bank (package kb) keeps its tables in this same database file and takes the same lock
    internal val lock = Any()
    internal val db: SQLiteConnection = BundledSQLiteDriver().open(path)

    private val mx = VectorMatrix(DIM)
    private var txDepth = 0

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
            repairPrivacy()
            loadMatrix()
        }
    }

    /** One SQLite transaction on the shared connection. A call inside another transaction joins it; any throw rolls the whole thing back. */
    internal fun <T> inTransaction(block: () -> T): T = synchronized(lock) {
        if (txDepth > 0) return block()
        exec("BEGIN")
        txDepth++
        try {
            val r = block()
            exec("COMMIT")
            r
        } catch (t: Throwable) {
            runCatching { exec("ROLLBACK") }
            throw t
        } finally {
            txDepth--
        }
    }

    /**
     * Start-up repair of the privacy flag. Peer search reads `isPrivate` from the chunk rows, so a chunk that is public while its
     * folder is private (or lies under a folder named Private) would still be served. The folder row and the chunk rows are now one
     * transaction, but a store written by an older build, or a kill in the middle of that older two-step write, can still hold such
     * chunks: force them private. Returns how many chunks were changed.
     */
    internal fun repairPrivacy(): Int = synchronized(lock) {
        val publicFolders = query("SELECT DISTINCT folder FROM chunks WHERE isPrivate=0", {}) { it.getText(0) }
        if (publicFolders.isEmpty()) return 0
        val stored = query("SELECT folder, isPrivate FROM folders", {}) { it.getText(0) to (it.getLong(1) == 1L) }.toMap()
        val force = SharePolicy.foldersToForce(publicFolders, stored)
        if (force.isEmpty()) return 0
        inTransaction {
            var n = 0
            for (f in force) {
                write("UPDATE chunks SET isPrivate=1 WHERE folder=? AND isPrivate=0") { it.bindText(1, f) }
                n += query("SELECT changes()", {}) { it.getLong(0).toInt() }.first()
            }
            n
        }
    }

    // ---- meta ---------------------------------------------------------------------------------------------

    override fun meta(key: String): String? = synchronized(lock) {
        query("SELECT v FROM meta WHERE k=?", { it.bindText(1, key) }) { it.getText(0) }.firstOrNull()
    }

    override fun setMeta(key: String, value: String) = synchronized(lock) {
        write("INSERT OR REPLACE INTO meta(k,v) VALUES(?,?)") { it.bindText(1, key); it.bindText(2, value) }
    }

    // ---- writes -------------------------------------------------------------------------------------------

    /** True when this exact document version (same uri, same lastModified) is already indexed. A file waiting for a re-read (chunks = -1) is not. */
    fun isIndexed(docId: String, lastModified: Long): Boolean = synchronized(lock) {
        query("SELECT 1 FROM docs WHERE docId=? AND lastModified=? AND chunks>=0", { it.bindText(1, docId); it.bindLong(2, lastModified) }) { 1 }
            .isNotEmpty()
    }

    /**
     * Replaces every chunk of [doc] (a changed file) and records it. [chunks] may be empty for a skipped file. The in-memory matrix is
     * changed in place afterwards: the old rows of this file are squeezed out and the new ones appended, nothing is reloaded.
     * Throws [IndexFull] when the store would hold more than [VectorMatrix.MAX_CHUNKS] chunks.
     */
    fun putDoc(doc: DocRecord, chunks: List<ChunkRow>, nowMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        var removed: List<Long> = emptyList()
        val newIds = ArrayList<Long>(chunks.size)
        inTransaction {
            val old = query("SELECT id FROM chunks WHERE docId=?", { it.bindText(1, doc.docId) }) { it.getLong(0) }
            if (!ChunkCap.fits(mx.rows, old.size, chunks.size)) throw IndexFull()
            removed = deleteChunks(doc.docId, old)
            write("INSERT OR IGNORE INTO folders(folder,isPrivate) VALUES(?,?)") {
                it.bindText(1, doc.folder); it.bindLong(2, if (SharePolicy.startsPrivate(doc.folder)) 1 else 0)
            }
            val stored = query("SELECT isPrivate FROM folders WHERE folder=?", { it.bindText(1, doc.folder) }) { it.getLong(0) }
                .firstOrNull() ?: 1L
            val priv = if (SharePolicy.effectivePrivate(doc.folder, stored == 1L)) 1L else 0L
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
        }
        try {
            mx.removeAll(removed.toHashSet())
            newIds.forEachIndexed { i, id -> mx.append(id, chunks[i].emb) }
        } catch (t: Throwable) {
            loadMatrix() // the database is right, so rebuild the matrix from it
        }
    }

    /** How many chunks this file holds now. */
    fun chunksOf(docId: String): Int = synchronized(lock) {
        query("SELECT COUNT(*) FROM chunks WHERE docId=?", { it.bindText(1, docId) }) { it.getLong(0).toInt() }.first()
    }

    /** The stored folder of this exact version of a file (same uri, same lastModified), or null when it is not indexed. */
    fun indexedFolder(docId: String, lastModified: Long): String? = synchronized(lock) {
        query("SELECT folder FROM docs WHERE docId=? AND lastModified=? AND chunks>=0", { it.bindText(1, docId); it.bindLong(2, lastModified) }) { it.getText(0) }
            .firstOrNull()
    }

    /**
     * The file is still the same version but now sits in another folder (a photo moved in the gallery): its chunks follow it, and
     * their privacy is decided again for the new folder, so a file that moved into a private folder stops being shared at once.
     */
    fun moveDoc(docId: String, folder: String) = inTransaction {
        write("INSERT OR IGNORE INTO folders(folder,isPrivate) VALUES(?,?)") {
            it.bindText(1, folder); it.bindLong(2, if (SharePolicy.startsPrivate(folder)) 1 else 0)
        }
        val stored = query("SELECT isPrivate FROM folders WHERE folder=?", { it.bindText(1, folder) }) { it.getLong(0) == 1L }.firstOrNull()
        val priv = if (SharePolicy.privacyAfterMove(folder, stored)) 1L else 0L
        write("UPDATE docs SET folder=? WHERE docId=?") { it.bindText(1, folder); it.bindText(2, docId) }
        write("UPDATE chunks SET folder=?, isPrivate=? WHERE docId=?") { it.bindText(1, folder); it.bindLong(2, priv); it.bindText(3, docId) }
    }

    /** Forget these files: their chunks, their text index entries and their rows. Returns how many documents were named. */
    fun removeDocs(docIds: Collection<String>): Int = synchronized(lock) {
        if (docIds.isEmpty()) return 0
        val gone = HashSet<Long>()
        inTransaction {
            for (d in docIds) {
                val ids = query("SELECT id FROM chunks WHERE docId=?", { it.bindText(1, d) }) { it.getLong(0) }
                deleteChunks(d, ids)
                gone += ids
                write("DELETE FROM docs WHERE docId=?") { it.bindText(1, d) }
            }
        }
        try { mx.removeAll(gone) } catch (t: Throwable) { loadMatrix() }
        docIds.size
    }

    /** A file that waited to be read again (chunks = -1) and failed: it is a skipped file now, so "Continue re-index" can finish. */
    fun markFailed(docId: String) = synchronized(lock) {
        write("UPDATE docs SET chunks=0 WHERE docId=? AND chunks<0") { it.bindText(1, docId) }
    }

    /** The files of one index root that this phone holds; what is not in a finished run's listing is gone from the phone. */
    fun docsUnder(root: IndexRoot): List<StoredDoc> = synchronized(lock) {
        val sql = StringBuilder("SELECT docId, uri, kind, folder FROM docs WHERE substr(uri,1,?)=?")
        root.kinds?.let { sql.append(" AND kind IN (").append(it.joinToString(",") { "?" }).append(")") }
        root.folderPrefix?.let { sql.append(" AND substr(folder,1,?)=?") }
        query(sql.toString(), { st ->
            var i = 1
            st.bindLong(i++, root.uriPrefix.codePointCount(0, root.uriPrefix.length).toLong()); st.bindText(i++, root.uriPrefix)
            root.kinds?.forEach { st.bindText(i++, it) }
            root.folderPrefix?.let { st.bindLong(i++, it.codePointCount(0, it.length).toLong()); st.bindText(i++, it) }
        }) { StoredDoc(it.getText(0), it.getText(1), it.getText(2), it.getText(3)) }
    }

    /**
     * Owner's per-folder switch. Applies to chunks already indexed and to files indexed later. The folder row and the chunk rows
     * change in one transaction: peer search reads the flag from the chunks, so a kill between two separate writes would leave a
     * folder that looks private to its owner while its snippets are still served.
     */
    fun setFolderPrivate(folder: String, isPrivate: Boolean) = inTransaction {
        val v = if (SharePolicy.effectivePrivate(folder, isPrivate)) 1L else 0L
        write("INSERT OR REPLACE INTO folders(folder,isPrivate) VALUES(?,?)") { it.bindText(1, folder); it.bindLong(2, v) }
        write("UPDATE chunks SET isPrivate=? WHERE folder=?") { it.bindLong(1, v); it.bindText(2, folder) }
    }

    /**
     * Drop every chunk (used when the embedder changes). The documents stay in the list with chunks = -1, "read me again",
     * so a re-index that is killed half way still knows what is left to do. Files that had no text (chunks = 0) stay as they are.
     */
    override fun wipe() = synchronized(lock) {
        exec("BEGIN")
        try {
            exec("DELETE FROM chunks"); exec("DELETE FROM chunks_fts"); exec("UPDATE docs SET chunks=-1 WHERE chunks>0")
            exec("COMMIT")
        } catch (t: Throwable) {
            runCatching { exec("ROLLBACK") }
            throw t
        }
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
            pending = n("SELECT COUNT(*) FROM docs WHERE chunks<0"),
            chunks = mx.rows,
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
            "SELECT d.docId, d.uri, d.title, d.kind, COALESCE(f.isPrivate,1), d.folder FROM docs d LEFT JOIN folders f ON f.folder=d.folder WHERE d.docId=?",
            { it.bindText(1, docId) },
        ) { DocInfo(it.getText(0), it.getText(1), it.getText(2), it.getText(3), SharePolicy.effectivePrivate(it.getText(5), it.getLong(4) == 1L)) }.firstOrNull()
    }

    /**
     * True when any indexed file with this title is private (its folder is not shared, or it is a Private folder). A title is not
     * unique, so one private match is enough; the knowledge bank uses this to decide whether a source may leave the phone.
     */
    fun anyPrivateTitled(title: String): Boolean = synchronized(lock) {
        query(
            "SELECT d.folder, COALESCE(f.isPrivate,1) FROM docs d LEFT JOIN folders f ON f.folder=d.folder WHERE d.title=?",
            { it.bindText(1, title) },
        ) { SharePolicy.effectivePrivate(it.getText(0), it.getLong(1) == 1L) }.any { it }
    }

    /** Every file the index knows about, so an explicit re-index can read them all again after the wipe. */
    fun allDocRefs(): List<DocRef> = synchronized(lock) {
        query("SELECT uri, title, kind, folder, lastModified, dateMs FROM docs ORDER BY title", {}) {
            DocRef(it.getText(0), it.getText(1), it.getText(2), it.getText(3), it.getLong(4), it.getLong(5))
        }
    }

    override val chunkCount: Int get() = synchronized(lock) { mx.rows }

    override val pendingCount: Int get() = synchronized(lock) {
        query("SELECT COUNT(*) FROM docs WHERE chunks<0", {}) { it.getLong(0).toInt() }.first()
    }

    /**
     * Hybrid search over every chunk of this phone, private ones included (the caller applies [SharePolicy]).
     * Dense cosine top-[topN] and FTS5 BM25 top-[topN] fused with RRF, one chunk kept per document.
     */
    fun search(queryEmb: FloatArray?, terms: List<String>, topN: Int = 30): List<Candidate> = synchronized(lock) {
        if (mx.rows == 0) return emptyList()
        // queryEmb == null: keyword search only (no usable embedder, or the vectors were made by another one)
        val dense = if (queryEmb == null) emptyList() else Cosine.topK(mx.data, DIM, mx.rows, queryEmb, topN).map { mx.ids[it.row] to it.score }
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
        if (mx.rows == 0) return null to emptyList()
        val all = FloatArray(mx.rows) { r ->
            var dot = 0f
            val base = r * DIM
            for (d in 0 until DIM) dot += mx.data[base + d] * queryEmb[d]
            dot
        }
        val sorted = all.sortedArray()
        val spread = CosineSpread(
            sorted.first().toDouble(), sorted[sorted.size / 2].toDouble(), sorted[(sorted.size * 9) / 10].toDouble(), sorted.last().toDouble(),
        )
        val denseTop = Cosine.topK(mx.data, DIM, mx.rows, queryEmb, topN)
        val denseRank = denseTop.mapIndexed { i, sc -> mx.ids[sc.row] to i + 1 }.toMap()
        val match = QueryTerms.ftsMatch(terms)
        val bm25 = if (match == null) emptyList() else query(
            "SELECT rowid, bm25(chunks_fts, 1.0, 2.0) FROM chunks_fts WHERE chunks_fts MATCH ? ORDER BY bm25(chunks_fts, 1.0, 2.0) LIMIT ?",
            { it.bindText(1, match); it.bindLong(2, topN.toLong()) },
        ) { it.getLong(0) to it.getDouble(1) }
        val bm25Rank = bm25.mapIndexed { i, (id, _) -> id to i + 1 }.toMap()
        val bm25Raw = bm25.toMap()
        val fusedScore = HybridRanker.fuse(denseTop.map { mx.ids[it.row] to it.score }, bm25.map { it.first }).associate { it.chunkId to it.score }
        val union = (denseRank.keys + bm25Rank.keys).toList()
        if (union.isEmpty()) return spread to emptyList()
        val rowOf = HashMap<Long, Int>().also { m -> for (r in 0 until mx.rows) m[mx.ids[r]] = r }
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

    /** Deletes the chunks [ids] of [docId] (and their text index entries). Returns [ids]. */
    private fun deleteChunks(docId: String, ids: List<Long>): List<Long> {
        if (ids.isEmpty()) return ids
        for (id in ids) write("DELETE FROM chunks_fts WHERE rowid=?") { it.bindLong(1, id) }
        write("DELETE FROM chunks WHERE docId=?") { it.bindText(1, docId) }
        return ids
    }

    /** Reads every vector once, straight into one array sized from the row count: no list of per-row arrays, no second copy. */
    private fun loadMatrix() {
        val n = query("SELECT COUNT(*) FROM chunks", {}) { it.getLong(0).toInt() }.first()
        mx.reset(n + n / 8 + 256) // a little headroom, so the first files added afterwards need no copy
        val st = db.prepare("SELECT id, emb FROM chunks ORDER BY id")
        try {
            while (st.step()) mx.appendBlob(st.getLong(0), st.getBlob(1))
        } finally { st.close() }
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
