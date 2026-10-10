package com.swarmos.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swarmos.app.core.PhoneActivity
import com.swarmos.app.ui.SwarmColors
import com.swarmos.app.ui.SwarmType

/**
 * Slim line under the status strip with what this phone is doing right now ([PhoneActivity]); nothing is drawn
 * while the phone is idle. A "Stop" action appears when the module that set the line supplied one.
 */
@Composable
fun ActivityBanner(modifier: Modifier = Modifier) {
    val entry by PhoneActivity.current.collectAsStateWithLifecycle()
    val e = entry ?: return
    Row(
        modifier
            .fillMaxWidth()
            .background(SwarmColors.sheet)
            .drawBehind {
                drawLine(SwarmColors.smudge, Offset(0f, size.height - 0.5.dp.toPx()), Offset(size.width, size.height - 0.5.dp.toPx()), 1.dp.toPx())
            }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            e.text,
            Modifier.weight(1f).padding(vertical = 8.dp),
            style = SwarmType.label.copy(fontSize = 13.sp, lineHeight = 16.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        e.onStop?.let { stop ->
            Box(
                Modifier
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClickLabel = "Stop this activity", onClick = stop)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Stop", style = SwarmType.label.copy(fontWeight = FontWeight.W700, textDecoration = TextDecoration.Underline))
            }
        }
    }
}
