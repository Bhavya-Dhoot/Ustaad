package com.swarmos.app.link

import com.swarmos.app.core.NodeHealth
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** A peer that is reachable over Bluetooth only: [NodeClient.hostFor] gives "bt:<node id>" for it. */
class BluetoothOnlyPeerTest {
    private class FakeBt(val reply: String) : SmallTransport {
        var calls = 0
        var method: String? = null
        var fullPath: String? = null
        var headers: Map<String, String> = emptyMap()
        override suspend fun send(path: PeerStatus, method: String, fullPath: String, body: String, headers: Map<String, String>, timeoutMs: Long): String {
            calls++; this.method = method; this.fullPath = fullPath; this.headers = headers
            return reply
        }
    }

    private val client = NodeClient({ "secret" }, { "selfself" }, verifyResponses = false)
    private val health = """{"nodeId":"abcd1234","thermalStatus":0,"batteryPct":80,"charging":false,"ramAvailMb":4000,"lastDecodeTokPerSec":null,"linkStandard":null,"linkSpeedMbps":null}"""

    @After fun clean() {
        PeerLinks.clearSessionsForTest()
        PeerLinks.btTransport = null
    }

    private fun holdBtOnly() = PeerLinks.putSessionForTest(PeerSession(testPath("abcd1234", LinkType.BT, "bt:abcd1234", 0)))

    @Test fun hostForGivesTheBluetoothAddressAndTheClientRoutesItOverTheChannel() = runBlocking {
        holdBtOnly()
        val bt = FakeBt(health).also { PeerLinks.btTransport = it }
        val host = client.hostFor("abcd1234")
        assertEquals("bt:abcd1234", host)
        val h = client.get<NodeHealth>(host!!, "/health", 3_000)
        assertEquals("abcd1234", h.nodeId)
        assertEquals(1, bt.calls)
        assertEquals("GET", bt.method)
        assertEquals("/v1/health", bt.fullPath)
        assertEquals("the request is signed like any other", "selfself", bt.headers["X-Swarm-Node"])
        assertNotNull(bt.headers["X-Swarm-Sig"])
        assertNotNull(bt.headers[HEADER_REQ])
    }

    @Test fun aBodyTooLargeForBluetoothIsRefusedWithAPlainErrorNotAMalformedUrl() = runBlocking {
        holdBtOnly()
        val bt = FakeBt("{}").also { PeerLinks.btTransport = it }
        try {
            client.raw(HttpMethod.Post, "bt:abcd1234", "/kb/cards", "x".repeat(BT_MAX_BODY + 1))
            fail("expected a refusal")
        } catch (e: IOException) {
            assertTrue(e.message, e.message!!.contains("Bluetooth"))
            assertTrue(e.message, e.message!!.contains("Wi-Fi"))
        }
        assertEquals("nothing was sent", 0, bt.calls)
    }

    @Test fun aBluetoothAddressWithNoPathAtAllIsAPlainNoPathError() = runBlocking {
        try {
            client.raw(HttpMethod.Get, "bt:abcd1234", "/health")
            fail("expected an error")
        } catch (e: IOException) {
            assertTrue(e.message, e.message!!.startsWith("no path to abcd1234"))
        }
    }

    @Test fun bulkNeverGetsABluetoothAddress() {
        holdBtOnly()
        assertEquals("bt:abcd1234", client.hostFor("abcd1234", Use.SMALL))
        assertNull(client.hostFor("abcd1234", Use.BULK))
        val lan = testPath("abcd1234", LinkType.LAN, "192.168.1.5")
        assertEquals("192.168.1.5", NodeClient.hostOf(listOf(testPath("abcd1234", LinkType.BT, "bt:abcd1234", 0), lan), Use.BULK, 8787))
        assertEquals("bt:abcd1234", NodeClient.hostOf(listOf(testPath("abcd1234", LinkType.BT, "bt:abcd1234", 0), lan), Use.SMALL, 8787))
        assertEquals("192.168.1.5:8797", NodeClient.hostOf(listOf(lan.copy(port = 8797)), Use.SMALL, 8787))
    }

    @Test fun theAddressParsingKeepsTheWholeBluetoothAddress() {
        assertEquals("abcd1234", NodeClient.btNodeOf("bt:abcd1234"))
        assertNull(NodeClient.btNodeOf("192.168.1.5:8787"))
        assertNull(NodeClient.btNodeOf("bt:"))
        assertTrue(NodeClient.isBluetoothAddress("bt:abcd1234"))
        assertFalse(NodeClient.isBluetoothAddress("192.168.1.5"))
    }

    @Test fun theConnectTimeoutIsTheSmallerOfTheCallersAndFiveSeconds() {
        assertEquals(5_000L, NodeClient.connectTimeoutFor(60_000))
        assertEquals(5_000L, NodeClient.connectTimeoutFor(5_000))
        assertEquals(2_500L, NodeClient.connectTimeoutFor(2_500))
    }
}
