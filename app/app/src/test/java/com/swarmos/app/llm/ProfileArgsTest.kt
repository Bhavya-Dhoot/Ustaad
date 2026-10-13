package com.swarmos.app.llm

import com.swarmos.app.core.DeviceFixtures
import com.swarmos.app.core.DeviceFixtures.QWEN_30B
import com.swarmos.app.core.DeviceFixtures.QWEN_4B
import com.swarmos.app.core.DeviceState
import com.swarmos.app.core.ProfilePlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The device plan in the command lines and in the choice of the model to load at start. */
class ProfileArgsTest {
    private fun planOf(p: com.swarmos.app.core.DeviceProfile) = ProfilePlan.plan(p, ModelCatalog.entries)
    private val small = planOf(DeviceFixtures.phone8gb)
    private fun flag(args: List<String>, name: String) = args[args.lastIndexOf(name) + 1]
    private val embedFile = "embeddinggemma-300M-Q8_0.gguf"

    private fun present(vararg files: String) = files.map { PresentModel(it, 1_000, true) }

    private val cpu get() = LlamaArgs.build(Role.CHAT, "/m/q.gguf", 8080, allowedCores = 8, plan = small)

    @Test fun theSmallPlansThreadsReachTheCommandLines() {
        assertEquals("the plan's bound of 4 (6 fast cores, two kept free), not the 6 of the iQOO 15", "4", flag(cpu, "-t"))
        assertEquals("4", flag(LlamaArgs.build(Role.CHAT, "/m/q.gguf", 8080, backend = Backend.GPU, plan = small), "-t"))
        assertEquals("1", flag(LlamaArgs.build(Role.EMBED, "/m/e.gguf", 8081, plan = small), "-t"))
        assertEquals("the bound still leaves a core free in the background", 2, LlamaArgs.cpuChatThreads(3, maxThreads = 4))
    }

    @Test fun theSmallPlansContextReachesTheChatCommandLine() = assertEquals("4096", flag(cpu, "-c"))

    @Test fun theSmallPlansSlotsReachTheChatCommandLine() = assertEquals("1", flag(cpu, "-np"))

    @Test fun thePlansGpuLayersReachTheGpuCommandLine() {
        assertEquals("99", flag(LlamaArgs.build(Role.CHAT, "/m/q.gguf", 8080, backend = Backend.GPU, plan = small.copy(gpuLayers = 99)), "-ngl"))
        assertEquals("20", flag(LlamaArgs.build(Role.CHAT, "/m/q.gguf", 8080, backend = Backend.GPU, plan = small.copy(gpuLayers = 20)), "-ngl"))
    }

    @Test fun aCallersFlagsStillWinOverThePlan() {
        val a = LlamaArgs.build(Role.CHAT, "/m/q.gguf", 8080, extra = listOf("-c", "2048", "-np", "3"), allowedCores = 8, plan = small)
        assertEquals("2048", flag(a, "-c"))
        assertEquals("3", flag(a, "-np"))
        assertEquals("the slot count a started server was given is read from the last flag", 3, LlamaArgs.slots(a))
    }

    @Test fun withoutAPlanTheConstantsStay() {
        val a = LlamaArgs.build(Role.CHAT, "/m/q.gguf", 8080)
        assertEquals("6", flag(a, "-t"))
        assertEquals("8192", flag(a, "-c"))
        assertEquals("2", flag(a, "-np"))
        assertEquals(LlamaArgs.CHAT_SLOTS, LlamaArgs.slots(a))
    }

    @Test fun theAutomaticLoadWithoutAPlanIsTheCatalogDefaultAsBefore() {
        assertEquals(QWEN_4B, ModelStore.autoLoadChoice(Role.CHAT, present(QWEN_4B, QWEN_30B, embedFile), plan = null))
        assertEquals(embedFile, ModelStore.autoLoadChoice(Role.EMBED, present(QWEN_4B, embedFile), plan = null))
        assertNull(ModelStore.autoLoadChoice(Role.CHAT, present(QWEN_30B), plan = null))
    }

    @Test fun theAutomaticLoadTakesTheLargestPlannedModelThatIsOnThePhone() {
        val big = planOf(DeviceFixtures.desktop64)
        assertEquals(QWEN_30B, ModelStore.autoLoadChoice(Role.CHAT, present(QWEN_4B, QWEN_30B), big))
        assertEquals("the big one is not there: the next one that fits", QWEN_4B, ModelStore.autoLoadChoice(Role.CHAT, present(QWEN_4B), big))
        assertEquals("a half-downloaded file is not there", QWEN_4B,
            ModelStore.autoLoadChoice(Role.CHAT, listOf(PresentModel(QWEN_30B, 5, false), PresentModel(QWEN_4B, 5, true)), big))
        assertNull(ModelStore.autoLoadChoice(Role.CHAT, present(embedFile), big))
        assertEquals(embedFile, ModelStore.autoLoadChoice(Role.EMBED, present(embedFile), big))
    }

    @Test fun aClientLoadsNoChatModelButStillTheEmbedderAndSaysWhy() {
        val client = planOf(DeviceFixtures.phone4gb)
        val state = DeviceState(DeviceFixtures.phone4gb, client)
        assertNull(ModelStore.autoLoadChoice(Role.CHAT, present(QWEN_4B, embedFile), client))
        assertEquals("it still searches its files", embedFile, ModelStore.autoLoadChoice(Role.EMBED, present(QWEN_4B, embedFile), client))
        assertNull("but only when the file is there", ModelStore.autoLoadChoice(Role.EMBED, present(QWEN_4B), client))
        val note = ModelStore.noModelNote(Role.CHAT, state)!!
        assertTrue(note, note.contains("4 GB of memory"))
        assertTrue(note, note.contains("too little for the smallest chat model"))
        assertTrue(note, note.contains("4.6 GB free"))
        assertTrue(note, note.endsWith("It can still search its files and use the other devices."))
        assertNull("the embedder is loaded, so nothing to explain for it", ModelStore.noModelNote(Role.EMBED, state))
    }

    @Test fun aDeviceTooSmallEvenForTheEmbedderLoadsNothingAndSaysWhyForBoth() {
        val plan = planOf(DeviceFixtures.phone2gb)
        val state = DeviceState(DeviceFixtures.phone2gb, plan)
        assertNull(ModelStore.autoLoadChoice(Role.CHAT, present(QWEN_4B, embedFile), plan))
        assertNull(ModelStore.autoLoadChoice(Role.EMBED, present(QWEN_4B, embedFile), plan))
        val chat = ModelStore.noModelNote(Role.CHAT, state)!!
        assertTrue(chat, chat.contains("too small for the search model too"))
        val embed = ModelStore.noModelNote(Role.EMBED, state)!!
        assertTrue(embed, embed.contains("too little for the search model (it needs about 1.9 GB free)"))
    }

    @Test fun aPhoneThatHasModelsNeedsNoNoteAndBeforeTheFirstCheckThereIsNothingToSay() {
        val iqoo = DeviceState(DeviceFixtures.iqoo15, planOf(DeviceFixtures.iqoo15))
        assertNull(ModelStore.noModelNote(Role.CHAT, iqoo))
        assertNull(ModelStore.noModelNote(Role.EMBED, iqoo))
        assertNull(ModelStore.noModelNote(Role.CHAT, null))
    }
}
