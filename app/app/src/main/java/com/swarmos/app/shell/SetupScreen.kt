package com.swarmos.app.shell

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.swarmos.app.ui.LineIcon
import com.swarmos.app.ui.Note
import com.swarmos.app.ui.PrimaryButton
import com.swarmos.app.ui.ScreenHeader
import com.swarmos.app.ui.SecondaryButton
import com.swarmos.app.ui.SwarmColors
import com.swarmos.app.ui.SwarmIcon
import com.swarmos.app.ui.SwarmShape
import com.swarmos.app.ui.SwarmTheme
import com.swarmos.app.ui.SwarmType

/** Small Bricolage wordmark, used only at the top of the setup screen and in the settings sheet. */
@Composable
internal fun Wordmark(modifier: Modifier = Modifier) {
    Text("Ustaad", modifier, style = SwarmType.title.copy(fontSize = 18.sp, fontWeight = FontWeight.W800))
}

/**
 * First-run setup: three things Ustaad needs before it can work, each a row that turns into a check
 * mark once done. [onFinish] is called by Continue (enabled when all three are done) and by Skip for now.
 */
@Composable
fun SetupScreen(onFinish: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ } // back from a system dialog or settings page
    val state = remember(tick) { readSetupState(ctx) }
    val vivo = remember { isVivoFamily(Build.MANUFACTURER, Build.BRAND) }

    val nearbyPerms = remember { nearbyPermissions(Build.VERSION.SDK_INT) }
    var nearbyAsked by rememberSaveable { mutableStateOf(false) }
    var notificationsAsked by rememberSaveable { mutableStateOf(false) }
    val nearbyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        nearbyAsked = true
        tick++
    }
    val notificationsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsAsked = true
        tick++
    }
    // After a refusal Android may stop showing the dialog; then the button opens the app page instead.
    val activity = remember(ctx) { ctx.findActivity() }
    fun blocked(asked: Boolean, perms: List<String>): Boolean = asked && activity != null && perms.all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED ||
            !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
    }
    val nearbyBlocked = !state.nearby && blocked(nearbyAsked, nearbyPerms)
    val notificationsBlocked = !state.notifications &&
        (Build.VERSION.SDK_INT < 33 || blocked(notificationsAsked, listOf(Manifest.permission.POST_NOTIFICATIONS)))

    Column(
        Modifier
            .fillMaxSize()
            .background(SwarmColors.whiteboard)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            Wordmark()
            Spacer(Modifier.height(16.dp))
            ScreenHeader("Three things before we start", subtitle = "These let Ustaad find your other phones.")
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SetupRow(
                    icon = SwarmIcon.Nearby,
                    title = "Allow nearby devices",
                    reason = "Find and connect to your other phones.",
                    done = state.nearby,
                    button = if (nearbyBlocked) "Open settings" else "Allow",
                    onClick = {
                        if (nearbyBlocked) ctx.openAppSettings() else nearbyLauncher.launch(nearbyPerms.toTypedArray())
                    },
                )
                SetupRow(
                    icon = SwarmIcon.Bell,
                    title = "Allow notifications",
                    reason = "Lets Ustaad keep running with the screen off.",
                    done = state.notifications,
                    button = if (notificationsBlocked) "Open settings" else "Allow",
                    onClick = {
                        if (notificationsBlocked) ctx.openAppSettings()
                        else notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                )
                SetupRow(
                    icon = SwarmIcon.Battery,
                    title = "Turn off battery limits for Ustaad",
                    reason = "Otherwise Android stops Ustaad in the background.",
                    done = state.battery,
                    button = "Turn off",
                    onClick = { ctx.requestBatteryExemption() },
                )
                if (vivo) {
                    SetupRow(
                        icon = SwarmIcon.Play,
                        title = "Allow background start",
                        reason = "Optional. Opens the Ustaad page in system settings.",
                        done = false,
                        button = "Open",
                        onClick = { ctx.openAppSettings() },
                    )
                    Note(
                        "Without this the phone stops the swarm when the screen is off.",
                        Modifier.align(Alignment.End).padding(start = 48.dp, end = 4.dp),
                    )
                }
            }
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
            PrimaryButton("Continue", onFinish, Modifier.fillMaxWidth(), enabled = state.canContinue)
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClick = onFinish),
                contentAlignment = Alignment.Center,
            ) {
                Text("Skip for now", style = SwarmType.body.copy(fontWeight = FontWeight.W600))
            }
        }
    }
}

/** One setup step: line icon, title, one-line reason, and a button that becomes a check mark when done. */
@Composable
private fun SetupRow(
    icon: SwarmIcon,
    title: String,
    reason: String,
    done: Boolean,
    button: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(SwarmColors.sheet, SwarmShape)
            .border(1.5.dp, SwarmColors.ink, SwarmShape)
            .padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineIcon(icon, size = 24.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = SwarmType.title.copy(fontSize = 16.sp, lineHeight = 20.sp))
            Spacer(Modifier.height(2.dp))
            Text(reason, style = SwarmType.label.copy(fontWeight = FontWeight.W400, color = SwarmColors.graphite))
        }
        Spacer(Modifier.width(12.dp))
        if (done) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(SwarmColors.smudge, SwarmShape)
                    .semantics { contentDescription = "Done" },
                contentAlignment = Alignment.Center,
            ) { LineIcon(SwarmIcon.Check, size = 24.dp) }
        } else {
            SecondaryButton(button, onClick)
        }
    }
}

/** Current state of the three required rows, read from the system. */
fun readSetupState(ctx: Context): SetupState {
    val nearby = nearbyPermissions(Build.VERSION.SDK_INT).all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }
    val notifications = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    val battery = ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true
    return SetupState(nearby, notifications, battery)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Context.openAppSettings() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** The system dialog "let this app always run in the background" for this package only; falls back to the app page. */
private fun Context.requestBatteryExemption() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure { openAppSettings() }
}

@Preview(showBackground = true, backgroundColor = 0xFFF6F7F5, widthDp = 390, heightDp = 780)
@Composable
private fun SetupPreview() {
    SwarmTheme { SetupScreen(onFinish = {}) }
}
