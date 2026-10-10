package com.swarmos.app.llm

import com.swarmos.app.core.ThermalLevel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Pure state-to-UI mapping for the Models tab and the benchmark page (no Compose, no Android), so which button is the
 * primary one, which numbers are shown and what they are labelled with can be unit-tested on the JVM.
 */

// ---------------------------------------------------------------------------------------------------------------------
// Header: the one primary button
// ---------------------------------------------------------------------------------------------------------------------

internal enum class PrimaryAction { LoadModels, Loading, RunBenchmark }

/**
 * [primary] is the only primary button of the Models page. Nothing loaded: "Load models". Chat loading: a disabled
 * "Loading". Chat READY: "Run benchmark" (disabled while the phone is too hot or a run is going).
 * [benchReason] says why a benchmark cannot run right now (null when it can); while no real chat model is READY the page shows
 * a disabled secondary "Run benchmark" ([showDisabledBench]) next to that reason.
 */
internal data class HeaderUi(
    val primary: PrimaryAction,
    val primaryEnabled: Boolean,
    val showDisabledBench: Boolean,
    val benchReason: String?,
)

internal fun headerUi(chat: ServerState, benchBlockedReason: String?, benchRunning: Boolean): HeaderUi = when (chat) {
    ServerState.READY -> HeaderUi(
        PrimaryAction.RunBenchmark,
        primaryEnabled = benchBlockedReason == null && !benchRunning,
        showDisabledBench = false,
        benchReason = benchBlockedReason,
    )
    ServerState.STARTING -> HeaderUi(PrimaryAction.Loading, false, true, "The chat model is still loading.")
    ServerState.STOPPED, ServerState.FAILED -> HeaderUi(PrimaryAction.LoadModels, true, true, "Load the chat model to run a benchmark.")
}

internal fun primaryText(a: PrimaryAction): String = when (a) {
    PrimaryAction.LoadModels -> "Load models"
    PrimaryAction.Loading -> "Loading"
    PrimaryAction.RunBenchmark -> "Run benchmark"
}

/** Roles whose catalog default should be started by "Load models": those that are neither running nor loading. */
internal fun rolesToLoad(chat: ServerState, embed: ServerState): List<Role> = buildList {
    if (chat == ServerState.STOPPED || chat == ServerState.FAILED) add(Role.CHAT)
    if (embed == ServerState.STOPPED || embed == ServerState.FAILED) add(Role.EMBED)
}

/** True when the chat model is READY but the embedder is not running: the page then offers a secondary "Load embedder". */
internal fun embedderMissing(chat: ServerState, embed: ServerState): Boolean =
    chat == ServerState.READY && (embed == ServerState.STOPPED || embed == ServerState.FAILED)

// ---------------------------------------------------------------------------------------------------------------------
// Server rows
// ---------------------------------------------------------------------------------------------------------------------

internal enum class RowMark { READY, FAILED, LOADING, RESTARTING, STOPPED, FAKE }

/**
 * What the state mark of a server row shows. Only the CHAT row can carry the FAKE stamp (replies on this phone are canned
 * while it is not running); every other stopped server shows a plain "stopped" tag, so one screen never has two FAKE stamps.
 */
internal fun rowMark(role: Role, st: ServerStatus): RowMark = when (st.state) {
    ServerState.READY -> RowMark.READY
    ServerState.FAILED -> RowMark.FAILED
    ServerState.STARTING -> if (st.restarts > 0) RowMark.RESTARTING else RowMark.LOADING
    ServerState.STOPPED -> if (role == Role.CHAT) RowMark.FAKE else RowMark.STOPPED
}

internal fun fakeStampCount(status: Map<Role, ServerStatus>): Int =
    status.count { (role, st) -> rowMark(role, st) == RowMark.FAKE }

/** "GPU" / "CPU" for a running server: the chat build is chosen at start, the embedder and rpc-server always run on the CPU build. */
internal fun backendLabel(st: ServerStatus): String? =
    if (st.state == ServerState.STOPPED) null else st.backend?.name ?: "CPU"

internal fun loadingLabel(elapsedMs: Long, restarting: Boolean): String =
    (if (restarting) "Restarting, " else "Loading, ") + (elapsedMs.coerceAtLeast(0) / 1000) + " s"

// ---------------------------------------------------------------------------------------------------------------------
// Measured speed
// ---------------------------------------------------------------------------------------------------------------------

enum class SpeedSource { LOAD, BENCH, REQUEST }

/**
 * A measurement of one server, with what it was measured on and when. LOAD: the warm-up request every CHAT and EMBED
 * server answers before it is READY. BENCH: the fixed benchmark. REQUEST: the latest embedding call.
 */
data class SpeedSample(
    val model: String,
    val source: SpeedSource,
    val atEpochMs: Long,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val prefillTokPerSec: Double? = null,
    val decodeTokPerSec: Double? = null,
    val embedMsPerText: Double? = null,
) {
    companion object {
        /** Prefill needs evaluated prompt tokens and a time, decode needs at least two generated tokens; otherwise that number is not measured. */
        fun fromStats(model: String, source: SpeedSource, atEpochMs: Long, s: ChatStats): SpeedSample = SpeedSample(
            model, source, atEpochMs, s.promptTokens, s.completionTokens,
            prefillTokPerSec = if (s.promptTokens > 0 && s.promptMs > 0) s.promptTokens * 1000.0 / s.promptMs else null,
            decodeTokPerSec = if (s.completionTokens >= 2 && s.genMs > 0) s.decodeTokPerSec else null,
        )
    }
}

