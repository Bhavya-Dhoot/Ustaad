package com.swarmos.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swarmos.app.AppGraph
import com.swarmos.app.BuildConfig
import com.swarmos.app.agent.AgentAccess
import com.swarmos.app.busy.BusyMode
import com.swarmos.app.core.AuditVerify
import com.swarmos.app.core.Ports
import com.swarmos.app.core.SwarmService
import com.swarmos.app.llm.ModelStore
import com.swarmos.app.ui.ErrorNote
import com.swarmos.app.ui.Hairline
import com.swarmos.app.ui.InkSwitch
import com.swarmos.app.ui.PhoneToken
import com.swarmos.app.ui.PrimaryButton
import com.swarmos.app.ui.ScreenHeader
import com.swarmos.app.ui.SecondaryButton
import com.swarmos.app.ui.Stamp
import com.swarmos.app.ui.SwarmColors
import com.swarmos.app.ui.SwarmShape
import com.swarmos.app.ui.SwarmTextField
import com.swarmos.app.ui.SwarmType
import com.swarmos.app.ui.phoneLetter
import com.swarmos.app.ui.underlinePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private val SheetShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)

/** "Phone A", "Phone B", "Phone C" in the order of the identity picker. */
internal val PHONE_IDENTITIES = listOf("Phone A", "Phone B", "Phone C")

/**
 * Settings, opened from the status strip: phone name, identity (A, B, C sets the name and with it the
 * marker colour), pairing secret, remove animations, audit log check and the app version.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val self by AppGraph.registry.self.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf(self.name) }
    fun close() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = SheetShape,
        containerColor = SwarmColors.sheet,
        contentColor = SwarmColors.ink,
        scrimColor = SwarmColors.ink.copy(alpha = 0.2f),
        dragHandle = null,
    ) {
        Column(Modifier.fillMaxWidth().drawBehind { drawSheetEdge() }) {
            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 40.dp, height = 4.dp).background(SwarmColors.ink, CircleShape))
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ScreenHeader("Settings") {
                    // wordmark and version on one line: a separate version row pushed the Save button out of reach after Verify
                    Row(Modifier.padding(bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
                        Wordmark()
                        Text(" ${BuildConfig.VERSION_NAME}", style = SwarmType.mono.copy(fontSize = 12.sp, lineHeight = 18.sp, color = SwarmColors.graphite))
                    }
                }
                Hairline()
                SwarmTextField(
                    value = name,
                    onValueChange = { name = it.take(32) },
                    label = "Phone name",
                    error = if (name.isBlank()) "Give this phone a name." else null,
                )
                IdentityPicker(selected = phoneLetter(name), onPick = { name = it })
                Hairline()
                PairingSecretRow()
                SwitchRow(
                    "Remove animations", "Stamps and lines show only their final state.",
                    checked = AppGraph.removeAnimations, onChange = { AppGraph.markAnimationsRemoved(it) },
                )
                val autoload by ModelStore.autoload.collectAsStateWithLifecycle()
                SwitchRow(
                    "Load models at start", "The default chat and embedding models load when Ustaad starts.",
                    checked = autoload, onChange = { ModelStore.setAutoload(it) },
                )
                KeepBusyRows()
                AgentAccessRows()
                AuditLogCard()
                StopStartRow()
            }
            // pinned below the scroll area: it is always fully visible, however tall the content grows (after Verify, for one)
            PrimaryButton(
                "Save changes",
                onClick = {
                    if (name.isNotBlank()) AppGraph.setNodeName(name)
                    close()
                },
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                enabled = name.isNotBlank(),
            )
        }
    }
}

/** 1.5 dp Ink edge along the top and both sides of the sheet (an open path: the bottom is off screen). */
private fun DrawScope.drawSheetEdge() {
    val half = 0.75.dp.toPx()
    val r = 16.dp.toPx()
    val path = Path().apply {
        moveTo(half, size.height)
        lineTo(half, r)
        arcTo(Rect(half, half, half + 2 * r, half + 2 * r), 180f, 90f, false)
        lineTo(size.width - r, half)
        arcTo(Rect(size.width - half - 2 * r, half, size.width - half, half + 2 * r), 270f, 90f, false)
        lineTo(size.width - half, size.height)
    }
    drawPath(path, SwarmColors.ink, style = Stroke(1.5.dp.toPx()))
}

