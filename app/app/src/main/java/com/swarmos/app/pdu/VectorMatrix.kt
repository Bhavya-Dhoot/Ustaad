package com.swarmos.app.pdu

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The in-memory half of the Data Union store: one row-major float matrix (unit-length vectors) and the chunk id of each row, for
 * brute-force cosine. Not thread safe on its own; [PduStore] reads and changes it under its lock.
 *
 * Memory arithmetic, which is also where [MAX_CHUNKS] comes from: a row is 256 floats * 4 B = 1,024 B plus an 8 B id, 1,032 B.
 * The matrix is one array that grows by half again when it is full ([growths] counts the copies), so appending n rows copies each
 * row a constant number of times, and loading takes its size from the database first so it is allocated once and never copied.
 * A growth step holds the old and the new array together: from 40,000 to 60,000 rows that is 41 MB + 62 MB, about 103 MB at the peak
 * and 62 MB afterwards, against a heap of 256 MB (the manifest asks for the large heap, which is larger).
 */
internal class VectorMatrix(private val dim: Int) {
    var data = FloatArray(0)
        private set
    var ids = LongArray(0)
        private set
    var rows = 0
        private set

    /** How many times the arrays were copied into larger ones. */
    var growths = 0
        private set

    val capacityRows: Int get() = ids.size

    /** Empty the matrix and make room for [capacity] rows. The old arrays are dropped first, so they are never held beside the new ones. */
    fun reset(capacity: Int) {
        data = FloatArray(0)
        ids = LongArray(0)
        rows = 0
        val c = maxOf(capacity, 0)
        data = FloatArray(c * dim)
        ids = LongArray(c)
    }

    /** Add one row, copying [dim] floats from [v] starting at [from]. */
    fun append(id: Long, v: FloatArray, from: Int = 0) {
        ensure(rows + 1)
        System.arraycopy(v, from, data, rows * dim, dim)
        ids[rows] = id
        rows++
    }

    /** Add one row straight from a stored little-endian float32 BLOB, without making a FloatArray of it. False if the BLOB has the wrong size. */
    fun appendBlob(id: Long, blob: ByteArray): Boolean {
        if (blob.size != dim * 4) return false
        ensure(rows + 1)
        ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(data, rows * dim, dim)
        ids[rows] = id
        rows++
        return true
    }

    /** Remove the rows whose id is in [removed], in place, keeping the order of the others. Returns how many rows went. */
    fun removeAll(removed: Set<Long>): Int {
        if (removed.isEmpty() || rows == 0) return 0
        var first = 0
        while (first < rows && ids[first] !in removed) first++
        if (first == rows) return 0
        var w = first
        for (r in first + 1 until rows) {
            if (ids[r] in removed) continue
            if (w != r) {
                System.arraycopy(data, r * dim, data, w * dim, dim)
                ids[w] = ids[r]
            }
            w++
        }
        val gone = rows - w
        rows = w
        return gone
    }

    private fun ensure(need: Int) {
        if (need <= ids.size) return
        val cap = maxOf(need, ids.size + ids.size / 2, MIN_CAPACITY)
        data = data.copyOf(cap * dim)
        ids = ids.copyOf(cap)
        growths++
    }

    companion object {
        private const val MIN_CAPACITY = 256

        /**
         * The most chunks one phone indexes: 60,000 rows is a 62 MB matrix (see above), a brute-force search of about 15 million
         * multiply-adds, well inside the 1.5 s a peer waits. A 2 MB text file is about 2,000 chunks and a photo or a page about 1 to 3,
         * so this is tens of thousands of documents.
         */
        const val MAX_CHUNKS = 60_000
    }
}

/** Whether a file with [adding] new chunks fits when the store holds [current] and the file's old [replacing] chunks go. */
internal object ChunkCap {
    fun fits(current: Int, replacing: Int, adding: Int, max: Int = VectorMatrix.MAX_CHUNKS): Boolean = current - replacing + adding <= max

    fun message(max: Int = VectorMatrix.MAX_CHUNKS) =
        "The index is full (%,d passages). Nothing more was added; remove a folder or fewer pages and index again.".format(java.util.Locale.US, max)
}
