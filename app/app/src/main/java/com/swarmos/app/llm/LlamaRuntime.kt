package com.swarmos.app.llm

import android.content.Context
import android.os.Process as AndroidProcess
import android.util.Log
import com.swarmos.app.AppGraph
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val locks = Role.entries.associateWith { Mutex() }

    private class Running(
        val role: Role,
        val process: Process,
        val ring: LogRing,
        val startedNs: Long,
    ) {
        @Volatile var stopRequested = false
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
                syncClient(Role.CHAT, st.getValue(Role.CHAT), AppGraph.llm) { AppGraph.llm = it }
                syncClient(Role.EMBED, st.getValue(Role.EMBED), AppGraph.embedder) { AppGraph.embedder = it }
            }
        }
        val load = scope.launch {
            killOrphans()
            for (role in listOf(Role.CHAT, Role.EMBED)) {
                val file = ModelStore.autoLoadFile(role) ?: continue
                launch { start(role, file.absolutePath) }
            }
        }
        serviceJobs = listOf(wire, load)
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
     * Restarts it when already running. [extraArgs] are appended after the defaults of [LlamaArgs].
     * RPC takes no model. Must run while SwarmService is alive.
     */
    suspend fun start(
        role: Role,
        modelPath: String?,
        extraArgs: List<String> = emptyList(),
        port: Int = role.defaultPort,
    ): ServerStatus = startAsync(role, modelPath, extraArgs, port).await()

    /** Same as [start] but returns at once; cancelling the caller never leaves a half-started server behind. */
    fun startAsync(
        role: Role,
        modelPath: String?,
        extraArgs: List<String> = emptyList(),
        port: Int = role.defaultPort,
    ): Deferred<ServerStatus> = scope.async { startInternal(role, modelPath, extraArgs, port) }

    private suspend fun startInternal(
        role: Role,
        modelPath: String?,
        extraArgs: List<String>,
        port: Int,
    ): ServerStatus = locks.getValue(role).withLock {
        stopLocked(role)
        val ctx = AppGraph.appContext
        if (!serviceUp) return@withLock fail(role, "SwarmService is not running", modelPath, port)
        val bin = File(ctx.applicationInfo.nativeLibraryDir, role.binary)
        if (!bin.exists()) return@withLock fail(role, "${role.binary} is not in the APK (nativeLibraryDir ${bin.parent})", modelPath, port)
        if (role != Role.RPC && (modelPath == null || !File(modelPath).isFile)) {
            return@withLock fail(role, "model file not found: $modelPath", modelPath, port)
        }
        if (!waitForPort(role, port, 3_000)) {
            return@withLock fail(role, "port $port is already in use by another process on this phone", modelPath, port)
        }
        val args = LlamaArgs.build(role, modelPath, port, extraArgs)
        val cmd = listOf(bin.absolutePath) + args
        val logFile = File(File(ctx.filesDir, "logs").apply { mkdirs() }, "${role.tag}.log")
        val ring = LogRing()
        setStatus(role) {
            ServerStatus(role, ServerState.STARTING, null, modelPath, port, null, args, System.currentTimeMillis())
        }
        val proc = try {
            ProcessBuilder(cmd)
                .directory(ctx.filesDir)
                .redirectErrorStream(true)
                .apply { environment()["LD_LIBRARY_PATH"] = ctx.applicationInfo.nativeLibraryDir }
                .start()
        } catch (e: Exception) {
            return@withLock fail(role, "cannot exec ${bin.name}: ${e.message}", modelPath, port)
        }
        val run = Running(role, proc, ring, System.nanoTime())
        running[role] = run
        val pid = pidOf(proc)
        setStatus(role) { it.copy(pid = pid) }
        Log.i(TAG, "${role.tag}: started pid=$pid ${cmd.joinToString(" ")}")
        pump(run, logFile)
        watchExit(run, modelPath, port)

        // wait for readiness
        val deadline = System.nanoTime() + READY_TIMEOUT_MS * 1_000_000
        while (System.nanoTime() < deadline) {
            if (!proc.isAlive) break
            if (probe(role, port)) {
                // A health answer only counts if OUR process is still alive a moment later (guards against a foreign server on the port).
                delay(400)
                if (!proc.isAlive || !probe(role, port)) continue
                val ms = (System.nanoTime() - run.startedNs) / 1_000_000
                setStatus(role) { it.copy(state = ServerState.READY, readyAfterMs = ms) }
                Log.i(TAG, "${role.tag}: READY after $ms ms")
                return@withLock statusOf(role)
            }
            delay(300)
        }
        if (proc.isAlive) {
            run.stopRequested = true
            killProcess(proc)
            return@withLock fail(role, "not ready after ${READY_TIMEOUT_MS / 1000} s; ${ring.tail(1).lastOrNull().orEmpty()}", modelPath, port)
        }
        if (run.stopRequested) return@withLock statusOf(role)
        val code = runCatching { proc.exitValue() }.getOrDefault(-1)
        fail(role, "${ProcInfo.describeExit(code)} during start; last log: ${ring.tail(3).joinToString(" | ")}", modelPath, port)
    }

    /** Stops [role] (SIGTERM, then SIGKILL after 3 s). Safe to call when it is not running. */
    fun stop(role: Role) {
        val run = running[role] ?: run {
            setStatus(role) { ServerStatus(role, port = it.port) }
            return
        }
        run.stopRequested = true
        killProcess(run.process)
        running.remove(role, run)
        setStatus(role) { ServerStatus(role, port = it.port) }
    }

    /** Stops every role. Non-blocking for the caller beyond the SIGTERM; a watchdog escalates to SIGKILL. */
    fun stopAll() {
        for (r in Role.entries) stop(r)
    }

    /** Stops [role] and waits until the process is really gone, so its port is free for the next start. */
    private fun stopLocked(role: Role) {
        val run = running[role] ?: return
        stop(role)
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

    private fun fail(role: Role, reason: String, modelPath: String?, port: Int): ServerStatus {
        Log.e(TAG, "${role.tag}: FAILED: $reason")
        setStatus(role) { ServerStatus(role, ServerState.FAILED, reason, modelPath, port, null, it.args, it.startedAtMs) }
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

    /** Reports unexpected exits (this is the evidence for the phantom-process / OriginOS question). */
    private fun watchExit(run: Running, modelPath: String?, port: Int) {
        scope.launch {
            val code = run.process.waitFor()
            val aliveMs = (System.nanoTime() - run.startedNs) / 1_000_000
            if (run.stopRequested) return@launch
            val last = run.ring.tail(3).joinToString(" | ")
            val reason = "${ProcInfo.describeExit(code)} after ${aliveMs / 1000} s; last log: $last"
            Log.e(TAG, "${run.role.tag}: DIED pid=${pidOf(run.process)} $reason")
            if (running[run.role] === run) running.remove(run.role)
            setStatus(run.role) { ServerStatus(run.role, ServerState.FAILED, reason, modelPath, port, null, it.args, it.startedAtMs) }
        }
    }

    private fun probe(role: Role, port: Int): Boolean =
        if (role == Role.RPC) {
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500); true }
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

    /** Kills llama-server / rpc-server processes left behind by an earlier app process (same uid), so ports are free. */
    private fun killOrphans() {
        val mine = AndroidProcess.myPid()
        val dirs = File("/proc").listFiles { f -> f.name.all(Char::isDigit) } ?: return
        for (d in dirs) {
            val pid = d.name.toInt()
            if (pid == mine) continue
            val cmd = try { File(d, "cmdline").readText() } catch (_: Exception) { continue }
            val exe = cmd.substringBefore('\u0000')
            if (exe.endsWith("libllama-server.so") || exe.endsWith("librpc-server.so")) {
                if (running.values.none { pidOf(it.process) == pid.toLong() }) {
                    Log.w(TAG, "killing orphan $exe pid=$pid")
                    AndroidProcess.killProcess(pid)
                }
            }
        }
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
