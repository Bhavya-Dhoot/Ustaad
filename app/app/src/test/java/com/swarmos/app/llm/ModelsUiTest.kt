package com.swarmos.app.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** The state-to-UI mapping of the Models tab and the benchmark page: which button is primary, what shows, how it is labelled. */
class ModelsUiTest {
    private val qwen = "/data/models/Qwen3.5-4B-Q4_K_M.gguf"
    private val gemma = "/data/models/embeddinggemma-300M-Q8_0.gguf"

    private fun st(role: Role, state: ServerState, path: String? = null, restarts: Int = 0) =
        ServerStatus(role, state, modelPath = path, restarts = restarts)

    private val chatEntry = CatalogEntry(Role.CHAT, "Qwen3.5-4B-Q4_K_M.gguf", "Qwen3.5-4B", "https://x/y", 2_000L, "", default = true)
    private val embedEntry = CatalogEntry(Role.EMBED, "embeddinggemma-300M-Q8_0.gguf", "EmbeddingGemma", "https://x/z", 300L, "", default = true)
    private fun complete(name: String, bytes: Long = 2_000L) = PresentModel(name, bytes, complete = true)

    // ---- header: the one primary button ----

    @Test fun nothingLoadedMakesLoadModelsThePrimaryAndBenchmarkADisabledSecondaryWithAReason() {
        for (s in listOf(ServerState.STOPPED, ServerState.FAILED)) {
            val h = headerUi(s, benchBlockedReason = null, benchRunning = false)
            assertEquals(PrimaryAction.LoadModels, h.primary)
            assertTrue(h.primaryEnabled)
            assertTrue(h.showDisabledBench)
            assertNotNull(h.benchReason)
            assertEquals("Load models", primaryText(h.primary))
        }
    }

    @Test fun whileTheChatModelLoadsThePrimaryIsDisabledAndSaysLoading() {
        val h = headerUi(ServerState.STARTING, null, false)
        assertEquals(PrimaryAction.Loading, h.primary)
        assertFalse(h.primaryEnabled)
        assertTrue(h.showDisabledBench)
        assertEquals("Loading", primaryText(h.primary))
    }

    @Test fun readyChatMakesRunBenchmarkThePrimary() {
        val h = headerUi(ServerState.READY, null, false)
        assertEquals(PrimaryAction.RunBenchmark, h.primary)
        assertTrue(h.primaryEnabled)
        assertFalse(h.showDisabledBench)
        assertNull(h.benchReason)
    }

    @Test fun heatOrARunningBenchmarkDisablesTheReadyPrimaryWithTheReason() {
        val hot = headerUi(ServerState.READY, "Phone is Severe. Benchmark is off until it cools to Hot or lower.", false)
        assertEquals(PrimaryAction.RunBenchmark, hot.primary)
        assertFalse(hot.primaryEnabled)
        assertEquals("Phone is Severe. Benchmark is off until it cools to Hot or lower.", hot.benchReason)
        assertFalse(headerUi(ServerState.READY, null, benchRunning = true).primaryEnabled)
    }

    @Test fun loadModelsStartsOnlyTheRolesThatAreNotRunningOrLoading() {
        assertEquals(listOf(Role.CHAT, Role.EMBED), rolesToLoad(ServerState.STOPPED, ServerState.STOPPED))
        assertEquals(listOf(Role.CHAT, Role.EMBED), rolesToLoad(ServerState.FAILED, ServerState.FAILED))
        assertEquals(listOf(Role.EMBED), rolesToLoad(ServerState.READY, ServerState.STOPPED))
        assertEquals(listOf(Role.CHAT), rolesToLoad(ServerState.STOPPED, ServerState.STARTING))
        assertTrue(rolesToLoad(ServerState.READY, ServerState.READY).isEmpty())
        assertTrue(rolesToLoad(ServerState.STARTING, ServerState.STARTING).isEmpty())
    }

