package com.swarmos.app.memcloud

import com.swarmos.app.llm.ChatChunk
import com.swarmos.app.llm.ChatRequest
import com.swarmos.app.llm.ChatResult
import com.swarmos.app.llm.ChatStats
import com.swarmos.app.llm.LlmClient
import com.swarmos.app.llm.Role
import com.swarmos.app.llm.ServerState
import com.swarmos.app.llm.ServerStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** A phone that records everything the coordinator does to it. */
private class FakeEnv : SplitEnv {
    val calls = CopyOnWriteArrayList<String>()
    val dir: File = File(System.getProperty("java.io.tmpdir"), "memcloud-test-${System.nanoTime()}").also { it.mkdirs() }
    val existing = mutableSetOf("model.gguf")

    @Volatile var peerList = listOf(
        PeerInfo("b", "Phone B", "10.0.0.2:8787", false, 9000, 1),
        PeerInfo("c", "Phone C", "10.0.0.3:8787", false, 9000, 1),
    )
    private val smallModels = mapOf(
        "chat" to RoleSnap("READY", "small.gguf", 5000),
        "embed" to RoleSnap("READY", "embed.gguf", 600),
    )
    val peerRoles = mutableMapOf<String, Map<String, RoleSnap>>("10.0.0.2:8787" to smallModels, "10.0.0.3:8787" to smallModels)
    val failingRpcStart = mutableSetOf<String>()
    val deadRpc = mutableSetOf<String>()

    /** Peers on the older build: they ignore stopOwn, stop nothing themselves and answer /rpc/start without stoppedOwn. */
    val oldLenders = mutableSetOf<String>()

    /** Peers no call reaches (switched off, out of range). */
    val unreachable = mutableSetOf<String>()

    /** Peers that answer but refuse /models/load. */
    val failingLoad = mutableSetOf<String>()

    /** Peers that stop their servers on /rpc/start and then drop the connection. */
    val diesAfterStop = mutableSetOf<String>()

    /** What the driver had saved to disk at the moment each peer received /rpc/start. */
    val recordAtStart = java.util.concurrent.ConcurrentHashMap<String, Persisted>()

    val local = mutableMapOf(
        Role.CHAT to LocalServer(ServerState.READY, "small.gguf", 5000),
        Role.EMBED to LocalServer(ServerState.READY, "embed.gguf", 600),
        Role.RPC to LocalServer(ServerState.STOPPED, null, null),
    )

    enum class Chat { READY, FAIL, HANG }
    @Volatile var chatMode = Chat.READY
    val chatStarts = AtomicInteger()
    val llmChats = AtomicInteger()
    val llmStreams = AtomicInteger()
    val activity = CopyOnWriteArrayList<String>()
    @Volatile var persisted = Persisted()
    val saved = CopyOnWriteArrayList<Persisted>()

    val gate = object : LlmClient {
        override val modelName = "small"
        override suspend fun ready() = true
        override fun chatStream(req: ChatRequest): Flow<ChatChunk> = flow {
            llmStreams.incrementAndGet()
            emit(ChatChunk("hello "))
            emit(ChatChunk("", done = true, stats = ChatStats(5, 2, 100.0, 300.0)))
        }
        override suspend fun chat(req: ChatRequest): ChatResult {
            llmChats.incrementAndGet()
            return ChatResult("x", ChatStats(10, 48, 100.0, 3000.0))
        }
        override suspend fun embed(texts: List<String>) = emptyList<FloatArray>()
    }

    override fun nowMs() = System.currentTimeMillis()
    override fun selfId() = "a"
    override fun selfName() = "Phone A"
    override fun selfHealth() = Health(9000, 0)
    override fun peers() = peerList
    override fun heartbeatRttMs(nodeId: String) = 10.0
    override suspend fun tcpRttMs(host: String, port: Int) = 5.0
    override fun threads() = 6

