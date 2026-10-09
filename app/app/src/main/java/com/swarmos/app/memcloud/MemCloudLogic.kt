package com.swarmos.app.memcloud

/**
 * The decisions of the MemoryCloud state machine as pure functions, so they are tested without a phone and work for
 * any number of peers (two or three phones in total, or more): nothing here names a phone or assumes a count.
 */
object MemCloudLogic {
    private const val MIB = 1024f * 1024f

    /** Phases in which a lost phone matters: the split is loading or serving. */
    fun isActive(phase: Phase): Boolean = phase == Phase.LOADING || phase == Phase.READY

    /** [participants] (node ids in plan order) that are not in [online]: three missed heartbeats or gone from the registry. */
    fun lostParticipants(participants: List<String>, online: Set<String>): List<String> = participants.filter { it !in online }

    /** Who a re-plan loads on: the phones of the last plan that are still online, or [requested] when given (offline ones dropped). */
    fun replanPeers(previous: List<String>, online: List<String>, requested: List<String> = emptyList()): List<String> {
        val pool = if (requested.isEmpty()) previous else requested
        return pool.filter { it in online }
    }

    /** Share (0..1) of a phone's planned weights that its rpc-server has received; null when nothing is known yet. */
    fun loadFraction(receivedMb: Long?, weightBytes: Long): Float? {
        if (receivedMb == null || weightBytes <= 0) return null
        return (receivedMb * MIB / weightBytes).coerceIn(0f, 1f)
    }

    /** Percent of all remote weights that have arrived, weighted by each peer's planned bytes. */
    fun overallPercent(shares: List<ShareView>, phones: List<PhoneView>): Int {
        var total = 0.0
        var got = 0.0
        for (s in shares) {
            if (s.isDriver || s.layers == 0) continue
            val f = phones.firstOrNull { it.nodeId == s.nodeId }?.loadFraction ?: 0f
            total += s.weightMb
            got += s.weightMb * f
        }
        return if (total <= 0.0) 0 else (100.0 * got / total).toInt().coerceIn(0, 100)
    }

    /** Layers whose weights are in place, per layer in layer order: the strip hatches the rest. */
    fun loadedLayers(shares: List<ShareView>, phones: List<PhoneView>, ready: Boolean, nLayers: Int): List<Boolean> {
        val out = BooleanArray(nLayers)
        val remote = overallPercent(shares, phones) / 100f
        for (s in shares) {
            if (s.layers == 0) continue
            val f = when {
                ready -> 1f
                s.isDriver -> remote // the driver's own layers are read while it feeds the others: same pace
                else -> phones.firstOrNull { it.nodeId == s.nodeId }?.loadFraction ?: 0f
            }
            val n = (s.layers * f).toInt()
            for (i in 0 until s.layers) if (i < n) out[s.firstLayer + i] = true
        }
        return out.toList()
    }

    /**
     * DEGRADED(peer): the split state after [nodeId] ([name]) was lost, or null when no split is loading or serving
     * (a late report about a phone that no longer matters changes nothing).
     */
    fun degraded(s: MemCloudState, nodeId: String, name: String, reason: String, nowMs: Long): MemCloudState? {
        if (!isActive(s.phase)) return null
        return s.copy(
            phase = Phase.DEGRADED,
            lostPhone = name,
            failReason = reason,
            phones = s.phones.map { v -> if (v.nodeId == nodeId) v.copy(online = false, step = "Not answering: $reason", rttMs = null) else v },
            steps = s.steps + "$name lost ($reason): generation stopped, server killed",
            generation = s.generation?.let { g -> if (g.running) g.copy(running = false, error = "$name stopped answering") else g },
            updatedAtMs = nowMs,
        )
    }

    /** The line the whole phone shows while a split runs (PhoneActivity, notification, banner); null when nothing is going on. */
    fun activityText(s: MemCloudState): String? {
        val phones = s.shares.size
        val peers = s.phones.filter { !it.isDriver }.joinToString(" and ") { it.name }
        return when (s.phase) {
            Phase.PLANNING, Phase.STARTING_PEERS -> if (phones >= 2) "Preparing a large model across $phones phones." else "Preparing a large model across phones."
            Phase.LOADING -> "Loading a large model across $phones phones (${overallPercent(s.shares, s.phones)} percent)."
            Phase.READY -> "Running a large model with $peers."
            Phase.DEGRADED -> "A large model is paused: ${s.lostPhone ?: "a phone"} stopped answering."
            Phase.BASELINE -> "Trying a large model on this phone alone."
            Phase.IDLE, Phase.FAILED -> null
        }
    }

    /** Whether a new load may start from [phase] (the caller has already checked that no operation is running). */
    fun canStart(phase: Phase): Boolean = phase != Phase.READY && phase != Phase.DEGRADED

    /** Buttons the screen offers in DEGRADED: re-plan needs at least one phone left besides this one. */
    fun canReplan(remainingPeers: Int): Boolean = remainingPeers >= 1
}
