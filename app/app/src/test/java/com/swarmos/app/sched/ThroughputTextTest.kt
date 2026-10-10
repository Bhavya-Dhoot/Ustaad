package com.swarmos.app.sched

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThroughputTextTest {
    private fun report(
        agg: Double,
        models: List<String> = listOf("Qwen3.5-4B"),
        state: String = "done",
        mode: String = "swarm",
    ) = BatchReport(
        id = "b", mode = mode, state = state, total = 32, done = 32, failed = 0, wallMs = 10_000.0,
        totalTokens = (agg * 10).toInt(), aggTokPerSec = agg, fake = models.contains("FAKE"), models = models,
        perPhone = emptyList(), items = emptyList(),
    )

    @Test fun theRatioIsAPlainNumberOfTimesOnePhone() {
        assertEquals("2.0 times one phone", ThroughputText.ratio(report(7.7, mode = "local"), report(15.4)))
        assertEquals("1.5 times one phone", ThroughputText.ratio(report(10.0, mode = "local"), report(15.0)))
        assertEquals("a slower swarm says so", "0.5 times one phone", ThroughputText.ratio(report(10.0, mode = "local"), report(5.0)))
    }

    @Test fun noRatioUnlessBothRunsExist() {
        assertNull(ThroughputText.ratio(null, report(15.4)))
        assertNull(ThroughputText.ratio(report(7.7, mode = "local"), null))
        assertNull(ThroughputText.ratio(null, null))
    }

    @Test fun noRatioAcrossDifferentModels() {
        assertNull(ThroughputText.ratio(report(7.7, models = listOf("Qwen3.5-4B"), mode = "local"), report(15.4, models = listOf("gemma-4-E4B"))))
        assertNull(ThroughputText.ratio(report(7.7, models = listOf("A", "B"), mode = "local"), report(15.4, models = listOf("A"))))
    }

    @Test fun noRatioFromARunThatIsStillGoingOrMeasuredNothing() {
        assertNull(ThroughputText.ratio(report(7.7, mode = "local"), report(15.4, state = "running")))
        assertNull(ThroughputText.ratio(report(7.7, state = "running", mode = "local"), report(15.4)))
        assertNull(ThroughputText.ratio(report(0.0, mode = "local"), report(15.4)))
        assertNull(ThroughputText.ratio(report(7.7, models = emptyList(), mode = "local"), report(15.4, models = emptyList())))
    }

    @Test fun theFakeModelCountsAsOneModelAndTheRatioIsStillStated() {
        assertEquals("2.0 times one phone", ThroughputText.ratio(report(10.0, listOf("FAKE"), mode = "local"), report(20.0, listOf("FAKE"))))
    }
}
