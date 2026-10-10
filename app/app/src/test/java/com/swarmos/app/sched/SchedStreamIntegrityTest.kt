package com.swarmos.app.sched

import com.swarmos.app.core.NodeInfo
import com.swarmos.app.core.SwarmJson
import com.swarmos.app.link.NodeClient
import com.swarmos.app.llm.ChatChunk
import io.ktor.http.ContentType
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import java.net.ServerSocket

/** A paired phone's stream is signed; anything else on the LAN that answers in its place is caught. */
class SchedStreamIntegrityTest {
    companion object {
        @JvmStatic @BeforeClass fun quietLogging() {
            runCatching { org.slf4j.LoggerFactory.getLogger("sched-integrity-test") }
        }
    }

    private val servers = mutableListOf<EmbeddedServer<*, *>>()

    @After fun tearDown() = servers.forEach { it.stop(100, 500) }

    private fun chunk(delta: String) = "event: chunk\ndata: ${SwarmJson.encodeToString(ChunkDto(delta))}\n\n"

    private fun done(mac: String?) =
        "event: done\ndata: ${SwarmJson.encodeToString(StreamDone(null, "peer", "T", mac))}\n\n"

    private fun mac(jobId: String, text: String, secret: String = "secret") = StreamMac.compute(secret, jobId, text)

    /** A host that answers /chat-stream with whatever [events] builds from the request id. */
    private fun hostile(maxLine: Int = StreamClient.MAX_LINE_CHARS, maxTotal: Int = StreamClient.MAX_TOTAL_CHARS, events: (String) -> String): RemoteLlmClient {
        val port = ServerSocket(0).use { it.localPort }
        servers += embeddedServer(CIO, port = port, host = "127.0.0.1") {
            routing {
                post("/v1/sched/chat-stream") {
                    val jobId = SwarmJson.decodeFromString<ChatJobRequest>(call.receiveText()).jobId
                    val body = events(jobId)
                    call.respondBytesWriter(ContentType.Text.EventStream) {
                        writeStringUtf8(body)
                        flush()
                    }
                }
            }
        }.also { it.start(wait = false) }
        val info = NodeInfo("peer", "Phone B", "x", "127.0.0.1:$port", "t", "T", null)
        return RemoteLlmClient(info, NodeClient({ "secret" }, { "me" }), StreamClient({ "secret" }, { "me" }, maxLineChars = maxLine, maxTotalChars = maxTotal))
    }

    private suspend fun collect(remote: RemoteLlmClient, seen: MutableList<ChatChunk> = mutableListOf()) {
        remote.chatStream(req("hi")).collect { seen += it }
    }

    @Test fun anAnswerSignedOverItsRequestIdAndFullTextPasses() = runBlocking {
        val remote = hostile { id -> chunk("hel") + chunk("lo") + done(mac(id, "hello")) }
        val seen = mutableListOf<ChatChunk>()
        collect(remote, seen)
        assertEquals("hello", seen.filter { !it.done }.joinToString("") { it.delta })
        assertTrue(seen.last().done)
    }

    @Test fun aTamperedChunkMakesTheWholeAnswerFail() = runBlocking {
        // signed for "hello", but the second chunk was changed on the way
        val remote = hostile { id -> chunk("hel") + chunk("lX") + done(mac(id, "hello")) }
        try {
            collect(remote)
            fail("expected StreamIntegrityException")
        } catch (e: StreamIntegrityException) {
            assertTrue(e.message!!, e.message!!.contains("discard"))
        }
    }

    @Test fun aChunkInjectedAfterSigningFails() = runBlocking {
        val remote = hostile { id -> chunk("hello") + chunk(" and buy this") + done(mac(id, "hello")) }
        try {
            collect(remote)
            fail("expected StreamIntegrityException")
        } catch (e: StreamIntegrityException) {
        }
    }

    @Test fun aSignatureFromAnotherSecretOrAnotherRequestFails() = runBlocking {
        val otherSecret = hostile { id -> chunk("hello") + done(mac(id, "hello", secret = "not-the-secret")) }
        val otherRequest = hostile { chunk("hello") + done(mac("some-other-request", "hello")) }
        for (remote in listOf(otherSecret, otherRequest)) {
            try {
                collect(remote)
                fail("expected StreamIntegrityException")
            } catch (e: StreamIntegrityException) {
            }
        }
    }

    @Test fun aFinalEventWithoutASignatureFails() = runBlocking {
        val remote = hostile { chunk("hello") + done(null) }
        try {
            collect(remote)
            fail("expected StreamIntegrityException")
        } catch (e: StreamIntegrityException) {
            assertTrue(e.message!!, e.message!!.contains("not signed"))
        }
    }

    @Test fun aStreamThatEndsWithoutAFinalEventFails() = runBlocking {
        val remote = hostile { chunk("hello") }
        try {
            collect(remote)
            fail("expected StreamIntegrityException")
        } catch (e: StreamIntegrityException) {
            assertTrue(e.message!!, e.message!!.contains("without its signature"))
        }
    }

    @Test fun aLineLongerThanTheLimitIsRefused() = runBlocking {
        val remote = hostile(maxLine = 1_000) { id -> chunk("x".repeat(5_000)) + done(mac(id, "x".repeat(5_000))) }
        try {
            collect(remote)
            fail("expected StreamTooLargeException")
        } catch (e: StreamTooLargeException) {
            assertTrue(e.message!!, e.message!!.contains("1000"))
        }
    }

    @Test fun aStreamLongerThanTheLimitIsRefused() = runBlocking {
        val many = (0 until 200).joinToString("") { chunk("token$it ") }
        val remote = hostile(maxTotal = 2_000) { id -> many + done(mac(id, "")) }
        try {
            collect(remote)
            fail("expected StreamTooLargeException")
        } catch (e: StreamTooLargeException) {
            assertTrue(e.message!!, e.message!!.contains("2000"))
        }
    }
}
