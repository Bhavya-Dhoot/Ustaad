package com.swarmos.app.link

import com.swarmos.app.core.Signer
import kotlinx.serialization.Serializable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Kotlin port of scripts/tput/tput.c on one raw TCP port. Every connection starts with an authentication token
 * (see [TputAuth]); then the mode byte inside it selects:
 *  'T' sink: server reads until EOF, times itself (first byte to EOF) and answers with two longs (bytes, nanos);
 *  'E' echo: server echoes every byte back with TCP_NODELAY (client does the 1-byte ping-pongs);
 *  'M' striped bulk transfer, see [Striped].
 * Pure java.net so it runs in JVM unit tests.
 */
object TputProtocol {
    const val MODE_SINK = 'T'.code
    const val MODE_ECHO = 'E'.code
    const val CHUNK = 1 shl 20          // 1 MiB writes, as in tput.c
    const val MB = 1L shl 20
}

/**
 * First bytes of every probe connection: mode (1) + timestamp ms (8) + 16 bytes of HMAC-SHA256(secret,
 * "tput\n$mode\n$ts"). The server refuses a connection whose token is wrong or older than 30 s, so an open probe
 * port cannot be used by anyone who does not hold the pairing secret.
 */
object TputAuth {
    const val BYTES = 25

    fun token(secret: String, mode: Int, tsMs: Long = System.currentTimeMillis()): ByteArray {
        val mac = Signer.hmacSha256Hex(secret, "tput\n$mode\n$tsMs").substring(0, 32)
        val out = ByteArray(BYTES)
        out[0] = mode.toByte()
        for (i in 0 until 8) out[1 + i] = (tsMs shr (56 - 8 * i)).toByte()
        for (i in 0 until 16) out[9 + i] = mac.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return out
    }

    /** The mode byte when [token] is valid for [secret], else null. */
    fun verify(secret: String, token: ByteArray, nowMs: Long = System.currentTimeMillis()): Int? {
        if (token.size != BYTES) return null
        val mode = token[0].toInt() and 0xFF
        var ts = 0L
        for (i in 0 until 8) ts = (ts shl 8) or (token[1 + i].toLong() and 0xFF)
        if (abs(nowMs - ts) > Signer.MAX_SKEW_MS) return null
        return if (MessageDigest.isEqual(token(secret, mode, ts), token)) mode else null
    }
}

/** What one probe connection may cost the phone. */
class TputLimits(
    val maxConnections: Int = 16,
    val maxBytes: Long = 4L shl 30,       // 4 GiB per connection
    val maxSeconds: Long = 300,           // wall clock per connection
)

@Serializable
data class RttStats(val n: Int, val minMs: Double, val avgMs: Double, val p95Ms: Double, val maxMs: Double)

