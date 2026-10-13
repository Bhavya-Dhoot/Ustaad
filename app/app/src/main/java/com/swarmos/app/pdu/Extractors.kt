package com.swarmos.app.pdu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
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

/** Both recognisers' output for one image. [devanagari] is null when the second pass was not needed. */
class OcrResult(val latin: String, val devanagari: String?, val text: String)

/**
 * ML Kit text recognition, bundled models, offline. The Latin recogniser runs first; it ignores Devanagari, so when its
 * result is short (see [OcrMerge.needsDevanagari]) the Devanagari recogniser runs too and both texts are kept.
 */
object Ocr {
    private val latinRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val devanagariRecognizer by lazy { TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()) }

    suspend fun recognize(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        latinRecognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { cont.resume(it.text) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    suspend fun recognizeDevanagari(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        devanagariRecognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { cont.resume(it.text) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    suspend fun recognizeAll(bitmap: Bitmap): OcrResult {
        val latin = recognize(bitmap)
        val deva = if (OcrMerge.needsDevanagari(latin)) runCatching { recognizeDevanagari(bitmap) }.getOrNull() else null
        return OcrResult(latin, deva, OcrMerge.merge(latin, deva))
    }

    /**
     * Decode an image (EXIF rotation applied), scaled so the longest side is at most [maxSide]. Software bitmap for ML Kit. ImageDecoder
     * is Android 9 (API 28); on Android 8 the same is done with BitmapFactory ([decodeWithBitmapFactory]).
     */
    fun decode(ctx: Context, uri: String, maxSide: Int = 2400): Bitmap {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return decodeWithBitmapFactory(ctx, uri, maxSide)
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

    /** [decode] for Android 8: BitmapFactory (a power-of-two sample size, then an exact resize) and the EXIF turn applied by hand. */
    private fun decodeWithBitmapFactory(ctx: Context, uri: String, maxSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        Extractors.open(ctx, uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        val options = BitmapFactory.Options().apply { inSampleSize = ImageTurn.sampleSize(bounds.outWidth, bounds.outHeight, maxSide) }
        val sampled = Extractors.open(ctx, uri).use { BitmapFactory.decodeStream(it, null, options) } ?: error("cannot decode $uri")
        val longest = maxOf(sampled.width, sampled.height)
        val scale = if (longest > maxSide) maxSide.toFloat() / longest else 1f
        val orientation = runCatching {
            Extractors.open(ctx, uri).use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val turn = ImageTurn.forExif(orientation)
        if (scale == 1f && turn.isNone) return sampled
        val matrix = Matrix().apply {
            postScale(scale, scale)
            if (turn.degrees != 0) postRotate(turn.degrees.toFloat())
            if (turn.mirror) postScale(-1f, 1f)
        }
        return Bitmap.createBitmap(sampled, 0, 0, sampled.width, sampled.height, matrix, true)
    }
}

/** The two decisions of the BitmapFactory decode used on Android 8, pure so they are tested on the JVM. */
object ImageTurn {
    /** The EXIF orientation as the turn to undo it: rotate clockwise by [degrees], then mirror left to right when [mirror]. */
    data class Turn(val degrees: Int, val mirror: Boolean) {
        val isNone: Boolean get() = degrees == 0 && !mirror
    }

    /** [orientation] is the EXIF tag value 1 to 8 (ExifInterface.ORIENTATION_*); anything else is no turn. */
    fun forExif(orientation: Int): Turn = when (orientation) {
        2 -> Turn(0, true)      // flip horizontal
        3 -> Turn(180, false)   // rotate 180
        4 -> Turn(180, true)    // flip vertical
        5 -> Turn(90, true)     // transpose
        6 -> Turn(90, false)    // rotate 90
        7 -> Turn(270, true)    // transverse
        8 -> Turn(270, false)   // rotate 270
        else -> Turn(0, false)
    }

    /** The largest power-of-two sample size that keeps the longest side at [maxSide] or more (so the exact resize after it only shrinks). 1 when the size is unknown. */
    fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
        val longest = maxOf(width, height)
        if (longest <= 0 || maxSide <= 0) return 1
        var sample = 1
        while (longest / (sample * 2) >= maxSide) sample *= 2
        return sample
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
        open(ctx, uri).use { CappedRead.bytes(it, MAX_TEXT_BYTES) }.let { b -> String(b, Charsets.UTF_8) }

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
                        sb.append(Ocr.recognizeAll(bmp).text).append("\n\n")
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
