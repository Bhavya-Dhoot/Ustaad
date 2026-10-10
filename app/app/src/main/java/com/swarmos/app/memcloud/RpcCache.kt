package com.swarmos.app.memcloud

import kotlinx.serialization.Serializable
import java.io.File

/** What POST /v1/memcloud/rpc/cache/clear removed, and what is left. */
@Serializable
data class RpcCacheDto(val clearedFiles: Int, val clearedBytes: Long, val remainingFiles: Int, val remainingBytes: Long)

/**
 * rpc-server's on-disk tensor cache (filesDir/llama-cache/rpc, one file per tensor it has received). It has no size cap, and a warm
 * load of a split depends on it (65 s warm against 319 s cold for the 18.6 GB model), so nothing deletes it automatically: the
 * state route reports its size (cachedBytes, cachedFiles) and a signed request clears it.
 */
object RpcCache {
    data class Stats(val bytes: Long, val files: Int)

    sealed interface ClearResult {
        data class Cleared(val removed: Stats, val remaining: Stats) : ClearResult
        data class Refused(val message: String) : ClearResult
    }

    fun stats(dir: File): Stats {
        val files = dir.listFiles { f -> f.isFile } ?: return Stats(0, 0)
        return Stats(files.sumOf { it.length() }, files.size)
    }

    /** Deletes the cache files in [dir]. [busy] is why that must not happen now (rpc-server is running and may be using them), or null. */
    fun clear(dir: File, busy: String?): ClearResult {
        if (busy != null) return ClearResult.Refused(busy)
        val files = dir.listFiles { f -> f.isFile } ?: return ClearResult.Cleared(Stats(0, 0), Stats(0, 0))
        var bytes = 0L
        var n = 0
        for (f in files) {
            val len = f.length()
            if (f.delete()) { bytes += len; n++ }
        }
        return ClearResult.Cleared(Stats(bytes, n), stats(dir))
    }
}
