package com.swarmos.app.core

import com.swarmos.app.llm.CatalogEntry
import com.swarmos.app.llm.LlamaArgs
import com.swarmos.app.llm.LoadPreflight
import com.swarmos.app.llm.ModelCatalog
import com.swarmos.app.llm.Role
import com.swarmos.app.memcloud.SplitPlanner
import com.swarmos.app.sched.GenBudget
import kotlinx.serialization.Serializable

/*
 * What this device is, measured once (DeviceProbe) and turned into settings by one pure function (ProfilePlan.plan).
 * No Android classes here, so every rule is unit-tested on the JVM.
 */

/** What the GPU probe ([GpuProbe]) concluded. [NO_ANSWER] is "unknown": it never counts against the GPU. */
@Serializable
enum class ProbeVerdict { GPU_LISTED, NO_GPU, NO_ANSWER }

/** The result of running the GPU build of llama-server with `--list-devices` (it prints the devices and exits without loading a model). */
@Serializable
data class GpuProbe(val verdict: ProbeVerdict, val note: String = "") {
    companion object {
        const val LIMIT_MS = 5_000L

        /** The Android linker's `library "libOpenCL.so" not found`: seen on phone C when the vendor directories were missing from LD_LIBRARY_PATH. */
        private val LIBRARY_MISSING = Regex("""library ".+" not found""")

        /**
         * [output] is stdout and stderr of the run. A run that timed out or was killed by a signal says nothing about the GPU
         * ([ProbeVerdict.NO_ANSWER]); a linker error or a listing without any device is [ProbeVerdict.NO_GPU]. A device line is
         * what upstream prints after "Available devices:" (indented, `name: description (... MiB)`); the OpenCL backend's own log
         * line naming an Adreno also counts, so a changed listing format cannot turn a working GPU into "none".
         */
        fun parse(output: String, exitCode: Int?, timedOut: Boolean): GpuProbe {
            if (timedOut) return GpuProbe(ProbeVerdict.NO_ANSWER, "no answer within ${LIMIT_MS / 1000} s")
            if (exitCode != null && exitCode > 128) return GpuProbe(ProbeVerdict.NO_ANSWER, "stopped by signal ${exitCode - 128}")
            val lines = output.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
            val first = lines.firstOrNull().orEmpty().take(160)
            val header = lines.indexOfFirst { it.trim().startsWith("Available devices") }
            val device = (if (header >= 0) lines.drop(header + 1) else emptyList()).firstOrNull { it.startsWith(" ") && ": " in it }
                ?: lines.firstOrNull { it.contains("adreno", ignoreCase = true) }
            val linkerError = lines.any { "CANNOT LINK EXECUTABLE" in it || LIBRARY_MISSING.containsMatchIn(it) }
            return when {
                device != null -> GpuProbe(ProbeVerdict.GPU_LISTED, device.trim().take(160))
                linkerError -> GpuProbe(ProbeVerdict.NO_GPU, first)
                header >= 0 && exitCode == 0 -> GpuProbe(ProbeVerdict.NO_GPU, "no GPU device listed")
                exitCode != null && exitCode != 0 -> GpuProbe(ProbeVerdict.NO_GPU, "exit code $exitCode: $first")
                else -> GpuProbe(ProbeVerdict.NO_ANSWER, "unrecognised output: $first")
            }
        }
    }
}

/** The facts of one device. Stored as JSON (DeviceProbe); a file of another [schema] or of another install is measured again. */
@Serializable
data class DeviceProfile(
    val schema: Int = SCHEMA,
    val measuredAtMs: Long = 0L,
    /** The install stamp (ModelStore.appStamp) the facts were measured under; a different one means the app was updated. */
    val appVersion: String = "",
    val totalRamMb: Long,
    val cores: Int,
    /** Top frequency of each core in kHz (cpuinfo_max_freq); empty when the system does not let the app read it. */
    val coreMaxKhz: List<Long> = emptyList(),
    val chip: String = "",
    val abi: String = "",
    val osLevel: Int = 0,
    val freeStorageMb: Long = 0L,
    val gpuBinaryPresent: Boolean = false,
    val openClPresent: Boolean = false,
    val gpuProbe: GpuProbe? = null,
    /** Smoothed decode speed of [chatTokPerSecModel], kept across restarts ([DeviceProfiles.noteSpeed]); null until one was measured. */
    val chatTokPerSec: Double? = null,
    val chatTokPerSecModel: String? = null,
) {
    companion object { const val SCHEMA = 1 }
}

/** What a device does in the swarm; the enum order is smallest to largest. */
enum class DeviceClass(val meaning: String) {
    CLIENT("no chat model fits in its memory: it asks other devices to compute and still stores and searches"),
    SMALL("a chat model fits with little to spare: one slot and a shorter context"),
    STANDARD("the usual phone: the default chat model with two slots, and it can lend memory"),
    LARGE("plenty of memory, such as a desktop: it can hold a bigger chat model than the default when the catalog has one"),
}

