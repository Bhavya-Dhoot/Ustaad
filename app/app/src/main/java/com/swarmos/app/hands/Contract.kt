package com.swarmos.app.hands

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// The contract of Hands: the harness that carries out a task on the phone.
// Only data and interfaces live here. Plan and safety rules S1 to S7: plans/2026-10-13-assistant-harness.md.
// No Android imports in this file or in the core that implements it (hands/*.kt outside hands/android).

// ---------------------------------------------------------------- what the phone shows

/** One node of a UI tree as a driver reads it, before compaction. */
data class RawNode(
    val cls: String,
    val text: String,
    val desc: String,
    val hint: String,
    val resId: String,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val enabled: Boolean,
    val password: Boolean,
    val visible: Boolean,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val children: List<RawNode>,
    /** Opaque to the core. The driver uses it to find this node again; valid until the driver's next read(). */
    val handle: String,
)

data class RawScreen(
    val pkg: String,
    val activity: String,
    val widthPx: Int,
    val heightPx: Int,
    val root: RawNode?,
    /** The window could not be read (a secure window, no window, the service is off). */
    val unreadable: Boolean,
)

enum class Role { BUTTON, EDIT, TEXT, TOGGLE, LIST, IMAGE, OTHER }
enum class Flag { CLICKABLE, EDITABLE, SCROLLABLE, CHECKED, DISABLED, PASSWORD }

/** One element the model refers to by number. The model sees id, role, label, value and flags, never handle or position. */
data class UiElement(
    val id: Int,
    val role: Role,
    val label: String,
    /** Current content of an edit field; always empty for a password field. */
    val value: String,
    val flags: Set<Flag>,
    val handle: String,
    val cx: Int,
    val cy: Int,
    /**
     * Everything the guard judges this element by, never shown to the model and never cut: its own text and
     * description, the texts of its descendants, the texts of every clickable ancestor (a touch on a child
     * reaches the parent), and the words of its resource id. Normalised by the reader.
     */
    val guard: String = "",
)

data class Screen(
    val pkg: String,
    val activity: String,
    val elements: List<UiElement>,
    val unreadable: Boolean,
    /** Same screen content gives the same hash; used to notice that an action changed nothing. */
    val hash: String,
)

data class AppInfo(val pkg: String, val label: String)

// ---------------------------------------------------------------- the driver (hands/android implements two)

/**
 * Acts on the phone. Handles and positions come from the last read(); the core always reads before it decides.
 * Every method returns false (or an unreadable screen) instead of throwing when the phone refuses.
 * A driver carries no policy: the guard in the core decides what may be done before a driver is called.
 */
interface PhoneDriver {
    /** "accessibility" or "root". */
    val name: String
    fun ready(): Boolean
    suspend fun read(): RawScreen
    suspend fun tap(handle: String, cx: Int, cy: Int): Boolean
    suspend fun setText(handle: String, cx: Int, cy: Int, text: String): Boolean
    /** The enter or search key of the keyboard on the focused field. */
    suspend fun enter(): Boolean
    /** handle null: scroll the screen. */
    suspend fun scroll(handle: String?, forward: Boolean): Boolean
    suspend fun back(): Boolean
    suspend fun home(): Boolean
    suspend fun openApp(pkg: String): Boolean
    /**
     * The package that would open the URI: the single handler, or the person's default for it.
     * Empty when there is none or when the system would show a chooser. Nothing is started.
     */
    suspend fun linkTarget(uri: String): String

    /** A VIEW intent for the URI, sent to exactly that package. The core has checked the scheme and the package. */
    suspend fun openLink(uri: String, pkg: String): Boolean
    suspend fun apps(): List<AppInfo>
}

// ---------------------------------------------------------------- the model

/** FAST picks steps. STRONG plans and takes over a stuck subtask. */
enum class Tier { FAST, STRONG }

interface Brain {
    fun has(tier: Tier): Boolean

    /**
     * One completion whose output satisfies the JSON schema; returns that JSON as text.
     * onPhoneOnly true: the prompt holds screen text and must be answered on this phone unless the owner
     * switched on sharing screens with the cluster (rule S5). Throws when no model can take the request.
     */
    suspend fun complete(tier: Tier, system: String, user: String, schema: JsonObject, maxTokens: Int, onPhoneOnly: Boolean): String
}

// ---------------------------------------------------------------- experts

/** Knowledge experts (ExpertMesh) and the owner's own data (Data Union). */
interface ExpertDesk {
    fun domains(): List<String>
    /** null: no expert for the domain, or it could not answer. */
    suspend fun ask(domain: String, question: String): String?

    /**
     * The same with the route stated: onPhoneOnly true when the question may hold screen text (every question the
     * step model writes itself, and a planned question that comes after a phone subtask). Rule S5 applies to it.
     */
    suspend fun ask(domain: String, question: String, onPhoneOnly: Boolean): String? = ask(domain, question)
    suspend fun searchMyData(query: String): String?
}