/** The three phone tokens; the picked one has a second ring. Picking sets the name to "Phone A/B/C". */
@Composable
private fun IdentityPicker(selected: Char?, onPick: (String) -> Unit) {
    Column {
        Text("Identity", style = SwarmType.label.copy(color = SwarmColors.graphite))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PHONE_IDENTITIES.forEach { phone ->
                val isSelected = phone.last() == selected
                Box(
                    Modifier
                        .size(48.dp)
                        .clickable(role = Role.RadioButton) { onPick(phone) }
                        .semantics {
                            contentDescription = phone
                            this.selected = isSelected
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(42.dp).then(if (isSelected) Modifier.border(1.5.dp, SwarmColors.ink, CircleShape) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) { PhoneToken(phone, 32.dp) }
                }
            }
        }
    }
}

@Composable
private fun PairingSecretRow() {
    var confirming by remember { mutableStateOf(false) }
    var wasReset by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Pairing secret", style = SwarmType.body.copy(fontWeight = FontWeight.W500))
                Text("••••••••", style = SwarmType.mono.copy(color = SwarmColors.graphite))
            }
            SecondaryButton("Reset", onClick = { confirming = true; wasReset = false }, enabled = !confirming)
        }
        if (confirming) {
            ErrorNote("A new secret means every paired phone has to pair with this one again.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Cancel", onClick = { confirming = false })
                SecondaryButton("Reset secret", onClick = {
                    AppGraph.resetPairingSecret()
                    confirming = false
                    wasReset = true
                })
            }
        } else if (wasReset) {
            Text("New secret set. Pair the other phones again.", style = SwarmType.label.copy(color = SwarmColors.graphite))
        }
    }
}

@Composable
private fun SwitchRow(title: String, helper: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = SwarmType.body.copy(fontWeight = FontWeight.W500))
            Text(helper, style = SwarmType.label.copy(fontWeight = FontWeight.W400, color = SwarmColors.graphite))
        }
        InkSwitch(checked, onCheckedChange = null) // the whole row is the toggle
    }
}

/** "Keep busy": the switch, and while it is on the state and the counters (re-read every second). */
@Composable
private fun KeepBusyRows() {
    val on by BusyMode.enabled.collectAsStateWithLifecycle()
    val state by BusyMode.state.collectAsStateWithLifecycle()
    var report by remember { mutableStateOf(BusyMode.report()) }
    LaunchedEffect(on, state) {
        report = BusyMode.report()
        while (on) {
            delay(1000)
            report = BusyMode.report()
        }
    }
    val ctx = LocalContext.current
    var exempt by remember { mutableStateOf(ctx.isBatteryExempt()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { exempt = ctx.isBatteryExempt() } // back from the system dialog
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SwitchRow(
            "Keep busy", "Runs short practice questions on this phone while it has no other work, and stops the moment real work arrives.",
            checked = on, onChange = { BusyMode.setEnabled(it) },
        )
        Text(keepBusyScreenOffLine(exempt), style = SwarmType.label.copy(fontWeight = FontWeight.W400, color = SwarmColors.graphite))
        if (!exempt) SecondaryButton("Allow with the screen off", onClick = { ctx.requestBatteryExemption() })
        if (on) {
            Text(
                "Now: ${state.text}. ${report.line()}",
                style = SwarmType.label.copy(fontWeight = FontWeight.W400, color = SwarmColors.graphite),
            )
        }
    }
}

/**
 * The agent endpoint: the switch, and while it is on the base URL, the key (copy it, or make a new one) and how many requests
 * were answered, and the switch for file search. Apps on this phone call Ustaad's model at that address with that key; see docs/AGENT-API.md.
 */
@Composable
private fun AgentAccessRows() {
    val ctx = LocalContext.current
    val on by AgentAccess.enabled.collectAsStateWithLifecycle()
    val key by AgentAccess.key.collectAsStateWithLifecycle()
    val served by AgentAccess.served.collectAsStateWithLifecycle()
    val error by AgentAccess.error.collectAsStateWithLifecycle()
    val files by AgentAccess.files.collectAsStateWithLifecycle()
    val running by SwarmService.running.collectAsStateWithLifecycle()
    var renewed by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SwitchRow(
            "Agent access",
            "Lets other apps on this phone use Ustaad's model, through a local address and a key. The model ustaad-swarm sends their prompts to your paired phones.",
            checked = on, onChange = { renewed = false; AgentAccess.setEnabled(it) },
        )
        if (on && !running) {
            Text(
                "Ustaad is stopped, so nothing is listening yet. Start Ustaad below and the address works again.",
                style = SwarmType.label.copy(fontWeight = FontWeight.W400, color = SwarmColors.graphite),
            )
        }
        if (on) {
            Column {
                Text("Base URL", style = SwarmType.label.copy(color = SwarmColors.graphite))
                Text("http://127.0.0.1:${Ports.AGENT}/v1", style = SwarmType.mono)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Key", style = SwarmType.label.copy(color = SwarmColors.graphite))
                    Text(key?.let { "••••••••" + it.takeLast(4) } ?: "", style = SwarmType.mono)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Copy", onClick = { key?.let { copyAgentKey(ctx, it) } }, enabled = key != null)
                    SecondaryButton("New key", onClick = { renewed = true; AgentAccess.newKey() }, enabled = key != null)
                }
            }
            Text(
                if (renewed) "New key set. Update the apps that use it."
                else String.format(Locale.US, "%,d %s served", served, if (served == 1) "request" else "requests"),
                style = SwarmType.label.copy(fontWeight = FontWeight.W400, color = SwarmColors.graphite),
            )
            SwitchRow(
                "File search for other apps",
                "Apps that hold the key can search the files you share with the swarm, on this phone and (when they ask for it) on your paired phones, and read short snippets of them. Files in private folders are never included. Answers name the paired phones that replied.",
                checked = files, onChange = { AgentAccess.setFiles(it) },
            )
        }
        error?.let { ErrorNote(it) }
    }
}

