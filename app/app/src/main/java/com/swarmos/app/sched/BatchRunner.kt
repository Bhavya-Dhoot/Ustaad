package com.swarmos.app.sched

import com.swarmos.app.core.SwarmJson
import com.swarmos.app.llm.ChatMessage
import com.swarmos.app.llm.ChatRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pushes many requests through a [SwarmLlm] with one job in flight per free slot across all phones.
 * Every input index gets exactly one [BatchItem], returned in input order; a request that no phone could
 * answer is reported with its error and does not stop the batch. The number of workers follows the phones that are up:
 * every [rescanMs] it is worked out again, so a phone that becomes ready mid-batch gets workers and one that goes away
 * does not leave idle ones.
 */
class BatchRunner(
    private val swarm: SwarmLlm,
    private val nanoClock: () -> Long = System::nanoTime,
    private val rescanMs: Long = RESCAN_MS,
) {

    suspend fun run(
        batchId: String,
        mode: String,
        requests: List<ChatRequest>,
        onUpdate: (BatchReport) -> Unit = {},
    ): BatchReport {
        val n = requests.size
        val items = arrayOfNulls<BatchItem>(n)
        val next = AtomicInteger(0)
        val startNs = nanoClock()
        val active = AtomicInteger(0)
        val target = AtomicInteger(workersFor(swarm.slotCount(includeDown = false), n))

        fun snapshot(state: String): BatchReport = synchronized(items) {
            buildReport(batchId, mode, state, n, items.filterNotNull(), (nanoClock() - startNs) / 1e6)
        }

        /** A worker leaves between two items when there are more of them than the phones that are up can use. */
        fun retire(): Boolean {
            while (true) {
                val a = active.get()
                if (a <= target.get()) return false
                if (active.compareAndSet(a, a - 1)) return true
            }
        }

        val allTaken = CompletableDeferred<Unit>()   // every item has been handed to a worker: no more scaling needed
        onUpdate(snapshot("running"))
        coroutineScope {
            fun launchWorker() {
                active.incrementAndGet()
                launch {
                    while (true) {
                        if (retire()) return@launch
                        val i = next.getAndIncrement()
                        if (i >= n) {
                            active.decrementAndGet()
                            allTaken.complete(Unit)
                            return@launch
                        }
                        val item = runOne(batchId, i, requests[i], startNs)
                        synchronized(items) { items[i] = item }
                        onUpdate(snapshot("running"))
                    }
                }
            }
            repeat(target.get()) { launchWorker() }
            launch {
                while (next.get() < n) {
                    if (withTimeoutOrNull(rescanMs) { allTaken.await() } != null) break
                    val want = workersFor(swarm.slotCount(includeDown = false), n - next.get())
                    target.set(want)
                    while (active.get() < want && next.get() < n) launchWorker()
                }
            }
        }
        return snapshot("done")
    }

    private suspend fun runOne(batchId: String, i: Int, req: ChatRequest, startNs: Long): BatchItem {
        val jobId = "$batchId-$i"
        val timing = JobTiming()
        return try {
            // a batch item is a one-shot prompt, on this phone or another: no prompt cache state is kept for it
            val r = swarm.chatJob(jobId, req.copy(cachePrompt = false), timing = timing)
            BatchItem(
                i, jobId, r.nodeId, r.modelName, r.text, r.stats.promptTokens, r.stats.completionTokens, r.stats.genMs,
                promptMs = r.stats.promptMs, waitedMs = timing.waitedMs.toDouble(), endMs = (nanoClock() - startNs) / 1e6,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BatchItem(i, jobId, error = e.message ?: e.javaClass.simpleName, waitedMs = timing.waitedMs.toDouble(), endMs = (nanoClock() - startNs) / 1e6)
        }
    }

    companion object {
        const val RESCAN_MS = 1_000L

        /** Workers for [liveSlots] free-able slots with [remaining] items left: one per slot, never more than items, at least 1. */
        fun workersFor(liveSlots: Int, remaining: Int): Int = liveSlots.coerceAtMost(remaining).coerceAtLeast(1)

        /** Pure function of the finished items, so the aggregate and the per-phone rows always add up. */
        fun buildReport(id: String, mode: String, state: String, total: Int, finished: List<BatchItem>, wallMs: Double): BatchReport {
            val ok = finished.filter { it.error == null }
            val tokens = ok.sumOf { it.completionTokens }
            val perPhone = ok.groupBy { it.nodeId!! }.map { (node, list) ->
                val t = list.sumOf { it.completionTokens }
                val genMs = list.sumOf { it.genMs }
                PhoneShare(
                    nodeId = node,
                    jobs = list.size,
                    tokens = t,
                    tokPerSec = if (wallMs > 0) t * 1000.0 / wallMs else 0.0,
                    decodeTokPerSec = if (genMs > 0) t * 1000.0 / genMs else 0.0,
                )
            }.sortedBy { it.nodeId }
            val models = ok.mapNotNull { it.modelName }.distinct().sorted()
            return BatchReport(
                id = id,
                mode = mode,
                state = state,
                total = total,
                done = ok.size,
                failed = finished.size - ok.size,
                wallMs = wallMs,
                totalTokens = tokens,
                aggTokPerSec = if (wallMs > 0) tokens * 1000.0 / wallMs else 0.0,
                fake = models.contains("FAKE"),
                models = models,
                perPhone = perPhone,
                items = finished.sortedBy { it.index },
            )
        }
    }
}

/** Built-in request sets for [BatchStartRequest.builtin]. */
object Bench {
    const val BENCH32 = "bench32"
    const val BENCH32_MAX_TOKENS = 48

    private val topics = listOf(
        "the monsoon", "a lighthouse", "binary search", "the Moon", "photosynthesis", "a transistor", "the Silk Road", "tides",
        "a prime number", "the water cycle", "a compiler", "volcanoes", "the printing press", "a magnet", "coral reefs", "Morse code",
        "the speed of light", "a sundial", "bread yeast", "glaciers", "a checksum", "the heart", "a telescope", "origami",
        "the Pythagorean theorem", "bees", "a fuse", "the Ganges", "chess openings", "static electricity", "a rainbow", "tea",
    )

    /** 32 short prompts with a fixed cap on the reply length, so every run asks the same work of the swarm. */
    fun bench32(): List<ChatRequest> = topics.mapIndexed { i, topic ->
        ChatRequest(
            messages = listOf(
                ChatMessage("system", "Answer in one short sentence."),
                ChatMessage("user", "Explain $topic."),
            ),
            maxTokens = BENCH32_MAX_TOKENS,
            temperature = 0.0,
            seed = i,
        )
    }

    fun builtin(name: String): List<ChatRequest>? = if (name == BENCH32) bench32() else null
}

/**
 * Runs batches in the background and keeps the latest report per mode ("swarm", "local") for the screen and the HTTP routes.
 * When [dir] gives a folder, the report of a running batch is written there as items finish (at most once a second, the final
 * one always, and before the batch is reported done), so after the app was killed the status route still answers for that id
 * with what had finished, state "interrupted". There is no resume. [audit] gets one entry if a batch ends with an error. [stopAll] ends the batches that run.
 * The first report is written before [start] returns the id, so an id that was handed out is never unknown after a restart.
 */
class BatchStore(
    private val scope: CoroutineScope,
    private val swarm: () -> SwarmLlm,
    private val localOnly: () -> SwarmLlm,
    private val dir: () -> File? = { null },
    private val audit: (String, JsonObject) -> Unit = { _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val flows = LinkedHashMap<String, MutableStateFlow<BatchReport>>()
    private val jobs = HashMap<String, Job>()                       // the coroutine of every batch that has not ended
    private val pending = LinkedHashMap<String, BatchReport>()      // newest report per batch that waits for the writer
    private var writer: CompletableFuture<Unit>? = null             // the writer at work (one at a time), completed when it is done
    private val newest = HashMap<String, Long>()                    // rank of the newest report accepted per batch, written or queued
    private val _latest = MutableStateFlow<Map<String, BatchReport>>(emptyMap())
    private var lastWriteMs = 0L

    /** Most recent report of each mode, updated while a batch runs. */
    val latest: StateFlow<Map<String, BatchReport>> = _latest.asStateFlow()

    fun isRunning(): Boolean = synchronized(lock) { flows.values.any { it.value.state == RUNNING } }

    fun start(requests: List<ChatRequest>, mode: String): String = begin(requests, mode, onlyIfIdle = false)!!

    /**
     * [start] on an IO thread, for a caller on the main thread: the first report is written to disk before the batch begins, which
     * must not hold up the screen. The id is not returned; the batch shows in [latest].
     */
    fun startInBackground(requests: List<ChatRequest>, mode: String) {
        scope.launch(Dispatchers.IO) { start(requests, mode) }
    }

    /** Starts the batch unless one is running; null then. The check and the start are one step. */
    fun tryStart(requests: List<ChatRequest>, mode: String): String? = begin(requests, mode, onlyIfIdle = true)

    private fun begin(requests: List<ChatRequest>, mode: String, onlyIfIdle: Boolean): String? {
        val id = newJobId()
        val client = if (mode == MODE_LOCAL) localOnly() else swarm()
        val flow = MutableStateFlow(BatchRunner.buildReport(id, mode, RUNNING, requests.size, emptyList(), 0.0))
        // created now but not started: it is in [jobs] before the first report is written, so [stopAll] in that moment ends it
        // before it has sent a job, and it is started only after the write
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val report = try {
                BatchRunner(client).run(id, mode, requests) { r ->
                    // updates come from several workers; never let an older snapshot overwrite a newer one
                    flow.update { cur -> if (r.done + r.failed >= cur.done + cur.failed) r else cur }
                    publish(flow.value)
                    persist(flow.value, force = false)
                    val cur = flow.value
                    SchedActivity.set(SchedActivity.batch(requests.size, cur.done + cur.failed, client.phoneViews().count { it.up }))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                audit("batch.failed", JsonObject(mapOf("id" to JsonPrimitive(id), "error" to JsonPrimitive(e.javaClass.simpleName))))
                flow.value.copy(state = "done", failed = requests.size - flow.value.done)
            } finally {
                SchedActivity.clear()
            }
            persist(report, force = true)   // on disk first: a status that says "done" never has a file that still says "running"
            flow.value = report
            publish(report)
        }
        synchronized(lock) {
            if (onlyIfIdle && flows.values.any { it.value.state == RUNNING }) {
                job.cancel()
                return null
            }
            flows[id] = flow
            jobs[id] = job
            // only finished batches are forgotten: a running one keeps its status (its coroutine goes on whatever the map holds)
            while (flows.size > KEEP) {
                val finished = flows.entries.firstOrNull { it.value.value.state != RUNNING } ?: break
                flows.remove(finished.key)
                newest.remove(finished.key)
            }
        }
        job.invokeOnCompletion { cause ->
            synchronized(lock) { jobs.remove(id) }
            // stopped ([stopAll], or the scope ended) before it finished, possibly before it ran a line: it will not go on, so it
            // must not stay "running" here or on disk
            if (cause != null && flow.value.state == RUNNING) {
                val stopped = flow.value.copy(state = INTERRUPTED)
                flow.value = stopped
                publish(stopped)
                persist(stopped, force = true)
            }
        }
        publish(flow.value)
        persist(flow.value, force = true)   // the id is about to be handed out: it must be known after a restart too
        job.start()                         // does nothing if the batch was stopped meanwhile
        return id
    }

    /**
     * Ends every batch that is running (Stop Ustaad): no more jobs go to the phones, and each report says "interrupted", the
     * state a coordinator that was killed leaves behind. Blocking; every batch is told to stop first, then all of them are
     * waited for together, at most [STOP_WAIT_MS]: one that is slow to wind up does not keep the others running.
     */
    fun stopAll() {
        val running = synchronized(lock) { jobs.values.toList() }
        if (running.isEmpty()) return
        running.forEach { it.cancel() }
        runBlocking { withTimeoutOrNull(STOP_WAIT_MS) { running.forEach { it.join() } } }
    }

    /** The batch's status: from memory, else from the file written while it ran (an id of an interrupted batch answers "interrupted"). */
    fun status(id: String): StateFlow<BatchReport>? = synchronized(lock) { flows[id] } ?: stored(id)?.let { MutableStateFlow(it) }

    private fun publish(r: BatchReport) = _latest.update { it + (r.mode to r) }

    /**
     * Files [r] for the writer. One writer at a time: a caller that finds the writer busy leaves its report in [pending] (the
     * newest per batch wins) and the writer takes it when it is done with the one it has, so no worker waits for the disk.
     * A forced report (the last of a batch, or the one that says it was stopped) is the exception: it returns only when the
     * writer is done, which is after that report is on disk.
     */
    internal fun persist(r: BatchReport, force: Boolean) {
        val d = dir() ?: return
        val mine = CompletableFuture<Unit>()
        val busy = synchronized(lock) {
            val now = clock()
            if (!force && now - lastWriteMs < WRITE_EVERY_MS) return
            // a snapshot that arrives late (a slow worker, after a newer one was queued or even written) must not replace it
            val rank = rankOf(r)
            if (rank < (newest[r.id] ?: -1L)) return
            newest[r.id] = rank
            lastWriteMs = now
            pending[r.id] = r
            writer ?: run {
                writer = mine
                null
            }
        }
        if (busy != null) {
            if (force) busy.join()
            return
        }
        try {
            while (true) {
                val next = synchronized(lock) {
                    val first = pending.keys.firstOrNull()
                    if (first == null) writer = null   // together with the look at [pending], so no report is left behind
                    first?.let { pending.remove(it) }
                } ?: break
                write(d, next)
            }
        } finally {
            synchronized(lock) { if (writer === mine) writer = null }
            mine.complete(Unit)
        }
    }

    /** How far a report is: finished items, and a report that is no longer "running" is ahead of a running one with the same items. */
    private fun rankOf(r: BatchReport): Long = (r.done + r.failed) * 2L + if (r.state == RUNNING) 0L else 1L

    /** Writes the report to a temporary file of its own and moves it over the real one, so a reader never sees half a report. */
    private fun write(d: File, r: BatchReport) {
        var tmp: File? = null
        try {
            d.mkdirs()
            tmp = File.createTempFile("batch-${r.id}-", ".tmp", d)
            tmp.writeText(SwarmJson.encodeToString(r))
            val target = File(d, "batch-${r.id}.json").toPath()
            try {
                Files.move(tmp.toPath(), target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), target, StandardCopyOption.REPLACE_EXISTING)
            }
            d.listFiles { f -> f.name.startsWith("batch-") && f.name.endsWith(".json") }
                ?.sortedByDescending { it.lastModified() }?.drop(KEEP_FILES)?.forEach { it.delete() }
            // a temporary file left by a write that was cut short (the app was killed)
            val stale = System.currentTimeMillis() - STALE_TMP_MS
            d.listFiles { f -> f.name.startsWith("batch-") && f.name.endsWith(".tmp") && f.lastModified() < stale }?.forEach { it.delete() }
        } catch (_: Exception) {
            // the status stays in memory; a full disk must not stop a batch
            tmp?.delete()
        }
    }

    private fun stored(id: String): BatchReport? {
        if (!SAFE_ID.matches(id)) return null
        val f = File(dir() ?: return null, "batch-$id.json")
        if (!f.isFile) return null
        val r = try {
            SwarmJson.decodeFromString<BatchReport>(f.readText())
        } catch (_: Exception) {
            return null
        }
        return if (r.state == RUNNING) r.copy(state = INTERRUPTED) else r
    }

    companion object {
        const val MODE_SWARM = "swarm"
        const val MODE_LOCAL = "local"
        const val RUNNING = "running"
        const val INTERRUPTED = "interrupted"
        private const val KEEP = 8
        private const val KEEP_FILES = 16
        private const val WRITE_EVERY_MS = 1_000L
        private const val STALE_TMP_MS = 60_000L

        /** [BatchStore.stopAll] waits this long for the batches to end. */
        const val STOP_WAIT_MS = 3_000L
        private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
