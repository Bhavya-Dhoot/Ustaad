package com.swarmos.app.pdu

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swarmos.app.AppGraph
import com.swarmos.app.ui.MetricStyle
import com.swarmos.app.ui.SwarmColors
import com.swarmos.app.ui.accentFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The Data tab: library, ask and answer, query audit, scan a document. Plain components; the styled kit replaces them later. */
@Composable
fun PduScreen() {
    var page by rememberSaveable { mutableStateOf("library") }
    LaunchedEffect(Unit) { PduEngine.start() }
    if (page != "library") BackHandler { page = if (page == "audit") "ask" else "library" }
    // the tab body has no Surface above it, so text would default to black on the dark background
    CompositionLocalProvider(LocalContentColor provides SwarmColors.Text) {
        Box(Modifier.fillMaxSize()) {
            when (page) {
                "ask" -> AskPage(onBack = { page = "library" }, onAudit = { page = "audit" })
                "audit" -> AuditPage(onBack = { page = "ask" })
                "scan" -> ScanPage(onBack = { page = "library" })
                else -> LibraryPage(go = { page = it })
            }
        }
    }
}

// ---- library --------------------------------------------------------------------------------------------

@Composable
private fun LibraryPage(go: (String) -> Unit) {
    val ctx = LocalContext.current
    val counts by PduEngine.counts.collectAsStateWithLifecycle()
    val progress by PduEngine.progress.collectAsStateWithLifecycle()
    val folders by PduEngine.folders.collectAsStateWithLifecycle()
    var note by remember { mutableStateOf<String?>(null) }

    val photoPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) PduEngine.scope.launch { PduEngine.indexPhotos() } else note = "Photos permission was not granted"
    }
    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            PduEngine.scope.launch { PduEngine.indexTree(uri) }
        }
    }
    val trees = remember(progress.running) { ctx.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Data Union", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Every phone searches its own files. Only short snippets travel.", color = SwarmColors.Dim)

        Box(Modifier.fillMaxWidth().border(1.dp, SwarmColors.Line, RoundedCornerShape(8.dp)).padding(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(AppGraph.embedder.modelName.let { if (it == "FAKE") "embedder FAKE" else "embedder $it" }, style = MetricStyle, color = SwarmColors.Dim)
                Text("%,d".format(counts.chunks), style = MetricStyle.copy(fontSize = 40.sp), color = SwarmColors.Cyan)
                Text("chunks indexed on this phone")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        if (counts.lastRunMs > 0) "last indexed " + DateUtils.getRelativeTimeSpanString(counts.lastRunMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) else "not indexed yet",
                        style = MetricStyle, color = SwarmColors.Dim,
                    )
                    if (PduEngine.lastSearchMs >= 0) Text("last search ${PduEngine.lastSearchMs} ms", style = MetricStyle, color = SwarmColors.Dim)
                }
                if (progress.running) {
                    LinearProgressIndicator(progress = { if (progress.total > 0) progress.done / progress.total.toFloat() else 0f }, Modifier.fillMaxWidth())
                    Text("${progress.label}  ${progress.done}/${progress.total}  ${progress.current}", style = MetricStyle, maxLines = 2)
                }
                progress.last?.let { r ->
                    val line = r.error ?: "last run: ${r.indexed} indexed, ${r.noText} without text, ${r.unchanged} unchanged" +
                        (if (r.ocrCount > 0) ", OCR ${r.ocrMsAvg} ms per image" else "") + ", ${r.totalMs} ms"
                    if (!progress.running) Text(line, style = MetricStyle, color = SwarmColors.Dim)
                }
            }
        }

        Text("Sources", style = MaterialTheme.typography.titleMedium, fontSize = 20.sp)
        SourceRow("Photos", "${counts.photos} with text" + if (counts.skipped > 0) ", ${counts.skipped} skipped" else "", "Index", !progress.running) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED) {
                PduEngine.scope.launch { PduEngine.indexPhotos() }
            } else photoPermission.launch(Manifest.permission.READ_MEDIA_IMAGES)
        }
        SourceRow("Documents", "${counts.documents} files", "Pick folder", !progress.running) { pickTree.launch(null) }
        for (t in trees) {
            SourceRow(Uri.decode(t.lastPathSegment ?: t.toString()), "folder you picked", "Index", !progress.running) {
                PduEngine.scope.launch { PduEngine.indexTree(t) }
            }
        }
        SourceRow("Scanned", "${counts.scans} items", "Scan", true) { go("scan") }
        note?.let { Text(it, color = SwarmColors.Warn) }

        if (folders.isNotEmpty()) {
            Text("Private folders", style = MaterialTheme.typography.titleMedium, fontSize = 20.sp)
            for (f in folders) {
                Row(
                    Modifier.fillMaxWidth().border(1.dp, SwarmColors.Line, RoundedCornerShape(8.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(f.folder, fontWeight = FontWeight.SemiBold)
                        Text(if (f.isPrivate) "${f.docs} files, answered on this phone only" else "${f.docs} files, may answer peer queries", style = MetricStyle, color = SwarmColors.Dim)
                    }
                    Switch(checked = f.isPrivate, onCheckedChange = { PduEngine.setFolderPrivate(f.folder, it) })
                }
            }
        }

        Button(onClick = { go("ask") }, Modifier.fillMaxWidth()) { Text("Ask the swarm") }
        OutlinedButton(onClick = { go("scan") }, Modifier.fillMaxWidth()) { Text("Scan a document") }
    }
}

