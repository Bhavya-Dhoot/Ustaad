package com.swarmos.app.llm

import android.os.StatFs
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swarmos.app.AppGraph
import com.swarmos.app.core.HealthMonitor
import com.swarmos.app.ui.EmptyHint
import com.swarmos.app.ui.ErrorNote
import com.swarmos.app.ui.Hairline
import com.swarmos.app.ui.HeroMetric
import com.swarmos.app.ui.InkProgress
import com.swarmos.app.ui.MetricRow
import com.swarmos.app.ui.Note
import com.swarmos.app.ui.PhoneToken
import com.swarmos.app.ui.PrimaryButton
import com.swarmos.app.ui.SectionTitle
import com.swarmos.app.ui.SecondaryButton
import com.swarmos.app.ui.Skeleton
import com.swarmos.app.ui.Stamp
import com.swarmos.app.ui.StreamingText
import com.swarmos.app.ui.SwarmCard
import com.swarmos.app.ui.SwarmColors
import com.swarmos.app.ui.SwarmIcon
import com.swarmos.app.ui.SwarmType
import com.swarmos.app.ui.Tag
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Models tab. Layout and content order follow ui-kit/screens/models (loaded servers, model files, benchmark button)
 * and ui-kit/screens/benchmark_phone_a (hero decode speed, metric list, tok/s by minute). Every value is measured or read
 * from the server state; nothing here is invented.
 */
@Composable
fun ModelsScreen() {
    var showBench by rememberSaveable { mutableStateOf(false) }
    if (showBench) BenchPage(onBack = { showBench = false }) else ModelsPage(onBench = { showBench = true })
}

private val GraphiteLabel get() = SwarmType.label.copy(color = SwarmColors.graphite)

// ---------------------------------------------------------------------------------------------------------------------
// Models page
// ---------------------------------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelsPage(onBench: () -> Unit) {
    val status by LlamaRuntime.status.collectAsStateWithLifecycle()
    val downloads by ModelStore.downloads.collectAsStateWithLifecycle()
    val wan by ModelStore.wanBytes.collectAsStateWithLifecycle()
    val tick by ModelStore.tick.collectAsStateWithLifecycle()
    val bench by Bench.last.collectAsStateWithLifecycle()
    val lastStats by LlamaRuntime.lastStats.collectAsStateWithLifecycle()
    val embedMs by LlamaRuntime.lastEmbedMsPerText.collectAsStateWithLifecycle()
    val autoload by ModelStore.autoload.collectAsStateWithLifecycle()

    // The file list can change without a state change (adb push), so re-list every 2 s while visible.
    var present by remember { mutableStateOf<List<PresentModel>>(emptyList()) }
    var rss by remember { mutableStateOf<Map<Role, Long?>>(emptyMap()) }
    var freeBytes by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(tick) {
        while (true) {
            present = ModelStore.present()
            rss = Role.entries.associateWith { LlamaRuntime.rssKb(it) }
            freeBytes = runCatching { StatFs(ModelStore.modelsDir().path).availableBytes }.getOrNull()
            delay(2000)
        }
    }

    val chat = status.getValue(Role.CHAT)
    val embed = status.getValue(Role.EMBED)
    val rpc = status.getValue(Role.RPC)
    val serverMb = rss.values.filterNotNull().sum() / 1024
    val anyRunning = status.values.any { it.state != ServerState.STOPPED }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Models", style = SwarmType.display)
            PrimaryButton("Run benchmark", onClick = onBench, icon = SwarmIcon.Play)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            SectionTitle("Loaded on this phone")
            if (serverMb > 0) Text(fmtBytes(serverMb * 1024 * 1024), style = SwarmType.mono)
        }
        ServerCard(
            tag = "CHAT", st = chat, rssKb = rss[Role.CHAT],
            metrics = listOf(
                "prefill" to (bench?.prefillTokPerSec?.let { fmt1(it) + " tok/s" } ?: "-"),
                "decode" to (lastStats?.decodeTokPerSec?.takeIf { it > 0 }?.let { fmt1(it) + " tok/s" } ?: "-"),
            ),
            fakeNote = "No chat model is loaded: replies on this phone are canned.",
        )
        ServerCard(
            tag = "EMBEDDER", st = embed, rssKb = rss[Role.EMBED],
            metrics = listOf("latency" to (embedMs?.let { fmt1(it) + " ms per text" } ?: "-")),
            fakeNote = "No embedding model is loaded: vectors on this phone are placeholders.",
        )
        if (rpc.state != ServerState.STOPPED) ServerCard("RPC", rpc, rss[Role.RPC], emptyList(), null)

        AutoloadRow(autoload)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Unload all", onClick = { LlamaRuntime.stopAll() }, enabled = anyRunning)
            SecondaryButton("Load defaults", onClick = { LlamaRuntime.loadDefaults() })
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            SectionTitle("On this phone")
            freeBytes?.let { Text(fmtBytes(it) + " free", style = SwarmType.mono) }
        }
        Text("Downloads use the internet. Received this session: ${fmtBytes(wan)}", style = GraphiteLabel)

        val presentByFile = present.associateBy { it.file }
        if (present.isEmpty()) {
            EmptyHint("No model files on this phone yet. Download one.", Modifier.fillMaxWidth())
        }
        for (e in ModelCatalog.entries) {
            FileCard(e.file, e, presentByFile[e.file], downloads[e.file], status)
        }
        for (p in present) {
            if (ModelCatalog.byFile(p.file) == null) FileCard(p.file, null, p, downloads[p.file], status)
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(label, style = SwarmType.label.copy(fontSize = 12.sp, color = SwarmColors.graphite))
        Text(value, style = SwarmType.mono)
    }
}

