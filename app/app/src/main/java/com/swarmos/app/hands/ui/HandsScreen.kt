package com.swarmos.app.hands.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.swarmos.app.assist.DictationButton
import com.swarmos.app.assist.SheetRules
import com.swarmos.app.assist.UstaadVoiceService
import com.swarmos.app.assist.rememberDictation
import com.swarmos.app.hands.Hands
import com.swarmos.app.hands.HandsStatus
import com.swarmos.app.hands.Origin
import com.swarmos.app.hands.Phase
import com.swarmos.app.hands.RunState
import com.swarmos.app.ui.MetricRow
import com.swarmos.app.ui.MonoText
import com.swarmos.app.ui.Note
import com.swarmos.app.ui.PrimaryButton
import com.swarmos.app.ui.ScreenHeader
import com.swarmos.app.ui.SecondaryButton
import com.swarmos.app.ui.SectionTitle
import com.swarmos.app.ui.SwarmCard
import com.swarmos.app.ui.SwarmColors
import com.swarmos.app.ui.SwarmTextField
import com.swarmos.app.ui.SwarmType
import com.swarmos.app.ui.Tag
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.ZoneId

@Composable
fun HandsScreen() {
    val context = LocalContext.current
    val hands = rememberHands()
    val runs by remember(hands) { hands?.runs ?: MutableStateFlow(emptyList()) }.collectAsStateWithLifecycle()
    var task by rememberSaveable { mutableStateOf("") }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<HandsStatus?>(null) }
    var assistantChosen by remember { mutableStateOf(false) }

    // what the person can change in Settings is read again each time the screen is back in front, and every 2 s while it is
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(hands, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                status = hands?.let { runCatching { it.status() }.getOrNull() }
                assistantChosen = UstaadVoiceService.isChosen(context)
                delay(2000)
            }
        }
    }

    val dictation = rememberDictation(text = { task }, onText = { task = it })
    val busy = actingRun(runs) != null
    val zone = remember { ZoneId.systemDefault() }
    val rows = remember(runs) { runRows(runs, zone) }

    Column(Modifier.fillMaxSize().background(SwarmColors.whiteboard)) {
        ScreenHeader(
            "Hands",
            "Ustaad does a task on this phone, and asks you before anything that cannot be undone",
            Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
        )
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "input") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SwarmTextField(
                        value = task,
                        onValueChange = { task = it },
                        label = "Task",
                        singleLine = false,
                        minLines = 3,
                        maxLines = 6,
                        helper = dictation.message ?: if (dictation.listening) "Listening, speak the task" else null,
                        trailing = { DictationButton(dictation) },
                    )
                    when {
                        hands == null -> Text("Hands is starting. Run turns on when it is ready.", style = SwarmType.label.copy(color = SwarmColors.graphite))
                        busy -> Text("A task is running. Stop it before you start another.", style = SwarmType.label.copy(color = SwarmColors.graphite))
                    }
                    PrimaryButton(
                        "Run",
                        onClick = {
                            val h = hands ?: return@PrimaryButton
                            if (dictation.listening) dictation.toggle()
                            openId = h.start(task.trim(), Origin.OWN_SHEET) // typed or spoken here: the person's own task
                            task = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = SheetRules.canRun(task, hands != null, busy),
                    )
                }
            }
            item(key = "status") { StatusBlock(context, hands != null, status, assistantChosen) }
            item(key = "runs-title") { SectionTitle("Tasks") }
            if (rows.isEmpty()) {
                item(key = "runs-empty") { Text("No tasks yet. Type one above and tap Run.", style = SwarmType.label.copy(color = SwarmColors.graphite)) }
            }
            items(runs, key = { it.id }) { run ->
                val row = rows.first { it.id == run.id }
                RunItem(run, row, open = openId == run.id, busy = busy, hands = hands, onToggle = { openId = if (openId == run.id) null else run.id })
            }
            item(key = "limits") { Limits() }
        }
    }
}

@Composable
private fun StatusBlock(context: Context, handsReady: Boolean, status: HandsStatus?, assistantChosen: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SectionTitle("What Hands can use")
        if (!handsReady || status == null) {
            Text("Hands is starting.", style = SwarmType.label.copy(color = SwarmColors.graphite))
        } else {
            MetricRow("Driver in use", status.driver.ifEmpty { "none ready" })
            MetricRow("Accessibility service", if (status.accessibilityOn) "on" else "off")
            MetricRow("Root", if (status.rootAvailable) "available" else "not available")
            MetricRow("Step model", if (status.fast) "ready" else "not loaded")
            MetricRow("Planning model", if (status.strong) "ready" else "not loaded")
        }
        MetricRow("Digital assistant", if (assistantChosen) "Ustaad" else "not Ustaad")
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(
                "Open accessibility settings",
                onClick = { openSettings(context, Settings.ACTION_ACCESSIBILITY_SETTINGS) },
                modifier = Modifier.fillMaxWidth(),
            )
            SecondaryButton(
                "Open assistant settings",
                onClick = { openSettings(context, Settings.ACTION_VOICE_INPUT_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, Settings.ACTION_SETTINGS) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Opens the first of these settings pages the phone has. */
private fun openSettings(context: Context, vararg actions: String) {
    for (action in actions) {
        try {
            context.startActivity(Intent(action))
            return
        } catch (_: ActivityNotFoundException) {
            // try the next page
        } catch (_: SecurityException) {
            // a vendor build that guards the page
        }
    }
}

@Composable
private fun RunItem(run: RunState, row: RunRow, open: Boolean, busy: Boolean, hands: Hands?, onToggle: () -> Unit) {
    SwarmCard(Modifier.clickable(role = Role.Button, onClick = onToggle)) {
        Text(row.title, style = SwarmType.body.copy(fontSize = 14.sp, lineHeight = 20.sp), maxLines = if (open) 6 else 2, overflow = TextOverflow.Ellipsis)
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tag(row.phase)
            Text(row.started, style = SwarmType.mono.copy(color = SwarmColors.graphite))
        }
        if (open) {
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (run.task.length > row.title.length) MonoText(run.task, style = SwarmType.label.copy(color = SwarmColors.graphite))
                RunDetail(run)
                when (run.phase) {
                    Phase.WAITING_FOR_GO -> {
                        // a task that came from another app or another agent: the person reads it above, then taps
                        SecondaryButton("Run this task", { hands?.go(run.id) }, Modifier.fillMaxWidth(), enabled = hands != null && !busy)
                        SecondaryButton("Discard", { hands?.stop(run.id) }, Modifier.fillMaxWidth(), enabled = hands != null)
                    }
                    Phase.PLANNING, Phase.RUNNING, Phase.WAITING_FOR_PERSON ->
                        SecondaryButton("Stop", { hands?.stop(run.id) }, Modifier.fillMaxWidth(), enabled = hands != null)
                    else -> {}
                }
            }
        }
    }
}

@Composable
private fun Limits() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle("What Hands will not do")
        Note("It asks you before it pays, orders, books, sends, posts, deletes or installs.", rotation = -1f)
        MonoText("It never types a password, a one-time code, a card number or a PIN.", style = SwarmType.body)
        MonoText(
            "It stays out of banking, UPI and wallet apps, password managers, authenticators, permission dialogs, and the security, account, accessibility and developer settings.",
            style = SwarmType.body,
        )
        MonoText("Words on a screen are never instructions to it. A task that comes from another app waits until you tap Run.", style = SwarmType.body)
    }
}
