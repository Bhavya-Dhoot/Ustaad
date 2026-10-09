package com.swarmos.app.link

import kotlinx.serialization.Serializable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.math.ceil

/**
 * Kotlin port of scripts/tput/tput.c on one raw TCP port. The client sends one mode byte:
 *  'T' sink: server reads until EOF, times itself (first byte to EOF) and answers with two longs (bytes, nanos);
 *  'E' echo: server echoes every byte back with TCP_NODELAY (client does the 1-byte ping-pongs).
 * Pure java.net so it runs in JVM unit tests.
 */
object TputProtocol {
    const val MODE_SINK = 'T'.code
    const val MODE_ECHO = 'E'.code
    const val CHUNK = 1 shl 20          // 1 MiB writes, as in tput.c
    const val MB = 1L shl 20
}

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

class TputServer(private val port: Int) {
    @Volatile private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "tput-conn").apply { isDaemon = true } }

    /** Binds and starts accepting. Returns the bound port (useful when constructed with port 0). */
    @Synchronized
    fun start(): Int {
        server?.let { return it.localPort }
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        server = ss
        Thread({
            while (!ss.isClosed) {
                val c = try { ss.accept() } catch (_: Exception) { break }
                pool.execute { handle(c) }
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
                c.soTimeout = 60_000
                val inp = c.getInputStream()
                val out = c.getOutputStream()
                when (inp.read()) {
                    TputProtocol.MODE_SINK -> {
                        val buf = ByteArray(TputProtocol.CHUNK)
                        var total = 0L
                        var t0 = 0L
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            if (total == 0L && n > 0) t0 = System.nanoTime()
                            total += n
                        }
                        val nanos = if (total > 0) System.nanoTime() - t0 else 0L
                        DataOutputStream(out).apply { writeLong(total); writeLong(nanos); flush() }
                    }
                    TputProtocol.MODE_ECHO -> {
                        c.tcpNoDelay = true
                        while (true) {
                            val b = inp.read()
                            if (b < 0) break
                            out.write(b)
                            out.flush()
                        }
                    }
                }
            } catch (_: Exception) {
                // peer went away mid-probe; nothing to report
            }
        }
    }
}

/** Client side. [open] must return a connected socket (the Android caller binds it to the right network). */
class TputClient(private val open: () -> Socket) {

    /** [pings] one-byte ping-pongs on a TCP_NODELAY connection. */
    fun rtt(pings: Int = 200): RttStats {
        open().use { s ->
            s.tcpNoDelay = true
            s.soTimeout = 10_000
            val out = s.getOutputStream()
            val inp = s.getInputStream()
            out.write(TputProtocol.MODE_ECHO); out.flush()
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
            out.write(TputProtocol.MODE_SINK)
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