/** One llama.cpp server of this phone: name from the GGUF file, state stamp, measured numbers, Stop and the log. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServerCard(tag: String, st: ServerStatus, rssKb: Long?, metrics: List<Pair<String, String>>, fakeNote: String?) {
    var showLog by remember { mutableStateOf(false) }
    val role = st.role
    // Loaded servers are flat rows separated by hairlines (ui-kit/screens/models); only model files are boxed cards.
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tag(tag)
            Spacer(Modifier.width(8.dp))
            Text(
                st.modelName ?: "not loaded",
                Modifier.weight(1f),
                style = SwarmType.mono.copy(color = if (st.modelName == null) SwarmColors.graphite else SwarmColors.ink),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            when (st.state) {
                ServerState.READY -> Stamp("READY", small = true)
                ServerState.FAILED -> Stamp("FAILED", caution = true, small = true)
                ServerState.STARTING -> Tag("loading")
                ServerState.STOPPED -> if (fakeNote != null) Stamp("FAKE", caution = true, small = true) else Tag("stopped")
            }
        }
        Spacer(Modifier.height(10.dp))
        when (st.state) {
            ServerState.STARTING -> Skeleton(18.dp)
            ServerState.STOPPED -> if (fakeNote != null) Text(fakeNote, style = GraphiteLabel)
            else -> {
                // plain rows of three: a FlowRow inside the intrinsic-height card would clip its second line
                val all = buildList {
                    st.modelPath?.let { p -> add("size" to fmtBytes(File(p).length())) }
                    addAll(metrics)
                    add("memory" to (rssKb?.let { fmtBytes(it * 1024) } ?: "-"))
                    add("load time" to (st.readyAfterMs?.let { fmt1(it / 1000.0) + " s" } ?: "-"))
                    add("port" to st.port.toString())
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (line in all.chunked(3)) {
                        Row(Modifier.fillMaxWidth()) {
                            for ((k, v) in line) Box(Modifier.weight(1f)) { Metric(k, v) }
                            repeat(3 - line.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
        if (st.state == ServerState.FAILED) {
            Spacer(Modifier.height(10.dp))
            ErrorNote(st.reason ?: "The server stopped.")
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (st.state != ServerState.STOPPED) {
                SecondaryButton("Stop", onClick = { LlamaRuntime.stop(role) })
                Spacer(Modifier.width(12.dp))
            }
            Text(
                if (showLog) "Hide log" else "Show log",
                Modifier.heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically).clickable { showLog = !showLog },
                style = SwarmType.label.copy(textDecoration = TextDecoration.Underline),
            )
        }
        if (showLog) {
            var lines by remember { mutableStateOf(emptyList<String>()) }
            LaunchedEffect(st.state, showLog) {
                while (true) { lines = LlamaRuntime.logTail(role, 8); delay(1000) }
            }
            if (lines.isEmpty()) Text("No log lines yet.", style = GraphiteLabel)
            for (l in lines) Text(l.take(160), style = SwarmType.mono.copy(fontSize = 10.sp, lineHeight = 13.sp, color = SwarmColors.graphite), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(4.dp))
        Hairline()
    }
}

/** "Load models at start": persisted, default on. Read by the service when it starts. */
@Composable
private fun AutoloadRow(on: Boolean) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = on, role = SemanticsRole.Switch, onValueChange = { ModelStore.setAutoload(it) }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("Load models at start", style = SwarmType.body.copy(fontWeight = FontWeight.W500))
            Text(
                if (on) "The chat and embedding models load when Ustaad starts." else "Models load only when you tap Load.",
                style = GraphiteLabel.copy(fontWeight = FontWeight.W400),
            )
        }
        InkSwitch(on)
    }
}

