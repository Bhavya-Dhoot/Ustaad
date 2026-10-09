package com.swarmos.app.link

import android.graphics.Bitmap
import android.graphics.Color
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object QrCode {
    /** ZXing QR as a black-on-white bitmap, [size] px square including the quiet zone. */
    fun bitmap(text: String, size: Int = 720): Bitmap {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2)
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val px = IntArray(size * size)
        for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (m[x, y]) Color.BLACK else Color.WHITE
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    fun scannerOptions(): BarcodeScannerOptions =
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()

    /** Decode a bitmap with the same ML Kit scanner the camera path uses. Null when no QR is found. */
    suspend fun decode(bmp: Bitmap): String? {
        val scanner = BarcodeScanning.getClient(scannerOptions())
        return try {
            suspendCancellableCoroutine { cont ->
                scanner.process(InputImage.fromBitmap(bmp, 0))
                    .addOnSuccessListener { list -> cont.resume(list.firstOrNull { it.rawValue != null }?.rawValue) }
                    .addOnFailureListener { cont.resume(null) }
            }
        } finally {
            scanner.close()
        }
    }
}
