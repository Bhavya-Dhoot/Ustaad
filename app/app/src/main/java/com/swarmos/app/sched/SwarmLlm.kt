package com.swarmos.app.sched

import com.swarmos.app.llm.ChatChunk
import com.swarmos.app.llm.ChatRequest
import com.swarmos.app.llm.ChatResult
import com.swarmos.app.llm.LlmClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap

class NoPhoneAvailableException(pinned: String?) :
    RuntimeException(if (pinned == null) "no phone in the swarm can take the job" else "no phone in the swarm runs model $pinned")

class PhoneTimeoutException(val nodeId: String, val afterMs: Long) :
    RuntimeException("phone $nodeId gave no answer within $afterMs ms")

/** A stream failed after tokens were already delivered, so it cannot be retried on another phone. */
private class StreamBrokenException(override val cause: Throwable) : RuntimeException(cause)

/** The phone stopped answering its pings while our job ran on it. */
class PhoneAbortedException(val nodeId: String) : IOException("phone $nodeId stopped answering")

/** What the UI shows for one phone; also the body of GET /v1/sched/phones. */
@Serializable
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
    private val probeEveryMs: Long = 1_000,
    /**
     * A phone that looks busy because of somebody else's job, or that missed a ping, is asked again this often instead of
     * every [capsMaxAgeMs]: a stale "busy" is exactly what would keep a ready phone idle.
     */
    private val suspectRecheckMs: Long = 250,
    /** While jobs run on a remote phone it is pinged this often; two missed pings abort its jobs so they move on. */
    private val heartbeatMs: Long = 1_000,
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
        var misses = 0
        /** Completed when the phone is declared down; attempts running on it stop waiting. Replaced when it answers again. */
        var abort = CompletableDeferred<Unit>()
    }

    private var heartbeat: Job? = null   // guarded by lock
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

    // channelFlow, because the attempt runs in a child coroutine (so a silent phone can be abandoned) and must not emit from there
    override fun chatStream(req: ChatRequest): Flow<ChatChunk> = channelFlow {
        val pin = pinnedModel
        val out = FlowCollector<ChatChunk> { chunk -> send(chunk) }
        val local = endpoints().firstOrNull { it.isLocal }
        if (local != null && local.streamIfFree(req, pin, out)) return@channelFlow
        // no free slot here: stream from the best free phone, or take its whole reply when it cannot stream
        val jobId = newJobId()
        val emitted = AtomicBoolean(false)
        val tracked = FlowCollector<ChatChunk> { chunk ->
            emitted.set(true)
            send(chunk)
        }
        dispatch(pin, STREAM_TIMEOUT_MS) { ep ->
            try {
                if (!ep.streamIfFree(req, pin, tracked)) {
                    val r = ep.runChat(jobId, req, pin)
                    if (r.text.isNotEmpty()) tracked.emit(ChatChunk(r.text))
                    tracked.emit(ChatChunk("", done = true, stats = r.stats.toStats()))
                }
            } catch (e: BusyException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // once tokens reached the caller another phone cannot continue the reply: pass the failure on
                throw if (emitted.get()) StreamBrokenException(e) else e
            }
        }
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

    /**
     * Sum of the slots of every registered phone that can take chat jobs, including a phone that is down right now
     * (it may come back mid-batch and should find a worker waiting); at least 1.
     */
    suspend fun slotCount(pinModel: String? = pinnedModel): Int {
        refreshCaps()
        val eps = endpoints()
        return synchronized(lock) {
            eps.sumOf { ep ->
                val c = states[ep.nodeId]?.caps
                if (c == null) 1 else if (Scheduler.eligible(c, pinModel)) c.capacity else 0
            }
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
                    val maxAge = if (st.misses > 0 || busyWithOthers(st)) suspectRecheckMs else capsMaxAgeMs
                    !st.down && (force || ep.isLocal || t - st.capsAt >= maxAge)
                }
            }
            if (stale.isNotEmpty()) coroutineScope { stale.map { async { fetch(it) } }.awaitAll() }
        }
    }

    private suspend fun fetch(ep: PhoneEndpoint) {
        val inflightBefore = synchronized(lock) { states.getOrPut(ep.nodeId) { State() }.inflight }
        val caps = if (ep.isLocal) runCatching { ep.caps() }.getOrNull()
        else withTimeoutOrNull(capsTimeoutMs) { runCatching { ep.caps() }.getOrNull() }
        synchronized(lock) {
            val st = states.getOrPut(ep.nodeId) { State() }
            st.capsAt = clock()
            if (caps != null) {
                st.caps = caps
                // the report was taken somewhere between asking and answering: whichever of our jobs were there at either end are ours
                st.mineAtSample = maxOf(inflightBefore, st.inflight)
                st.down = false
                st.misses = 0
                if (st.abort.isCompleted) st.abort = CompletableDeferred()
            } else {
                st.misses++
                // one missed ping is not a verdict (the phone may be decoding with every core); two in a row are
                if (st.down || st.misses >= MISS_LIMIT) markDownLocked(st)
            }
        }
        changes.tryEmit(Unit)
    }

    /** The last report says jobs we did not send occupy the phone's slots. That is the state that goes stale fastest. */
    private fun busyWithOthers(st: State): Boolean = st.caps?.let { it.running - st.mineAtSample > 0 } == true

    private fun markDownLocked(st: State) {
        st.down = true
        st.nextProbeAt = clock() + probeEveryMs
        st.abort.complete(Unit)
    }

    /** While any job runs on a remote phone, ping that phone; a phone that stops answering has its jobs aborted at once. */
    private fun ensureHeartbeat() {
        synchronized(lock) {
            if (heartbeat?.isActive == true) return
            heartbeat = scope.launch {
                while (true) {
                    delay(heartbeatMs)
                    val eps = endpoints()
                    val busy = synchronized(lock) {
                        val remote = eps.filter { !it.isLocal && (states[it.nodeId]?.inflight ?: 0) > 0 }
                        if (remote.isEmpty() && states.values.none { it.inflight > 0 }) {
                            heartbeat = null
                            return@launch
                        }
                        remote
                    }
                    coroutineScope { busy.map { async { fetch(it) } }.awaitAll() }
                }
            }
        }
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

    private suspend fun <T : Any> dispatch(pin: String?, timeoutMs: Long = attemptTimeoutMs, call: suspend (PhoneEndpoint) -> T): T {
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
            val abort = synchronized(lock) { states[ep.nodeId]!!.abort }
            if (!ep.isLocal) ensureHeartbeat()
            try {
                return attempt(ep, abort, timeoutMs, call)
            } catch (e: BusyException) {
                synchronized(lock) {
                    states[ep.nodeId]?.let { it.busyUntil = clock() + busyBackoffMs; it.capsAt = 0 }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: StreamBrokenException) {
                throw e.cause
            } catch (e: Exception) {
                lastError = e
                tried += ep.nodeId
                synchronized(lock) {
                    val st = states[ep.nodeId]
                    if (st != null) {
                        // only a lost connection or silence says the phone is gone; an error answer says the job (or its model) failed
                        if (e is PhoneTimeoutException || e is IOException) {
                            markDownLocked(st)
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

    /** One try on one phone. Gives up when the phone is declared down meanwhile or [attemptTimeoutMs] passes. */
    private suspend fun <T : Any> attempt(ep: PhoneEndpoint, abort: CompletableDeferred<Unit>, timeoutMs: Long, call: suspend (PhoneEndpoint) -> T): T {
        val r = withTimeoutOrNull(timeoutMs) {
            if (ep.isLocal) {
                call(ep)
            } else {
                coroutineScope {
                    val work = async { call(ep) }
                    select<T> {
                        work.onAwait { it }
                        abort.onAwait {
                            work.cancel()
                            throw PhoneAbortedException(ep.nodeId)
                        }
                    }
                }
            }
        }
        return r ?: throw PhoneTimeoutException(ep.nodeId, timeoutMs)
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
        const val STREAM_TIMEOUT_MS = 10 * 60_000L
        const val MISS_LIMIT = 2
    }
}
