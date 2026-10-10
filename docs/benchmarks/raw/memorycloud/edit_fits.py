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

edit(main, "MemCloudLogic.kt", [(
"""    /** Whether a new load may start from [phase]""", """    /** The sentence shown when the model would run on this phone alone: splitting it then costs speed and proves only the mechanism. */
    const val SPLIT_COSTS_SPEED = "This model fits on one phone. Splitting it is slower than running it alone; it is shown only to prove the mechanism."

    /** True when the whole file plus the planning overhead fits in what this phone can give ([haveMb], after its own servers stop). */
    fun fitsOnOnePhone(fileBytes: Long, haveMb: Long): Boolean = fileBytes / (1024 * 1024) + SplitPlanner.OVERHEAD_MB <= haveMb

    /** Whether a new load may start from [phase]""")])

edit(main, "MemCloudScreen.kt", [
("""        SectionTitle("Where the layers go")
""", """        if (MemCloudLogic.fitsOnOnePhone(info.fileBytes, ownAvail)) ErrorNote(MemCloudLogic.SPLIT_COSTS_SPEED)
        SectionTitle("Where the layers go")
"""),
("""    if (n > 0) LayerStrip(shareAssignments(st.shares, n), List(n) { true })
    SwarmTextField(prompt""", """    val alone = st.shares.firstOrNull()?.let { MemCloudLogic.fitsOnOnePhone(st.fileBytes ?: Long.MAX_VALUE, it.haveMb) } == true
    if (alone) {
        ErrorNote(MemCloudLogic.SPLIT_COSTS_SPEED)
        st.smallModel?.let { Text("Alone on this phone: ${tok(it.tokPerSec)} tok/s (${it.name}).", style = SwarmType.mono.copy(fontSize = 12.sp)) }
    }
    if (n > 0) LayerStrip(shareAssignments(st.shares, n), List(n) { true })
    SwarmTextField(prompt"""),
])

edit(test, "MemCloudLogicTest.kt", [(
"""    @Test fun aNewLoadIsRefusedWhileASplitIsServingOrDegraded() {""", """    @Test fun aModelThatFitsOnePhoneIsFlaggedAsSlowerWhenSplit() {
        assertTrue(MemCloudLogic.fitsOnOnePhone(2_740_937_888L, 9_000))     // 4B on a phone with 9 GB to give
        assertFalse(MemCloudLogic.fitsOnOnePhone(18_556_686_752L, 13_621))  // 30B needs 17.7 GB of weights alone
        assertTrue(MemCloudLogic.SPLIT_COSTS_SPEED.contains("slower"))
    }

    @Test fun aNewLoadIsRefusedWhileASplitIsServingOrDegraded() {"""),
])
# clearer failure reason when the stream dies without a message
edit(main, "MemCloud.kt", [(
"""if (culprit != null) degrade(culprit, "stream failed: ${e.message}")""", """if (culprit != null) degrade(culprit, "stream failed (${e.message ?: e.javaClass.simpleName})")""")])
print("ok")
