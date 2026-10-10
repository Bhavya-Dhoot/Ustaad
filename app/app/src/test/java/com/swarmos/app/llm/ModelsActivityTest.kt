package com.swarmos.app.llm

import com.swarmos.app.core.PhoneActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ModelsActivityTest {
    @Before @After fun reset() = PhoneActivity.clearAll()

    private val now get() = PhoneActivity.current.value

    @Test fun twoLoadsShowTheNewestAndTheOlderOneComesBack() {
        ModelsActivity.set(ModelsActivity.loadKey(Role.CHAT), ModelsActivity.loadText(Role.CHAT)!!)
        ModelsActivity.set(ModelsActivity.loadKey(Role.EMBED), ModelsActivity.loadText(Role.EMBED)!!)
        assertEquals("Loading embedder.", now?.text)
        assertEquals("models", now?.owner)
        ModelsActivity.end(ModelsActivity.loadKey(Role.EMBED))
        assertEquals("Loading chat model.", now?.text)
        ModelsActivity.end(ModelsActivity.loadKey(Role.CHAT))
        assertNull(now)
    }

    @Test fun rpcLoadsAreNotAnnouncedHere() {
        assertNull(ModelsActivity.loadText(Role.RPC))
    }

    @Test fun updateKeepsTheStopActionAndEndedTasksAreNotRevived() {
        var stopped = false
        ModelsActivity.set("bench", ModelsActivity.benchText(1, 10), onStop = { stopped = true })
        ModelsActivity.update("bench", ModelsActivity.benchText(3, 10))
        assertEquals("Running a benchmark (minute 3 of 10).", now?.text)
        assertNotNull(now?.onStop)
        now?.onStop?.invoke()
        assertTrue(stopped)
        ModelsActivity.end("bench")
        ModelsActivity.update("bench", "late")
        assertNull(now)
    }

    @Test fun downloadAndBenchLines() {
        assertEquals("Downloading a model (1.9 of 18.6 GB).", ModelsActivity.downloadText((1.9 * 1024 * 1024 * 1024).toLong(), (18.6 * 1024 * 1024 * 1024).toLong()))
        assertEquals("120 of 310 MB", ModelsActivity.sizePair(120L * 1024 * 1024, 310L * 1024 * 1024))
        assertEquals("Running a benchmark.", ModelsActivity.benchText(null, 0))
    }

    @Test fun benchGuardUsesTheSharedThermalDefinition() {
        assertNull(Bench.blockReasonFor(0))
        assertNull(Bench.blockReasonFor(2))
        assertNotNull(Bench.blockReasonFor(3))
        assertNotNull(Bench.blockReasonFor(4))
    }
}
