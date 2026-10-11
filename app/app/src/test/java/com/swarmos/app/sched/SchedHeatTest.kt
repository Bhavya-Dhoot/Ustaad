package com.swarmos.app.sched

import com.swarmos.app.core.NodeHealth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A phone at thermal status 4 or more takes on no new job from another phone; its own owner's requests are never refused for heat. */
class SchedHeatTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val llm = TestLlm(delayMs = 150)
    @Volatile private var status = 0
    private val node = SchedNode(scope, { "n" }, { llm }, { llm }, { NodeHealth("n", status, 80, false, 4096, null, null, null) })
    private val local = LocalEndpoint(node, { llm }, { llm }, { "n" })

    @After fun tearDown() = scope.cancel()

    @Test fun aChatJobFromAnotherPhoneIsRefusedAtCriticalLikeAFullPhoneAndAcceptedAtSevere() = runBlocking {
        for (hot in 4..6) {
            status = hot
            try {
                node.chat("j$hot", req("x"))
                fail("status $hot: expected BusyException")
            } catch (e: BusyException) {
                assertEquals(0, e.running)
                assertEquals(1, e.capacity)
                assertEquals(0, e.queued)
            }
        }
        assertTrue("the model was never called", llm.executed.isEmpty())
        assertEquals("nothing is held for a refused job", 0, node.chatExec.running)
        assertEquals("a refused job is not remembered", 0, node.chatExec.remembered)
        status = 3
        assertEquals("the same job id runs once the phone is under 4", "T:x", node.chat("j4", req("x")).text)
    }

    @Test fun aStreamFromAnotherPhoneIsRefusedAtCriticalAndAcceptedAtSevere() = runBlocking {
        status = 4
        try {
            node.openStream(null)
            fail("expected BusyException")
        } catch (e: BusyException) {
            assertEquals(1, e.capacity)
        }
        assertEquals("no slot is held", 0, node.chatExec.running)
        status = 3
        val slot = node.openStream(null)
        assertEquals(1, node.chatExec.running)
        slot.close()
        assertEquals(0, node.chatExec.running)
    }

    @Test fun anEmbeddingJobFromAnotherPhoneIsRefusedAtCriticalAndAcceptedAtSevere() = runBlocking {
        status = 4
        try {
            node.embed("e1", listOf("abc"))
            fail("expected BusyException")
        } catch (e: BusyException) {
            assertEquals(0, e.running)
        }
        assertEquals("the embedder was never called", 0, llm.embedCalls.get())
        status = 3
        assertEquals(listOf(listOf(3f)), node.embed("e1", listOf("abc")).vectors)
    }

    @Test fun theOwnersRequestsOnThisPhoneAreNotRefusedAtCritical() = runBlocking {
        status = 4
        assertEquals("T:mine", local.runChat("own", req("mine"), null).text)
        assertEquals(listOf(listOf(4f)), local.runEmbed("own-e", listOf("mine")).vectors)
        val chunks = ArrayList<String>()
        val streamed = local.streamIfFree(req("mine"), null, FlowCollector { chunks += it.delta })
        assertTrue("the owner's stream ran here", streamed)
        assertTrue(chunks.joinToString("").endsWith("mine"))
        assertEquals("Agent Choir and Data Union go through the gate, not the node API", "T:mine", GatedLlmClient({ llm }, node).chat(req("mine")).text)
        assertEquals(0, node.chatExec.running)
    }

    @Test fun aJobAlreadyRunningWhenThePhoneGetsCriticalIsNotTouched() = runBlocking {
        val running = async { node.chat("early", req("early")) }
        while (node.chatExec.running == 0) delay(5)
        status = 4
        assertEquals("T:early", running.await().text)
    }

    @Test fun aResendOfAFinishedJobGetsItsResultEvenWhenThePhoneHasGotHot() = runBlocking {
        assertEquals("T:x", node.chat("j1", req("x")).text)
        assertEquals(listOf(listOf(3f)), node.embed("e1", listOf("abc")).vectors)
        status = 4   // the reply was lost and the phone got hot: the sender asks again with the same job id
        assertEquals("the stored result, not a refusal", "T:x", node.chat("j1", req("x")).text)
        assertEquals(listOf(listOf(3f)), node.embed("e1", listOf("abc")).vectors)
        assertEquals("the job was not run again", 1, llm.executed.size)
        assertEquals(1, llm.embedCalls.get())
        try {
            node.chat("j2", req("y"))
            fail("a new job id is still refused")
        } catch (e: BusyException) {
            assertEquals(0, e.running)
        }
    }

    @Test fun aResendOfAJobStillRunningSharesItWhenThePhoneHasGotHot() = runBlocking {
        val first = async { node.chat("slow", req("slow")) }
        while (node.chatExec.running == 0) delay(5)
        status = 4
        assertEquals("joins the running job", "T:slow", node.chat("slow", req("slow")).text)
        assertEquals("T:slow", first.await().text)
        assertEquals("one run for both", 1, llm.executed.size)
    }
}
