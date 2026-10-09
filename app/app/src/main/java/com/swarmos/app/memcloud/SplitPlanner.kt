package com.swarmos.app.memcloud

import java.util.Locale
import kotlin.math.ceil

/** One phone in a split. The driver (this phone) is always first: its layers are the lowest, the peers follow in `--rpc` order. */
data class PlanDevice(
    val nodeId: String,
    /** "Phone A", "Phone B", ... */
    val name: String,
    val isDriver: Boolean,
    /** Memory this phone can give to the model once its own chat and embedding servers are stopped. */
    val ramAvailMb: Long,
    /** Address the driver reaches this phone's rpc-server on (null for the driver). */
    val host: String? = null,
)

data class Share(
    val device: PlanDevice,
    /** First layer index held, or -1 when the phone holds none. */
    val firstLayer: Int,
    val layerCount: Int,
    /** Layer weights plus the embedding table on the driver and the output head on the last remote phone. */
    val weightBytes: Long,
    val holdsInput: Boolean,
    val holdsOutput: Boolean,
) {
    val lastLayer: Int get() = firstLayer + layerCount - 1
    val range: String get() = if (layerCount == 0) "none" else if (layerCount == 1) "$firstLayer" else "$firstLayer-$lastLayer"
}

data class Fit(val device: PlanDevice, val needMb: Long, val haveMb: Long) {
    val ok: Boolean get() = needMb <= haveMb
}

enum class SplitMode { EQUAL, RAM }

class SplitPlan(val info: GgufInfo, val shares: List<Share>) {
    val nLayers: Int get() = info.blockCount
    val driver: Share get() = shares.first()
    val remote: List<Share> get() = shares.drop(1).filter { it.layerCount > 0 }

    /** Node name per layer (index = layer), null where nobody holds it. */
    fun assignments(): List<String?> {
        val out = arrayOfNulls<String>(nLayers)
        for (s in shares) for (i in 0 until s.layerCount) out[s.firstLayer + i] = s.device.name
        return out.toList()
    }

    fun counts(): List<Int> = shares.map { it.layerCount }

    /** Per phone: what it needs against what it has. */
    fun fits(): List<Fit> = shares.map { s ->
        Fit(s.device, ceil(s.weightBytes / MIB).toLong() + SplitPlanner.OVERHEAD_MB, s.device.ramAvailMb)
    }

    fun fitsAll(): Boolean = fits().all { it.ok }

    /** Layer boundaries a token crosses: one per change of holder in layer order, plus the return to the driver when it does not hold the last layer. */
    fun boundariesPerToken(): Int {
        val holders = shares.filter { it.layerCount > 0 }
        if (holders.isEmpty()) return 0
        val between = holders.size - 1
        return between + if (holders.last().device.isDriver) 0 else 1
    }

    private companion object { const val MIB = 1024.0 * 1024.0 }
}

object SplitPlanner {
    /** Memory every phone needs besides its weights: KV cache, compute buffers, the process itself. A planning constant, not a measurement. */
    const val OVERHEAD_MB = 1536L

    /** Largest-remainder split of [total] by [weights]; the counts add up to [total]. */
    fun apportion(total: Int, weights: List<Double>): List<Int> {
        require(weights.isNotEmpty() && total >= 0)
        val sum = weights.sum()
        if (sum <= 0.0) return apportion(total, weights.map { 1.0 })
        val exact = weights.map { it / sum * total }
        val counts = exact.map { it.toInt() }.toIntArray()
        var left = total - counts.sum()
        val order = exact.indices.sortedByDescending { exact[it] - counts[it] }
        var k = 0
        while (left > 0) { counts[order[k % order.size]]++; left--; k++ }
        return counts.toList()
    }

    /**
     * The +/- steppers of the plan screen: [delta] +1 moves one layer to device [index] from the nearest neighbour that has
     * one (next phones first), -1 moves one away to the next phone (the previous one for the last phone). The sum never changes.
     */
    fun shift(counts: List<Int>, index: Int, delta: Int): List<Int> {
        if (counts.size < 2 || index !in counts.indices) return counts
        val out = counts.toMutableList()
        val others = (index + 1 until counts.size) + (index - 1 downTo 0)
        if (delta > 0) {
            val donor = others.firstOrNull { out[it] > 0 } ?: return counts
            out[donor]--; out[index]++
        } else if (delta < 0) {
            if (out[index] == 0) return counts
            out[others.first()]++; out[index]--
        }
        return out
    }

