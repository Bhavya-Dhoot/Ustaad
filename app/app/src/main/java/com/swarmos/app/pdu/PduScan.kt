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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
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
    var keepPrivate by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageHeader("Scan document", onBack)

        // the viewfinder: live preview, or the page that was just captured
        Box(Modifier.fillMaxWidth().weight(1f, fill = true).border(1.5.dp, SwarmColors.ink, SwarmShape)) {
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
            if (shot == null && hasCamera) Note("align edges", Modifier.align(Alignment.TopEnd).padding(12.dp), rotation = 3f)
        }

        if (shot == null) {
            if (hasCamera) PrimaryButton(
                "Capture",
                {
                    val f = File(ctx.cacheDir, "scan-${System.currentTimeMillis()}.jpg")
                    imageCapture.takePicture(
                        ImageCapture.OutputFileOptions.Builder(f).build(), ContextCompat.getMainExecutor(ctx),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(r: ImageCapture.OutputFileResults) {
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

                            override fun onError(e: ImageCaptureException) { status = "capture failed: ${e.message}" }
                        },
                    )
                },
                Modifier.fillMaxWidth(),
            )
        } else {
            val text = recognised
            val enough = text != null && text.trim().length >= PduIndexer.MIN_OCR_CHARS
            // the bottom sheet
            SwarmCard(Modifier.heightIn(max = 420.dp)) {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle("Text found")
                        Spacer(Modifier.width(10.dp))
                        if (text != null) Tag("${text.trim().length} characters")
                    }
                    Box(Modifier.fillMaxWidth().heightIn(max = 130.dp).border(1.dp, SwarmColors.smudge, SwarmShape).padding(10.dp)) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            Text(
                                when {
                                    text == null -> "reading text"
                                    text.isBlank() -> "no text recognised"
                                    else -> text
                                },
                                style = SwarmType.mono.copy(fontSize = 12.sp, lineHeight = 17.sp),
                            )
                        }
                    }
                    if (text != null && !enough) Text("Fewer than ${PduIndexer.MIN_OCR_CHARS} characters found, nothing to index.", style = SwarmType.label)
                    SwarmTextField(title, { title = it }, "Title", helper = "Saved on this phone only")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Keep private", style = SwarmType.title.copy(fontSize = 16.sp, lineHeight = 20.sp))
                            Text("Never shared with other phones", style = SwarmType.label.copy(color = SwarmColors.graphite))
                        }
                        Switch(checked = keepPrivate, onCheckedChange = { keepPrivate = it })
                    }
                    PrimaryButton(
                        "Add to index",
                        {
                            val file = shot ?: return@PrimaryButton
                            status = "indexing"
                            scope.launch {
                                val rep = withContext(Dispatchers.IO) {
                                    val (uri, modified) = PduIndexer.saveScan(ctx, file, keepPrivate)
                                    PduEngine.indexScan(uri, modified, title.trim(), keepPrivate, text!!)
                                }
                                if (rep.error == null && rep.indexed > 0) { file.delete(); onBack() } else status = rep.error ?: "nothing was indexed"
                            }
                        },
                        Modifier.fillMaxWidth(),
                        enabled = enough && title.isNotBlank(),
                    )
                    SecondaryButton("Retake", { shot?.delete(); shot = null; recognised = null; status = null }, Modifier.fillMaxWidth())
                }
            }
        }
        status?.let { Text(it, style = SwarmType.mono.copy(color = SwarmColors.graphite)) }
    }
}

/** Four Ink corner brackets over the viewfinder. */
@Composable
private fun Corners() {
    Canvas(Modifier.fillMaxSize().padding(10.dp)) {
        val s = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
        val len = 28.dp.toPx()
        val w = size.width
        val h = size.height
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(SwarmColors.ink, Offset(x, y), Offset(x + dx * len, y), s.width, StrokeCap.Round)
            drawLine(SwarmColors.ink, Offset(x, y), Offset(x, y + dy * len), s.width, StrokeCap.Round)
        }
        corner(0f, 0f, 1f, 1f)
        corner(w, 0f, -1f, 1f)
        corner(0f, h, 1f, -1f)
        corner(w, h, -1f, -1f)
    }
}
