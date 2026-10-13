package com.swarmos.app.cloud

/**
 * What a device shows to the local network (docs/CLOUD-PROTOCOL.md section 11): a tag that changes every 10 minutes and
 * differs per device, computed from the cloud's discovery key. Members recognise each other; an outsider sees random
 * bytes that cannot be tied to a cloud or followed beyond one slot.
 */
object Discovery {
    /** Version of the closed-cloud protocol; the unsigned hello answers this and nothing else. */
    const val PROTOCOL = 3
    const val HELLO_JSON = """{"protocol":3}"""

    const val SLOT_MS = 600_000L
    const val TAG_BYTES = 8
    const val MIN_TAG_BYTES = 4 // a Bluetooth advert may have room for 4

    fun slot(nowMs: Long): Long = Math.floorDiv(nowMs, SLOT_MS)

    fun tag(discoveryKey: ByteArray, nodeId: String, slot: Long, length: Int = TAG_BYTES): ByteArray {
        require(length in MIN_TAG_BYTES..16) { "tag length" }
        return Crypto.hmac(discoveryKey, lbl("tag"), Writer().str(nodeId).u64(slot).bytes()).copyOf(length)
    }

    /** True when [tag] is [nodeId]'s tag in the previous, current or next slot (clocks differ). No early exit. */
    fun matches(discoveryKey: ByteArray, nodeId: String, tag: ByteArray, nowMs: Long): Boolean {
        if (tag.size !in MIN_TAG_BYTES..16) return false
        val s = slot(nowMs)
        var ok = false
        for (d in -1L..1L) ok = Crypto.equal(tag(discoveryKey, nodeId, s + d, tag.size), tag) or ok
        return ok
    }

    /** Which of the known node ids advertised [tag], if any. */
    fun recognise(discoveryKey: ByteArray, tag: ByteArray, nodeIds: Collection<String>, nowMs: Long): String? {
        var found: String? = null
        for (id in nodeIds) if (matches(discoveryKey, id, tag, nowMs)) found = id
        return found
    }
}
