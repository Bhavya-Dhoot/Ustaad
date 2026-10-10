package com.swarmos.app.link

import com.swarmos.app.core.NodeInfo
import com.swarmos.app.core.NodeRegistry
import com.swarmos.app.core.PROTOCOL_VERSION
import com.swarmos.app.core.SwarmJson
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** NodeInfo as the build before the protocol field defined it (master c2ca589). */
@Serializable
private data class OldNodeInfo(
    val nodeId: String, val name: String, val serial: String, val ip: String, val appVersion: String,
    val chatModel: String?, val embedModel: String?,
)

class ProtocolVersionTest {
    @Test fun aNodeInfoWithoutTheFieldIsVersionOneAndOlder() {
        val old = SwarmJson.decodeFromString<NodeInfo>("""{"nodeId":"a","name":"Phone A","serial":"s","ip":"10.0.0.2","appVersion":"1","chatModel":null,"embedModel":null}""")
        assertEquals(1, old.protocol)
        assertTrue(old.older)
    }

    @Test fun thisBuildSendsItsVersionAndTheOldBuildStillReadsIt() {
        val reg = NodeRegistry(NodeInfo("b", "Phone B", "s", "10.0.0.3", "2", null, null)) // built without the field
        assertEquals(PROTOCOL_VERSION, reg.self.value.protocol)
        assertEquals(2, PROTOCOL_VERSION)
        val wire = SwarmJson.encodeToString(NodeInfo.serializer(), reg.self.value)
        assertTrue(wire, wire.contains("\"protocol\":2"))
        val seenByOld = SwarmJson.decodeFromString<OldNodeInfo>(wire) // the old phone ignores unknown keys
        assertEquals("b", seenByOld.nodeId)
        assertFalse(reg.self.value.older)
    }

    @Test fun updatingSelfKeepsTheVersion() {
        val reg = NodeRegistry(NodeInfo("b", "Phone B", "s", "10.0.0.3", "2", null, null))
        reg.updateSelf { it.copy(name = "Phone C", protocol = 1) }
        assertEquals(PROTOCOL_VERSION, reg.self.value.protocol)
        reg.refreshSelf("10.0.0.9", "m", null)
        assertEquals(PROTOCOL_VERSION, reg.self.value.protocol)
    }

    private fun status(protocol: Int, incompatible: Boolean = false) = testPath("a", LinkType.LAN, "10.0.0.2").let {
        it.copy(info = it.info.copy(protocol = protocol), incompatible = incompatible)
    }

    @Test fun theScreensGetAnOlderVersionNote() {
        assertEquals("Phone a runs an older version", status(1).versionNote)
        assertTrue(status(1).olderVersion)
        assertNull(status(2).versionNote)
        assertEquals("Phone a runs an incompatible version", status(1, incompatible = true).versionNote)
    }
}

class DecodeErrorTrackerTest {
    @Test fun threeUnreadableAnswersInARowMarkIncompatibleNotAway() {
        val t = HeartbeatTracker()
        t.onSuccess(2.0, 1_000)
        assertFalse(t.onDecodeError()); assertFalse(t.onDecodeError())
        assertFalse("not incompatible after two", t.incompatible)
        assertTrue("the third sets the flag", t.onDecodeError())
        assertTrue(t.incompatible)
        assertEquals("still online: the phone is there", LinkState.ONLINE, t.state)
        assertEquals("a decode error is not a miss", 0, t.misses)
        assertEquals(0, t.missesTotal)
        assertEquals(0, t.awayCount)
        assertFalse("the flag is raised once", t.onDecodeError())
    }

    @Test fun aDecodeErrorDoesNotCountTowardsAwayButARealMissStillDoes() {
        val t = HeartbeatTracker()
        t.onMiss(); t.onMiss()
        t.onDecodeError()
        assertEquals("sign of life: the run of misses starts again", 0, t.misses)
        t.onMiss(); t.onMiss()
        assertEquals(LinkState.ONLINE, t.state)
        assertTrue(t.onMiss())
        assertEquals(LinkState.AWAY, t.state)
    }

    @Test fun aReadableAnswerClearsTheIncompatibleFlag() {
        val t = HeartbeatTracker()
        repeat(3) { t.onDecodeError() }
        assertTrue(t.incompatible)
        t.onSuccess(1.0, 5_000)
        assertFalse(t.incompatible)
        assertEquals(0, t.decodeErrors)
        assertFalse("two more are again below the limit", t.onDecodeError() || t.onDecodeError())
    }
}