/** Every setting [plan] derives from a [DeviceProfile]. */
data class ProfilePlan(
    val deviceClass: DeviceClass,
    /** Catalog chat model files that fit, largest first; empty for a client. The automatic load takes the first one that is on the device. */
    val chatModels: List<String>,
    val useGpu: Boolean,
    /** Upper bound of the CPU chat build's threads (it also leaves a core free for the app, see LlamaArgs.cpuChatThreads). */
    val chatThreads: Int,
    val gpuThreads: Int,
    val gpuLayers: Int,
    val embedThreads: Int,
    val contextSize: Int,
    val slots: Int,
    /** Jobs the scheduler hands this device at once while its chat server is READY; 0 for a client. */
    val schedCapacity: Int,
    val mayLend: Boolean,
    val lendMaxMb: Long,
    val storageOfferMb: Long,
    /** Allowance per generated token for jobs sent to this device ([GenBudget.msPerToken]); never below 200. */
    val msPerToken: Long,
    /** What the smallest chat model of the catalog needs under the load rule, in MB: how far a client is from running one. */
    val smallestChatNeedMb: Long,
) {
    val chatModel: String? get() = chatModels.firstOrNull()
    val isClient: Boolean get() = deviceClass == DeviceClass.CLIENT

    companion object {
        /**
         * PLANNING RULE, NOT A MEASUREMENT: a chat model "fits" when the load rule ([LoadPreflight.neededBytes]: 1.2 x file + 1.5 GB)
         * is met by this share of the total memory. The load itself is checked against MemAvailable at the moment of the load;
         * this is the same test made ahead of time, when only the total is known. 0.65 is the middle of three idle readings on
         * the 16 GB phones: 56 % (MemAvailable 8,680,356 of MemTotal 15,583,752 kB, docs/device-probes/iqoo-A-10BFC41T2N001UZ.md),
         * 64 to 67 % (9.94 GB, docs/benchmarks/2026-10-10-throughput-tuning.md) and 72 to 77 % (11.2 to 11.5 GB with nothing loaded,
         * docs/benchmarks/2026-10-10-memorycloud-inapp.md). Lower turns a phone that can run the model into a client; higher plans
         * a model that the load check then refuses (it refuses with a message and tries again by itself, so that costs less).
         * No phone with less than 16 GB was measured. Whole percent, so the sums are exact.
         */
        const val MEMORY_SHARE_PCT = 65L

        /** Below this total memory a device that runs a model is SMALL (one slot, 4096 context); an 8 GB phone reports about 7.4 GB. */
        const val SMALL_BELOW_MB = 10_240L

        /** From this total memory a device is LARGE (a 32 GB desktop reports about 31 GB). */
        const val LARGE_FROM_MB = 24_576L

        /** A cap, not a measurement: decode is memory-bound, threads beyond this add little. */
        const val MAX_CHAT_THREADS = 8

        /** A core counts as fast when its top frequency is at least this share of the fastest core's; the small cores of the SoCs seen run at 60 to 70 %. */
        const val FAST_CORE_SHARE = 0.7

        /** Memory a device keeps for itself when it lends: the split planner's overhead. Lending below [MIN_LEND_MB] is not offered. */
        val LEND_RESERVE_MB = SplitPlanner.OVERHEAD_MB
        const val MIN_LEND_MB = 2_048L

        /** Default storage offer: 10 % of the free space, at least 1 GB, never more than half of it. */
        fun storageOfferMb(freeMb: Long): Long = maxOf(freeMb / 10, 1_024L).coerceAtMost(freeMb / 2).coerceAtLeast(0L)

        fun plan(p: DeviceProfile, catalog: List<CatalogEntry>): ProfilePlan {
            val shareMb = p.totalRamMb * MEMORY_SHARE_PCT / 100
            val shareBytes = shareMb * MIB
            // Only models the app's own flows can use on their own: chat models whose structured output works (Choir and the Data Union
            // send JSON-schema calls to the chat model) and whose size is pinned.
            val candidates = catalog.filter { it.role == Role.CHAT && it.structuredOutput && it.bytes > 0 }
            val models = candidates.filter { LoadPreflight.neededBytes(it.bytes) <= shareBytes }.sortedByDescending { it.bytes }
            val deviceClass = when {
                models.isEmpty() -> DeviceClass.CLIENT
                p.totalRamMb < SMALL_BELOW_MB -> DeviceClass.SMALL
                p.totalRamMb < LARGE_FROM_MB -> DeviceClass.STANDARD
                else -> DeviceClass.LARGE
            }
            val chatThreads = (fastCores(p) - 2).coerceIn(LlamaArgs.MIN_CHAT_THREADS, MAX_CHAT_THREADS)
            val small = deviceClass <= DeviceClass.SMALL
            val lendMax = shareMb - LEND_RESERVE_MB // the lender stops its own servers, so what the load rule would let a model have is lendable
            val gpu = p.gpuBinaryPresent && when (p.gpuProbe?.verdict) {
                ProbeVerdict.GPU_LISTED -> true
                ProbeVerdict.NO_GPU -> false
                else -> p.openClPresent // no answer from the probe: the library check decides
            }
            return ProfilePlan(
                deviceClass = deviceClass,
                chatModels = models.map { it.file },
                useGpu = models.isNotEmpty() && gpu,
                chatThreads = chatThreads,
                gpuThreads = minOf(LlamaArgs.GPU_THREADS, chatThreads),
                gpuLayers = LlamaArgs.GPU_LAYERS,
                embedThreads = (chatThreads / 3).coerceIn(1, LlamaArgs.EMBED_THREADS),
                // 4096 is the context one slot needs for Choir and Data Union prompts (LlamaArgs.CHAT_CTX)
                contextSize = if (small) LlamaArgs.CHAT_CTX / LlamaArgs.CHAT_SLOTS else LlamaArgs.CHAT_CTX,
                slots = if (small) 1 else LlamaArgs.CHAT_SLOTS,
                schedCapacity = if (models.isEmpty()) 0 else LlamaArgs.SCHED_CAPACITY,
                mayLend = lendMax >= MIN_LEND_MB,
                lendMaxMb = if (lendMax >= MIN_LEND_MB) lendMax else 0L,
                storageOfferMb = storageOfferMb(p.freeStorageMb),
                msPerToken = GenBudget.msPerToken(p.chatTokPerSec),
                smallestChatNeedMb = candidates.minOfOrNull { LoadPreflight.neededBytes(it.bytes) / MIB } ?: 0L,
            )
        }

        private const val MIB = 1024L * 1024L

        /** Cores worth a thread: all of them when their frequencies are unknown, else those near the fastest core's. */
        private fun fastCores(p: DeviceProfile): Int {
            val top = p.coreMaxKhz.maxOrNull() ?: return p.cores
            return p.coreMaxKhz.count { it >= top * FAST_CORE_SHARE }
        }
    }
}

