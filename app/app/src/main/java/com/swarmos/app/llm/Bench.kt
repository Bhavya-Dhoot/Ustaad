package com.swarmos.app.llm

import com.swarmos.app.AppGraph
import com.swarmos.app.core.HealthMonitor
import com.swarmos.app.core.ThermalGate
import com.swarmos.app.core.ThermalLevel
import com.swarmos.app.core.WorkKind
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

@Serializable
data class BenchResult(
    val model: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val prefillTokPerSec: Double,
    val decodeTokPerSec: Double,
    val ttftMs: Double,
    val rssMb: Long?,
    val peakRssMb: Long?,
    /** Thermal status when the run ended / when it started (NONE, LIGHT, MODERATE, SEVERE, CRITICAL, ...). */
    val thermal: String,
    val thermalStart: String,
    val threads: Int?,
    val atEpochMs: Long,
    /** "GPU" or "CPU": the llama-server build the chat model ran on. */
    val backend: String? = null,
)

@Serializable
data class SustainedPoint(val minute: Int, val decodeTokPerSec: Double, val thermal: String)

/**
 * Fixed benchmark through the real client path (chat completions, streamed): a prompt of about 512 tokens
 * (the result reports the exact prompt token count the server evaluated) and exactly 128 generated tokens
 * (`ignore_eos`), prompt cache off so every run pays the full prefill.
 */
object Bench {
    const val GEN_TOKENS = 128

    /** A run (512-token prefill + 128 tokens, a minute or so on the slowest build) that takes longer than this is a hung server. */
    private const val RUN_BUDGET_MS = 300_000L

    /** 9 paragraphs (50 tokens each) plus two sentences (25 + 14) plus the instruction and chat template give about 512 prompt tokens with the Qwen3.5 tokenizer. */
    private const val REPEATS = 9
    private const val PARAGRAPH =
        "A mesh of phones can share work when each phone keeps its own memory and passes only small messages " +
            "over the local network. Every phone checks its battery and temperature before it accepts a job, " +
            "and every answer carries the name of the phone that produced it. "

    val prompt: String = buildString {
        repeat(REPEATS) { append(PARAGRAPH) }
        append("A mesh of phones can share work when each phone keeps its own memory and passes only small messages over the local network. ")
        append("Every phone checks its battery and temperature before it accepts a job. ")
        append("\n\nWrite a long, detailed story that continues the text above.")
    }

    private val mutex = Mutex()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _last = MutableStateFlow<BenchResult?>(null)
    val last: StateFlow<BenchResult?> = _last.asStateFlow()

    private val _sustained = MutableStateFlow<List<SustainedPoint>>(emptyList())
    val sustained: StateFlow<List<SustainedPoint>> = _sustained.asStateFlow()

    /** Text streamed by the current/last run, for the Bench UI. */
    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * Why a benchmark must not start at [thermalStatus], or null when it may. Heavy repeated generation is refused when the
     * shared decision ([ThermalGate], BENCHMARK: SEVERE and up) says the phone is too hot; chat requests are not affected.
     * Pure so it can be unit-tested.
     */
    fun blockReasonFor(thermalStatus: Int): String? =
        if (!ThermalGate.decide(thermalStatus, WorkKind.BENCHMARK).allowed) {
            "Phone is ${ThermalLevel.of(thermalStatus).word}. Benchmark is off until it cools to ${ThermalLevel.Hot.word} or lower."
        } else {
            null
        }

    /** Live thermal status straight from PowerManager (not the 2 s sample); on Android 8 and 9 the stand-in from the battery temperature. */
    fun thermalStatusNow(): Int = HealthMonitor.thermalNow(AppGraph.appContext).status

    fun blockReason(): String? = blockReasonFor(thermalStatusNow())

    /** One benchmark run on the CHAT server. Throws if the chat server is not READY or the phone is too hot. */
    suspend fun run(): BenchResult = mutex.withLock {
        blockReason()?.let { throw IllegalStateException(it) }
        _running.value = true
        _error.value = null
        _text.value = ""
        try {
            announced(ModelsActivity.benchText(null, 0)) { runOnce() }.also { record(it) }
        } catch (e: Exception) {
            _error.value = if (e is kotlinx.coroutines.CancellationException) "Benchmark stopped." else e.message ?: e.toString()
            throw e
        } finally {
            _running.value = false
        }
    }

