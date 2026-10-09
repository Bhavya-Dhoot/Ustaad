package com.swarmos.app.link

import kotlinx.serialization.Serializable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32

/**
 * Striped bulk transfer. One transfer is cut into chunks (1 MiB by default), each chunk is sent as
 * `[index u32][length u32][crc32 u32][payload]` on whichever of N TCP streams is free (streams may run over
 * different paths at once, for example LAN and Wi-Fi Direct), and the receiver checks every CRC and hands the
 * chunks to a sink in index order. Streams start with the mode byte 'M' and a header, end with an index of -1,
 * and each receives the same 24-byte result (bytes, receiver nanoseconds, bad chunks, chunks not delivered).
 * Pure java.net, so it runs in JVM tests.
 */
object Striped {
    const val MODE = 'M'.code
    const val END = -1
    const val DEFAULT_CHUNK = 1 shl 20

    class Header(val session: UUID, val streams: Int, val streamIdx: Int, val totalChunks: Int, val chunkSize: Int, val totalBytes: Long)

    fun writeHeader(out: DataOutputStream, h: Header) {
        out.writeLong(h.session.mostSignificantBits); out.writeLong(h.session.leastSignificantBits)
        out.writeByte(h.streams); out.writeByte(h.streamIdx)
        out.writeInt(h.totalChunks); out.writeInt(h.chunkSize); out.writeLong(h.totalBytes)
    }

    fun readHeader(inp: DataInputStream): Header {
        val s = UUID(inp.readLong(), inp.readLong())
        return Header(s, inp.readUnsignedByte(), inp.readUnsignedByte(), inp.readInt(), inp.readInt(), inp.readLong())
    }

    fun chunkLength(index: Int, totalBytes: Long, chunkSize: Int): Int =
        minOf(chunkSize.toLong(), totalBytes - index.toLong() * chunkSize).toInt()
}

/**
 * Delivers chunks to [sink] in index order. Out-of-order chunks wait in memory; a stream that is more than
 * [maxBuffered] chunks ahead blocks until the gap closes (back-pressure, so a slow path cannot make memory grow).
 */
class Reassembler(private val total: Int, private val maxBuffered: Int = 64, private val sink: (index: Int, data: ByteArray) -> Unit) {
    private val lock = java.lang.Object()
    private val pending = HashMap<Int, ByteArray>()
    private var next = 0

    val delivered: Int get() = synchronized(lock) { next }

    /** Hand over chunk [index]. Throws on a duplicate or out-of-range index. The sink runs under the lock, so it is called strictly in order. */
    fun accept(index: Int, data: ByteArray) {
        synchronized(lock) {
            if (index < 0 || index >= total) throw IOException("chunk $index outside 0..${total - 1}")
            if (index < next || pending.containsKey(index)) throw IOException("duplicate chunk $index")
            while (index != next && pending.size >= maxBuffered) lock.wait(50)
            if (index == next) {
                sink(next, data); next++
                while (true) { val d = pending.remove(next) ?: break; sink(next, d); next++ }
                lock.notifyAll()
            } else {
                pending[index] = data
            }
        }
    }
}

/** What a finished striped receive looks like to the sender. */
class StripedReceipt(val bytes: Long, val nanos: Long, val badChunks: Int, val missing: Int)

private class StripedSession(val header: Striped.Header, sink: (Int, ByteArray) -> Unit) {
    val reasm = Reassembler(header.totalChunks, sink = sink)
    val firstByte = AtomicLong(0)
    val lastByte = AtomicLong(0)
    val bytes = AtomicLong(0)
    val bad = AtomicInteger(0)
    val done = CountDownLatch(header.streams)
    val users = AtomicInteger(0)
}

/** Receiving side, used by [TputServer] after it has read the mode byte 'M'. */
class StripedReceiver(private val sink: (Int, ByteArray) -> Unit = { _, _ -> }) {
    private val sessions = ConcurrentHashMap<UUID, StripedSession>()

    fun handle(inp: DataInputStream, out: java.io.OutputStream) {
        val h = Striped.readHeader(inp)
        val s = sessions.computeIfAbsent(h.session) { StripedSession(h, sink) }
        s.users.incrementAndGet()
        try {
            try {
                while (true) {
                    val idx = inp.readInt()
                    if (idx == Striped.END) break
                    val len = inp.readInt()
                    val crc = inp.readInt()
                    if (len < 0 || len > (16 shl 20)) throw IOException("bad chunk length $len")
                    val data = ByteArray(len)
                    inp.readFully(data)
                    val now = System.nanoTime()
                    s.firstByte.compareAndSet(0, now)
                    val c = CRC32().apply { update(data, 0, len) }
                    if (c.value.toInt() != crc) s.bad.incrementAndGet()
                    s.reasm.accept(idx, data)
                    s.bytes.addAndGet(len.toLong())
                    s.lastByte.set(System.nanoTime())
                }
            } finally {
                s.done.countDown()
            }
            s.done.await(120, TimeUnit.SECONDS)
            val fb = s.firstByte.get()
            val nanos = if (fb > 0) s.lastByte.get() - fb else 0L
            DataOutputStream(out).apply {
                writeLong(s.bytes.get()); writeLong(nanos); writeInt(s.bad.get()); writeInt(h.totalChunks - s.reasm.delivered); flush()
            }
        } finally {
            if (s.users.decrementAndGet() == 0) sessions.remove(h.session, s)
        }
    }
}