/** Flat switch like the one in the settings sheet: Smudge track when off, Ink when on, round Sheet knob. */
@Composable
private fun InkSwitch(checked: Boolean) {
    val knobX = if (checked) 20.dp else 0.dp
    Box(Modifier.size(width = 52.dp, height = 48.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(width = 44.dp, height = 26.dp)
                .background(if (checked) SwarmColors.ink else SwarmColors.smudge, CircleShape)
                .border(1.5.dp, SwarmColors.ink, CircleShape)
                .padding(2.5.dp),
        ) {
            Box(Modifier.offset(x = knobX).size(18.dp).background(SwarmColors.sheet, CircleShape).border(1.5.dp, SwarmColors.ink, CircleShape))
        }
    }
}

/** A GGUF file of this phone: kind, name from the file, size, state, and the actions that file allows. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FileCard(file: String, entry: CatalogEntry?, p: PresentModel?, dl: DownloadState?, status: Map<Role, ServerStatus>) {
    val loadedAs = Role.entries.firstOrNull { r ->
        status.getValue(r).let { it.state != ServerState.STOPPED && it.modelPath?.endsWith("/$file") == true }
    }
    SwarmCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tag(entry?.role?.let { if (it == Role.EMBED) "EMBEDDER" else "CHAT" } ?: "GGUF")
            Spacer(Modifier.width(8.dp))
            Text(file.removeSuffix(".gguf"), Modifier.weight(1f), style = SwarmType.mono, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(8.dp))
        val sizeText = when {
            p != null -> fmtBytes(p.bytes) + if (!p.complete) " of ${fmtBytes(entry?.bytes ?: 0)}" else ""
            entry != null && entry.bytes > 0 -> fmtBytes(entry.bytes)
            else -> "size not pinned"
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Bottom) {
            Metric("size", sizeText)
            if (p != null && !p.complete) Metric("state", "incomplete")
            Spacer(Modifier.weight(1f))
            loadedAs?.let { Tag("loaded as ${it.tag}") }
        }
        if (entry != null && !entry.structuredOutput) {
            Spacer(Modifier.height(6.dp))
            Text(ModelCatalog.CHAT_ONLY_NOTE, style = GraphiteLabel)
        }
        when (dl) {
            is DownloadState.Downloading -> {
                Spacer(Modifier.height(12.dp))
                val frac = if (dl.total > 0) (dl.done.toFloat() / dl.total).coerceIn(0f, 1f) else 0f
                InkProgress(frac)
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${fmtBytes(dl.done)} of ${if (dl.total > 0) fmtBytes(dl.total) else "?"}", style = SwarmType.mono)
                    Text(fmtBytes(dl.bytesPerSec) + "/s", style = SwarmType.mono)
                }
                Spacer(Modifier.height(8.dp))
                SecondaryButton("Cancel", onClick = { ModelStore.cancelDownload(file) })
            }
            DownloadState.Verifying -> {
                Spacer(Modifier.height(12.dp))
                Text("Checking SHA-256", style = SwarmType.mono)
            }
            is DownloadState.Failed -> {
                Spacer(Modifier.height(10.dp))
                ErrorNote(dl.message)
            }
            null -> {}
        }
        if (dl == null || dl is DownloadState.Failed) {
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (p != null && p.complete) {
                    SecondaryButton("Load as chat", onClick = { ModelsModule.loadModelAsync(Role.CHAT, file) }, modifier = Modifier.fillMaxWidth())
                    SecondaryButton("Load as embedder", onClick = { ModelsModule.loadModelAsync(Role.EMBED, file) }, modifier = Modifier.fillMaxWidth())
                    SecondaryButton("Delete", onClick = { ModelStore.delete(file) }, enabled = loadedAs == null)
                } else if (entry != null) {
                    // Two taps on purpose: a download is the only thing in the app that uses the internet.
                    var armed by remember { mutableStateOf(false) }
                    val size = if (entry.bytes > 0) " " + fmtBytes(entry.bytes) else ""
                    SecondaryButton(
                        if (armed) "Tap again: uses the internet$size" else if (p != null) "Resume download" else "Download$size",
                        onClick = { if (armed) { armed = false; ModelStore.download(entry) } else armed = true },
                        enabled = entry.url.isNotBlank(),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Benchmark page
// ---------------------------------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BenchPage(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val running by Bench.running.collectAsStateWithLifecycle()
    val last by Bench.last.collectAsStateWithLifecycle()
    val text by Bench.text.collectAsStateWithLifecycle()
    val err by Bench.error.collectAsStateWithLifecycle()
    val sustained by Bench.sustained.collectAsStateWithLifecycle()
    val status by LlamaRuntime.status.collectAsStateWithLifecycle()
    val chat = status.getValue(Role.CHAT)
    val ready = chat.state == ServerState.READY
    val health by AppGraph.health.state.collectAsStateWithLifecycle()
    val blocked = Bench.blockReasonFor(health.thermalStatus) // heavy generation is refused when the phone is SEVERE or hotter
    val r = last

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Back",
                Modifier.heightIn(min = 48.dp).wrapContentHeight(Alignment.CenterVertically).clickable(onClick = onBack).padding(end = 16.dp),
                style = SwarmType.label.copy(textDecoration = TextDecoration.Underline),
            )
            Text("Benchmark", style = SwarmType.display)
        }
        val self by AppGraph.registry.self.collectAsStateWithLifecycle()
        Row(verticalAlignment = Alignment.CenterVertically) {
            PhoneToken(self.name, 28.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(self.name, style = SwarmType.label)
                Text(r?.model ?: chat.modelName ?: "no chat model loaded", style = SwarmType.mono.copy(color = SwarmColors.graphite))
            }
        }

        SwarmCard {
            Box(Modifier.fillMaxWidth()) {
                HeroMetric(r?.let { fmt1(it.decodeTokPerSec) } ?: "-", "tok/s decode")
                if (blocked != null) Box(Modifier.align(Alignment.TopEnd)) { Stamp("TOO HOT", caution = true) }
            }
            Spacer(Modifier.height(10.dp))
            MetricRow("Prefill", r?.let { fmt1(it.prefillTokPerSec) } ?: "-", "tok/s")
            MetricRow("Prompt tokens", r?.promptTokens?.toString() ?: "-")
            MetricRow("Generated tokens", r?.completionTokens?.toString() ?: "-")
            MetricRow("Time to first token", r?.let { fmt1(it.ttftMs / 1000.0) } ?: "-", "s")
            MetricRow("Server memory", r?.rssMb?.toString() ?: "-", "MB")
            MetricRow("Server memory peak", r?.peakRssMb?.toString() ?: "-", "MB")
            MetricRow("Thermal at start", r?.thermalStart ?: "-")
            MetricRow("Thermal at end", r?.thermal ?: "-")
            MetricRow("Thermal now", HealthMonitor.thermalName(health.thermalStatus))
            MetricRow("Threads", r?.threads?.toString() ?: "-")
        }

        if (blocked != null) Note(blocked)
        if (!ready) EmptyHint("Load a chat model first.", Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(
                if (running) "Running" else "Run benchmark",
                onClick = { scope.launch { runCatching { Bench.run() } } },
                enabled = ready && !running && blocked == null,
                icon = SwarmIcon.Play,
            )
            SecondaryButton(
                "Run 10 minutes",
                onClick = { scope.launch { runCatching { Bench.runSustained(10) } } },
                enabled = ready && !running && blocked == null,
            )
        }
        err?.let { ErrorNote(it) }

        SwarmCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("10 minute run", style = SwarmType.body.copy(fontWeight = FontWeight.W500))
                if (sustained.isNotEmpty()) Text("mean " + fmt1(sustained.map { it.decodeTokPerSec }.average()) + " tok/s", style = SwarmType.mono)
            }
            if (sustained.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("Not run yet.", style = GraphiteLabel)
            } else {
                TokPerMinuteLine(sustained)
                Text(sustained.joinToString("   ") { "m${it.minute} ${fmt1(it.decodeTokPerSec)}" }, style = SwarmType.mono.copy(fontSize = 11.sp, lineHeight = 14.sp, color = SwarmColors.graphite))
                slowdownNote(sustained)?.let { Note(it, Modifier.padding(top = 6.dp)) }
            }
        }

        if (text.isNotEmpty()) {
            SwarmCard {
                Text("Streamed output", style = GraphiteLabel)
                Spacer(Modifier.height(6.dp))
                StreamingText(text, streaming = running)
            }
        }
    }
}

/** "slower from minute 5" when decode speed fell at least 10 % below the first minute; null when it held or there is too little data. */
internal fun slowdownNote(points: List<SustainedPoint>): String? {
    if (points.size < 3) return null
    val first = points.first().decodeTokPerSec
    val slow = points.drop(1).firstOrNull { it.decodeTokPerSec < first * 0.9 } ?: return null
    return "slower from minute ${slow.minute}"
}

