package com.swarmos.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.swarmos.app.ui.Skeleton
import com.swarmos.app.ui.SwarmColors
import com.swarmos.app.ui.SwarmTheme
import androidx.compose.ui.tooling.preview.Preview

/**
 * First frame while the settings and the audit log load (a few ms off the main thread): Smudge blocks in the
 * layout of the status strip, a title, two blocks and the bottom bar. No spinner, no text.
 */
@Composable
fun StartupSkeleton() {
    Column(
        Modifier
            .fillMaxSize()
            .background(SwarmColors.whiteboard)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            Modifier.fillMaxWidth().background(SwarmColors.sheet).padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(28.dp).background(SwarmColors.smudge, CircleShape))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Skeleton(14.dp, Modifier.width(110.dp))
                Skeleton(11.dp, Modifier.width(210.dp))
            }
            Skeleton(22.dp, Modifier.width(96.dp))
        }
        Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Skeleton(32.dp, Modifier.width(180.dp))
            Skeleton(96.dp)
            Skeleton(96.dp)
            Skeleton(64.dp)
        }
        Box(Modifier.fillMaxWidth().height(64.dp).background(SwarmColors.sheet))
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF6F7F5, widthDp = 390, heightDp = 780)
@Composable
private fun StartupSkeletonPreview() {
    SwarmTheme { StartupSkeleton() }
}
