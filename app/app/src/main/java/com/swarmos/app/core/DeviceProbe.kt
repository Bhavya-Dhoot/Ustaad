package com.swarmos.app.core

import android.app.ActivityManager
import android.os.Build
import android.os.StatFs
import android.util.Log
import com.swarmos.app.AppGraph
import com.swarmos.app.llm.Backend
import com.swarmos.app.llm.LlamaArgs
import com.swarmos.app.llm.ModelStore
import com.swarmos.app.llm.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/**
 * Reads the facts of this phone ([DeviceProfile]) at the first start, after an app update (the stored install stamp differs) and
 * on "Check again", stores them in `filesDir/device-profile.json`, and publishes them with the plan to [DeviceProfiles]. Android
 * only; the rules are in DeviceProfile.kt. Runs on the IO pool, one check at a time.
 */
object DeviceProbe {
    private const val TAG = "DeviceProbe"
    private const val FILE_NAME = "device-profile.json"
    private const val MIB = 1024L * 1024L
    private val lock = Mutex()
    private val writeLock = Any()

    private val _result = MutableStateFlow<DeviceState?>(null)

    /** The profile and plan once a check finished or a stored one was loaded; null before. The setup row follows it. */
    val result: StateFlow<DeviceState?> = _result.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    /**
     * Makes the profile current: the one in memory, else the stored one, else a fresh measurement. A measurement is made when
     * none is stored, the stored one is of another schema or another install, or [force] ("Check this phone again"). Returns at once
     * when nothing needs measuring; a measurement takes up to [GpuProbe.LIMIT_MS] for the GPU probe.
     */
    suspend fun ensure(force: Boolean = false): DeviceState? = lock.withLock {
        withContext(Dispatchers.IO) {
            val ctx = AppGraph.appContext
            val file = File(ctx.filesDir, FILE_NAME)
            val stamp = ModelStore.appStamp()
            DeviceProfiles.save = { write(file, it) }
            val modelsPath = ModelStore.modelsDir().path // looked up once: the capacity report reads the free space every few seconds
            DeviceProfiles.freeStorageMb = { freeStorageMb(modelsPath) }
            val have = DeviceProfiles.current?.profile ?: read(file)
            if (!force && !DeviceProfiles.needsCheck(have, stamp)) {
                if (DeviceProfiles.current == null) DeviceProfiles.set(have)
            } else {
                _checking.value = true
                try {
                    val fresh = measure(stamp, have)
                    write(file, fresh)
                    DeviceProfiles.set(fresh)
                    Log.i(TAG, "measured: $fresh -> ${DeviceProfiles.current?.plan}")
                } finally {
                    _checking.value = false
                }
            }
            DeviceProfiles.current.also { _result.value = it }
        }
    }

    private fun measure(stamp: String, old: DeviceProfile?): DeviceProfile {
        val ctx = AppGraph.appContext
        val mem = ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val cpuDirs = File("/sys/devices/system/cpu").listFiles { f -> f.isDirectory && f.name.matches(Regex("cpu\\d+")) }.orEmpty()
        val freqs = cpuDirs.sortedBy { it.name.removePrefix("cpu").toInt() }
            .mapNotNull { runCatching { File(it, "cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull() }
        val libDir = File(ctx.applicationInfo.nativeLibraryDir)
        val gpuBinary = File(libDir, LlamaArgs.GPU_BINARY).exists()
        return DeviceProfile(
            measuredAtMs = System.currentTimeMillis(),
            appVersion = stamp,
            totalRamMb = mem.totalMem / MIB,
            cores = cpuDirs.size.takeIf { it > 0 } ?: Runtime.getRuntime().availableProcessors(),
            // a partly readable list would under-count the fast cores: use all of it or none of it
            coreMaxKhz = if (freqs.size == cpuDirs.size) freqs else emptyList(),
            chip = Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN } ?: Build.HARDWARE,
            abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            osLevel = Build.VERSION.SDK_INT,
            freeStorageMb = freeStorageMb(ModelStore.modelsDir().path) ?: 0L,
            gpuBinaryPresent = gpuBinary,
            openClPresent = (LlamaArgs.VENDOR_LIB_DIRS + "/system/lib64").any { dir ->
                listOf("libOpenCL.so", "libOpenCL_adreno.so").any { File(dir, it).exists() }
            },
            gpuProbe = if (gpuBinary) probeGpu(libDir) else null,
            // the speed belongs to the model and the llama.cpp build, not to the facts above: it is kept across a re-check
            chatTokPerSec = old?.chatTokPerSec,
            chatTokPerSecModel = old?.chatTokPerSecModel,
        )
    }

    /**
     * Runs the GPU build of llama-server with `--list-devices`: upstream prints the non-CPU devices and exits without loading a model
     * (the flag is in both shipped builds). At most [GpuProbe.LIMIT_MS]; a run that cannot start or does not answer in time is
     * [ProbeVerdict.NO_ANSWER], which does not count against the GPU.
     */
    private fun probeGpu(libDir: File): GpuProbe {
        val ctx = AppGraph.appContext
        val bin = File(libDir, LlamaArgs.GPU_BINARY)
        val out = File(ctx.cacheDir, "gpu-probe.txt").apply { delete() }
        return try {
            val proc = ProcessBuilder(bin.absolutePath, "--list-devices")
                .directory(ctx.filesDir)
                .redirectErrorStream(true)
                .redirectOutput(out)
                .apply {
                    environment()["LD_LIBRARY_PATH"] = LlamaArgs.ldLibraryPath(libDir.path, Role.CHAT, Backend.GPU)
                    environment().putAll(
                        LlamaArgs.kernelCacheEnv(Role.CHAT, Backend.GPU, File(ctx.filesDir, LlamaArgs.KERNEL_CACHE_DIR_NAME).apply { mkdirs() }.path),
                    )
                }
                .start()
            val finished = proc.waitFor(GpuProbe.LIMIT_MS, TimeUnit.MILLISECONDS)
            if (!finished) proc.destroyForcibly().waitFor(1, TimeUnit.SECONDS)
            GpuProbe.parse(runCatching { out.readText().take(4_000) }.getOrDefault(""), if (finished) proc.exitValue() else null, !finished)
        } catch (e: Exception) {
            GpuProbe(ProbeVerdict.NO_ANSWER, "cannot run ${bin.name}: ${e.message}")
        }
    }

    private fun freeStorageMb(path: String): Long? = runCatching { StatFs(path).availableBytes / MIB }.getOrNull()

    private fun read(file: File): DeviceProfile? =
        runCatching { SwarmJson.decodeFromString(DeviceProfile.serializer(), file.readText()) }.getOrNull()

    private fun write(file: File, profile: DeviceProfile) {
        synchronized(writeLock) {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(SwarmJson.encodeToString(DeviceProfile.serializer(), profile))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }
}
