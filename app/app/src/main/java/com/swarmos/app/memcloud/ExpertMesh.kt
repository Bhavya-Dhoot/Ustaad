package com.swarmos.app.memcloud

/**
 * ExpertMesh arithmetic. Inputs are the model file (layer count, experts) and live measurements (round-trip time of
 * the link); nothing is assumed.
 *
 * Spreading the experts of one layer over several phones would send the hidden state to a remote phone and wait for the
 * answer in every MoE layer of every token: one round trip per MoE layer. Putting whole layer ranges on one phone
 * crosses a phone boundary only where the range changes.
 */
object ExpertMesh {
    /** Time per token spent waiting for the network if every MoE layer needed one round trip, in ms. */
    fun remoteExpertMsPerToken(moeLayers: Int, rttMs: Double): Double? =
        if (moeLayers > 0 && rttMs > 0.0) moeLayers * rttMs else null

    /** Tokens per second that remote experts could not exceed even with zero compute: 1000 / (layers x RTT). */
    fun remoteExpertCeilingTokPerSec(moeLayers: Int, rttMs: Double): Double? =
        remoteExpertMsPerToken(moeLayers, rttMs)?.let { 1000.0 / it }

    /** Network wait per token with layer ranges: one round trip per boundary the token crosses. */
    fun layerRangeMsPerToken(boundaries: Int, rttMs: Double): Double? =
        if (boundaries > 0 && rttMs > 0.0) boundaries * rttMs else null

    /** Experts that live on one phone: its layers times the experts per layer. */
    fun expertsOnPhone(layers: Int, expertsPerLayer: Int): Long = layers.toLong() * expertsPerLayer

    /** Mean of the positive samples (heartbeat round trips of the phones in the split), null when there are none. */
    fun meanRtt(samples: List<Double?>): Double? =
        samples.filterNotNull().filter { it > 0.0 }.takeIf { it.isNotEmpty() }?.average()
}
