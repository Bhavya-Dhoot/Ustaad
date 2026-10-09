package com.swarmos.app.llm

import android.content.Context
import android.os.Process as AndroidProcess
import android.util.Log
import com.swarmos.app.AppGraph
import com.swarmos.app.sched.Sched
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

enum class ServerState { STOPPED, STARTING, READY, FAILED }

data class ServerStatus(
    val role: Role,
    val state: ServerState = ServerState.STOPPED,
    /** Why the server is FAILED (exit code / signal / last log line), or a note while STOPPED after a failure. */
    val reason: String? = null,
    val modelPath: String? = null,
    val port: Int = role.defaultPort,
    val pid: Long? = null,
    val args: List<String> = emptyList(),
    val startedAtMs: Long = 0L,
    /** Milliseconds from launch to READY (load time). */
    val readyAfterMs: Long? = null,
    /** Banner text for the UI: the server was killed and is being restarted, or was restarted, or will not be. Null normally. */
    val notice: String? = null,
    /** Automatic restarts used in the current failure episode (0 or 1). */
    val restarts: Int = 0,
) {
    /** File name without extension, e.g. "Qwen3.5-4B-Q4_K_M"; null for RPC or when stopped. */
    val modelName: String? get() = modelPath?.let { File(it).name.removeSuffix(".gguf") }
}

/**
 * Launches and supervises the shipped llama.cpp binaries (libllama-server.so, librpc-server.so) as child processes
 * of the app, from nativeLibraryDir (the only place an app may exec from). One process per [Role].
 *
 * Public API for other modules (MemoryCloud, Choir, Data Union):
 *  - [start] / [stop] / [stopAll] / [status] / [statusOf] / [logTail] / [rssKb] / [lastStats]
 *  - AppGraph.llm / AppGraph.embedder switch to real [LlamaServerClient]s while CHAT / EMBED are READY, and back to FakeLlmClient otherwise.
 * Everything runs only while SwarmService is alive: it calls [onServiceStarted] and [onServiceStopped].
 */
object LlamaRuntime {
    private const val TAG = "LlamaRuntime"
    private const val READY_TIMEOUT_MS = 300_000L
    private const val LOG_FILE_CAP = 8L * 1024 * 1024

    /** How long to wait for a killed orphan to vanish from /proc before its port is checked. */
    private const val ORPHAN_WAIT_MS = 5_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val locks = Role.entries.associateWith { Mutex() }

    /**
     * Bumped by [stop]. A start remembers the value it was requested under and gives up when it changed, so a Stop that
     * arrives while a start is queued, killing orphans or loading can never be followed by a server nobody asked for.
     */
    private val epochs = Role.entries.associateWith { AtomicInteger(0) }

    /** Held while a child is being launched and published in [running], and while orphans are scanned (an orphan scan must not kill a child that is about to be published). */
    private val procLock = Any()

    private class StartConfig(val modelPath: String?, val extraArgs: List<String>, val port: Int, val env: Map<String, String>, val readyTimeoutMs: Long = READY_TIMEOUT_MS)

    private class Running(
        val role: Role,
        val process: Process,
        val ring: LogRing,
        val startedNs: Long,
        val cfg: StartConfig,
    ) {
        @Volatile var stopRequested = false
        @Volatile var wasReady = false
    }

    private val running = java.util.concurrent.ConcurrentHashMap<Role, Running>()

    private val _status = MutableStateFlow(Role.entries.associateWith { ServerStatus(it) })

    /** State of every role. Collect it from the UI. */
    val status: StateFlow<Map<Role, ServerStatus>> = _status.asStateFlow()

    /** Stats of the most recent generation on this phone (chat or bench). */
    val lastStats = MutableStateFlow<ChatStats?>(null)

    /** Wall time per text of the most recent embed() call. */
    val lastEmbedMsPerText = MutableStateFlow<Double?>(null)

    @Volatile private var serviceUp = false
    private var serviceJobs: List<Job> = emptyList()

    fun statusOf(role: Role): ServerStatus = _status.value.getValue(role)

