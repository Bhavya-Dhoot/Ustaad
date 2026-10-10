import os
base = r"H:\UStaaf\.claude\worktrees\agent-ac34ad0bc1b4ddd46\app\app\src"
main = os.path.join(base, "main", "java", "com", "swarmos", "app", "memcloud")
test = os.path.join(base, "test", "java", "com", "swarmos", "app", "memcloud")

def edit(d, name, pairs):
    p = os.path.join(d, name)
    s = open(p, encoding="utf-8").read()
    for old, new in pairs:
        if old not in s:
            raise SystemExit("missing in %s: %s" % (name, old[:80]))
        s = s.replace(old, new, 1)
    open(p, "w", encoding="utf-8").write(s)

# ---------------- logic helpers
edit(main, "MemCloudLogic.kt", [(
"""    /** Whether a new load may start from [phase]""", """    /** "1 phone", "2 phones"; [many] defaults to the one-form plus "s". */
    fun plural(n: Int, one: String, many: String = one + "s"): String = "$n ${if (n == 1) one else many}"

    /** Caption of a lane: short enough never to clip on a phone screen. */
    fun laneRole(isDriver: Boolean): String = if (isDriver) "this phone, driver" else "worker"

    /**
     * The GGUF `general.name` as a secondary line under the file name, only when it says something the file name does not:
     * at least four characters and not already contained in the file name ("Hf" and "Qwen3.5-4B" for Qwen3.5-4B-Q4_K_M.gguf are hidden).
     */
    fun secondaryName(fileName: String, ggufName: String?): String? {
        val n = ggufName?.trim().orEmpty()
        if (n.length < 4) return null
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        return if (norm(fileName.removeSuffix(".gguf")).contains(norm(n))) null else n
    }

    /** Subtitle under the title: what the planner says about the selected model on this phone. Null [fitsAlone] = no plan yet. */
    fun subtitle(fitsAlone: Boolean?): String = when (fitsAlone) {
        false -> "A model too big for one phone, split by layers."
        true -> "Fits this phone; splitting it is slower and shown only to prove the mechanism."
        null -> "A model split by layers across phones."
    }

    /** Text of the load button for [selectedPeers] other phones, and whether it can be pressed. */
    fun loadButton(selectedPeers: Int): Pair<String, Boolean> =
        if (selectedPeers < 1) "Pick at least one other phone" to false else "Load across ${plural(selectedPeers + 1, "phone")}" to true

    /** Accessibility text of the layer strip in the plan: what is planned, and that nothing is loaded yet. */
    fun planDescription(shares: List<ShareView>): String =
        "Plan, no layer loaded yet: " + shares.filter { it.layers > 0 }.joinToString(", ") { "${it.name} layers ${it.range}" }

    /** Whether a new load may start from [phase]"""), (
"""        return when (s.phase) {
            Phase.PLANNING, Phase.STARTING_PEERS -> if (phones >= 2) "Preparing a large model across $phones phones." else "Preparing a large model across phones.\"""",
"""        return when (s.phase) {
            Phase.PLANNING, Phase.STARTING_PEERS -> if (phones >= 2) "Preparing a large model across ${plural(phones, "phone")}." else "Preparing a large model across phones.\""""),
(""" "Loading a large model across $phones phones (${overallPercent(s.shares, s.phones)} percent).\"""", """ "Loading a large model across ${plural(phones, "phone")} (${overallPercent(s.shares, s.phones)} percent).\""""),
])

