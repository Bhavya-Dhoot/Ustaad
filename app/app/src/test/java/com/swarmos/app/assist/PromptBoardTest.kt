package com.swarmos.app.assist

import com.swarmos.app.hands.ConfirmPrompt
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PromptBoardTest {
    @Volatile
    private var now = 0L
    private fun board(timeoutMs: Long = 10_000, armMs: Long = 700) = PromptBoard({ now }, timeoutMs, armMs)
    private fun prompt(run: String = "run-1", app: String = "Swiggy", action: String = "tap \"Place order\"", why: String = "this places an order") =
        ConfirmPrompt(run, app, action, why)

    /** Polls until [get] has a value; fails the test after 5 s ([what] is only the name of the thing waited for). */
    private suspend fun <T : Any> waitFor(what: String, get: () -> T?): T = withTimeout(5_000) {
        var v = get()
        while (v == null) {
            delay(5)
            v = get()
        }
        v
    }

    private fun runTest(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        runBlocking { block() }
    }

    private fun kotlinx.coroutines.CoroutineScope.confirmAsync(b: PromptBoard, p: ConfirmPrompt = prompt()): Deferred<Boolean> =
        async(Dispatchers.Default, CoroutineStart.DEFAULT) { b.confirm(p) }

    @Test
    fun yesNeedsTheArmingTimeAfterTheWindowIsVisible() = runTest {
        val b = board()
        val answer = confirmAsync(b)
        val id = waitFor("pending") { b.pending.value }.id
        now = 0
        assertFalse("not visible yet", b.answerYes(id))
        b.markVisible(id)
        now = 699
        assertFalse("700 ms have not passed", b.answerYes(id))
        assertFalse(answer.isCompleted)
        now = 700
        assertTrue(b.answerYes(id))
        assertTrue(answer.await())
        assertNull(b.pending.value)
    }

    @Test
    fun coveringThePromptSwitchesYesOffAgain() = runTest {
        val b = board()
        val answer = confirmAsync(b)
        val id = waitFor("pending") { b.pending.value }.id
        b.markVisible(id)
        now = 5_000
        assertTrue(b.armed(id))
        b.markHidden(id)
        assertFalse(b.armed(id))
        assertFalse(b.answerYes(id))
        b.markVisible(id)
        now = 5_699
        assertFalse(b.answerYes(id))
        now = 5_700
        assertTrue(b.answerYes(id))
        assertTrue(answer.await())
    }

    @Test
    fun markingVisibleTwiceDoesNotRestartTheClock() = runTest {
        val b = board()
        val answer = confirmAsync(b)
        val id = waitFor("pending") { b.pending.value }.id
        b.markVisible(id)
        now = 600
        b.markVisible(id)
        now = 700
        assertTrue(b.answerYes(id))
        assertTrue(answer.await())
    }

    @Test
    fun noIsAnAnswer() = runTest {
        val b = board()
        val answer = confirmAsync(b)
        val id = waitFor("pending") { b.pending.value }.id
        assertTrue(b.answerNo(id))
        assertFalse(answer.await())
        assertNull(b.pending.value)
    }

    @Test
    fun aTimeoutIsNo() = runTest {
        val b = board(timeoutMs = 80)
        val closed = AtomicInteger()
        val result = async(Dispatchers.Default) { b.confirm(prompt(), onClose = { closed.incrementAndGet() }) }
        assertFalse(result.await())
        assertNull(b.pending.value)
        assertEquals(1, closed.get())
    }

    @Test
    fun aTimeoutOfAQuestionIsNull() = runTest {
        val b = board(timeoutMs = 80)
        assertNull(async(Dispatchers.Default) { b.ask("run-1", "Which address?") }.await())
        assertNull(b.pending.value)
    }

    @Test
    fun aDismissedQuestionIsNullAndATypedAnswerIsReturned() = runTest {
        val b = board()
        val dismissed = async(Dispatchers.Default) { b.ask("run-1", "Which address?") }
        val id = waitFor("pending") { b.pending.value }.id
        assertTrue(b.pending.value!!.isQuestion)
        assertTrue(b.answerNo(id))
        assertNull(dismissed.await())

        val typed = async(Dispatchers.Default) { b.ask("run-1", "Which address?") }
        val id2 = waitFor("second") { b.pending.value }.id
        assertFalse("an empty answer keeps the question open", b.answerText(id2, "   "))
        assertTrue(b.answerText(id2, "  12 MG Road\nBengaluru  "))
        assertEquals("12 MG Road Bengaluru", typed.await())
    }

    @Test
    fun aQuestionCannotBeAnsweredYesAndAConfirmCannotBeAnsweredWithText() = runTest {
        val b = board()
        val q = async(Dispatchers.Default) { b.ask("run-1", "Which address?") }
        val qid = waitFor("question") { b.pending.value }.id
        b.markVisible(qid)
        now = 10_000
        assertFalse(b.answerYes(qid))
        assertTrue(b.answerText(qid, "home"))
        assertEquals("home", q.await())

        val c = confirmAsync(b)
        val cid = waitFor("confirm") { b.pending.value }.id
        assertFalse(b.answerText(cid, "yes"))
        assertTrue(b.answerNo(cid))
        assertFalse(c.await())
    }

    @Test
    fun aSecondPromptWaitsForTheFirstAndAnAnswerForTheFirstDoesNotTouchIt() = runTest {
        val b = board()
        val first = confirmAsync(b, prompt(action = "first"))
        val firstId = waitFor("first") { b.pending.value }.id
        val second = confirmAsync(b, prompt(action = "second"))
        delay(150)
        assertEquals("the second prompt is not shown while the first is pending", firstId, b.pending.value?.id)
        assertFalse(second.isCompleted)

        assertTrue(b.answerNo(firstId))
        assertFalse(first.await())

        val secondId = waitFor("second") { b.pending.value?.takeIf { it.id != firstId } }.id
        assertNotEquals(firstId, secondId)
        assertEquals("second", b.pending.value?.action)

        // a late tap for the first prompt, from a window that was not yet closed
        b.markVisible(firstId)
        now = 100_000
        assertFalse(b.answerYes(firstId))
        assertFalse(b.answerNo(firstId))
        assertEquals("the second prompt is still pending", secondId, b.pending.value?.id)
        assertFalse(second.isCompleted)

        b.markVisible(secondId)
        now = 100_700
        assertTrue(b.answerYes(secondId))
        assertTrue(second.await())
    }

    @Test
    fun anAnswerForAnIdThatNeverExistedIsIgnored() = runTest {
        val b = board()
        assertFalse(b.answerYes(1))
        assertFalse(b.answerNo(1))
        assertFalse(b.answerText(1, "x"))
        val answer = confirmAsync(b)
        val id = waitFor("pending") { b.pending.value }.id
        b.markVisible(id + 1)
        now = 10_000
        assertFalse(b.answerYes(id + 1))
        assertFalse(b.answerYes(id))
        assertFalse(b.answerNo(id + 7))
        assertFalse(answer.isCompleted)
        assertTrue(b.answerNo(id))
        assertFalse(answer.await())
    }

    @Test
    fun aCancelledCallerFreesTheBoardForTheNextPrompt() = runTest {
        val b = board()
        val closed = AtomicInteger()
        val first = async(Dispatchers.Default) { b.confirm(prompt(), onClose = { closed.incrementAndGet() }) }
        val firstId = waitFor("first") { b.pending.value }.id
        first.cancelAndJoin()
        assertNull(b.pending.value)
        assertEquals(1, closed.get())

        val second = confirmAsync(b)
        val secondId = waitFor("second") { b.pending.value }.id
        assertNotEquals(firstId, secondId)
        assertTrue(b.answerNo(secondId))
        assertFalse(second.await())
    }

    @Test
    fun aPromptThatWaitedBehindAnotherEndsAsNoWhenItsOwnTimeRunsOut() = runTest {
        val b = board(timeoutMs = 400)
        val first = confirmAsync(b, prompt(action = "first"))
        waitFor("first") { b.pending.value }
        delay(100)
        // asked 100 ms later: it waits for the first (which ends at 400 ms), is then shown, and nobody answers it
        val second = async(Dispatchers.Default) { b.confirm(prompt(action = "second")) }
        assertFalse(first.await())
        // the second one got its turn after the first timed out, and ends as a no when its own time runs out
        assertFalse(second.await())
        assertNull(b.pending.value)
    }

    @Test
    fun openAndCloseCallbacksRunOncePerPrompt() = runTest {
        val b = board()
        val opened = AtomicInteger()
        val closed = AtomicInteger()
        val answer = async(Dispatchers.Default) { b.confirm(prompt(), { opened.incrementAndGet() }, { closed.incrementAndGet() }) }
        val id = waitFor("pending") { b.pending.value }.id
        assertEquals(1, opened.get())
        assertEquals(0, closed.get())
        b.answerNo(id)
        answer.await()
        assertEquals(1, closed.get())
    }

    @Test
    fun theTextsOfAPromptAreCleanedAndCut() = runTest {
        val b = board()
        val bidi = String(intArrayOf(0x202E), 0, 1)
        val hostile = ConfirmPrompt("run-1", "Evil\u0000App" + bidi + "x".repeat(500), "tap\n\n\"Pay\"" + bidi, "why ".repeat(300))
        val answer = async(Dispatchers.Default) { b.confirm(hostile) }
        val p = waitFor("pending") { b.pending.value }
        assertTrue(p.app.length <= 60)
        assertTrue(p.why.length <= 240)
        assertEquals("tap \"Pay\"", p.action)
        assertFalse(p.app.contains('\u0000'))
        assertFalse(p.app.contains(bidi))
        assertFalse(p.isQuestion)
        b.answerNo(p.id)
        answer.await()
    }
}