private fun copyAgentKey(ctx: android.content.Context, key: String) {
    val clip = android.content.ClipData.newPlainText("Ustaad agent key", key)
    // keeps the key out of the clipboard preview on Android 13 and later
    clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    ctx.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(clip)
    if (android.os.Build.VERSION.SDK_INT < 33) android.widget.Toast.makeText(ctx, "Key copied", android.widget.Toast.LENGTH_SHORT).show()
}

/** Stops the service (node server, models, wake and Wi-Fi locks) in one tap; the same button starts it again. */
@Composable
private fun StopStartRow() {
    val ctx = LocalContext.current
    val running by SwarmService.running.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SecondaryButton(
            if (running) "Stop Ustaad" else "Start Ustaad",
            onClick = { if (running) SwarmService.stop(ctx) else SwarmService.start(ctx) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            if (running) "Stops this phone's node, models and wake locks."
            else "Ustaad is stopped. Starts the node server and the models again.",
            style = SwarmType.label.copy(fontWeight = FontWeight.W400, color = SwarmColors.graphite),
        )
    }
}

@Composable
private fun AuditLogCard() {
    val scope = rememberCoroutineScope()
    var count by remember { mutableStateOf(AppGraph.audit.count) } // kept in memory by the log, no parsing
    var result by remember { mutableStateOf<AuditVerify?>(null) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(SwarmColors.sheet, SwarmShape)
            .border(1.5.dp, SwarmColors.ink, SwarmShape)
            .padding(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Audit log", style = SwarmType.label.copy(color = SwarmColors.graphite))
                Spacer(Modifier.height(2.dp))
                Text(
                    String.format(Locale.US, "%,d %s", count, if (count == 1) "entry" else "entries"),
                    Modifier
                        .drawWithCache {
                            val path = underlinePath(-2.dp.toPx(), size.width + 4.dp.toPx(), size.height - 1.dp.toPx(), 2.dp.toPx(), seed = 21)
                            val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
                            onDrawBehind { drawPath(path, SwarmColors.ink, style = stroke) }
                        }
                        .padding(bottom = 5.dp),
                    style = SwarmType.mono.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.W700),
                )
            }
            SecondaryButton("Verify", onClick = {
                scope.launch {
                    result = withContext(Dispatchers.IO) { AppGraph.audit.verify() }
                    count = AppGraph.audit.count
                }
            })
        }
        result?.let { r ->
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                key(r) { Stamp(if (r.ok) "Chain OK" else "Chain broken", caution = !r.ok) }
                if (!r.ok) {
                    Spacer(Modifier.width(10.dp))
                    ErrorNote("First bad entry: ${r.badLine}", Modifier.weight(1f))
                }
            }
        }
    }
}
