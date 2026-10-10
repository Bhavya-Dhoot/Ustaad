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

edit("MemCloudState.kt", [
("""    val rssMb: Long? = null,
    /** Peers: anonymous memory""", """    val rssMb: Long? = null,
    /** Highest resident memory of that process so far (VmHWM). */
    val peakMb: Long? = null,
    /** Peers: anonymous memory"""),
("""    val rssMb: Long?,
    /** Anonymous resident memory: the tensors the driver has sent so far. */""", """    val rssMb: Long?,
    /** Highest resident memory of rpc-server since it started (VmHWM). */
    val peakMb: Long? = null,
    /** Anonymous resident memory: the tensors the driver has sent so far. */"""),
])
edit("RpcWorker.kt", [(
"""            anonMb = anon?.div(1024),""", """            peakMb = if (running) LlamaRuntime.peakRssKb(Role.RPC)?.div(1024) else null,
            anonMb = anon?.div(1024),""")])
edit("MemCloud.kt", [
("""                    v.copy(rssMb = LlamaRuntime.rssKb(Role.CHAT)?.div(1024), thermalStatus""", """                    v.copy(rssMb = LlamaRuntime.rssKb(Role.CHAT)?.div(1024), peakMb = LlamaRuntime.peakRssKb(Role.CHAT)?.div(1024), thermalStatus"""),
("""                        rssMb = st?.rssMb ?: v.rssMb, receivedMb""", """                        rssMb = st?.rssMb ?: v.rssMb, peakMb = st?.peakMb ?: v.peakMb, receivedMb"""),
])
edit("MemCloudScreen.kt", [(
"""            MetricRow("Memory in use", mbText(p.rssMb))""", """            MetricRow("Memory in use", mbText(p.rssMb), "peak ${mbText(p.peakMb)}")""")])
print("ok")
