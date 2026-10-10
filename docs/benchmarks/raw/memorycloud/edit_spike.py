import os
base = r"H:\UStaaf\.claude\worktrees\agent-ac34ad0bc1b4ddd46\app\app\src"
main = os.path.join(base, "main", "java", "com", "swarmos", "app", "memcloud")
test = os.path.join(base, "test", "java", "com", "swarmos", "app", "memcloud")

def edit(d, name, pairs):
    p = os.path.join(d, name)
    s = open(p, encoding="utf-8").read()
    for old, new in pairs:
        if old not in s:
            raise SystemExit("missing in %s: %s" % (name, old[:70]))
        s = s.replace(old, new, 1)
    open(p, "w", encoding="utf-8").write(s)

# ---- GGUF: separate the output head from the other output tensors
edit(main, "GgufMeta.kt", [
("""    /** Bytes of everything else (output norm, lm_head, rope tables): goes with the last layer range. */
    val outputBytes: Long,
) {""", """    /** Bytes of the other tensors outside the layers (output norm, rope tables): goes with the last layer range. */
    val outputBytes: Long,
    /** Bytes of `output.weight`, the lm_head; 0 when the model ties it to the embedding table (Qwen3.5-4B). */
    val outputHeadBytes: Long = 0,
) {
    /** An untied lm_head can stay on the driver, so the 600 KB of logits per token never cross the link. */
    val hasUntiedHead: Boolean get() = outputHeadBytes > 0
"""),
("""    val tensorBytes: Long get() = layerBytes.sum() + inputBytes + outputBytes""", """    val tensorBytes: Long get() = layerBytes.sum() + inputBytes + outputBytes + outputHeadBytes"""),
("""        var rest = 0L
        for ((i, t) in sorted.withIndex()) {""", """        var rest = 0L
        var head = 0L
        for ((i, t) in sorted.withIndex()) {"""),
("""                t.name.startsWith("token_embd") -> input0 += size""", """                t.name.startsWith("token_embd") -> input0 += size
                t.name == "output.weight" -> head += size"""),
("""            outputBytes = rest,
        )""", """            outputBytes = rest,
            outputHeadBytes = head,
        )"""),
])

# ---- planner: lm_head on the driver when the model has one; 8 threads like the spike
edit(main, "SplitPlanner.kt", [
("""class SplitPlan(val info: GgufInfo, val shares: List<Share>) {""", """class SplitPlan(val info: GgufInfo, val shares: List<Share>, val headOnDriver: Boolean = false) {"""),
("""    fun plan(info: GgufInfo, devices: List<PlanDevice>, mode: SplitMode = SplitMode.EQUAL, manual: List<Int>? = null): SplitPlan {""",
 """    fun plan(info: GgufInfo, devices: List<PlanDevice>, mode: SplitMode = SplitMode.EQUAL, manual: List<Int>? = null, headOnDriver: Boolean = info.hasUntiedHead): SplitPlan {"""),
("""            if (input) bytes += info.inputBytes
            if (output) bytes += info.outputBytes
            Share(d, first, counts[i], bytes, input, output)
        }
        return SplitPlan(info, shares)""", """            if (input) bytes += info.inputBytes
            if (output) bytes += info.outputBytes
            // the lm_head follows the output layer unless it is pinned to the driver (spike: -ot ^output\\.weight=CPU)
            val headHere = if (headOnDriver && lastRemote != null) i == 0 else output
            if (headHere) bytes += info.outputHeadBytes
            Share(d, first, counts[i], bytes, input, output)
        }
        return SplitPlan(info, shares, headOnDriver && lastRemote != null)"""),
("""        args += listOf("-c", ctx.toString(), "-np", "1", "-fit", "off")""", """        if (plan.headOnDriver) args += listOf("-ot", "^output\\\\.weight=CPU")
        args += listOf("-t", "8", "-c", ctx.toString(), "-np", "1", "-fit", "off")"""),
])

# ---- tests
edit(test, "SplitAndLogTest.kt", [
("""        assertEquals(1280L, info.inputBytes)
        assertEquals(640L, info.outputBytes)
        assertEquals(6 * 6400L + 1280 + 640, info.tensorBytes)""", """        assertEquals(1280L, info.inputBytes)
        assertEquals(320L, info.outputBytes)      // output_norm
        assertEquals(320L, info.outputHeadBytes)  // output.weight: untied head
        assertTrue(info.hasUntiedHead)
        assertEquals(6 * 6400L + 1280 + 640, info.tensorBytes)"""),
("""    @Test fun denseModelHasNoExperts() {""", """    @Test fun tiedEmbeddingsMeanNoSeparateHead() {
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

    @Test fun denseModelHasNoExperts() {"""),
("""    @Test fun threePhoneFlagsPlaceEveryLayerWhereThePlanSays() {""", """    @Test fun anUntiedHeadStaysOnTheDriverAndTheFlagsSayso() {
        val withHead = info().copy(outputHeadBytes = 620_000_000L, outputBytes = 1_000_000L)
        val p = SplitPlanner.plan(withHead, devs(9000, 9000, 9000))
        assertTrue(p.headOnDriver)
        val args = SplitPlanner.serverArgs(p, 50052)
        assertEquals("^output\\\\.weight=CPU", args[args.indexOf("-ot") + 1])
        assertEquals(16 * 380_000_000L + 300_000_000L + 620_000_000L, p.shares[0].weightBytes)  // driver: layers, embedding, head
        assertEquals(16 * 380_000_000L + 1_000_000L, p.shares[2].weightBytes)                    // last phone: layers, output norm
        assertTrue(SplitPlanner.argsMatchPlan(p, args))
    }

    @Test fun aTiedHeadModelGetsNoOverride() {
        val p = SplitPlanner.plan(info(), devs(9000, 9000))
        assertFalse(p.headOnDriver)
        assertFalse(SplitPlanner.serverArgs(p, 50052).contains("-ot"))
    }

    @Test fun threePhoneFlagsPlaceEveryLayerWhereThePlanSays() {"""),
])
print("ok")
