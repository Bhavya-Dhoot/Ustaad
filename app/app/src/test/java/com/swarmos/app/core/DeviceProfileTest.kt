package com.swarmos.app.core

import com.swarmos.app.core.DeviceFixtures.EMBED
import com.swarmos.app.core.DeviceFixtures.GEMMA
import com.swarmos.app.core.DeviceFixtures.QWEN_30B
import com.swarmos.app.core.DeviceFixtures.QWEN_4B
import com.swarmos.app.llm.Backend
import com.swarmos.app.llm.CatalogEntry
import com.swarmos.app.llm.LlamaArgs
import com.swarmos.app.llm.ModelCatalog
import com.swarmos.app.llm.Role
import com.swarmos.app.shell.deviceCheckLine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileTest {
    private val catalog = ModelCatalog.entries
    private fun plan(p: DeviceProfile) = ProfilePlan.plan(p, catalog)

    @After fun cleanUp() = DeviceProfiles.reset()

    // ---- the guard: the measured phones must not change behaviour -------------------------------------------------

    @Test fun theIqoo15GetsExactlyTodaysConstants() {
        val plan = plan(DeviceFixtures.iqoo15)
        assertEquals(listOf(QWEN_4B), plan.chatModels)
        assertEquals(QWEN_4B, plan.chatModel)
        assertTrue("GPU", plan.useGpu)
        assertEquals("CPU chat threads", 6, plan.chatThreads)
        assertEquals("GPU chat threads", 4, plan.gpuThreads)
        assertEquals("GPU layers", 99, plan.gpuLayers)
        assertEquals("embedding threads", 2, plan.embedThreads)
        assertEquals("context", 8192, plan.contextSize)
        assertEquals("slots", 2, plan.slots)
        assertEquals("scheduler capacity", 1, plan.schedCapacity)
        assertEquals("embedding model", EMBED, plan.embedModel)
        assertEquals("ms per token", 200L, plan.msPerToken)
        assertEquals(DeviceClass.STANDARD, plan.deviceClass)
    }

    @Test fun theIqoo15GuardAlsoHoldsWhenTheSystemHidesTheCoreFrequenciesOrTheMeasuredSpeedIsTheKnownOne() {
        val noFreq = plan(DeviceFixtures.iqoo15.copy(coreMaxKhz = emptyList()))
        assertEquals(6, noFreq.chatThreads) // 8 cores, frequencies unknown: all count
        assertEquals(plan(DeviceFixtures.iqoo15), noFreq)
        val measured = plan(DeviceFixtures.iqoo15.copy(chatTokPerSec = 13.5, chatTokPerSecModel = QWEN_4B))
        assertEquals("13.5 tokens/s is the measured 4B decode: the floor of 200 ms stays", 200L, measured.msPerToken)
    }

    @Test fun theIqoo15CommandLinesAreTheSameWithAndWithoutThePlan() {
        val plan = plan(DeviceFixtures.iqoo15)
        for ((role, backend) in listOf(Role.CHAT to Backend.GPU, Role.CHAT to Backend.CPU, Role.EMBED to Backend.CPU)) {
            assertEquals(
                "$role on $backend",
                LlamaArgs.build(role, "/m/x.gguf", 8080, backend = backend, apiKey = "k", allowedCores = 8),
                LlamaArgs.build(role, "/m/x.gguf", 8080, backend = backend, apiKey = "k", allowedCores = 8, plan = plan),
            )
        }
        // and the background case, where the app may use fewer cores
        assertEquals(
            LlamaArgs.build(Role.CHAT, "/m/x.gguf", 8080, apiKey = "k", allowedCores = 4),
            LlamaArgs.build(Role.CHAT, "/m/x.gguf", 8080, apiKey = "k", allowedCores = 4, plan = plan),
        )
    }

    // ---- the four device fixtures ---------------------------------------------------------------------------------

    @Test fun anEightGigabytePhoneWithAGpuRunsTheSmallModelWithOneSlotAndCanLend() {
        val p = plan(DeviceFixtures.phone8gb)
        assertEquals(DeviceClass.SMALL, p.deviceClass)
        assertEquals(listOf(QWEN_4B), p.chatModels)
        assertTrue(p.useGpu)
        assertEquals("6 of the 8 cores run at 70 % or more of the fastest (the two little ones, at 69 %, do not count), two stay free", 4, p.chatThreads)
        assertEquals(4, p.gpuThreads)
        assertEquals(1, p.embedThreads)
        assertEquals(4096, p.contextSize)
        assertEquals(1, p.slots)
        assertEquals(1, p.schedCapacity)
        assertTrue(p.mayLend)
        assertEquals("65 % of 7,500 MB is 4,875; minus the split planner's 1,536", 3_339L, p.lendMaxMb)
        assertEquals("10 % of 40,000 MB", 4_000L, p.storageOfferMb)
        assertEquals(200L, p.msPerToken)
    }

    @Test fun aFourGigabytePhoneWithoutTheGpuBuildIsAClientThatStillLoadsTheEmbedder() {
        val p = plan(DeviceFixtures.phone4gb)
        assertEquals(DeviceClass.CLIENT, p.deviceClass)
        assertTrue(p.isClient)
        assertEquals(emptyList<String>(), p.chatModels)
        assertNull(p.chatModel)
        assertEquals("it still searches: the embedder fits (1,917 MB under the load rule, 2,340 MB usable)", EMBED, p.embedModel)
        assertEquals(1_917L, p.embedNeedMb)
        assertFalse(p.useGpu)
        assertEquals("no chat model, no capacity", 0, p.schedCapacity)
        assertFalse(p.mayLend)
        assertEquals("804 MB over the reserve is below the 2 GB a lender must have", 0L, p.lendMaxMb)
        assertEquals("10 % of 8,000 MB is under the 1 GB minimum", 1_024L, p.storageOfferMb)
        assertEquals("what the smallest chat model needs, so the status can say it", 4_672L, p.smallestChatNeedMb)
    }

    @Test fun aDeviceWhereEvenTheEmbedderDoesNotFitLoadsNoModelAtAll() {
        val p = plan(DeviceFixtures.phone2gb)
        assertTrue(p.isClient)
        assertNull(p.embedModel)
        assertEquals(0, p.schedCapacity)
        // the embedder (333,590,944 bytes) needs 2,010,921,868 bytes under the load rule, i.e. 1,918 MB: 65 % of 2,951 MB is 1,918, of 2,950 MB is 1,917
        assertEquals(EMBED, plan(DeviceFixtures.phone4gb.copy(totalRamMb = 2_951)).embedModel)
        assertNull(plan(DeviceFixtures.phone4gb.copy(totalRamMb = 2_950)).embedModel)
    }

    @Test fun aThirtyTwoGigabyteDesktopRunsTheDefaultModelOnTheCpuWithMoreThreadsAndLends() {
        val p = plan(DeviceFixtures.desktop32)
        assertEquals(DeviceClass.LARGE, p.deviceClass)
        assertEquals("21.7 GB usable is just under the 23.9 GB the 30B model needs", listOf(QWEN_4B), p.chatModels)
        assertFalse("no GPU binary", p.useGpu)
        assertEquals("14 fast cores minus two, capped at 8", 8, p.chatThreads)
        assertEquals(2, p.embedThreads)
        assertEquals(8192, p.contextSize)
        assertEquals(2, p.slots)
        assertEquals(1, p.schedCapacity)
        assertEquals(19_199L, p.lendMaxMb)
        assertEquals("10 % of 500,000 MB", 50_000L, p.storageOfferMb)
    }

    @Test fun aSixtyFourGigabyteDesktopPrefersTheBigModelAndNeverThePhonesOnlyChatModel() {
        val p = plan(DeviceFixtures.desktop64)
        assertEquals("largest first, so the first one on the disk is loaded", listOf(QWEN_30B, QWEN_4B), p.chatModels)
        assertFalse("Gemma 4 E4B returns nothing for JSON-schema calls: never picked on its own", GEMMA in p.chatModels)
        assertEquals(QWEN_30B, p.chatModel)
    }

    // ---- older Android (API 26): the plan reads memory, cores and the GPU, never the system version or the chip name ---------

    @Test fun aSixGigabytePhoneOnAndroid8IsAClientThatSearchesLendsAndOffersStorage() {
        val p = plan(DeviceFixtures.android8_6gb)
        assertEquals("65 % of 5,600 MB is 3,640, below the 4,672 MB the 4B model needs", DeviceClass.CLIENT, p.deviceClass)
        assertEquals(emptyList<String>(), p.chatModels)
        assertEquals(EMBED, p.embedModel)
        assertFalse("no OpenCL library and no probe", p.useGpu)
        assertEquals(0, p.schedCapacity)
        assertTrue("3,640 minus the split planner's 1,536 is 2,104, over the 2 GB a lender must have", p.mayLend)
        assertEquals(2_104L, p.lendMaxMb)
        assertEquals("10 % of 20,000 MB", 2_000L, p.storageOfferMb)
        assertEquals(4_672L, p.smallestChatNeedMb)
        assertEquals(200L, p.msPerToken)
        assertEquals(
            "6 GB memory: too little for a chat model, so this phone asks the other phones to compute and still stores and searches files.",
            deviceCheckLine(DeviceState(DeviceFixtures.android8_6gb, p), checking = false),
        )
    }

    @Test fun aThreeGigabytePhoneOnAndroid8LoadsNoModelButStillStoresAndAsks() {
        val p = plan(DeviceFixtures.android8_3gb)
        assertTrue(p.isClient)
        assertNull("65 % of 2,800 MB is 1,820, below the 1,917 MB the embedder needs", p.embedModel)
        assertEquals(emptyList<String>(), p.chatModels)
        assertEquals(0, p.schedCapacity)
        assertFalse(p.mayLend)
        assertEquals(0L, p.lendMaxMb)
        assertEquals("10 % of 6,000 MB is under the 1 GB minimum", 1_024L, p.storageOfferMb)
        assertEquals("hidden core frequencies: all eight cores count, minus two", 6, p.chatThreads)
        assertEquals(
            "3 GB memory: too little to run any model here, so this phone uses the other phones and stores files.",
            deviceCheckLine(DeviceState(DeviceFixtures.android8_3gb, p), checking = false),
        )
    }

    @Test fun theSystemVersionAndTheChipNameDoNotChangeThePlan() {
        for (base in listOf(DeviceFixtures.android8_6gb, DeviceFixtures.android8_3gb)) {
            val newer = base.copy(osLevel = 36, chip = "SM8850")
            assertEquals(plan(base), plan(newer))
            assertEquals(plan(base), plan(base.copy(osLevel = 0, chip = "")))
        }
    }

    @Test fun theChipNameIsTheSocModelThenTheBoardNameThenUnknown() {
        assertEquals("SM8850", DeviceProfile.chipName("SM8850", "qcom"))
        assertEquals("API 31 and up with a blank model: the board name", "qcom", DeviceProfile.chipName("", "qcom"))
        assertEquals("the system's own word for no answer", "qcom", DeviceProfile.chipName("unknown", "qcom"))
        assertEquals("below API 31 there is no model to read", "mt6762", DeviceProfile.chipName(null, "mt6762"))
        assertEquals("unknown", DeviceProfile.chipName(null, "unknown"))
        assertEquals("unknown", DeviceProfile.chipName(" ", ""))
    }

    // ---- single rules ---------------------------------------------------------------------------------------------

    @Test fun aModelFitsWhenTheLoadRuleIsMetBySixtyFivePercentOfTheTotalMemory() {
        // the 4B file needs 4,899,738,201 bytes under the load rule, i.e. 4,673 MB: 65 % of 7,190 MB is 4,673, of 7,189 MB is 4,672
        val at = { total: Long -> plan(DeviceFixtures.phone8gb.copy(totalRamMb = total)).chatModels }
        assertEquals(listOf(QWEN_4B), at(7_190))
        assertEquals(emptyList<String>(), at(7_189))
        assertEquals(listOf(QWEN_4B), at(7_500))
    }

    @Test fun theClassFollowsTheMemoryAndWhetherAModelFits() {
        fun cls(mb: Long) = plan(DeviceFixtures.desktop32.copy(totalRamMb = mb)).deviceClass
        assertEquals(DeviceClass.CLIENT, cls(5_000))
        assertEquals(DeviceClass.SMALL, cls(10_239))
        assertEquals(DeviceClass.STANDARD, cls(10_240))
        assertEquals(DeviceClass.STANDARD, cls(24_575))
        assertEquals(DeviceClass.LARGE, cls(24_576))
        assertTrue(DeviceClass.entries.all { it.meaning.isNotBlank() })
    }

    @Test fun theStorageOfferIsATenthAtLeastOneGigabyteAndNeverMoreThanHalf() {
        assertEquals(20_000L, ProfilePlan.storageOfferMb(200_000))
        assertEquals("below 10 GB free the 1 GB minimum applies", 1_024L, ProfilePlan.storageOfferMb(5_000))
        assertEquals("but never more than half", 750L, ProfilePlan.storageOfferMb(1_500))
        assertEquals(1_024L, ProfilePlan.storageOfferMb(2_048))
        assertEquals(0L, ProfilePlan.storageOfferMb(0))
        assertEquals(10_240L, ProfilePlan.storageOfferMb(102_400))
    }

    @Test fun theBudgetPerTokenFollowsTheMeasuredSpeedWithAFloorOfTwoHundred() {
        fun ms(tps: Double?) = plan(DeviceFixtures.iqoo15.copy(chatTokPerSec = tps)).msPerToken
        assertEquals(200L, ms(null))
        assertEquals(200L, ms(30.0))
        assertEquals("a phone at 5 tokens/s: 2.5 times slower than measured is allowed", 500L, ms(5.0))
        assertEquals(1_250L, ms(2.0))
    }

    @Test fun theGpuIsPlannedOnlyWhenTheBinaryIsThereAndTheProbeOrTheLibraryAllowsIt() {
        fun gpu(binary: Boolean = true, openCl: Boolean = true, probe: GpuProbe? = null) =
            plan(DeviceFixtures.iqoo15.copy(gpuBinaryPresent = binary, openClPresent = openCl, gpuProbe = probe)).useGpu
        assertTrue(gpu(probe = DeviceFixtures.gpuListed))
        assertFalse("no binary", gpu(binary = false, probe = DeviceFixtures.gpuListed))
        val none = GpuProbe(ProbeVerdict.NO_GPU, "none")
        assertTrue("NO_GPU alone does not switch a GPU off while the library is there: the trial at load decides, as before the probe", gpu(probe = none))
        assertFalse("NO_GPU and no library: CPU, no trial", gpu(openCl = false, probe = none))
        assertTrue("a probe that gave no answer does not count against the GPU", gpu(probe = GpuProbe(ProbeVerdict.NO_ANSWER, "slow")))
        assertTrue("no probe: the library decides", gpu(probe = null))
        assertFalse("no probe and no library", gpu(openCl = false, probe = null))
        assertFalse("no answer and no library", gpu(openCl = false, probe = GpuProbe(ProbeVerdict.NO_ANSWER, "slow")))
        assertTrue("the probe's word beats the file check, which an app may be unable to make", gpu(openCl = false, probe = DeviceFixtures.gpuListed))
        val clientWithGpu = plan(DeviceFixtures.phone4gb.copy(gpuBinaryPresent = true, openClPresent = true))
        assertTrue("a client is a fact about memory: a model it is given by hand still gets the GPU build", clientWithGpu.isClient && clientWithGpu.useGpu)
    }

    @Test fun aCatalogWithoutAPinnedSizeOrWithOnlyChatOnlyModelsMakesEveryDeviceAClient() {
        val chatOnly = CatalogEntry(Role.CHAT, "g.gguf", "G", "u", 5_000_000_000L, "", structuredOutput = false)
        val unpinned = CatalogEntry(Role.CHAT, "u.gguf", "U", "u", 0L, "")
        val embed = CatalogEntry(Role.EMBED, "e.gguf", "E", "u", 300_000_000L, "")
        val p = ProfilePlan.plan(DeviceFixtures.desktop64, listOf(chatOnly, unpinned, embed))
        assertTrue(p.isClient)
        assertEquals(0L, p.smallestChatNeedMb)
        assertEquals("the embedder is judged on its own", "e.gguf", p.embedModel)
    }

    @Test fun nominalMemoryIsTheSizeThePhoneIsSoldAs() {
        assertEquals(16, nominalRamGb(15_218))
        assertEquals(8, nominalRamGb(7_500))
        assertEquals(4, nominalRamGb(3_600))
        assertEquals(12, nominalRamGb(11_200))
        assertEquals(32, nominalRamGb(31_900))
        assertEquals(200, nominalRamGb(200 * 1024L))
    }

    // ---- the GPU probe's reading of `llama-server --list-devices` ---------------------------------------------------

    @Test fun theProbeReadsTheListingTheLinkerErrorAndTheSilences() {
        fun parse(out: String, code: Int? = 0, timedOut: Boolean = false) = GpuProbe.parse(out, code, timedOut)
        val listing = "ggml_opencl: selected platform: 'QUALCOMM'\nAvailable devices:\n  GPUOpenCL: QUALCOMM Adreno(TM) 840 (11000 MiB, 11000 MiB free)\n"
        assertEquals(ProbeVerdict.GPU_LISTED, parse(listing).verdict)
        assertEquals("GPUOpenCL: QUALCOMM Adreno(TM) 840 (11000 MiB, 11000 MiB free)", parse(listing).note)
        assertEquals("a header and nothing under it", ProbeVerdict.NO_GPU, parse("Available devices:\n").verdict)
        assertEquals("the library is missing", ProbeVerdict.NO_GPU, parse("CANNOT LINK EXECUTABLE \"x\": library \"libOpenCL.so\" not found", code = 1).verdict)
        assertEquals("a non-zero exit", ProbeVerdict.NO_GPU, parse("error: invalid argument", code = 1).verdict)
        assertEquals("the backend's own log names an Adreno even if the listing format changed", ProbeVerdict.GPU_LISTED,
            parse("ggml_opencl: device: 'QUALCOMM Adreno(TM) 840'\nsome other format\n").verdict)
        assertEquals("too slow says nothing", ProbeVerdict.NO_ANSWER, parse("", code = null, timedOut = true).verdict)
        assertEquals("killed by a signal says nothing", ProbeVerdict.NO_ANSWER, parse("", code = 137).verdict)
        assertEquals("exit 0 and nothing recognisable", ProbeVerdict.NO_ANSWER, parse("hello").verdict)
    }

    // ---- storing, re-checking, the speed kept across restarts -------------------------------------------------------

    @Test fun aProfileSurvivesTheJsonRoundTripAndAnOlderFileReadsWithDefaults() {
        val p = DeviceFixtures.iqoo15.copy(chatTokPerSec = 13.5, chatTokPerSecModel = QWEN_4B)
        assertEquals(p, SwarmJson.decodeFromString(DeviceProfile.serializer(), SwarmJson.encodeToString(DeviceProfile.serializer(), p)))
        val minimal = SwarmJson.decodeFromString(DeviceProfile.serializer(), """{"totalRamMb":7500,"cores":8,"somethingNew":1}""")
        assertEquals(7500L, minimal.totalRamMb)
        assertTrue(minimal.coreMaxKhz.isEmpty())
        assertNull(minimal.gpuProbe)
        assertNull(minimal.chatTokPerSec)
    }

    @Test fun theFactsAreMeasuredAgainOnlyForANewInstallANewSchemaOrNoFile() {
        val p = DeviceFixtures.iqoo15
        assertFalse(DeviceProfiles.needsCheck(p, "t"))
        assertTrue("an app update", DeviceProfiles.needsCheck(p, "t2"))
        assertTrue("another schema", DeviceProfiles.needsCheck(p.copy(schema = 0), "t"))
        assertTrue("nothing stored", DeviceProfiles.needsCheck(null, "t"))
    }

    @Test fun theSpeedIsTakenOnceAMinuteSmoothedAndRestartedWhenTheModelChanges() {
        val saved = mutableListOf<DeviceProfile>()
        DeviceProfiles.set(DeviceFixtures.iqoo15)
        DeviceProfiles.save = { saved += it }
        DeviceProfiles.noteSpeed(QWEN_4B, 10.0, nowMs = 1_000_000)
        assertEquals("the first figure is taken as it is", 10.0, DeviceProfiles.current!!.profile.chatTokPerSec!!, 1e-9)
        DeviceProfiles.noteSpeed(QWEN_4B, 20.0, nowMs = 1_030_000)
        assertEquals("30 s later: not taken", 10.0, DeviceProfiles.current!!.profile.chatTokPerSec!!, 1e-9)
        DeviceProfiles.noteSpeed(QWEN_4B, 20.0, nowMs = 1_060_000)
        assertEquals("a minute later: 30 % of the way", 13.0, DeviceProfiles.current!!.profile.chatTokPerSec!!, 1e-9)
        DeviceProfiles.noteSpeed(QWEN_30B, 3.0, nowMs = 1_200_000)
        assertEquals("another model starts again", 3.0, DeviceProfiles.current!!.profile.chatTokPerSec!!, 1e-9)
        assertEquals(QWEN_30B, DeviceProfiles.current!!.profile.chatTokPerSecModel)
        assertEquals("saved each time it changed", 3, saved.size)
        assertEquals(13.0, saved[1].chatTokPerSec!!, 1e-9)
        assertEquals("and the plan follows the new figure", 834L, DeviceProfiles.current!!.plan.msPerToken)
    }

    @Test fun aSpeedWithoutAProfileOrOfNothingIsIgnored() {
        DeviceProfiles.noteSpeed(QWEN_4B, 10.0, nowMs = 5_000_000)
        assertNull(DeviceProfiles.current)
        DeviceProfiles.set(DeviceFixtures.iqoo15)
        DeviceProfiles.noteSpeed(QWEN_4B, 0.0, nowMs = 6_000_000)
        assertNull(DeviceProfiles.current!!.profile.chatTokPerSec)
    }
}