@Composable
private fun SourceRow(title: String, sub: String, action: String, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().border(1.dp, SwarmColors.Line, RoundedCornerShape(8.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(sub, style = MetricStyle, color = SwarmColors.Dim)
        }
        OutlinedButton(onClick = onClick, enabled = enabled) { Text(action) }
    }
}

// ---- ask and answer ------------------------------------------------------------------------------------

@Composable
private fun AskPage(onBack: () -> Unit, onAudit: () -> Unit) {
    val scope = rememberCoroutineScope()
    val q by PduEngine.query.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    var opening by remember { mutableStateOf<String?>(null) }
    val busy = q != null && q?.phase != "done"

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Data Union", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        OutlinedTextField(
            value = text, onValueChange = { text = it }, label = { Text("Question") }, modifier = Modifier.fillMaxWidth(), minLines = 1, maxLines = 3,
        )
        Button(
            onClick = { val t = text.trim(); if (t.isNotEmpty()) PduEngine.scope.launch { PduEngine.ask(t) } },
            enabled = !busy && text.isNotBlank(), modifier = Modifier.fillMaxWidth(),
        ) { Text(if (busy) "Searching..." else "Ask") }

        val cur = q
        if (cur != null) {
            TimingStrip(cur)
            Text("Sources", style = MaterialTheme.typography.titleMedium, fontSize = 20.sp)
            if (cur.sources.isEmpty()) Text(if (cur.phase == "searching") "waiting for phones..." else "no matches", color = SwarmColors.Dim)
            for (s in cur.sources) SourceCard(s) {
                scope.launch { opening = PduEngine.openSource(s).let { r -> if (r.opened) "opened via ${r.via}" else "could not open: ${r.error}" } }
            }
            if (cur.incomplete) {
                val missing = cur.devices.filter { !it.local && it.status != "ok" && it.status != "waiting" }
                Column(Modifier.fillMaxWidth().border(1.dp, SwarmColors.Warn, RoundedCornerShape(8.dp)).padding(12.dp)) {
                    Text("INCOMPLETE: answer may be missing files", color = SwarmColors.Warn, fontWeight = FontWeight.Bold)
                    for (d in missing) Text("${d.name}: ${d.status}${d.detail?.let { " ($it)" } ?: ""}", style = MetricStyle)
                }
            }
            opening?.let { Text(it, style = MetricStyle, color = SwarmColors.Dim) }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Answer", style = MaterialTheme.typography.titleMedium, fontSize = 20.sp)
                Spacer(Modifier.width(8.dp))
                Text(if (cur.model == "FAKE") "FAKE model, canned text" else cur.model, style = MetricStyle, color = if (cur.model == "FAKE") SwarmColors.Warn else SwarmColors.Dim)
            }
            Box(Modifier.fillMaxWidth().border(1.dp, SwarmColors.Line, RoundedCornerShape(8.dp)).padding(12.dp)) {
                if (cur.answer.isEmpty()) Text(if (cur.phase == "done") "no answer" else "...", color = SwarmColors.Dim)
                else Text(withCitations(cur.answer) { n ->
                    cur.sources.getOrNull(n - 1)?.let { s ->
                        scope.launch { opening = PduEngine.openSource(s).let { r -> if (r.opened) "opened via ${r.via}" else "could not open: ${r.error}" } }
                    }
                })
            }
            OutlinedButton(onClick = onAudit, Modifier.fillMaxWidth()) { Text("Query audit") }
        }
    }
}

