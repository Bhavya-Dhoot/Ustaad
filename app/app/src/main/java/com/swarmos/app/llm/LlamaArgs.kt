package com.swarmos.app.llm

import com.swarmos.app.core.Ports
import com.swarmos.app.core.ProfilePlan

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

    /**
     * The banner of a chat server that moved from the GPU to the CPU build: shown by a start whose GPU attempt did not get READY
     * and by the restart after a GPU server died twice. [reason] is the failure or the way it died.
     */
    fun fallbackNote(reason: String?): String = "The GPU server failed (${reason?.take(160) ?: "unknown reason"}). Running on the CPU build."

    /** The banner when the one automatic GPU try after a CPU fallback failed too: no further try is made until the owner uses the GPU switch or loads by hand. */
    fun gpuRetryFailedNote(reason: String?): String =
        "The GPU was tried again and failed (${reason?.take(160) ?: "unknown reason"}). Running on the CPU build. Turn \"Use GPU for chat\" off and on to try again."
}

/**
 * A GPU failure is remembered per install of the app: a GPU build that hangs costs [LlamaRuntime]'s whole GPU start timeout
 * (120 s) on every start, so after the CPU build had to take over once, chat starts on the CPU until the app is updated or
 * reinstalled (the stamp changes) or the owner turns the "Use GPU for chat" switch off and on again (the memory is cleared).
 */
object GpuFailureMemory {
    const val SKIPPED_NOTE = "The GPU build failed earlier on this version of Ustaad, so the chat model runs on the CPU. " +
        "Turn \"Use GPU for chat\" off and on to try the GPU again."

    /** True when [failedStamp] (the install stamp stored with the failure, null when none) is this install's [currentStamp]. */
    fun skipGpu(failedStamp: String?, currentStamp: String): Boolean = failedStamp != null && failedStamp == currentStamp
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
     * Scheduler capacity of a phone with a READY chat server (the fallback of [ProfilePlan.schedCapacity]). The server keeps [CHAT_SLOTS] slots so a local stream and a
     * short call can overlap without errors, but slots add no throughput (docs/benchmarks/2026-10-10-throughput-tuning.md),
     * so the scheduler hands out one job at a time.
     */
    const val SCHED_CAPACITY = 1

    fun binaryFor(role: Role, backend: Backend): String = if (role == Role.CHAT && backend == Backend.GPU) GPU_BINARY else role.binary

    /** Vendor library directories that hold libOpenCL.so (Adreno) and its dependencies. */
    val VENDOR_LIB_DIRS = listOf("/vendor/lib64", "/system/vendor/lib64")

    /**
     * LD_LIBRARY_PATH of the child. The GPU build links libOpenCL.so, which lives in the vendor partition. The manifest entry
     * `uses-native-library libOpenCL.so` only opens that library to the app's own class loader; an exec'd child is linked by
     * the linker's default namespace, which finds a vendor library only through LD_LIBRARY_PATH (directories on it count as
     * accessible; an absolute LD_PRELOAD path does not). Measured on phone C: without the vendor directories the child
     * stops with `library "libOpenCL.so" not found`. Every other child gets the native library directory only.
     */
    fun ldLibraryPath(nativeDir: String, role: Role, backend: Backend): String =
        if (role == Role.CHAT && backend == Backend.GPU) (listOf(nativeDir) + VENDOR_LIB_DIRS).joinToString(":") else nativeDir

    /**
     * Flags follow docs/llama-server-api-notes.md ("Recommended production flags"). One deviation: the chat context is
     * 8192 total (4096 per slot with 2 slots) instead of 4096 total, because Choir and Data Union prompts can exceed 2048 tokens.
     * Phones have 8 cores (2 prime + 6 performance); 6 chat threads leave two for the app and Android.
     */
    const val CHAT_THREADS = 6
    const val MIN_CHAT_THREADS = 2
    const val EMBED_THREADS = 2

    /**
     * Threads of the CPU chat build for a phone where the app may run on [allowedCores] cores (memcloud/CpuSet.allowed): one core
     * stays free for the app, at most [CHAT_THREADS], at least [MIN_CHAT_THREADS]. A child forked while the app is in the background
     * inherits fewer cores, and llama.cpp's thread barriers spin, so more threads than cores make every graph wait for a
     * descheduled thread (measured 0.3 against 3 tok/s, see CpuSet). The GPU build keeps [GPU_THREADS]. [maxThreads] is the
     * device plan's bound ([ProfilePlan.chatThreads]); without a plan it is [CHAT_THREADS].
     */
    fun cpuChatThreads(allowedCores: Int, maxThreads: Int = CHAT_THREADS): Int =
        (allowedCores - 1).coerceAtMost(maxThreads).coerceAtLeast(MIN_CHAT_THREADS)
    const val CHAT_CTX = 8192
    const val CHAT_SLOTS = 2

    /**
     * Memory caps of the chat server's prompt cache. This llama.cpp keeps up to 32 context checkpoints per slot (`-ctxcp`, tools/server
     * README) and a host prompt cache of 8192 MiB (`-cram`, common/common.h cache_ram_mib); on a phone the chat server's resident
     * memory was seen growing from about 0.8 GB after load to 3.0-3.9 GB over a session. Six checkpoints and 1 GiB keep the
     * repeat-question cache hit (never 0: 0 turns the cache off). Both flags exist in the shipped CPU and GPU builds. A 45-minute phone
     * run showed the growth stops: 1.1 to 1.6 GB (docs/benchmarks/2026-10-10-evening-checks.md, section 2).
     */
    const val CHAT_CTX_CHECKPOINTS = 6
    const val CHAT_CACHE_RAM_MIB = 1024

