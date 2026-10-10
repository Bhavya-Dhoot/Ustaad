import os
base = r"H:\UStaaf\.claude\worktrees\agent-ac34ad0bc1b4ddd46\app\app\src\main\java\com\swarmos\app\memcloud"

def edit(name, pairs):
    p = os.path.join(base, name)
    s = open(p, encoding="utf-8").read()
    for old, new in pairs:
        if old not in s:
            raise SystemExit("missing in %s: %s" % (name, old[:70]))
        s = s.replace(old, new, 1)
    open(p, "w", encoding="utf-8").write(s)

edit("MemCloud.kt", [(
"""        val keep = MemCloudLogic.replanPeers(participants.map { it.nodeId }, online, peers.map { sel -> AppGraph.registry.peers.value.firstOrNull { it.info.nodeId == sel || it.info.name.equals(sel, true) }?.info?.nodeId ?: sel })
        if (keep.isEmpty()) return "no phone is online to re-plan with\"""",
"""        val asked = peers.map { sel -> AppGraph.registry.peers.value.firstOrNull { it.info.nodeId == sel || it.info.name.equals(sel, true) }?.info?.nodeId ?: sel }
        // the phones of the last plan that are still online; when none is, whichever phones are online now
        val keep = MemCloudLogic.replanPeers(participants.map { it.nodeId }, online, asked).ifEmpty { online }
        if (keep.isEmpty()) return "no other phone is online to re-plan with\"""")])

edit("MemCloudScreen.kt", [
("""            Phase.READY -> RunningView(st, onExpert = { showExpert = true })
            Phase.DEGRADED -> DroppedView(st)""",
"""            Phase.READY -> RunningView(st, onExpert = { showExpert = true })
            Phase.DEGRADED -> DroppedView(st, peers.filter { !it.away }.map { it.info.nodeId }.toSet())"""),
("""private fun DroppedView(st: MemCloudState) {""", """private fun DroppedView(st: MemCloudState, onlineIds: Set<String>) {"""),
("""    val remaining = st.phones.count { !it.isDriver && it.online }
    PrimaryButton("Re-plan with ${remaining + 1} phones", { MemCloud.replan() }, Modifier.fillMaxWidth(), enabled = MemCloudLogic.canReplan(remaining))""",
"""    // phones that are online right now (the lost one counts again as soon as its heartbeat is back)
    val remaining = onlineIds.size
    PrimaryButton("Re-plan with ${remaining + 1} phones", { MemCloud.replan() }, Modifier.fillMaxWidth(), enabled = MemCloudLogic.canReplan(remaining))
    if (remaining == 0) Note("no other phone is online yet")"""),
])
print("ok")
