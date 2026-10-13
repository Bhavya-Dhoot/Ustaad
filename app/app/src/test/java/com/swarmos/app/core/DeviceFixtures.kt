package com.swarmos.app.core

/**
 * Device profiles for the tests. Only [iqoo15]'s total memory is a measured figure (MemTotal 15,583,752 kB, i.e. 15,218 MB, in
 * docs/device-probes/iqoo-A-10BFC41T2N001UZ.md); its core frequencies are the SoC's published ones, not read from a phone, and the
 * storage figure is made up. The other devices are made up to show a rule: the numbers are inputs to the plan, not claims about a model.
 */
internal object DeviceFixtures {
    const val QWEN_4B = "Qwen3.5-4B-Q4_K_M.gguf"
    const val QWEN_30B = "Qwen3-30B-A3B-Instruct-2507-Q4_K_M.gguf"
    const val GEMMA = "gemma-4-E4B_q4_0-it.gguf"
    const val EMBED = "embeddinggemma-300M-Q8_0.gguf"

    val gpuListed = GpuProbe(ProbeVerdict.GPU_LISTED, "GPUOpenCL: Adreno (fixture)")

    /** SM8850: 2 prime + 6 performance cores, GPU build present, the probe lists the Adreno. */
    val iqoo15 = DeviceProfile(
        measuredAtMs = 1L, appVersion = "t", totalRamMb = 15_218, cores = 8,
        coreMaxKhz = List(2) { 4_608_000L } + List(6) { 3_628_800L },
        chip = "SM8850", abi = "arm64-v8a", osLevel = 36, freeStorageMb = 200_000,
        gpuBinaryPresent = true, openClPresent = true, gpuProbe = gpuListed,
    )

    /** An 8 GB phone (the system reports about 7.4 GB): 1 prime + 3 + 2 big cores and 2 little ones, a GPU. */
    val phone8gb = DeviceProfile(
        measuredAtMs = 1L, appVersion = "t", totalRamMb = 7_500, cores = 8,
        coreMaxKhz = listOf(3_300_000L) + List(3) { 3_150_000L } + List(2) { 2_960_000L } + List(2) { 2_270_000L },
        chip = "SM8650", abi = "arm64-v8a", osLevel = 34, freeStorageMb = 40_000,
        gpuBinaryPresent = true, openClPresent = true, gpuProbe = gpuListed,
    )

    /** A 4 GB phone whose build has no GPU binary (and no OpenCL library). */
    val phone4gb = DeviceProfile(
        measuredAtMs = 1L, appVersion = "t", totalRamMb = 3_600, cores = 8,
        coreMaxKhz = List(4) { 2_400_000L } + List(4) { 1_800_000L },
        chip = "MT6769", abi = "arm64-v8a", osLevel = 33, freeStorageMb = 8_000,
        gpuBinaryPresent = false, openClPresent = false,
    )

    /** A 2.5 GB device: not even the embedding model fits. */
    val phone2gb = phone4gb.copy(totalRamMb = 2_500)

    /**
     * A 6 GB phone on Android 8.0 (API 26), made up like the others: eight cores with the frequencies readable, a 64-bit build (the only one
     * the APK installs on), no Adreno OpenCL library so no GPU. Build.SOC_MODEL does not exist before API 31, so the chip is the board name
     * or "unknown"; the system reports about 5.5 GB of its 6.
     */
    val android8_6gb = DeviceProfile(
        measuredAtMs = 1L, appVersion = "t", totalRamMb = 5_600, cores = 8,
        coreMaxKhz = List(4) { 2_200_000L } + List(4) { 1_800_000L },
        chip = "unknown", abi = "arm64-v8a", osLevel = 26, freeStorageMb = 20_000,
        gpuBinaryPresent = true, openClPresent = false,
    )

    /** A 3 GB phone on Android 8.0 (API 26) whose system hides the core frequencies (sysfs not readable): the plan counts all eight cores. */
    val android8_3gb = DeviceProfile(
        measuredAtMs = 1L, appVersion = "t", totalRamMb = 2_800, cores = 8, coreMaxKhz = emptyList(),
        chip = "mt6762", abi = "arm64-v8a", osLevel = 26, freeStorageMb = 6_000,
        gpuBinaryPresent = true, openClPresent = false,
    )

    /** A 32 GB desktop: 16 equal cores, no GPU build, a big disk. */
    val desktop32 = DeviceProfile(
        measuredAtMs = 1L, appVersion = "t", totalRamMb = 31_900, cores = 16, coreMaxKhz = List(16) { 4_500_000L },
        chip = "x86", abi = "x86_64", osLevel = 0, freeStorageMb = 500_000,
    )

    val desktop64 = desktop32.copy(totalRamMb = 63_800)
}