    private fun setStatus(role: Role, f: (ServerStatus) -> ServerStatus) {
        _status.update { it + (role to f(it.getValue(role))) }
    }

    // ---- lifecycle tied to SwarmService ----

    /** Called by SwarmService after it entered the foreground. Wires AppGraph and loads the default models. */
    @Synchronized
    fun onServiceStarted() {
        if (serviceUp) return
        serviceUp = true
        val wire = scope.launch {
            _status.collect { st ->
                val chat = st.getValue(Role.CHAT)
                syncClient(Role.CHAT, chat, AppGraph.llm) { AppGraph.llm = it }
                syncClient(Role.EMBED, st.getValue(Role.EMBED), AppGraph.embedder) { AppGraph.embedder = it }
                // the scheduler accepts as many parallel chat jobs as the chat server has slots, none while there is no server
                val cap = if (chat.state == ServerState.READY) LlamaArgs.slots(chat.args) else 0
                if (cap != schedCapacity) {
                    schedCapacity = cap
                    Sched.setCapacity(cap)
                }
            }
        }
        val load = scope.launch {
            awaitGone(killOrphans(), ORPHAN_WAIT_MS)
            if (ModelStore.autoload.value) loadDefaults() else Log.i(TAG, "autoload is off: no model is loaded at start")
        }
        serviceJobs = listOf(wire, load)
    }

    @Volatile private var schedCapacity = -1

    /**
     * Starts the catalog default chat and embedding models (Qwen3.5-4B, EmbeddingGemma-300M) when they are present.
     * Returns one handle per role that was started; roles whose file is missing are skipped.
     */
    fun loadDefaults(): List<Deferred<ServerStatus>> =
        listOf(Role.CHAT, Role.EMBED).mapNotNull { role ->
            ModelStore.autoLoadFile(role)?.let { startAsync(role, it.absolutePath) }
        }

    /** Called by SwarmService.onDestroy: kill every child, restore the fake clients. */
    @Synchronized
    fun onServiceStopped() {
        serviceUp = false
        serviceJobs.forEach { it.cancel() }
        serviceJobs = emptyList()
        stopAll()
        AppGraph.llm = com.swarmos.app.llm.FakeLlmClient()
        AppGraph.embedder = com.swarmos.app.llm.FakeLlmClient()
        schedCapacity = 0
        Sched.setCapacity(0)
    }

    private fun syncClient(role: Role, st: ServerStatus, current: LlmClient, set: (LlmClient) -> Unit) {
        if (st.state == ServerState.READY) {
            val name = st.modelName ?: return
            val already = current is LlamaServerClient && current.modelName == name && current.port == st.port
            if (!already) set(LlamaServerClient(role, name, st.port))
        } else if (current is LlamaServerClient) {
            set(FakeLlmClient())
        }
    }

    // ---- start / stop ----

    /**
     * Starts [role] and suspends until it is READY or FAILED; returns the final status.
     * Restarts it when already running. [extraArgs] are appended after the defaults of [LlamaArgs] (the last flag wins),
     * [env] is added to the child's environment (LD_LIBRARY_PATH and LLAMA_CACHE are preset).
     * RPC takes no model. Must run while SwarmService is alive. CHAT and EMBED get one throwaway request before READY
     * (the first request after load pays a page-in penalty). A start that is superseded by a newer start of the same role,
     * or by [stop], gives up and returns the status at that moment (not READY).
     */
    suspend fun start(
        role: Role,
        modelPath: String?,
        extraArgs: List<String> = emptyList(),
        port: Int = role.defaultPort,
        env: Map<String, String> = emptyMap(),
        readyTimeoutMs: Long = READY_TIMEOUT_MS,
    ): ServerStatus = startAsync(role, modelPath, extraArgs, port, env, readyTimeoutMs).await()

