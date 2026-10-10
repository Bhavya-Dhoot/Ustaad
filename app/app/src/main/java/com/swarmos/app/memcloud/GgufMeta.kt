package com.swarmos.app.memcloud

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * What MemoryCloud and ExpertMesh need to know about a model, read from the GGUF header only (a few MB at the start of
 * the file; the weights are never touched). Nothing here is hard-coded per model: layer count, expert count, experts
 * used per token and the bytes of every layer come from the file.
 */
data class GgufInfo(
    val file: String,
    val fileBytes: Long,
    val architecture: String,
    /** `general.name` when present, else the file name without extension. */
    val name: String,
    val blockCount: Int,
    val embeddingLength: Int?,
    val contextLength: Int?,
    /** 0 for a dense model. */
    val expertCount: Int,
    /** Experts selected per token per MoE layer; 0 for a dense model. */
    val expertUsedCount: Int,
    /** Leading layers that are dense inside an MoE model (`<arch>.leading_dense_block_count`), 0 when absent. */
    val leadingDenseBlocks: Int,
    /** Bytes of the `blk.<i>.*` tensors, index = layer. */
    val layerBytes: List<Long>,
    /** Bytes of `token_embd.*` (kept by the driver, which tokenises and embeds). */
    val inputBytes: Long,
    /** Bytes of the other tensors outside the layers (output norm, rope tables): goes with the last layer range. */
    val outputBytes: Long,
    /** Bytes of `output.weight`, the lm_head; 0 when the model ties it to the embedding table (Qwen3.5-4B). */
    val outputHeadBytes: Long = 0,
) {
    /** An untied lm_head can stay on the driver, so the 600 KB of logits per token never cross the link. */
    val hasUntiedHead: Boolean get() = outputHeadBytes > 0

    val isMoe: Boolean get() = expertCount > 1
    val moeLayers: Int get() = if (isMoe) (blockCount - leadingDenseBlocks).coerceAtLeast(0) else 0
    val tensorBytes: Long get() = layerBytes.sum() + inputBytes + outputBytes + outputHeadBytes
}

class GgufFormatException(message: String) : RuntimeException(message)

object GgufMeta {
    private const val MAGIC = 0x46554747L // "GGUF" little-endian
    private const val DEFAULT_ALIGNMENT = 32L
    private const val MAX_KEPT_STRING = 512L
    private const val MAX_TENSORS = 100_000L
    private const val MAX_KEYS = 100_000L
    private const val MAX_BLOCKS = 4096

    fun read(file: File): GgufInfo = FileInputStream(file).use { read(it, file.name, file.length()) }

    /** [fileBytes] is the full file length; the last tensor's size is derived from it. A corrupt file gives [GgufFormatException], nothing else. */
    fun read(input: InputStream, fileName: String, fileBytes: Long): GgufInfo = try {
        parse(input, fileName, fileBytes)
    } catch (e: GgufFormatException) {
        throw e
    } catch (e: EOFException) {
        throw GgufFormatException("$fileName ends inside its header (truncated or not a GGUF file)")
    } catch (e: OutOfMemoryError) {
        throw GgufFormatException("$fileName has an implausible header size (corrupt file)")
    } catch (e: RuntimeException) {
        throw GgufFormatException("$fileName has a corrupt header: ${e.javaClass.simpleName}")
    }

    private fun parse(input: InputStream, fileName: String, fileBytes: Long): GgufInfo {
        val r = Src(BufferedInputStream(input, 1 shl 20), fileBytes)
        if (r.u32() != MAGIC) throw GgufFormatException("not a GGUF file: $fileName")
        val version = r.u32()
        if (version < 2 || version > 3) throw GgufFormatException("unsupported GGUF version $version")
        val tensorCount = r.u64()
        val kvCount = r.u64()
        // every key takes at least 12 bytes and every tensor entry at least 24, so counts beyond the file size are corrupt
        if (tensorCount < 0 || kvCount < 0 || tensorCount > MAX_TENSORS || kvCount > MAX_KEYS ||
            tensorCount > fileBytes / 24 || kvCount > fileBytes / 12) {
            throw GgufFormatException("implausible header: $tensorCount tensors, $kvCount keys in a file of $fileBytes bytes")
        }

        val ints = HashMap<String, Long>()
        val strings = HashMap<String, String>()
        repeat(kvCount.toInt()) {
            val key = r.string(256)
            val type = r.u32().toInt()
            readValue(r, type, key, ints, strings)
        }

        class T(val name: String, val offset: Long)
        val tensors = ArrayList<T>(tensorCount.toInt())
        repeat(tensorCount.toInt()) {
            val name = r.string(256)
            val dims = r.u32().toInt()
            if (dims < 0 || dims > 8) throw GgufFormatException("tensor $name has $dims dims")
            repeat(dims) { r.u64() }
            r.u32() // ggml type
            tensors += T(name, r.u64())
        }
        val alignment = ints["general.alignment"]?.takeIf { it > 0 } ?: DEFAULT_ALIGNMENT
        val dataStart = (r.pos + alignment - 1) / alignment * alignment
        val dataBytes = fileBytes - dataStart

        // Tensor data follows the info block in offset order, so a tensor's size is the gap to the next offset.
        val sorted = tensors.sortedBy { it.offset }
        val arch = strings["general.architecture"] ?: throw GgufFormatException("no general.architecture")
        val blockCount = ints["$arch.block_count"]?.toInt()?.takeIf { it in 1..MAX_BLOCKS }
            ?: throw GgufFormatException("no usable $arch.block_count (it must be between 1 and $MAX_BLOCKS)")
        val layerBytes = LongArray(blockCount)
        var input0 = 0L
        var rest = 0L
        var head = 0L
        for ((i, t) in sorted.withIndex()) {
            val end = if (i + 1 < sorted.size) sorted[i + 1].offset else dataBytes
            val size = (end - t.offset).coerceAtLeast(0)
            val layer = blockIndex(t.name)
            when {
                layer != null && layer < blockCount -> layerBytes[layer] += size
                t.name.startsWith("token_embd") -> input0 += size
                t.name == "output.weight" -> head += size
                else -> rest += size
            }
        }
        return GgufInfo(
            file = fileName,
            fileBytes = fileBytes,
            architecture = arch,
            name = strings["general.name"] ?: fileName.removeSuffix(".gguf"),
            blockCount = blockCount,
            embeddingLength = ints["$arch.embedding_length"]?.toInt(),
            contextLength = ints["$arch.context_length"]?.toInt(),
            expertCount = ints["$arch.expert_count"]?.toInt() ?: 0,
            expertUsedCount = ints["$arch.expert_used_count"]?.toInt() ?: 0,
            leadingDenseBlocks = ints["$arch.leading_dense_block_count"]?.toInt() ?: 0,
            layerBytes = layerBytes.toList(),
            inputBytes = input0,
            outputBytes = rest,
            outputHeadBytes = head,
        )
    }

