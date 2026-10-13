package com.swarmos.app.assist

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.swarmos.app.hands.Hands
import com.swarmos.app.hands.HandsHub
import com.swarmos.app.hands.android.HandsRuntime
import com.swarmos.app.hands.RunState
import com.swarmos.app.hands.ui.RunDetail
import com.swarmos.app.hands.ui.actingRun
import com.swarmos.app.hands.ui.isEnded
import com.swarmos.app.hands.ui.rememberHands
import com.swarmos.app.ui.BackLink
import com.swarmos.app.ui.Note
import com.swarmos.app.ui.PrimaryButton
import com.swarmos.app.ui.SecondaryButton
import com.swarmos.app.ui.SectionTitle
import com.swarmos.app.ui.SwarmColors
import com.swarmos.app.ui.SwarmTextField
import com.swarmos.app.ui.SwarmTheme
import com.swarmos.app.ui.SwarmType
import com.swarmos.app.ui.MonoText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.ui.graphics.Color as ComposeColor

/** What the task sheet holds. One instance per activity; the activity survives rotation (configChanges), so nothing is saved. */
@Stable
internal class SheetState {
    var task by mutableStateOf("")
    /** The text in the field came from another app and has not been emptied since. */
    var seeded by mutableStateOf(false)
    var fromSession by mutableStateOf(false)
    /** The task was dictated: its result is read aloud. */
    var spoken by mutableStateOf(false)
    var runId by mutableStateOf<String?>(null)
    var promptOnly by mutableStateOf(false)
    /** Raised at each opening by the assistant session with an empty field: the sheet starts listening if it may. */
    var listenTrigger by mutableIntStateOf(0)

    val hasContent: Boolean get() = task.isNotBlank() || runId != null

    fun open(task: String, fromSession: Boolean) {
        this.task = task
        seeded = task.isNotEmpty()
        this.fromSession = fromSession
        spoken = false
        runId = null
        promptOnly = false
        if (fromSession && task.isEmpty()) listenTrigger++
    }
}

/**
 * The task sheet: a bottom sheet over whatever app is in front. It answers the assistant gesture, ACTION_ASSIST, ACTION_VOICE_COMMAND,
 * shared text, selected text and the link ustaad://do?q=... A task that came from another app is shown in the field and waits for the
 * person's tap on Run; nothing starts by itself. The same window shows the confirmation prompt of [HumanGateUi].
 */
class AssistActivity : ComponentActivity() {
    private val sheet = SheetState()
    private var interactive by mutableStateOf(false)
    private var resumed = false
    private var focused = false
    private val spokenRuns = HashSet<String>()
    private lateinit var speaker: Speaker