/** The profile of this device and the plan made from it, always the two together. */
data class DeviceState(val profile: DeviceProfile, val plan: ProfilePlan)

/**
 * The one place the rest of the app reads the device's profile and plan from. Null until the check has run (DeviceProbe sets it):
 * every reader then keeps today's constants. [save] and [freeStorageMb] are set by DeviceProbe, the only Android part.
 */
object DeviceProfiles {
    @Volatile
    var current: DeviceState? = null
        private set

    /** Writes a changed profile to disk (the smoothed speed changes it once a minute at most). */
    @Volatile var save: ((DeviceProfile) -> Unit)? = null

    /** Free storage right now in MB, read when a capacity report is made; null when unknown (the profile's figure is then used). */
    @Volatile var freeStorageMb: (() -> Long?)? = null

    fun set(profile: DeviceProfile?, catalog: List<CatalogEntry> = ModelCatalog.entries) {
        current = profile?.let { DeviceState(it, ProfilePlan.plan(it, catalog)) }
    }

    /** True when the facts must be measured again: none stored, a file of another schema, or another install of the app. */
    fun needsCheck(stored: DeviceProfile?, appVersion: String): Boolean =
        stored == null || stored.schema != DeviceProfile.SCHEMA || stored.appVersion != appVersion

    const val SPEED_EVERY_MS = 60_000L
    const val SPEED_ALPHA = 0.3
    private var lastSpeedAtMs = -SPEED_EVERY_MS

    /**
     * A decode speed seen on a real job. Taken at most once a minute; the stored figure moves [SPEED_ALPHA] of the way to it, or
     * is replaced by it when the model changed (a speed belongs to its model). The changed profile is saved.
     */
    @Synchronized
    fun noteSpeed(model: String, tokPerSec: Double, nowMs: Long = System.currentTimeMillis()) {
        val cur = current ?: return
        if (tokPerSec <= 0.0 || nowMs - lastSpeedAtMs < SPEED_EVERY_MS) return
        lastSpeedAtMs = nowMs
        val old = cur.profile.chatTokPerSec
        val smoothed = if (old == null || cur.profile.chatTokPerSecModel != model) tokPerSec else old + SPEED_ALPHA * (tokPerSec - old)
        val updated = cur.profile.copy(chatTokPerSec = smoothed, chatTokPerSecModel = model)
        set(updated)
        runCatching { save?.invoke(updated) }
    }

    /** Test hook: forget the profile and the once-a-minute clock. */
    internal fun reset() {
        current = null
        save = null
        freeStorageMb = null
        lastSpeedAtMs = -SPEED_EVERY_MS
    }
}

/** "16 GB": the memory size the phone is sold as, the smallest usual size that holds the [totalRamMb] the system reports (it reports about 1 GB less). */
fun nominalRamGb(totalRamMb: Long): Int =
    listOf(1, 2, 3, 4, 6, 8, 12, 16, 18, 24, 32, 48, 64, 96, 128).firstOrNull { it * 1024L >= totalRamMb } ?: ((totalRamMb + 1023) / 1024).toInt()
