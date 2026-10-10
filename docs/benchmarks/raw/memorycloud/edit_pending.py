import os
base = r"H:\UStaaf\.claude\worktrees\agent-ac34ad0bc1b4ddd46\app\app\src\main\java\com\swarmos\app\memcloud"

def edit(name, pairs):
    p = os.path.join(base, name)
    s = open(p, encoding="utf-8").read()
    for old, new in pairs:
        if old not in s:
            raise SystemExit("missing in %s: %s" % (name, old[:60]))
        s = s.replace(old, new, 1)
    open(p, "w", encoding="utf-8").write(s)

edit("MemCloudState.kt", [(
"""    val smallModel: SmallModelView? = null,
""",
"""    val smallModel: SmallModelView? = null,
    /** Model servers MemoryCloud stopped to make room and has not restored yet, e.g. "Phone A: chat Qwen3.5-4B-Q4_K_M.gguf". */
    val pendingRestore: List<String> = emptyList(),
""")])

edit("MemCloud.kt", [(
"""        restore[key] = merged
        persistRestore()
    }""",
"""        restore[key] = merged
        persistRestore()
        _state.update { it.copy(pendingRestore = pendingText()) }
    }

    private fun pendingText(): List<String> = restore.entries.flatMap { (k, items) ->
        val who = if (k == "self") AppGraph.registry.self.value.name else AppGraph.registry.peers.value.firstOrNull { it.info.nodeId == k }?.info?.name ?: k
        items.map { "$who: ${it.role} ${it.file}" }
    }"""),
("""baseline = prev.baseline, smallModel = prev.smallModel, steps = listOf("Reading the model header")""",
 """baseline = prev.baseline, smallModel = prev.smallModel, pendingRestore = pendingText(), steps = listOf("Reading the model header")""")])

edit("MemCloudScreen.kt", [(
"""    BaselinePanel(st, enabled = chosen != null && st.phase != Phase.BASELINE)
    if (st.steps.isNotEmpty()) StepsList(st)""",
"""    if (st.pendingRestore.isNotEmpty()) {
        Text("Stopped to make room: ${st.pendingRestore.joinToString("; ")}", style = SwarmType.label.copy(color = SwarmColors.graphite))
        SecondaryButton("Restore phones", { MemCloud.stop() }, Modifier.fillMaxWidth())
    }
    BaselinePanel(st, enabled = chosen != null && st.phase != Phase.BASELINE)
    if (st.steps.isNotEmpty()) StepsList(st)""")])
print("ok")
