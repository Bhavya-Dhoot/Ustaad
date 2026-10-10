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

edit("MemCloudScreen.kt", [
("""    val tick by ModelStore.tick.collectAsStateWithLifecycle()
    var showExpert by rememberSaveable { mutableStateOf(false) }
""", """    val tick by ModelStore.tick.collectAsStateWithLifecycle()
    val lending by RpcWorker.lending.collectAsStateWithLifecycle()
    var showExpert by rememberSaveable { mutableStateOf(false) }
"""),
("""        if (showExpert) {
            ExpertMeshView(st, onBack = { showExpert = false })
            return@Column
        }""", """        // rpc-server has no authentication: while it listens for another phone, say so in plain words
        lending?.let { l ->
            SwarmCard {
                Text(l.sentence, style = SwarmType.body)
                Text("It stops when the split ends or that phone is out of reach for 10 s.", style = SwarmType.label.copy(color = SwarmColors.graphite))
                Spacer(Modifier.height(8.dp))
                SecondaryButton("Stop lending", { RpcWorker.stop("stopped on this phone") }, Modifier.fillMaxWidth())
            }
        }
        if (showExpert) {
            ExpertMeshView(st, onBack = { showExpert = false })
            return@Column
        }"""),
])
print("ok")
