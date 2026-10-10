package com.swarmos.app.llm

import com.swarmos.app.core.SwarmJson
import com.swarmos.app.sched.ChatJobRequest
import com.swarmos.app.sched.ChatRequestDto
import com.swarmos.app.sched.toDto
import com.swarmos.app.sched.toRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * `cache_prompt` is off only for a request that says so: the flag lives on [ChatRequest] (on by default), rides in the job
 * body to another phone, and reaches the model server as `cache_prompt`.
 */
class CachePromptTest {
    private val servers = ArrayList<ServerSocket>()

    @After fun tearDown() = servers.forEach { runCatching { it.close() } }

    /** A stand-in llama-server: keeps the body of every POST and answers with a finished empty stream. Returns the port. */
    private fun standIn(bodies: MutableList<String>): Int {
        val s = ServerSocket(0, 5, InetAddress.getLoopbackAddress()).also { servers += it }
        thread(isDaemon = true) {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: Exception) { break }
                try {
                    val ins = c.getInputStream()
                    val head = StringBuilder()
                    while (!head.endsWith("\r\n\r\n")) head.append(ins.read().also { if (it < 0) error("closed") }.toChar())
                    val length = Regex("(?i)content-length:\\s*(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
                    bodies += String(ins.readNBytes(length), Charsets.UTF_8)
                    val reply = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n" +
                        """data: {"choices":[{"finish_reason":"stop","index":0,"delta":{}}]}""" + "\n\ndata: [DONE]\n\n"
                    c.getOutputStream().apply { write(reply.toByteArray()); flush() }
                } catch (_: Exception) {
                }
                runCatching { c.close() }
            }
        }
        return s.localPort
    }

    private val hi = listOf(ChatMessage("user", "hi"))

    @Test fun aRequestKeepsTheCacheOnUnlessItSaysOtherwise() {
        assertTrue(ChatRequest(hi).cachePrompt)
        assertFalse(ChatRequest(hi, cachePrompt = false).cachePrompt)
    }

    @Test fun aJobBodyWithoutTheFieldMeansCacheOn() {
        val fromOlderBuild = """{"jobId":"j","request":{"messages":[{"role":"user","content":"hi"}],"maxTokens":8}}"""
        val job = SwarmJson.decodeFromString<ChatJobRequest>(fromOlderBuild)
        assertTrue(job.request.cachePrompt)
        assertTrue(job.request.toRequest().cachePrompt)
    }

    @Test fun theFlagIsOnTheWireAndRoundTripsBothWays() {
        for (flag in listOf(true, false)) {
            val sent = ChatJobRequest("j", ChatRequest(hi, cachePrompt = flag).toDto())
            val text = SwarmJson.encodeToString(sent)
            assertEquals(flag, Json.parseToJsonElement(text).jsonObject["request"]!!.jsonObject["cachePrompt"]!!.jsonPrimitive.boolean)
            val received = SwarmJson.decodeFromString<ChatJobRequest>(text).request.toRequest()
            assertEquals(flag, received.cachePrompt)
            assertEquals(hi, received.messages)
        }
    }

    @Test fun aPhoneOnAnOlderBuildIgnoresTheField() {
        val text = SwarmJson.encodeToString(ChatRequest(hi, maxTokens = 8, cachePrompt = false).toDto())
        val old = SwarmJson.decodeFromString<OlderDto>(text)
        assertEquals(8, old.maxTokens)
    }

    @Serializable
    private data class OlderDto(val messages: List<com.swarmos.app.sched.MessageDto>, val maxTokens: Int = 256)

    @Test fun theModelServerGetsTheRequestsOwnFlag() = runBlocking {
        val bodies = CopyOnWriteArrayList<String>()
        val client = LlamaServerClient(Role.CHAT, "m", standIn(bodies))
        withTimeout(10_000) {
            client.chat(ChatRequest(hi))
            client.chat(ChatRequest(hi, cachePrompt = false))
            client.chatStream(ChatRequest(hi, cachePrompt = false)).collect { }
        }
        val flags = bodies.map { Json.parseToJsonElement(it).jsonObject["cache_prompt"]!!.jsonPrimitive.boolean }
        assertEquals(listOf(true, false, false), flags)
    }

    @Test fun anExplicitFlagOnTheRawCallStillWinsOverTheRequest() = runBlocking {
        val bodies = CopyOnWriteArrayList<String>()
        val client = LlamaServerClient(Role.CHAT, "m", standIn(bodies))
        withTimeout(10_000) { client.chatStreamRaw(ChatRequest(hi), cachePrompt = false).collect { } }
        assertFalse(Json.parseToJsonElement(bodies.single()).jsonObject["cache_prompt"]!!.jsonPrimitive.boolean)
    }
}
