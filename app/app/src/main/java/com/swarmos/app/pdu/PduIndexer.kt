package com.swarmos.app.pdu

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.swarmos.app.AppGraph
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
)

/** Mutable counters for one run. */
class IndexStats(val label: String) {
    var seen = 0; var indexed = 0; var unchanged = 0; var noText = 0; var failed = 0; var chunks = 0
    var embedMs = 0L; var totalMs = 0L
    val ocrMs = ArrayList<Long>()
    val files = ArrayList<String>()
    val errors = ArrayList<String>()

    fun report() = IndexReport(
        label, seen, indexed, unchanged, noText, failed, chunks, ocrMs.size,
        if (ocrMs.isEmpty()) 0 else ocrMs.sum() / ocrMs.size, ocrMs.toList(), embedMs, totalMs, files.toList(), errors.toList(),
    )
}

/** Listing and reading of the three on-device sources. Nothing here touches the network. */
object PduIndexer {
    const val MIN_OCR_CHARS = 20
    private const val EMBED_BATCH = 16

    fun kindOf(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "txt", "md" -> "text"
        "pdf" -> "pdf"
        "docx" -> "docx"
        "jpg", "jpeg", "png", "webp" -> "photo"
        else -> null
    }

    // ---- listing ------------------------------------------------------------------------------------------

    /** MediaStore images, optionally only those whose RELATIVE_PATH starts with [relPrefix] (e.g. "Pictures/SwarmDemo"). */
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
            ctx.contentResolver.query(
                DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId),
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null, null, null,
            )?.use { c ->
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
        val root = Environment.getExternalStorageDirectory().absolutePath
        return dir.walkTopDown().filter { it.isFile }.mapNotNull { f ->
            val kind = kindOf(f.name) ?: return@mapNotNull null
            DocRef(
                Uri.fromFile(f).toString(), f.name, kind,
                f.parentFile!!.absolutePath.removePrefix(root).trim('/'), f.lastModified(), f.lastModified(),
            )
        }.toList()
    }

    private fun displayName(ctx: Context, docUri: Uri): String? =
        ctx.contentResolver.query(docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }

    /** Saves a captured page into Pictures/SwarmScans (Private/ for private scans) so any viewer can open it. Returns uri and DATE_MODIFIED in ms. */
    fun saveScan(ctx: Context, jpeg: File, keepPrivate: Boolean): Pair<String, Long> {
        val resolver = ctx.contentResolver
        val values = android.content.ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "scan-${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, scanFolder(keepPrivate))
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore insert failed")
        resolver.openOutputStream(uri)!!.use { out -> jpeg.inputStream().use { it.copyTo(out) } }
        resolver.update(uri, android.content.ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        val modified = resolver.query(uri, arrayOf(MediaStore.Images.Media.DATE_MODIFIED), null, null, null)
            ?.use { if (it.moveToFirst()) it.getLong(0) * 1000 else null } ?: System.currentTimeMillis()
        return uri.toString() to modified
    }

    fun scanFolder(keepPrivate: Boolean) = if (keepPrivate) "Pictures/SwarmScans/Private" else "Pictures/SwarmScans"

    // ---- ingest -------------------------------------------------------------------------------------------

    /** Read, chunk, embed and store one file. Skips it when this uri + lastModified is already indexed. */
    suspend fun ingest(ctx: Context, store: PduStore, ref: DocRef, stats: IndexStats, preText: String? = null) {
        stats.seen++
        val docId = PduStore.docIdOf(ref.uri)
        if (store.isIndexed(docId, ref.lastModified)) { stats.unchanged++; return }
        val doc = DocRecord(docId, ref.uri, ref.title, ref.kind, ref.folder, ref.lastModified, ref.dateMs)
        try {
            var ocrMsHere = -1L
            val text = when (ref.kind) {
                "text" -> Extractors.plainText(ctx, ref.uri)
                "docx" -> Extractors.docx(ctx, ref.uri)
                "pdf" -> Extractors.pdf(ctx, ref.uri) { ms -> stats.ocrMs += ms; ocrMsHere = ms }.first
                else -> if (preText != null) preText else { // photo, scan
                    val bmp = Ocr.decode(ctx, ref.uri)
                    val t0 = System.nanoTime()
                    val t = Ocr.recognize(bmp)
                    ocrMsHere = (System.nanoTime() - t0) / 1_000_000
                    stats.ocrMs += ocrMsHere
                    bmp.recycle()
                    t
                }
            }
            val isImage = ref.kind == "photo" || ref.kind == "scan"
            val bodies = if (isImage && text.trim().length < MIN_OCR_CHARS) emptyList() else Chunker.split(text)
            val rows = embed(bodies.map { Chunker.withTitle(ref.title, it) }, stats).mapIndexed { i, e -> ChunkRow(bodies[i], e) }
            store.putDoc(doc, rows)
            if (rows.isEmpty()) stats.noText++ else { stats.indexed++; stats.chunks += rows.size }
            val shown = if (store.doc(docId)?.isPrivate == true) "(private file)" else ref.title
            stats.files += "$shown ${ref.kind} chars=${text.trim().length} chunks=${rows.size}" +
                (if (ocrMsHere >= 0) " ocrMs=$ocrMsHere" else "")
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            stats.failed++
            val shown = if (SharePolicy.isPrivateByDefault(ref.folder)) "(private file)" else ref.title
            stats.errors += "$shown: ${t.javaClass.simpleName} ${t.message}".take(200)
        }
    }

    private suspend fun embed(texts: List<String>, stats: IndexStats): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val t0 = System.nanoTime()
        val out = ArrayList<FloatArray>(texts.size)
        for (batch in texts.chunked(EMBED_BATCH)) out += AppGraph.embedder.embed(batch).map(PduStore::fit)
        stats.embedMs += (System.nanoTime() - t0) / 1_000_000
        return out
    }
}