    /** `GGML_OPENCL_KERNEL_CACHE_DIR`: where the OpenCL build keeps compiled programs, so a later start skips the compile (ggml-opencl/cl-program-cache.cpp). */
    const val KERNEL_CACHE_ENV = "GGML_OPENCL_KERNEL_CACHE_DIR"
    const val KERNEL_CACHE_DIR_NAME = "cl-cache"

    /**
     * The environment entry that points the GPU chat build at [kernelCacheDir] (a directory under the app's files). Without it the
     * build looks for `<temp dir>/llama.cpp/cl-cache`, and the temp directory is not set for an exec'd child of an app, so the cache
     * stays off and every start compiles every kernel again. Empty for the other builds and when no directory could be made.
     */
    fun kernelCacheEnv(role: Role, backend: Backend, kernelCacheDir: String?): Map<String, String> =
        if (role == Role.CHAT && backend == Backend.GPU && !kernelCacheDir.isNullOrBlank()) mapOf(KERNEL_CACHE_ENV to kernelCacheDir) else emptyMap()
    const val EMBED_CTX = 4096
    const val EMBED_SLOTS = 2

    /**
     * Every llama-server (CHAT and EMBED, the split driver's included) gets `--api-key [apiKey]`: its port is on the loopback
     * address, open to every app on the phone, and the key is what keeps them out. rpc-server takes none. With the key
     * before [extra], an `--api-key` in [extra] would win (the last flag wins), so callers must not pass one.
     */
    fun build(
        role: Role, modelPath: String?, port: Int, extra: List<String> = emptyList(), backend: Backend = Backend.CPU,
        apiKey: String = LlamaKey.value,
        /** Cores this process may run on; the CPU chat build sizes its threads from it ([cpuChatThreads]). Null: the plan's bound or [CHAT_THREADS]. */
        allowedCores: Int? = null,
        /**
         * What the device check decided for this phone (threads, context, slots, GPU layers). Null (no check yet) keeps the constants
         * of this object, which are the iQOO 15's. [extra] still comes last and wins.
         */
        plan: ProfilePlan? = null,
    ): List<String> {
        val gpu = role == Role.CHAT && backend == Backend.GPU
        val maxThreads = plan?.chatThreads ?: CHAT_THREADS
        val chatThreads = if (gpu) plan?.gpuThreads ?: GPU_THREADS else allowedCores?.let { cpuChatThreads(it, maxThreads) } ?: maxThreads
        val base = when (role) {
            Role.CHAT -> listOf(
                "-m", requireNotNull(modelPath) { "chat needs a model" },
                "--host", "127.0.0.1", "--port", port.toString(),
            ) + (if (gpu) listOf("-ngl", (plan?.gpuLayers ?: GPU_LAYERS).toString()) else emptyList()) + listOf(
                "-t", chatThreads.toString(),
                "-c", (plan?.contextSize ?: CHAT_CTX).toString(),
                "-np", (plan?.slots ?: CHAT_SLOTS).toString(),
                "-ctxcp", CHAT_CTX_CHECKPOINTS.toString(),
                "-cram", CHAT_CACHE_RAM_MIB.toString(),
                "--reasoning", "off", // Qwen3.5 thinking off for every request (the client also sends enable_thinking=false)
                "--no-webui",
                API_KEY_FLAG, apiKey,
            )
            Role.EMBED -> listOf(
                "-m", requireNotNull(modelPath) { "embed needs a model" },
                "--host", "127.0.0.1", "--port", port.toString(),
                "--embedding",
                "-t", (plan?.embedThreads ?: EMBED_THREADS).toString(),
                "-c", EMBED_CTX.toString(),
                "-np", EMBED_SLOTS.toString(),
                "-b", (EMBED_CTX / EMBED_SLOTS).toString(), "-ub", (EMBED_CTX / EMBED_SLOTS).toString(), // longest text one slot can embed
                "--no-webui",
                API_KEY_FLAG, apiKey,
            )
            Role.RPC -> listOf("-H", "127.0.0.1", "-p", port.toString())
        }
        return base + extra
    }

    const val API_KEY_FLAG = "--api-key"

    /** [args] with the value of every `--api-key` hidden, for the status shown on the Models tab and in the log. */
    fun redacted(args: List<String>): List<String> =
        args.mapIndexed { i, a -> if (i > 0 && args[i - 1] == API_KEY_FLAG) "***" else a }

    /** Parallel slots a started server was given: the last `-np` / `--parallel` in [args], else [CHAT_SLOTS]. */
    fun slots(args: List<String>): Int {
        val i = args.indexOfLast { it == "-np" || it == "--parallel" }
        return args.getOrNull(i + 1)?.toIntOrNull()?.takeIf { i >= 0 && it > 0 } ?: CHAT_SLOTS
    }
}

/** Pure helpers for /proc parsing and the log ring buffer. */
object ProcInfo {
    /** Largest `lastError` the state route returns. */
    const val ERROR_DETAIL_MAX = 2048

    /**
     * The newest whole non-blank lines of [lines] joined with newlines, at most [maxChars] characters: the oldest lines are
     * dropped first, and a single line longer than the limit keeps its end.
     */
    fun errorDetail(lines: List<String>, maxChars: Int = ERROR_DETAIL_MAX): String {
        val kept = ArrayDeque<String>()
        var size = 0
        for (l in lines.asReversed().filter { it.isNotBlank() }) {
            val add = l.length + if (kept.isEmpty()) 0 else 1
            if (size + add > maxChars) {
                if (kept.isEmpty()) kept.addFirst(l.takeLast(maxChars))
                break
            }
            kept.addFirst(l)
            size += add
        }
        return kept.joinToString("\n")
    }

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