# ---------------- coordinator: baseline with an explicit file, preview with no peers
edit(main, "SplitCoordinator.kt", [
("""    fun onlinePeers(selection: List<String> = emptyList()): List<PeerInfo> =
        env.peers().filter { !it.away }
            .filter { p -> selection.isEmpty() || selection.any { it.equals(p.nodeId, true) || it.equals(p.name, true) } }
            .sortedBy { it.name }""", """    fun onlinePeers(selection: List<String> = emptyList(), emptyMeansNone: Boolean = false): List<PeerInfo> =
        env.peers().filter { !it.away }
            .filter { p -> if (selection.isEmpty()) !emptyMeansNone else selection.any { it.equals(p.nodeId, true) || it.equals(p.name, true) } }
            .sortedBy { it.name }"""),
("""    fun previewPlan(file: String, peers: List<String>, mode: SplitMode, manual: List<Int>? = null): SplitPlan {
        val info = header(file)
        return SplitPlanner.plan(info, devicesFor(onlinePeers(peers)), mode, manual)""", """    fun previewPlan(file: String, peers: List<String>, mode: SplitMode, manual: List<Int>? = null, emptyMeansNone: Boolean = false): SplitPlan {
        val info = header(file)
        return SplitPlanner.plan(info, devicesFor(onlinePeers(peers, emptyMeansNone)), mode, manual)"""),
("""    /** Returns null when the attempt started. It replaces the split server (they share the chat port). */
    fun baseline(timeoutSec: Int = 120): String? = synchronized(lock) {
        val st = _state.value
        val file = st.file ?: return "choose a model first (start a split once, or pass a file)\"""", """    /** Returns null when the attempt started. It replaces the split server (they share the chat port). [file] defaults to the one of the last split. */
    fun baseline(timeoutSec: Int = 120, file: String? = null): String? = synchronized(lock) {
        val st = _state.value
        val file = file?.let { n -> env.modelFile(n)?.name ?: return "no such model file in the models folder: $n" } ?: st.file ?: return "choose a model first\""""),
("""        _state.update { it.copy(phase = Phase.BASELINE, baseline = BaselineView(true, "LOADING", "Loading $file on this phone with no RPC", 0),""", """        _state.update { it.copy(phase = Phase.BASELINE, file = file, baseline = BaselineView(true, "LOADING", "Loading $file on this phone with no RPC", 0),"""),
])
edit(main, "MemCloud.kt", [
("""    fun previewPlan(file: String, peers: List<String>, mode: SplitMode, manual: List<Int>? = null): SplitPlan = coordinator.previewPlan(file, peers, mode, manual)""",
 """    fun previewPlan(file: String, peers: List<String>, mode: SplitMode, manual: List<Int>? = null, emptyMeansNone: Boolean = false): SplitPlan = coordinator.previewPlan(file, peers, mode, manual, emptyMeansNone)"""),
("""    fun baseline(timeoutSec: Int = 120): String? = coordinator.baseline(timeoutSec)""", """    fun baseline(timeoutSec: Int = 120, file: String? = null): String? = coordinator.baseline(timeoutSec, file)"""),
])
edit(main, "MemCloudState.kt", [(
"""@Serializable data class BaselineRequest(val timeoutSec: Int = 120)""", """@Serializable data class BaselineRequest(val timeoutSec: Int = 120, val file: String? = null)""")])
edit(main, "MemCloudModule.kt", [(
"""            val refused = MemCloud.baseline(req.timeoutSec)""", """            val refused = MemCloud.baseline(req.timeoutSec, req.file)""")])

