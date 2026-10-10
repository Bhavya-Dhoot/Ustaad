package com.swarmos.app.llm

import java.util.Locale

/**
 * The memory check before a local model load. Loading a model that does not fit makes Android swap other apps out until they
 * are killed (measured on the phones: MemAvailable fell from 10.2 GB to 0.9 GB and SwapFree from 14.7 to 4.1 GB while a 17 GB
 * model was read), so a load that cannot fit is refused with a plain message instead of attempted. Pure, for unit tests.
 */
object LoadPreflight {
    /**
     * PLANNING RULE, NOT A MEASUREMENT: a load needs MemAvailable of at least `file size x [NEED_FILE_FACTOR] + [NEED_MARGIN_BYTES]`.
     * The factor covers the repacked weights and the KV cache on top of the mapped file, the margin keeps Android and the app
     * itself alive. It was picked to be safe for the pinned models (4B: about 4.8 GB, 30B-A3B: about 24 GB), not fitted to data.
     */
    const val NEED_FILE_FACTOR = 1.2
    const val NEED_MARGIN_BYTES = 3L * 1024 * 1024 * 1024 / 2 // 1.5 GB

    fun neededBytes(fileBytes: Long): Long = (fileBytes * NEED_FILE_FACTOR).toLong() + NEED_MARGIN_BYTES

    /**
     * True when a load of [role] with [extraArgs] is checked. Only a plain local load is: MemoryCloud's driver starts the chat
     * server with arguments of its own (the split adds `--rpc`, the one-phone baseline adds `-ngl 0 ... -fit off` and exists to
     * show what happens when the model does not fit), so a load with extra arguments is left to its caller. rpc-server loads no model.
     */
    fun applies(role: Role, extraArgs: List<String>): Boolean = role != Role.RPC && extraArgs.isEmpty()

    /** `MemAvailable:    8716032 kB` -> 8716032 from the text of /proc/meminfo; null when absent. */
    fun parseMemAvailableKb(meminfo: String): Long? =
        meminfo.lineSequence().firstOrNull { it.startsWith("MemAvailable:") }
            ?.removePrefix("MemAvailable:")?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toLongOrNull()

    /**
     * The refusal text, or null when the load may go ahead. [availableKb] null (meminfo unreadable) allows the load: the check
     * must never be the reason a working phone cannot load anything.
     */
    fun refusal(fileName: String, fileBytes: Long, availableKb: Long?): String? {
        if (availableKb == null) return null
        val need = neededBytes(fileBytes)
        val have = availableKb * 1024
        if (have >= need) return null
        return "Not enough free memory to load $fileName: it needs about ${gb(need)} free (the file is ${gb(fileBytes)}; rule: " +
            "${NEED_FILE_FACTOR} x file + ${gb(NEED_MARGIN_BYTES)}) and this phone has ${gb(have)} available. " +
            "Close other apps, choose a smaller model, or split it over phones with MemoryCloud."
    }

    private fun gb(bytes: Long): String = String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
}
