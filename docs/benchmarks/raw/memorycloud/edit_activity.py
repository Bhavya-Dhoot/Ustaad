import os
base = r"H:\UStaaf\.claude\worktrees\agent-ac34ad0bc1b4ddd46\app\app\src"
main = os.path.join(base, "main", "java", "com", "swarmos", "app", "memcloud")
test = os.path.join(base, "test", "java", "com", "swarmos", "app", "memcloud")

def edit(d, name, pairs):
    p = os.path.join(d, name)
    s = open(p, encoding="utf-8").read()
    for old, new in pairs:
        if old not in s:
            raise SystemExit("missing in %s: %s" % (name, old[:70]))
        s = s.replace(old, new, 1)
    open(p, "w", encoding="utf-8").write(s)

edit(main, "MemCloudLogic.kt", [(
"""    /** Whether a new load may start from [phase]""", """    /** The line the whole phone shows while a split runs (PhoneActivity, notification, banner); null when nothing is going on. */
    fun activityText(s: MemCloudState): String? {
        val phones = s.shares.size
        val peers = s.phones.filter { !it.isDriver }.joinToString(" and ") { it.name }
        return when (s.phase) {
            Phase.PLANNING, Phase.STARTING_PEERS -> if (phones >= 2) "Preparing a large model across $phones phones." else "Preparing a large model across phones."
            Phase.LOADING -> "Loading a large model across $phones phones (${overallPercent(s.shares, s.phones)} percent)."
            Phase.READY -> "Running a large model with $peers."
            Phase.DEGRADED -> "A large model is paused: ${s.lostPhone ?: "a phone"} stopped answering."
            Phase.BASELINE -> "Trying a large model on this phone alone."
            Phase.IDLE, Phase.FAILED -> null
        }
    }

    /** Whether a new load may start from [phase]"""),
])

edit(main, "MemCloud.kt", [
("""    init {
        recoverAfterKill()
    }""", """    init {
        recoverAfterKill()
        // the whole phone says what it is doing (banner and notification); the banner's stop button stops the split
        scope.launch {
            _state.collect { s ->
                val text = MemCloudLogic.activityText(s)
                if (text == null) PhoneActivity.clear("memcloud") else PhoneActivity.set("memcloud", text) { stop() }
            }
        }
    }"""),
("""    fun stop() {
        scope.launch {
            val old = synchronized(lock) { opJob }
            old?.cancel()
            teardownSplit()
            restoreAll()
            _state.update { MemCloudState(phase = Phase.IDLE, baseline = it.baseline, smallModel = it.smallModel, steps = it.steps.takeLast(30) + "Stopped; chat and embedding servers restored", updatedAtMs = now()) }
        }
    }""", """    fun stop() {
        scope.launch {
            try {
                val old = synchronized(lock) { opJob }
                old?.cancel()
                teardownSplit()
                restoreAll()
                _state.update { MemCloudState(phase = Phase.IDLE, baseline = it.baseline, smallModel = it.smallModel, steps = it.steps.takeLast(30) + "Stopped; chat and embedding servers restored", updatedAtMs = now()) }
            } finally {
                PhoneActivity.clear("memcloud")
            }
        }
    }"""),
("""import com.swarmos.app.core.Ports""", """import com.swarmos.app.core.PhoneActivity
import com.swarmos.app.core.Ports"""),
])

edit(main, "RpcWorker.kt", [
("""import com.swarmos.app.AppGraph
""", """import com.swarmos.app.AppGraph
import com.swarmos.app.core.PhoneActivity
"""),
("""            _lending.value = Lending(fromNode, driverName, bind, where, _lending.value?.sinceMs ?: System.currentTimeMillis())
            return StartResult.Ok(state())""", """            _lending.value = Lending(fromNode, driverName, bind, where, _lending.value?.sinceMs ?: System.currentTimeMillis())
            announce(driverName)
            return StartResult.Ok(state())"""),
("""            _lending.value = Lending(fromNode, driverName, bind, where, System.currentTimeMillis())
            audit("memcloud.rpc.start\"""", """            _lending.value = Lending(fromNode, driverName, bind, where, System.currentTimeMillis())
            announce(driverName)
            audit("memcloud.rpc.start\""""),
("""        LlamaRuntime.stop(Role.RPC)
        _lending.value = null
        val items = restoreItems""", """        LlamaRuntime.stop(Role.RPC)
        _lending.value = null
        PhoneActivity.clear("memcloud")
        val items = restoreItems"""),
("""                if (st != ServerState.READY && st != ServerState.STARTING) {
                    _lending.value = null
                    return@launch""", """                if (st != ServerState.READY && st != ServerState.STARTING) {
                    _lending.value = null
                    PhoneActivity.clear("memcloud")
                    return@launch"""),
("""    /** The driver's heartbeat must keep arriving""", """    /** The phone-wide activity line while rpc-server listens; its stop button ends the loan like the Memory tab's button. */
    private fun announce(driverName: String) {
        PhoneActivity.set("memcloud", "Lending memory to $driverName.") { stop("stopped from the banner") }
    }

    /** The driver's heartbeat must keep arriving"""),
])

edit(test, "MemCloudLogicTest.kt", [(
"""    @Test fun aNewLoadIsRefusedWhileASplitIsServingOrDegraded() {""", """    @Test fun theActivityLineFollowsThePhaseForTwoAndThreePhones() {
        val two = listOf(share("c", "Phone C", true, 0, 18, 1500), share("a", "Phone A", false, 18, 18, 1500))
        val phonesTwo = listOf(phone("c", "Phone C", true), phone("a", "Phone A", false, 0.41f))
        val loading = MemCloudState(phase = Phase.LOADING, shares = two, phones = phonesTwo)
        assertEquals("Loading a large model across 2 phones (41 percent).", MemCloudLogic.activityText(loading))
        assertEquals("Running a large model with Phone A.", MemCloudLogic.activityText(loading.copy(phase = Phase.READY)))
        val three = MemCloudState(
            phase = Phase.READY, shares = threeShares,
            phones = listOf(phone("a", "Phone A", true), phone("b", "Phone B", false), phone("c", "Phone C", false)),
        )
        assertEquals("Running a large model with Phone B and Phone C.", MemCloudLogic.activityText(three))
        assertEquals("A large model is paused: Phone B stopped answering.", MemCloudLogic.activityText(three.copy(phase = Phase.DEGRADED, lostPhone = "Phone B")))
        assertEquals("Preparing a large model across phones.", MemCloudLogic.activityText(MemCloudState(phase = Phase.PLANNING)))
        assertNull(MemCloudLogic.activityText(MemCloudState(phase = Phase.IDLE)))
        assertNull(MemCloudLogic.activityText(MemCloudState(phase = Phase.FAILED)))
    }

    @Test fun aNewLoadIsRefusedWhileASplitIsServingOrDegraded() {"""),
])
print("ok")
