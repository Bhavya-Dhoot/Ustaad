package com.swarmos.app.pdu

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlinx.serialization.Serializable
import java.io.File

/** A file the indexer was pointed at. `uri` is the identity (docId = hash of it); `lastModified` decides whether it is re-read. */
class DocRef(
    val uri: String,
    val title: String,
    val kind: String,
    val folder: String,
    val lastModified: Long,
    val dateMs: Long,
)

@Serializable
data class IndexReport(
    val label: String,
    val seen: Int = 0,
    val indexed: Int = 0,
    val unchanged: Int = 0,
    val noText: Int = 0,
    val failed: Int = 0,
    val chunks: Int = 0,
    val ocrCount: Int = 0,
    val ocrMsAvg: Long = 0,
    val ocrMs: List<Long> = emptyList(),
    val embedMs: Long = 0,
    val totalMs: Long = 0,
    val files: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val error: String? = null,
    val devanagariRuns: Int = 0,
    /** Files that sat in another folder than the one stored and were moved (their privacy was decided again for the new folder). */
    val moved: Int = 0,
    /** Files removed from the index: gone from the phone, or not in the finished run's listing. */
    val removed: Int = 0,
)

/** Mutable counters for one run. */
class IndexStats(val label: String) {
    var seen = 0; var indexed = 0; var unchanged = 0; var noText = 0; var failed = 0; var chunks = 0; var devanagariRuns = 0
    var moved = 0; var removed = 0
    var embedMs = 0L; var totalMs = 0L
    val ocrMs = ArrayList<Long>()
    val files = ArrayList<String>()
    val errors = ArrayList<String>()

    fun report() = IndexReport(
        label, seen, indexed, unchanged, noText, failed, chunks, ocrMs.size,
        if (ocrMs.isEmpty()) 0 else ocrMs.sum() / ocrMs.size, ocrMs.toList(), embedMs, totalMs, files.toList(), errors.toList(),
        devanagariRuns = devanagariRuns, moved = moved, removed = removed,
    )
}

/** Listing and reading of the three on-device sources. Nothing here touches the network. */
object PduIndexer {
    const val MIN_OCR_CHARS = 20

    fun kindOf(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "txt", "md" -> "text"
        "pdf" -> "pdf"
        "docx" -> "docx"
        "jpg", "jpeg", "png", "webp" -> "photo"
        else -> null
    }

    // ---- listing ------------------------------------------------------------------------------------------

