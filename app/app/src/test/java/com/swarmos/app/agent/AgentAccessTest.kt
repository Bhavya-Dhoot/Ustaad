package com.swarmos.app.agent

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** The switch and key behind Settings: off by default, a key per turn-on, nothing left behind when it is off. */
class AgentAccessTest {
    // a new list per test: a save left over from the previous test writes to that test's list, not this one
    private var saved = CopyOnWriteArrayList<Pair<Boolean, String?>>()

    private fun restore(on: Boolean, key: String?) {
        val mine = CopyOnWriteArrayList<Pair<Boolean, String?>>()
        saved = mine
        AgentAccess.restore(on, key) { o, k -> mine += o to k }
    }

    @Before fun fresh() = restore(false, null)

    private fun waitForSave(n: Int) = runBlocking {
        repeat(100) { if (saved.size < n) delay(20) }
        assertTrue("expected $n saves, got ${saved.size}", saved.size >= n)
    }

    @Test fun offByDefaultWithNoKey() {
        assertFalse(AgentAccess.enabled.value)
        assertNull(AgentAccess.key.value)
        assertEquals(0, AgentAccess.served.value)
    }

    @Test fun turningItOnMakesAKeyAndSavesIt() {
        AgentAccess.setEnabled(true)
        val key = AgentAccess.key.value
        assertNotNull(key)
        assertEquals(43, key!!.length)
        waitForSave(1)
        assertEquals(true to key, saved.last())
    }

    @Test fun turningItOffForgetsTheKey() {
        AgentAccess.setEnabled(true)
        AgentAccess.setEnabled(false)
        assertFalse(AgentAccess.enabled.value)
        assertNull(AgentAccess.key.value)
        waitForSave(1)
        runBlocking { delay(200) }
        assertEquals(false to null, saved.last())
    }

    @Test fun turningItOnAgainMakesAnotherKey() {
        AgentAccess.setEnabled(true)
        val first = AgentAccess.key.value
        AgentAccess.setEnabled(false)
        AgentAccess.setEnabled(true)
        assertNotEquals(first, AgentAccess.key.value)
    }

    @Test fun switchingOnWhenAlreadyOnKeepsTheKey() {
        AgentAccess.setEnabled(true)
        val key = AgentAccess.key.value
        AgentAccess.setEnabled(true)
        assertEquals(key, AgentAccess.key.value)
    }

    @Test fun aNewKeyReplacesTheOldOneOnlyWhileOn() {
        AgentAccess.newKey()
        assertNull(AgentAccess.key.value)
        AgentAccess.setEnabled(true)
        val first = AgentAccess.key.value
        AgentAccess.newKey()
        assertNotEquals(first, AgentAccess.key.value)
        assertFalse(AgentKey.matches(AgentAccess.key.value, first))
    }

    @Test fun aKnownKeyCanBeSetForTestsWhileOn() {
        AgentAccess.setKey("known")
        assertNull(AgentAccess.key.value)
        AgentAccess.setEnabled(true)
        AgentAccess.setKey("known")
        assertEquals("known", AgentAccess.key.value)
        AgentAccess.setKey(" ")
        assertEquals("known", AgentAccess.key.value)
        waitForSave(1)
        runBlocking { delay(200) }
        assertEquals(true to "known", saved.last())
    }

    @Test fun theStoredStateComesBack() {
        AgentAccess.restore(true, "stored-key") { _, _ -> }
        assertTrue(AgentAccess.enabled.value)
        assertEquals("stored-key", AgentAccess.key.value)
    }

    @Test fun aStoredKeyIsIgnoredWhileOff() {
        AgentAccess.restore(false, "stale") { _, _ -> }
        assertNull(AgentAccess.key.value)
    }

    @Test fun anOnSwitchWithoutAKeyGetsOneAndSavesIt() {
        restore(true, null)
        assertNotNull(AgentAccess.key.value)
        waitForSave(1)
        assertEquals(true to AgentAccess.key.value, saved.last())
    }

    @Test fun servedCountsUpAndResetsOnRestore() {
        AgentAccess.noteServed()
        AgentAccess.noteServed()
        assertEquals(2, AgentAccess.served.value)
        AgentAccess.restore(false, null) { _, _ -> }
        assertEquals(0, AgentAccess.served.value)
    }

    @Test fun quickChangesSaveTheLatestStateLast() {
        AgentAccess.setEnabled(true)
        AgentAccess.newKey()
        AgentAccess.newKey()
        AgentAccess.setEnabled(false)
        runBlocking { delay(500) }
        assertEquals(false to null, saved.last())
    }
}
