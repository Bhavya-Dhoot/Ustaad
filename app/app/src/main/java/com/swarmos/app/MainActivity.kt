package com.swarmos.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.swarmos.app.core.SwarmService
import com.swarmos.app.ui.GalleryPage
import com.swarmos.app.ui.NavItem
import com.swarmos.app.ui.StatsBar
import com.swarmos.app.ui.SwarmBottomBar
import com.swarmos.app.ui.SwarmColors
import com.swarmos.app.ui.SwarmTheme
import com.swarmos.app.ui.destinationLabel
import com.swarmos.app.ui.galleryTab

class MainActivity : ComponentActivity() {
    /** Debug only: 0 = normal app, 1..N = a page of the UI kit gallery (see ui/Gallery.kt). */
    private var galleryPage by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // light app: transparent bars with dark icons, whatever the system dark mode says
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        applyIntent(intent)
        SwarmService.start(this)
        setContent { SwarmTheme { SwarmRoot(galleryPage, onLeaveGallery = { galleryPage = 0 }) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyIntent(intent)
    }

    /** `adb shell am start -n com.swarmos.app/.MainActivity --es name "Phone A"` names the node. */
    private fun applyIntent(i: Intent?) {
        i?.getStringExtra(EXTRA_NAME)?.takeIf { it.isNotBlank() }?.let { AppGraph.setNodeName(it) }
        // `--ez keepon true` keeps the screen on while this activity is shown (app flag, no system setting); used to test OriginOS background kills
        if (i?.getBooleanExtra("keepon", false) == true) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // `--ez gallery true [--ei galleryPage 3]` opens the UI kit gallery, `--ez gallery false` closes it (debug builds only)
        if (BuildConfig.DEBUG && i?.hasExtra(EXTRA_GALLERY) == true) {
            galleryPage = if (i.getBooleanExtra(EXTRA_GALLERY, false)) i.getIntExtra(EXTRA_GALLERY_PAGE, 1) else 0
        }
    }

    companion object {
        const val EXTRA_NAME = "name"
        const val EXTRA_GALLERY = "gallery"
        const val EXTRA_GALLERY_PAGE = "galleryPage"
    }
}

@Composable
private fun SwarmRoot(galleryPage: Int, onLeaveGallery: () -> Unit) {
    val modules = AppGraph.modules
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    val items = modules.map { NavItem(it.id, destinationLabel(it.id) ?: it.title) }
    val inGallery = galleryPage > 0

    Column(
        Modifier
            .fillMaxSize()
            .background(SwarmColors.whiteboard),
    ) {
        StatsBar(onEditName = { showSettings = true })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (inGallery) GalleryPage(galleryPage) else modules[selected].Screen()
        }
        SwarmBottomBar(
            items = items,
            selected = if (inGallery) galleryTab(galleryPage) else selected,
            onSelect = { i -> selected = i; onLeaveGallery() },
        )
    }

    if (showSettings) NameDialog(onDismiss = { showSettings = false })
}

@Composable
private fun NameDialog(onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(AppGraph.registry.self.value.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Node name") },
        text = { OutlinedTextField(value = text, onValueChange = { text = it.take(32) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { AppGraph.setNodeName(text); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
