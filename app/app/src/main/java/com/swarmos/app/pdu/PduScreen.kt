package com.swarmos.app.pdu

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swarmos.app.AppGraph
import com.swarmos.app.ui.Hairline
import com.swarmos.app.ui.HeroMetric
import com.swarmos.app.ui.InkProgress
import com.swarmos.app.ui.InkSwitch
import com.swarmos.app.ui.LineIcon
import com.swarmos.app.ui.MetricRow
import com.swarmos.app.ui.Note
import com.swarmos.app.ui.PrimaryButton
import com.swarmos.app.ui.SecondaryButton
import com.swarmos.app.ui.SectionTitle
import com.swarmos.app.ui.Stamp
import com.swarmos.app.ui.SwarmCard
import com.swarmos.app.ui.SwarmColors
import com.swarmos.app.ui.SwarmIcon
import com.swarmos.app.ui.SwarmShape
import com.swarmos.app.ui.SwarmType
import com.swarmos.app.ui.Tag
import kotlinx.coroutines.launch

/** The Data tab: library, ask and answer, query audit, scan a document. */
@Composable
fun PduScreen() {
    var page by rememberSaveable { mutableStateOf("library") }
    LaunchedEffect(Unit) { PduEngine.start() }
    if (page != "library") BackHandler { page = if (page == "audit") "ask" else "library" }
    // the tab body sits on the bare Whiteboard, so give text its Ink colour explicitly
    CompositionLocalProvider(LocalContentColor provides SwarmColors.ink) {
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
    val self by AppGraph.registry.self.collectAsStateWithLifecycle()
    val counts by PduEngine.counts.collectAsStateWithLifecycle()
    val progress by PduEngine.progress.collectAsStateWithLifecycle()
    val folders by PduEngine.folders.collectAsStateWithLifecycle()
    val emb by PduEngine.embedder.collectAsStateWithLifecycle()
    val reindexNeeded by PduEngine.reindexNeeded.collectAsStateWithLifecycle()
    // nothing is indexed, scanned or wiped without a real, loaded embedder, and a stale index waits for the owner
    val canIndex = emb.ready && !reindexNeeded && !progress.running
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

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Data Union", style = SwarmType.display)
            Text("Every phone searches its own files. Only short snippets travel.", style = SwarmType.body.copy(color = SwarmColors.graphite))
        }

        // the store of this phone: the one number that matters here
        SwarmCard(owner = self.name) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("on this phone", style = SwarmType.label.copy(color = SwarmColors.graphite))
                when {
                    !emb.ready -> Stamp("Embedder not loaded", caution = true, small = true)
                    reindexNeeded -> Stamp("Re-index needed", caution = true, small = true)
                    else -> Tag(emb.name)
                }
            }
            val lastSearch = PduEngine.lastSearchMs
            HeroMetric("%,d".format(counts.chunks), "chunks", note = if (lastSearch >= 0) "last search $lastSearch ms" else null)
            Text("indexed on this phone", style = SwarmType.body.copy(color = SwarmColors.graphite))
            Spacer(Modifier.height(8.dp))
            Hairline()
            MetricRow(
                "last indexed",
                if (counts.lastRunMs > 0) DateUtils.getRelativeTimeSpanString(counts.lastRunMs, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS).toString() else "never",
            )
            if (!emb.ready) {
                Spacer(Modifier.height(6.dp))
                Text("Load the embedding model in Models, then index. Nothing is indexed or removed until then.", style = SwarmType.body.copy(fontSize = 14.sp, lineHeight = 20.sp))
            } else if (reindexNeeded) {
                Spacer(Modifier.height(6.dp))
                Text("This index was made with another embedding model or prefix. Questions use keywords only until it is rebuilt.", style = SwarmType.body.copy(fontSize = 14.sp, lineHeight = 20.sp))
                Spacer(Modifier.height(8.dp))
                SecondaryButton("Re-index everything", { PduEngine.scope.launch { PduEngine.reindexAll() } }, enabled = !progress.running)
            }
            if (progress.running) {
                Spacer(Modifier.height(6.dp))
                InkProgress(if (progress.total > 0) progress.done / progress.total.toFloat() else 0f)
                Text("${progress.label}  ${progress.done}/${progress.total}  ${progress.current}", style = SwarmType.mono, maxLines = 2)
            }
            progress.last?.takeIf { !progress.running }?.let { r ->
                val line = r.error ?: "${r.indexed} indexed, ${r.noText} without text, ${r.unchanged} unchanged" +
                    (if (r.ocrCount > 0) ", OCR ${r.ocrMsAvg} ms per image" else "") + ", ${r.totalMs} ms"
                Text(line, style = SwarmType.mono.copy(color = SwarmColors.graphite))
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("Sources")
            if (counts.chunks == 0 && !progress.running) Note("nothing indexed yet, tap Index on Photos", rotation = -1.5f)
            SourceRow(SwarmIcon.Photo, "Photos", "${counts.photos} with text" + if (counts.skipped > 0) ", ${counts.skipped} skipped" else "", "Index", canIndex) {
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED) {
                    PduEngine.scope.launch { PduEngine.indexPhotos() }
                } else photoPermission.launch(Manifest.permission.READ_MEDIA_IMAGES)
            }
            SourceRow(SwarmIcon.Folder, "Documents", "${counts.documents} files", "Pick folder", canIndex) { pickTree.launch(null) }
            for (t in trees) {
                SourceRow(SwarmIcon.Folder, Uri.decode(t.lastPathSegment ?: t.toString()), "folder you picked", "Index", canIndex) {
                    PduEngine.scope.launch { PduEngine.indexTree(t) }
                }
            }
            SourceRow(SwarmIcon.Scan, "Scanned", "${counts.scans} items", "Scan", true) { go("scan") }
            note?.let { Text(it, style = SwarmType.label.copy(color = SwarmColors.graphite)) }
        }

        if (folders.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("Folders")
                Text("Every folder stays on this phone until you share it.", style = SwarmType.body.copy(color = SwarmColors.graphite))
                for (f in folders) FolderRow(f)
            }
        }

        PrimaryButton("Ask the swarm", { go("ask") }, Modifier.fillMaxWidth(), icon = SwarmIcon.Swarm)
        SecondaryButton("Scan a document", { go("scan") }, Modifier.fillMaxWidth())
    }
}

