package com.swarmos.app.cloud

import java.util.TreeMap

/** Frame types (docs/CLOUD-PROTOCOL.md section 10). The receiver of a stream expects one type and refuses any other. */
object FrameType {
    const val REQUEST = 1
    const val RESPONSE = 2
    const val EVENT = 3
    const val JOIN = 4
    const val CONTROL = 5
    const val BULK = 6
}

/**
 * The 14 header bytes of a frame: type, flags, stream, position. One format for HTTP bodies, the local channel, the bulk
 * path and the relay. A demultiplexer reads this before it picks the stream's receiver.
 */
class FrameHeader(val type: Int, val flags: Int, val stream: Int, val position: Long) {
    val final: Boolean get() = (flags and FrameCodec.FINAL) != 0

    internal fun bytes(): ByteArray = Writer().u8(type).u8(flags).u32(stream).u64(position).bytes()

    companion object {
        fun parse(frame: ByteArray): FrameHeader {
            if (frame.size < FrameCodec.HEADER + FrameCodec.TAG) throw ProtocolError(CloudError.BAD_FRAME)
            return try {
                val r = Reader(frame)
                FrameHeader(r.u8(), r.u8(), r.u32(), r.u64())
            } catch (e: ProtocolError) {
                throw ProtocolError(CloudError.BAD_FRAME) // a stream number with the top bit set
            }
        }
    }
}

internal object FrameCodec {
    const val MAX_PLAIN = 4 * 1024 * 1024
    const val MAX_BULK_PLAIN = 1024 * 1024
    const val HEADER = 14 // type 1, flags 1, stream 4, position 8
    const val TAG = 16
    const val MAX_COUNTER = 0xFFFF_FFFFL
    const val FINAL = 1

    /**
     * Each stream has its own key, derived from the session's direction key, the cipher, the stream number and a context
     * (the request id, the cloud id for a join, the channel number, the transfer id). So two streams of one session never
     * share a key and a nonce, and a frame cannot be moved to another stream, request, transfer or direction.
     */
    fun streamKey(directionKey: ByteArray, aead: Aead, stream: Int, context: ByteArray): ByteArray =
        Crypto.hkdfExpand(directionKey, concat(lbl("frame/stream"), Writer().u8(aead.id).u32(stream).lp(context).bytes()), 32)

    /** The whole header is authenticated: type, final flag, stream and position (counter or offset). */
    fun aad(h: FrameHeader): ByteArray = concat(lbl("frame"), h.bytes())

    /** 4 zero bytes then the 64-bit position. */
    fun nonce(position: Long): ByteArray = Writer().u32(0).u64(position).bytes()

    fun seal(key: ByteArray, aead: Aead, h: FrameHeader, plain: ByteArray): ByteArray =
        concat(h.bytes(), Crypto.seal(aead, key, nonce(h.position), aad(h), plain))

    /** Plaintext of a frame whose header is [h], or null when the tag fails. */
    fun open(key: ByteArray, aead: Aead, h: FrameHeader, frame: ByteArray): ByteArray? =
        Crypto.open(aead, key, nonce(h.position), aad(h), frame.copyOfRange(HEADER, frame.size))
}

/** Seals the frames of one ordered stream (types 1 to 5). The last frame is marked final; nothing can be sealed after it. */
class FrameSealer(
    directionKey: ByteArray,
    private val stream: Int,
    context: ByteArray,
    private val type: Int,
    private val aead: Aead = Aead.AES_256_GCM,
    private val maxPlain: Int = FrameCodec.MAX_PLAIN,
) {
    private val key = FrameCodec.streamKey(directionKey, aead, stream, context)
    private var counter = 0L
    private var done = false

    init { require(type in FrameType.REQUEST..FrameType.CONTROL && stream >= 0) { "ordered stream" } }

    @Synchronized fun seal(plain: ByteArray, final: Boolean = false): ByteArray {
        check(!done) { "the stream already has its final frame" }
        require(plain.size <= maxPlain) { "frame too large" }
        check(counter < FrameCodec.MAX_COUNTER) { "stream counter exhausted" }
        val c = counter++
        if (final) done = true
        return FrameCodec.seal(key, aead, FrameHeader(type, if (final) FrameCodec.FINAL else 0, stream, c), plain)
    }
}

/**
 * Opens the frames of one ordered stream. It accepts exactly the next counter (0, 1, 2, ...): "higher than the last" would
 * let a frame deleted from the middle go unnoticed. After any failure the stream is dead and nothing more is opened from
 * it. When the carrier closes, [end] says whether the stream was complete.
 */
class FrameOpener(
    directionKey: ByteArray,
    private val stream: Int,
    context: ByteArray,
    private val type: Int,
    private val aead: Aead = Aead.AES_256_GCM,
    private val maxPlain: Int = FrameCodec.MAX_PLAIN,
) {
    private val key = FrameCodec.streamKey(directionKey, aead, stream, context)
    private var next = 0L
    private var failed = false

    var finished = false
        private set

    @Synchronized fun open(frame: ByteArray): ByteArray {
        if (failed || finished) { failed = true; throw ProtocolError(CloudError.BAD_FRAME) } // nothing follows a final frame
        try {
            if (frame.size > FrameCodec.HEADER + maxPlain + FrameCodec.TAG) throw ProtocolError(CloudError.BAD_FRAME)
            val h = FrameHeader.parse(frame)
            if (h.type != type || h.stream != stream || (h.flags and FrameCodec.FINAL.inv()) != 0) throw ProtocolError(CloudError.BAD_FRAME)
            if (h.position != next) throw ProtocolError(CloudError.OUT_OF_ORDER) // replayed, reordered or a frame missing before it
            val plain = FrameCodec.open(key, aead, h, frame) ?: throw ProtocolError(CloudError.BAD_FRAME)
            next++
            if (h.final) finished = true
            return plain
        } catch (e: ProtocolError) {
            failed = true
            throw e
        }
    }

    /** The carrier closed. A stream that never reached its final frame was cut: the partial data must not be used. */
    @Synchronized fun end() {
        if (failed || !finished) throw ProtocolError(CloudError.TRUNCATED)
    }
}

