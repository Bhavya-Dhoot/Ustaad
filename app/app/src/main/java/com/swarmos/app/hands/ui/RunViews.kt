package com.swarmos.app.hands.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swarmos.app.hands.Hands
import com.swarmos.app.hands.HandsHub
import com.swarmos.app.hands.RunState
import com.swarmos.app.ui.MonoText
import com.swarmos.app.ui.SwarmCard
import com.swarmos.app.ui.SwarmColors
import com.swarmos.app.ui.SwarmType
import com.swarmos.app.ui.Tag
import kotlinx.coroutines.delay
import java.time.ZoneId

/**
 * A run as the person reads it: the plan with a status per subtask, the last [maxSteps] step lines, and the end note.
 * Used by the Hands screen (all steps) and by the task sheet (the last few).
 */
@Composable
fun RunDetail(run: RunState, modifier: Modifier = Modifier, maxSteps: Int = Int.MAX_VALUE) {
    val zone = remember { ZoneId.systemDefault() }
    val plan = planRows(run)
    val steps = stepRows(run, zone, maxSteps)
    val end = endNote(run)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tag(phaseWord(run.phase))
            Spacer(Modifier.width(8.dp))
            Text(clockTime(run.startedAtMs, zone), style = SwarmType.mono.copy(color = SwarmColors.graphite))
        }
        if (plan.isEmpty()) {
            Text(
                if (isActing(run.phase)) "Making a plan." else "No plan yet.",
                style = SwarmType.label.copy(color = SwarmColors.graphite),
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                plan.forEach { row ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.width(76.dp).padding(top = 1.dp)) { Tag(row.status) }
                        Column(Modifier.weight(1f)) {
                            MonoText("${row.number}. ${row.goal}", style = SwarmType.body.copy(fontSize = 14.sp, lineHeight = 20.sp))
                            if (row.outcome.isNotEmpty()) {
                                MonoText(row.outcome, style = SwarmType.label.copy(color = SwarmColors.graphite, fontSize = 12.sp, lineHeight = 16.sp))
                            }
                        }
                    }
                }
            }
        }
        if (steps.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Steps", style = SwarmType.label.copy(color = SwarmColors.graphite))
                steps.forEach { s ->
                    Column {
                        Text(
                            "${s.time}  ${s.what}",
                            style = SwarmType.mono.copy(fontSize = 12.sp, lineHeight = 16.sp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (s.result.isEmpty()) s.verdict else "${s.verdict}: ${s.result}",
                            style = SwarmType.label.copy(color = SwarmColors.graphite, fontSize = 12.sp, lineHeight = 16.sp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (end.isNotEmpty()) {
            SwarmCard { MonoText(end, style = SwarmType.body) }
        }
    }
}

/** The Hands the wiring has set, or null until it does (polled, because [HandsHub] is a plain holder). */
@Composable
fun rememberHands(): Hands? {
    var hands by remember { mutableStateOf(HandsHub.hands) }
    LaunchedEffect(hands) {
        while (hands == null) {
            delay(300)
            hands = HandsHub.hands
        }
    }
    return hands
}