    /** Same as [start] but returns at once; cancelling the caller never leaves a half-started server behind. */
    fun startAsync(
        role: Role,
        modelPath: String?,
        extraArgs: List<String> = emptyList(),
        port: Int = role.defaultPort,
        env: Map<String, String> = emptyMap(),
        readyTimeoutMs: Long = READY_TIMEOUT_MS,
    ): Deferred<ServerStatus> {
        // A newer start or a stop() after this point cancels this start; a pending automatic restart is superseded too.
        val epoch = epochs.getValue(role).incrementAndGet()
        val cfg = StartConfig(modelPath, extraArgs, port, env, readyTimeoutMs)
        return scope.async { startInternal(role, cfg, epoch, auto = false) }
    }

    private suspend fun startInternal(role: Role, cfg: StartConfig, epoch: Int, auto: Boolean): ServerStatus = locks.getValue(role).withLock {
        val modelPath = cfg.modelPath
        val port = cfg.port
        if (epochs.getValue(role).get() != epoch) return@withLock statusOf(role) // stopped before this start began
        val prior = statusOf(role)
        stopProcess(role) // waits until the old process is gone, so its port is free
        val ctx = AppGraph.appContext
        if (!serviceUp) return@withLock fail(role, "SwarmService is not running", modelPath, port)
        val bin = File(ctx.applicationInfo.nativeLibraryDir, role.binary)
        if (!bin.exists()) return@withLock fail(role, "${role.binary} is not in the APK (nativeLibraryDir ${bin.parent})", modelPath, port)
        if (role != Role.RPC && (modelPath == null || !File(modelPath).isFile)) {
            return@withLock fail(role, "model file not found: $modelPath", modelPath, port)
        }
        if (!ensurePortFree(role, port)) {
            return@withLock fail(role, "port $port is still in use after two retries (another app or an adb shell process holds it)", modelPath, port)
        }
        if (epochs.getValue(role).get() != epoch) return@withLock statusOf(role)
        val args = LlamaArgs.build(role, modelPath, port, cfg.extraArgs)
        val cmd = listOf(bin.absolutePath) + args
        val logFile = File(File(ctx.filesDir, "logs").apply { mkdirs() }, "${role.tag}.log")
        val ring = LogRing()
        val restarts = if (auto) prior.restarts else 0
        val startingNotice = if (auto) prior.notice else null
        setStatus(role) {
            ServerStatus(role, ServerState.STARTING, null, modelPath, port, null, args, System.currentTimeMillis(), null, startingNotice, restarts)
        }
        val run: Running
        synchronized(procLock) {
            val proc = try {
                ProcessBuilder(cmd)
                    .directory(ctx.filesDir)
                    .redirectErrorStream(true)
                    .apply {
                        environment()["LD_LIBRARY_PATH"] = ctx.applicationInfo.nativeLibraryDir
                        environment()["LLAMA_CACHE"] = File(ctx.filesDir, "llama-cache").apply { mkdirs() }.path // rpc-server -c needs it (no $HOME on Android)
                        environment().putAll(cfg.env)
                    }
                    .start()
            } catch (e: Exception) {
                return@withLock fail(role, "cannot exec ${bin.name}: ${e.message}", modelPath, port)
            }
            run = Running(role, proc, ring, System.nanoTime(), cfg)
            running[role] = run
        }
        val proc = run.process
        // stop() may have run between the epoch check above and the publish just now: it found nothing to kill, so do it here
        if (epochs.getValue(role).get() != epoch) {
            run.stopRequested = true
            running.remove(role, run)
            killProcess(proc)
            return@withLock statusOf(role)
        }
        val pid = pidOf(proc)
        setStatus(role) { it.copy(pid = pid) }
        Log.i(TAG, "${role.tag}: started pid=$pid ${cmd.joinToString(" ")}")
        pump(run, logFile)
        watchExit(run)

        // wait for readiness
        val deadline = System.nanoTime() + cfg.readyTimeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (!proc.isAlive) break
            if (epochs.getValue(role).get() != epoch) { // stopped, or superseded by a newer start: stop loading at once
                run.stopRequested = true
                running.remove(role, run)
                killProcess(proc)
                return@withLock statusOf(role)
            }
            if (probe(role, port)) {
                // A health answer only counts if OUR process is still alive a moment later (guards against a foreign server on the port).
                delay(400)
                if (!proc.isAlive || !probe(role, port)) continue
                warmUp(role, port)
                if (run.stopRequested || epochs.getValue(role).get() != epoch) return@withLock statusOf(role)
                val ms = (System.nanoTime() - run.startedNs) / 1_000_000
                run.wasReady = true
                val doneNotice = if (auto) "Restarted automatically after the server was killed." else null
                setStatus(role) { it.copy(state = ServerState.READY, readyAfterMs = ms, notice = doneNotice) }
                Log.i(TAG, "${role.tag}: READY after $ms ms")
                return@withLock statusOf(role)
            }
            delay(300)
        }
        if (proc.isAlive) {
            run.stopRequested = true
            killProcess(proc)
            return@withLock fail(role, "not ready after ${cfg.readyTimeoutMs / 1000} s; ${ring.tail(1).lastOrNull().orEmpty()}", modelPath, port)
        }
        if (run.stopRequested) return@withLock statusOf(role)
        val code = runCatching { proc.exitValue() }.getOrDefault(-1)
        fail(role, "${ProcInfo.describeExit(code)} during start; last log: ${ring.tail(3).joinToString(" | ")}", modelPath, port)
    }

    /**
     * Stops [role] (SIGTERM, then SIGKILL after 3 s). Safe to call when it is not running, and safe while a start for this
     * role is queued, checking ports or loading: that start is cancelled (see [epochs]) instead of racing with this stop.
     * It also ends the automatic restart of a server that was killed.
     */
    fun stop(role: Role) {
        epochs.getValue(role).incrementAndGet()
        val run = running.remove(role)
        if (run != null) {
            run.stopRequested = true
            killProcess(run.process)
        }
        setStatus(role) { ServerStatus(role, port = it.port) }
    }

    /** Stops every role. Non-blocking for the caller beyond the SIGTERM; a watchdog escalates to SIGKILL. */
    fun stopAll() {
        for (r in Role.entries) stop(r)
    }

    /** Called by a start that already holds the role lock: kills the old process of [role] and waits until it is really gone. */
    private fun stopProcess(role: Role) {
        val run = running.remove(role) ?: return
        run.stopRequested = true
        killProcess(run.process)
        setStatus(role) { ServerStatus(role, port = it.port) }
        if (!waitFor(run.process, 4_000)) {
            run.process.destroyForcibly()
            waitFor(run.process, 2_000)
        }
    }

    /**
     * True when [port] can be bound on this phone. Loopback is shared by every app and every adb shell process, so a
     * foreign server on the same port would otherwise make our /health probe succeed against the wrong process.
     */
    private fun portFree(role: Role, port: Int): Boolean = try {
        java.net.ServerSocket().use { s ->
            s.reuseAddress = true
            s.bind(if (role == Role.RPC) InetSocketAddress(port) else InetSocketAddress("127.0.0.1", port))
        }
        true
    } catch (_: Exception) {
        false
    }

    private fun waitForPort(role: Role, port: Int, ms: Long): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (portFree(role, port)) return true
            Thread.sleep(150)
        }
        return portFree(role, port)
    }

    /**
     * Frees [port] for a new server: the first try, then two retries, each after killing this app's stale servers (left
     * by an earlier app process) and waiting until they have really vanished from /proc (a SIGKILL is asynchronous).
     */
    private suspend fun ensurePortFree(role: Role, port: Int): Boolean {
        if (waitForPort(role, port, 1_500)) return true
        repeat(2) {
            awaitGone(killOrphans(), ORPHAN_WAIT_MS)
            if (waitForPort(role, port, 3_000)) return true
        }
        return false
    }

    private fun fail(role: Role, reason: String, modelPath: String?, port: Int): ServerStatus {
        Log.e(TAG, "${role.tag}: FAILED: $reason")
        setStatus(role) {
            val note = if (it.restarts > 0) "The automatic restart failed. Tap Load to start it again." else null
            ServerStatus(role, ServerState.FAILED, reason, modelPath, port, null, it.args, it.startedAtMs, null, note, it.restarts)
        }
        return statusOf(role)
    }

    private fun killProcess(p: Process) {
        p.destroy() // SIGTERM
        scope.launch {
            if (!waitFor(p, 3_000)) {
                p.destroyForcibly() // SIGKILL
            }
        }
    }

    private fun waitFor(p: Process, ms: Long): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (!p.isAlive) return true
            Thread.sleep(50)
        }
        return !p.isAlive
    }

    /** Copies the child's stdout+stderr to the ring buffer and to filesDir/logs/<role>.log (truncated at each start, capped). */
    private fun pump(run: Running, logFile: File) {
        Thread({
            try {
                logFile.bufferedWriter().use { w ->
                    var written = 0L
                    run.process.inputStream.bufferedReader().forEachLine { line ->
                        run.ring.add(line)
                        if (written < LOG_FILE_CAP) {
                            w.write(line); w.newLine(); w.flush()
                            written += line.length + 1
                        }
                    }
                }
            } catch (_: Exception) {
                // stream closed when the process died
            }
        }, "llama-log-${run.role.tag}").apply { isDaemon = true }.start()
    }

    /**
     * Reports unexpected exits (this is the evidence for the phantom-process / OriginOS question) and, when
     * [RestartPolicy] allows, restarts the server once after a backoff with a banner ([ServerStatus.notice]).
     */
    private fun watchExit(run: Running) {
        scope.launch {
            val code = run.process.waitFor()
            val aliveMs = (System.nanoTime() - run.startedNs) / 1_000_000
            if (run.stopRequested) return@launch
            val last = run.ring.tail(3).joinToString(" | ")
            val what = ProcInfo.describeExit(code)
            val reason = "$what after ${aliveMs / 1000} s; last log: $last"
            Log.e(TAG, "${run.role.tag}: DIED pid=${pidOf(run.process)} $reason")
            if (running[run.role] === run) running.remove(run.role, run)
            val role = run.role
            val cfg = run.cfg
            setStatus(role) {
                // a server that never got ready was reported by its start; keep that note, otherwise the decision below sets one
                ServerStatus(role, ServerState.FAILED, reason, cfg.modelPath, cfg.port, null, it.args, it.startedAtMs, null, if (run.wasReady) null else it.notice, it.restarts)
            }
            val epoch = epochs.getValue(role).get()
            when (val d = RestartPolicy.decide(role, run.wasReady, serviceUp, statusOf(role).restarts, aliveMs, cfg.extraArgs)) {
                RestartPolicy.Decision.Ignore -> {}
                RestartPolicy.Decision.GiveUp -> setStatus(role) {
                    it.copy(notice = "The server was killed again ($what), so it is not restarted. Tap Load to start it.")
                }
                is RestartPolicy.Decision.Restart -> {
                    setStatus(role) {
                        it.copy(
                            state = ServerState.STARTING,
                            notice = "The server was killed ($what). Restarting in ${d.delayMs / 1000} s.",
                            restarts = d.attempt,
                        )
                    }
                    delay(d.delayMs)
                    if (serviceUp && epochs.getValue(role).get() == epoch) startInternal(role, cfg, epoch, auto = true)
                }
            }
        }
    }

    /** One 1-token request so the weights are paged in before the server is reported READY. Failures are ignored. */
    private fun warmUp(role: Role, port: Int) {
        val (path, body) = when (role) {
            Role.CHAT -> "/v1/chat/completions" to
                """{"messages":[{"role":"user","content":"Hi"}],"max_tokens":1,"temperature":0,"chat_template_kwargs":{"enable_thinking":false}}"""
            Role.EMBED -> "/v1/embeddings" to """{"input":["warm up"]}"""
            Role.RPC -> return
        }
        try {
            val c = LlamaHttp.post("http://127.0.0.1:$port$path", body, 120_000)
            try { LlamaHttp.readBody(c) } finally { c.disconnect() }
        } catch (_: Exception) {
        }
    }

    private fun probe(role: Role, port: Int): Boolean =
        if (role == Role.RPC) {
            // rpc-server may listen on the link address only (-H <ip>), where 127.0.0.1 is refused: probe where it was told to bind
            val a = statusOf(role).args
            val host = a.lastIndexOf("-H").takeIf { it >= 0 }?.let { a.getOrNull(it + 1) }?.takeIf { it != "0.0.0.0" } ?: "127.0.0.1"
            try {
                Socket().use { it.connect(InetSocketAddress(host, port), 500); true }
            } catch (_: Exception) {
                false
            }
        } else {
            LlamaHttp.healthOk(port)
        }

    // ---- introspection ----

    fun logTail(role: Role, n: Int = 40): List<String> = running[role]?.ring?.tail(n) ?: readLogFile(role, n)

    private fun readLogFile(role: Role, n: Int): List<String> = try {
        val f = File(File(AppGraph.appContext.filesDir, "logs"), "${role.tag}.log")
        if (f.isFile) f.readLines().takeLast(n) else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    /** Resident set size (kB) of the child process from /proc/<pid>/status, or null if it is not running. */
    fun rssKb(role: Role): Long? = statusKb(role, "VmRSS")

    /** Peak resident set size (kB) of the child (VmHWM). */
    fun peakRssKb(role: Role): Long? = statusKb(role, "VmHWM")

    private fun statusKb(role: Role, key: String): Long? {
        val pid = statusOf(role).pid ?: return null
        return try {
            ProcInfo.statusKb(File("/proc/$pid/status").readText(), key)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Kills llama-server / rpc-server processes left behind by an earlier app process (same uid), so ports are free.
     * Returns the pids it signalled: a SIGKILL is asynchronous, callers [awaitGone] before they bind a port.
     */
    private fun killOrphans(): List<Int> = synchronized(procLock) {
        val mine = AndroidProcess.myPid()
        val dirs = File("/proc").listFiles { f -> f.name.all(Char::isDigit) } ?: return emptyList()
        val killed = ArrayList<Int>()
        for (d in dirs) {
            val pid = d.name.toInt()
            if (pid == mine) continue
            val cmd = try { File(d, "cmdline").readText() } catch (_: Exception) { continue }
            val exe = cmd.substringBefore('\u0000')
            if (exe.endsWith("libllama-server.so") || exe.endsWith("librpc-server.so")) {
                if (running.values.none { pidOf(it.process) == pid.toLong() }) {
                    Log.w(TAG, "killing orphan $exe pid=$pid")
                    AndroidProcess.killProcess(pid)
                    killed += pid
                }
            }
        }
        killed
    }

    /** Waits until every pid in [pids] has left /proc (or is a zombie awaiting its parent), at most [timeoutMs]. */
    private suspend fun awaitGone(pids: List<Int>, timeoutMs: Long) {
        if (pids.isEmpty()) return
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end && pids.any { !isGone(it) }) delay(100)
        pids.filter { !isGone(it) }.takeIf { it.isNotEmpty() }?.let { Log.w(TAG, "orphans still present after $timeoutMs ms: $it") }
    }

    private fun isGone(pid: Int): Boolean = try {
        !File("/proc/$pid").exists() || ProcInfo.isZombie(File("/proc/$pid/status").readText())
    } catch (_: Exception) {
        true
    }

    /** The android.jar stubs do not expose Process.pid() on every API level, so ask the implementation class. */
    private fun pidOf(p: Process): Long? = try {
        val m = p.javaClass.getMethod("pid")
        (m.invoke(p) as Number).toLong()
    } catch (_: Exception) {
        try {
            val f = p.javaClass.getDeclaredField("pid").apply { isAccessible = true }
            (f.get(p) as Number).toLong()
        } catch (_: Exception) {
            null
        }
    }

    /** Where the binaries live; handy for adb debugging. */
    fun nativeDir(ctx: Context): String = ctx.applicationInfo.nativeLibraryDir
}
