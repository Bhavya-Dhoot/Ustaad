package com.swarmos.app.pdu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** ML Kit Latin text recognition (bundled model, offline). Devanagari and CJK need their own artifacts. */
object Ocr {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun recognize(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { cont.resume(it.text) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    /** Decode an image (EXIF rotation applied), scaled so the longest side is at most [maxSide]. Software bitmap for ML Kit. */
    fun decode(ctx: Context, uri: String, maxSide: Int = 2400): Bitmap {
        val src = if (uri.startsWith("file:")) ImageDecoder.createSource(File(Uri.parse(uri).path!!))
        else ImageDecoder.createSource(ctx.contentResolver, Uri.parse(uri))
        return ImageDecoder.decodeBitmap(src) { dec, info, _ ->
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > maxSide) {
                val f = maxSide.toFloat() / longest
                dec.setTargetSize((info.size.width * f).toInt(), (info.size.height * f).toInt())
            }
        }
    }
}

/** Text of a document, by kind. Everything runs on the phone. */
object Extractors {
    const val MAX_PDF_PAGES = 40
    private const val MAX_TEXT_BYTES = 2_000_000

    fun open(ctx: Context, uri: String): InputStream =
        if (uri.startsWith("file:")) FileInputStream(File(Uri.parse(uri).path!!))
        else ctx.contentResolver.openInputStream(Uri.parse(uri)) ?: error("cannot open $uri")

    fun plainText(ctx: Context, uri: String): String =
        open(ctx, uri).use { it.readBytes() }.let { b -> String(b, 0, minOf(b.size, MAX_TEXT_BYTES), Charsets.UTF_8) }

    fun docx(ctx: Context, uri: String): String = open(ctx, uri).use { DocxText.extract(it) }

    /** Text layer through pdfbox; no text layer (a scan) falls back to PdfRenderer pages + OCR. Returns text and whether OCR was needed. */
    suspend fun pdf(ctx: Context, uri: String, onOcr: (Long) -> Unit): Pair<String, Boolean> {
        PDFBoxResourceLoader.init(ctx.applicationContext)
        val layer = runCatching {
            open(ctx, uri).use { s ->
                PDDocument.load(s).use { doc ->
                    PDFTextStripper().apply { startPage = 1; endPage = minOf(doc.numberOfPages, MAX_PDF_PAGES) }.getText(doc)
                }
            }
        }.getOrDefault("")
        if (layer.trim().length >= MIN_TEXT_LAYER) return layer to false
        return renderAndOcr(ctx, uri, onOcr) to true
    }

    private suspend fun renderAndOcr(ctx: Context, uri: String, onOcr: (Long) -> Unit): String {
        val pfd: ParcelFileDescriptor =
            if (uri.startsWith("file:")) ParcelFileDescriptor.open(File(Uri.parse(uri).path!!), ParcelFileDescriptor.MODE_READ_ONLY)
            else ctx.contentResolver.openFileDescriptor(Uri.parse(uri), "r") ?: error("cannot open $uri")
        val sb = StringBuilder()
        try {
            val r = PdfRenderer(pfd)
            try {
                for (i in 0 until minOf(r.pageCount, MAX_PDF_PAGES)) {
                    val page = r.openPage(i)
                    try {
                        val scale = 1800f / maxOf(page.width, page.height)
                        val bmp = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val t0 = System.nanoTime()
                        sb.append(Ocr.recognize(bmp)).append("\n\n")
                        onOcr((System.nanoTime() - t0) / 1_000_000)
                        bmp.recycle()
                    } finally { page.close() }
                }
            } finally { r.close() }
        } finally { pfd.close() }
        return sb.toString()
    }

    private const val MIN_TEXT_LAYER = 20
}
