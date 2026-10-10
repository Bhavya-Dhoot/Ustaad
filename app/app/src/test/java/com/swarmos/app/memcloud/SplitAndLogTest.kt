package com.swarmos.app.memcloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** Builds a tiny GGUF in memory: the same byte layout llama.cpp writes. */
private class GgufBuilder {
    val out = ByteArrayOutputStream()
    private val kvs = ArrayList<ByteArray>()
    private val infos = ArrayList<ByteArray>()
    private var dataBytes = 0L

    private fun le(n: Long, bytes: Int, into: ByteArrayOutputStream = out) { for (i in 0 until bytes) into.write(((n shr (8 * i)) and 0xFF).toInt()) }
    private fun str(s: String, into: ByteArrayOutputStream) { val b = s.toByteArray(); le(b.size.toLong(), 8, into); into.write(b) }

    fun kvU32(key: String, v: Long) = kvs.add(ByteArrayOutputStream().also { str(key, it); le(4, 4, it); le(v, 4, it) }.toByteArray())
    fun kvStr(key: String, v: String) = kvs.add(ByteArrayOutputStream().also { str(key, it); le(8, 4, it); str(v, it) }.toByteArray())
    fun kvF32(key: String) = kvs.add(ByteArrayOutputStream().also { str(key, it); le(6, 4, it); le(0x3f800000, 4, it) }.toByteArray())
    fun kvStringArray(key: String, items: List<String>) = kvs.add(ByteArrayOutputStream().also {
        str(key, it); le(9, 4, it); le(8, 4, it); le(items.size.toLong(), 8, it); items.forEach { s -> str(s, it) }
    }.toByteArray())
    fun kvIntArray(key: String, items: List<Int>) = kvs.add(ByteArrayOutputStream().also {
        str(key, it); le(9, 4, it); le(5, 4, it); le(items.size.toLong(), 8, it); items.forEach { n -> le(n.toLong(), 4, it) }
    }.toByteArray())

    fun tensor(name: String, bytes: Long) {
        infos.add(ByteArrayOutputStream().also {
            str(name, it); le(2, 4, it); le(64, 8, it); le(bytes / 64, 8, it); le(0, 4, it); le(dataBytes, 8, it)
        }.toByteArray())
        dataBytes += bytes
    }

    /** Returns the whole file; the data section is zero padding of the right size. */
    fun build(): ByteArray {
        val head = ByteArrayOutputStream()
        head.write("GGUF".toByteArray()); le(3, 4, head); le(infos.size.toLong(), 8, head); le(kvs.size.toLong(), 8, head)
        kvs.forEach { head.write(it) }
        infos.forEach { head.write(it) }
        val padded = (head.size() + 31) / 32 * 32
        val all = ByteArrayOutputStream()
        all.write(head.toByteArray())
        for (i in head.size() until padded) all.write(0)
        all.write(ByteArray(dataBytes.toInt()))
        return all.toByteArray()
    }
}

private fun moeFile(layers: Int = 6, perLayer: Long = 6400, embd: Long = 1280, out: Long = 640): ByteArray = GgufBuilder().apply {
    kvStr("general.architecture", "qwen3moe")
    kvStr("general.name", "Test MoE")
    kvU32("qwen3moe.block_count", layers.toLong())
    kvU32("qwen3moe.expert_count", 128)
    kvU32("qwen3moe.expert_used_count", 8)
    kvU32("qwen3moe.embedding_length", 2048)
    kvF32("qwen3moe.rope.freq_base")
    kvIntArray("qwen3moe.attention.head_count_kv", listOf(4, 4, 4))
    kvStringArray("tokenizer.ggml.tokens", listOf("a", "bb", "ccc"))
    tensor("token_embd.weight", embd)
    for (l in 0 until layers) {
        tensor("blk.$l.attn_q.weight", perLayer / 2)
        tensor("blk.$l.ffn_up_exps.weight", perLayer / 2)
    }
    tensor("output_norm.weight", out / 2)
    tensor("output.weight", out / 2)
}.build()

