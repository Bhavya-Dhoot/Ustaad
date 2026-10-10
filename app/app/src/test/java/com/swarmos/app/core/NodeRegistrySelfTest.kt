package com.swarmos.app.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeRegistrySelfTest {
    private fun registry() = NodeRegistry(NodeInfo("n1", "Phone A", "unknown", "10.0.0.5", "0.1.0", "FAKE", "FAKE"))

    @Test
    fun refreshReplacesTheStartUpModelNamesAndAddress() {
        val r = registry()
        assertTrue(r.refreshSelf("192.168.242.89", "Qwen3.5-4B-Q4_K_M", "embeddinggemma-300M-Q8_0"))
        val s = r.self.value
        assertEquals("Qwen3.5-4B-Q4_K_M", s.chatModel)
        assertEquals("embeddinggemma-300M-Q8_0", s.embedModel)
        assertEquals("192.168.242.89", s.ip)
        assertEquals("Phone A", s.name) // nothing else is touched
    }

    @Test
    fun refreshWithTheSameValuesChangesNothing() {
        val r = registry()
        assertFalse(r.refreshSelf("10.0.0.5", "FAKE", "FAKE"))
        assertTrue(r.refreshSelf("10.0.0.6", "FAKE", "FAKE"))
        assertFalse(r.refreshSelf("10.0.0.6", "FAKE", "FAKE"))
    }

    @Test
    fun aModelThatStopsGoesBackToFake() {
        val r = registry()
        r.refreshSelf("10.0.0.5", "Qwen3.5-4B-Q4_K_M", "FAKE")
        assertTrue(r.refreshSelf("10.0.0.5", "FAKE", "FAKE"))
        assertEquals("FAKE", r.self.value.chatModel)
    }

    @Test
    fun aRenameIsNotLostByARefresh() {
        val r = registry()
        r.updateSelf { it.copy(name = "Phone B") }
        r.refreshSelf("10.0.0.9", "Qwen3.5-4B-Q4_K_M", "FAKE")
        assertEquals("Phone B", r.self.value.name)
    }

    @Test
    fun followSelfKeepsRegistrySelfInStepWithTheModelAndTheAddress() = runBlocking {
        val r = registry()
        val models = MutableStateFlow<Pair<String?, String?>>("FAKE" to "FAKE")
        val ip = MutableStateFlow("10.0.0.5")
        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch { r.followSelf(models, ip) }

        models.value = "Qwen3.5-4B-Q4_K_M" to "FAKE" // the chat model finished loading
        withTimeout(5000) { r.self.first { it.chatModel == "Qwen3.5-4B-Q4_K_M" } }

        ip.value = "192.168.242.76" // the Wi-Fi address changed
        withTimeout(5000) { r.self.first { it.ip == "192.168.242.76" } }
        assertEquals("Qwen3.5-4B-Q4_K_M", r.self.value.chatModel)

        models.value = "FAKE" to "FAKE" // the model was stopped
        withTimeout(5000) { r.self.first { it.chatModel == "FAKE" } }
        scope.cancel()
    }
}
