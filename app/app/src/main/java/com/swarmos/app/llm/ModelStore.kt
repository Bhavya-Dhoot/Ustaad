package com.swarmos.app.llm

import android.content.Context
import android.os.StatFs
import com.swarmos.app.AppGraph
import com.swarmos.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** One downloadable model. [bytes] == 0 means the size is not pinned yet; [sha256] empty means unverified. */
data class CatalogEntry(
    val role: Role,           // CHAT or EMBED: what the file is meant for
    val file: String,
    val label: String,
    val url: String,
    val bytes: Long,
    val sha256: String,
    val default: Boolean = false,
    /** false = chat only: this model returns empty content for JSON-schema requests, so no structured call may be routed to it. */
    val structuredOutput: Boolean = true,
)

/** The pinned list from docs/models.md (file names, sizes, SHA-256). embeddinggemma-2 is not listed: llama.cpp v0.6.0 cannot load it. */
object ModelCatalog {
    val entries: List<CatalogEntry> = listOf(
        CatalogEntry(
            Role.CHAT, "Qwen3.5-4B-Q4_K_M.gguf", "Qwen3.5-4B Q4_K_M",
            "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_K_M.gguf",
            2_740_937_888L, "00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4", default = true,
        ),
        CatalogEntry(
            Role.CHAT, "gemma-4-E4B_q4_0-it.gguf", "Gemma 4 E4B QAT Q4_0",
            "https://huggingface.co/google/gemma-4-E4B-it-qat-q4_0-gguf/resolve/main/gemma-4-E4B_q4_0-it.gguf",
            5_154_941_280L, "676c35070db6dbe52f93e9c864ee0fba4eddea94b9c875d9cb10daff453fbaee",
            structuredOutput = false, // returns empty content for JSON-schema requests (measured on the phones)
        ),
        CatalogEntry(
            Role.EMBED, "embeddinggemma-300M-Q8_0.gguf", "EmbeddingGemma 300M Q8_0",
            "https://huggingface.co/ggml-org/embeddinggemma-300M-GGUF/resolve/main/embeddinggemma-300M-Q8_0.gguf",
            333_590_944L, "b5ce9d77a3fc4b3b39ccb5643c36777911cc4eb46a66962eadfa3f5f60490d63", default = true,
        ),
        // Too big for one phone (MemoryCloud splits it over three). Size and SHA-256 pinned in docs/models.md.
        CatalogEntry(
            Role.CHAT, "Qwen3-30B-A3B-Instruct-2507-Q4_K_M.gguf", "Qwen3-30B-A3B Instruct 2507 Q4_K_M",
            "https://huggingface.co/unsloth/Qwen3-30B-A3B-Instruct-2507-GGUF/resolve/main/Qwen3-30B-A3B-Instruct-2507-Q4_K_M.gguf",
            18_556_686_752L, "6c997b8af17debdfb01d890214400ccbab00db6acc0ba8da5de1cc906c4774d0",
        ),
    )

    fun byFile(file: String): CatalogEntry? = entries.firstOrNull { it.file == file }
    fun defaultFor(role: Role): CatalogEntry? = entries.firstOrNull { it.role == role && it.default }

    /** Shown on the model card and in /v1/models/state for models that must not get JSON-schema calls. */
    const val CHAT_ONLY_NOTE = "chat only, not for structured output"

    /** [modelName] is a file name with or without ".gguf" (as in ServerStatus.modelName). Unknown models are assumed fine. */
    fun allowsSchema(modelName: String): Boolean {
        val file = if (modelName.endsWith(".gguf")) modelName else "$modelName.gguf"
        return byFile(file)?.structuredOutput ?: true
    }
}

sealed interface DownloadState {
    data class Downloading(val done: Long, val total: Long, val bytesPerSec: Long) : DownloadState
    data object Verifying : DownloadState
    data class Failed(val message: String) : DownloadState
}