@Composable
private fun SourceRow(icon: SwarmIcon, title: String, sub: String, action: String, enabled: Boolean, onClick: () -> Unit) {
    SwarmCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBox(icon)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = SwarmType.title.copy(fontSize = 17.sp, lineHeight = 22.sp), maxLines = 1)
                Text(sub, style = SwarmType.mono.copy(color = SwarmColors.graphite), maxLines = 2)
            }
            Spacer(Modifier.width(8.dp))
            SecondaryButton(action, onClick, enabled = enabled)
        }
    }
}

@Composable
private fun FolderRow(f: FolderInfo) {
    val locked = SharePolicy.mustStayPrivate(f.folder) // a folder named Private can never be shared
    val shared = !f.isPrivate && !locked
    SwarmCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBox(if (shared) SwarmIcon.Folder else SwarmIcon.Lock)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(f.folder, style = SwarmType.title.copy(fontSize = 16.sp, lineHeight = 20.sp), maxLines = 2)
                Text(
                    if (shared) "${f.docs} files, may answer peer queries" else "${f.docs} files, stays on this phone",
                    style = SwarmType.mono.copy(color = SwarmColors.graphite),
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text("Share with the swarm", style = SwarmType.label.copy(fontSize = 11.sp, color = SwarmColors.graphite))
                InkSwitch(checked = shared, onCheckedChange = if (locked) null else ({ PduEngine.setFolderPrivate(f.folder, !it) }))
            }
        }
        if (locked) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Stamp("Never shared", caution = true, small = true) }
        }
    }
}

/** A line icon in a 40 dp outlined square, the leading element of a source or folder row. */
@Composable
private fun IconBox(icon: SwarmIcon) {
    Box(Modifier.size(40.dp).border(1.5.dp, SwarmColors.ink, SwarmShape), contentAlignment = Alignment.Center) { LineIcon(icon) }
}