/** Min, mean, nearest-rank p95 and max in ms from nanosecond samples. */
fun rttStats(samplesNs: LongArray): RttStats {
    require(samplesNs.isNotEmpty())
    val sorted = samplesNs.sortedArray()
    val p95 = sorted[(ceil(0.95 * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)]
    return RttStats(
        n = sorted.size,
        minMs = sorted.first() / 1e6,
        avgMs = sorted.average() / 1e6,
        p95Ms = p95 / 1e6,
        maxMs = sorted.last() / 1e6,
    )
}

data class ThroughputResult(
    val bytes: Long,
    val receiverSeconds: Double,
    val mbpsReceiver: Double,
    val senderSeconds: Double,
    val mbpsSender: Double,
)

class TputServer(
    private val port: Int,
    private val secret: () -> String = { "" },
    private val limits: TputLimits = TputLimits(),
) {
    @Volatile private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "tput-conn").apply { isDaemon = true } }
    private val striped = StripedReceiver(limits)
    private val active = AtomicInteger(0)

    /** Connections that were refused (bad token or over the connection cap), for tests and the status screen. */
    val refused = AtomicInteger(0)

    /** Binds and starts accepting. Returns the bound port (useful when constructed with port 0). */
    @Synchronized
    fun start(): Int {
        server?.let { return it.localPort }
        val ss = ServerSocket()
        ss.reuseAddress = true
        if (LinkTuning.rcvBufKb > 0) ss.receiveBufferSize = LinkTuning.rcvBufKb * 1024 // inherited by accepted sockets
        ss.bind(InetSocketAddress(port))
        server = ss
        Thread({
            while (!ss.isClosed) {
                val c = try { ss.accept() } catch (_: Exception) { break }
                if (active.incrementAndGet() > limits.maxConnections) {
                    active.decrementAndGet(); refused.incrementAndGet(); runCatching { c.close() }
                    continue
                }
                pool.execute { try { handle(c) } finally { active.decrementAndGet() } }
            }
        }, "tput-accept").apply { isDaemon = true }.start()
        return ss.localPort
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun handle(c: Socket) {
        c.use {
            try {
                c.soTimeout = 15_000 // the token must arrive quickly
                val din = DataInputStream(c.getInputStream())
                val out = c.getOutputStream()
                val token = ByteArray(TputAuth.BYTES)
                din.readFully(token)
                val mode = TputAuth.verify(secret(), token)
                if (mode == null) { refused.incrementAndGet(); return }
                c.soTimeout = 60_000
                val deadline = System.nanoTime() + limits.maxSeconds * 1_000_000_000L
                when (mode) {
                    TputProtocol.MODE_SINK -> {
                        val buf = ByteArray(TputProtocol.CHUNK)
                        var total = 0L
                        var t0 = 0L
                        while (true) {
                            val n = din.read(buf)
                            if (n < 0) break
                            if (total == 0L && n > 0) t0 = System.nanoTime()
                            total += n
                            if (total > limits.maxBytes || System.nanoTime() > deadline) throw IOException("probe over its byte or time cap")
                        }
                        val nanos = if (total > 0) System.nanoTime() - t0 else 0L
                        DataOutputStream(out).apply { writeLong(total); writeLong(nanos); flush() }
                    }
                    Striped.MODE -> striped.handle(din, out)
                    TputProtocol.MODE_ECHO -> {
                        c.tcpNoDelay = true
                        c.soTimeout = 30_000
                        var echoed = 0L
                        while (true) {
                            val b = din.read()
                            if (b < 0) break
                            out.write(b)
                            out.flush()
                            if (++echoed > 10_000_000 || System.nanoTime() > deadline) break
                        }
                    }
                }
            } catch (_: Exception) {
                // peer went away mid-probe, or went over a cap; nothing to report
            }
        }
    }
}

/** Client side. [open] must return a connected socket (the Android caller binds it to the right network). */
class TputClient(private val secret: () -> String, private val open: () -> Socket) {

    /** [pings] one-byte ping-pongs on a TCP_NODELAY connection. */
    fun rtt(pings: Int = 200): RttStats {
        open().use { s ->
            s.tcpNoDelay = true
            s.soTimeout = 10_000
            val out = s.getOutputStream()
            val inp = s.getInputStream()
            out.write(TputAuth.token(secret(), TputProtocol.MODE_ECHO)); out.flush()
            val samples = LongArray(pings)
            for (i in 0 until pings) {
                val t = System.nanoTime()
                out.write('a'.code); out.flush()
                if (inp.read() < 0) throw java.io.EOFException("echo closed after $i pings")
                samples[i] = System.nanoTime() - t
            }
            return rttStats(samples)
        }
    }

    /** Sends [megabytes] MiB in 1 MiB writes; Mbps is receiver-timed (server clock, first byte to EOF). */
    fun throughput(megabytes: Int, onProgress: ((Long) -> Unit)? = null): ThroughputResult {
        open().use { s ->
            s.soTimeout = 60_000
            val out = s.getOutputStream()
            val want = megabytes.toLong() * TputProtocol.MB
            val buf = ByteArray(TputProtocol.CHUNK)
            out.write(TputAuth.token(secret(), TputProtocol.MODE_SINK))
            val t0 = System.nanoTime()
            var sent = 0L
            while (sent < want) {
                val n = minOf(buf.size.toLong(), want - sent).toInt()
                out.write(buf, 0, n)
                sent += n
                onProgress?.invoke(sent)
            }
            out.flush()
            s.shutdownOutput()
            val reply = DataInputStream(s.getInputStream())
            val rxBytes = reply.readLong()
            val rxNanos = reply.readLong()
            val senderS = (System.nanoTime() - t0) / 1e9
            val rxS = rxNanos / 1e9
            return ThroughputResult(
                bytes = rxBytes,
                receiverSeconds = rxS,
                mbpsReceiver = if (rxS > 0) rxBytes * 8 / rxS / 1e6 else 0.0,
                senderSeconds = senderS,
                mbpsSender = if (senderS > 0) sent * 8 / senderS / 1e6 else 0.0,
            )
        }
    }
}
