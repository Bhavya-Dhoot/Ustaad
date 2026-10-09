package com.swarmos.app.llm

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swarmos.app.ui.LabelStyle
import com.swarmos.app.ui.MetricStyle
import com.swarmos.app.ui.SwarmColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Models tab. Structure follows ui-kit/screens/models and benchmark_phone_a; styling comes later from the UI kit. */
@Composable
fun ModelsScreen() {
    var showBench by remember { mutableStateOf(false) }
    if (showBench) BenchPanel(onBack = { showBench = false }) else ModelsMain(onBench = { showBench = true })
}

@Composable
private fun ModelsMain(onBench: () -> Unit) {
    val status by LlamaRuntime.status.collectAsStateWithLifecycle()
    val downloads by ModelStore.downloads.collectAsStateWithLifecycle()
    val wan by ModelStore.wanBytes.collectAsStateWithLifecycle()
    val tick by ModelStore.tick.collectAsStateWithLifecycle()
    val bench by Bench.last.collectAsStateWithLifecycle()
    val lastStats by LlamaRuntime.lastStats.collectAsStateWithLifecycle()
    val embedMs by LlamaRuntime.lastEmbedMsPerText.collectAsStateWithLifecycle()

    // The file list can change without a state change (adb push), so re-list every 2 s while visible.
    var present by remember { mutableStateOf<List<PresentModel>>(emptyList()) }
    var rss by remember { mutableStateOf<Map<Role, Long?>>(emptyMap()) }
    LaunchedEffect(tick) {
        while (true) {
            present = ModelStore.present()
            rss = Role.entries.associateWith { LlamaRuntime.rssKb(it) }
            delay(2000)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Models", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Button(onClick = onBench) { Text("Run benchmark") }
        }

        Text("Loaded on this phone", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        ServerRow(
            tag = "CHAT", st = status.getValue(Role.CHAT), rssKb = rss[Role.CHAT],
            metrics = listOf(
                "prefill" to (bench?.prefillTokPerSec?.let { fmt1(it) + " tok/s" } ?: "-"),
                "decode" to (lastStats?.decodeTokPerSec?.takeIf { it > 0 }?.let { fmt1(it) + " tok/s" } ?: "-"),
            ),
        )
        ServerRow(
            tag = "EMBEDDER", st = status.getValue(Role.EMBED), rssKb = rss[Role.EMBED],
            metrics = listOf("latency" to (embedMs?.let { fmt1(it) + " ms per text" } ?: "-")),
        )
        val rpc = status.getValue(Role.RPC)
        if (rpc.state != ServerState.STOPPED) ServerRow("RPC", rpc, rss[Role.RPC], emptyList())

        Text("On this phone", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text("Downloads use the internet. WAN bytes this session: ${fmtBytes(wan)}", style = LabelStyle)

        val presentByFile = present.associateBy { it.file }
        for (e in ModelCatalog.entries) {
            FileCard(e.file, e.label, e, presentByFile[e.file], downloads[e.file], status)
        }
        for (p in present) {
            if (ModelCatalog.byFile(p.file) == null) FileCard(p.file, p.file.removeSuffix(".gguf"), null, p, downloads[p.file], status)
        }
    }
}

@Composable
private fun ServerRow(tag: String, st: ServerStatus, rssKb: Long?, metrics: List<Pair<String, String>>) {
    val scope = rememberCoroutineScope()
    var showLog by remember { mutableStateOf(false) }
    val role = st.role
    Column(Modifier.fillMaxWidth().border(1.dp, SwarmColors.Line, RoundedCornerShape(8.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tag(tag)
            Spacer(Modifier.width(8.dp))
            Text(st.modelName ?: "not loaded", style = MetricStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(st.state.name, style = MetricStyle, color = stateColor(st.state))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            for ((k, v) in metrics) Column { Text(k, style = LabelStyle); Text(v, style = MetricStyle) }
            Column { Text("memory", style = LabelStyle); Text(rssKb?.let { fmtBytes(it * 1024) } ?: "-", style = MetricStyle) }
            Column { Text("load time", style = LabelStyle); Text(st.readyAfterMs?.let { fmt1(it / 1000.0) + " s" } ?: "-", style = MetricStyle) }
            Column { Text("port", style = LabelStyle); Text(st.port.toString(), style = MetricStyle) }
        }
        st.reason?.let { Text(it, color = SwarmColors.Bad, fontSize = 11.sp, maxLines = 4, overflow = TextOverflow.Ellipsis) }
        Row {
            OutlinedButton(onClick = { LlamaRuntime.stop(role) }, enabled = st.state != ServerState.STOPPED) { Text("Stop") }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide log" else "Show log") }
        }
        if (showLog) {
            var lines by remember { mutableStateOf(emptyList<String>()) }
            LaunchedEffect(st.state, showLog) {
                while (true) { lines = LlamaRuntime.logTail(role, 8); delay(1000) }
            }
            for (l in lines) Text(l.take(160), fontSize = 10.sp, color = SwarmColors.Dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun FileCard(file: String, label: String, entry: CatalogEntry?, p: PresentModel?, dl: DownloadState?, status: Map<Role, ServerStatus>) {
    val scope = rememberCoroutineScope()
    val loadedAs = Role.entries.firstOrNull { r ->
        status.getValue(r).let { it.state != ServerState.STOPPED && it.modelPath?.endsWith("/$file") == true }
    }
    Column(Modifier.fillMaxWidth().border(1.dp, SwarmColors.Line, RoundedCornerShape(8.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tag(entry?.role?.let { if (it == Role.EMBED) "EMBEDDER" else "CHAT" } ?: "GGUF")
            Spacer(Modifier.width(8.dp))
            Text(label, style = MetricStyle, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            loadedAs?.let { Text("loaded as ${it.tag}", style = MetricStyle, color = SwarmColors.Ok) }
        }
        val sizeText = when {
            p != null -> fmtBytes(p.bytes) + if (!p.complete) " of ${fmtBytes(entry?.bytes ?: 0)} (incomplete)" else ""
            entry != null && entry.bytes > 0 -> fmtBytes(entry.bytes)
            else -> "size not pinned"
        }
        Text(file + "   " + sizeText, style = LabelStyle)
        when (dl) {
            is DownloadState.Downloading -> {
                val frac = if (dl.total > 0) (dl.done.toFloat() / dl.total).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth().height(3.dp))
                Row {
                    Text("${fmtBytes(dl.done)} of ${if (dl.total > 0) fmtBytes(dl.total) else "?"}", style = MetricStyle, modifier = Modifier.weight(1f))
                    Text(fmtBytes(dl.bytesPerSec) + "/s", style = MetricStyle)
                }
                TextButton(onClick = { ModelStore.cancelDownload(file) }) { Text("Cancel") }
            }
            DownloadState.Verifying -> Text("verifying SHA-256", style = MetricStyle, color = SwarmColors.Warn)
            is DownloadState.Failed -> Text(dl.message, color = SwarmColors.Bad, fontSize = 11.sp)
            null -> {}
        }
        if (dl == null || dl is DownloadState.Failed) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (p != null && p.complete) {
                    OutlinedButton(onClick = { ModelsModule.loadModelAsync(Role.CHAT, file) }) { Text("Load as chat") }
                    OutlinedButton(onClick = { ModelsModule.loadModelAsync(Role.EMBED, file) }) { Text("Load as embedder") }
                    TextButton(onClick = { ModelStore.delete(file) }, enabled = loadedAs == null) { Text("Delete") }
                } else if (entry != null) {
                    // Two taps on purpose: a download is the only thing in the app that uses the internet.
                    var armed by remember { mutableStateOf(false) }
                    Button(
                        onClick = { if (armed) { armed = false; ModelStore.download(entry) } else armed = true },
                        enabled = entry.url.isNotBlank(),
                    ) {
                        val size = if (entry.bytes > 0) " " + fmtBytes(entry.bytes) else ""
                        Text(if (armed) "Tap again: uses the internet$size" else if (p != null) "Resume download" else "Download$size")
                    }
                }
            }
        }
    }
}

@Composable
private fun BenchPanel(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val running by Bench.running.collectAsStateWithLifecycle()
    val last by Bench.last.collectAsStateWithLifecycle()
    val text by Bench.text.collectAsStateWithLifecycle()
    val err by Bench.error.collectAsStateWithLifecycle()
    val sustained by Bench.sustained.collectAsStateWithLifecycle()
    val chat by LlamaRuntime.status.collectAsStateWithLifecycle()
    val ready = chat.getValue(Role.CHAT).state == ServerState.READY
    val health by com.swarmos.app.AppGraph.health.state.collectAsStateWithLifecycle()
    val blocked = Bench.blockReasonFor(health.thermalStatus) // heavy generation is refused when the phone is SEVERE or hotter

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Benchmark", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Text(last?.model ?: chat.getValue(Role.CHAT).modelName ?: "no chat model loaded", style = LabelStyle)

        Column(Modifier.fillMaxWidth().border(1.dp, SwarmColors.Line, RoundedCornerShape(8.dp)).padding(12.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(last?.let { fmt1(it.decodeTokPerSec) } ?: "-", fontSize = 44.sp, fontWeight = FontWeight.Bold, style = MetricStyle.copy(fontSize = 44.sp))
                Spacer(Modifier.width(8.dp))
                Text("tok/s decode", color = SwarmColors.Dim)
            }
            val r = last
            BenchRow("Prefill", r?.let { fmt1(it.prefillTokPerSec) + " tok/s" })
            BenchRow("Prompt tokens", r?.promptTokens?.toString())
            BenchRow("Generated tokens", r?.completionTokens?.toString())
            BenchRow("Time to first token", r?.let { fmt1(it.ttftMs / 1000.0) + " s" })
            BenchRow("Server memory", r?.rssMb?.let { "$it MB" })
            BenchRow("Server memory peak", r?.peakRssMb?.let { "$it MB" })
            BenchRow("Thermal at start", r?.thermalStart)
            BenchRow("Thermal at end", r?.thermal)
            BenchRow("Thermal now", com.swarmos.app.core.HealthMonitor.thermalName(health.thermalStatus))
            BenchRow("Threads", r?.threads?.toString())
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scope.launch { runCatching { Bench.run() } } }, enabled = ready && !running && blocked == null) {
                Text(if (running) "Running..." else "Run benchmark")
            }
            OutlinedButton(onClick = { scope.launch { runCatching { Bench.runSustained(10) } } }, enabled = ready && !running && blocked == null) {
                Text("Run 10 minutes")
            }
        }
        blocked?.let { Text(it, color = SwarmColors.Warn) }
        if (!ready) Text("Load a chat model first (Back).", color = SwarmColors.Warn)
        err?.let { Text(it, color = SwarmColors.Bad, fontSize = 11.sp) }

        Column(Modifier.fillMaxWidth().border(1.dp, SwarmColors.Line, RoundedCornerShape(8.dp)).padding(12.dp)) {
            Text("10 minute run (tok/s by minute)", fontSize = 14.sp)
            if (sustained.isEmpty()) {
                Text("not run yet", color = SwarmColors.Dim, fontSize = 12.sp)
            } else {
                Text("mean " + fmt1(sustained.map { it.decodeTokPerSec }.average()) + " tok/s", style = MetricStyle)
                TokPerMinuteLine(sustained)
                Text(sustained.joinToString("   ") { "m${it.minute} ${fmt1(it.decodeTokPerSec)}" }, style = LabelStyle)
            }
        }

        if (text.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().border(1.dp, SwarmColors.Line, RoundedCornerShape(8.dp)).padding(12.dp)) {
                Text("Streamed output", style = LabelStyle)
                Text(text, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun BenchRow(label: String, value: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, modifier = Modifier.weight(1f))
        Text(value ?: "-", style = MetricStyle)
    }
}

@Composable
private fun TokPerMinuteLine(points: List<SustainedPoint>) {
    val max = (points.maxOf { it.decodeTokPerSec } * 1.15).coerceAtLeast(1.0)
    Canvas(Modifier.fillMaxWidth().height(80.dp).padding(vertical = 6.dp)) {
        val path = Path()
        val n = points.size
        points.forEachIndexed { i, p ->
            val x = if (n == 1) size.width / 2 else size.width * i / (n - 1)
            val y = (size.height * (1 - p.decodeTokPerSec / max)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, SwarmColors.ink, style = Stroke(width = 3f))
        drawLine(SwarmColors.Line, Offset(0f, size.height), Offset(size.width, size.height), 2f)
    }
}

@Composable
private fun Tag(text: String) {
    Text(
        text, fontSize = 10.sp, style = MetricStyle.copy(fontSize = 10.sp),
        modifier = Modifier.border(1.dp, SwarmColors.Dim, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

private fun stateColor(s: ServerState) = when (s) {
    ServerState.READY -> SwarmColors.Ok
    ServerState.STARTING -> SwarmColors.Warn
    ServerState.FAILED -> SwarmColors.Bad
    ServerState.STOPPED -> SwarmColors.Dim
}

internal fun fmt1(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)

/** 1234567890 -> "1.15 GB" (binary units shown with the usual MB/GB names). */
internal fun fmtBytes(b: Long): String = when {
    b >= 1L shl 30 -> String.format(java.util.Locale.US, "%.2f GB", b / (1024.0 * 1024 * 1024))
    b >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", b / (1024.0 * 1024))
    b >= 1L shl 10 -> String.format(java.util.Locale.US, "%.0f KB", b / 1024.0)
    else -> "$b B"
}
