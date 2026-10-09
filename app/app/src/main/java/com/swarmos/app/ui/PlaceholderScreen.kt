package com.swarmos.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Shown by modules that are not built yet. */
@Composable
fun PlaceholderScreen(title: String) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        Text("not built yet", color = SwarmColors.Dim)
    }
}
