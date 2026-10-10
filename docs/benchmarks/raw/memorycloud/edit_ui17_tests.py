import os
base = r"H:\UStaaf\.claude\worktrees\agent-ac34ad0bc1b4ddd46\app\app\src\test\java\com\swarmos\app\memcloud"

def edit(name, pairs):
    p = os.path.join(base, name)
    s = open(p, encoding="utf-8").read()
    for old, new in pairs:
        if old not in s:
            raise SystemExit("missing in %s: %s" % (name, old[:80]))
        s = s.replace(old, new, 1)
    open(p, "w", encoding="utf-8").write(s)

edit("MemCloudLogicTest.kt", [(
"""    @Test fun aNewLoadIsRefusedWhileASplitIsServingOrDegraded() {""", """    @Test fun pluralsAreRight() {
        assertEquals("1 phone", MemCloudLogic.plural(1, "phone"))
        assertEquals("2 phones", MemCloudLogic.plural(2, "phone"))
        assertEquals("0 tokens", MemCloudLogic.plural(0, "token"))
        assertEquals("1 layer", MemCloudLogic.plural(1, "layer"))
        assertEquals("Preparing a large model across 3 phones.", MemCloudLogic.activityText(MemCloudState(phase = Phase.PLANNING, shares = threeShares)))
    }

    @Test fun theLoadButtonNeedsAtLeastOneOtherPhone() {
        assertEquals("Pick at least one other phone" to false, MemCloudLogic.loadButton(0))
        assertEquals("Load across 2 phones" to true, MemCloudLogic.loadButton(1))
        assertEquals("Load across 3 phones" to true, MemCloudLogic.loadButton(2))
    }

    @Test fun laneCaptionsAreShortEnoughNotToClip() {
        assertEquals("this phone, driver", MemCloudLogic.laneRole(true))
        assertEquals("worker", MemCloudLogic.laneRole(false))
        assertTrue(MemCloudLogic.laneRole(true).length <= 20)
    }

    @Test fun theGgufNameIsASecondaryLineOnlyWhenItIsInformative() {
        assertNull(MemCloudLogic.secondaryName("gemma-4-E4B_q4_0-it.gguf", "Hf"))                       // too short to mean anything
        assertNull(MemCloudLogic.secondaryName("Qwen3.5-4B-Q4_K_M.gguf", "Qwen3.5-4B"))                // the file name already says it
        assertNull(MemCloudLogic.secondaryName("Qwen3-30B-A3B-Instruct-2507-Q4_K_M.gguf", "Qwen3 30B A3B Instruct 2507"))
        assertEquals("Mistral Small 3", MemCloudLogic.secondaryName("model-q4.gguf", "Mistral Small 3"))
        assertNull(MemCloudLogic.secondaryName("model.gguf", null))
    }

    @Test fun theSubtitleFollowsWhatThePlannerSaysAboutThisPhone() {
        assertEquals("A model too big for one phone, split by layers.", MemCloudLogic.subtitle(false))
        assertTrue(MemCloudLogic.subtitle(true).startsWith("Fits this phone; splitting it is slower"))
        assertFalse(MemCloudLogic.subtitle(null).contains("too big"))
        assertFalse(MemCloudLogic.subtitle(true).contains("too big"))
    }

    @Test fun theLayerStripPlanSaysNothingIsLoadedYet() {
        val d = MemCloudLogic.planDescription(threeShares)
        assertTrue(d.startsWith("Plan, no layer loaded yet"))
        assertTrue(d.contains("Phone B layers 16-31"))
        assertFalse(d.contains("loaded 48"))
    }

    @Test fun aNewLoadIsRefusedWhileASplitIsServingOrDegraded() {"""),
])

edit("SplitCoordinatorTest.kt", [(
"""    @Test fun theActivityLineBelongsToTheDriverRoleOnly() {""", """    @Test fun theBaselineFromAnIdlePhoneRunsTheOnePhoneAttemptAndSaysWhatHappened() {
        // no split has ever run: the button still does something, with an explicit file
        assertEquals("choose a model first", c.baseline(5))
        assertTrue(c.baseline(5, "missing.gguf")!!.contains("no such model file"))
        assertNull(c.baseline(5, "model.gguf"))
        val s = awaitState("baseline result") { it.phase == Phase.IDLE && it.baseline?.running == false }
        assertEquals("OK", s.baseline!!.outcome)
        assertEquals("model.gguf", s.file)
        assertEquals("no other phone is involved", 0, env.count("rpcStart"))
        awaitCall("this phone restored") { env.count("restoreLocal chat small.gguf") == 1 }
        assertTrue(env.calls.any { it.startsWith("startChat -ngl 0") })
    }

    @Test fun thePlanPreviewCanBeForThisPhoneAloneOrForEveryOnlinePhone() {
        assertEquals(3, c.previewPlan("model.gguf", emptyList(), SplitMode.EQUAL).shares.size)                           // empty = all online peers
        assertEquals(1, c.previewPlan("model.gguf", emptyList(), SplitMode.EQUAL, null, emptyMeansNone = true).shares.size) // every switch off
        assertEquals(2, c.previewPlan("model.gguf", listOf("Phone B"), SplitMode.EQUAL, null, emptyMeansNone = true).shares.size)
    }

    @Test fun theActivityLineBelongsToTheDriverRoleOnly() {"""),
])
print("ok")
