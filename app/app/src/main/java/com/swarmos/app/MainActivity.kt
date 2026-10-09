package com.swarmos.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
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
import androidx.compose.ui.unit.sp
import com.swarmos.app.core.SwarmService
import com.swarmos.app.ui.StatsBar
import com.swarmos.app.ui.SwarmColors
import com.swarmos.app.ui.SwarmTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyIntent(intent)
        SwarmService.start(this)
        setContent { SwarmTheme { SwarmRoot() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyIntent(intent)
    }

    /** `adb shell am start -n com.swarmos.app/.MainActivity --es name "Phone A"` names the node. */
    private fun applyIntent(i: Intent?) {
        i?.getStringExtra(EXTRA_NAME)?.takeIf { it.isNotBlank() }?.let { AppGraph.setNodeName(it) }
    }

    companion object {
        const val EXTRA_NAME = "name"
    }
}

@Composable
private fun SwarmRoot() {
    val modules = AppGraph.modules
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(SwarmColors.Bg)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        StatsBar(onEditName = { showSettings = true })
        PrimaryTabRow(selectedTabIndex = selected, containerColor = SwarmColors.Surface) {
            modules.forEachIndexed { i, m ->
                Tab(selected = i == selected, onClick = { selected = i }, text = { Text(m.title, maxLines = 1, fontSize = 12.sp) })
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) { modules[selected].Screen() }
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