class GgufMetaTest {
    @Test fun readsLayersExpertsAndBytesFromTheHeader() {
        val bytes = moeFile()
        val info = GgufMeta.read(bytes.inputStream(), "test.gguf", bytes.size.toLong())
        assertEquals("qwen3moe", info.architecture)
        assertEquals("Test MoE", info.name)
        assertEquals(6, info.blockCount)
        assertEquals(128, info.expertCount)
        assertEquals(8, info.expertUsedCount)
        assertEquals(2048, info.embeddingLength)
        assertTrue(info.isMoe)
        assertEquals(6, info.moeLayers)
        assertEquals(List(6) { 6400L }, info.layerBytes)
        assertEquals(1280L, info.inputBytes)
        assertEquals(320L, info.outputBytes)      // output_norm
        assertEquals(320L, info.outputHeadBytes)  // output.weight: untied head
        assertTrue(info.hasUntiedHead)
        assertEquals(6 * 6400L + 1280 + 640, info.tensorBytes)
    }

    @Test fun tiedEmbeddingsMeanNoSeparateHead() {
        val bytes = GgufBuilder().apply {
            kvStr("general.architecture", "qwen35")
            kvU32("qwen35.block_count", 1)
            tensor("token_embd.weight", 128)
            tensor("blk.0.ffn_up.weight", 256)
            tensor("output_norm.weight", 64)
        }.build()
        val info = GgufMeta.read(bytes.inputStream(), "tied.gguf", bytes.size.toLong())
        assertFalse(info.hasUntiedHead)
        assertEquals(64L, info.outputBytes)
    }

    @Test fun denseModelHasNoExperts() {
        val bytes = GgufBuilder().apply {
            kvStr("general.architecture", "qwen3")
            kvU32("qwen3.block_count", 2)
            tensor("token_embd.weight", 128)
            tensor("blk.0.ffn_up.weight", 256)
            tensor("blk.1.ffn_up.weight", 256)
        }.build()
        val info = GgufMeta.read(bytes.inputStream(), "dense-Q4.gguf", bytes.size.toLong())
        assertFalse(info.isMoe)
        assertEquals(0, info.moeLayers)
        assertEquals("dense-Q4", info.name) // no general.name: falls back to the file name
    }

    @Test fun rejectsAFileThatIsNotGguf() {
        val bad = ByteArray(64) { 7 }
        val e = runCatching { GgufMeta.read(bad.inputStream(), "x.gguf", 64) }.exceptionOrNull()
        assertTrue(e is GgufFormatException)
    }

    @Test fun blockIndexOnlyMatchesRepeatingLayers() {
        assertEquals(17, GgufMeta.blockIndex("blk.17.ffn_up_exps.weight"))
        assertNull(GgufMeta.blockIndex("token_embd.weight"))
        assertNull(GgufMeta.blockIndex("blk.x.weight"))
    }

    /** Runs against the real 30B file when this PC has it; skipped elsewhere. */
    @Test fun readsTheRealQwen3Moe30BHeader() {
        val f = File("H:/UStaaf/models/Qwen3-30B-A3B-Instruct-2507-Q4_K_M.gguf")
        assumeTrue(f.isFile)
        val info = GgufMeta.read(f)
        assertEquals(48, info.blockCount)
        assertEquals(128, info.expertCount)
        assertEquals(8, info.expertUsedCount)
        assertEquals(48, info.moeLayers)
        // layers + embedding + output account for the file minus the header
        assertTrue(info.tensorBytes in (info.fileBytes - 20_000_000)..info.fileBytes)
        assertTrue(info.layerBytes.all { it > 300_000_000L })
    }
}

class CpuSetTest {
    @Test fun countsCoresInACpuList() {
        assertEquals(6, CpuSet.count("0-5"))
        assertEquals(8, CpuSet.count("0-7"))
        assertEquals(6, CpuSet.count("0-3,6-7"))
        assertEquals(1, CpuSet.count("4"))
        assertNull(CpuSet.count(""))
        assertNull(CpuSet.count("a-b"))
        assertNull(CpuSet.count("5-2"))
    }

    @Test fun threadsStayBetweenTwoAndEight() {
        val t = CpuSet.threads()
        assertTrue(t in 2..8)
    }
}

class SplitPlannerTest {
    private fun info(layers: Int = 48, perLayer: Long = 380_000_000L) = GgufInfo(
        "m.gguf", layers * perLayer + 500_000_000, "qwen3moe", "M", layers, 2048, 4096, 128, 8, 0,
        List(layers) { perLayer }, 300_000_000L, 200_000_000L,
    )