    override fun modelFile(name: String): File? = ModelFiles.clean(name)?.takeIf { it in existing }?.let { File(dir, it).also { f -> f.writeText("x") } }
    override fun readHeader(file: File) = GgufInfo(file.name, 3_000_000_000, "qwen35", "Test 4B", 32, 2560, 4096, 0, 0, 0, List(32) { 70_000_000L }, 500_000_000, 10_000_000, 0)

    private fun reach(addr: String) { if (addr in unreachable) throw java.io.IOException("$addr does not answer") }

    override suspend fun peerModels(addr: String): Map<String, RoleSnap> { reach(addr); return peerRoles.getValue(addr) }
    override suspend fun peerStopRole(addr: String, role: String) { reach(addr); calls += "stopRole $addr $role" }
    override suspend fun peerLoadModel(addr: String, role: String, file: String) {
        reach(addr)
        if (addr in failingLoad) throw java.io.IOException("load refused")
        calls += "loadModel $addr $role $file"
    }
    override suspend fun peerRpcStart(addr: String, req: RpcStartRequest): RpcStateDto {
        reach(addr)
        calls += "rpcStart $addr restore=${req.restore.joinToString(",") { it.role + ":" + it.file }} stopOwn=${req.stopOwn}"
        recordAtStart[addr] = saved.lastOrNull() ?: Persisted()
        if (addr in diesAfterStop) { // stopped its servers, then the connection or the phone was lost
            peerRoles[addr] = peerRoles.getValue(addr).mapValues { RoleSnap("STOPPED", null, null) }
            throw IllegalStateException("connection lost")
        }
        if (addr in failingRpcStart) throw IllegalStateException("rpc-server failed to start")
        if (req.stopOwn && addr !in oldLenders) {
            // a new build records the list and the lease, then stops its own servers, then starts rpc-server
            val running = peerRoles.getValue(addr).filterValues { it.state != "STOPPED" }
            calls += "lenderStopsOwn $addr ${running.keys.sorted().joinToString(",")}"
            peerRoles[addr] = peerRoles.getValue(addr).mapValues { RoleSnap("STOPPED", null, null) }
            return dto("READY").copy(stoppedOwn = running.map { (r, s) -> RestoreItem(r, s.file!!, s.rssMb) })
        }
        return dto("READY")
    }
    override suspend fun peerRpcRenew(addr: String): RpcStateDto { calls += "rpcRenew $addr"; return dto("READY") }
    override suspend fun peerRpcStop(addr: String) { reach(addr); calls += "rpcStop $addr" }
    override suspend fun peerRpcState(addr: String): RpcStateDto {
        if (addr in deadRpc) throw IllegalStateException("no answer")
        return dto("READY")
    }
    override fun reachable(host: String, port: Int) = true

    private fun dto(state: String) = RpcStateDto(
        state = state, port = 50052, bind = "10.0.0.2", pid = 1, rssMb = 100, anonMb = 90, cachedBytes = 0, cachedFiles = 0,
        reason = null, lastLog = emptyList(), thermalStatus = 0, ramAvailMb = 9000, args = emptyList(),
    )

    override fun local(role: Role) = local.getValue(role)
    override fun localPeakMb(role: Role): Long? = null
    override fun stopLocal(role: Role) { calls += "stopLocal ${role.tag}"; local[role] = LocalServer(ServerState.STOPPED, null, null) }
    override fun restoreLocal(role: Role, file: String) { calls += "restoreLocal ${role.tag} $file"; local[role] = LocalServer(ServerState.READY, file, 100) }

