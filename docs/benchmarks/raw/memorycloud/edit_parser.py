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

edit("MemCloud.kt", [
("""    private var participants: List<Peer> = emptyList()
""", """    private var participants: List<Peer> = emptyList()

    /** Accumulates the start-up log of the split server across watch ticks (the log ring only keeps the last 400 lines). */
    @Volatile private var loadParser = LoadLogParser()
"""),
("""        val prev = _state.value
        _state.value = MemCloudState(phase = Phase.PLANNING""", """        val prev = _state.value
        loadParser = LoadLogParser()
        _state.value = MemCloudState(phase = Phase.PLANNING"""),
("""        val parser = LoadLogParser().also { it.feed(LlamaRuntime.logTail(Role.CHAT, 400)) }
        val log = parser.snapshot()""", """        val log = loadParser.also { it.feed(LlamaRuntime.logTail(Role.CHAT, 400)) }.snapshot()"""),
])
print("ok")