    private fun devs(vararg ram: Long) = ram.mapIndexed { i, r ->
        PlanDevice("n$i", "Phone ${'A' + i}", i == 0, r, if (i == 0) null else "10.0.0.${i + 1}")
    }

    @Test fun apportionAddsUpAndFollowsWeights() {
        assertEquals(listOf(16, 16, 16), SplitPlanner.apportion(48, listOf(1.0, 1.0, 1.0)))
        assertEquals(listOf(14, 13, 13), SplitPlanner.apportion(40, listOf(1.0, 1.0, 1.0)))
        val byRam = SplitPlanner.apportion(48, listOf(8000.0, 4000.0, 4000.0))
        assertEquals(48, byRam.sum())
        assertEquals(listOf(24, 12, 12), byRam)
        assertEquals(10, SplitPlanner.apportion(10, listOf(0.0, 0.0)).sum())
    }

    @Test fun equalSplitGivesContiguousRangesInDeviceOrder() {
        val p = SplitPlanner.plan(info(), devs(9000, 9000, 9000))
        assertEquals(listOf("0-15", "16-31", "32-47"), p.shares.map { it.range })
        assertTrue(p.driver.holdsInput)
        assertTrue(p.shares[2].holdsOutput)
        assertEquals(List(16) { "Phone A" } + List(16) { "Phone B" } + List(16) { "Phone C" }, p.assignments())
        // driver carries the embedding table, the last phone the output head
        assertEquals(16 * 380_000_000L + 300_000_000L, p.shares[0].weightBytes)
        // tied head: the last phone also receives a copy of the embedding table for the output layer
        assertEquals(16 * 380_000_000L + 200_000_000L + 300_000_000L, p.shares[2].weightBytes)
    }

    @Test fun ramModeGivesTheBiggerPhoneMoreLayers() {
        val p = SplitPlanner.plan(info(), devs(12000, 6000, 6000), SplitMode.RAM)
        assertEquals(listOf(24, 12, 12), p.counts())
    }

    @Test fun manualCountsAreValidatedAndHonoured() {
        val p = SplitPlanner.plan(info(), devs(9000, 9000, 9000), manual = listOf(10, 20, 18))
        assertEquals(listOf("0-9", "10-29", "30-47"), p.shares.map { it.range })
        val e = runCatching { SplitPlanner.plan(info(), devs(9000, 9000), manual = listOf(10, 10)) }.exceptionOrNull()
        assertNotNull(e)
    }

    @Test fun fitCheckNamesThePhoneThatDoesNotFit() {
        val p = SplitPlanner.plan(info(), devs(9000, 5000, 9000))
        val fits = p.fits()
        assertTrue(fits[0].ok)
        assertFalse(fits[1].ok)
        assertFalse(p.fitsAll())
        // 16 layers x 380 MB = 6.08 GB = 5798 MiB (+1536 MiB planning overhead)
        assertTrue(fits[1].needMb in 7300..7400)
    }

    @Test fun anUntiedHeadStaysOnTheDriverAndTheFlagsSayso() {
        val withHead = info().copy(outputHeadBytes = 620_000_000L, outputBytes = 1_000_000L)
        val p = SplitPlanner.plan(withHead, devs(9000, 9000, 9000))
        assertTrue(p.headOnDriver)
        val args = SplitPlanner.serverArgs(p, 50052)
        assertEquals("^output\\.weight=CPU", args[args.indexOf("-ot") + 1])
        assertEquals(16 * 380_000_000L + 300_000_000L + 620_000_000L, p.shares[0].weightBytes)  // driver: layers, embedding, head
        assertEquals(16 * 380_000_000L + 1_000_000L, p.shares[2].weightBytes)                    // last phone: layers, output norm
        assertTrue(SplitPlanner.argsMatchPlan(p, args))
    }

    @Test fun aTiedHeadModelGetsNoOverride() {
        val p = SplitPlanner.plan(info(), devs(9000, 9000))
        assertFalse(p.headOnDriver)
        assertFalse(SplitPlanner.serverArgs(p, 50052).contains("-ot"))
    }