    override fun startChat(path: String, args: List<String>, readyTimeoutMs: Long): Deferred<ServerStatus> {
        chatStarts.incrementAndGet()
        calls += "startChat ${args.joinToString(" ")}"
        val d = CompletableDeferred<ServerStatus>()
        when (chatMode) {
            Chat.READY -> {
                local[Role.CHAT] = LocalServer(ServerState.READY, File(path).name, 2000)
                d.complete(ServerStatus(Role.CHAT, ServerState.READY, readyAfterMs = 1500))
            }
            Chat.FAIL -> d.complete(ServerStatus(Role.CHAT, ServerState.FAILED, reason = "exited with code 1 during start"))
            Chat.HANG -> {}
        }
        return d
    }
    override fun chatLog(n: Int) = emptyList<String>()
    override val llm: LlmClient get() = gate

    override fun setActivity(owner: String, text: String, onStop: () -> Unit) { activity += "set $owner $text" }
    override fun clearActivity(owner: String) { activity += "clear $owner" }
    override fun load() = persisted
    override fun save(p: Persisted) { saved += p }

    fun count(prefix: String) = calls.count { it.startsWith(prefix) }
}

class SplitCoordinatorTest {
    private lateinit var env: FakeEnv
    private lateinit var scope: CoroutineScope
    private lateinit var c: SplitCoordinator
    private val timing = SplitCoordinator.Timing(watchMs = 20, renewMs = 20, degradedRenewMs = 150, loadTimeoutMs = 60_000, settleMs = 1, restoreRetryMs = 30)

    @Before fun setUp() {
        env = FakeEnv()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        c = SplitCoordinator(env, scope, timing)
    }

    @After fun tearDown() {
        scope.cancel()
        env.dir.deleteRecursively()
    }

    private fun awaitState(what: String, ms: Long = 5_000, cond: (MemCloudState) -> Boolean): MemCloudState = runBlocking {
        try {
            withTimeout(ms) {
                while (!cond(c.state.value)) delay(5)
                c.state.value
            }
        } catch (e: Exception) {
            throw AssertionError("timed out waiting for $what; phase=${c.state.value.phase} fail=${c.state.value.failReason} steps=${c.state.value.steps.takeLast(4)} calls=${env.calls}")
        }
    }

    private fun awaitCall(what: String, ms: Long = 5_000, cond: () -> Boolean) = runBlocking {
        try { withTimeout(ms) { while (!cond()) delay(5) } } catch (e: Exception) { throw AssertionError("timed out waiting for $what; calls=${env.calls}") }
    }

    private fun start(vararg peers: String) = c.start(StartRequest("model.gguf", peers.toList()))

    private fun lastActivityIsClear() = env.activity.lastOrNull { it.contains(SplitCoordinator.ACTIVITY_OWNER) }?.startsWith("clear ") == true

    @Test fun aSplitLoadsStopsPeersMemoryHygieneAndComesBack() {
        assertNull(start("Phone B"))
        val s = awaitState("READY") { it.phase == Phase.READY }
        assertEquals(listOf("Phone A", "Phone B"), s.phones.map { it.name })
        // memory hygiene: rpc-server is started with the restore list and the instruction to stop its own servers; the driver stops none itself
        assertEquals(0, env.count("stopRole"))
        assertEquals(1, env.count("lenderStopsOwn 10.0.0.2:8787 chat,embed"))
        assertTrue(env.calls.any { it.startsWith("rpcStart 10.0.0.2:8787 restore=chat:small.gguf,embed:embed.gguf stopOwn=true") })
        assertEquals(listOf("chat small.gguf, 4.9 GB", "embed embed.gguf, 600 MB"), s.phones.first { it.name == "Phone B" }.freed)
        // and this phone's own servers
        assertEquals(1, env.count("stopLocal chat"))
        assertEquals(1, env.count("stopLocal embed"))
        // the split goes through the shared gate: the small model was measured through env.llm
        assertTrue(env.llmChats.get() >= 1)
        assertNotNull(s.smallModel)
        c.ask("hi", 20)
        awaitState("answer") { it.generation?.running == false && it.generation?.text == "hello " }
        assertEquals(1, env.llmStreams.get())

        c.stop()
        awaitState("IDLE") { it.phase == Phase.IDLE && it.pendingRestore.isEmpty() }
        assertEquals(1, env.count("rpcStop 10.0.0.2:8787"))
        assertEquals(1, env.count("restoreLocal chat small.gguf"))
        assertEquals(1, env.count("restoreLocal embed embed.gguf"))
        awaitCall("activity cleared") { lastActivityIsClear() }
    }

