package com.swarmos.app.memcloud

import com.swarmos.app.core.LruBudget
import com.swarmos.app.core.SwarmJson
import com.swarmos.app.core.UseLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
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

    /** What the Clear button says: how much went, or why it did not. */
    fun clearMessage(r: ClearResult): String = when (r) {
        is ClearResult.Cleared -> "Cleared ${"%.1f".format(r.removed.bytes / 1073741824.0)} GB (${r.removed.files} files)."
        is ClearResult.Refused -> r.message.replaceFirstChar { it.uppercase() } + "."
    }
}

/**
 * The budget keeper of the rpc tensor cache, least recently used first ([LruBudget]). The cache is a flat folder of files, one per tensor, so
 * the keeper groups them by the model of the loan that created them: a loan lists the folder when it starts and again when it ends, and the new
 * files belong to the loan's model (recorded under [stateDir], which is not the cache folder). Every loan marks its model's group as used now.
 * Over the budget, whole groups are evicted, least recently used first, never the group of a running loan; files that belong to no group
 * (from before this record, or from a loan nobody closed) form the group [UNKNOWN], which has never been used and so goes first.
 */
class RpcCacheKeeper(
    private val dir: File,
    stateDir: File,
    private val budget: () -> Long = { DEFAULT_BUDGET_BYTES },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        /** Holds the 30B model's 6 GB and the 4B model's 1.6 GB together; the device profile supplies the real budget later. */
        const val DEFAULT_BUDGET_BYTES = 8L * 1024 * 1024 * 1024
        const val UNKNOWN = "(unknown origin)"

        /** The group of a loan whose driver did not say which model it loads (an older driver). */
        const val UNNAMED = "(unnamed model)"
    }

    data class Report(val bytes: Long, val files: Int, val budgetBytes: Long)

    private val use = UseLog(stateDir, "rpc-cache-use.json")
    private val groupsFile = File(stateDir, "rpc-cache-groups.json")
    private var groups: MutableMap<String, List<String>>? = null
    private var running: String? = null
    private var before: Set<String> = emptySet()

    private fun files(): Map<String, Long> = dir.listFiles { f -> f.isFile }?.associate { it.name to it.length() } ?: emptyMap()

    @Synchronized private fun groups(): MutableMap<String, List<String>> = groups ?: (runCatching {
        if (groupsFile.isFile) SwarmJson.decodeFromString<Map<String, List<String>>>(groupsFile.readText()).toMutableMap() else null
    }.getOrNull() ?: mutableMapOf()).also { groups = it }

    @Synchronized private fun saveGroups() {
        runCatching { groupsFile.parentFile?.mkdirs(); groupsFile.writeText(SwarmJson.encodeToString(groups().toMap())) }
    }

    /** A loan for [model] begins (rpc-server is not yet running): the folder is listed, the model's group is used now, and the budget is kept. */
    @Synchronized fun loanStarted(model: String?) {
        loanEnded() // a loan nobody closed (its start failed) is closed first
        val key = model?.takeIf { it.isNotBlank() } ?: UNNAMED
        running = key
        before = files().keys
        use.touch(key, clock())
        enforce()
    }

    /** The loan ended and rpc-server is gone: the files that appeared belong to its model; the budget is kept. */
    @Synchronized fun loanEnded() {
        val key = running ?: return
        running = null
        val added = files().keys - before
        if (added.isNotEmpty()) { groups()[key] = (groups()[key].orEmpty() + added).distinct(); saveGroups() }
        use.touch(key, clock())
        enforce()
    }

    /** Deletes whole groups, least recently used first, until the cache is within the budget; returns the groups it deleted. */
    @Synchronized fun enforce(): List<String> {
        val present = files()
        val g = groups()
        if (g.entries.removeAll { (k, names) -> k != running && names.none { it in present } }) saveGroups() // groups whose files are all gone
        // the files the running loan has cached so far are its own (recorded when it ends) and are never evicted with it running
        val live = running?.let { r -> mapOf(r to (g[r].orEmpty() + (present.keys - before - g.values.flatten().toSet())).distinct()) } ?: emptyMap()
        val byGroup = g + live
        val known = byGroup.values.flatten().toSet()
        val unknownBytes = present.filterKeys { it !in known }.values.sum()
        val entries = byGroup.map { (k, names) -> LruBudget.Entry(k, names.sumOf { present[it] ?: 0L }, use.lastUsed(k), pinned = k == running) } +
            (if (unknownBytes > 0) listOf(LruBudget.Entry(UNKNOWN, unknownBytes, 0L)) else emptyList())
        val out = LruBudget.evict(entries, budget())
        for (e in out) {
            val names = if (e.key == UNKNOWN) present.keys - known else byGroup[e.key].orEmpty()
            names.forEach { File(dir, it).delete() }
            g.remove(e.key)
        }
        if (out.isNotEmpty()) { use.forget(out.map { it.key }); saveGroups() }
        return out.map { it.key }
    }

    /** The cache was cleared from outside (the Clear button): nothing is recorded any more. */
    @Synchronized fun forgetAll() {
        groups().clear(); saveGroups(); use.clear()
    }

    fun report(): Report = RpcCache.stats(dir).let { Report(it.bytes, it.files, budget()) }
}