    /** MediaStore images, optionally only those whose RELATIVE_PATH starts with [relPrefix] (e.g. "Pictures/UstaadDemo"). */
    fun listPhotos(ctx: Context, relPrefix: String?, limit: Int?): List<DocRef> {
        val proj = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.RELATIVE_PATH,
        )
        val sel = relPrefix?.let { "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?" }
        val args = relPrefix?.let { arrayOf(it.trimEnd('/') + "%") }
        val out = ArrayList<DocRef>()
        ctx.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, sel, args, "${MediaStore.Images.Media.DATE_MODIFIED} DESC",
        )?.use { c ->
            while (c.moveToNext() && (limit == null || out.size < limit)) {
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)).toString()
                val modified = c.getLong(3) * 1000
                val taken = c.getLong(2)
                out += DocRef(
                    uri, c.getString(1) ?: "image-${c.getLong(0)}", "photo",
                    (c.getString(4) ?: "Pictures/").trim('/'), modified, if (taken > 0) taken else modified,
                )
            }
        }
        return out
    }

    /** Every supported file under a SAF tree. `folder` is the root's display name plus the sub-folder path. */
    fun listTree(ctx: Context, tree: Uri): List<DocRef> {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val rootName = displayName(ctx, DocumentsContract.buildDocumentUriUsingTree(tree, rootId)) ?: rootId.substringAfterLast('/')
        val out = ArrayList<DocRef>()
        fun walk(parentId: String, path: String) {
            class Kid(val id: String, val name: String, val mime: String, val modified: Long)
            val kids = ArrayList<Kid>()
            // a folder that cannot be listed fails the run: a partial listing would make the sweep after it remove what it did not see
            (ctx.contentResolver.query(
                DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId),
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null, null, null,
            ) ?: throw java.io.IOException("cannot list $path")).use { c ->
                while (c.moveToNext()) kids += Kid(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getLong(3))
            }
            for (k in kids) {
                if (k.mime == DocumentsContract.Document.MIME_TYPE_DIR) { walk(k.id, "$path/${k.name}"); continue }
                val kind = kindOf(k.name) ?: continue
                out += DocRef(
                    DocumentsContract.buildDocumentUriUsingTree(tree, k.id).toString(), k.name, kind, path, k.modified, k.modified,
                )
            }
        }
        walk(rootId, rootName)
        return out
    }

    /** All-files-access path (`appops ... MANAGE_EXTERNAL_STORAGE allow`): walk a plain directory. */
    fun listDir(dir: File): List<DocRef> {
        // /sdcard is a symlink; canonical paths keep one identity (and one folder name) per file
        val root = Environment.getExternalStorageDirectory().canonicalPath
        return dir.canonicalFile.walkTopDown().filter { it.isFile }.mapNotNull { f ->
            val kind = kindOf(f.name) ?: return@mapNotNull null
            DocRef(
                Uri.fromFile(f).toString(), f.name, kind,
                f.parentFile!!.path.removePrefix(root).trim('/'), f.lastModified(), f.lastModified(),
            )
        }.toList()
    }

    private fun displayName(ctx: Context, docUri: Uri): String? =
        ctx.contentResolver.query(docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }

    /** Saves a captured page into Pictures/UstaadScans (Shared/ when the owner shares it) so any viewer can open it. Returns uri and DATE_MODIFIED in ms. */
    fun saveScan(ctx: Context, jpeg: File, share: Boolean): Pair<String, Long> {
        val resolver = ctx.contentResolver
        val values = android.content.ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "scan-${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, scanFolder(share))
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore insert failed")
        resolver.openOutputStream(uri)!!.use { out -> jpeg.inputStream().use { it.copyTo(out) } }
        resolver.update(uri, android.content.ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        val modified = resolver.query(uri, arrayOf(MediaStore.Images.Media.DATE_MODIFIED), null, null, null)
            ?.use { if (it.moveToFirst()) it.getLong(0) * 1000 else null } ?: System.currentTimeMillis()
        return uri.toString() to modified
    }

    /** New scans stay on this phone ("Pictures/UstaadScans"); only an explicit share puts them in the shared folder. */
    fun scanFolder(share: Boolean) = if (share) "Pictures/UstaadScans/Shared" else "Pictures/UstaadScans"

    // ---- ingest -------------------------------------------------------------------------------------------

    /** What the first step of a file produced; see [prepare] and [commit]. */
    sealed interface Prepared

    /** This exact version is already stored. [moveTo] is the new folder when the file now sits in another one. */
    class Unchanged(val docId: String, val moveTo: String?) : Prepared

    /** The file was read and cut into chunks; nothing is embedded or stored yet. */
    class Ready(
        val docId: String,
        val doc: DocRecord,
        val bodies: List<String>,
        val textChars: Int,
        val ocrMs: List<Long>,
        val devanagari: Boolean,
    ) : Prepared

    /**
     * Step one of a file: skip it when this uri + lastModified is already indexed (not on an owner-forced re-index, which reads
     * everything again), otherwise read it, OCR it if needed and cut it into chunks. Touches neither the embedder nor the store's
     * rows, so it can run while the file before it is being embedded. Throws when the file cannot be read.
     */
    suspend fun prepare(ctx: Context, store: PduStore, ref: DocRef, rereadAll: Boolean): Prepared {
        val docId = PduStore.docIdOf(ref.uri)
        if (!rereadAll) store.indexedFolder(docId, ref.lastModified)?.let { stored ->
            return Unchanged(docId, ref.folder.takeIf { it != stored })
        }
        val ocr = ArrayList<Long>()
        var devanagari = false
        val text = when (ref.kind) {
            "text" -> Extractors.plainText(ctx, ref.uri)
            "docx" -> Extractors.docx(ctx, ref.uri)
            "pdf" -> Extractors.pdf(ctx, ref.uri) { ms -> ocr += ms }.first
            else -> { // photo
                val bmp = Ocr.decode(ctx, ref.uri)
                try {
                    val t0 = System.nanoTime()
                    val r = Ocr.recognizeAll(bmp)
                    if (r.devanagari != null) devanagari = true
                    ocr += (System.nanoTime() - t0) / 1_000_000
                    r.text
                } finally { bmp.recycle() }
            }
        }
        val isImage = ref.kind == "photo" || ref.kind == "scan"
        val bodies = if (isImage && text.trim().length < MIN_OCR_CHARS) emptyList() else Chunker.split(text)
        val doc = DocRecord(docId, ref.uri, ref.title, ref.kind, ref.folder, ref.lastModified, ref.dateMs)
        return Ready(docId, doc, bodies, text.trim().length, ocr, devanagari)
    }

    /**
     * Step two: embed and store, in the order of the listing. Throws [EmbedderStopped] when the embedder went away and [IndexFull]
     * at the chunk cap; in both cases nothing of this file is stored.
     */
    suspend fun commit(store: PduStore, ref: DocRef, p: Prepared, stats: IndexStats, run: IndexRun) {
        when (p) {
            is Unchanged -> {
                stats.unchanged++
                if (p.moveTo != null) { store.moveDoc(p.docId, p.moveTo); stats.moved++ }
            }
            is Ready -> {
                // checked before the embedding, so a full index does not spend minutes on a file it cannot take
                val current = if (run.wipePending) 0 else store.chunkCount
                if (!ChunkCap.fits(current, store.chunksOf(p.docId), p.bodies.size)) throw IndexFull()
                val rows = embed(run, p.bodies.map { PduEmbedConfig.document(ref.title, it) }, stats).mapIndexed { i, e -> ChunkRow(p.bodies[i], e) }
                run.beforeStore(embedded = rows.isNotEmpty())
                store.putDoc(p.doc, rows)
                stats.ocrMs += p.ocrMs
                if (p.devanagari) stats.devanagariRuns++
                if (rows.isEmpty()) stats.noText++ else { stats.indexed++; stats.chunks += rows.size }
                val shown = if (store.doc(p.docId)?.isPrivate == true) "(private file)" else ref.title
                stats.files += "$shown ${ref.kind} chars=${p.textChars} chunks=${rows.size}" +
                    (if (p.ocrMs.isNotEmpty()) " ocrMs=${p.ocrMs.last()}" else "")
            }
        }
    }

    /**
     * A file that could not be read or timed out: counted, named (a file in a Private folder only as "(private file)"), and its row
     * dealt with. A file that can no longer be opened is deleted from the index; one that waited for a re-read becomes a skipped
     * file, so "Continue re-index" can finish.
     */
    fun recordFailure(store: PduStore, ref: DocRef, t: Throwable, stats: IndexStats) {
        stats.failed++
        val shown = if (SharePolicy.mustStayPrivate(ref.folder)) "(private file)" else ref.title
        stats.errors += "$shown: ${t.javaClass.simpleName} ${t.message}".take(200)
        runCatching {
            val docId = PduStore.docIdOf(ref.uri)
            if (isGone(t)) { store.removeDocs(listOf(docId)); stats.removed++ } else store.markFailed(docId)
        }
    }

    /** The file is not there (any more): deleted, or its permission was taken back. */
    fun isGone(t: Throwable): Boolean {
        var e: Throwable? = t
        repeat(4) {
            val c = e ?: return false
            if (c is java.io.FileNotFoundException || c is SecurityException) return true
            if (c is IllegalStateException && c.message?.startsWith("cannot open") == true) return true
            e = c.cause
        }
        return false
    }

    /** Takes a scan image back out of the gallery (an indexing that failed after the page was saved). */
    fun deleteScan(ctx: Context, uri: String) {
        runCatching { ctx.contentResolver.delete(Uri.parse(uri), null, null) }
    }

    private suspend fun embed(run: IndexRun, texts: List<String>, stats: IndexStats): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val t0 = System.nanoTime()
        val out = run.embed(texts).map(PduStore::fit)
        stats.embedMs += (System.nanoTime() - t0) / 1_000_000
        return out
    }

    /**
     * A scanned page whose text is already known: embed first, and only then save the image to the gallery and store the page, so a
     * refusal or a failure leaves nothing behind and a retry cannot duplicate the image. If the store refuses after the image was
     * saved, the image is taken out again.
     */
    suspend fun ingestScan(
        ctx: Context, store: PduStore, title: String, folder: String, text: String, stats: IndexStats, run: IndexRun,
        save: () -> Pair<String, Long>, savedHere: Boolean,
    ) {
        stats.seen++
        val bodies = if (text.trim().length < MIN_OCR_CHARS) emptyList() else Chunker.split(text)
        val current = if (run.wipePending) 0 else store.chunkCount
        if (!ChunkCap.fits(current, 0, bodies.size)) throw IndexFull()
        val rows = embed(run, bodies.map { PduEmbedConfig.document(title, it) }, stats).mapIndexed { i, e -> ChunkRow(bodies[i], e) }
        run.beforeStore(embedded = rows.isNotEmpty())
        val (uri, modified) = save()
        try {
            store.putDoc(DocRecord(PduStore.docIdOf(uri), uri, title, "scan", folder, modified, System.currentTimeMillis()), rows)
        } catch (t: Throwable) {
            if (savedHere) deleteScan(ctx, uri)
            throw t
        }
        if (rows.isEmpty()) stats.noText++ else { stats.indexed++; stats.chunks += rows.size }
        stats.files += "${if (store.doc(PduStore.docIdOf(uri))?.isPrivate == true) "(private file)" else title} scan chars=${text.trim().length} chunks=${rows.size}"
    }
}