    @Test fun aFailedRpcStartOnOnePeerTearsEverythingDown() {
        env.failingRpcStart += "10.0.0.3:8787"
        assertNull(start("Phone B", "Phone C"))
        val s = awaitState("FAILED") { it.phase == Phase.FAILED }
        assertTrue(s.failReason!!.contains("Phone C"))
        awaitCall("cleanup") { env.count("rpcStop 10.0.0.2:8787") == 1 && env.count("rpcStop 10.0.0.3:8787") == 1 }
        // Phone C never got rpc-server running and its servers were never stopped (the start failed before that): nothing to load
        assertEquals(0, env.count("loadModel 10.0.0.3:8787"))
        // Phone B was running rpc-server, so it restores itself (it holds the list); the driver does not do it twice
        assertEquals(0, env.count("loadModel 10.0.0.2:8787"))
        // no llama-server was started, and the activity line is cleared
        assertEquals(0, env.chatStarts.get())
        awaitCall("activity cleared") { lastActivityIsClear() }
        assertTrue(c.state.value.pendingRestore.isEmpty())
    }

    @Test fun aServerThatDoesNotStartTearsEverythingDownIncludingThisPhone() {
        env.chatMode = FakeEnv.Chat.FAIL
        assertNull(start("Phone B"))
        val s = awaitState("FAILED") { it.phase == Phase.FAILED }
        assertTrue(s.failReason!!.contains("exited with code 1"))
        awaitCall("peer stopped") { env.count("rpcStop 10.0.0.2:8787") == 1 }
        awaitCall("this phone restored") { env.count("restoreLocal chat small.gguf") == 1 && env.count("restoreLocal embed embed.gguf") == 1 }
        awaitCall("activity cleared") { lastActivityIsClear() }
    }

    @Test fun anAbsentPeerListFailsCleanlyWithNothingToTearDown() {
        env.peerList = emptyList()
        assertNull(start())
        val s = awaitState("FAILED") { it.phase == Phase.FAILED }
        assertTrue(s.failReason!!.contains("No other phone"))
        assertEquals(0, env.count("stopLocal"))
        awaitCall("activity cleared") { lastActivityIsClear() }
    }

    @Test fun aCancelledLoadTearsDownOnceAndRestoresOnce() {
        env.chatMode = FakeEnv.Chat.HANG
        assertNull(start("Phone B"))
        awaitState("LOADING") { it.phase == Phase.LOADING }
        c.stop()
        awaitState("IDLE") { it.phase == Phase.IDLE }
        awaitCall("peer stopped") { env.count("rpcStop 10.0.0.2:8787") >= 1 }
        awaitCall("restored") { env.count("restoreLocal chat small.gguf") >= 1 }
        runBlocking { delay(100) }
        assertEquals(1, env.count("restoreLocal chat small.gguf"))
        assertEquals(1, env.count("restoreLocal embed embed.gguf"))
        assertTrue(lastActivityIsClear())
    }

    @Test fun cancellingTheLoadJobItselfAlsoTearsDown() {
        env.chatMode = FakeEnv.Chat.HANG
        assertNull(start("Phone B"))
        awaitState("LOADING") { it.phase == Phase.LOADING }
        // the job is cancelled from outside (the scope goes away, as when the driver app is shutting down)
        scope.cancel()
        awaitCall("peer stopped after cancel") { env.count("rpcStop 10.0.0.2:8787") >= 1 }
        awaitCall("this phone restored after cancel") { env.count("restoreLocal chat small.gguf") >= 1 }
    }

