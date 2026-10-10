package com.swarmos.app.sched

import com.swarmos.app.core.ThermalLevel
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

class NoPhoneAvailableException(pinned: String?, tooHot: Boolean = false) :
    RuntimeException(
        when {
            tooHot -> "phone too hot: no phone in the swarm can take the job until one cools down"
            pinned == null -> "no phone in the swarm can take the job"
            else -> "no phone in the swarm runs model $pinned"
        },
    )

class PhoneTimeoutException(val nodeId: String, val afterMs: Long) :
    RuntimeException("phone $nodeId gave no answer within $afterMs ms")

/** Why a phone that is up still takes no model jobs or streams. Not an I/O failure: the phone is fine, it is just not a target. */
class PhoneUnusableException(message: String) : RuntimeException(message)

/** Shown in the phone list for a phone that is reachable only over Bluetooth, which carries small control messages and no model jobs. */
const val BLUETOOTH_ONLY = "reachable over Bluetooth only: model jobs and streams need Wi-Fi"

/** No phone had a free slot within the time a call may wait for one (twice its generation budget). */
class SwarmWaitTimeoutException(val waitedMs: Long) :
    RuntimeException("no phone in the swarm had a free slot within ${waitedMs / 1000} s")

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
    /** Why this phone takes no model jobs although it is reachable (for example Bluetooth only); null when it does. */
    val note: String? = null,
)

/**
 * The whole swarm behind one [LlmClient].
 *
 * Each chat call goes to the best-scored ([Scheduler.score]) phone that has a free slot, this phone included. When every
 * phone is busy it waits (at most twice the job's generation budget). When a phone times out or the connection fails, the
 * same jobId is sent once more to that phone if it still answers its pings (a phone that finished keeps its result for 10
 * minutes), and then to the next phone. A phone runs a jobId at most once, but a reply lost after a phone finished can make
 * another phone run the job again, and its answer may differ. Streaming runs on this phone when it has a free slot and
 * otherwise falls back to a non-streamed remote call. Embeddings stay on this phone unless it fails.
 */
