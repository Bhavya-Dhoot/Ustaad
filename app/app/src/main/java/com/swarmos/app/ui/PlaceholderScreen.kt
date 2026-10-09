package com.swarmos.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Shown by modules that are not built yet. */
@Composable
fun PlaceholderScreen(title: String) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        SectionTitle(title)
        Note("not built yet", Modifier.padding(top = 4.dp), color = SwarmColors.graphite)
    }
}
