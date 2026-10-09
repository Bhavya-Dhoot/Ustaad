package com.swarmos.app.sched

import com.swarmos.app.llm.ChatMessage
import com.swarmos.app.llm.ChatRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pushes many requests through a [SwarmLlm] with one job in flight per free slot across all phones.
 * Every input index gets exactly one [BatchItem], returned in input order; a request that no phone could
 * answer is reported with its error and does not stop the batch.
 */
class BatchRunner(private val swarm: SwarmLlm, private val nanoClock: () -> Long = System::nanoTime) {

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
        val workers = swarm.slotCount().coerceAtMost(n).coerceAtLeast(1)

        fun snapshot(state: String): BatchReport = synchronized(items) {
            buildReport(batchId, mode, state, n, items.filterNotNull(), (nanoClock() - startNs) / 1e6)
        }

        onUpdate(snapshot("running"))
        coroutineScope {
            repeat(workers) {
                launch {
                    while (true) {
                        val i = next.getAndIncrement()
                        if (i >= n) break
                        val item = runOne(batchId, i, requests[i])
                        synchronized(items) { items[i] = item }
                        onUpdate(snapshot("running"))
                    }
                }
            }
        }
        return snapshot("done")
    }

    private suspend fun runOne(batchId: String, i: Int, req: ChatRequest): BatchItem {
        val jobId = "$batchId-$i"
        return try {
            val r = swarm.chatJob(jobId, req)
            BatchItem(i, jobId, r.nodeId, r.modelName, r.text, r.stats.promptTokens, r.stats.completionTokens, r.stats.genMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BatchItem(i, jobId, error = e.message ?: e.javaClass.simpleName)
        }
    }

    companion object {
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

/** Runs batches in the background and keeps the latest report per mode ("swarm", "local") for the screen and the HTTP routes. */
class BatchStore(
    private val scope: CoroutineScope,
    private val swarm: () -> SwarmLlm,
    private val localOnly: () -> SwarmLlm,
) {
    private val lock = Any()
    private val flows = LinkedHashMap<String, MutableStateFlow<BatchReport>>()
    private val _latest = MutableStateFlow<Map<String, BatchReport>>(emptyMap())

    /** Most recent report of each mode, updated while a batch runs. */
    val latest: StateFlow<Map<String, BatchReport>> = _latest.asStateFlow()

    fun isRunning(): Boolean = synchronized(lock) { flows.values.any { it.value.state == "running" } }

    fun start(requests: List<ChatRequest>, mode: String): String {
        val id = newJobId()
        val client = if (mode == MODE_LOCAL) localOnly() else swarm()
        val flow = MutableStateFlow(BatchRunner.buildReport(id, mode, "running", requests.size, emptyList(), 0.0))
        synchronized(lock) {
            flows[id] = flow
            while (flows.size > KEEP) flows.remove(flows.keys.first())
        }
        publish(flow.value)
        scope.launch {
            val report = try {
                BatchRunner(client).run(id, mode, requests) { r ->
                    // updates come from several workers; never let an older snapshot overwrite a newer one
                    flow.update { cur -> if (r.done + r.failed >= cur.done + cur.failed) r else cur }
                    publish(flow.value)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                flow.value.copy(state = "done", failed = requests.size - flow.value.done)
            }
            flow.value = report
            publish(report)
        }
        return id
    }

    fun status(id: String): StateFlow<BatchReport>? = synchronized(lock) { flows[id] }

    private fun publish(r: BatchReport) = _latest.update { it + (r.mode to r) }

    companion object {
        const val MODE_SWARM = "swarm"
        const val MODE_LOCAL = "local"
        private const val KEEP = 8
    }
}
