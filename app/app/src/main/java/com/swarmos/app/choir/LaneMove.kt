package com.swarmos.app.choir

/**
 * Which phone takes over when the phone that had a lane's work, or the judge's, has left or failed. Pure: the phones, who is up,
 * who already has which lane's work, and the time left go in; a phone or nobody comes out.
 */
object LaneMove {
    /** The judge's work is a lane of its own for this rule. */
    const val JUDGE = "judge"

    /** Under this much time left nobody is picked: one model call cannot finish in it. A floor chosen by hand, not measured on the phones. */
    const val MIN_LEFT_MS = 3_000L

    /** A phone that could take the work; [up] is false for one that is away or has no chat model. */
    data class Device(val nodeId: String, val name: String, val up: Boolean)

    /** [nodeId] was given the work of lane [laneId]; [running] while it is still at it. A phone that left keeps its entry. */
    data class Held(val nodeId: String, val laneId: String, val running: Boolean)

    /**
     * The first phone that is up and has not had [laneId]'s work, taking the one with the fewest lanes running now, then the
     * fewest held at all (a phone that came back and holds none comes first), then the earlier in [devices]. Null when less than
     * [minLeftMs] is left or nobody qualifies. A phone that left and came back still has its old lane's work, so it is never
     * handed a second copy of it.
     */
    fun pick(devices: List<Device>, held: List<Held>, laneId: String, timeLeftMs: Long, minLeftMs: Long = MIN_LEFT_MS): Device? {
        if (timeLeftMs < minLeftMs) return null
        val had = held.filter { it.laneId == laneId }.map { it.nodeId }.toSet()
        return devices.filter { it.up && it.nodeId !in had }
            .sortedWith(compareBy({ d -> held.count { it.nodeId == d.nodeId && it.running } }, { d -> held.count { it.nodeId == d.nodeId } }))
            .firstOrNull()
    }
}