@Composable
private fun TimingStrip(q: QueryUi) {
    fun ms(v: Long) = if (v < 0) "-" else "$v ms"
    val t = q.timings
    Column(Modifier.fillMaxWidth().border(1.dp, SwarmColors.Line, RoundedCornerShape(8.dp)).padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("embed ${ms(t.embedMs)}", style = MetricStyle)
            Text("search ${ms(t.localSearchMs)}", style = MetricStyle)
            Text("first word ${ms(t.ttftMs)}", style = MetricStyle)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (d in q.devices.filter { !it.local }) Text("${d.name} ${if (d.status == "waiting") "-" else ms(d.rttMs)}", style = MetricStyle)
            Text("total ${ms(t.totalMs)}", style = MetricStyle)
        }
    }
}

@Composable
private fun SourceCard(s: SourceUi, onOpen: () -> Unit) {
    val accent = accentFor(s.deviceName)
    Row(
        Modifier.fillMaxWidth().border(1.dp, SwarmColors.Line, RoundedCornerShape(8.dp)).clickable(onClick = onOpen),
    ) {
        Box(Modifier.width(5.dp).height(80.dp).background(accent))
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("[${s.n}]", style = MetricStyle, color = accent)
                Text(s.deviceName, fontWeight = FontWeight.Bold, color = accent)
                Text(PromptBuilder.day(s.hit.dateMs), style = MetricStyle, color = SwarmColors.Dim)
            }
            Text(s.hit.title, style = MetricStyle)
            Text(s.hit.snippet, style = MaterialTheme.typography.bodyMedium, maxLines = 6)
            if (s.localPrivate) Text("private folder, this phone only", style = MetricStyle, color = SwarmColors.Warn)
        }
    }
}

/** The answer text with every [n] turned into a tappable link. */
private fun withCitations(answer: String, onCite: (Int) -> Unit) = buildAnnotatedString {
    var last = 0
    for (m in Regex("\\[(\\d+)]").findAll(answer)) {
        append(answer.substring(last, m.range.first))
        val n = m.groupValues[1].toInt()
        withLink(LinkAnnotation.Clickable("cite$n", TextLinkStyles(SpanStyle(color = SwarmColors.Cyan, fontWeight = FontWeight.Bold))) { onCite(n) }) { append(m.value) }
        last = m.range.last + 1
    }
    append(answer.substring(last))
}

// ---- audit ---------------------------------------------------------------------------------------------

@Composable
private fun AuditPage(onBack: () -> Unit) {
    val q by PduEngine.query.collectAsStateWithLifecycle()
    val served by PduEngine.served.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var verify by remember { mutableStateOf<String?>(null) }
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Query audit", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        val cur = q
        if (cur == null) Text("No query yet on this phone.", color = SwarmColors.Dim)
        else {
            Text("Sent from this phone", style = MaterialTheme.typography.titleMedium, fontSize = 18.sp)
            Column(Modifier.fillMaxWidth().border(1.dp, SwarmColors.Line, RoundedCornerShape(8.dp)).padding(12.dp)) {
                Text("\"${cur.text}\"")
                val peers = cur.devices.filter { !it.local && it.status != "offline" }
                Text("${peers.firstOrNull()?.sentBytes ?: 0} bytes to ${peers.size} phones, query text only", style = MetricStyle, color = SwarmColors.Dim)
            }
            Text("Returned by each phone", style = MaterialTheme.typography.titleMedium, fontSize = 18.sp)
            for (d in cur.devices) {
                val accent = accentFor(d.name)
                Column(Modifier.fillMaxWidth().border(1.dp, accent, RoundedCornerShape(8.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (d.local) "${d.name} (this phone)" else d.name, fontWeight = FontWeight.Bold, color = accent)
                    when (d.status) {
                        "ok" -> {
                            Text("${d.hits} matches, ${if (d.local) "" else "${d.bytes} bytes, "}${if (d.local) d.tookMs else d.rttMs} ms", style = MetricStyle)
                            if (!d.local) for (t in d.titles) Text(t, style = MetricStyle, color = SwarmColors.Dim)
                            if (d.withheldPrivate > 0) Text("${d.withheldPrivate} match withheld (private folder)", style = MetricStyle, color = SwarmColors.Warn)
                        }
                        "waiting" -> Text("waiting", style = MetricStyle, color = SwarmColors.Dim)
                        else -> Text("${d.status}${d.detail?.let { ": $it" } ?: ""}", style = MetricStyle, color = SwarmColors.Bad)
                    }
                }
            }
            cur.auditHash?.let { Text("audit log entry ${it.take(8)}...${it.takeLast(4)}", style = MetricStyle, color = SwarmColors.Dim) }
        }

        Text("Sent to other phones from this phone", style = MaterialTheme.typography.titleMedium, fontSize = 18.sp)
        if (served.isEmpty()) Text("No peer has asked this phone yet.", color = SwarmColors.Dim)
        for (s in served) Column(Modifier.fillMaxWidth().border(1.dp, SwarmColors.Line, RoundedCornerShape(8.dp)).padding(12.dp)) {
            Text("${fmt.format(Date(s.atMs))}  ${s.peer}  query ${s.qid}", style = MetricStyle)
            Text("${s.n} snippets, ${s.bytes} bytes, ${s.withheldPrivate} withheld", style = MetricStyle)
            for (t in s.titles) Text(t, style = MetricStyle, color = SwarmColors.Dim)
        }

        OutlinedButton(
            onClick = { scope.launch { verify = withContext(Dispatchers.IO) { AppGraph.audit.verify() }.let { if (it.ok) "CHAIN OK, ${it.entries} entries" else "CHAIN BROKEN at line ${it.badLine}: ${it.reason}" } } },
            Modifier.fillMaxWidth(),
        ) { Text("Verify local audit ledger") }
        verify?.let { Text(it, style = MetricStyle, color = if (it.startsWith("CHAIN OK")) SwarmColors.Ok else SwarmColors.Bad) }
    }
}