    @Test fun workersAreRenewedWhileLoadingAndServingAndNotAfterTheSplitEnds() {
        assertNull(start("Phone B", "Phone C"))
        awaitState("READY") { it.phase == Phase.READY }
        awaitCall("renewals") { env.count("rpcRenew 10.0.0.2:8787") >= 2 && env.count("rpcRenew 10.0.0.3:8787") >= 2 }
        c.stop()
        awaitState("IDLE") { it.phase == Phase.IDLE }
        runBlocking { delay(80) }
        val after = env.count("rpcRenew")
        runBlocking { delay(120) }
        assertEquals("no renewals once the split is over", after, env.count("rpcRenew"))
    }

    @Test fun aLostPhoneGivesDegradedAndRenewalsStopAfterTheQuickReplanWindow() {
        assertNull(start("Phone B", "Phone C"))
        awaitState("READY") { it.phase == Phase.READY }
        env.peerList = env.peerList.map { if (it.nodeId == "c") it.copy(away = true) else it }
        val s = awaitState("DEGRADED") { it.phase == Phase.DEGRADED }
        assertEquals("Phone C", s.lostPhone)
        assertEquals(listOf(true, true, false), s.phones.map { it.online })
        assertEquals("llama-server was killed", ServerState.STOPPED, env.local[Role.CHAT]!!.state)
        // renewals go on for the quick re-plan window, then stop so the workers' leases run out
        awaitCall("renewals during the window") { env.count("rpcRenew 10.0.0.2:8787") >= 1 }
        runBlocking { delay(timing.degradedRenewMs + 150) }
        val after = env.count("rpcRenew")
        runBlocking { delay(120) }
        assertEquals(after, env.count("rpcRenew"))
    }

    @Test fun aReplanDoesNotStopThePhonesThatRemain() {
        assertNull(start("Phone B", "Phone C"))
        awaitState("READY") { it.phase == Phase.READY }
        env.peerList = env.peerList.map { if (it.nodeId == "c") it.copy(away = true) else it }
        awaitState("DEGRADED") { it.phase == Phase.DEGRADED }
        assertNull(c.replan())
        val s = awaitState("READY again") { it.phase == Phase.READY && it.phones.size == 2 }
        assertEquals(listOf("Phone A", "Phone B"), s.phones.map { it.name })
        assertEquals("the remaining worker kept its rpc-server", 0, env.count("rpcStop 10.0.0.2:8787"))
        assertEquals(2, env.chatStarts.get())
    }

    @Test fun aSecondStartIsRefusedWhileOneRunsAndFileNamesAreChecked() {
        env.chatMode = FakeEnv.Chat.HANG
        assertNull(start("Phone B"))
        awaitState("LOADING") { it.phase == Phase.LOADING }
        assertTrue(start("Phone B")!!.startsWith("busy"))
        c.stop()
        awaitState("IDLE") { it.phase == Phase.IDLE }
        // a name that climbs out of the models folder, or does not exist there, is refused
        assertTrue(c.start(StartRequest("../../etc/passwd.gguf", listOf("Phone B")))!!.contains("no such model file"))
        assertTrue(c.start(StartRequest("missing.gguf", listOf("Phone B")))!!.contains("no such model file"))
        assertEquals("a path is reduced to its last element before the lookup", null, c.start(StartRequest("../../models/model.gguf", listOf("Phone B"))))
        c.stop()
    }

