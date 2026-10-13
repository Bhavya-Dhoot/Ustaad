package com.swarmos.app.cloud

/**
 * What a device offers for the local channel, the bulk path and the ciphers (docs/CLOUD-PROTOCOL.md 11.1). It travels as
 * item 1 of the `ext` field of the session Init and Reply, inside the signed transcript. A device at profile 1 (or one that
 * knows nothing of the field) sends an empty ext and is a baseline device: signed requests with sealed bodies over HTTP,
 * AES-256-GCM, no channel and no bulk path. Only the format and the choice are here; the channel itself is a later stage.
 */
class Caps(
    val minProfile: Int = 1,
    val maxProfile: Int = 1,
    val ciphers: Int = CIPHER_AES,
    val links: Int = 0,
    val features: Int = 0,
    val bulkStreams: Int = 0,
    val maxFrame: Int = DEFAULT_MAX_FRAME,
) {
    init {
        require(minProfile in 1..maxProfile && maxProfile <= 255) { "profiles" }
        require(ciphers and CIPHER_AES != 0) { "AES-256-GCM is required in every offer" }
        require(maxFrame in MIN_FRAME..MAX_FRAME) { "maxFrame" }
        require(bulkStreams in 0..255)
    }

    /** The ext field: empty for a baseline offer, else item 1. */
    fun encodeExt(): ByteArray {
        if (maxProfile == 1) return ByteArray(0)
        val value = Writer().u8(minProfile).u8(maxProfile).u8(ciphers).u8(links).u8(features).u8(bulkStreams).u32(maxFrame).bytes()
        return Writer().u8(ITEM_CAPS).lp(value).bytes()
    }

    companion object {
        const val CIPHER_AES = 1
        const val CIPHER_CHACHA = 2
        const val CIPHER_PREFER_CHACHA = 4 // the chip has no AES instructions
        const val LINK_AP = 1
        const val LINK_DIRECT = 2
        const val LINK_BLUETOOTH = 4
        const val LINK_RELAY = 8
        const val FEATURE_CHANNEL = 1
        const val FEATURE_BULK = 2
        const val MIN_FRAME = 16 * 1024
        const val MAX_FRAME = 1024 * 1024
        const val DEFAULT_MAX_FRAME = 64 * 1024
        const val MAX_EXT = 256
        private const val ITEM_CAPS = 1

        val BASELINE = Caps()

        /** Items in ascending id order; an id this build does not know is skipped. No item 1 means baseline. */
        fun decodeExt(ext: ByteArray): Caps {
            val r = Reader(ext)
            var caps: Caps? = null
            var last = 0
            while (r.remaining() > 0) {
                val id = r.u8()
                val value = r.lp(MAX_EXT)
                if (id <= last) malformed("ext items out of order")
                last = id
                if (id == ITEM_CAPS) caps = parseValue(value)
            }
            return caps ?: BASELINE
        }

        private fun parseValue(value: ByteArray): Caps {
            val r = Reader(value)
            val caps = try {
                Caps(r.u8(), r.u8(), r.u8(), r.u8(), r.u8(), r.u8(), r.u32())
            } catch (e: IllegalArgumentException) {
                malformed("capability offer")
            }
            r.end()
            return caps
        }
    }
}

/** What both sides use, computed identically by each from the two offers. */
class Negotiated(
    val profile: Int,
    val aead: Aead,
    val channel: Boolean,
    /** Ceiling on parallel bulk streams; 0 means no bulk path. */
    val bulkStreams: Int,
    val maxFrame: Int,
    /** Links both sides have to each other (Caps.LINK_* bits). */
    val links: Int,
) {
    companion object {
        /** A symmetric function of the two offers. */
        fun of(a: Caps, b: Caps): Negotiated {
            val lo = maxOf(a.minProfile, b.minProfile)
            val hi = minOf(a.maxProfile, b.maxProfile)
            if (lo > hi) malformed("no common profile")
            val chacha = (a.ciphers and b.ciphers and Caps.CIPHER_CHACHA) != 0 &&
                ((a.ciphers or b.ciphers) and Caps.CIPHER_PREFER_CHACHA) != 0
            val modern = hi >= 2
            return Negotiated(
                profile = hi,
                aead = if (chacha && modern) Aead.CHACHA20_POLY1305 else Aead.AES_256_GCM,
                channel = modern && (a.features and b.features and Caps.FEATURE_CHANNEL) != 0,
                bulkStreams = if (modern && (a.features and b.features and Caps.FEATURE_BULK) != 0) minOf(a.bulkStreams, b.bulkStreams) else 0,
                maxFrame = minOf(a.maxFrame, b.maxFrame),
                links = a.links and b.links,
            )
        }
    }
}
