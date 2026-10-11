package com.swarmos.app.memcloud

import com.swarmos.app.core.SwarmJson
import com.swarmos.app.llm.LoadRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The driver's request to a phone to load a model back is that phone's own restore, not the owner's load: it must say so, or the phone takes
 * it as a manual load that moves the epoch and ends the phone's own retry after a refusal for heat, and the driver counts the 202 as settled.
 */
class RestoreLoadRequestTest {
    @Test fun theDriversRestoreRequestIsMarkedAutomaticAndStillDoesNotWait() {
        val req = SwarmJson.decodeFromString<LoadRequest>(restoreLoadBody("chat", "Qwen3.5-4B-Q4_K_M.gguf"))
        assertEquals("chat", req.role)
        assertEquals("Qwen3.5-4B-Q4_K_M.gguf", req.file)
        assertEquals(false, req.wait)
        assertTrue(req.automatic)
    }

    @Test fun anOwnersOrAnOlderDriversRequestIsNotAutomatic() {
        assertFalse(SwarmJson.decodeFromString<LoadRequest>("""{"role":"chat","file":"a.gguf","wait":false}""").automatic)
        assertFalse(SwarmJson.decodeFromString<LoadRequest>("""{"role":"chat","file":"a.gguf"}""").automatic)
    }

    @Test fun aPhoneOnTheOlderBuildIgnoresTheFieldItDoesNotKnow() {
        // SwarmJson ignores unknown keys: a request with "automatic" decodes on a build whose LoadRequest has no such field
        val withUnknown = SwarmJson.decodeFromString<UnknownFieldProbe>(restoreLoadBody("embed", "e.gguf"))
        assertEquals("embed", withUnknown.role)
    }

    @kotlinx.serialization.Serializable
    private data class UnknownFieldProbe(val role: String, val file: String = "", val wait: Boolean? = null)
}