    /** Repeats the benchmark for [minutes] minutes and records the mean decode tok/s of every minute. */
    suspend fun runSustained(minutes: Int = 10): List<SustainedPoint> = mutex.withLock {
        blockReason()?.let { throw IllegalStateException(it) }
        _running.value = true
        _error.value = null
        _sustained.value = emptyList()
        try {
            announced(ModelsActivity.benchText(1, minutes)) {
                val start = System.nanoTime()
                val perMinute = HashMap<Int, MutableList<Double>>()
                var lastThermal = "?"
                while ((System.nanoTime() - start) < minutes * 60_000_000_000L) {
                    // a loop of generations must never keep a hot phone busy: stop as soon as the guard trips
                    blockReason()?.let { _error.value = "Stopped after ${perMinute.size} min: $it"; return@announced _sustained.value }
                    val now = ((System.nanoTime() - start) / 60_000_000_000L).toInt().coerceAtMost(minutes - 1)
                    ModelsActivity.update(BENCH_KEY, ModelsActivity.benchText(now + 1, minutes))
                    val r = runOnce()
                    record(r)
                    lastThermal = r.thermal
                    val minute = ((System.nanoTime() - start) / 60_000_000_000L).toInt().coerceAtMost(minutes - 1)
                    perMinute.getOrPut(minute) { mutableListOf() }.add(r.decodeTokPerSec)
                    _sustained.value = perMinute.toSortedMap().map { (m, v) -> SustainedPoint(m + 1, v.average(), lastThermal) }
                }
                _sustained.value
            }
        } catch (e: Exception) {
            _error.value = if (e is kotlinx.coroutines.CancellationException) "Benchmark stopped." else e.message ?: e.toString()
            throw e
        } finally {
            _running.value = false
        }
    }

    /** Keeps the result as "last" and shows its numbers on the chat row of the Models tab (labelled as a benchmark). */
    private fun record(r: BenchResult) {
        _last.value = r
        LlamaRuntime.publishSpeed(
            Role.CHAT,
            SpeedSample(
                r.model, SpeedSource.BENCH, r.atEpochMs, r.promptTokens, r.completionTokens,
                prefillTokPerSec = r.prefillTokPerSec.takeIf { it > 0 },
                decodeTokPerSec = r.decodeTokPerSec.takeIf { it > 0 },
            ),
        )
    }

    private const val BENCH_KEY = "bench"

    /**
     * Runs [block] while the phone's activity banner says [text]; the banner's Stop action cancels the block (the
     * chat stream is cancelled with it, so llama-server frees the slot). The cancellation reaches the caller as a
     * CancellationException, the banner entry is removed in every case.
     */
    private suspend fun <T> announced(text: String, block: suspend () -> T): T = coroutineScope {
        val work = async { block() }
        ModelsActivity.set(BENCH_KEY, text, onStop = { work.cancel() })
        try {
            work.await()
        } finally {
            ModelsActivity.end(BENCH_KEY)
        }
    }

    private suspend fun runOnce(): BenchResult {
        val client = AppGraph.llm as? LlamaServerClient
            ?: throw IllegalStateException("chat server is not READY (the fake client is active)")
        val req = ChatRequest(
            messages = listOf(ChatMessage("user", prompt)),
            maxTokens = GEN_TOKENS,
            temperature = 0.0,
            seed = 1,
        )
        val extra = buildJsonObject { put("ignore_eos", JsonPrimitive(true)) }
        val thermalStart = HealthMonitor.thermalName(thermalStatusNow())
        val t0 = System.nanoTime()
        var firstMs = 0.0
        var stats: ChatStats? = null
        val sb = StringBuilder()
        withChatBudgetOrFail("one benchmark run", RUN_BUDGET_MS) {
            client.chatStreamRaw(req, cachePrompt = false, extra = extra).collect { c ->
                if (c.delta.isNotEmpty()) {
                    if (firstMs == 0.0) firstMs = (System.nanoTime() - t0) / 1e6
                    sb.append(c.delta)
                    _text.value = sb.toString()
                }
                if (c.done) stats = c.stats
            }
        }
        val s = stats ?: throw IllegalStateException("no timings in the stream")
        val st = LlamaRuntime.statusOf(Role.CHAT)
        val threads = st.args.indexOf("-t").takeIf { it >= 0 }?.let { st.args.getOrNull(it + 1)?.toIntOrNull() }
        return BenchResult(
            model = client.modelName,
            promptTokens = s.promptTokens,
            completionTokens = s.completionTokens,
            prefillTokPerSec = if (s.promptMs > 0) s.promptTokens * 1000.0 / s.promptMs else 0.0,
            decodeTokPerSec = s.decodeTokPerSec,
            ttftMs = firstMs,
            rssMb = LlamaRuntime.rssKb(Role.CHAT)?.div(1024),
            peakRssMb = LlamaRuntime.peakRssKb(Role.CHAT)?.div(1024),
            thermal = HealthMonitor.thermalName(thermalStatusNow()),
            thermalStart = thermalStart,
            threads = threads,
            atEpochMs = System.currentTimeMillis(),
            backend = st.backend?.name,
        )
    }
}