@Serializable
data class PathShare(val path: String, val streams: Int, val bytes: Long, val mbps: Double)

class StripedResult(
    val bytes: Long,
    val receiverSeconds: Double,
    val mbpsReceiver: Double,
    val senderSeconds: Double,
    val mbpsSender: Double,
    val badChunks: Int,
    val missing: Int,
    val perPath: List<PathShare>,
)

/**
 * Sender: [openers] gives one connected socket per path (index = path). [streams] threads share one chunk counter,
 * so a faster path automatically carries more chunks. [labels] names the paths in the result.
 */
class StripedSender(private val openers: List<() -> Socket>, private val labels: List<String>) {
    fun send(
        megabytes: Int, streams: Int, chunkSize: Int = Striped.DEFAULT_CHUNK, onProgress: ((Long) -> Unit)? = null,
    ): StripedResult {
        require(openers.isNotEmpty() && streams >= openers.size)
        val totalBytes = megabytes.toLong() * TputProtocol.MB
        val totalChunks = ((totalBytes + chunkSize - 1) / chunkSize).toInt()
        val session = UUID.randomUUID()
        val next = AtomicInteger(0)
        val sent = AtomicLong(0)
        val perPathBytes = LongArray(openers.size)
        val perPathStreams = IntArray(openers.size)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val socks = ArrayList<Socket>()
        try {
            // open every stream first so all of them start together
            for (i in 0 until streams) {
                val path = i % openers.size
                socks += openers[path]()
                perPathStreams[path]++
            }
            val ready = CountDownLatch(1)
            val finished = CountDownLatch(streams)
            val receipts = arrayOfNulls<StripedReceipt>(streams)
            val t0 = System.nanoTime()
            val threads = socks.mapIndexed { i, s ->
                Thread({
                    try {
                        val path = i % openers.size
                        val out = DataOutputStream(java.io.BufferedOutputStream(s.getOutputStream(), 64 * 1024))
                        out.writeByte(Striped.MODE)
                        Striped.writeHeader(out, Striped.Header(session, streams, i, totalChunks, chunkSize, totalBytes))
                        out.flush()
                        ready.await()
                        val buf = ByteArray(chunkSize)
                        val crc = CRC32()
                        while (failure.get() == null) {
                            val idx = next.getAndIncrement()
                            if (idx >= totalChunks) break
                            val len = Striped.chunkLength(idx, totalBytes, chunkSize)
                            // make every chunk different so nothing in the path can cache or compress it
                            buf[0] = idx.toByte(); buf[1] = (idx shr 8).toByte(); buf[2] = (idx shr 16).toByte(); buf[3] = i.toByte()
                            crc.reset(); crc.update(buf, 0, len)
                            out.writeInt(idx); out.writeInt(len); out.writeInt(crc.value.toInt())
                            out.write(buf, 0, len)
                            synchronized(perPathBytes) { perPathBytes[path] += len.toLong() }
                            onProgress?.invoke(sent.addAndGet(len.toLong()))
                        }
                        out.writeInt(Striped.END)
                        out.flush()
                        s.shutdownOutput()
                        val inp = DataInputStream(s.getInputStream())
                        receipts[i] = StripedReceipt(inp.readLong(), inp.readLong(), inp.readInt(), inp.readInt())
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    } finally {
                        finished.countDown()
                    }
                }, "striped-$i").apply { isDaemon = true; start() }
            }
            ready.countDown()
            finished.await()
            threads.forEach { it.join(1000) }
            failure.get()?.let { throw if (it is IOException) it else IOException(it) }
            val senderS = (System.nanoTime() - t0) / 1e9
            val r = receipts.filterNotNull().firstOrNull() ?: throw EOFException("no receipt")
            val rxS = r.nanos / 1e9
            val mbps = if (rxS > 0) r.bytes * 8 / rxS / 1e6 else 0.0
            return StripedResult(
                bytes = r.bytes, receiverSeconds = rxS, mbpsReceiver = mbps, senderSeconds = senderS,
                mbpsSender = if (senderS > 0) sent.get() * 8 / senderS / 1e6 else 0.0,
                badChunks = r.badChunks, missing = r.missing,
                perPath = openers.indices.map { p ->
                    PathShare(labels.getOrElse(p) { "path$p" }, perPathStreams[p], perPathBytes[p], if (rxS > 0) perPathBytes[p] * 8 / rxS / 1e6 else 0.0)
                },
            )
        } finally {
            socks.forEach { runCatching { it.close() } }
        }
    }
}
