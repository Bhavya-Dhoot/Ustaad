package com.swarmos.app.llm

import com.swarmos.app.core.Ports

/** Which llama.cpp process. CHAT and EMBED are llama-server instances, RPC is rpc-server (MemoryCloud workers). */
enum class Role(val tag: String, val binary: String, val defaultPort: Int) {
    CHAT("chat", "libllama-server.so", Ports.LLM_CHAT),
    EMBED("embed", "libllama-server.so", Ports.LLM_EMBED),
    RPC("rpc", "librpc-server.so", Ports.RPC);

    companion object {
        fun parse(s: String?): Role? = entries.firstOrNull { it.tag.equals(s?.trim(), ignoreCase = true) }
    }
}

/** Which build of llama-server a CHAT server runs on: the OpenCL (Adreno GPU) build or the CPU i8mm build. */
enum class Backend { CPU, GPU }

/**
 * Which backends a start of [role] tries, in order. CHAT goes to the GPU build first and falls back to the CPU build when
 * the GPU server does not get READY. Everything else (embedder, rpc-server, a split chat server) runs on the CPU build.
 */
object BackendPlan {
    fun choose(role: Role, extraArgs: List<String>, gpuEnabled: Boolean, gpuBinaryPresent: Boolean, cpuOnly: Boolean): List<Backend> {
        if (role != Role.CHAT || cpuOnly || !gpuEnabled || !gpuBinaryPresent) return listOf(Backend.CPU)
        // the MemoryCloud split keeps the CPU build: RPC together with OpenCL is unproven
        if ("--rpc" in extraArgs || "-rpc" in extraArgs) return listOf(Backend.CPU)
        return listOf(Backend.GPU, Backend.CPU)
    }
}

/**
 * Command lines for the shipped binaries. Everything that is a tuning choice lives here so it can be changed
 * in one place (and unit-tested). Flags that callers append through `extraArgs` come last, and llama.cpp's
 * argument parser lets the last occurrence of a flag win, so extraArgs override these defaults.
 */
object LlamaArgs {
    /** The OpenCL llama-server, shipped next to the CPU one under its own file name (both are called libllama-server.so upstream). */
    const val GPU_BINARY = "libllama-server-gpu.so"

    /** GPU path (docs/llama-server-api-notes.md): every layer on the GPU, threads are irrelevant so 4 leave the CPU free for the app. */
    const val GPU_THREADS = 4
    const val GPU_LAYERS = 99

    /**
     * Scheduler capacity of a phone with a READY chat server. The server keeps [CHAT_SLOTS] slots so a local stream and a
     * short call can overlap without errors, but slots add no throughput (docs/benchmarks/2026-10-10-throughput-tuning.md),
     * so the scheduler hands out one job at a time.
     */
    const val SCHED_CAPACITY = 1

    fun binaryFor(role: Role, backend: Backend): String = if (role == Role.CHAT && backend == Backend.GPU) GPU_BINARY else role.binary

    /**
     * Flags follow docs/llama-server-api-notes.md ("Recommended production flags"). One deviation: the chat context is
     * 8192 total (4096 per slot with 2 slots) instead of 4096 total, because Choir and Data Union prompts can exceed 2048 tokens.
     * Phones have 8 cores (2 prime + 6 performance); 6 chat threads leave two for the app and Android.
     */
    const val CHAT_THREADS = 6
    const val EMBED_THREADS = 2
    const val CHAT_CTX = 8192
    const val CHAT_SLOTS = 2
    const val EMBED_CTX = 4096
    const val EMBED_SLOTS = 2

    fun build(role: Role, modelPath: String?, port: Int, extra: List<String> = emptyList(), backend: Backend = Backend.CPU): List<String> {
        val gpu = role == Role.CHAT && backend == Backend.GPU
        val base = when (role) {
            Role.CHAT -> listOf(
                "-m", requireNotNull(modelPath) { "chat needs a model" },
                "--host", "127.0.0.1", "--port", port.toString(),
            ) + (if (gpu) listOf("-ngl", GPU_LAYERS.toString()) else emptyList()) + listOf(
                "-t", (if (gpu) GPU_THREADS else CHAT_THREADS).toString(),
                "-c", CHAT_CTX.toString(),
                "-np", CHAT_SLOTS.toString(),
                "--reasoning", "off", // Qwen3.5 thinking off for every request (the client also sends enable_thinking=false)
                "--no-webui",
            )
            Role.EMBED -> listOf(
                "-m", requireNotNull(modelPath) { "embed needs a model" },
                "--host", "127.0.0.1", "--port", port.toString(),
                "--embedding",
                "-t", EMBED_THREADS.toString(),
                "-c", EMBED_CTX.toString(),
                "-np", EMBED_SLOTS.toString(),
                "-b", (EMBED_CTX / EMBED_SLOTS).toString(), "-ub", (EMBED_CTX / EMBED_SLOTS).toString(), // longest text one slot can embed
                "--no-webui",
            )
            Role.RPC -> listOf("-H", "127.0.0.1", "-p", port.toString())
        }
        return base + extra
    }

    /** Parallel slots a started server was given: the last `-np` / `--parallel` in [args], else [CHAT_SLOTS]. */
    fun slots(args: List<String>): Int {
        val i = args.indexOfLast { it == "-np" || it == "--parallel" }
        return args.getOrNull(i + 1)?.toIntOrNull()?.takeIf { i >= 0 && it > 0 } ?: CHAT_SLOTS
    }
}

/** Pure helpers for /proc parsing and the log ring buffer. */
object ProcInfo {
    /** `VmRSS:   123456 kB` -> 123456 (kB) from the text of /proc/<pid>/status; null if absent. */
    fun statusKb(statusText: String, key: String): Long? =
        statusText.lineSequence()
            .firstOrNull { it.startsWith("$key:") }
            ?.removePrefix("$key:")?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toLongOrNull()

    /** True when /proc/<pid>/status says `State: Z (zombie)`: the process is dead and only waits to be reaped. */
    fun isZombie(statusText: String): Boolean =
        statusText.lineSequence().firstOrNull { it.startsWith("State:") }?.removePrefix("State:")?.trim()?.startsWith("Z") == true

    /** Process exit code as reported by the JVM: 128+N means killed by signal N. */
    fun describeExit(code: Int): String = when {
        code == 0 -> "exited normally"
        code > 128 -> "killed by signal ${code - 128}" + when (code - 128) {
            9 -> " (SIGKILL: lmkd, phantom-process killer or the vendor task killer)"
            15 -> " (SIGTERM)"
            11 -> " (SIGSEGV: crash)"
            6 -> " (SIGABRT: crash)"
            else -> ""
        }
        else -> "exited with code $code"
    }
}

/** Fixed-size ring of the most recent log lines. Thread-safe. */
class LogRing(private val capacity: Int = 400) {
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun add(line: String) {
        if (lines.size == capacity) lines.removeFirst()
        lines.addLast(line)
    }

    @Synchronized
    fun tail(n: Int): List<String> = lines.toList().takeLast(n)

    @Synchronized
    fun size(): Int = lines.size
}