/** One chunk of a bulk payload: where it goes and what it holds. */
class BulkChunk(val offset: Long, val data: ByteArray, val final: Boolean)

/**
 * Seals bulk chunks (type 6). The position is the byte offset in the payload and is part of the associated data and the
 * nonce, so a chunk is bound to its stream and place. Each offset is sealed once per stream: a resume uses a new transfer
 * id (the context) and so new keys.
 */
class BulkSealer(
    directionKey: ByteArray,
    private val stream: Int,
    context: ByteArray,
    private val aead: Aead = Aead.AES_256_GCM,
    private val maxPlain: Int = FrameCodec.MAX_BULK_PLAIN,
) {
    private val key = FrameCodec.streamKey(directionKey, aead, stream, context)
    private val sealed = HashSet<Long>()

    init { require(stream >= 0) { "stream" } }

    /** [final] marks the chunk that ends the payload. */
    @Synchronized fun seal(offset: Long, data: ByteArray, final: Boolean = false): ByteArray {
        require(offset >= 0 && data.isNotEmpty() && data.size <= maxPlain) { "chunk" }
        check(sealed.add(offset)) { "this offset was already sealed on this stream" } // the same key and nonce must not carry two plaintexts
        return FrameCodec.seal(key, aead, FrameHeader(FrameType.BULK, if (final) FrameCodec.FINAL else 0, stream, offset), data)
    }
}

/**
 * Opens bulk chunks in any order and tracks which ranges are held, so a resume starts at [firstMissing]. A chunk already
 * held is returned as null (ignore it); one that overlaps a held chunk, or lies past the end a final chunk fixed, is an error.
 */
class BulkOpener(
    directionKey: ByteArray,
    private val stream: Int,
    context: ByteArray,
    private val aead: Aead = Aead.AES_256_GCM,
    private val maxPlain: Int = FrameCodec.MAX_BULK_PLAIN,
) {
    private val key = FrameCodec.streamKey(directionKey, aead, stream, context)
    private val held = TreeMap<Long, Int>() // offset to length
    private var total = -1L

    @Synchronized fun open(frame: ByteArray): BulkChunk? {
        if (frame.size > FrameCodec.HEADER + maxPlain + FrameCodec.TAG) throw ProtocolError(CloudError.BAD_FRAME)
        val h = FrameHeader.parse(frame)
        if (h.type != FrameType.BULK || h.stream != stream || (h.flags and FrameCodec.FINAL.inv()) != 0) throw ProtocolError(CloudError.BAD_FRAME)
        val plain = FrameCodec.open(key, aead, h, frame) ?: throw ProtocolError(CloudError.BAD_FRAME)
        val end = h.position + plain.size
        if (plain.isEmpty() || h.position < 0 || end < h.position) throw ProtocolError(CloudError.BAD_FRAME)
        val same = held[h.position]
        if (same != null) {
            if (same != plain.size) throw ProtocolError(CloudError.BAD_FRAME)
            return null // a chunk we already hold (authenticated, so harmless)
        }
        val before = held.floorEntry(h.position)
        val after = held.ceilingEntry(h.position)
        if ((before != null && before.key + before.value > h.position) || (after != null && after.key < end)) throw ProtocolError(CloudError.BAD_FRAME)
        if (h.final) {
            if (total >= 0 && total != end) throw ProtocolError(CloudError.BAD_FRAME)
            val tail = held.lastEntry()
            if (tail != null && tail.key + tail.value > end) throw ProtocolError(CloudError.BAD_FRAME) // a held chunk lies past the end
            total = end
        } else if (total in 0 until end) {
            throw ProtocolError(CloudError.BAD_FRAME)
        }
        held[h.position] = plain.size
        return BulkChunk(h.position, plain, h.final)
    }

    /** First byte offset not yet held: where a resume starts. */
    @Synchronized fun firstMissing(): Long {
        var at = 0L
        for ((offset, len) in held) {
            if (offset > at) break
            at = offset + len
        }
        return at
    }

    /** True when the final chunk has arrived and nothing is missing before it. */
    @Synchronized fun complete(): Boolean = total >= 0 && firstMissing() == total
}

object Frames {
    /** A one-shot body: one final frame. [stream] must not be reused with the same [directionKey] and [context]. */
    fun sealOnce(directionKey: ByteArray, stream: Int, context: ByteArray, type: Int, plain: ByteArray, aead: Aead = Aead.AES_256_GCM): ByteArray =
        FrameSealer(directionKey, stream, context, type, aead).seal(plain, final = true)

    fun openOnce(directionKey: ByteArray, stream: Int, context: ByteArray, type: Int, frame: ByteArray, aead: Aead = Aead.AES_256_GCM): ByteArray {
        val o = FrameOpener(directionKey, stream, context, type, aead)
        val plain = o.open(frame)
        if (!o.finished) throw ProtocolError(CloudError.TRUNCATED) // a one-shot body must be a single final frame
        return plain
    }
}
