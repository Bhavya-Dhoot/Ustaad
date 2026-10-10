package com.swarmos.app.sched

import com.swarmos.app.core.NodeInfo
import com.swarmos.app.core.SwarmJson
import com.swarmos.app.link.ErrorBody
import com.swarmos.app.link.NodeClient
import com.swarmos.app.link.RequestDedup
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.net.ServerSocket

/**
 * The two kinds of HTTP 409 a phone can answer: a pinned model it does not run (a [MismatchBody]) and the node server's
 * "the same request is still running" (an [ErrorBody] with no model). Only the first is a model mismatch.
 */
class RemoteConflictTest {
    companion object {
        @JvmStatic @BeforeClass fun quietLogging() {
            runCatching { org.slf4j.LoggerFactory.getLogger("sched-conflict-test") }
        }
    }

    private lateinit var server: EmbeddedServer<*, *>
    private lateinit var remote: RemoteLlmClient

    /** The body every /v1/sched route answers with, status 409. */
    @Volatile private var conflictBody = ""

    @Before fun setUp() {
        val port = ServerSocket(0).use { it.localPort }
        server = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            install(ContentNegotiation) { json(SwarmJson) }
            routing {
                route("/v1/sched") {
                    for (path in listOf("/chat", "/chat-stream", "/embed")) {
                        post(path) { call.respondText(conflictBody, ContentType.Application.Json, HttpStatusCode.Conflict) }
                    }
                }
            }
        }.also { it.start(wait = false) }
        val info = NodeInfo("peer", "Phone peer", "x", "127.0.0.1:$port", "t", "T", null)
        remote = RemoteLlmClient(info, NodeClient({ "secret" }, { "me" }), StreamClient({ "secret" }, { "me" }))
    }

    @After fun tearDown() {
        server.stop(100, 500)
    }

    private fun mismatch(model: String) = SwarmJson.encodeToString(MismatchBody(model = model))
    private val stillRunning = SwarmJson.encodeToString(ErrorBody("the same request (X-Swarm-Req) is still running"))

    @Test fun aRealModelMismatchStaysAModelMismatch() = runBlocking {
        conflictBody = mismatch("Other")
        try {
            remote.runChat("a", req("x"), "Wanted")
            fail("expected ModelMismatchException")
        } catch (e: ModelMismatchException) {
            assertEquals("Other", e.model)
        }
        try {
            remote.chatStream(req("x")).toList()
            fail("expected ModelMismatchException from the stream")
        } catch (e: ModelMismatchException) {
            assertEquals("Other", e.model)
        }
    }

    @Test fun aRequestStillRunningIsATimeoutNotAModelMismatch() = runBlocking {
        conflictBody = stillRunning
        for (what in listOf("chat", "embed", "stream")) {
            try {
                when (what) {
                    "chat" -> remote.runChat("a", req("x"), null)
                    "embed" -> remote.runEmbed("a", listOf("x"))
                    else -> remote.chatStream(req("x")).toList()
                }
                fail("$what: expected PhoneTimeoutException")
            } catch (e: PhoneTimeoutException) {
                assertEquals(what, "peer", e.nodeId)
                assertEquals(what, RequestDedup.MAX_WAIT_MS, e.afterMs)
            }
        }
    }

    @Test fun aConflictWithAnyOtherBodyIsAlsoNotAModelMismatch() = runBlocking {
        for (body in listOf("", "not json", "{}", "{\"error\":\"busy\"}")) {
            conflictBody = body
            try {
                remote.runChat("a", req("x"), null)
                fail("'$body': expected PhoneTimeoutException")
            } catch (e: PhoneTimeoutException) {
                assertEquals("peer", e.nodeId)
            }
        }
    }
}
