package com.swarmos.app.memcloud

/** What llama-server printed about one device while loading, in MiB. */
data class DeviceBuffers(
    /** llama.cpp's buffer name: `CPU_Mapped`, `CPU`, `RPC0[192.168.242.23:50052]`. */
    val name: String,
    val modelMiB: Double? = null,
    val kvMiB: Double? = null,
    val computeMiB: Double? = null,
) {
    /** The RPC endpoint inside `RPC0[host:port]`, null for local buffers. */
    val endpoint: String? get() = name.substringAfter('[', "").substringBefore(']').ifEmpty { null }
    val isRemote: Boolean get() = name.startsWith("RPC")
}

data class LoadLog(
    /** "offloaded 48/49 layers to GPU" gives (48, 49): layers handed to the RPC devices out of repeating layers plus the output head. */
    val offloaded: Int?,
    val offloadableTotal: Int?,
    val buffers: List<DeviceBuffers>,
    /** `layer 12 assigned to device RPC0`, only present when llama-server ran with debug logging. */
    val layerDevice: Map<Int, String>,
    /** 0..100 from the progress dots, only known once the dot line is complete. */
    val dotsPercent: Int,
    val metaLoaded: Boolean,
    val listening: Boolean,
    val error: String?,
) {
    fun modelMiBOf(endpointHost: String): Double? = buffers.firstOrNull { it.endpoint?.startsWith(endpointHost) == true }?.modelMiB
    val localModelMiB: Double? get() = buffers.filter { !it.isRemote }.mapNotNull { it.modelMiB }.takeIf { it.isNotEmpty() }?.sum()
}

/**
 * Reads llama-server's start-up log line by line (formats from llama.cpp src/llama-model.cpp, llama-kv-cache.cpp,
 * llama-context.cpp). Feed it [LlamaRuntime.logTail] output; feeding the same lines twice is harmless.
 */
class LoadLogParser {
    private val order = ArrayList<String>()
    private val model = HashMap<String, Double>()
    private val kv = HashMap<String, Double>()
    private val compute = HashMap<String, Double>()
    private val layers = HashMap<Int, String>()
    private var offloaded: Int? = null
    private var total: Int? = null
    private var dots = 0
    private var meta = false
    private var listening = false
    private var error: String? = null

    fun feed(lines: List<String>) { lines.forEach(::feed) }

    fun feed(line: String) {
        modelBuf.find(line)?.let { put(model, it); return }
        kvBuf.find(line)?.let { put(kv, it); return }
        computeBuf.find(line)?.let { put(compute, it); return }
        offloadedRe.find(line)?.let {
            offloaded = it.groupValues[1].toInt()
            total = it.groupValues[2].toInt()
            return
        }
        assigned.find(line)?.let { layers[it.groupValues[1].toInt()] = it.groupValues[2]; return }
        if (line.contains("loaded meta data with")) meta = true
        if (line.contains("server is listening") || line.contains("all slots are idle")) listening = true
        dotRun.find(line)?.let { dots = maxOf(dots, minOf(100, it.value.length)) }
        if (error == null && errorRe.containsMatchIn(line) && !line.contains("error_log", ignoreCase = true)) {
            error = line.trim().take(240)
        }
    }

    private fun put(into: MutableMap<String, Double>, m: MatchResult) {
        val name = m.groupValues[1]
        if (name !in order) order += name
        into[name] = m.groupValues[2].toDouble()
    }

    fun snapshot(): LoadLog = LoadLog(
        offloaded = offloaded,
        offloadableTotal = total,
        buffers = order.map { DeviceBuffers(it, model[it], kv[it], compute[it]) },
        layerDevice = layers.toMap(),
        dotsPercent = dots,
        metaLoaded = meta,
        listening = listening,
        error = error,
    )

    private companion object {
        val modelBuf = Regex("""(\S+)\s+model buffer size =\s+([\d.]+) MiB""")
        val kvBuf = Regex("""(\S+)\s+KV buffer size =\s+([\d.]+) MiB""")
        val computeBuf = Regex("""(\S+)\s+compute buffer size =\s+([\d.]+) MiB""")
        val offloadedRe = Regex("""offloaded (\d+)/(\d+) layers to GPU""")
        val assigned = Regex("""layer\s+(\d+) assigned to device (\S+?),""")
        val dotRun = Regex("""\.{10,}""")
        val errorRe = Regex("""\b(error|failed|failure|abort|cannot|out of memory)\b""", RegexOption.IGNORE_CASE)
    }
}