/** App experts: a short card of hints for an app or for its category. Cards are data, the guard still decides. */
interface SkillBook {
    fun categories(): List<String>
    fun forApp(pkg: String): String?
    fun forCategory(category: String): String?
    /** The category of an installed app, by package or by label; null when unknown. */
    fun categoryOf(app: AppInfo): String?
}

// ---------------------------------------------------------------- the person

/** Why the person is asked. The UI words the prompt by it; a test gate can answer by it. */
enum class AskKind {
    /** The control's text names a committing action (pay, order, send, delete ...). */
    COMMIT,
    /** The screen has payment context: everything on it is asked about. */
    CHECKOUT_SCREEN,
    /** The control is not on the short list of controls known to be harmless (careful mode only). */
    UNKNOWN_CONTROL,
    /** The action is in, or opens, an app the plan did not name. */
    APP_SCOPE,
    LINK,
    ENTER,
    SCROLL,
    OTHER,
}

/**
 * How much Hands asks. CAREFUL (the default): a tap is free only on a control established as harmless, everything
 * else is asked about. FEWER_QUESTIONS: a tap is asked about only when it is recognised as committing; the
 * recognition is a word list and can miss a button, which the switch in the UI says in plain words.
 */
enum class Caution { CAREFUL, FEWER_QUESTIONS }

data class ConfirmPrompt(
    val runId: String,
    /** Label of the app the action is in. */
    val app: String,
    /** What will be done, for example: tap "Place order". */
    val action: String,
    /** Why the guard stopped here, for example: this places an order. */
    val why: String,
    val kind: AskKind = AskKind.OTHER,
)

/** Implemented by the UI only. Nothing but a tap of the person answers these; a timeout or a dismissal is a no. */
interface HumanGate {
    suspend fun confirm(prompt: ConfirmPrompt): Boolean
    /** null: dismissed. */
    suspend fun ask(runId: String, question: String): String?
}

// ---------------------------------------------------------------- tools

enum class Risk { READ, ACT, COMMIT }

/** params is a flat JSON schema object: every argument is a top-level string, integer or enum. */
@Serializable
data class ToolSpec(val name: String, val description: String, val params: JsonObject, val risk: Risk)

data class ToolCall(val tool: String, val args: JsonObject)
data class ToolResult(val ok: Boolean, val text: String)

enum class Verdict { ALLOWED, CONFIRMED, DECLINED, REFUSED }

// ---------------------------------------------------------------- a run

/** Where a task came from. Only OWN_SHEET and ASSISTANT_SESSION start without the person's tap on Run. */
enum class Origin { OWN_SHEET, ASSISTANT_SESSION, EXTERNAL_INTENT, MCP, APP_FUNCTION }

enum class Phase { WAITING_FOR_GO, PLANNING, RUNNING, WAITING_FOR_PERSON, DONE, FAILED, STOPPED }

enum class Kind { PHONE, KNOWLEDGE, DATA, REPLY }

@Serializable
data class SubTask(
    val goal: String,
    val kind: Kind,
    /** An app category or a knowledge domain; empty when none fits. */
    val expert: String = "",
    /** Package of the app a PHONE subtask works in; empty otherwise. */
    val app: String = "",
)

enum class SubStatus { TODO, RUNNING, DONE, FAILED, SKIPPED }

@Serializable
data class SubTaskState(val task: SubTask, val status: SubStatus = SubStatus.TODO, val outcome: String = "")

@Serializable
data class StepLine(
    val atMs: Long,
    /** Index of the subtask in the plan. */
    val sub: Int,
    val tier: Tier,
    val tool: String,
    /** Arguments as shown to the person; never screen dumps, never the content of a password field. */
    val args: String,
    val verdict: Verdict,
    val result: String,
)

@Serializable
data class RunState(
    val id: String,
    val task: String,
    val origin: Origin,
    val phase: Phase,
    val plan: List<SubTaskState> = emptyList(),
    val log: List<StepLine> = emptyList(),
    /** What is said or shown to the person at the end. */
    val result: String = "",
    val startedAtMs: Long,
    val endedAtMs: Long = 0,
)

data class HandsStatus(
    /** Name of the driver in use, empty when none is ready. */
    val driver: String,
    val accessibilityOn: Boolean,
    val rootAvailable: Boolean,
    val fast: Boolean,
    val strong: Boolean,
)

/** What the ways in (assist/), the Hands screen and the MCP route call. One run acts on the phone at a time. */
interface Hands {
    /** Newest first. */
    val runs: StateFlow<List<RunState>>

    /** Returns the run id. Origins other than OWN_SHEET and ASSISTANT_SESSION wait in WAITING_FOR_GO for go(). */
    fun start(task: String, origin: Origin): String

    /** The person's tap on Run. Called by UI code only. */
    fun go(runId: String)
    fun stop(runId: String)
    fun status(): HandsStatus

    /** The registry, for the MCP route. */
    fun tools(): List<ToolSpec>

    /** One tool call from outside a run. The guard and the person's prompts apply exactly as inside a run. */
    suspend fun call(call: ToolCall, origin: Origin): ToolResult
}

/** Set once by the wiring at app start; null until then (the UI shows that Hands is starting). */
object HandsHub {
    @Volatile
    var hands: Hands? = null
}