/** Ink line of tok/s by minute: one dot per minute, faint baseline, hand-drawn look from round joins. */
@Composable
private fun TokPerMinuteLine(points: List<SustainedPoint>) {
    val max = (points.maxOf { it.decodeTokPerSec } * 1.15).coerceAtLeast(1.0)
    Canvas(Modifier.fillMaxWidth().height(84.dp).padding(vertical = 8.dp)) {
        val n = points.size
        val xs = points.indices.map { i -> if (n == 1) size.width / 2 else size.width * i / (n - 1) }
        val ys = points.map { p -> (size.height * (1 - p.decodeTokPerSec / max)).toFloat() }
        drawLine(SwarmColors.smudge, Offset(0f, size.height), Offset(size.width, size.height), 2.dp.toPx())
        if (n > 1) {
            val path = Path()
            for (i in 0 until n) if (i == 0) path.moveTo(xs[i], ys[i]) else path.lineTo(xs[i], ys[i])
            drawPath(path, SwarmColors.ink, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        for (i in 0 until n) drawCircle(SwarmColors.ink, 3.dp.toPx(), Offset(xs[i], ys[i]))
    }
}

internal fun fmt1(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)

/** 1234567890 -> "1.15 GB" (binary units shown with the usual MB/GB names). */
internal fun fmtBytes(b: Long): String = when {
    b >= 1L shl 30 -> String.format(java.util.Locale.US, "%.2f GB", b / (1024.0 * 1024 * 1024))
    b >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", b / (1024.0 * 1024))
    b >= 1L shl 10 -> String.format(java.util.Locale.US, "%.0f KB", b / 1024.0)
    else -> "$b B"
}
