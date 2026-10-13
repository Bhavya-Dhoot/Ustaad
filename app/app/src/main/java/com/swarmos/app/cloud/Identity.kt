package com.swarmos.app.cloud

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec

/**
 * A device's identity: one EC P-256 key pair that only signs (ECDSA, SHA-256). The private key never leaves the
 * implementation. The Android implementation (a keystore key) comes in the wiring stage; the core asks for nothing
 * but these two members.
 */
interface DeviceIdentity {
    /** 65 bytes: 0x04 || X || Y. */
    val publicKey: ByteArray

    /** 64 bytes: r || s. */
    fun sign(message: ByteArray): ByteArray
}

/** `fp(pub)` as lower-case hex: the member id of a device key. */
fun DeviceIdentity.memberId(): String = Hex.of(Crypto.fingerprint(publicKey))

/**
 * Software key for tests and for a desktop node. A desktop node that keeps this in a file must protect the file; the
 * hardware-backed implementation is the one for phones.
 */
class SoftwareIdentity private constructor(
    private val priv: PrivateKey,
    override val publicKey: ByteArray,
) : DeviceIdentity {

    override fun sign(message: ByteArray): ByteArray = Crypto.ecdsaSign(priv, message)

    /** PKCS#8 form, to be written to the node's own protected file. */
    fun exportPrivate(): ByteArray = priv.encoded

    companion object {
        fun generate(): SoftwareIdentity {
            val kp = Crypto.newKeyPair()
            return SoftwareIdentity(kp.private, Crypto.rawPublic(kp.public))
        }

        /** Fails loudly when the private key and the public key do not belong together. */
        fun restore(pkcs8: ByteArray, publicKey: ByteArray): SoftwareIdentity {
            val priv = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
            val id = SoftwareIdentity(priv, publicKey)
            val probe = Crypto.random(16)
            require(Crypto.ecdsaVerify(publicKey, probe, id.sign(probe))) { "private key does not match the public key" }
            return id
        }
    }
}
