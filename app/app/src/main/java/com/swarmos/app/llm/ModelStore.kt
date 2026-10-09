package com.swarmos.app.llm

import android.content.Context
import com.swarmos.app.AppGraph
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
)

/** The pinned list from docs/models.md. File names, sizes and hashes come from files that were verified on the PC. */
object ModelCatalog {
    val entries: List<CatalogEntry> = listOf(
        CatalogEntry(
            Role.CHAT, "Qwen3.5-4B-Q4_K_M.gguf", "Qwen3.5-4B Q4_K_M",
            "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_K_M.gguf",
            2_740_937_888L, "00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4", default = true,
        ),
        CatalogEntry(
            Role.EMBED, "embeddinggemma-2-Q8_0.gguf", "EmbeddingGemma 2 Q8_0",
            "", // reconciled from docs/models.md
            309_855_456L, "2188ac1deca4b77dffefd603c2776a9d76d9d74ec01841392982ebb840b09135", default = true,
        ),
    )

    fun byFile(file: String): CatalogEntry? = entries.firstOrNull { it.file == file }
    fun defaultFor(role: Role): CatalogEntry? = entries.firstOrNull { it.role == role && it.default }
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

    /** The file to load for [role] at service start: the last one loaded for the role, else the catalog default; null if absent. */
    fun autoLoadFile(role: Role): File? {
        val candidates = listOfNotNull(lastLoaded(role), ModelCatalog.defaultFor(role)?.file)
        val complete = present().filter { it.complete }.map { it.file }.toSet()
        return candidates.firstOrNull { it in complete }?.let(::fileOf)
    }

    fun rememberLoaded(role: Role, file: String) {
        prefs().edit().putString("last_${role.tag}", file).apply()
    }

    private fun lastLoaded(role: Role): String? = prefs().getString("last_${role.tag}", null)

    private fun prefs() = AppGraph.appContext.getSharedPreferences("swarm_llm", Context.MODE_PRIVATE)

    // ---- download ----

    @Synchronized
    fun download(entry: CatalogEntry) {
        if (entry.url.isBlank()) { setState(entry.file, DownloadState.Failed("no download URL pinned for this file")); return }
        if (jobs[entry.file]?.isActive == true) return
        setState(entry.file, DownloadState.Downloading(0, entry.bytes, 0))
        jobs[entry.file] = scope.launch {
            var lastBytes = 0L
            var lastNs = System.nanoTime()
            var rate = 0L
            val listener = object : Downloader.Listener {
                override fun onNetworkBytes(n: Long) { _wanBytes.value = wan.addAndGet(n) }
                override fun onProgress(done: Long, total: Long) {
                    val now = System.nanoTime()
                    if (now - lastNs >= 1_000_000_000L) {
                        rate = (done - lastBytes) * 1_000_000_000L / (now - lastNs)
                        lastBytes = done; lastNs = now
                    }
                    setState(entry.file, DownloadState.Downloading(done, total, rate))
                }
                override fun onVerifying() { setState(entry.file, DownloadState.Verifying) }
            }
            try {
                Downloader().download(entry.url, fileOf(entry.file), entry.bytes.takeIf { it > 0 }, entry.sha256.takeIf { it.isNotEmpty() }, listener)
                setState(entry.file, null)
            } catch (e: CancellationException) {
                setState(entry.file, null)
                throw e
            } catch (e: Exception) {
                setState(entry.file, DownloadState.Failed(e.message ?: e.toString()))
            }
            refresh()
        }
    }

    @Synchronized
    fun cancelDownload(file: String) {
        jobs.remove(file)?.cancel()
        setState(file, null)
    }

    /** Deletes the model (and any partial download). Refuses while a server is using it. */
    fun delete(file: String): Boolean {
        val inUse = LlamaRuntime.status.value.values.any {
            it.state != ServerState.STOPPED && it.modelPath?.let { p -> File(p).name } == file
        }
        if (inUse) return false
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