    @Test fun aSecondaryLoadEmbedderAppearsOnlyForReadyChatWithoutEmbedder() {
        assertTrue(embedderMissing(ServerState.READY, ServerState.STOPPED))
        assertTrue(embedderMissing(ServerState.READY, ServerState.FAILED))
        assertFalse(embedderMissing(ServerState.READY, ServerState.STARTING))
        assertFalse(embedderMissing(ServerState.READY, ServerState.READY))
        assertFalse(embedderMissing(ServerState.STOPPED, ServerState.STOPPED)) // there the primary loads both
    }

    // ---- server rows: one FAKE stamp at most ----

    @Test fun aScreenWithNothingLoadedHasExactlyOneFakeStamp() {
        val all = mapOf(
            Role.CHAT to st(Role.CHAT, ServerState.STOPPED),
            Role.EMBED to st(Role.EMBED, ServerState.STOPPED),
            Role.RPC to st(Role.RPC, ServerState.STOPPED),
        )
        assertEquals(1, fakeStampCount(all))
        assertEquals(RowMark.FAKE, rowMark(Role.CHAT, all.getValue(Role.CHAT)))
        assertEquals(RowMark.STOPPED, rowMark(Role.EMBED, all.getValue(Role.EMBED)))
    }

    @Test fun noFakeStampOnceTheChatModelRunsOrIsLoading() {
        for (state in listOf(ServerState.READY, ServerState.STARTING, ServerState.FAILED)) {
            val all = mapOf(Role.CHAT to st(Role.CHAT, state), Role.EMBED to st(Role.EMBED, ServerState.STOPPED))
            assertEquals("state $state", 0, fakeStampCount(all))
        }
    }

    @Test fun startingRowsSayLoadingOrRestarting() {
        assertEquals(RowMark.LOADING, rowMark(Role.CHAT, st(Role.CHAT, ServerState.STARTING)))
        assertEquals(RowMark.RESTARTING, rowMark(Role.CHAT, st(Role.CHAT, ServerState.STARTING, restarts = 1)))
        assertEquals(RowMark.READY, rowMark(Role.EMBED, st(Role.EMBED, ServerState.READY)))
        assertEquals(RowMark.FAILED, rowMark(Role.EMBED, st(Role.EMBED, ServerState.FAILED)))
    }

    @Test fun backendIsShownForEveryRunningServerAndNeverForAStoppedOne() {
        assertEquals("GPU", backendLabel(ServerStatus(Role.CHAT, ServerState.READY, backend = Backend.GPU)))
        assertEquals("CPU", backendLabel(ServerStatus(Role.CHAT, ServerState.READY, backend = Backend.CPU)))
        assertEquals("CPU", backendLabel(st(Role.EMBED, ServerState.READY, gemma))) // the embedder always runs on the CPU build
        assertNull(backendLabel(st(Role.CHAT, ServerState.STOPPED)))
    }

    @Test fun loadingShowsElapsedSeconds() {
        assertEquals("Loading, 0 s", loadingLabel(400, restarting = false))
        assertEquals("Loading, 7 s", loadingLabel(7_900, restarting = false))
        assertEquals("Restarting, 12 s", loadingLabel(12_000, restarting = true))
        assertEquals("Loading, 0 s", loadingLabel(-5, restarting = false)) // clock skew never prints a negative time
    }

    // ---- measured speed: shown only when measured ----

    @Test fun loadTimingsGiveRealNumbersAndTheCaptionSaysWhenAndOnWhat() {
        val s = SpeedSample.fromStats("Qwen3.5-4B-Q4_K_M", SpeedSource.LOAD, 1_790_000_000_000L, ChatStats(14, 8, 140.0, 590.0))
        assertEquals(100.0, s.prefillTokPerSec!!, 1e-9)
        assertEquals(8 * 1000.0 / 590.0, s.decodeTokPerSec!!, 1e-9)
        val metrics = speedMetrics(Role.CHAT, s)
        assertEquals(listOf("prefill", "decode"), metrics.map { it.first })
        assertEquals("100.0 tok/s", metrics[0].second)
        val caption = measuredCaption(s, ZoneOffset.UTC)
        assertTrue(caption, caption.startsWith("Measured at load on the first request (14 prompt tokens, 8 generated), "))
        assertTrue(caption, caption.endsWith("."))
    }