    /**
     * [devices] must start with the driver. [manual] (one count per device, summing to the layer count) overrides [mode];
     * [mode] EQUAL gives every phone the same number of layers, RAM weights them by free memory.
     */
    fun plan(info: GgufInfo, devices: List<PlanDevice>, mode: SplitMode = SplitMode.EQUAL, manual: List<Int>? = null): SplitPlan {
        require(devices.isNotEmpty() && devices.first().isDriver) { "the driver must be first" }
        val n = info.blockCount
        val counts = when {
            manual != null -> {
                require(manual.size == devices.size && manual.sum() == n && manual.all { it >= 0 }) { "manual counts must be ${devices.size} values adding up to $n" }
                manual
            }
            mode == SplitMode.RAM -> apportion(n, devices.map { it.ramAvailMb.toDouble() })
            else -> apportion(n, devices.map { 1.0 })
        }
        var next = 0
        val lastRemote = devices.indices.lastOrNull { it > 0 && counts[it] > 0 }
        val shares = devices.mapIndexed { i, d ->
            val first = if (counts[i] == 0) -1 else next
            var bytes = 0L
            for (l in 0 until counts[i]) bytes += info.layerBytes[next + l]
            next += counts[i]
            val input = i == 0
            val output = if (lastRemote != null) i == lastRemote else i == 0
            if (input) bytes += info.inputBytes
            if (output) bytes += info.outputBytes
            Share(d, first, counts[i], bytes, input, output)
        }
        return SplitPlan(info, shares)
    }

    /**
     * The llama-server flags that make llama.cpp place the layers as planned, derived from how it assigns them
     * (src/llama-model.cpp, get_layer_buft_list): the last `ngl` of the n+1 layers (output head included) go to the RPC
     * devices in `--rpc` order, cut at the fractions of `-ts`; the first n+1-ngl layers stay on this phone's CPU.
     * Cut points sit half a layer before each boundary so float rounding cannot move a layer.
     */
    fun serverArgs(plan: SplitPlan, rpcPort: Int, ctx: Int = 4096): List<String> {
        val remote = plan.remote
        val n = plan.nLayers
        val args = ArrayList<String>()
        if (remote.isNotEmpty()) {
            args += listOf("--rpc", remote.joinToString(",") { "${it.device.host}:$rpcPort" })
            args += listOf("-ngl", (n + 1 - plan.driver.layerCount).toString())
            if (remote.size > 1) args += listOf("-ts", tensorSplit(remote.map { it.layerCount }).joinToString(","))
        } else {
            args += listOf("-ngl", "0")
        }
        args += listOf("-c", ctx.toString(), "-np", "1", "-fit", "off")
        return args
    }

    /** `-ts` values for remote layer counts [c]; the output head counts as one more layer on the last device. */
    fun tensorSplit(c: List<Int>): List<String> {
        val out = ArrayList<String>()
        var cum = 0
        var prevCut = 0.0
        val act = c.sum() + 1
        for (j in c.indices) {
            if (j < c.size - 1) {
                cum += c[j]
                val cut = cum - 0.5
                out += String.format(Locale.ROOT, "%.1f", cut - prevCut)
                prevCut = cut
            } else {
                out += String.format(Locale.ROOT, "%.1f", act - prevCut)
            }
        }
        return out
    }

    /**
     * Where llama.cpp puts layer 0..n (n = output head) for `-ngl [ngl] -ts [ts]` over [nRemote] RPC devices: the index of the
     * RPC device, or -1 for this phone's CPU. A copy of the placement rule, used to check the flags before a long load.
     */
    internal fun placement(n: Int, ngl: Int, ts: List<Float>, nRemote: Int): List<Int> {
        val iGpuStart = maxOf(n + 1 - ngl, 0)
        val act = minOf(ngl, n + 1)
        val splits = FloatArray(nRemote)
        var sum = 0f
        for (i in 0 until nRemote) { sum += ts.getOrElse(i) { 0f }; splits[i] = sum }
        for (i in 0 until nRemote) splits[i] = splits[i] / sum
        return (0..n).map { il ->
            if (il < iGpuStart || il - iGpuStart >= act) -1
            else {
                val v = (il - iGpuStart).toFloat() / act
                var d = 0
                while (d < nRemote && splits[d] <= v) d++ // upper_bound: first split strictly above v
                minOf(d, nRemote - 1)
            }
        }
    }

    /** True when [args] place every layer where [plan] says (the output head with the last remote phone). */
    fun argsMatchPlan(plan: SplitPlan, args: List<String>): Boolean {
        val remote = plan.remote
        if (remote.isEmpty()) return true
        val ngl = args.valueAfter("-ngl")?.toIntOrNull() ?: return false
        val ts = args.valueAfter("-ts")?.split(',')?.mapNotNull { it.toFloatOrNull() } ?: listOf(1f)
        val got = placement(plan.nLayers, ngl, ts, remote.size)
        val want = ArrayList<Int>()
        for (l in 0 until plan.driver.layerCount) want += -1
        remote.forEachIndexed { j, s -> repeat(s.layerCount) { want += j } }
        want += remote.size - 1 // output head
        return got == want
    }

    private fun List<String>.valueAfter(flag: String): String? = indexOf(flag).takeIf { it >= 0 }?.let { getOrNull(it + 1) }
}