    @Test fun threePhoneFlagsPlaceEveryLayerWhereThePlanSays() {
        val p = SplitPlanner.plan(info(), devs(9000, 9000, 9000))
        val args = SplitPlanner.serverArgs(p, 50052)
        assertEquals("10.0.0.2:50052,10.0.0.3:50052", args[args.indexOf("--rpc") + 1])
        assertEquals("33", args[args.indexOf("-ngl") + 1]) // 48 + 1 - 16 local layers
        assertTrue(SplitPlanner.argsMatchPlan(p, args))
    }

    @Test fun flagsMatchThePlanForManySplitsAndModelSizes() {
        for (n in listOf(36, 40, 48)) for (a in 0..n step 5) for (b in 0..(n - a) step 7) {
            val counts = listOf(a, b, n - a - b)
            val p = SplitPlanner.plan(info(layers = n), devs(9000, 9000, 9000), manual = counts)
            val args = SplitPlanner.serverArgs(p, 50052)
            assertTrue("n=$n counts=$counts args=$args", SplitPlanner.argsMatchPlan(p, args))
        }
    }

    @Test fun theThreadCountIsAParameter() {
        val p = SplitPlanner.plan(info(layers = 36), devs(9000, 9000))
        val args = SplitPlanner.serverArgs(p, 50052, threads = 6)
        assertEquals("6", args[args.indexOf("-t") + 1])
    }

    @Test fun twoPhoneSplitUsesNoTensorSplitFlag() {
        val p = SplitPlanner.plan(info(layers = 36), devs(9000, 9000))
        val args = SplitPlanner.serverArgs(p, 50052)
        assertFalse(args.contains("-ts"))
        assertEquals("19", args[args.indexOf("-ngl") + 1]) // 36 + 1 - 18
        assertTrue(SplitPlanner.argsMatchPlan(p, args))
        assertEquals(2, p.boundariesPerToken()) // A to B, then B back to A
    }

    @Test fun driverWithNoLayersIsOnlyACoordinator() {
        val p = SplitPlanner.plan(info(layers = 12), devs(9000, 9000, 9000), manual = listOf(0, 6, 6))
        val args = SplitPlanner.serverArgs(p, 50052)
        assertEquals("13", args[args.indexOf("-ngl") + 1])
        assertTrue(SplitPlanner.argsMatchPlan(p, args))
        assertEquals("none", p.shares[0].range)
    }

    @Test fun noPeersMeansACpuOnlyLoad() {
        val p = SplitPlanner.plan(info(layers = 12), devs(9000))
        val args = SplitPlanner.serverArgs(p, 50052)
        assertFalse(args.contains("--rpc"))
        assertEquals("0", args[args.indexOf("-ngl") + 1])
        assertEquals(0, p.boundariesPerToken())
    }

    @Test fun steppersMoveOneLayerAndKeepTheTotal() {
        assertEquals(listOf(17, 15, 16), SplitPlanner.shift(listOf(16, 16, 16), 0, +1))
        assertEquals(listOf(15, 17, 16), SplitPlanner.shift(listOf(16, 16, 16), 0, -1))
        assertEquals(listOf(16, 17, 15), SplitPlanner.shift(listOf(16, 16, 16), 2, -1)) // last phone gives to the previous one
        assertEquals(listOf(16, 17, 15), SplitPlanner.shift(listOf(16, 16, 16), 1, +1)) // takes from the next phone first
        assertEquals(listOf(0, 10), SplitPlanner.shift(listOf(0, 10), 0, -1)) // nothing to give
        assertEquals(listOf(4, 6), SplitPlanner.shift(listOf(5, 5), 1, +1)) // last phone takes from the previous one
        assertEquals(10, SplitPlanner.shift(listOf(3, 3, 4), 1, +1).sum())
    }

    @Test fun threePhonesCrossThreeBoundariesPerToken() {
        assertEquals(3, SplitPlanner.plan(info(), devs(9000, 9000, 9000)).boundariesPerToken())
    }
}