    @Test fun aNumberThatWasNotMeasuredIsLeftOutNotShownAsADash() {
        // one generated token or no prompt timing: no decode / prefill figure at all
        val tooShort = SpeedSample.fromStats("m", SpeedSource.LOAD, 0L, ChatStats(0, 1, 0.0, 30.0))
        assertNull(tooShort.prefillTokPerSec)
        assertNull(tooShort.decodeTokPerSec)
        assertTrue(speedMetrics(Role.CHAT, tooShort).isEmpty())
        assertTrue(speedMetrics(Role.CHAT, null).isEmpty())
        val onlyPrefill = SpeedSample.fromStats("m", SpeedSource.LOAD, 0L, ChatStats(10, 0, 100.0, 0.0))
        assertEquals(listOf("prefill"), speedMetrics(Role.CHAT, onlyPrefill).map { it.first })
    }

    @Test fun embedderShowsLatencyOnlyOnceMeasured() {
        assertTrue(speedMetrics(Role.EMBED, null).isEmpty())
        val s = SpeedSample("embeddinggemma-300M-Q8_0", SpeedSource.LOAD, 0L, embedMsPerText = 26.04)
        assertEquals(listOf("latency" to "26.0 ms per text"), speedMetrics(Role.EMBED, s))
        assertTrue(speedMetrics(Role.CHAT, s).isEmpty()) // a chat row never borrows the embedder's number
    }

    @Test fun benchmarkAndRequestSamplesAreLabelledAsSuch() {
        val bench = SpeedSample("m", SpeedSource.BENCH, 0L, 512, 128, 200.0, 17.0)
        assertTrue(measuredCaption(bench, ZoneOffset.UTC).startsWith("Measured by the benchmark (512 prompt tokens, 128 generated), "))
        val req = SpeedSample("m", SpeedSource.REQUEST, 0L, embedMsPerText = 20.0)
        assertTrue(measuredCaption(req, ZoneOffset.UTC).startsWith("Measured on the latest request, "))
    }

    @Test fun clockUsesTheGivenZone() {
        assertEquals("00:00", clock(0L, ZoneOffset.UTC))
        assertEquals("05:30", clock(0L, ZoneOffset.ofHoursMinutes(5, 30)))
    }

    @Test fun aSampleOnlyCountsWhileItsServerIsReadyWithThatModel() {
        val s = SpeedSample("Qwen3.5-4B-Q4_K_M", SpeedSource.LOAD, 0L, 10, 8, 100.0, 15.0)
        assertEquals(s, speedFor(st(Role.CHAT, ServerState.READY, qwen), s))
        assertNull(speedFor(st(Role.CHAT, ServerState.STARTING, qwen), s)) // still loading
        assertNull(speedFor(st(Role.CHAT, ServerState.STOPPED), s))         // unloaded: the numbers are gone
        assertNull(speedFor(st(Role.CHAT, ServerState.READY, "/data/models/other-model.gguf"), s)) // another model took its place
        assertNull(speedFor(st(Role.CHAT, ServerState.READY, qwen), null))
    }

    // ---- file cards: one action ----

    @Test fun catalogFilesOfferOneLoadForTheirOwnRole() {
        val chat = cardAction(chatEntry, complete(chatEntry.file), null, loadedAs = null)
        assertEquals(CardAction.Load(Role.CHAT), chat)
        assertEquals("Load as chat", cardActionLabel(chat, 0))
        val emb = cardAction(embedEntry, complete(embedEntry.file, 300L), null, loadedAs = null)
        assertEquals(CardAction.Load(Role.EMBED), emb)
        assertEquals("Load as embedder", cardActionLabel(emb, 0))
    }