    /** `blk.17.ffn_up_exps.weight` -> 17; null for tensors outside the repeating layers. */
    internal fun blockIndex(name: String): Int? {
        if (!name.startsWith("blk.")) return null
        val end = name.indexOf('.', 4)
        if (end < 0) return null
        return name.substring(4, end).toIntOrNull()
    }

    private fun readValue(r: Src, type: Int, key: String, ints: MutableMap<String, Long>, strings: MutableMap<String, String>) {
        when (type) {
            0, 1 -> ints[key] = r.u8().let { if (type == 1) it.toByte().toLong() else it }
            2 -> ints[key] = r.u16()
            3 -> ints[key] = r.u16().toShort().toLong()
            4 -> ints[key] = r.u32()
            5 -> ints[key] = r.u32().toInt().toLong()
            6 -> r.skip(4)
            7 -> r.skip(1)
            8 -> {
                val len = r.u64()
                if (len in 0..MAX_KEPT_STRING && key.startsWith("general.")) strings[key] = r.bytes(len.toInt()).toString(Charsets.UTF_8)
                else r.skip(len)
            }
            9 -> {
                val elem = r.u32().toInt()
                val n = r.u64()
                val fixed = fixedSize(elem)
                if (n < 0) throw GgufFormatException("array of $n elements in $key")
                if (fixed != null) {
                    if (n > r.remaining() / fixed) throw GgufFormatException("array of $n elements in $key does not fit in the file")
                    r.skip(n * fixed)
                } else if (elem == 8) {
                    if (n > r.remaining() / 8) throw GgufFormatException("array of $n strings in $key does not fit in the file")
                    var i = 0L
                    while (i < n) { r.skip(r.u64()); i++ }
                } else {
                    throw GgufFormatException("array of type $elem in $key")
                }
            }
            10 -> ints[key] = r.u64()
            11 -> ints[key] = r.u64()
            12 -> r.skip(8)
            else -> throw GgufFormatException("unknown value type $type for $key")
        }
    }

    private fun fixedSize(type: Int): Long? = when (type) {
        0, 1, 7 -> 1L
        2, 3 -> 2L
        4, 5, 6 -> 4L
        10, 11, 12 -> 8L
        else -> null
    }

    /** Little-endian reader that counts the bytes it consumed. */
    private class Src(private val ins: InputStream, private val fileBytes: Long) {
        var pos = 0L
            private set

        /** Bytes of the file not read yet. */
        fun remaining(): Long = (fileBytes - pos).coerceAtLeast(0)

        fun u8(): Long {
            val b = ins.read()
            if (b < 0) throw EOFException("GGUF header ends early")
            pos++
            return b.toLong()
        }

        fun u16(): Long = u8() or (u8() shl 8)
        fun u32(): Long = u16() or (u16() shl 16)
        fun u64(): Long = u32() or (u32() shl 32)

        fun bytes(n: Int): ByteArray {
            if (n < 0 || n > remaining()) throw GgufFormatException("a value of $n bytes at offset $pos runs past the end of the file")
            val out = ByteArray(n)
            var got = 0
            while (got < n) {
                val k = ins.read(out, got, n - got)
                if (k < 0) throw EOFException("GGUF header ends early")
                got += k
            }
            pos += n
            return out
        }

        fun skip(n: Long) {
            if (n < 0 || n > remaining()) throw GgufFormatException("a value of $n bytes at offset $pos runs past the end of the file")
            var left = n
            while (left > 0) {
                val k = ins.skip(left)
                if (k > 0) { left -= k; continue }
                if (ins.read() < 0) throw EOFException("GGUF header ends early")
                left--
            }
            pos += n
        }

        /** String of at most [max] bytes (keys and tensor names); a longer one means the file is not GGUF. */
        fun string(max: Long): String {
            val len = u64()
            if (len < 0 || len > max) throw GgufFormatException("string of $len bytes where at most $max is expected")
            return bytes(len.toInt()).toString(Charsets.UTF_8)
        }
    }
}
