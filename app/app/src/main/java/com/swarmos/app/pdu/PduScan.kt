package com.swarmos.app.pdu

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.verticalScroll
import com.swarmos.app.ui.InkSwitch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.swarmos.app.BuildConfig
import com.swarmos.app.ui.Note
import com.swarmos.app.ui.PrimaryButton
import com.swarmos.app.ui.SecondaryButton
import com.swarmos.app.ui.SectionTitle
import com.swarmos.app.ui.SwarmCard
import com.swarmos.app.ui.SwarmColors
import com.swarmos.app.ui.SwarmShape
import com.swarmos.app.ui.SwarmTextField
import com.swarmos.app.ui.SwarmType
import com.swarmos.app.ui.Tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Scan a document: CameraX preview and capture, then a sheet with the recognised text, a title and the privacy switch. */
@Composable
internal fun ScanPage(onBack: () -> Unit) {
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
    var share by remember { mutableStateOf(false) }
    val emb by PduEngine.embedder.collectAsStateWithLifecycle()
    val reindexNeeded by PduEngine.reindexNeeded.collectAsStateWithLifecycle()
    var status by remember { mutableStateOf<String?>(null) }

    // the captured page lives in the cache directory only until the page is left: Back after a failed index deletes it too
    val heldShot by rememberUpdatedState(shot)
    DisposableEffect(Unit) { onDispose { heldShot?.delete() } }

    /** Both the shutter and the debug hook land here: read the page, propose a title. */
    fun review(f: File) {
        shot = f
        status = "reading text"
        scope.launch {
            val text = withContext(Dispatchers.Default) {
                val bmp = Ocr.decode(ctx, Uri.fromFile(f).toString())
                Ocr.recognizeAll(bmp).text.also { bmp.recycle() }
            }
            recognised = text
            title = text.lineSequence().map { it.trim() }.firstOrNull { it.length >= 3 }?.take(40)
                ?: ("Scan " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
            status = null
        }
    }
    LaunchedEffect(Unit) {
        if (BuildConfig.DEBUG) PduEngine.debugScanFile?.let { PduEngine.debugScanFile = null; review(it) }
    }

    // the viewfinder: live preview, or the page that was just captured
    @Composable
    fun Viewfinder(modifier: Modifier) {
        Box(modifier.fillMaxWidth().border(1.5.dp, SwarmColors.ink, SwarmShape)) {
            val file = shot
            if (file == null) {
                if (!hasCamera) Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center) {
                    Text("Camera permission is needed to scan.", style = SwarmType.body)
                    Spacer(Modifier.height(8.dp))
                    SecondaryButton("Ask again", { askCamera.launch(Manifest.permission.CAMERA) })
                } else AndroidView(
                    modifier = Modifier.fillMaxSize(),
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
            } else {
                val bmp by produceState<Bitmap?>(null, file) { value = withContext(Dispatchers.IO) { runCatching { Ocr.decode(ctx, Uri.fromFile(file).toString(), 900) }.getOrNull() } }
                bmp?.let { Image(it.asImageBitmap(), "captured page", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            }
            Corners()
        }
    }

    if (shot == null) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PageHeader("Scan document", onBack)
            // the hint is a handwritten note on the page, not a label on the camera picture
            if (hasCamera) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Note("align the page edges with the corners", rotation = -2f) }
            Viewfinder(Modifier.weight(1f, fill = true))
            if (hasCamera) PrimaryButton(
                "Capture",
                {
                    val f = File(ctx.cacheDir, "scan-${System.currentTimeMillis()}.jpg")
                    imageCapture.takePicture(
                        ImageCapture.OutputFileOptions.Builder(f).build(), ContextCompat.getMainExecutor(ctx),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(r: ImageCapture.OutputFileResults) = review(f)

                            override fun onError(e: ImageCaptureException) { status = "capture failed: ${e.message}" }
                        },
                    )
                },
                Modifier.fillMaxWidth(),
            )
            status?.let { Text(it, style = SwarmType.mono.copy(color = SwarmColors.graphite)) }
        }
    } else {
        // review: the page picture, then the form on the page itself (no card around it); the whole page scrolls
        val text = recognised
        val enough = text != null && text.trim().length >= PduIndexer.MIN_OCR_CHARS
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PageHeader("Scan document", onBack)
            Viewfinder(Modifier.height(190.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Text found")
                Spacer(Modifier.width(10.dp))
                if (text != null) Tag("${text.trim().length} characters")
            }
            // the whole recognised text, every line complete: no inner scroll and no fixed height that could cut a line
            SwarmCard {
                Text(
                    when {
                        text == null -> "reading text"
                        text.isBlank() -> "no text recognised"
                        else -> text
                    },
                    style = SwarmType.mono.copy(fontSize = 12.sp, lineHeight = 17.sp),
                )
            }
            if (text != null && !enough) Text("Fewer than ${PduIndexer.MIN_OCR_CHARS} characters found, nothing to index.", style = SwarmType.label)
            SwarmTextField(title, { title = it }, "Title", helper = "Saved on this phone only")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Share with the swarm", style = SwarmType.title.copy(fontSize = 16.sp, lineHeight = 20.sp))
                    Text(if (share) "Other phones may be answered from this page" else "Off: this page stays on this phone", style = SwarmType.label.copy(color = SwarmColors.graphite))
                }
                InkSwitch(checked = share, onCheckedChange = { share = it })
            }
            if (!emb.ready) Text("Embedder not loaded: load the embedding model in Models before adding pages.", style = SwarmType.label)
            else if (reindexNeeded) Text("Re-index needed: rebuild the index in the library before adding pages.", style = SwarmType.label)
            PrimaryButton(
                "Add to index",
                {
                    val file = shot ?: return@PrimaryButton
                    status = "indexing"
                    scope.launch {
                        // the page reaches the gallery only after it has been embedded and is about to be stored: a refusal leaves no image
                        val rep = withContext(Dispatchers.IO) {
                            PduEngine.indexScan(title.trim(), share, text!!, { PduIndexer.saveScan(ctx, file, share) })
                        }
                        if (rep.error == null && rep.indexed > 0) { file.delete(); onBack() } else status = rep.error ?: "nothing was indexed"
                    }
                },
                Modifier.fillMaxWidth(),
                enabled = enough && title.isNotBlank() && emb.ready && !reindexNeeded,
            )
            SecondaryButton("Retake", { shot?.delete(); shot = null; recognised = null; status = null }, Modifier.fillMaxWidth())
            status?.let { Text(it, style = SwarmType.mono.copy(color = SwarmColors.graphite)) }
        }
    }
}

/** Four corner brackets over the viewfinder: Sheet on top of Ink, so they show on a dark preview and on a bright page. */
@Composable
private fun Corners() {
    Canvas(Modifier.fillMaxSize().padding(10.dp)) {
        val len = 28.dp.toPx()
        val w = size.width
        val h = size.height
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            for ((color, width) in listOf(SwarmColors.ink to 6.dp.toPx(), SwarmColors.sheet to 3.dp.toPx())) {
                drawLine(color, Offset(x, y), Offset(x + dx * len, y), width, StrokeCap.Round)
                drawLine(color, Offset(x, y), Offset(x, y + dy * len), width, StrokeCap.Round)
            }
        }
        corner(0f, 0f, 1f, 1f)
        corner(w, 0f, -1f, 1f)
        corner(0f, h, 1f, -1f)
        corner(w, h, -1f, -1f)
    }
}
