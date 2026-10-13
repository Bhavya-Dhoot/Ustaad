package com.swarmos.app.memcloud

import com.swarmos.app.link.LinkRoute

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

    /** True when a refusal from a lender says its rpc port is still held ("port 50052 is still in use after two retries ..."). */
    fun portInUse(reason: String?): Boolean = reason != null && reason.contains("in use", ignoreCase = true)

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
            Phase.PLANNING, Phase.STARTING_PEERS -> if (phones >= 2) "Preparing a large model across ${plural(phones, "phone")}." else "Preparing a large model across phones."
            Phase.LOADING -> "Loading a large model across ${plural(phones, "phone")} (${overallPercent(s.shares, s.phones)} percent)."
            Phase.READY -> "Running a large model with $peers."
            Phase.DEGRADED -> if (s.released) null else "A large model is paused: ${s.lostPhone ?: "a phone"} stopped answering."
            Phase.BASELINE -> "Trying a large model on this phone alone."
            Phase.IDLE, Phase.FAILED -> null
        }
    }

    /** The sentence shown when the model would run on this phone alone: splitting it then costs speed and proves only the mechanism. */
    const val SPLIT_COSTS_SPEED = "This model fits on one phone. Splitting it is slower than running it alone; it is shown only to prove the mechanism."

    /** True when the whole file plus the planning overhead fits in what this phone can give ([haveMb], after its own servers stop). */
    fun fitsOnOnePhone(fileBytes: Long, haveMb: Long): Boolean = fileBytes / (1024 * 1024) + SplitPlanner.OVERHEAD_MB <= haveMb

    /** "1 phone", "2 phones"; [many] defaults to the one-form plus "s". */
    fun plural(n: Int, one: String, many: String = one + "s"): String = "$n ${if (n == 1) one else many}"

    /** Caption of a lane: short enough never to clip on a phone screen. */
    fun laneRole(isDriver: Boolean): String = if (isDriver) "this phone, driver" else "worker"

    /**
     * The GGUF `general.name` as a secondary line under the file name, only when it says something the file name does not:
     * at least four characters and not already contained in the file name ("Hf" and "Qwen3.5-4B" for Qwen3.5-4B-Q4_K_M.gguf are hidden).
     */
    fun secondaryName(fileName: String, ggufName: String?): String? {
        val n = ggufName?.trim().orEmpty()
        if (n.length < 4) return null
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        return if (norm(fileName.removeSuffix(".gguf")).contains(norm(n))) null else n
    }

    /** Subtitle under the title: what the planner says about the selected model on this phone. Null [fitsAlone] = no plan yet. */
    fun subtitle(fitsAlone: Boolean?): String = when (fitsAlone) {
        false -> "A model too big for one phone, split by layers."
        true -> "Fits this phone; splitting it is slower and shown only to prove the mechanism."
        null -> "A model split by layers across phones."
    }

    /** Text of the load button for [selectedPeers] other phones, and whether it can be pressed. */
    fun loadButton(selectedPeers: Int): Pair<String, Boolean> =
        if (selectedPeers < 1) "Pick at least one other phone" to false else "Load across ${plural(selectedPeers + 1, "phone")}" to true

    /** Accessibility text of the layer strip in the plan: what is planned, and that nothing is loaded yet. */
    fun planDescription(shares: List<ShareView>): String =
        "Plan, no layer loaded yet: " + shares.filter { it.layers > 0 }.joinToString(", ") { "${it.name} layers ${it.range}" }

    /** Whether a new load may start from [phase] (the caller has already checked that no operation is running). */
    fun canStart(phase: Phase): Boolean = phase != Phase.READY && phase != Phase.DEGRADED

    /**
     * Whether MemoryCloud is using this phone's model servers or memory in state [s]. FAILED is not: a failed start or run ends
     * with the same teardown as stop, and the phase then stays FAILED until the owner starts again. DEGRADED is not once it has
     * [MemCloudState.released] the phone (the automatic restore ran the same teardown); a re-plan takes the phone again.
     */
    fun holdsPhone(s: MemCloudState): Boolean = when (s.phase) {
        Phase.IDLE, Phase.FAILED -> false
        Phase.DEGRADED -> !s.released
        else -> true
    }

    /**
     * The wait before the next round of giving peers their models back: [baseMs] after a round that made progress, double the last wait
     * (at most [maxMs]) after a round in which calls were made and failed (5, 10, 20, 40, 60, 60 s), unchanged when nothing was called
     * (every peer was away or busy in a split).
     */
    fun nextRestoreWait(currentMs: Long, progressed: Boolean, attempted: Boolean, baseMs: Long, maxMs: Long): Long = when {
        progressed -> baseMs
        attempted -> (currentMs * 2).coerceAtMost(maxMs)
        else -> currentMs
    }

    /**
     * Memory is lent over the local link only, as a rule of its own. Why this phone may not lend to the driver at [driverAddr] with rpc-server bound
     * to [bind], or null when it may: [bind] must be the local address the request arrived on ([arrivedOn]), that address must be one of this phone's
     * Wi-Fi or Wi-Fi Direct addresses ([local]), and the driver must be on that address's network. [peerIsLocalNode]: the driver is a device of this
     * local network (true for now; a later package that knows device kinds feeds it, so an away client never borrows memory).
     */
    fun lendRefusal(bind: String, arrivedOn: String?, driverAddr: String?, local: List<LinkAddr>, peerIsLocalNode: Boolean = true): String? {
        if (!peerIsLocalNode) return "the driver is not a device of this local network, so this phone lends it no memory"
        if (arrivedOn.isNullOrBlank()) return "this phone cannot tell which link the request arrived on, so it lends no memory"
        if (bind != arrivedOn) return "rpc-server is bound only to the address the request arrived on ($arrivedOn), not to $bind"
        val link = local.firstOrNull { it.address == arrivedOn } ?: return "$arrivedOn is not a Wi-Fi or Wi-Fi Direct address of this phone"
        if (driverAddr.isNullOrBlank() || !LinkRoute.isLocalAddress(driverAddr)) {
            return "the driver's address (${driverAddr ?: "unknown"}) is not on a local network; memory is lent only over the local link"
        }
        if (!LinkRoute.sameSubnet(link.address, driverAddr, link.prefix)) {
            return "the driver ($driverAddr) is not on the same ${if (link.direct) "Wi-Fi Direct" else "Wi-Fi"} network as this phone ($arrivedOn); memory is lent only over the local link"
        }
        return null
    }

    fun mayLend(bind: String, arrivedOn: String?, driverAddr: String?, local: List<LinkAddr>, peerIsLocalNode: Boolean = true): Boolean =
        lendRefusal(bind, arrivedOn, driverAddr, local, peerIsLocalNode) == null

    /** Why the phone [name] at [host] is left out of a plan, or null: it must be on a Wi-Fi or Wi-Fi Direct network this phone is on (and a device of it). */
    fun leftOutReason(name: String, host: String, local: List<LinkAddr>, peerIsLocalNode: Boolean = true): String? = when {
        !peerIsLocalNode -> "$name is not a device of this local network, so it takes no part"
        !LinkRoute.isLocalAddress(host) || local.none { LinkRoute.sameSubnet(it.address, host, it.prefix) } ->
            "$name ($host) is not on a Wi-Fi or Wi-Fi Direct network this phone is on, so it takes no part: memory is shared over the local link only"
        else -> null
    }

    /** Buttons the screen offers in DEGRADED: re-plan needs at least one phone left besides this one. */
    fun canReplan(remainingPeers: Int): Boolean = remainingPeers >= 1
}