    @Test fun anUnknownFileOffersOneLoadWithAChooser() {
        val a = cardAction(null, complete("mystery.gguf"), null, null)
        assertEquals(CardAction.Choose, a)
        assertEquals("Load", cardActionLabel(a, 0))
    }

    @Test fun aLoadedFileOffersNoLoadAtAll() {
        assertEquals(CardAction.None, cardAction(chatEntry, complete(chatEntry.file), null, loadedAs = Role.CHAT))
        assertNull(cardActionLabel(CardAction.None, 0))
    }

    @Test fun anAbsentOrIncompleteCatalogFileOffersDownloadOrResume() {
        val absent = cardAction(chatEntry, null, null, null)
        assertEquals(CardAction.Download(resume = false), absent)
        assertEquals("Download 1000 B", cardActionLabel(absent, 1000))
        val partial = cardAction(chatEntry, PresentModel(chatEntry.file, 500L, complete = false), null, null)
        assertEquals(CardAction.Download(resume = true), partial)
        assertEquals("Resume download", cardActionLabel(partial, 1000))
        // an unknown incomplete file has nowhere to download from
        assertEquals(CardAction.None, cardAction(null, PresentModel("x.gguf", 5L, complete = false), null, null))
    }

    @Test fun whileDownloadingTheCardOffersNothingExceptItsOwnCancel() {
        assertEquals(CardAction.None, cardAction(chatEntry, null, DownloadState.Downloading(1, 2, 3), null))
        assertEquals(CardAction.None, cardAction(chatEntry, null, DownloadState.Verifying, null))
        // a failed download can be tried again
        assertEquals(CardAction.Download(resume = false), cardAction(chatEntry, null, DownloadState.Failed("timeout"), null))
    }

    @Test fun deleteIsOfferedOnlyForAFileNoServerUses() {
        assertTrue(canDelete(complete("a.gguf"), loadedAs = null))
        assertFalse(canDelete(complete("a.gguf"), loadedAs = Role.CHAT))
        assertFalse(canDelete(null, null))
    }

    // ---- benchmark page ----

    private fun result(model: String = "Qwen3.5-4B-Q4_K_M") =
        BenchResult(model, 512, 128, 206.3, 17.0, 2500.0, 1040, 1141, "NONE", "NONE", 4, 1_790_000_000_000L, "GPU")

    @Test fun benchViewIsLiveOnlyWhileTheSameModelRuns() {
        assertEquals(BenchView.NONE, benchView(st(Role.CHAT, ServerState.READY, qwen), null))
        assertEquals(BenchView.LIVE, benchView(st(Role.CHAT, ServerState.READY, qwen), result()))
        assertEquals(BenchView.LAST_RUN, benchView(st(Role.CHAT, ServerState.STOPPED), result()))
        assertEquals(BenchView.LAST_RUN, benchView(st(Role.CHAT, ServerState.STARTING, qwen), result()))
        assertEquals(BenchView.LAST_RUN, benchView(st(Role.CHAT, ServerState.READY, "/data/models/other.gguf"), result()))
    }

    @Test fun lastRunCaptionNamesTheModelAndTheTime() {
        val c = lastRunCaption(result(), ZoneOffset.UTC)
        assertTrue(c, c.startsWith("Qwen3.5-4B-Q4_K_M, measured "))
        assertTrue(c, c.endsWith("This model is not loaded now."))
    }

    @Test fun thermalRowsUseTheSharedWordsNeverTheRawNames() {
        assertEquals("Cool", thermalWord("NONE"))
        assertEquals("Warm", thermalWord("LIGHT"))
        assertEquals("Hot", thermalWord("MODERATE"))
        assertEquals("Severe", thermalWord("SEVERE"))
        assertEquals("Severe", thermalWord("CRITICAL"))
        assertEquals("Severe", thermalWord("emergency"))
        assertEquals("?", thermalWord("?")) // an unknown value is passed through, not invented
    }
}
