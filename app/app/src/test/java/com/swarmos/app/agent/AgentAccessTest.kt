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
    private var saved = CopyOnWriteArrayList<Triple<Boolean, String?, Boolean>>()

    private fun restore(on: Boolean, key: String?, files: Boolean = false) {
        val mine = CopyOnWriteArrayList<Triple<Boolean, String?, Boolean>>()
        saved = mine
        AgentAccess.restore(on, key, files) { o, k, f -> mine += Triple(o, k, f) }
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
        assertEquals(Triple(true, key, false), saved.last())
    }

    @Test fun turningItOffForgetsTheKey() {
        AgentAccess.setEnabled(true)
        AgentAccess.setEnabled(false)
        assertFalse(AgentAccess.enabled.value)
        assertNull(AgentAccess.key.value)
        waitForSave(1)
        runBlocking { delay(200) }
        assertEquals(Triple(false, null, false), saved.last())
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
        assertEquals(Triple(true, "known", false), saved.last())
    }

    @Test fun theStoredStateComesBack() {
        AgentAccess.restore(true, "stored-key", false) { _, _, _ -> }
        assertTrue(AgentAccess.enabled.value)
        assertEquals("stored-key", AgentAccess.key.value)
    }

    @Test fun aStoredKeyIsIgnoredWhileOff() {
        AgentAccess.restore(false, "stale", false) { _, _, _ -> }
        assertNull(AgentAccess.key.value)
    }

    @Test fun anOnSwitchWithoutAKeyGetsOneAndSavesIt() {
        restore(true, null)
        assertNotNull(AgentAccess.key.value)
        waitForSave(1)
        assertEquals(Triple(true, AgentAccess.key.value, false), saved.last())
    }

    @Test fun servedAndRefusedCountUpAndResetOnRestore() {
        AgentAccess.noteServed()
        AgentAccess.noteServed()
        AgentAccess.noteRefused()
        assertEquals(2, AgentAccess.served.value)
        assertEquals(1, AgentAccess.refused.value)
        AgentAccess.restore(false, null, false) { _, _, _ -> }
        assertEquals(0, AgentAccess.served.value)
        assertEquals(0, AgentAccess.refused.value)
    }

    @Test fun quickChangesSaveTheLatestStateLast() {
        AgentAccess.setEnabled(true)
        AgentAccess.newKey()
        AgentAccess.newKey()
        AgentAccess.setEnabled(false)
        runBlocking { delay(500) }
        assertEquals(Triple(false, null, false), saved.last())
    }

    // ---- file search ------------------------------------------------------------------------------------------

    @Test fun fileSearchIsOffByDefault() {
        assertFalse(AgentAccess.files.value)
        AgentAccess.setEnabled(true)
        assertFalse("turning access on does not turn file search on", AgentAccess.files.value)
    }

    @Test fun fileSearchCannotBeSwitchedOnWhileAccessIsOff() {
        AgentAccess.setFiles(true)
        assertFalse(AgentAccess.files.value)
        runBlocking { delay(150) }
        assertTrue("nothing was saved", saved.isEmpty())
    }

    @Test fun switchingFileSearchOnIsSavedWithTheKey() {
        AgentAccess.setEnabled(true)
        AgentAccess.setFiles(true)
        assertTrue(AgentAccess.files.value)
        runBlocking { delay(300) }
        assertEquals(Triple(true, AgentAccess.key.value, true), saved.last())
    }

    @Test fun turningAccessOffTurnsFileSearchOffAndSavesIt() {
        AgentAccess.setEnabled(true)
        AgentAccess.setFiles(true)
        AgentAccess.setEnabled(false)
        assertFalse(AgentAccess.files.value)
        runBlocking { delay(300) }
        assertEquals(Triple(false, null, false), saved.last())
        // and it stays off when access comes back
        AgentAccess.setEnabled(true)
        assertFalse(AgentAccess.files.value)
    }

    @Test fun aNewKeyLeavesFileSearchAsItWas() {
        AgentAccess.setEnabled(true)
        AgentAccess.setFiles(true)
        AgentAccess.newKey()
        assertTrue(AgentAccess.files.value)
        AgentAccess.setFiles(false)
        AgentAccess.newKey()
        assertFalse(AgentAccess.files.value)
    }

    @Test fun theStoredFileSearchSwitchComesBackOnlyWithAccess() {
        restore(true, "k", files = true)
        assertTrue(AgentAccess.files.value)
        restore(false, null, files = true)
        assertFalse("a stale switch is not honoured while access is off", AgentAccess.files.value)
        waitForSave(1)
        assertEquals("and it is cleaned up in the store", Triple(false, null, false), saved.last())
    }
}
