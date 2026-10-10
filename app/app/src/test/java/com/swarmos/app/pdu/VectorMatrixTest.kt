package com.swarmos.app.pdu

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/** Finding 5: the vector matrix grows in place by amortised steps, drops a changed file's rows without a reload, and has a cap. */
class VectorMatrixTest {
    private val dim = 8

    private fun unit(seed: Int): FloatArray {
        val r = Random(seed)
        val v = FloatArray(dim) { r.nextFloat() - 0.5f }
        val n = Math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(dim) { v[it] / n }
    }

    private fun rowOf(m: VectorMatrix, r: Int) = m.data.copyOfRange(r * dim, (r + 1) * dim)

    @Test fun appendingTenThousandRowsCopiesTheArraysOnlyAFewTimes() {
        val m = VectorMatrix(dim)
        for (i in 0 until 10_000) m.append(i.toLong(), unit(i))
        assertEquals(10_000, m.rows)
        // growth by half again: about log1.5(10000/256) = 9 copies, not one per row
        assertTrue("growths ${m.growths}", m.growths <= 12)
        for (i in listOf(0, 1, 255, 256, 5_000, 9_999)) {
            assertEquals(i.toLong(), m.ids[i])
            assertArrayEquals(unit(i), rowOf(m, i), 0f)
        }
    }

    @Test fun roomMadeAtLoadMeansNoCopyForTheFirstFilesAdded() {
        val m = VectorMatrix(dim)
        m.reset(1_000 + 1_000 / 8 + 256)
        for (i in 0 until 1_000) m.append(i.toLong(), unit(i))
        for (i in 1_000 until 1_300) m.append(i.toLong(), unit(i)) // the headroom
        assertEquals(0, m.growths)
        assertEquals(1_300, m.rows)
    }

    @Test fun resetDropsTheOldArraysBeforeTheNewOnesExist() {
        val m = VectorMatrix(dim)
        for (i in 0 until 500) m.append(i.toLong(), unit(i))
        m.reset(10)
        assertEquals(0, m.rows)
        assertEquals(10, m.capacityRows)
        assertEquals(10 * dim, m.data.size)
    }

    @Test fun aRowCanBeReadStraightFromItsStoredBytes() {
        val v = unit(3)
        val b = ByteBuffer.allocate(dim * 4).order(ByteOrder.LITTLE_ENDIAN).also { bb -> v.forEach { bb.putFloat(it) } }.array()
        val m = VectorMatrix(dim)
        assertTrue(m.appendBlob(42, b))
        assertArrayEquals(v, rowOf(m, 0), 0f)
        assertFalse("a blob of the wrong size is refused", m.appendBlob(43, ByteArray(5)))
        assertEquals(1, m.rows)
    }

    @Test fun removingAChangedFilesRowsKeepsTheOrderAndTheVectorsOfTheOthers() {
        val m = VectorMatrix(dim)
        for (i in 0 until 20) m.append(i.toLong(), unit(i))
        val gone = setOf(3L, 4L, 5L, 11L, 19L)
        assertEquals(5, m.removeAll(gone))
        assertEquals(15, m.rows)
        val expected = (0 until 20).filter { it.toLong() !in gone }
        assertEquals(expected.map { it.toLong() }, (0 until m.rows).map { m.ids[it] })
        for ((r, i) in expected.withIndex()) assertArrayEquals(unit(i), rowOf(m, r), 0f)
        // then the new rows of the changed file go at the end
        m.append(100, unit(100))
        assertEquals(100L, m.ids[15])
        assertArrayEquals(unit(100), rowOf(m, 15), 0f)
    }

    @Test fun removingNothingOrAnUnknownIdChangesNothing() {
        val m = VectorMatrix(dim)
        for (i in 0 until 5) m.append(i.toLong(), unit(i))
        assertEquals(0, m.removeAll(emptySet()))
        assertEquals(0, m.removeAll(setOf(99L)))
        assertEquals(5, m.rows)
    }

    @Test fun searchOverTheMatrixAfterAChangeGivesTheSameAnswerAsAFreshLoad() {
        val m = VectorMatrix(dim)
        for (i in 0 until 200) m.append(i.toLong(), unit(i))
        val gone = (0 until 200 step 7).map { it.toLong() }.toSet()
        m.removeAll(gone)
        m.append(1_000, unit(1_000))

        val fresh = VectorMatrix(dim) // what a reload from the database would build
        for (i in (0 until 200).filter { it.toLong() !in gone } + 1_000) fresh.append(i.toLong(), unit(i))

        val q = unit(77)
        val a = Cosine.topK(m.data, dim, m.rows, q, 10).map { m.ids[it.row] to it.score }
        val b = Cosine.topK(fresh.data, dim, fresh.rows, q, 10).map { fresh.ids[it.row] to it.score }
        assertEquals(b, a)
    }

    @Test fun theChunkCapComesFromTheArithmeticAndIsEnforced() {
        // 256 floats * 4 B + 8 B id = 1,032 B a row
        val perRow = 256 * 4 + 8
        val matrixMb = VectorMatrix.MAX_CHUNKS.toLong() * perRow / 1_000_000.0
        assertTrue("matrix $matrixMb MB", matrixMb < 70)
        assertEquals(60_000, VectorMatrix.MAX_CHUNKS)
        assertTrue(ChunkCap.fits(current = 59_990, replacing = 0, adding = 10))
        assertFalse(ChunkCap.fits(current = 59_990, replacing = 0, adding = 11))
        assertTrue("a changed file's old chunks go, so they do not count", ChunkCap.fits(current = 60_000, replacing = 5, adding = 5))
        assertTrue(ChunkCap.message().contains("60,000"))
    }
}