// ---- scan a document -----------------------------------------------------------------------------------

@Composable
private fun ScanPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var hasCamera by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }
    LaunchedEffect(Unit) { if (!hasCamera) askCamera.launch(Manifest.permission.CAMERA) }

    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }
    var shot by remember { mutableStateOf<File?>(null) }
    var recognised by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }
    var keepPrivate by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Scan document", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        if (shot == null) {
            if (!hasCamera) Text("Camera permission is needed to scan.", color = SwarmColors.Warn)
            else {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { c ->
                        PreviewView(c).also { pv ->
                            val future = ProcessCameraProvider.getInstance(c)
                            future.addListener({
                                val provider = future.get()
                                val preview = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                                provider.unbindAll()
                                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                            }, ContextCompat.getMainExecutor(c))
                        }
                    },
                )
                Button(
                    onClick = {
                        val f = File(ctx.cacheDir, "scan-${System.currentTimeMillis()}.jpg")
                        imageCapture.takePicture(
                            ImageCapture.OutputFileOptions.Builder(f).build(), ContextCompat.getMainExecutor(ctx),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                                    shot = f
                                    status = "reading text..."
                                    scope.launch {
                                        val text = withContext(Dispatchers.Default) {
                                            val bmp = Ocr.decode(ctx, Uri.fromFile(f).toString())
                                            Ocr.recognize(bmp).also { bmp.recycle() }
                                        }
                                        recognised = text
                                        title = text.lineSequence().map { it.trim() }.firstOrNull { it.length >= 3 }?.take(40)
                                            ?: "Scan " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
                                        status = null
                                    }
                                }

                                override fun onError(e: ImageCaptureException) { status = "capture failed: ${e.message}" }
                            },
                        )
                    },
                    Modifier.fillMaxWidth(),
                ) { Text("Capture") }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Text found", style = MaterialTheme.typography.titleMedium, fontSize = 20.sp)
                Box(Modifier.fillMaxWidth().border(1.dp, SwarmColors.Line, RoundedCornerShape(8.dp)).padding(12.dp)) {
                    Text(recognised?.ifBlank { "no text recognised" } ?: "reading text...", style = MetricStyle)
                }
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Keep private", fontWeight = FontWeight.SemiBold)
                        Text("Never shared with other phones", color = SwarmColors.Dim)
                    }
                    Switch(checked = keepPrivate, onCheckedChange = { keepPrivate = it })
                }
                val text = recognised
                Button(
                    enabled = text != null && text.trim().length >= PduIndexer.MIN_OCR_CHARS && title.isNotBlank(),
                    onClick = {
                        val file = shot ?: return@Button
                        status = "indexing..."
                        scope.launch {
                            val rep = withContext(Dispatchers.IO) {
                                val (uri, modified) = PduIndexer.saveScan(ctx, file, keepPrivate)
                                PduEngine.indexScan(uri, modified, title.trim(), keepPrivate, text!!)
                            }
                            if (rep.error == null && rep.indexed > 0) { file.delete(); onBack() } else status = rep.error ?: "nothing was indexed"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Add to index") }
                OutlinedButton(onClick = { shot?.delete(); shot = null; recognised = null; status = null }, Modifier.fillMaxWidth()) { Text("Retake") }
                if (text != null && text.trim().length < PduIndexer.MIN_OCR_CHARS) Text("Fewer than ${PduIndexer.MIN_OCR_CHARS} characters found, nothing to index.", color = SwarmColors.Warn)
            }
        }
        status?.let { Text(it, style = MetricStyle, color = SwarmColors.Dim) }
    }
}
