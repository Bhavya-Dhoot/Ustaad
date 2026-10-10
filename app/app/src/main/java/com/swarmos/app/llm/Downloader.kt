package com.swarmos.app.llm

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class Sha256MismatchException(val expected: String, val actual: String) :
    IOException("SHA-256 mismatch: expected $expected, got $actual")

/**
 * Resumable HTTP file download with a SHA-256 check. No Android classes so it is unit-testable on the JVM.
 * Data goes to `<dest>.part`; a later call resumes from the part file's length with a Range request.
 * Every byte read from the network is reported through [onNetworkBytes] (that is the app's "WAN bytes").
 */
class Downloader(
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    interface Listener {
        fun onProgress(done: Long, total: Long) {}
        fun onNetworkBytes(n: Long) {}
        fun onVerifying() {}
    }

    /** Returns when `dest` exists, is complete and (if [sha256] is given) verified. Throws on failure; the part file is kept for resume unless the hash is wrong. */
    suspend fun download(url: String, dest: File, expectedBytes: Long?, sha256: String?, listener: Listener, maxAttempts: Int = 4) {
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            attempt++
            try {
                fetch(url, part, expectedBytes, listener)
                break
            } catch (e: IOException) {
                if (attempt >= maxAttempts) throw e
                Thread.sleep(500L * attempt)
            }
        }
        listener.onVerifying()
        if (sha256 != null) {
            val actual = sha256Hex(part)
            if (!actual.equals(sha256, ignoreCase = true)) {
                part.delete()
                throw Sha256MismatchException(sha256, actual)
            }
        }
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) throw IOException("cannot rename ${part.name} to ${dest.name}")
    }

    private suspend fun fetch(url: String, part: File, expectedBytes: Long?, listener: Listener) {
        var offset = part.length()
        if (expectedBytes != null && offset == expectedBytes) {
            listener.onProgress(offset, expectedBytes)
            return // already complete, only verification left
        }
        val conn = open(URL(url))
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Ustaad-model-download")
            if (offset > 0) conn.setRequestProperty("Range", "bytes=$offset-")
            val code = conn.responseCode
            val total: Long
            when (code) {
                200 -> { // server ignored the Range header (or none was sent): start over
                    offset = 0
                    total = conn.contentLengthLong.takeIf { it > 0 } ?: expectedBytes ?: -1L
                }
                206 -> total = contentRangeTotal(conn.getHeaderField("Content-Range")) ?: expectedBytes ?: -1L
                416 -> { // our part file is not a valid prefix any more
                    part.delete()
                    throw IOException("HTTP 416, restarting from zero")
                }
                else -> throw IOException("HTTP $code")
            }
            RandomAccessFile(part, "rw").use { raf ->
                raf.setLength(offset)
                raf.seek(offset)
                val buf = ByteArray(256 * 1024)
                var done = offset
                var lastReport = 0L
                conn.inputStream.use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        done += n
                        listener.onNetworkBytes(n.toLong())
                        val now = System.nanoTime()
                        if (now - lastReport > 250_000_000L) {
                            lastReport = now
                            listener.onProgress(done, total)
                        }
                    }
                }
                listener.onProgress(done, total)
                val want = expectedBytes ?: total.takeIf { it > 0 }
                if (want != null && done != want) throw IOException("short download: $done of $want bytes")
            }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** Free space that must remain after the download, so the phone does not end up full (1 GB). */
        const val SPARE_BYTES = 1024L * 1024 * 1024

        /**
         * The refusal text when the models folder cannot take [totalBytes] of [fileName] (of which [partBytes] are already on disk from an
         * earlier try) plus [SPARE_BYTES], or null when it can. [freeBytes] null (StatFs failed) and [totalBytes] 0 (size not pinned)
         * allow the download: the check must never be the reason a working phone cannot download.
         */
        fun spaceRefusal(fileName: String, totalBytes: Long, partBytes: Long, freeBytes: Long?): String? {
            if (freeBytes == null || totalBytes <= 0) return null
            val remaining = (totalBytes - partBytes).coerceAtLeast(0)
            if (freeBytes >= remaining + SPARE_BYTES) return null
            return "Not enough free space to download $fileName: it needs ${gb(remaining)} more plus ${gb(SPARE_BYTES)} to spare, " +
                "and this phone has ${gb(freeBytes)} free. Delete a model or free up storage, then try again."
        }

        private fun gb(bytes: Long): String = String.format(java.util.Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))

        /** `bytes 100-199/1234` -> 1234 */
        fun contentRangeTotal(header: String?): Long? =
            header?.substringAfterLast('/')?.trim()?.toLongOrNull()

        fun sha256Hex(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(1024 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
