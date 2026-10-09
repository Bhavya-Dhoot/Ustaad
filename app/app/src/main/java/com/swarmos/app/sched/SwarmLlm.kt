package com.swarmos.app.sched

import com.swarmos.app.llm.ChatChunk
import com.swarmos.app.llm.ChatRequest
import com.swarmos.app.llm.ChatResult
import com.swarmos.app.llm.LlmClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.BufferOverflow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class NoPhoneAvailableException(pinned: String?) :
    RuntimeException(if (pinned == null) "no phone in the swarm can take the job" else "no phone in the swarm runs model $pinned")

class PhoneTimeoutException(val nodeId: String, val afterMs: Long) :
    RuntimeException("phone $nodeId gave no answer within $afterMs ms")

/** What the UI shows for one phone. */
data class PhoneView(
    val nodeId: String,
    val isLocal: Boolean,
    val up: Boolean,
    val caps: Caps?,
    /** Jobs this client currently has on the phone. */
    val inflight: Int,
    /** Jobs this client has completed on the phone since it started. */
    val jobsDone: Int,
)

/**
 * The whole swarm behind one [LlmClient].
 *
 * Each chat call goes to the best-scored ([Scheduler.score]) phone that has a free slot, this phone included. When every
 * phone is busy it waits. When a phone times out or the connection fails, the same jobId is retried on the next phone
 * (the phones deduplicate by jobId, and only one result is ever returned). Streaming runs on this phone when it has a
 * free slot and otherwise falls back to a non-streamed remote call. Embeddings stay on this phone unless it fails.
 */
