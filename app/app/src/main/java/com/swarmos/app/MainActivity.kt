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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.swarmos.app.core.PhoneActivity
import com.swarmos.app.core.SwarmService
import com.swarmos.app.shell.ActivityBanner
import com.swarmos.app.shell.SettingsSheet
import com.swarmos.app.shell.SetupScreen
import com.swarmos.app.shell.StartupSkeleton
import com.swarmos.app.ui.GalleryPage
import com.swarmos.app.ui.NavItem
import com.swarmos.app.ui.ScreenTransition
import com.swarmos.app.ui.StatsBar
import com.swarmos.app.ui.SwarmBottomBar
import com.swarmos.app.ui.SwarmColors
import com.swarmos.app.ui.SwarmTheme
import com.swarmos.app.ui.destinationLabel
import com.swarmos.app.ui.galleryTab
import kotlinx.coroutines.launch

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
        applyWhenReady(intent)
        // a dozing phone stops answering its peers, so the screen stays on while Ustaad is in front
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        SwarmService.start(this)
        setContent { SwarmTheme { SwarmRoot(galleryPage, onLeaveGallery = { galleryPage = 0 }) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyWhenReady(intent)
        // opening the app again is the one tap that starts Ustaad after "Stop Ustaad"
        if (!SwarmService.running.value) SwarmService.start(this)
    }

    override fun onStart() {
        super.onStart()
        AppGraph.foreground.value = true // the service holds its wake and Wi-Fi locks while this is true or a peer is online
    }

    override fun onStop() {
        AppGraph.foreground.value = false
        super.onStop()
    }

    /** Settings and the audit log load off the main thread; intents are applied once that is done. */
    private fun applyWhenReady(i: Intent?) {
        lifecycleScope.launch {
            AppGraph.awaitReady()
            applyIntent(i)
        }
    }

    /** `adb shell am start -n com.swarmos.app/.MainActivity --es name "Phone A"` names the node. */
    private fun applyIntent(i: Intent?) {
        i?.getStringExtra(EXTRA_NAME)?.takeIf { it.isNotBlank() }?.let { AppGraph.setNodeName(it) }
        i?.data?.takeIf { it.scheme == "swarmos" }?.let { com.swarmos.app.link.LinkModule.handleDeepLink(it.toString()) }
        // `--ez setupDone true` marks the first-run setup done (false shows it again), so phones can be driven over adb
        if (i?.hasExtra(EXTRA_SETUP_DONE) == true) AppGraph.markSetupDone(i.getBooleanExtra(EXTRA_SETUP_DONE, false))
        // `--ez autoload false` turns "Load models at start" off (true turns it on); it is persisted and read when the service starts
        if (i?.hasExtra(EXTRA_AUTOLOAD) == true) com.swarmos.app.llm.ModelStore.setAutoload(i.getBooleanExtra(EXTRA_AUTOLOAD, true))
        // `--es activity 'Running a check (7 of 20).'` shows that line in the banner and the notification, with a Stop action; `--es activity ''` clears it (debug builds only)
        if (BuildConfig.DEBUG && i?.hasExtra(EXTRA_ACTIVITY) == true) {
            val line = i.getStringExtra(EXTRA_ACTIVITY).orEmpty()
            if (line.isBlank()) PhoneActivity.clear("adb") else PhoneActivity.set("adb", line, onStop = { PhoneActivity.clear("adb") })
        }
        // `--ez gallery true [--ei galleryPage 3]` opens the UI kit gallery, `--ez gallery false` closes it (debug builds only)
        if (BuildConfig.DEBUG && i?.hasExtra(EXTRA_GALLERY) == true) {
            galleryPage = if (i.getBooleanExtra(EXTRA_GALLERY, false)) i.getIntExtra(EXTRA_GALLERY_PAGE, 1) else 0
        }
    }

    companion object {
        const val EXTRA_NAME = "name"
        const val EXTRA_SETUP_DONE = "setupDone"
        const val EXTRA_AUTOLOAD = "autoload"
        const val EXTRA_ACTIVITY = "activity"
        const val EXTRA_GALLERY = "gallery"
        const val EXTRA_GALLERY_PAGE = "galleryPage"
    }
}

@Composable
private fun SwarmRoot(galleryPage: Int, onLeaveGallery: () -> Unit) {
    val modules = remember { AppGraph.modules.filter { it.showInNav } }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    val items = modules.map { NavItem(it.id, destinationLabel(it.id) ?: it.title) }
    val inGallery = galleryPage > 0

    if (!AppGraph.ready) {
        StartupSkeleton()
        return
    }
    if (!AppGraph.setupDone) {
        SetupScreen(onFinish = { AppGraph.markSetupDone(true) })
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(SwarmColors.whiteboard),
    ) {
        StatsBar(onEditName = { showSettings = true })
        ActivityBanner()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            ScreenTransition(if (inGallery) -galleryPage else selected, Modifier.fillMaxSize()) {
                if (inGallery) GalleryPage(galleryPage) else modules[selected].Screen()
            }
        }
        SwarmBottomBar(
            items = items,
            selected = if (inGallery) galleryTab(galleryPage) else selected,
            onSelect = { i -> selected = i; onLeaveGallery() },
        )
    }

    if (showSettings) SettingsSheet(onDismiss = { showSettings = false })
}