class SwarmLlm(
    private val endpoints: () -> List<PhoneEndpoint>,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Fixed part of the longest a single chat attempt on one phone may take before the job moves to the next phone; the full
     * budget adds [msPerToken] for every token the request may generate ([GenBudget]). Silence is detected by pings, not by this.
     */
    private val attemptTimeoutMs: Long = GenBudget.BASE_MS,
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
    /** Generation allowance per requested token in a chat attempt's time budget. */
    private val msPerToken: Long = GenBudget.MS_PER_TOKEN,
) : LlmClient {
    private class State {
        var caps: Caps? = null
        var capsAt = 0L
        /** The thermal status to steer by: the reading, with hysteresis once the phone was excluded. */
        val thermal = ThermalGate()
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
        // a local stream bypasses ranking, so it gets the same heat rule: from status 4 it starts only when no other phone could take it
        if (local != null && localMayStream(local, pin) && local.streamIfFree(req, pin, out)) return@channelFlow
        // no free slot here: stream from the best free phone, or take its whole reply when it cannot stream
        val jobId = newJobId()
        val emitted = AtomicBoolean(false)
        val tracked = FlowCollector<ChatChunk> { chunk ->
            emitted.set(true)
            send(chunk)
        }
        dispatch(pin, STREAM_TIMEOUT_MS, committed = { emitted.get() }) { ep ->
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
            if (ep.unusableReason != null || (!ep.isLocal && synchronized(lock) { states[ep.nodeId]?.down == true })) continue
            try {
                val r = withTimeoutOrNull(EMBED_TIMEOUT_MS) { ep.runEmbed(jobId, texts) } ?: throw PhoneTimeoutException(ep.nodeId, EMBED_TIMEOUT_MS)
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
     * Runs one chat job somewhere in the swarm and says where. Calling again with the same [jobId] runs the job at most
     * once on the same phone and returns one result; a reply lost after a phone finished can still make another phone run
     * it again. [timing], when given, learns how long the job waited before a phone was picked for it.
     */
    suspend fun chatJob(jobId: String, req: ChatRequest, pinModel: String? = pinnedModel, timing: JobTiming? = null): ChatJobResult {
        val r = dispatch(pinModel, GenBudget.forTokens(req.maxTokens, attemptTimeoutMs, msPerToken), timing) { ep -> ep.runChat(jobId, req, pinModel) }
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
     * (it may come back mid-batch and should find a worker waiting); at least 1. With [includeDown] false a phone that is down
     * counts 0: the number of jobs that can run right now.
     */
    suspend fun slotCount(pinModel: String? = pinnedModel, includeDown: Boolean = true): Int {
        refreshCaps()
        val eps = endpoints()
        return synchronized(lock) {
            eps.sumOf { ep ->
                val st = states[ep.nodeId]
                val c = st?.caps
                if (ep.unusableReason != null) 0
                else if (!includeDown && st?.down == true) 0
                else if (c == null) 1
                else if (Scheduler.eligible(c, pinModel, st.thermal.level)) c.capacity
                else 0
            }
        }.coerceAtLeast(1)
    }

    // ---- Capability cache -----------------------------------------------------------------------------------

    /** Fetches the capability report of every phone whose cached report is stale. Down phones are probed in the background. */
    suspend fun refreshCaps(force: Boolean = false) {
        launchProbes()
        refreshMutex.withLock { fetchStale(force) }
    }

    /**
     * A dispatch decision uses the cached reports; stale ones are refreshed in the background, one refresh at a time, so a phone
     * that misses its ping (1.5 s) never stalls the callers waiting to dispatch. The first dispatch, with nothing cached, waits.
     */
    private suspend fun refreshForDispatch() {
        val eps = endpoints()
        val cold = synchronized(lock) { eps.none { states[it.nodeId]?.caps != null } }
        if (cold) {
            refreshCaps()
            return
        }
        eps.firstOrNull { it.isLocal }?.let { fetch(it) }   // in-process and free: this phone's slots are always read fresh
        launchProbes()
        if (!refreshMutex.tryLock()) return                  // a refresh is already running
        scope.launch {
            try {
                fetchStale(force = false)
            } finally {
                refreshMutex.unlock()
            }
        }
    }

    /** Starts a probe of every phone marked down whose time has come. */
    private fun launchProbes() {
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
        for (ep in probes) {
            scope.launch {
                try {
                    fetch(ep)
                } finally {
                    synchronized(lock) { states[ep.nodeId]?.probing = false }
                }
            }
        }
    }

    private suspend fun fetchStale(force: Boolean) {
        val eps = endpoints()
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

    /** A caller going away is not a missed ping: its cancellation is passed on, everything else the phone does wrong is "no answer". */
    private suspend fun capsOrNull(ep: PhoneEndpoint): Caps? {
        suspend fun ask(): Caps? = try {
            ep.caps()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return if (ep.isLocal) ask() else withTimeoutOrNull(capsTimeoutMs) { ask() }
    }

    /**
     * Asks [ep] for its report and files it. Wakes the dispatchers that wait for a slot only when something they decide on
     * changed (slots, running jobs, up or down, the thermal level): an unconditional wake-up made two waiting dispatchers wake
     * each other in a loop.
     */
    private suspend fun fetch(ep: PhoneEndpoint) {
        val inflightBefore = synchronized(lock) { states.getOrPut(ep.nodeId) { State() }.inflight }
        val caps = capsOrNull(ep)
        var changed = false
        synchronized(lock) {
            val st = states.getOrPut(ep.nodeId) { State() }
            val before = st.caps
            val wasDown = st.down
            val levelBefore = st.thermal.level
            st.capsAt = clock()
            if (caps != null) {
                st.caps = caps
                st.thermal.update(caps.thermalStatus, clock())
                // the report was taken somewhere between asking and answering: whichever of our jobs were there at either end are ours
                st.mineAtSample = maxOf(inflightBefore, st.inflight)
                st.down = false
                st.misses = 0
                if (st.abort.isCompleted) st.abort = CompletableDeferred()
                changed = wasDown || before == null || before.capacity != caps.capacity || before.running != caps.running ||
                    levelBefore != st.thermal.level
            } else {
                st.misses++
                // one missed ping is not a verdict (the phone may be decoding with every core); two in a row are
                if (st.down || st.misses >= MISS_LIMIT) markDownLocked(st)
                changed = st.down != wasDown
            }
        }
        if (changed) changes.tryEmit(Unit)
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
                val note = ep.unusableReason
                // a phone that takes no model work is listed as not up for it, with the reason beside it
                PhoneView(ep.nodeId, ep.isLocal, note == null && st != null && !st.down && st.caps != null, st?.caps, st?.inflight ?: 0, st?.jobsDone ?: 0, note)
            }
        }
    }

    // ---- Dispatch -------------------------------------------------------------------------------------------

    /**
     * Picks a phone and runs [call] on it, moving on to the next phone when it fails. [timeoutMs] is the time budget of one
     * attempt; the whole call, waiting for a free slot included, gives up after twice that. [committed] says that part of the
     * reply already reached the caller, after which nothing is retried: another phone cannot continue a reply.
     */
    private suspend fun <T : Any> dispatch(
        pin: String?,
        timeoutMs: Long = attemptTimeoutMs,
        timing: JobTiming? = null,
        committed: () -> Boolean = { false },
        call: suspend (PhoneEndpoint) -> T,
    ): T {
        val startedAt = clock()
        val deadline = startedAt + 2 * timeoutMs
        val tried = HashSet<String>()
        val resent = HashSet<String>()
        var lastError: Throwable? = null
        var picked = false
        while (true) {
            currentCoroutineContext().ensureActive()
            refreshForDispatch()
            val eps = endpoints()
            val ep = synchronized(lock) { pickAndReserveLocked(eps, pin, tried) }
            if (ep == null) {
                if (!anyServable(eps, pin, tried)) throw lastError ?: NoPhoneAvailableException(pin, tooHot = allUpPhonesTooHot(eps))
                if (clock() >= deadline) throw lastError ?: SwarmWaitTimeoutException(clock() - startedAt)
                waitForChange()
                continue
            }
            if (!picked) {
                picked = true
                timing?.waitedMs = clock() - startedAt
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
                if (committed()) throw e   // tokens already reached the caller: surface the break, do not start a second reply
                var failure: Exception = e
                // The phone may have finished and lost only the reply (or still be on the job): if it still answers its pings,
                // ask it once more for the same job id, which returns the stored result or shares the running job. Only then go elsewhere.
                if (!ep.isLocal && (e is PhoneTimeoutException || e is IOException) && resent.add(ep.nodeId) && stillAnswers(ep)) {
                    try {
                        // stillAnswers() may have brought the phone back up, and that replaces a fired abort signal with a new one:
                        // the signal read before the first try would end this try at once
                        val current = synchronized(lock) { states[ep.nodeId]?.abort } ?: abort
                        return attempt(ep, current, timeoutMs, call)
                    } catch (again: CancellationException) {
                        throw again
                    } catch (again: Exception) {
                        if (committed()) throw again
                        failure = again
                    }
                }
                lastError = failure
                tried += ep.nodeId
                synchronized(lock) {
                    val st = states[ep.nodeId]
                    if (st != null) {
                        // A lost connection says the phone is gone. Silence on one job does not: only pings decide that. An error
                        // answer says the job (or its model) failed.
                        if (failure is IOException) {
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
            if (ep.nodeId in tried || ep.unusableReason != null) continue
            val st = states[ep.nodeId] ?: continue
            val caps = st.caps ?: continue
            if (st.down || st.busyUntil > now) continue
            val s = Scheduler.score(caps, pin, st.thermal.level)
            if (s == Double.NEGATIVE_INFINITY) continue
            // status 3 takes a job only when no cooler phone is up to take it (status 4 never scores at all)
            if (st.thermal.level >= ThermalLevel.TOO_HOT_FROM && coolerPhoneUpLocked(eps, pin, tried, ep)) continue
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
            ep.nodeId !in tried && ep.unusableReason == null && st != null && !st.down &&
                st.caps?.let { Scheduler.eligible(it, pin, st.thermal.level) } == true
        }
    }

    /** Another phone that is up, runs the pinned model and is cooler than status 3: the reason a status-3 phone is passed over. */
    private fun coolerPhoneUpLocked(eps: List<PhoneEndpoint>, pin: String?, tried: Set<String>, except: PhoneEndpoint): Boolean =
        eps.any { o ->
            val so = if (o.nodeId == except.nodeId || o.nodeId in tried || o.unusableReason != null) null else states[o.nodeId]
            val c = so?.caps
            so != null && c != null && !so.down && so.thermal.level < ThermalLevel.TOO_HOT_FROM && Scheduler.eligible(c, pin, so.thermal.level)
        }

    /** True when at least one phone is up and every phone that is up is excluded for heat (status 4 or more). */
    private fun allUpPhonesTooHot(eps: List<PhoneEndpoint>): Boolean = synchronized(lock) {
        val up = eps.filter { it.unusableReason == null }.mapNotNull { ep -> states[ep.nodeId]?.takeIf { !it.down && it.caps != null } }
        up.isNotEmpty() && up.all { it.thermal.level >= Scheduler.EXCLUDE_FROM_STATUS }
    }

    /**
     * Whether this phone may start a local stream: always, except from thermal status 4 when another phone is up and could take
     * it (the stream then goes through the normal dispatch, which does not pick a phone that hot).
     */
    private suspend fun localMayStream(local: PhoneEndpoint, pin: String?): Boolean {
        fetch(local)
        if (synchronized(lock) { (states[local.nodeId]?.thermal?.level ?: 0) < Scheduler.EXCLUDE_FROM_STATUS }) return true
        refreshCaps()
        val eps = endpoints()
        return synchronized(lock) {
            eps.none { o ->
                val so = if (o.nodeId == local.nodeId || o.unusableReason != null) null else states[o.nodeId]
                val c = so?.caps
                so != null && c != null && !so.down && Scheduler.eligible(c, pin, so.thermal.level)
            }
        }
    }

    /** Asks [ep] for its report now and says whether it answered: the test for "the phone is still there" before a job is re-sent to it. */
    private suspend fun stillAnswers(ep: PhoneEndpoint): Boolean {
        fetch(ep)
        return synchronized(lock) { states[ep.nodeId]?.let { !it.down && it.misses == 0 } == true }
    }

    private companion object {
        const val POLL_MS = 100L
        const val STREAM_TIMEOUT_MS = 10 * 60_000L
        const val EMBED_TIMEOUT_MS = 75_000L
        const val MISS_LIMIT = 2
    }
}