class SwarmLlm(
    private val endpoints: () -> List<PhoneEndpoint>,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Longest a single attempt on one phone may take before the job moves to the next phone. */
    private val attemptTimeoutMs: Long = 75_000,
    /** Capability reports older than this are fetched again before a dispatch decision. */
    private val capsMaxAgeMs: Long = 2_000,
    private val capsTimeoutMs: Long = 1_500,
    /** How long a phone that answered 429 is skipped. */
    private val busyBackoffMs: Long = 200,
    /** How often a phone marked down is probed again. */
    private val probeEveryMs: Long = 3_000,
) : LlmClient {
    private class State {
        var caps: Caps? = null
        var capsAt = 0L
        var mineAtSample = 0
        var inflight = 0
        var jobsDone = 0
        var down = false
        var nextProbeAt = 0L
        var probing = false
        var busyUntil = 0L
    }

    private val lock = Any()
    private val states = ConcurrentHashMap<String, State>()
    private val refreshMutex = Mutex()
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** When set, only phones running this model take chat jobs. */
    @Volatile
    var pinnedModel: String? = null

    override val modelName: String
        get() {
            val models = synchronized(lock) { states.values.mapNotNull { it.caps?.modelName }.distinct() }
            return when (models.size) {
                0 -> "swarm"
                1 -> models[0]
                else -> "swarm: " + models.joinToString(", ")
            }
        }

    override suspend fun ready(): Boolean {
        refreshCaps(force = true)
        return synchronized(lock) { states.values.any { !it.down && it.caps != null } }
    }

    // ---- LlmClient ------------------------------------------------------------------------------------------

    override suspend fun chat(req: ChatRequest): ChatResult {
        val r = chatJob(newJobId(), req)
        return ChatResult(r.text, r.stats.toStats())
    }

    override fun chatStream(req: ChatRequest): Flow<ChatChunk> = flow {
        val local = endpoints().firstOrNull { it.isLocal }
        if (local != null && local.streamIfFree(req, pinnedModel, this)) return@flow
        val r = chatJob(newJobId(), req)
        if (r.text.isNotEmpty()) emit(ChatChunk(r.text))
        emit(ChatChunk("", done = true, stats = r.stats.toStats()))
    }

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        val jobId = newJobId()
        var last: Throwable? = null
        val ordered = endpoints().sortedBy { if (it.isLocal) 0 else 1 }
        for (ep in ordered) {
            if (!ep.isLocal && synchronized(lock) { states[ep.nodeId]?.down == true }) continue
            try {
                val r = withTimeoutOrNull(attemptTimeoutMs) { ep.runEmbed(jobId, texts) } ?: throw PhoneTimeoutException(ep.nodeId, attemptTimeoutMs)
                return r.vectors.map { it.toFloatArray() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: NoPhoneAvailableException(null)
    }

    // ---- Jobs with ids --------------------------------------------------------------------------------------

    /**
     * Runs one chat job somewhere in the swarm and says where. Calling again with the same [jobId] never runs the
     * job twice on the same phone, and a result is returned once.
     */
    suspend fun chatJob(jobId: String, req: ChatRequest, pinModel: String? = pinnedModel): ChatJobResult {
        val r = dispatch(pinModel) { ep -> ep.runChat(jobId, req, pinModel) }
        synchronized(lock) {
            val st = states[r.nodeId]
            if (st != null) {
                st.jobsDone++
                val tps = r.stats.decodeTokPerSec
                if (tps > 0.0) st.caps = st.caps?.copy(lastDecodeTokPerSec = tps)
            }
        }
        return r
    }

    /** Sum of the slots of every reachable phone that can take chat jobs; at least 1. */
    suspend fun slotCount(pinModel: String? = pinnedModel): Int {
        refreshCaps()
        return synchronized(lock) {
            states.values.filter { !it.down }.mapNotNull { it.caps }
                .filter { Scheduler.eligible(it, pinModel) }.sumOf { it.capacity }
        }.coerceAtLeast(1)
    }

    // ---- Capability cache -----------------------------------------------------------------------------------

    /** Fetches the capability report of every phone whose cached report is stale. Down phones are probed in the background. */
    suspend fun refreshCaps(force: Boolean = false) {
        val eps = endpoints()
        val now = clock()
        val probes = ArrayList<PhoneEndpoint>()
        synchronized(lock) {
            for (ep in eps) {
                val st = states.getOrPut(ep.nodeId) { State() }
                if (st.down && !st.probing && now >= st.nextProbeAt) {
                    st.probing = true
                    probes += ep
                }
            }
        }
        for (ep in probes) scope.launch { fetch(ep); synchronized(lock) { states[ep.nodeId]?.probing = false } }

        refreshMutex.withLock {
            val t = clock()
            val stale = eps.filter { ep ->
                synchronized(lock) {
                    val st = states.getOrPut(ep.nodeId) { State() }
                    !st.down && (force || ep.isLocal || t - st.capsAt >= capsMaxAgeMs)
                }
            }
            if (stale.isNotEmpty()) coroutineScope { stale.map { async { fetch(it) } }.awaitAll() }
        }
    }

    private suspend fun fetch(ep: PhoneEndpoint) {
        val caps = if (ep.isLocal) runCatching { ep.caps() }.getOrNull()
        else withTimeoutOrNull(capsTimeoutMs) { runCatching { ep.caps() }.getOrNull() }
        synchronized(lock) {
            val st = states.getOrPut(ep.nodeId) { State() }
            st.capsAt = clock()
            if (caps != null) {
                st.caps = caps
                st.mineAtSample = st.inflight
                st.down = false
            } else {
                st.down = true
                st.nextProbeAt = clock() + probeEveryMs
            }
        }
        changes.tryEmit(Unit)
    }

    /** Snapshot for the UI, local phone first. */
    fun phoneViews(): List<PhoneView> {
        val eps = endpoints()
        return synchronized(lock) {
            eps.sortedBy { if (it.isLocal) 0 else 1 }.map { ep ->
                val st = states[ep.nodeId]
                PhoneView(ep.nodeId, ep.isLocal, st != null && !st.down && st.caps != null, st?.caps, st?.inflight ?: 0, st?.jobsDone ?: 0)
            }
        }
    }

    // ---- Dispatch -------------------------------------------------------------------------------------------

    private suspend fun <T : Any> dispatch(pin: String?, call: suspend (PhoneEndpoint) -> T): T {
        val tried = HashSet<String>()
        var lastError: Throwable? = null
        while (true) {
            currentCoroutineContext().ensureActive()
            refreshCaps()
            val eps = endpoints()
            val ep = synchronized(lock) { pickAndReserveLocked(eps, pin, tried) }
            if (ep == null) {
                if (!anyServable(eps, pin, tried)) throw lastError ?: NoPhoneAvailableException(pin)
                waitForChange()
                continue
            }
            try {
                val r = withTimeoutOrNull(attemptTimeoutMs) { call(ep) } ?: throw PhoneTimeoutException(ep.nodeId, attemptTimeoutMs)
                return r
            } catch (e: BusyException) {
                synchronized(lock) {
                    states[ep.nodeId]?.let { it.busyUntil = clock() + busyBackoffMs; it.capsAt = 0 }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                tried += ep.nodeId
                synchronized(lock) {
                    val st = states[ep.nodeId]
                    if (st != null) {
                        // only a lost connection or silence says the phone is gone; an error answer says the job (or its model) failed
                        if (e is PhoneTimeoutException || e is IOException) {
                            st.down = true
                            st.nextProbeAt = clock() + probeEveryMs
                        } else {
                            st.capsAt = 0
                        }
                    }
                }
            } finally {
                synchronized(lock) { states[ep.nodeId]?.let { it.inflight-- } }
                changes.tryEmit(Unit)
            }
        }
    }

    private suspend fun waitForChange() {
        withTimeoutOrNull(POLL_MS) { changes.first() }
    }

    /** Picks the best-scored phone with a free slot and counts the job against it before returning. */
    private fun pickAndReserveLocked(eps: List<PhoneEndpoint>, pin: String?, tried: Set<String>): PhoneEndpoint? {
        val now = clock()
        var best: PhoneEndpoint? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (ep in eps) {
            if (ep.nodeId in tried) continue
            val st = states[ep.nodeId] ?: continue
            val caps = st.caps ?: continue
            if (st.down || st.busyUntil > now) continue
            val s = Scheduler.score(caps, pin)
            if (s == Double.NEGATIVE_INFINITY) continue
            if (freeSlots(st, caps, ep.isLocal) <= 0) continue
            val better = best == null || s > bestScore ||
                (s == bestScore && ep.isLocal && !best.isLocal) ||
                (s == bestScore && ep.isLocal == best.isLocal && ep.nodeId < best.nodeId)
            if (better) {
                best = ep
                bestScore = s
            }
        }
        best?.let { states[it.nodeId]!!.inflight++ }
        return best
    }

    /** Free slots, counting this client's own jobs immediately and other callers' jobs from the last report. */
    private fun freeSlots(st: State, caps: Caps, local: Boolean): Int {
        val used = if (local) maxOf(caps.running, st.inflight)
        else st.inflight + maxOf(0, caps.running - st.mineAtSample)
        return caps.capacity - used
    }

    /** True when some phone that has not failed this job could still take it once it frees up. */
    private fun anyServable(eps: List<PhoneEndpoint>, pin: String?, tried: Set<String>): Boolean = synchronized(lock) {
        eps.any { ep ->
            val st = states[ep.nodeId]
            ep.nodeId !in tried && st != null && !st.down && st.caps?.let { Scheduler.eligible(it, pin) } == true
        }
    }

    private companion object {
        const val POLL_MS = 100L
    }
}
