package com.swarmos.app.link

import kotlinx.serialization.json.Json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Length-prefixed messages over a pipe of small fragments (one GATT write or notification each).
 * Every message is `[length u16 big-endian][payload]`, cut into fragments of at most `maxFragment` bytes; the
 * pipe keeps order, so the receiver just counts bytes. Pure Kotlin, tested without a radio.
 */
object BleFrame {
    const val MAX_MESSAGE = 16 * 1024

    fun split(message: ByteArray, maxFragment: Int): List<ByteArray> {
        require(message.size <= MAX_MESSAGE) { "message of ${message.size} bytes is too large" }
        require(maxFragment >= 1)
        val framed = ByteArray(message.size + 2)
        framed[0] = (message.size shr 8).toByte(); framed[1] = message.size.toByte()
        System.arraycopy(message, 0, framed, 2, message.size)
        return framed.toList().chunked(maxFragment).map { it.toByteArray() }
    }
}

class BleReassembler(private val onMessage: (ByteArray) -> Unit) {
    private val buf = ByteArrayOutputStream()
    private var need = -1

    @Synchronized
    fun feed(fragment: ByteArray) {
        buf.write(fragment)
        while (true) {
            val bytes = buf.toByteArray()
            if (need < 0) {
                if (bytes.size < 2) return
                need = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
            }
            if (bytes.size < 2 + need) return
            val msg = bytes.copyOfRange(2, 2 + need)
            buf.reset()
            if (bytes.size > 2 + need) buf.write(bytes, 2 + need, bytes.size - 2 - need)
            need = -1
            onMessage(msg)
        }
    }
}

/** Compact JSON for the radio: defaults and nulls are not written. */
internal val BleJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }

/** One message on the Bluetooth channel: a request, its response, or the identity both sides send first. */
@Serializable
data class BleMsg(
    val t: String,                       // "req" | "rsp" | "id"
    val id: Int = 0,
    val m: String? = null,               // HTTP method
    val p: String? = null,               // full path, e.g. /v1/health
    val h: Map<String, String>? = null,  // X-Swarm-* headers
    val b: String? = null,               // body
    val s: Int? = null,                  // response status
    val n: String? = null,               // node id ("id" message)
    val nm: String? = null,              // node name ("id" message)
)

/**
 * Request/response over one [BleFrame] pipe. [send] writes one whole message (the caller fragments it);
 * [handler] answers requests arriving from the other side. Calls are multiplexed by id, so several can be in flight.
 */
class BleRpc(
    private val scope: CoroutineScope,
    private val send: suspend (ByteArray) -> Unit,
    private val handler: suspend (BleMsg) -> BleMsg,
    private val onIdentity: (BleMsg) -> Unit = {},
) {
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<BleMsg>>()
    private val ids = AtomicInteger(1)

    suspend fun sendIdentity(nodeId: String, name: String) =
        send(BleJson.encodeToString(BleMsg.serializer(), BleMsg(t = "id", n = nodeId, nm = name)).toByteArray())

    suspend fun call(request: BleMsg, timeoutMs: Long): BleMsg {
        val id = ids.getAndIncrement().and(0x7FFFFFFF)
        val d = CompletableDeferred<BleMsg>()
        pending[id] = d
        try {
            send(BleJson.encodeToString(BleMsg.serializer(), request.copy(t = "req", id = id)).toByteArray())
            return try { withTimeout(timeoutMs) { d.await() } } catch (e: TimeoutCancellationException) { throw IOException("bluetooth request timed out after $timeoutMs ms") }
        } finally {
            pending.remove(id)
        }
    }

    fun onMessage(bytes: ByteArray) {
        val msg = runCatching { BleJson.decodeFromString(BleMsg.serializer(), String(bytes)) }.getOrNull() ?: return
        when (msg.t) {
            "rsp" -> pending[msg.id]?.complete(msg)
            "id" -> onIdentity(msg)
            "req" -> scope.launch {
                val rsp = runCatching { handler(msg) }.getOrElse { BleMsg(t = "rsp", s = 500, b = """{"error":"${it.message?.replace('"', '\'')}"}""") }
                runCatching { send(BleJson.encodeToString(BleMsg.serializer(), rsp.copy(t = "rsp", id = msg.id)).toByteArray()) }
            }
        }
    }

    /** Fail every call still waiting (the link dropped). */
    fun close() {
        pending.values.forEach { it.completeExceptionally(IOException("bluetooth link closed")) }
        pending.clear()
    }
}

/**
 * Advertisement payload and the encryption of Wi-Fi Direct credentials, both keyed by the pairing secret, so a
 * phone that is not paired can neither be recognised nor read anything from the advertisement or the channel.
 *
 * Advertisement service data (9 bytes): node id (4) + tag (4) + flags (1: bit0 hosts a Wi-Fi Direct group, bit1 app in front).
 * tag = first 4 bytes of HMAC-SHA256(secret, "ustaad-ble" + node id + minute), minute = epoch ms / 60000, so it
 * rotates every minute; receivers accept the previous and next minute for clock skew.
 */
object BleAdv {
    const val PAYLOAD = 9
    const val FLAG_DIRECT_HOST = 1
    const val FLAG_FOREGROUND = 2

    fun nodeIdBytes(nodeId: String): ByteArray = ByteArray(4) { i -> nodeId.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    fun nodeIdHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    private fun tag(secret: String, nodeId: ByteArray, minute: Long): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        mac.update("ustaad-ble".toByteArray()); mac.update(nodeId); mac.update(minute.toString().toByteArray())
        return mac.doFinal().copyOf(4)
    }

    fun encode(secret: String, nodeId: String, flags: Int, nowMs: Long = System.currentTimeMillis()): ByteArray {
        val id = nodeIdBytes(nodeId)
        return id + tag(secret, id, nowMs / 60_000) + byteArrayOf(flags.toByte())
    }

    class Seen(val nodeId: String, val flags: Int)

    /** The sender's node id and flags when [payload] was made with our secret, else null. */
    fun decode(secret: String, payload: ByteArray, nowMs: Long = System.currentTimeMillis()): Seen? {
        if (payload.size < PAYLOAD) return null
        val id = payload.copyOfRange(0, 4)
        val got = payload.copyOfRange(4, 8)
        val minute = nowMs / 60_000
        for (m in longArrayOf(minute, minute - 1, minute + 1)) {
            if (java.security.MessageDigest.isEqual(tag(secret, id, m), got)) return Seen(nodeIdHex(id), payload[8].toInt() and 0xFF)
        }
        return null
    }
}

/** AES-256-GCM of small secrets (the Wi-Fi Direct credentials) under a key derived from the pairing secret. */
object SecretBox {
    private fun key(secret: String): SecretKeySpec =
        SecretKeySpec(java.security.MessageDigest.getInstance("SHA-256").digest(("ustaad-box-v1" + secret).toByteArray()), "AES")

    /** nonce (12) + ciphertext + tag (16), base64. [aad] binds the blob to its owner (its node id). */
    fun seal(secret: String, plain: ByteArray, aad: String): String {
        val nonce = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(secret), GCMParameterSpec(128, nonce))
        c.updateAAD(aad.toByteArray())
        return java.util.Base64.getEncoder().encodeToString(nonce + c.doFinal(plain))
    }

    /** The plaintext, or null when the blob was not sealed with this secret and aad. */
    fun open(secret: String, blob: String, aad: String): ByteArray? = runCatching {
        val raw = java.util.Base64.getDecoder().decode(blob)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(secret), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        c.updateAAD(aad.toByteArray())
        c.doFinal(raw, 12, raw.size - 12)
    }.getOrNull()
}