    /** The last prompt this window showed: if the window closes while it is pending, it ends as a no. */
    @Volatile
    private var lastPromptId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        // a window that covers this one must not receive the person's tap meant for it (tapjacking)
        window.decorView.filterTouchesWhenObscured = true
        if (Build.VERSION.SDK_INT >= 31) runCatching { window.setHideOverlayWindows(true) }
        speaker = Speaker(this)
        if (savedInstanceState == null) handle(intent)
        setContent { SwarmTheme { SheetRoot() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        isResumed = true
        interactive = resumed && focused
    }

    override fun onPause() {
        resumed = false
        isResumed = false
        interactive = false
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        focused = hasFocus
        interactive = resumed && focused
    }

    override fun onDestroy() {
        // a prompt that was on this window when it closed is a no (the id check ignores a prompt that came later)
        if (isFinishing && lastPromptId != 0L) HumanGateUi.answerNo(lastPromptId)
        speaker.close()
        super.onDestroy()
    }

    /** Closing the sheet is a dismissal: a prompt that is pending ends as a no. */
    private fun close() {
        HumanGateUi.pending.value?.let { HumanGateUi.answerNo(it.id) }
        finish()
    }

    /** Reads what the intent carries. A hostile intent (bad extras) opens an empty sheet. */
    private fun handle(intent: Intent?) {
        if (intent == null) return
        try {
            if (intent.getBooleanExtra(EXTRA_PROMPT_ONLY, false)) {
                if (!sheet.hasContent) sheet.promptOnly = true
                return
            }
            val extras = HashMap<String, String>()
            for (key in listOf(Intent.EXTRA_TEXT, Intent.EXTRA_PROCESS_TEXT)) {
                intent.getCharSequenceExtra(key)?.toString()?.let { extras[key] = it }
            }
            val incoming = AssistIntents.parse(intent.action, intent.dataString, intent.type, extras)
            val fromSession = SessionPass.shared.redeem(intent.getStringExtra(EXTRA_SESSION_TOKEN))
            // a run that is still going stays in view: a new request does not replace it
            val shown = sheet.runId?.let { id -> HandsHub.hands?.runs?.value?.firstOrNull { it.id == id } }
            if (shown != null && !isEnded(shown.phase)) return
            sheet.open(incoming.task, fromSession)
        } catch (e: RuntimeException) {
            Log.w("AssistActivity", "unreadable intent: ${e.javaClass.simpleName}")
            sheet.open("", fromSession = false)
        }
    }

    @Composable
    private fun SheetRoot() {
        val hands = rememberHands()
        val pending by HumanGateUi.pending.collectAsStateWithLifecycle()
        val runs by remember(hands) { hands?.runs ?: MutableStateFlow(emptyList()) }.collectAsStateWithLifecycle()
        val run = sheet.runId?.let { id -> runs.firstOrNull { it.id == id } }

        // a window opened only for a prompt closes when the prompt is over
        LaunchedEffect(pending == null, sheet.promptOnly) { if (pending == null && sheet.promptOnly) finish() }
        // the result of a dictated task is read aloud once
        LaunchedEffect(run?.id, run?.phase) {
            if (run != null && isEnded(run.phase) && sheet.spoken && run.result.isNotBlank() && spokenRuns.add(run.id)) {
                speaker.speak(SheetRules.spokenText(run.result))
            }
        }
        BackHandler { close() }

        val dictation = rememberDictation(
            text = { sheet.task },
            onText = { heard -> sheet.task = heard; sheet.spoken = true },
        )
        LaunchedEffect(sheet.listenTrigger) { if (sheet.listenTrigger > 0) dictation.startIfAllowed() }

        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(ComposeColor(0x6615171A)) // Ink at 40 percent behind the sheet, no blur
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close() },
            contentAlignment = Alignment.BottomCenter,
        ) {
            val shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
            Column(
                Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .heightIn(max = maxHeight * 0.9f)
                    .clip(shape)
                    .background(SwarmColors.whiteboard)
                    .border(1.5.dp, SwarmColors.ink, shape)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {} // taps on the sheet are not taps outside it
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val p = pending
                when {
                    p != null -> {
                        LaunchedEffect(p.id) { lastPromptId = p.id }
                        PromptPanel(p, interactive)
                    }
                    sheet.runId != null -> RunPanel(hands, run)
                    else -> TaskPanel(hands, runs, dictation)
                }
            }
        }
    }

    @Composable
    private fun TaskPanel(hands: Hands?, runs: List<RunState>, dictation: Dictation) {
        val busy = actingRun(runs) != null
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Ask Ustaad", Modifier.weight(1f))
            BackLink({ close() }, label = "Close")
        }
        if (sheet.seeded) Note("This came from another app. Read it, then tap Run.")
        SwarmTextField(
            value = sheet.task,
            onValueChange = { sheet.task = it; sheet.seeded = SheetRules.stillSeeded(sheet.seeded, it); if (it.isEmpty()) sheet.spoken = false },
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
                val origin = SheetRules.runOrigin(sheet.seeded, sheet.fromSession)
                // the sheet is in front: the foreground service is started first, then the run
                val id = HandsRuntime.startFromUi(this@AssistActivity, h, sheet.task.trim(), origin)
                if (SheetRules.needsGo(origin)) HandsRuntime.goFromUi(this@AssistActivity, h, id) // this tap is the person's go
                sheet.runId = id
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = SheetRules.canRun(sheet.task, hands != null, busy),
        )
    }

    @Composable
    private fun RunPanel(hands: Hands?, run: RunState?) {
        val ended = run != null && isEnded(run.phase)
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Ustaad", Modifier.weight(1f))
            BackLink({ close() }, label = "Close")
        }
        Text("Task", style = SwarmType.label.copy(color = SwarmColors.graphite))
        MonoText(sheet.task.trim(), maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (run == null) Text("Starting the run.", style = SwarmType.label.copy(color = SwarmColors.graphite)) else RunDetail(run, maxSteps = 4)
        if (ended) {
            SecondaryButton("New task", onClick = { speaker.stop(); sheet.open("", fromSession = false) }, modifier = Modifier.fillMaxWidth())
        } else {
            SecondaryButton(
                "Stop",
                onClick = {
                    speaker.stop()
                    val id = sheet.runId
                    if (hands != null && id != null) hands.stop(id)
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    @Composable
    private fun PromptPanel(p: PendingPrompt, interactive: Boolean) {
        var armed by remember(p.id) { mutableStateOf(false) }
        // "Do it" stays off until the prompt has been in front, resumed and in focus, for the arming time; covering it switches it off again
        LaunchedEffect(p.id, interactive) {
            if (interactive) {
                HumanGateUi.markVisible(p.id)
                delay(PromptBoard.ARM_MS + 60)
                armed = true
            } else {
                HumanGateUi.markHidden(p.id)
                armed = false
            }
        }
        if (p.isQuestion) {
            var answer by remember(p.id) { mutableStateOf("") }
            SectionTitle("Ustaad has a question")
            MonoText(p.action, style = SwarmType.body)
            SwarmTextField(answer, { answer = it }, "Your answer", singleLine = false, minLines = 2, maxLines = 4)
            PrimaryButton(
                "Send answer",
                onClick = { HumanGateUi.answerText(p.id, answer) },
                modifier = Modifier.fillMaxWidth(),
                enabled = answer.isNotBlank(),
            )
            SecondaryButton("Skip", onClick = { HumanGateUi.answerNo(p.id) }, modifier = Modifier.fillMaxWidth())
        } else {
            SectionTitle("Ustaad asks you first")
            PromptLine("App", p.app)
            PromptLine("Action", p.action)
            if (p.why.isNotEmpty()) PromptLine("Why you are asked", p.why)
            Text(
                if (armed) "Nothing happens unless you tap Do it." else "Read it first. Do it turns on in a moment.",
                style = SwarmType.label.copy(color = SwarmColors.graphite),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                SecondaryButton("Do not", onClick = { HumanGateUi.answerNo(p.id) }, modifier = Modifier.weight(1f))
                PrimaryButton("Do it", onClick = { HumanGateUi.answerYes(p.id) }, modifier = Modifier.weight(1f), enabled = armed)
            }
        }
    }

    @Composable
    private fun PromptLine(label: String, value: String) {
        Column {
            Text(label, style = SwarmType.label.copy(color = SwarmColors.graphite))
            MonoText(value.ifEmpty { "-" }, style = SwarmType.body)
        }
    }

    companion object {
        /** Put in the intent by [UstaadSession] only; a random one-time value that [SessionPass] checks. */
        const val EXTRA_SESSION_TOKEN = "com.swarmos.app.assist.SESSION_TOKEN"

        /** Opens the window for the pending prompt only; it changes nothing else about the sheet. */
        const val EXTRA_PROMPT_ONLY = "com.swarmos.app.assist.PROMPT_ONLY"

        /** True while a sheet is resumed in front of the person. */
        @Volatile
        var isResumed = false
            private set

        fun promptIntent(context: Context): Intent =
            Intent(context, AssistActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_PROMPT_ONLY, true)
    }
}
