package com.swarmos.app.hands.android

import com.swarmos.app.hands.Hands
import com.swarmos.app.hands.HandsStatus
import com.swarmos.app.hands.Origin
import com.swarmos.app.hands.RunState
import com.swarmos.app.hands.ToolCall
import com.swarmos.app.hands.ToolResult
import com.swarmos.app.hands.ToolSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class RunLauncherTest {
    private val events = ArrayList<String>()

    private val hands = object : Hands {
        override val runs: StateFlow<List<RunState>> = MutableStateFlow(emptyList())
        override fun start(task: String, origin: Origin): String { events += "start:$task:${origin.name}"; return "id1" }
        override fun go(runId: String) { events += "go:$runId" }
        override fun stop(runId: String) {}
        override fun status() = HandsStatus("", accessibilityOn = false, rootAvailable = false, fast = false, strong = false)
        override fun tools(): List<ToolSpec> = emptyList()
        override suspend fun call(call: ToolCall, origin: Origin) = ToolResult(false, "")
    }

    private fun launcher(running: Boolean, fails: Boolean = false) = RunLauncher(
        serviceRunning = { running },
        startService = { events += "service"; if (fails) throw IllegalStateException("not allowed now") },
    )

    @Test
    fun theServiceIsStartedBeforeTheRun() {
        val id = launcher(running = false).start(hands, "order biryani", Origin.OWN_SHEET)
        assertEquals("id1", id)
        assertEquals(listOf("service", "start:order biryani:OWN_SHEET"), events)
    }

    @Test
    fun aRunningServiceIsLeftAlone() {
        launcher(running = true).start(hands, "t", Origin.ASSISTANT_SESSION)
        assertEquals(listOf("start:t:ASSISTANT_SESSION"), events)
    }

    @Test
    fun goStartsTheServiceFirstToo() {
        launcher(running = false).go(hands, "r9")
        assertEquals(listOf("service", "go:r9"), events)
    }

    @Test
    fun aRefusedServiceStartDoesNotStopTheRun() {
        launcher(running = false, fails = true).start(hands, "t", Origin.OWN_SHEET)
        assertEquals(listOf("service", "start:t:OWN_SHEET"), events)
    }

    @Test
    fun nothingStartsTheServiceBeforeTheUiCalls() {
        launcher(running = false)
        assertEquals(emptyList<String>(), events)
    }
}