class LoadLogParserTest {
    private val log = """
        llama_model_loader: loaded meta data with 33 key-value pairs and 579 tensors from /models/q.gguf (version GGUF V3 (latest))
        load_tensors: offloading output layer to GPU
        load_tensors: offloading 31 repeating layers to GPU
        load_tensors: offloaded 33/49 layers to GPU
        load_tensors:   CPU_Mapped model buffer size =  6012.31 MiB
        load_tensors: RPC0[192.168.242.23:50052] model buffer size =  5921.50 MiB
        load_tensors: RPC0[192.168.242.76:50052] model buffer size =  6100.00 MiB
        llama_kv_cache: RPC0[192.168.242.23:50052] KV buffer size =    64.00 MiB
        llama_kv_cache:        CPU KV buffer size =    32.00 MiB
        sched_reserve: RPC0[192.168.242.23:50052] compute buffer size =   300.25 MiB
        load_tensors: layer   0 assigned to device CPU, is_swa = 0
        load_tensors: layer  16 assigned to device RPC0, is_swa = 0
        ....................................................................................................
        main: server is listening on http://127.0.0.1:8080
    """.trimIndent().lines()

    @Test fun readsPerDeviceBuffersAndOffloadCounts() {
        val p = LoadLogParser().also { it.feed(log) }
        val s = p.snapshot()
        assertEquals(33, s.offloaded)
        assertEquals(49, s.offloadableTotal)
        assertEquals(4, s.buffers.size) // CPU_Mapped, two RPC buffers, and CPU (only the KV line names it)
        assertEquals(6012.31, s.localModelMiB!!, 0.001)
        assertEquals(5921.50, s.modelMiBOf("192.168.242.23")!!, 0.001)
        assertEquals(6100.0, s.modelMiBOf("192.168.242.76")!!, 0.001)
        val b = s.buffers.first { it.isRemote }
        assertEquals("192.168.242.23:50052", b.endpoint)
        assertEquals(64.0, b.kvMiB!!, 0.001)
        assertEquals(300.25, b.computeMiB!!, 0.001)
        assertEquals("RPC0", s.layerDevice[16])
        assertEquals(100, s.dotsPercent)
        assertTrue(s.metaLoaded)
        assertTrue(s.listening)
        assertNull(s.error)
    }

    @Test fun feedingTheSameLinesTwiceChangesNothing() {
        val p = LoadLogParser()
        p.feed(log); p.feed(log)
        assertEquals(4, p.snapshot().buffers.size)
    }

    @Test fun keepsTheFirstErrorLine() {
        val p = LoadLogParser()
        p.feed("load_tensors: loading model tensors")
        p.feed("ggml_backend_rpc_buffer_type_alloc_buffer: failed to allocate buffer of size 6442450944")
        p.feed("llama_model_load: error loading model: unable to allocate RPC0 buffer")
        val e = p.snapshot().error
        assertNotNull(e)
        assertTrue(e!!.contains("failed to allocate"))
    }

    @Test fun anEmptyLogIsNotListening() {
        val s = LoadLogParser().snapshot()
        assertFalse(s.listening)
        assertEquals(0, s.buffers.size)
        assertEquals(0, s.dotsPercent)
    }
}

class ExpertMeshTest {
    @Test fun remoteExpertCeilingIsOneRoundTripPerMoeLayer() {
        // 48 MoE layers at 11 ms: 528 ms per token, 1.89 tok/s
        assertEquals(528.0, ExpertMesh.remoteExpertMsPerToken(48, 11.0)!!, 1e-9)
        assertEquals(1.893939, ExpertMesh.remoteExpertCeilingTokPerSec(48, 11.0)!!, 1e-5)
    }

    @Test fun layerRangesPayOneRoundTripPerBoundary() {
        assertEquals(33.0, ExpertMesh.layerRangeMsPerToken(3, 11.0)!!, 1e-9)
        assertNull(ExpertMesh.layerRangeMsPerToken(0, 11.0))
    }

    @Test fun noMeasurementMeansNoNumber() {
        assertNull(ExpertMesh.remoteExpertCeilingTokPerSec(48, 0.0))
        assertNull(ExpertMesh.remoteExpertCeilingTokPerSec(0, 11.0)) // dense model: no MoE layers
        assertNull(ExpertMesh.meanRtt(listOf(null, null)))
    }

    @Test fun meanRttIgnoresMissingAndZeroSamples() {
        assertEquals(10.0, ExpertMesh.meanRtt(listOf(8.0, null, 12.0, 0.0))!!, 1e-9)
    }

    @Test fun expertsPerPhoneFollowTheLayerRange() {
        assertEquals(2048L, ExpertMesh.expertsOnPhone(16, 128))
    }
}