    @Test fun theAppRestartingDuringASplitCleansUpWhatTheKilledSessionLeft() {
        env.persisted = Persisted(
            restore = mapOf(
                "self" to listOf(RestoreItem("chat", "small.gguf"), RestoreItem("embed", "embed.gguf")),
                "b" to listOf(RestoreItem("chat", "small.gguf")),
            ),
            peerAddrs = mapOf("b" to "10.0.0.2:8787"),
        )
        env.local[Role.CHAT] = LocalServer(ServerState.STOPPED, null, null)
        env.local[Role.EMBED] = LocalServer(ServerState.STOPPED, null, null)
        env.peerRoles["10.0.0.2:8787"] = mapOf("chat" to RoleSnap("STOPPED", null, null), "embed" to RoleSnap("READY", "embed.gguf", 600))
        runBlocking { c.onAppStart() }
        assertEquals(1, env.count("rpcStop 10.0.0.2:8787"))
        assertEquals("only the role that is still stopped is loaded back on the peer", 1, env.count("loadModel 10.0.0.2:8787 chat small.gguf"))
        assertEquals(1, env.count("restoreLocal chat small.gguf"))
        assertEquals(1, env.count("restoreLocal embed embed.gguf"))
        assertEquals(Persisted(), env.saved.last())
        assertTrue(lastActivityIsClear())
        // once only
        runBlocking { c.onAppStart() }
        assertEquals(1, env.count("rpcStop 10.0.0.2:8787"))
    }

    @Test fun anAppRestartAfterAKilledBaselineSaysSo() {
        env.persisted = Persisted(baselinePending = "model.gguf|${System.currentTimeMillis() - 5000}|120")
        runBlocking { c.onAppStart() }
        val b = c.state.value.baseline!!
        assertEquals("KILLED", b.outcome)
        assertEquals("model.gguf", c.state.value.file)
    }

    @Test fun theBaselineReleasesTheWorkersAndRestoresThisPhoneWhateverHappens() {
        assertNull(start("Phone B"))
        awaitState("READY") { it.phase == Phase.READY }
        env.chatMode = FakeEnv.Chat.FAIL
        assertNull(c.baseline(5))
        val s = awaitState("baseline result") { it.phase == Phase.IDLE && it.baseline?.running == false }
        assertEquals("FAILED", s.baseline!!.outcome)
        awaitCall("peer released") { env.count("rpcStop 10.0.0.2:8787") >= 1 }
        awaitCall("this phone restored") { env.count("restoreLocal chat small.gguf") >= 1 }
        assertTrue(c.state.value.pendingRestore.isEmpty())
    }

    @Test fun theBaselineFromAnIdlePhoneRunsTheOnePhoneAttemptAndSaysWhatHappened() {
        // no split has ever run: the button still does something, with an explicit file
        assertEquals("choose a model first", c.baseline(5))
        assertTrue(c.baseline(5, "missing.gguf")!!.contains("no such model file"))
        assertNull(c.baseline(5, "model.gguf"))
        val s = awaitState("baseline result") { it.phase == Phase.IDLE && it.baseline?.running == false }
        assertEquals("OK", s.baseline!!.outcome)
        assertEquals("model.gguf", s.file)
        assertEquals("no other phone is involved", 0, env.count("rpcStart"))
        awaitCall("this phone restored") { env.count("restoreLocal chat small.gguf") == 1 }
        assertTrue(env.calls.any { it.startsWith("startChat -ngl 0") })
    }

    @Test fun thePlanPreviewCanBeForThisPhoneAloneOrForEveryOnlinePhone() {
        assertEquals(3, c.previewPlan("model.gguf", emptyList(), SplitMode.EQUAL).shares.size)                           // empty = all online peers
        assertEquals(1, c.previewPlan("model.gguf", emptyList(), SplitMode.EQUAL, null, emptyMeansNone = true).shares.size) // every switch off
        assertEquals(2, c.previewPlan("model.gguf", listOf("Phone B"), SplitMode.EQUAL, null, emptyMeansNone = true).shares.size)
    }

    // ---------- finding 1: the loan and the restore list exist on a lender before its servers are stopped ----------