/** The sample only counts while its server is READY with that same model; otherwise the numbers belong to something that is gone. */
internal fun speedFor(st: ServerStatus, sample: SpeedSample?): SpeedSample? =
    sample?.takeIf { st.state == ServerState.READY && it.model == st.modelName }

/** Metric cells of a running chat / embedder row. Only measured values appear: no placeholders. */
internal fun speedMetrics(role: Role, s: SpeedSample?): List<Pair<String, String>> = buildList {
    if (s == null) return@buildList
    if (role == Role.CHAT) {
        s.prefillTokPerSec?.let { add("prefill" to fmt1(it) + " tok/s") }
        s.decodeTokPerSec?.let { add("decode" to fmt1(it) + " tok/s") }
    }
    if (role == Role.EMBED) s.embedMsPerText?.let { add("latency" to fmt1(it) + " ms per text") }
}

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

internal fun clock(atEpochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    CLOCK.format(Instant.ofEpochMilli(atEpochMs).atZone(zone))

/** "Measured at load, 12 prompt tokens, 08:04." - says what the numbers came from and when, so nobody reads them as a benchmark. */
internal fun measuredCaption(s: SpeedSample, zone: ZoneId = ZoneId.systemDefault()): String {
    val at = clock(s.atEpochMs, zone)
    return when (s.source) {
        SpeedSource.LOAD ->
            if (s.promptTokens > 0) "Measured at load on the first request (${s.promptTokens} prompt tokens, ${s.completionTokens} generated), $at."
            else "Measured at load on the first request, $at."
        SpeedSource.BENCH -> "Measured by the benchmark (${s.promptTokens} prompt tokens, ${s.completionTokens} generated), $at."
        SpeedSource.REQUEST -> "Measured on the latest request, $at."
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// File cards
// ---------------------------------------------------------------------------------------------------------------------

/** The one action a model file card offers (plus Delete, which lives in a secondary place). */
internal sealed interface CardAction {
    /** "Load as chat" or "Load as embedder": the catalog says which. */
    data class Load(val role: Role) : CardAction

    /** A GGUF the catalog does not know: one "Load" that opens a chooser. */
    data object Choose : CardAction

    data class Download(val resume: Boolean) : CardAction

    /** Nothing to offer: the file is already loaded, a download is running (it has its own Cancel), or it is incomplete and unknown. */
    data object None : CardAction
}

/**
 * The role whose server is loading or running the file [file], or null. A server in FAILED holds nothing: its model is offered
 * for Load and Delete again (the `loadedAs` that [cardAction] and [canDelete] take).
 */
internal fun loadedAs(status: Map<Role, ServerStatus>, file: String): Role? = Role.entries.firstOrNull { r ->
    status.getValue(r).let { (it.state == ServerState.READY || it.state == ServerState.STARTING) && it.modelPath?.endsWith("/$file") == true }
}

internal fun cardAction(entry: CatalogEntry?, present: PresentModel?, dl: DownloadState?, loadedAs: Role?): CardAction = when {
    dl is DownloadState.Downloading || dl == DownloadState.Verifying -> CardAction.None
    present != null && present.complete -> when {
        loadedAs != null -> CardAction.None
        entry != null -> CardAction.Load(entry.role)
        else -> CardAction.Choose
    }
    entry != null -> CardAction.Download(resume = present != null)
    else -> CardAction.None
}

internal fun cardActionLabel(a: CardAction, sizeBytes: Long): String? = when (a) {
    is CardAction.Load -> if (a.role == Role.EMBED) "Load as embedder" else "Load as chat"
    CardAction.Choose -> "Load"
    is CardAction.Download -> if (a.resume) "Resume download" else "Download" + if (sizeBytes > 0) " " + fmtBytes(sizeBytes) else ""
    CardAction.None -> null
}

/** Delete is offered only for a complete-or-partial file that no server is using, and only behind a confirmation. */
internal fun canDelete(present: PresentModel?, loadedAs: Role?): Boolean = present != null && loadedAs == null

// ---------------------------------------------------------------------------------------------------------------------
// Benchmark page
// ---------------------------------------------------------------------------------------------------------------------

internal enum class BenchView { NONE, LIVE, LAST_RUN }

/** LIVE while the chat server runs the model the result came from; LAST_RUN once it is unloaded or another model took its place. */
internal fun benchView(chat: ServerStatus, last: BenchResult?): BenchView = when {
    last == null -> BenchView.NONE
    chat.state == ServerState.READY && chat.modelName == last.model -> BenchView.LIVE
    else -> BenchView.LAST_RUN
}

internal fun lastRunCaption(r: BenchResult, zone: ZoneId = ZoneId.systemDefault()): String =
    "${r.model}, measured ${clock(r.atEpochMs, zone)}. This model is not loaded now."

private val THERMAL_NAMES = listOf("NONE", "LIGHT", "MODERATE", "SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN")

/** Raw thermal name stored in a benchmark result ("NONE", "MODERATE", ...) -> the app's shared word (Cool, Warm, Hot, Severe). */
internal fun thermalWord(raw: String): String {
    val status = THERMAL_NAMES.indexOf(raw.trim().uppercase())
    return if (status < 0) raw else ThermalLevel.of(status).word
}
