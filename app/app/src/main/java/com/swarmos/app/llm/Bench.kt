package com.swarmos.app.llm

import com.swarmos.app.AppGraph
import com.swarmos.app.core.HealthMonitor
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
    val thermal: String,
    val threads: Int?,
    val atEpochMs: Long,
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

    /** Repeat count of [PARAGRAPH] that gives about 512 prompt tokens with the Qwen3.5 tokenizer and chat template. */
    private const val REPEATS = 8
    private const val PARAGRAPH =
        "A mesh of phones can share work when each phone keeps its own memory and passes only small messages " +
            "over the local network. Every phone checks its battery and temperature before it accepts a job, " +
            "and every answer carries the name of the phone that produced it. "

    val prompt: String = buildString {
        repeat(REPEATS) { append(PARAGRAPH) }
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

    /** One benchmark run on the CHAT server. Throws if the chat server is not READY. */
    suspend fun run(): BenchResult = mutex.withLock {
        _running.value = true
        _error.value = null
        _text.value = ""
        try {
            runOnce().also { _last.value = it }
        } catch (e: Exception) {
            _error.value = e.message ?: e.toString()
            throw e
        } finally {
            _running.value = false
        }
    }

    /** Repeats the benchmark for [minutes] minutes and records the mean decode tok/s of every minute. */
    suspend fun runSustained(minutes: Int = 10): List<SustainedPoint> = mutex.withLock {
        _running.value = true
        _error.value = null
        _sustained.value = emptyList()
        try {
            val start = System.nanoTime()
            val perMinute = HashMap<Int, MutableList<Double>>()
            var lastThermal = "?"
            while ((System.nanoTime() - start) < minutes * 60_000_000_000L) {
                val r = runOnce()
                _last.value = r
                lastThermal = r.thermal
                val minute = ((System.nanoTime() - start) / 60_000_000_000L).toInt().coerceAtMost(minutes - 1)
                perMinute.getOrPut(minute) { mutableListOf() }.add(r.decodeTokPerSec)
                _sustained.value = perMinute.toSortedMap().map { (m, v) -> SustainedPoint(m + 1, v.average(), lastThermal) }
            }
            _sustained.value
        } catch (e: Exception) {
            _error.value = e.message ?: e.toString()
            throw e
        } finally {
            _running.value = false
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
        val t0 = System.nanoTime()
        var firstMs = 0.0
        var stats: ChatStats? = null
        val sb = StringBuilder()
        client.chatStreamRaw(req, cachePrompt = false, extra = extra).collect { c ->
            if (c.delta.isNotEmpty()) {
                if (firstMs == 0.0) firstMs = (System.nanoTime() - t0) / 1e6
                sb.append(c.delta)
                _text.value = sb.toString()
            }
            if (c.done) stats = c.stats
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
            thermal = HealthMonitor.thermalName(AppGraph.health.state.value.thermalStatus),
            threads = threads,
            atEpochMs = System.currentTimeMillis(),
        )
    }
}