/** A GGUF file in the models directory. [complete] is false when its size differs from the pinned size. */
data class PresentModel(val file: String, val bytes: Long, val complete: Boolean)

/**
 * GGUF files in `/sdcard/Android/data/<pkg>/files/models/` (CONTRACTS 9.5): catalog, listing, resumable download
 * with a SHA-256 check, delete. Downloading is the app's only internet use, and [wanBytes] counts every byte of it.
 */
object ModelStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wan = AtomicLong(0)
    private val _wanBytes = MutableStateFlow(0L)

    /** Bytes received from the internet by this app process (model downloads only). Reads 0 when models were pushed over adb. */
    val wanBytes: StateFlow<Long> = _wanBytes.asStateFlow()

    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads.asStateFlow()

    private val _tick = MutableStateFlow(0)

    /** Bumps whenever the file list may have changed; the UI also bumps it on a timer. */
    val tick: StateFlow<Int> = _tick.asStateFlow()

    private val jobs = HashMap<String, Job>()

    fun refresh() { _tick.update { it + 1 } }

    fun modelsDir(): File {
        val ctx = AppGraph.appContext
        val dir = ctx.getExternalFilesDir("models") ?: File(ctx.filesDir, "models")
        dir.mkdirs()
        return dir
    }

    fun fileOf(name: String): File = File(modelsDir(), name)

    fun present(): List<PresentModel> =
        (modelsDir().listFiles { f -> f.isFile && f.name.endsWith(".gguf") } ?: emptyArray())
            .sortedBy { it.name }
            .map { f ->
                val want = ModelCatalog.byFile(f.name)?.bytes ?: 0L
                PresentModel(f.name, f.length(), want == 0L || want == f.length())
            }

    /**
     * The file to load for [role] at service start: the catalog default for the role (Qwen3.5-4B chat, EmbeddingGemma-300M
     * embedder), never the model that was loaded last (that could be the 18.5 GB split model, which no single phone can load).
     * Null when the default file is not on this phone or is incomplete.
     */
    fun autoLoadFile(role: Role): File? = autoLoadChoice(role, present())?.let(::fileOf)

    internal fun autoLoadChoice(role: Role, present: List<PresentModel>): String? {
        val want = ModelCatalog.defaultFor(role)?.file ?: return null
        return want.takeIf { f -> present.any { it.file == f && it.complete } }
    }

    private val _autoload by lazy { MutableStateFlow(prefs().getBoolean(KEY_AUTOLOAD, true)) }

    /** "Load models at start" (default on): whether the service loads the default chat and embedding models when it starts. */
    val autoload: StateFlow<Boolean> get() = _autoload

    fun setAutoload(on: Boolean) {
        prefs().edit().putBoolean(KEY_AUTOLOAD, on).apply()
        _autoload.value = on
    }

    private const val KEY_AUTOLOAD = "autoload"

    private val _useGpu by lazy { MutableStateFlow(prefs().getBoolean(KEY_GPU, true)) }

    /** "Use GPU for chat" (default on): the chat server starts on the OpenCL build and falls back to the CPU build if that fails. Applies from the next load. */
    val useGpu: StateFlow<Boolean> get() = _useGpu

    /** Setting the switch (either way) is the owner's "try the GPU again": it also forgets a remembered GPU failure ([GpuFailureMemory]). */
    fun setUseGpu(on: Boolean) {
        prefs().edit().putBoolean(KEY_GPU, on).remove(KEY_GPU_FAILED).apply()
        _useGpu.value = on
    }

    private const val KEY_GPU = "use_gpu"
    private const val KEY_GPU_FAILED = "gpu_failed_stamp"

    /** Identifies this install: version name, version code and the time of the last install or update (a dev build keeps its version name). */
    @Suppress("DEPRECATION")
    fun appStamp(): String {
        val ctx = AppGraph.appContext
        val updated = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        return "${BuildConfig.VERSION_NAME}/${BuildConfig.VERSION_CODE}/$updated"
    }

    /** The install stamp stored with the last GPU failure that the CPU build had to take over from, or null. */
    fun gpuFailedStamp(): String? = prefs().getString(KEY_GPU_FAILED, null)

    /** Remembers that the GPU build failed on this install while the CPU build served the same model. */
    fun markGpuFailed() {
        prefs().edit().putString(KEY_GPU_FAILED, appStamp()).apply()
    }

    private fun prefs() = AppGraph.appContext.getSharedPreferences("swarm_llm", Context.MODE_PRIVATE)

    // ---- download ----

    @Synchronized
    fun download(entry: CatalogEntry) {
        if (entry.url.isBlank()) { setState(entry.file, DownloadState.Failed("no download URL pinned for this file")); return }
        if (jobs[entry.file]?.isActive == true) return
        setState(entry.file, DownloadState.Downloading(0, entry.bytes, 0))
        jobs[entry.file] = scope.launch {
            // free space first: a download that cannot fit would fill the phone and fail at the end (StatFs runs here, off the main thread)
            val free = runCatching { StatFs(modelsDir().path).availableBytes }.getOrNull()
            val part = File(fileOf(entry.file).path + ".part").length()
            Downloader.spaceRefusal(entry.file, entry.bytes, part, free)?.let {
                setState(entry.file, DownloadState.Failed(it))
                return@launch
            }
            var lastBytes = 0L
            var lastNs = System.nanoTime()
            var rate = 0L
            val activityKey = "download-${entry.file}"
            ModelsActivity.set(activityKey, ModelsActivity.downloadText(0, entry.bytes), onStop = { cancelDownload(entry.file) })
            val listener = object : Downloader.Listener {
                override fun onNetworkBytes(n: Long) { _wanBytes.value = wan.addAndGet(n) }
                override fun onProgress(done: Long, total: Long) {
                    val now = System.nanoTime()
                    if (now - lastNs >= 1_000_000_000L) {
                        rate = (done - lastBytes) * 1_000_000_000L / (now - lastNs)
                        lastBytes = done; lastNs = now
                        ModelsActivity.update(activityKey, ModelsActivity.downloadText(done, total)) // once a second: it is also a notification
                    }
                    setState(entry.file, DownloadState.Downloading(done, total, rate))
                }
                override fun onVerifying() {
                    setState(entry.file, DownloadState.Verifying)
                    ModelsActivity.update(activityKey, "Checking a downloaded model.")
                }
            }
            try {
                Downloader().download(entry.url, fileOf(entry.file), entry.bytes.takeIf { it > 0 }, entry.sha256.takeIf { it.isNotEmpty() }, listener)
                setState(entry.file, null)
            } catch (e: CancellationException) {
                setState(entry.file, null)
                throw e
            } catch (e: Exception) {
                setState(entry.file, DownloadState.Failed(e.message ?: e.toString()))
            } finally {
                ModelsActivity.end(activityKey)
            }
            refresh()
        }
    }

    @Synchronized
    fun cancelDownload(file: String) {
        jobs.remove(file)?.cancel()
        setState(file, null)
    }

    /** True while a server that is loading or running holds [file]. A server in FAILED holds nothing, so its model may be deleted. */
    internal fun inUse(statuses: Collection<ServerStatus>, file: String): Boolean = statuses.any {
        (it.state == ServerState.READY || it.state == ServerState.STARTING) && it.modelPath?.let { p -> File(p).name } == file
    }

    /** Deletes the model (and any partial download). Refuses while a server is using it. */
    fun delete(file: String): Boolean {
        if (inUse(LlamaRuntime.status.value.values, file)) return false
        cancelDownload(file)
        val a = fileOf(file).delete()
        File(fileOf(file).path + ".part").delete()
        refresh()
        return a
    }

    private fun setState(file: String, s: DownloadState?) {
        _downloads.update { if (s == null) it - file else it + (file to s) }
    }
}
