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
import kotlinx.coroutines.awaitCancellation
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

    /** The megabytes the driver asked of each peer in its last /rpc/start (-1: none). */
    val needMbSeen = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** How many more /rpc/start calls to a peer fail because its rpc port is still held. */
    val portHeld = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** The loan token the driver sent with its last renew and stop to each peer ("none": no token). */
    val renewLoans = java.util.concurrent.ConcurrentHashMap<String, String>()
    val stopLoans = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Peers that answer /rpc/stop with 409: the loan is another phone's or a newer one. */
    val refusingStop = mutableSetOf<String>()

    /** Peers that answer but refuse /models/load. */
    val failingLoad = mutableSetOf<String>()

    /** A /models/load for "address role" that answers with this HTTP status (404: the file is gone; 400: the request is wrong for that phone), or fails on the network. */
    val loadStatus = java.util.concurrent.ConcurrentHashMap<String, Int>()
    val loadIo: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Peers that stop their servers on /rpc/start and then drop the connection; [diedState] is what their roles read afterwards. */
    val diesAfterStop = mutableSetOf<String>()
    @Volatile var diedState = "STOPPED"

    /** The loan each lender holds now (address to token), issued by /rpc/start like the real lender: the same driver asking again keeps the loan. */
    val currentLoan = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val loansIssued = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** When set, a /rpc/stop to [stopGateAddr] (any peer when null) is sent, then waits here before the lender sees it: a call that is out on the network. */
    @Volatile var stopGate: CompletableDeferred<Unit>? = null
    @Volatile var stopGateAddr: String? = null
    val stopParked = CopyOnWriteArrayList<String>()

    /** Every /rpc/stop the driver sent: "address token" ("none" for no token). */
    val stopsSent = CopyOnWriteArrayList<String>()

    /** When set, the first /rpc/state call waits here (the driver's final refresh while it loads); [stateParked] says it got there. */
    val firstStateGate = java.util.concurrent.atomic.AtomicReference<CompletableDeferred<Unit>?>(null)
    val stateParked = java.util.concurrent.atomic.AtomicBoolean(false)

    /** This phone's own restore of a model server throws (a teardown that fails). */
    @Volatile var restoreThrows = false

    /** What the driver had saved to disk at the moment each peer received /rpc/start. */
    val recordAtStart = java.util.concurrent.ConcurrentHashMap<String, Persisted>()

    val local = mutableMapOf(
        Role.CHAT to LocalServer(ServerState.READY, "small.gguf", 5000),
        Role.EMBED to LocalServer(ServerState.READY, "embed.gguf", 600),
        Role.RPC to LocalServer(ServerState.STOPPED, null, null),
    )

    /** How long the 48-token run of the small model takes, and how long /rpc/start takes on a peer. */
    @Volatile var chatDelayMs = 0L
    @Volatile var rpcStartDelayMs = 0L

    /** What the split model's stream does: answers, or goes quiet before or after its first token (a hung rpc-server). */
    enum class Stream { NORMAL, HANG_BEFORE_FIRST, HANG_AFTER_FIRST }
    @Volatile var streamMode = Stream.NORMAL

    /** Peers whose every second /rpc/state check fails (a flapping link). */
    val flaky = mutableSetOf<String>()
    val probeCount = java.util.concurrent.ConcurrentHashMap<String, Int>()

    enum class Chat { READY, FAIL, HANG, THROW }
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
            if (streamMode == Stream.HANG_BEFORE_FIRST) awaitCancellation()
            emit(ChatChunk("hello "))
            if (streamMode == Stream.HANG_AFTER_FIRST) awaitCancellation()
            emit(ChatChunk("", done = true, stats = ChatStats(5, 2, 100.0, 300.0)))
        }
        override suspend fun chat(req: ChatRequest): ChatResult {
            llmChats.incrementAndGet()
            calls += "llmChat start"
            if (chatDelayMs > 0) delay(chatDelayMs)
            calls += "llmChat done"
            return ChatResult("x", ChatStats(10, 48, 100.0, 3000.0))
        }
        override suspend fun embed(texts: List<String>) = emptyList<FloatArray>()
    }

    override fun nowMs() = System.currentTimeMillis()
    override fun selfId() = "a"
    override fun selfName() = "Phone A"
    override fun selfHealth() = Health(9000, 0)
    /** This phone's Wi-Fi addresses: the fake peers (10.0.0.2, 10.0.0.3) are on its network. */
    @Volatile var localLinkList = listOf(LinkAddr("10.0.0.1", false, 24))
    override fun localLinks() = localLinkList

    /** Runs once, on whichever thread asks next for the registry (a test parks a coroutine inside its own check with it). */
    @Volatile var peersHook: (() -> Unit)? = null

    override fun peers(): List<PeerInfo> {
        peersHook?.let { peersHook = null; it() }
        return peerList
    }
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
        loadStatus["$addr $role"]?.let { throw com.swarmos.app.link.NodeHttpException(it, "no such model file: $file") }
        if ("$addr $role" in loadIo) throw java.io.IOException("connection reset")
        calls += "loadModel $addr $role $file"
    }
    override suspend fun peerRpcStart(addr: String, req: RpcStartRequest): RpcStateDto {
        reach(addr)
        calls += "rpcStart $addr restore=${req.restore.joinToString(",") { it.role + ":" + it.file }} stopOwn=${req.stopOwn}"
        recordAtStart[addr] = saved.lastOrNull() ?: Persisted()
        needMbSeen[addr] = req.needMb ?: -1L
        val held = portHeld[addr] ?: 0
        if (held > 0) {
            portHeld[addr] = held - 1
            throw IllegalStateException("HTTP 409: {\"reason\":\"port 50052 is still in use after two retries (another app or an adb shell process holds it)\"}")
        }
        if (addr in diesAfterStop) { // stopped its servers, then the connection or the phone was lost
            peerRoles[addr] = peerRoles.getValue(addr).mapValues { RoleSnap(diedState, null, null) }
            throw IllegalStateException("connection lost")
        }
        if (rpcStartDelayMs > 0) delay(rpcStartDelayMs)
        if (addr in failingRpcStart) throw IllegalStateException("rpc-server failed to start")
        calls += "rpcServerListening $addr"
        if (req.stopOwn && addr !in oldLenders) {
            // a new build records the list and the lease, then stops its own servers, then starts rpc-server
            val running = peerRoles.getValue(addr).filterValues { it.state != "STOPPED" }
            calls += "lenderStopsOwn $addr ${running.keys.sorted().joinToString(",")}"
            peerRoles[addr] = peerRoles.getValue(addr).mapValues { RoleSnap("STOPPED", null, null) }
            return dto("READY").copy(stoppedOwn = running.map { (r, s) -> RestoreItem(r, s.file!!, s.rssMb) }, loan = issue(addr))
        }
        return dto("READY")
    }

    /** The token of the loan on [addr]: a new one when the lender holds none ("loan-addr" the first time), else the one it holds. */
    private fun issue(addr: String): String = currentLoan.computeIfAbsent(addr) {
        val n = loansIssued.merge(addr, 1, Int::plus)!!
        if (n == 1) "loan-$addr" else "loan-$addr#$n"
    }
    override suspend fun peerRpcRenew(addr: String, loan: String?): RpcStateDto { calls += "rpcRenew $addr"; renewLoans[addr] = loan ?: "none"; return dto("READY") }
    override suspend fun peerRpcStop(addr: String, loan: String?) {
        reach(addr)
        stopLoans[addr] = loan ?: "none"
        stopsSent += "$addr ${loan ?: "none"}"
        val gate = stopGate
        if (gate != null && (stopGateAddr == null || stopGateAddr == addr)) { stopParked += addr; gate.await() }
        if (addr in refusingStop) throw LoanNotOurs("the loan belongs to Phone X")
        // the lender's own rule: a stop that names a loan it no longer holds is refused (a stop with no token is taken from the owner)
        val held = currentLoan[addr]
        if (loan != null && held != null && loan != held) throw LoanNotOurs("that loan has ended; this phone is lending under a newer one")
        currentLoan.remove(addr)
        calls += "rpcStop $addr"
    }
    override suspend fun peerRpcState(addr: String): RpcStateDto {
        firstStateGate.getAndSet(null)?.let { stateParked.set(true); it.await() }
        val n = probeCount.merge(addr, 1, Int::plus)!!
        if (addr in deadRpc) throw IllegalStateException("no answer")
        if (addr in flaky && n % 2 == 0) throw IllegalStateException("one lost check")
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
    override fun restoreLocal(role: Role, file: String) {
        if (restoreThrows) throw IllegalStateException("restore failed")
        calls += "restoreLocal ${role.tag} $file"; local[role] = LocalServer(ServerState.READY, file, 100)
    }

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
            Chat.THROW -> throw IllegalStateException("exec failed")
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
    private val timing = SplitCoordinator.Timing(watchMs = 20, renewMs = 20, degradedRenewMs = 150, loadTimeoutMs = 60_000, settleMs = 1, restoreRetryMs = 30, portRetryDelayMs = 1, firstTokenStallMs = 600, tokenStallMs = 150, stallPollMs = 10, degradedRestoreMs = 400, restoreRetryMaxMs = 240)

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
        awaitCall("the baseline is accepted") { c.baseline(5) == null } // the load job may take a moment to finish after READY
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

    // ---------- finding 3: a lender whose rpc port is still held gets stop, wait, start again ----------

    @Test fun aLenderWhosePortIsStillHeldIsStoppedAndAskedAgain() {
        env.portHeld["10.0.0.2:8787"] = 1
        assertNull(start("Phone B"))
        val s = awaitState("READY") { it.phase == Phase.READY }
        val b = env.calls.filter { it.contains("10.0.0.2:8787") && (it.startsWith("rpcStart") || it.startsWith("rpcStop")) }.map { it.substringBefore(' ') }
        assertEquals(listOf("rpcStart", "rpcStop", "rpcStart"), b)
        assertTrue(s.steps.any { it.contains("still in use") && it.contains("1 of 2") })
    }

    @Test fun aPortThatStaysHeldFailsTheSplitAfterTwoRetries() {
        env.portHeld["10.0.0.2:8787"] = 10
        assertNull(start("Phone B"))
        val s = awaitState("FAILED") { it.phase == Phase.FAILED }
        assertEquals(3, env.count("rpcStart 10.0.0.2:8787"))
        assertTrue(s.failReason!!.contains("Phone B") && s.failReason!!.contains("in use"))
        assertEquals(0, env.chatStarts.get())
    }

    @Test fun anotherRefusalIsNotRetried() {
        env.failingRpcStart += "10.0.0.2:8787"
        assertNull(start("Phone B"))
        awaitState("FAILED") { it.phase == Phase.FAILED }
        assertEquals(1, env.count("rpcStart 10.0.0.2:8787"))
        assertTrue(MemCloudLogic.portInUse("HTTP 409: port 50052 is still in use after two retries"))
        assertFalse(MemCloudLogic.portInUse("rpc-server failed to start"))
        assertFalse(MemCloudLogic.portInUse(null))
    }

    // ---------- finding 4: a plan that does not fit is refused, unless forced ----------

    private fun makeBTight() {
        env.peerList = listOf(PeerInfo("b", "Phone B", "10.0.0.2:8787", false, 100, 1), PeerInfo("c", "Phone C", "10.0.0.3:8787", false, 9000, 1))
        env.peerRoles["10.0.0.2:8787"] = mapOf("chat" to RoleSnap("STOPPED", null, null), "embed" to RoleSnap("STOPPED", null, null))
    }

    @Test fun aPlanThatDoesNotFitIsRefusedBeforeAnythingIsStoppedOrSent() {
        makeBTight()
        assertNull(start("Phone B"))
        val s = awaitState("FAILED") { it.phase == Phase.FAILED }
        assertTrue(s.failReason!!.contains("does not fit") && s.failReason!!.contains("Phone B has 100 MB free"))
        assertTrue("it says how to go on", s.failReason!!.contains("force"))
        assertEquals(0, env.count("rpcStart"))
        assertEquals(0, env.count("stopLocal"))
        assertEquals(0, env.chatStarts.get())
    }

    @Test fun theOverrideRunsTheDeliberateDoesNotFitDemonstration() {
        makeBTight()
        assertNull(c.start(StartRequest("model.gguf", listOf("Phone B"), force = true)))
        val s = awaitState("READY") { it.phase == Phase.READY }
        assertTrue("the warning stays on the state", s.warnings.any { it.contains("Phone B") })
        assertEquals("the lender is not asked for what the driver was told to try", -1L, env.needMbSeen["10.0.0.2:8787"])
    }

    @Test fun theLenderIsAskedForExactlyItsPlannedShare() {
        assertNull(start("Phone B"))
        awaitState("READY") { it.phase == Phase.READY }
        val plan = c.previewPlan("model.gguf", listOf("Phone B"), SplitMode.EQUAL)
        assertEquals(plan.fits()[1].needMb, env.needMbSeen["10.0.0.2:8787"])
        assertTrue(plan.fits()[1].needMb > SplitPlanner.OVERHEAD_MB)
    }

    // ---------- finding 5: a hung split is noticed ----------

    @Test fun aStreamThatGoesQuietAfterItsFirstTokenDegradesTheSplitAndKeepsTheText() {
        assertNull(start("Phone B"))
        awaitState("READY") { it.phase == Phase.READY }
        env.streamMode = FakeEnv.Stream.HANG_AFTER_FIRST
        assertNull(c.ask("hi", 20))
        val s = awaitState("DEGRADED") { it.phase == Phase.DEGRADED }
        assertEquals("rpc-server answers its state check, so no phone can be named", "the split model", s.lostPhone)
        assertEquals("hello ", s.generation!!.text)
        assertEquals("the split model stopped answering", s.generation!!.error)
        assertFalse(s.generation!!.running)
        assertTrue(s.failReason!!.contains("no token for"))
        assertEquals("llama-server was killed", ServerState.STOPPED, env.local[Role.CHAT]!!.state)
    }

    @Test fun beforeTheFirstTokenTheStreamGetsTheLongerLimit() {
        assertNull(start("Phone B"))
        awaitState("READY") { it.phase == Phase.READY }
        env.streamMode = FakeEnv.Stream.HANG_BEFORE_FIRST
        assertNull(c.ask("hi", 20))
        runBlocking { delay(300) } // twice the limit after a first token, half the limit before it
        assertEquals(Phase.READY, c.state.value.phase)
        assertTrue(c.state.value.generation!!.running)
        val s = awaitState("DEGRADED") { it.phase == Phase.DEGRADED }
        assertTrue(s.failReason!!.contains("no first token"))
        assertEquals("the split model stopped answering", s.generation!!.error)
    }

    @Test fun aStallNamesThePhoneWhoseRpcServerStoppedAnswering() {
        c = SplitCoordinator(env, scope, timing.copy(probeFailLimit = 1_000)) // only the stall path may decide here
        assertNull(start("Phone B", "Phone C"))
        awaitState("READY") { it.phase == Phase.READY }
        env.deadRpc += "10.0.0.3:8787"
        env.streamMode = FakeEnv.Stream.HANG_AFTER_FIRST
        assertNull(c.ask("hi", 20))
        val s = awaitState("DEGRADED") { it.phase == Phase.DEGRADED }
        assertEquals("Phone C", s.lostPhone)
        assertEquals("Phone C stopped answering", s.generation!!.error)
        assertEquals(listOf(true, true, false), s.phones.map { it.online })
    }

    @Test fun threeFailedStateChecksInARowDegradeAServingSplitNamingThePhone() {
        assertNull(start("Phone B", "Phone C"))
        awaitState("READY") { it.phase == Phase.READY }
        env.deadRpc += "10.0.0.3:8787"
        val s = awaitState("DEGRADED") { it.phase == Phase.DEGRADED }
        assertEquals("Phone C", s.lostPhone)
        assertTrue(s.failReason!!.contains("3 state checks in a row"))
    }

    @Test fun anOccasionalLostStateCheckDoesNotDegradeTheSplit() {
        assertNull(start("Phone B", "Phone C"))
        awaitState("READY") { it.phase == Phase.READY }
        env.flaky += "10.0.0.3:8787" // every second check fails, never three in a row
        val before = env.probeCount["10.0.0.3:8787"] ?: 0
        awaitCall("a dozen more checks") { (env.probeCount["10.0.0.3:8787"] ?: 0) >= before + 12 }
        assertEquals(Phase.READY, c.state.value.phase)
    }

    // ---------- finding 6: DEGRADED does not hold this phone's own models for ever ----------

    private fun degradeByLosingC() {
        assertNull(start("Phone B", "Phone C"))
        awaitState("READY") { it.phase == Phase.READY }
        env.peerList = env.peerList.map { if (it.nodeId == "c") it.copy(away = true) else it }
        awaitState("DEGRADED") { it.phase == Phase.DEGRADED }
    }

    @Test fun aSplitThatStaysDegradedGivesThisPhoneItsModelsBackAndSaysSo() {
        degradeByLosingC()
        assertEquals("not at once: a quick re-plan stays quick", 0, env.count("restoreLocal chat small.gguf"))
        val s = awaitState("restored automatically") { st -> st.notices.any { it.contains("restored automatically") } }
        assertEquals("the split is still DEGRADED, only this phone is put right", Phase.DEGRADED, s.phase)
        assertEquals(1, env.count("restoreLocal chat small.gguf"))
        assertEquals(1, env.count("restoreLocal embed embed.gguf"))
        assertTrue(s.notices.single().startsWith("Phone A's own chat and embedding models were restored automatically after"))
        assertTrue(s.notices.single().contains("with no re-plan"))
        assertTrue(s.pendingRestore.isEmpty())
        assertEquals("the lenders were released with it", 1, env.count("rpcStop 10.0.0.2:8787"))
    }

    @Test fun aReplanBeforeTheTimeIsUpCancelsTheAutomaticRestore() {
        degradeByLosingC()
        assertNull(c.replan())
        awaitState("READY again") { it.phase == Phase.READY && it.phones.size == 2 }
        runBlocking { delay(timing.degradedRestoreMs + 200) }
        assertEquals(0, env.count("restoreLocal chat small.gguf"))
        // the only notice is about the phone that was lost, whose record stays until it answers; there is no automatic-restore sentence
        assertEquals(listOf("Phone C has not got its models back yet"), c.state.value.notices)
    }

    @Test fun aReplanAfterTheAutomaticRestoreStopsThePhonesModelsAgain() {
        degradeByLosingC()
        awaitState("restored automatically") { st -> st.notices.any { it.contains("restored automatically") } }
        awaitCall("the release is over") { env.local[Role.CHAT]!!.state == ServerState.READY }
        assertNull(c.replan())
        val s = awaitState("READY again") { it.phase == Phase.READY && it.phones.size == 2 }
        assertTrue("a new split starts with no old notice", s.notices.none { it.contains("restored automatically") })
        // stops of the chat server: for the first load, the split server killed by DEGRADED, and the restored model again for the second load
        assertEquals(3, env.count("stopLocal chat"))
    }

    // ---------- item 8: the small-model measurement runs while the lenders prepare ----------

    @Test fun theSmallModelIsMeasuredWhileTheLendersPrepareAndBeforeThisPhoneStopsIt() {
        env.chatDelayMs = 150
        env.rpcStartDelayMs = 150
        assertNull(start("Phone B", "Phone C"))
        val s = awaitState("READY") { it.phase == Phase.READY }
        val measureStart = env.calls.indexOf("llmChat start")
        val measureDone = env.calls.indexOf("llmChat done")
        val firstListening = env.calls.indexOfFirst { it.startsWith("rpcServerListening") }
        assertTrue("the measurement starts before the first lender is ready: $measureStart < $firstListening", measureStart in 0 until firstListening)
        assertTrue("it is over before this phone's own chat server is stopped", measureDone in 0 until env.calls.indexOf("stopLocal chat"))
        assertNotNull("the number is still shown", s.smallModel)
    }

    @Test fun aFailedLenderDropsTheMeasurementInsteadOfWaitingForIt() {
        env.chatDelayMs = 20_000
        env.failingRpcStart += "10.0.0.2:8787"
        assertNull(start("Phone B"))
        val s = awaitState("FAILED", 3_000) { it.phase == Phase.FAILED }
        assertTrue(s.failReason!!.contains("Phone B"))
        assertTrue("it never finished", env.calls.none { it == "llmChat done" })
        assertNull(s.smallModel)
    }

    // ---------- item 9: the explicit layers override is validated ----------

    @Test fun anExplicitLayerSplitIsCheckedBeforeAnythingStartsAndTheRouteCanAnswer400() {
        // the fake model has 32 layers; this phone and Phone B are two phones
        assertNull(c.validateStart(StartRequest("model.gguf", listOf("Phone B"))))
        assertNull(c.validateStart(StartRequest("model.gguf", listOf("Phone B"), layers = listOf(10, 22))))
        assertTrue(c.validateStart(StartRequest("model.gguf", listOf("Phone B"), layers = listOf(10, 10, 12)))!!.contains("needs 2 counts"))
        assertTrue(c.validateStart(StartRequest("model.gguf", listOf("Phone B"), layers = listOf(0, 32)))!!.contains("at least one layer"))
        assertTrue(c.validateStart(StartRequest("model.gguf", listOf("Phone B"), layers = listOf(10, 20)))!!.contains("add up to 30 but the model has 32 layers"))
        // an unknown file or no phone is start()'s own refusal, not a layers problem
        assertNull(c.validateStart(StartRequest("missing.gguf", listOf("Phone B"), layers = listOf(1, 1))))
        env.peerList = emptyList()
        assertNull(c.validateStart(StartRequest("model.gguf", emptyList(), layers = listOf(32))))
    }

    @Test fun aBadLayerSplitThatReachesTheLoadFailsItBeforeAnyPhoneIsTouched() {
        assertNull(c.start(StartRequest("model.gguf", listOf("Phone B"), layers = listOf(0, 32))))
        val s = awaitState("FAILED") { it.phase == Phase.FAILED }
        assertTrue(s.failReason!!.contains("at least one layer"))
        assertEquals(0, env.count("rpcStart"))
        assertEquals(0, env.count("stopLocal"))
    }

    @Test fun aGoodLayerSplitIsUsedAsGiven() {
        assertNull(c.start(StartRequest("model.gguf", listOf("Phone B", "Phone C"), layers = listOf(2, 10, 20))))
        val s = awaitState("READY") { it.phase == Phase.READY }
        assertEquals(listOf(2, 10, 20), s.shares.map { it.layers })
        assertEquals(listOf("Phone A", "Phone B", "Phone C"), s.shares.map { it.name })
    }

    // ---------- finding 10: the driver carries the loan's token ----------

    @Test fun theDriverSendsTheLoansTokenOnEveryRenewAndOnStop() {
        assertNull(start("Phone B"))
        awaitState("READY") { it.phase == Phase.READY }
        awaitCall("a renewal") { env.renewLoans["10.0.0.2:8787"] != null }
        assertEquals("loan-10.0.0.2:8787", env.renewLoans["10.0.0.2:8787"])
        c.stop()
        awaitState("IDLE") { it.phase == Phase.IDLE }
        assertEquals("loan-10.0.0.2:8787", env.stopLoans["10.0.0.2:8787"])
    }

    @Test fun aLenderOnTheOlderBuildGetsNoTokenAndNothingBreaks() {
        env.oldLenders += "10.0.0.2:8787"
        assertNull(start("Phone B"))
        awaitState("READY") { it.phase == Phase.READY }
        awaitCall("a renewal") { env.renewLoans["10.0.0.2:8787"] != null }
        assertEquals("none", env.renewLoans["10.0.0.2:8787"])
        c.stop()
        awaitState("IDLE") { it.phase == Phase.IDLE }
        assertEquals("none", env.stopLoans["10.0.0.2:8787"])
    }

    @Test fun aLenderThatSaysTheLoanIsNotOursAnymoreIsLeftAloneAndTheRecordGoes() {
        assertNull(start("Phone B"))
        awaitState("READY") { it.phase == Phase.READY }
        env.refusingStop += "10.0.0.2:8787" // another phone holds it now, or a newer loan of ours
        c.stop()
        val s = awaitState("IDLE") { it.phase == Phase.IDLE }
        assertTrue(s.notices.isEmpty() && s.pendingRestore.isEmpty())
        assertEquals("nothing is loaded on a phone that is serving someone else", 0, env.count("loadModel 10.0.0.2:8787"))
        assertEquals(Persisted(), env.saved.last())
    }

    // ---------- finding 11: a re-plan on fewer phones releases the lender it drops ----------

    @Test fun aReplanOnFewerPhonesStopsAndRestoresTheLenderItDropsBeforeLoading() {
        assertNull(start("Phone B", "Phone C"))
        awaitState("READY") { it.phase == Phase.READY }
        assertNull(c.replan(listOf("Phone B")))
        val s = awaitState("READY on two phones") { it.phase == Phase.READY && it.phones.size == 2 && env.chatStarts.get() == 2 }
        assertEquals(listOf("Phone A", "Phone B"), s.phones.map { it.name })
        assertEquals("Phone C was told to stop, once", 1, env.count("rpcStop 10.0.0.3:8787"))
        assertEquals("Phone B kept its rpc-server", 0, env.count("rpcStop 10.0.0.2:8787"))
        val dropped = env.calls.indexOf("rpcStop 10.0.0.3:8787")
        val secondLoad = env.calls.withIndex().filter { it.value.startsWith("startChat") }.map { it.index }.last()
        assertTrue("before the new load starts: $dropped < $secondLoad", dropped in 0 until secondLoad)
        // its lease is no longer renewed, and nothing of it is owed
        val renewals = env.count("rpcRenew 10.0.0.3:8787")
        runBlocking { delay(150) }
        assertEquals(renewals, env.count("rpcRenew 10.0.0.3:8787"))
        assertTrue(s.pendingRestore.none { it.startsWith("Phone C") } && s.notices.isEmpty())
    }

    @Test fun aLenderThatIsAwayAtReplanIsNotCalledAndIsReleasedWhenItAnswersAgain() {
        assertNull(start("Phone B", "Phone C"))
        awaitState("READY") { it.phase == Phase.READY }
        env.peerList = env.peerList.map { if (it.nodeId == "c") it.copy(away = true) else it }
        awaitState("DEGRADED") { it.phase == Phase.DEGRADED }
        assertNull(c.replan())
        val s = awaitState("READY on two phones") { it.phase == Phase.READY && it.phones.size == 2 }
        assertEquals("no call goes to a phone that is away", 0, env.count("rpcStop 10.0.0.3:8787"))
        assertEquals(listOf("Phone C has not got its models back yet"), s.notices)
        val renewals = env.count("rpcRenew 10.0.0.3:8787")
        runBlocking { delay(100) }
        assertEquals("and it is not renewed", renewals, env.count("rpcRenew 10.0.0.3:8787"))
        // it is back: the next tick ends its loan and confirms
        env.peerList = env.peerList.map { if (it.nodeId == "c") it.copy(away = false) else it }
        awaitState("released") { it.notices.isEmpty() }
        assertEquals(1, env.count("rpcStop 10.0.0.3:8787"))
        assertEquals(Phase.READY, c.state.value.phase)
    }

    @Test fun theActivityLineBelongsToTheDriverRoleOnly() {
        assertNull(start("Phone B"))
        awaitState("READY") { it.phase == Phase.READY }
        awaitCall("running line") { env.activity.any { it == "set ${SplitCoordinator.ACTIVITY_OWNER} Running a large model with Phone B." } }
        assertTrue("the driver never touches the lending owner", env.activity.none { it.contains(RpcLender.ACTIVITY_OWNER) })
        assertTrue(SplitCoordinator.ACTIVITY_OWNER != RpcLender.ACTIVITY_OWNER)
    }

    // ---------- a resting state must not look like work: the released DEGRADED split ----------

    @Test fun aDegradedSplitThatReleasedThePhoneSaysSoDoesNotHoldItAndPostsNoBanner() {
        degradeByLosingC()
        assertTrue("until the restore the paused split holds the phone", MemCloudLogic.holdsPhone(c.state.value))
        val s = awaitState("released") { it.released }
        assertEquals("the split stays DEGRADED so that a re-plan is still offered", Phase.DEGRADED, s.phase)
        assertFalse(MemCloudLogic.holdsPhone(s))
        assertNull(MemCloudLogic.activityText(s))
        awaitCall("the paused banner is cleared and not posted again") { lastActivityIsClear() }
        runBlocking { delay(100) }
        assertTrue(lastActivityIsClear())
    }

    @Test fun aReplanAfterTheReleaseHoldsThePhoneAgainAndStopClearsTheFlag() {
        degradeByLosingC()
        awaitState("released") { it.released }
        awaitCall("the release is over") { env.local[Role.CHAT]!!.state == ServerState.READY }
        assertNull(c.replan())
        val s = awaitState("READY again") { it.phase == Phase.READY && it.phones.size == 2 }
        assertFalse(s.released)
        assertTrue(MemCloudLogic.holdsPhone(s))
        c.stop()
        assertFalse(awaitState("IDLE") { it.phase == Phase.IDLE }.released)
    }

    @Test fun aBaselineFromAReleasedDegradedSplitDoesNotKeepTheFlag() {
        degradeByLosingC()
        awaitState("released") { it.released }
        awaitCall("the release is over") { env.local[Role.CHAT]!!.state == ServerState.READY }
        awaitCall("the baseline is accepted") { c.baseline(5) == null } // the release job is still finishing for a moment
        assertFalse("the phone is used by the baseline now", c.state.value.released)
        val s = awaitState("baseline result") { it.phase == Phase.IDLE && it.baseline?.running == false }
        assertFalse(s.released)
    }

    // ---------- the baseline always ends in IDLE with a result; Stop always reaches IDLE ----------

    @Test fun aBaselineWhoseServerCannotBeStartedEndsInIdleWithAFailedResultAndPutsThisPhoneRight() {
        env.chatMode = FakeEnv.Chat.THROW
        assertNull(c.baseline(5, "model.gguf"))
        val s = awaitState("baseline result") { it.phase == Phase.IDLE && it.baseline?.running == false }
        assertEquals("FAILED", s.baseline!!.outcome)
        assertTrue(s.baseline!!.detail.contains("exec failed"))
        awaitCall("this phone restored") { env.count("restoreLocal chat small.gguf") == 1 && env.count("restoreLocal embed embed.gguf") == 1 }
        assertNull("nothing is left marked as running", env.saved.last().baselinePending)
        assertEquals("the small chat server is serving again", ServerState.READY, env.local[Role.CHAT]!!.state)
        assertTrue(s.phones.isEmpty())
        // and a second attempt is accepted and runs
        env.chatMode = FakeEnv.Chat.READY
        awaitCall("the next attempt is accepted") { c.baseline(5, "model.gguf") == null }
        awaitState("second result") { it.phase == Phase.IDLE && it.baseline?.running == false && it.baseline?.outcome == "OK" }
    }

    @Test fun aBaselineWhoseCleanupFailsStillEndsInIdleAndSaysSo() {
        env.restoreThrows = true
        assertNull(c.baseline(5, "model.gguf"))
        val s = awaitState("IDLE") { it.phase == Phase.IDLE && it.baseline?.running == false }
        assertTrue(s.steps.any { it.contains("putting the models back failed") })
    }

    @Test fun stopReachesIdleEvenWhenGivingTheModelsBackThrows() {
        assertNull(start("Phone B"))
        awaitState("READY") { it.phase == Phase.READY }
        env.restoreThrows = true
        c.stop()
        val s = awaitState("IDLE") { it.phase == Phase.IDLE }
        assertTrue(s.steps.last().startsWith("Stopped, but putting the models back failed: restore failed"))
        awaitCall("activity cleared") { lastActivityIsClear() }
    }

    // ---------- READY never overwrites DEGRADED ----------

    @Test fun aSplitDegradedWhileTheFinalRefreshIsOutStaysDegraded() {
        val gate = CompletableDeferred<Unit>()
        env.firstStateGate.set(gate)
        assertNull(start("Phone B"))
        awaitCall("the final refresh is out") { env.stateParked.get() }
        assertEquals(Phase.LOADING, c.state.value.phase)
        env.peerList = env.peerList.map { if (it.nodeId == "b") it.copy(away = true) else it }
        awaitState("DEGRADED") { it.phase == Phase.DEGRADED }
        gate.complete(Unit) // the load job now writes its last step
        runBlocking { delay(150) }
        val s = c.state.value
        assertEquals(Phase.DEGRADED, s.phase)
        assertNull(s.loadMs)
        assertTrue(s.steps.none { it.startsWith("Ready after") })
    }

    // ---------- a lender that shows FAILED is not back ----------

    @Test fun aLenderWhoseModelsShowFailedGetsThemLoadedAgainWhenTheDriverGivesThemBack() {
        env.diesAfterStop += "10.0.0.3:8787"
        env.diedState = "FAILED" // its load was refused while rpc-server still held memory
        assertNull(start("Phone B", "Phone C"))
        awaitState("FAILED") { it.phase == Phase.FAILED }
        awaitCall("C loaded back") { env.count("loadModel 10.0.0.3:8787 chat small.gguf") == 1 && env.count("loadModel 10.0.0.3:8787 embed embed.gguf") == 1 }
    }

    @Test fun recoveryLoadsBackAServerThatShowsFailedOnThePeerAndOnThisPhone() {
        env.persisted = Persisted(
            restore = mapOf(
                "self" to listOf(RestoreItem("chat", "small.gguf")),
                "b" to listOf(RestoreItem("chat", "small.gguf")),
            ),
            peerAddrs = mapOf("b" to "10.0.0.2:8787"),
        )
        env.local[Role.CHAT] = LocalServer(ServerState.FAILED, "small.gguf", null)
        env.peerRoles["10.0.0.2:8787"] = mapOf("chat" to RoleSnap("FAILED", "small.gguf", null), "embed" to RoleSnap("READY", "embed.gguf", 600))
        runBlocking { c.onAppStart() }
        assertEquals(1, env.count("loadModel 10.0.0.2:8787 chat small.gguf"))
        assertEquals(1, env.count("restoreLocal chat small.gguf"))
    }

    // ---------- a second baseline while the first releases the lenders is refused ----------

    @Test fun aSecondBaselineWhileTheFirstIsStillReleasingTheLendersIsRefused() {
        assertNull(start("Phone B"))
        awaitState("READY") { it.phase == Phase.READY }
        val gate = CompletableDeferred<Unit>()
        env.stopGate = gate // releasing Phone B takes a while
        awaitCall("the baseline is accepted") { c.baseline(5) == null } // the load job may take a moment to finish after READY
        awaitCall("the release is out") { env.stopParked.isNotEmpty() }
        val second = c.baseline(5)
        assertTrue("$second", second != null && second.startsWith("busy: BASELINE"))
        gate.complete(Unit)
        awaitState("baseline result") { it.phase == Phase.IDLE && it.baseline?.running == false }
        assertEquals("only one single-phone server was started", 1, env.calls.count { it.startsWith("startChat -ngl 0") })
        assertEquals(1, env.count("rpcStop 10.0.0.2:8787"))
        awaitCall("the next one is accepted") { c.baseline(5) == null }
        awaitCall("and it runs") { env.calls.count { it.startsWith("startChat -ngl 0") } == 2 }
    }

    @Test fun aBaselineIsRefusedWhileTheCleanupOfAFailedSplitIsStillRunning() {
        val gate = CompletableDeferred<Unit>()
        env.stopGate = gate // the lender is slow to answer /rpc/stop, so the clean-up after the failure is still running
        env.chatMode = FakeEnv.Chat.FAIL
        assertNull(start("Phone B"))
        awaitState("FAILED") { it.phase == Phase.FAILED }
        awaitCall("the clean-up is out") { env.stopParked.isNotEmpty() }
        val refused = c.baseline(5)
        assertTrue("$refused", refused != null && refused.startsWith("busy: FAILED"))
        assertEquals("nothing of the baseline started", 0, env.calls.count { it.startsWith("startChat -ngl 0") })
        gate.complete(Unit)
        awaitCall("accepted once the clean-up is over") { c.baseline(5) == null }
    }

    @Test fun aBaselineAStartAndAReplanAreRefusedWhileTheCleanupAfterADeadChatServerIsRunning() {
        assertNull(start("Phone B"))
        awaitState("READY") { it.phase == Phase.READY }
        val gate = CompletableDeferred<Unit>()
        env.stopGate = gate // the lender is slow to answer /rpc/stop, so the clean-up the watch started is still running
        env.local[Role.CHAT] = LocalServer(ServerState.STOPPED, null, null) // the split's llama-server died; every lender still answers
        awaitState("FAILED") { it.phase == Phase.FAILED }
        awaitCall("the clean-up is out") { env.stopParked.isNotEmpty() }
        val baseline = c.baseline(5)
        assertTrue("$baseline", baseline != null && baseline.startsWith("busy: FAILED"))
        val started = start("Phone B")
        assertTrue("$started", started != null && started.startsWith("busy"))
        val replanned = c.replan()
        assertTrue("$replanned", replanned != null && replanned.startsWith("busy"))
        assertEquals("nothing of the baseline started", 0, env.calls.count { it.startsWith("startChat -ngl 0") })
        gate.complete(Unit)
        awaitCall("accepted once the clean-up is over") { c.baseline(5) == null }
    }

    @Test fun aBaselineIsRefusedWhileTheDelayedReleaseOfADegradedSplitIsStillRunning() {
        degradeByLosingC()
        val gate = CompletableDeferred<Unit>()
        env.stopGate = gate // the automatic release starts after degradedRestoreMs and waits for the lender
        awaitCall("the release is out") { env.stopParked.isNotEmpty() }
        val refused = c.baseline(5)
        assertTrue("$refused", refused != null && refused.startsWith("busy: DEGRADED"))
        gate.complete(Unit)
        awaitCall("accepted once the release is over") { c.baseline(5) == null }
    }

    // ---------- a stop that could not be confirmed carries the loan's token, so a late retry cannot end a newer loan ----------

    @Test fun aRetryThatIsOutWhileTheSamePhoneIsLentAgainDoesNotEndTheNewLoan() {
        val c3 = "10.0.0.3:8787"
        assertNull(start("Phone B", "Phone C"))
        awaitState("READY") { it.phase == Phase.READY }
        val first = env.currentLoan.getValue(c3)
        env.unreachable += c3
        c.stop()
        awaitState("IDLE with a notice") { it.phase == Phase.IDLE && it.notices.isNotEmpty() }
        assertEquals("the record keeps the loan's token", first, env.saved.last().peerLoans["c"])
        // the lender's lease ran out while the phone was away; the phone answers again, and the retry's stop is out on the network
        env.currentLoan.remove(c3)
        val gate = CompletableDeferred<Unit>()
        env.stopGateAddr = c3
        env.stopGate = gate
        env.unreachable -= c3
        awaitCall("the retry's stop is out") { c3 in env.stopParked }
        assertEquals("the retry names the loan it is about", "$c3 $first", env.stopsSent.last { it.startsWith(c3) })
        // meanwhile this driver lends from the same phone again
        assertNull(start("Phone B", "Phone C"))
        awaitState("READY again") { it.phase == Phase.READY }
        val second = env.currentLoan.getValue(c3)
        assertTrue("a new loan, a new token", second != first)
        env.stopGate = null
        gate.complete(Unit) // the old stop reaches the lender now
        runBlocking { delay(200) }
        assertEquals("the new loan was not ended", second, env.currentLoan[c3])
        assertEquals(Phase.READY, c.state.value.phase)
        assertEquals("the record of the new split is not settled by the old retry", 0, env.count("rpcStop $c3"))
        c.stop()
        awaitState("IDLE") { it.phase == Phase.IDLE && it.notices.isEmpty() }
        assertEquals(second, env.stopLoans[c3])
        assertEquals(1, env.count("rpcStop $c3"))
    }

    // ---------- the retry that gives a peer its models back ----------

    private fun readyThenLoseC(): String {
        val c3 = "10.0.0.3:8787"
        assertNull(start("Phone B", "Phone C"))
        awaitState("READY") { it.phase == Phase.READY }
        env.unreachable += c3
        c.stop()
        awaitState("IDLE with a notice") { it.phase == Phase.IDLE && it.notices.isNotEmpty() }
        return c3
    }

    @Test fun aPeerWhoseFirstItemAnswers404StillGetsItsSecondItemAndIsSettled() {
        val c3 = readyThenLoseC()
        env.loadStatus["$c3 chat"] = 404 // the chat file is gone on that phone
        env.unreachable -= c3
        val s = awaitState("settled") { it.notices.isEmpty() && it.pendingRestore.isEmpty() }
        assertEquals("the second item was loaded", 1, env.count("loadModel $c3 embed embed.gguf"))
        assertEquals(0, env.count("loadModel $c3 chat"))
        assertEquals("said once", 1, s.steps.count { it.contains("chat model small.gguf cannot be loaded there (HTTP 404)") })
        assertEquals(Persisted(), env.saved.last())
    }

    @Test fun anItemRefusedForGoodIsSaidOnceAndTheOtherItemIsRetriedUntilItWorks() {
        val c3 = readyThenLoseC()
        env.loadStatus["$c3 chat"] = 400
        env.loadIo += "$c3 embed" // the network drops that one for a while
        env.unreachable -= c3
        awaitCall("a few rounds") { env.count("rpcStop $c3") >= 3 }
        assertEquals("not settled while one item keeps failing", listOf("Phone C has not got its models back yet"), c.state.value.notices)
        env.loadIo -= "$c3 embed"
        val s = awaitState("settled") { it.notices.isEmpty() && it.pendingRestore.isEmpty() }
        assertEquals(1, env.count("loadModel $c3 embed embed.gguf"))
        assertEquals(1, s.steps.count { it.contains("cannot be loaded there (HTTP 400)") })
    }

    @Test fun anyOtherFailureKeepsRetryingWithWaitsThatDoubleFiveToSixtySeconds() {
        val waits = java.util.concurrent.CopyOnWriteArrayList<Long>()
        c = SplitCoordinator(env, scope, timing.copy(restoreRetryMs = 5_000, restoreRetryMaxMs = 60_000, restoreWait = { waits += it; delay(2) }))
        readyThenLoseC()
        awaitCall("six waits") { waits.size >= 6 }
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L), waits.take(6))
        assertEquals("the record of the phone that is not answering stays", listOf("Phone C has not got its models back yet"), c.state.value.notices)
    }

    @Test fun aRestoreRetryParkedInItsCheckWhileThePhoneIsLentAgainDoesNotEndTheNewLoan() {
        val c3 = "10.0.0.3:8787"
        val armNext = java.util.concurrent.atomic.AtomicBoolean(false)
        val parked = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        // the first look at the registry after a wait is the retry's own check: park it there
        c = SplitCoordinator(env, scope, timing.copy(restoreWait = { delay(5); if (armNext.compareAndSet(true, false)) env.peersHook = { parked.countDown(); release.await() } }))
        readyThenLoseC()
        armNext.set(true)
        assertTrue("the retry is parked inside its check", parked.await(5, java.util.concurrent.TimeUnit.SECONDS))
        // meanwhile the lease of the old loan ran out, the phone answers, and this driver lends from it again
        env.currentLoan.remove(c3)
        env.unreachable -= c3
        assertNull(start("Phone B", "Phone C"))
        awaitState("READY again") { it.phase == Phase.READY }
        val second = env.currentLoan.getValue(c3)
        release.countDown() // the old retry goes on with the obligation it had
        runBlocking { delay(300) }
        assertEquals("the new loan was not ended by the old retry", second, env.currentLoan[c3])
        assertEquals(0, env.count("rpcStop $c3"))
        assertEquals(Phase.READY, c.state.value.phase)
    }

    // ---------- memory is shared over the local link only ----------

    private val offLinkC = PeerInfo("c", "Phone C", "172.20.1.9:8787", false, 9000, 1) // online, but not on this phone's network

    @Test fun aPhoneThatIsNotOnThisPhonesLocalNetworkIsLeftOutOfThePlanAndTheReasonIsShown() {
        env.peerList = listOf(env.peerList.first { it.nodeId == "b" }, offLinkC)
        assertEquals("the preview leaves it out too", 2, c.previewPlan("model.gguf", emptyList(), SplitMode.EQUAL).shares.size)
        assertNull(start("Phone B", "Phone C"))
        val s = awaitState("READY") { it.phase == Phase.READY }
        assertEquals(listOf("Phone A", "Phone B"), s.phones.map { it.name })
        assertTrue(s.warnings.single().let { it.contains("Phone C (172.20.1.9)") && it.contains("local link only") })
        assertEquals("nothing was sent to it", 0, env.count("rpcStart 172.20.1.9"))
    }

    @Test fun whenNoPhoneIsOnTheLocalNetworkTheLoadFailsAndSaysWhy() {
        env.peerList = listOf(offLinkC)
        assertNull(start("Phone C"))
        val s = awaitState("FAILED") { it.phase == Phase.FAILED }
        assertTrue(s.failReason!!, s.failReason!!.startsWith("No other phone is online on this phone's local network") && s.failReason!!.contains("Phone C (172.20.1.9)"))
        assertEquals(0, env.count("stopLocal"))
    }

    @Test fun aRestartedAppSendsTheTokenItSavedAndKeepsItWhenThePhoneIsStillAway() {
        env.persisted = Persisted(
            restore = mapOf("b" to listOf(RestoreItem("chat", "small.gguf"))),
            peerAddrs = mapOf("b" to "10.0.0.2:8787"),
            peerLoans = mapOf("b" to "saved-token"),
        )
        env.unreachable += "10.0.0.2:8787"
        runBlocking { c.onAppStart() }
        assertEquals("the record keeps its token", mapOf("b" to "saved-token"), env.saved.last().peerLoans)
        env.unreachable -= "10.0.0.2:8787"
        awaitState("record cleared") { it.notices.isEmpty() && it.pendingRestore.isEmpty() }
        assertEquals("b's stop named the saved loan", "10.0.0.2:8787 saved-token", env.stopsSent.last())
        assertEquals(Persisted(), env.saved.last())
    }
}