# ---------------- screen
edit(main, "MemCloudScreen.kt", [
# subtitle follows the planner
("""    var showExpert by rememberSaveable { mutableStateOf(false) }

    // peers' model servers""", """    var showExpert by rememberSaveable { mutableStateOf(false) }
    var fitsAlone by remember { mutableStateOf<Boolean?>(null) } // what the planner says about the selected model on this phone

    // peers' model servers"""),
("""                Text("A model too big for one phone, split by layers.", style = SwarmType.body.copy(color = SwarmColors.graphite))""",
 """                Text(MemCloudLogic.subtitle(if (st.phase == Phase.IDLE || st.phase == Phase.FAILED || st.phase == Phase.BASELINE) fitsAlone else null), style = SwarmType.body.copy(color = SwarmColors.graphite))"""),
("""self.name, tick, onExpert = { showExpert = true })
            Phase.PLANNING""", """self.name, tick, onExpert = { showExpert = true }, onFits = { fitsAlone = it })
            Phase.PLANNING"""),
("""private fun PlanView(st: MemCloudState, onlineNames: List<String>, selfName: String, tick: Int, onExpert: () -> Unit) {""",
 """private fun PlanView(st: MemCloudState, onlineNames: List<String>, selfName: String, tick: Int, onExpert: () -> Unit, onFits: (Boolean?) -> Unit) {"""),
# the plan stays visible when every peer is switched off
("""        value = if (chosen == null || selected.isEmpty()) null else withContext(Dispatchers.IO) {
            try {
                PlanResult.Ok(MemCloud.previewPlan(chosen.file, selected, if (byRam) SplitMode.RAM else SplitMode.EQUAL, counts?.takeIf { c -> c.size == selected.size + 1 }))""",
 """        value = if (chosen == null) null else withContext(Dispatchers.IO) {
            try {
                PlanResult.Ok(MemCloud.previewPlan(chosen.file, selected, if (byRam) SplitMode.RAM else SplitMode.EQUAL, counts?.takeIf { c -> c.size == selected.size + 1 }, emptyMeansNone = true))"""),
("""    val plan = (result as? PlanResult.Ok)?.plan

    if (present.isEmpty()) {""", """    val plan = (result as? PlanResult.Ok)?.plan
    val alone = plan?.let { MemCloudLogic.fitsOnOnePhone(it.info.fileBytes, it.driver.device.ramAvailMb) }
    LaunchedEffect(alone) { onFits(alone) }

    if (present.isEmpty()) {"""),
# model card: file name first, GGUF name only when informative
("""                Column(Modifier.weight(1f)) {
                    Text(info.name, style = SwarmType.mono)
                    Text(info.architecture, style = SwarmType.label.copy(color = SwarmColors.graphite))
                }
                if (info.fileBytes / 1048576 > ownAvail) Stamp("Too big for one phone", caution = true)""", """                Column(Modifier.weight(1f)) {
                    Text(info.file, style = SwarmType.mono)
                    Text(listOfNotNull(MemCloudLogic.secondaryName(info.file, info.name), info.architecture).joinToString("  "), style = SwarmType.label.copy(color = SwarmColors.graphite))
                }
                if (!MemCloudLogic.fitsOnOnePhone(info.fileBytes, ownAvail)) Stamp("Too big for one phone", caution = true)"""),
("""        LayerStrip(plan.assignments(), List(plan.nLayers) { true })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (s in plan.shares)""", """        // the strip shows the plan in the phones' colours; what a screen reader hears says nothing is loaded yet
        Box(Modifier.clearAndSetSemantics { contentDescription = MemCloudLogic.planDescription(shareViewsOf(plan)) }) {
            LayerStrip(plan.assignments(), List(plan.nLayers) { true })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (s in plan.shares)"""),
("""Lane(s.device.name, if (s.device.isDriver) "this phone, drives the model" else "worker", trailing = {""", """Lane(s.device.name, MemCloudLogic.laneRole(s.device.isDriver), trailing = {"""),
# peers as switches
("""        SectionTitle("Phones to use")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            onlineNames.forEach { n ->
                val on = n in selected
                SecondaryButton((if (on) "Use " else "Skip ") + n, {
                    val ex = excluded.split(',').filter { it.isNotBlank() }.toMutableList()
                    if (on) ex += n else ex.remove(n)
                    excluded = ex.joinToString(","); counts = null
                }, Modifier.weight(1f))
            }
        }""", """        SectionTitle("Phones to use")
        onlineNames.forEach { n ->
            val on = n in selected
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (on) "$n, lending" else "$n, not used", style = SwarmType.body)
                InkSwitch(on, { want ->
                    val ex = excluded.split(',').filter { it.isNotBlank() }.toMutableList()
                    if (want) ex.remove(n) else ex += n
                    excluded = ex.joinToString(","); counts = null
                })
            }
        }"""),
("""    PrimaryButton(
        "Load across ${selected.size + 1} phones",
        { if (chosen != null) MemCloud.start(StartRequest(chosen.file, selected, if (byRam) "ram" else "equal", counts?.takeIf { c -> c.size == selected.size + 1 })) },
        Modifier.fillMaxWidth(),
        enabled = chosen != null && selected.isNotEmpty() && plan != null,
    )""", """    val (loadText, canPick) = MemCloudLogic.loadButton(selected.size)
    PrimaryButton(
        loadText,
        { if (chosen != null) MemCloud.start(StartRequest(chosen.file, selected, if (byRam) "ram" else "equal", counts?.takeIf { c -> c.size == selected.size + 1 })) },
        Modifier.fillMaxWidth(),
        enabled = chosen != null && canPick && plan != null,
    )"""),
("""    BaselinePanel(st, enabled = chosen != null && st.phase != Phase.BASELINE)
    if (st.steps.isNotEmpty()) StepsList(st)
}""", """    BaselinePanel(st, file = chosen?.file, enabled = chosen != null && st.phase != Phase.BASELINE)
    if (st.steps.isNotEmpty()) StepsList(st)
}

private fun shareViewsOf(plan: SplitPlan): List<ShareView> = plan.shares.map { s ->
    ShareView(s.device.nodeId, s.device.name, s.device.isDriver, s.firstLayer, s.layerCount, s.range, s.weightBytes / (1024 * 1024), 0, 0, true)
}"""),
# loading / running titles
("""        Text(st.model ?: st.file ?: "", style = SwarmType.mono)
        Text(
            when (st.phase) {""", """        ModelTitle(st)
        Text(
            when (st.phase) {"""),
("""    SwarmCard {
        Text(st.model ?: "", style = SwarmType.mono)
        Spacer(Modifier.height(6.dp))
        HeroMetric(""", """    SwarmCard {
        ModelTitle(st)
        Spacer(Modifier.height(6.dp))
        HeroMetric("""),
# lane captions elsewhere
("""        Lane(p.name, if (p.isDriver) "this phone, drives the model" else "worker", trailing = {""", """        Lane(p.name, MemCloudLogic.laneRole(p.isDriver), trailing = {"""),
("""                Lane(s.name, if (s.isDriver) "this phone" else "worker", trailing""", """                Lane(s.name, MemCloudLogic.laneRole(s.isDriver), trailing"""),
# plurals
("""PrimaryButton("Re-plan with ${remaining + 1} phones", """, """PrimaryButton("Re-plan with ${MemCloudLogic.plural(remaining + 1, "phone")}", """),
("""            if (!g.running && g.tokens > 0) Text("${g.tokens} tokens", """, """            if (!g.running && g.tokens > 0) Text(MemCloudLogic.plural(g.tokens, "token"), """),
("""            Text("${g.tokens} tokens streamed before the phone dropped", """, """            Text("${MemCloudLogic.plural(g.tokens, "token")} streamed before the phone dropped", """),
("""Text("${s.layers} layers x ${m.expertCount} experts = ${ExpertMesh.expertsOnPhone(s.layers, m.expertCount)} experts, all on this phone.", """,
 """Text("${MemCloudLogic.plural(s.layers, "layer")} x ${m.expertCount} experts = ${ExpertMesh.expertsOnPhone(s.layers, m.expertCount)} experts, all on this phone.", """),
("""            MetricRow("Estimate if experts were remote: ${m.moeLayers} layers x""", """            MetricRow("Estimate if experts were remote: ${MemCloudLogic.plural(m.moeLayers, "layer")} x"""),
# baseline panel
("""private fun BaselinePanel(st: MemCloudState, enabled: Boolean) {
    val b = st.baseline""", """private fun BaselinePanel(st: MemCloudState, file: String?, enabled: Boolean) {
    val b = st.baseline
    val target = file ?: st.file"""),
("""        Text("Same model, no other phone, no RPC.", style = SwarmType.label.copy(color = SwarmColors.graphite))""",
 """        Text("Same model, no other phone, no RPC.", style = SwarmType.label.copy(color = SwarmColors.graphite))
        // say what pressing the button does, so it never looks dead
        Text(
            if (target == null) "Pick a model above first."
            else "Loads $target on this phone alone and waits up to 120 s, then generates 32 tokens and reports what happened. This phone's chat and embedding servers stop meanwhile and come back afterwards.",
            style = SwarmType.label.copy(fontSize = 12.sp, lineHeight = 16.sp),
        )"""),
("""        SecondaryButton("Try on this phone alone", { MemCloud.baseline() }, Modifier.fillMaxWidth(), enabled = enabled && b?.running != true)""",
 """        SecondaryButton("Try on this phone alone", { MemCloud.baseline(120, target) }, Modifier.fillMaxWidth(), enabled = enabled && target != null && b?.running != true)"""),
("""    BaselinePanel(st, enabled = true)""", """    BaselinePanel(st, file = st.file, enabled = true)"""),
("""            Text(m.name, style = SwarmType.mono)""", """            Text(m.file, style = SwarmType.mono)"""),
# imports and the ModelTitle composable
("""import com.swarmos.app.ui.HeroMetric""", """import com.swarmos.app.ui.HeroMetric
import com.swarmos.app.ui.InkSwitch"""),
("""import androidx.compose.ui.unit.dp""", """import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp"""),
("""private sealed interface PlanResult {""", """/** The model's file name, with the GGUF name under it only when it adds something. */
@Composable
private fun ModelTitle(st: MemCloudState) {
    val file = st.file ?: st.model ?: ""
    Text(file, style = SwarmType.mono)
    MemCloudLogic.secondaryName(file, st.model)?.let { Text(it, style = SwarmType.label.copy(color = SwarmColors.graphite)) }
}

private sealed interface PlanResult {"""),
])
print("ok")