    @Test fun aNewLenderGetsTheLoanAndTheRestoreListBeforeItStopsItsServers() {
        assertNull(start("Phone B"))
        awaitState("READY") { it.phase == Phase.READY }
        val rpcStart = env.calls.indexOfFirst { it.startsWith("rpcStart 10.0.0.2:8787") }
        val stops = env.calls.indexOfFirst { it.startsWith("lenderStopsOwn 10.0.0.2:8787") }
        assertTrue("no server is stopped before /rpc/start, which carries the list and the lease", rpcStart in 0 until stops)
        assertEquals("the driver stops nothing on the peer itself", 0, env.count("stopRole"))
        // and the driver's own record of what is owed was on disk before the peer was asked
        val onDisk = env.recordAtStart.getValue("10.0.0.2:8787")
        assertEquals(listOf("chat", "embed"), onDisk.restore.getValue("b").map { it.role })
        assertEquals("10.0.0.2:8787", onDisk.peerAddrs["b"])
    }

    @Test fun anOlderLenderIsAskedToStopItsServersOnlyAfterTheLoanExists() {
        env.oldLenders += "10.0.0.2:8787"
        assertNull(start("Phone B"))
        val s = awaitState("READY") { it.phase == Phase.READY }
        val rpcStart = env.calls.indexOfFirst { it.startsWith("rpcStart 10.0.0.2:8787") }
        val stopChat = env.calls.indexOf("stopRole 10.0.0.2:8787 chat")
        val stopEmbed = env.calls.indexOf("stopRole 10.0.0.2:8787 embed")
        assertTrue("an older build keeps its servers until the driver stops them, after /rpc/start", rpcStart in 0 until stopChat && rpcStart in 0 until stopEmbed)
        assertEquals(0, env.count("lenderStopsOwn"))
        assertEquals(listOf("chat small.gguf, 4.9 GB", "embed embed.gguf, 600 MB"), s.phones.first { it.name == "Phone B" }.freed)
        // the lease is already being renewed while the servers are stopped
        awaitCall("renewals") { env.count("rpcRenew 10.0.0.2:8787") >= 1 }
    }

    @Test fun aLenderThatStoppedItsServersAndDiedHalfWayGetsThemLoadedBackByTheDriver() {
        env.diesAfterStop += "10.0.0.3:8787"
        assertNull(start("Phone B", "Phone C"))
        awaitState("FAILED") { it.phase == Phase.FAILED }
        // the snapshot says stopped, so the driver loads what its record says was running
        awaitCall("C loaded back") { env.count("loadModel 10.0.0.3:8787 chat small.gguf") == 1 && env.count("loadModel 10.0.0.3:8787 embed embed.gguf") == 1 }
    }

    @Test fun recoveryKeepsTheRecordOfAPeerItCannotReach() {
        env.persisted = Persisted(
            restore = mapOf(
                "self" to listOf(RestoreItem("chat", "small.gguf")),
                "b" to listOf(RestoreItem("chat", "small.gguf")),
                "c" to listOf(RestoreItem("chat", "small.gguf")),
            ),
            peerAddrs = mapOf("b" to "10.0.0.2:8787", "c" to "10.0.0.3:8787"),
        )
        env.local[Role.CHAT] = LocalServer(ServerState.STOPPED, null, null)
        env.unreachable += "10.0.0.2:8787"
        env.peerRoles["10.0.0.3:8787"] = mapOf("chat" to RoleSnap("STOPPED", null, null), "embed" to RoleSnap("READY", "embed.gguf", 600))
        runBlocking { c.onAppStart() }
        val rec = env.saved.last()
        assertEquals("only the unreachable peer stays on record", mapOf("b" to listOf(RestoreItem("chat", "small.gguf"))), rec.restore)
        assertEquals(mapOf("b" to "10.0.0.2:8787"), rec.peerAddrs)
        assertEquals(listOf("Phone B: chat small.gguf"), c.state.value.pendingRestore)
        // the reachable peer and this phone were put right
        assertEquals(1, env.count("loadModel 10.0.0.3:8787 chat small.gguf"))
        assertEquals(1, env.count("restoreLocal chat small.gguf"))
    }

    // ---------- finding 2: a restore that is not confirmed is kept, retried and shown ----------

    @Test fun aPeerThatDoesNotAnswerWhenTheSplitStopsKeepsItsRecordIsRetriedAndShown() {
        assertNull(start("Phone B", "Phone C"))
        awaitState("READY") { it.phase == Phase.READY }
        env.unreachable += "10.0.0.3:8787"
        c.stop()
        val s = awaitState("IDLE with a notice") { it.phase == Phase.IDLE && it.notices.isNotEmpty() }
        assertEquals(listOf("Phone C has not got its models back yet"), s.notices)
        assertEquals(listOf("Phone C: chat small.gguf", "Phone C: embed embed.gguf"), s.pendingRestore)
        assertEquals("Phone B answered and is clear", 1, env.count("rpcStop 10.0.0.2:8787"))
        val rec = env.saved.last()
        assertEquals(setOf("c"), rec.restore.keys)
        assertEquals(mapOf("c" to "10.0.0.3:8787"), rec.peerAddrs)
        // the phone answers again: the next tick stops its loan, loads what is still stopped, and the record goes
        env.unreachable -= "10.0.0.3:8787"
        val after = awaitState("record cleared") { it.notices.isEmpty() && it.pendingRestore.isEmpty() }
        assertTrue(after.phase == Phase.IDLE)
        assertEquals(1, env.count("rpcStop 10.0.0.3:8787"))
        assertEquals(1, env.count("loadModel 10.0.0.3:8787 chat small.gguf"))
        assertEquals(Persisted(), env.saved.last())
    }

    @Test fun aLoadThatAPeerRefusesIsKeptAndRetriedUntilItWorks() {
        env.diesAfterStop += "10.0.0.3:8787"
        env.failingLoad += "10.0.0.3:8787"
        assertNull(start("Phone B", "Phone C"))
        val s = awaitState("FAILED with a notice") { it.phase == Phase.FAILED && it.notices.isNotEmpty() }
        assertEquals(listOf("Phone C has not got its models back yet"), s.notices)
        assertEquals(0, env.count("loadModel 10.0.0.3:8787"))
        env.failingLoad -= "10.0.0.3:8787"
        awaitState("record cleared") { it.notices.isEmpty() }
        assertEquals(1, env.count("loadModel 10.0.0.3:8787 chat small.gguf"))
        assertEquals(1, env.count("loadModel 10.0.0.3:8787 embed embed.gguf"))
        assertTrue(c.state.value.pendingRestore.isEmpty())
    }

    @Test fun aRetryNeverTouchesAPhoneThatTheNextSplitIsUsing() {
        assertNull(start("Phone B", "Phone C"))
        awaitState("READY") { it.phase == Phase.READY }
        env.unreachable += "10.0.0.3:8787"
        c.stop()
        awaitState("notice") { it.phase == Phase.IDLE && it.notices.isNotEmpty() }
        // the phone answers again, but is away in the registry: no call goes to it
        env.unreachable -= "10.0.0.3:8787"
        env.peerList = env.peerList.map { if (it.nodeId == "c") it.copy(away = true) else it }
        runBlocking { delay(150) }
        assertEquals(0, env.count("rpcStop 10.0.0.3:8787"))
        assertTrue(c.state.value.notices.isNotEmpty())
    }

    @Test fun theActivityLineBelongsToTheDriverRoleOnly() {
        assertNull(start("Phone B"))
        awaitState("READY") { it.phase == Phase.READY }
        awaitCall("running line") { env.activity.any { it == "set ${SplitCoordinator.ACTIVITY_OWNER} Running a large model with Phone B." } }
        assertTrue("the driver never touches the lending owner", env.activity.none { it.contains(RpcLender.ACTIVITY_OWNER) })
        assertTrue(SplitCoordinator.ACTIVITY_OWNER != RpcLender.ACTIVITY_OWNER)
    }
}
