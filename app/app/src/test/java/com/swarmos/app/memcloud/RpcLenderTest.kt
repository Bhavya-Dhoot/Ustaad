package com.swarmos.app.memcloud

import com.swarmos.app.llm.Role
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A worker phone with a clock the test moves. */
private class FakeLender : LenderEnv {
    var now = 1_000_000L
    var elapsed = 50_000L
    val calls = mutableListOf<String>()
    var ready = false
    var bind: String? = null
    var port = 0
    val models = mutableSetOf("small.gguf", "embed.gguf", "evil.gguf")
    var startFails = false
    var links = listOf(LinkAddr("192.168.242.23", false), LinkAddr("192.168.49.1", true))

    override fun nowMs() = now
    override fun elapsedMs() = elapsed
    override fun selfName() = "Phone A"
    override fun linkAddresses() = links
    override fun peerName(nodeId: String?) = if (nodeId == "c") "Phone C" else null
    override fun threads() = 6
    override suspend fun startRpc(bind: String, port: Int, threads: Int, cache: Boolean): Boolean {
        calls += "startRpc $bind:$port t=$threads cache=$cache"
        startGate?.await()
        if (throwOnStartRpc) throw IllegalStateException("exec failed")
        if (startFails) return false
        ready = true; this.bind = bind; this.port = port
        return true
    }
    override fun rpcReady() = ready
    override fun rpcBind() = bind
    override fun rpcPort() = port
    override fun stopRpc() { calls += "stopRpc"; ready = false }
    override fun modelFileExists(file: String) = file in models
    override fun restoreLocal(role: Role, file: String) { calls += "restore ${role.tag} $file" }

    /** The chat and embedding servers that run on this phone until the lender stops them. */
    val own = mutableListOf(RestoreItem("chat", "small.gguf", 5000), RestoreItem("embed", "embed.gguf", 600))
    var throwOnStartRpc = false

    /** When set, startRpc waits here: a start that is still in flight. */
    var startGate: CompletableDeferred<Unit>? = null
    var restoringAtStop = mutableListOf<List<RestoreItem>>()
    lateinit var lender: RpcLender
    var memAvail = 20_000L
    var thermal = 0
    override fun memAvailMb() = memAvail
    override fun thermalStatus() = thermal
    override fun ownModels() = own.toList()
    override fun stopOwn(role: Role) {
        calls += "stopOwn ${role.tag}"
        restoringAtStop += lender.restoring // what a lease expiry would bring back at the moment the server is stopped
        own.removeAll { it.role == role.tag }
    }
    override fun setActivity(owner: String, text: String, onStop: () -> Unit) { calls += "set $owner $text" }
    override fun clearActivity(owner: String) { calls += "clear $owner" }
    override fun audit(kind: String, vararg kv: Pair<String, String>) { calls += "audit $kind" }
}

class RpcLenderTest {
    /** A start that worked: rpc-server is ready, [stopped] is what the lender stopped itself, and the loan has a token. */
    private fun assertOk(r: RpcLender.StartResult, stopped: List<RestoreItem>? = null) {
        assertTrue("$r", r is RpcLender.StartResult.Ok && r.ready)
        r as RpcLender.StartResult.Ok
        assertEquals(stopped, r.stopped)
        assertNotNull(r.loan)
    }

    private val env = FakeLender()
    private val lender = RpcLender(env).also { env.lender = it }
    private val restore = listOf(RestoreItem("chat", "small.gguf"), RestoreItem("embed", "embed.gguf"))

    private fun start(bind: String? = "192.168.49.1", from: String? = "c", restore: List<RestoreItem> = this.restore, stopOwn: Boolean = false, needMb: Long? = null) =
        runBlocking { lender.start(RpcStartRequest(bind = bind, restore = restore, stopOwn = stopOwn, needMb = needMb), null, from) }

    @Test fun theBindAddressMustBeAnAdvertisableLinkAddress() {
        for (bad in listOf("0.0.0.0", "::", "127.0.0.1", "", "10.20.30.40" /* e.g. a cellular address */, "192.168.49.2" /* not ours */)) {
            val r = start(bad)
            assertTrue("$bad: $r", r is RpcLender.StartResult.Refused)
        }
        assertFalse(env.ready)
        assertTrue(env.calls.none { it.startsWith("startRpc") })
        // the request's own local address is used when the body names none
        val r = runBlocking { lender.start(RpcStartRequest(), "192.168.242.23", "c") }
        assertOk(r)
        assertEquals("192.168.242.23", env.bind)
        assertEquals("Wi-Fi network", lender.lending.value!!.link)
    }

    @Test fun aDirectAddressIsNamedAsTheDirectLink() {
        assertOk(start("192.168.49.1"))
        val l = lender.lending.value!!
        assertEquals("direct link", l.link)
        assertEquals("Phone A is lending memory to Phone C on the direct link (192.168.49.1).", l.sentence)
        assertTrue(l.leaseSentence.contains("${RpcLender.LEASE_MS / 1000} s"))
        assertTrue(env.calls.contains("set ${RpcLender.ACTIVITY_OWNER} Lending memory to Phone C."))
    }

    @Test fun theLoanEndsWhenTheLeaseRunsOutWhateverTheDriverHeartbeatSays() {
        start()
        env.elapsed += RpcLender.LEASE_MS - 1_000
        lender.tick()
        assertNotNull(lender.lending.value)
        env.elapsed += 2_000
        lender.tick()
        assertNull(lender.lending.value)
        assertFalse(env.ready)
        // this phone's own servers come back from the list the driver sent, and the line is cleared
        assertTrue(env.calls.contains("restore chat small.gguf"))
        assertTrue(env.calls.contains("restore embed embed.gguf"))
        assertTrue(env.calls.contains("clear ${RpcLender.ACTIVITY_OWNER}"))
    }

    @Test fun renewingExtendsTheLeaseFromNowNotFromTheOldEnd() {
        start()
        repeat(5) {
            env.elapsed += RpcLender.LEASE_MS - 2_000
            assertEquals(RpcLender.RenewResult.Ok(RpcLender.LEASE_MS), lender.renew("c"))
            lender.tick()
            assertNotNull("renewed loan still alive after ${it + 1} renewals", lender.lending.value)
        }
        assertEquals(RpcLender.LEASE_MS, lender.leaseMsLeft())
        env.elapsed += RpcLender.LEASE_MS + 1
        lender.tick()
        assertNull(lender.lending.value)
    }

    @Test fun onlyTheDriverThatStartedTheLoanCanRenewOrReplaceIt() {
        start(from = "c")
        assertTrue(lender.renew("x") is RpcLender.RenewResult.Refused)
        assertTrue(lender.renew(null) is RpcLender.RenewResult.Refused)
        val other = runBlocking { lender.start(RpcStartRequest(bind = "192.168.49.1"), null, "x") }
        assertTrue(other is RpcLender.StartResult.Refused)
        assertTrue((other as RpcLender.StartResult.Refused).message.contains("Phone C"))
        // the same driver may call start again (idempotent): rpc-server is not restarted
        assertOk(start(from = "c"))
        assertEquals(1, env.calls.count { it.startsWith("startRpc") })
    }

    @Test fun renewingWithoutALoanIsRefused() {
        assertTrue(lender.renew("c") is RpcLender.RenewResult.Refused)
    }

    @Test fun aDeadRpcServerEndsTheLoanAndClearsTheLine() {
        start()
        env.ready = false
        lender.tick()
        assertNull(lender.lending.value)
        assertTrue(env.calls.contains("clear ${RpcLender.ACTIVITY_OWNER}"))
        assertTrue(env.calls.contains("restore chat small.gguf"))
    }

    @Test fun stoppingFromTheDriverOrTheBannerRestoresOnceAndSayingItTwiceDoesNothing() {
        start()
        lender.stop("stopped by the driver")
        lender.stop("stopped by the driver")
        assertEquals(1, env.calls.count { it == "restore chat small.gguf" })
        assertEquals(1, env.calls.count { it == "audit memcloud.rpc.stop" })
    }

    @Test fun restoreItemsAreReducedToFileNamesAndMustExistInTheModelsFolder() {
        start(restore = listOf(
            RestoreItem("chat", "../../evil.gguf"),   // reduced to evil.gguf, which exists
            RestoreItem("embed", "missing.gguf"),     // not in the folder: dropped
            RestoreItem("rpc", "small.gguf"),         // not a model role: dropped
            RestoreItem("chat", "notes.txt"),         // not a gguf: dropped
        ))
        lender.stop("test")
        assertEquals(listOf("restore chat evil.gguf"), env.calls.filter { it.startsWith("restore ") })
    }

    @Test fun aFailedRpcStartLeavesNoLoanAndNoActivityLine() {
        env.startFails = true
        val r = start()
        assertEquals(RpcLender.StartResult.Ok(false), r)
        assertNull(lender.lending.value)
        assertTrue(env.calls.none { it.startsWith("set ") })
    }

    // ---------- finding 1: the lender stops its own servers only after it holds the restore list ----------

    @Test fun aLenderThatIsAskedToStopItsOwnServersRecordsTheListFirstAndStopsThemBeforeRpcServerStarts() {
        val r = start(restore = emptyList(), stopOwn = true)
        assertOk(r, listOf(RestoreItem("chat", "small.gguf", 5000), RestoreItem("embed", "embed.gguf", 600)))
        assertEquals(listOf("stopOwn chat", "stopOwn embed"), env.calls.filter { it.startsWith("stopOwn") })
        assertTrue("the servers are stopped before rpc-server starts", env.calls.indexOf("stopOwn embed") < env.calls.indexOfFirst { it.startsWith("startRpc") })
        // when each server was stopped the list already named both of them
        assertEquals(listOf("chat", "embed"), env.restoringAtStop.first().map { it.role })
        assertEquals(listOf("chat", "embed"), env.restoringAtStop.last().map { it.role })
    }

    @Test fun theServersALenderStoppedItselfComeBackWhenTheLeaseRunsOutEvenIfTheDriverSentNoList() {
        start(restore = emptyList(), stopOwn = true)
        env.elapsed += RpcLender.LEASE_MS + 1
        lender.tick()
        assertNull(lender.lending.value)
        assertTrue(env.calls.contains("restore chat small.gguf"))
        assertTrue(env.calls.contains("restore embed embed.gguf"))
    }

    @Test fun aStartThatFailsAfterTheServersWereStoppedBringsThemBackAtOnce() {
        env.startFails = true
        assertEquals(RpcLender.StartResult.Ok(false), start(stopOwn = true))
        assertNull(lender.lending.value)
        assertTrue(env.calls.contains("restore chat small.gguf"))
        assertTrue(env.calls.contains("restore embed embed.gguf"))
        assertTrue(lender.restoring.isEmpty())

        env.calls.clear(); env.own.addAll(listOf(RestoreItem("chat", "small.gguf", 5000)))
        env.startFails = false; env.throwOnStartRpc = true
        try { start(stopOwn = true); org.junit.Assert.fail("the start error should reach the caller") } catch (e: IllegalStateException) { /* expected */ }
        assertTrue(env.calls.contains("restore chat small.gguf"))
    }

    @Test fun aDriverThatDoesNotAskForItGetsTheOldBehaviour() {
        val r = start()
        assertOk(r)
        assertTrue("nothing is stopped by the lender", env.calls.none { it.startsWith("stopOwn") })
        assertEquals(listOf("chat", "embed"), lender.restoring.map { it.role })
    }

    // ---------- finding 4: a lender looks at its own memory and heat ----------

    @Test fun aHotPhoneRefusesAtThermalStatusFourAndNotAtThree() {
        env.thermal = 4
        val r = start(stopOwn = true)
        assertTrue(r is RpcLender.StartResult.Refused)
        assertTrue((r as RpcLender.StartResult.Refused).message.contains("too hot") && r.message.contains("thermal status 4"))
        assertTrue("nothing was stopped and no loan was taken", env.calls.none { it.startsWith("stopOwn") || it.startsWith("startRpc") })
        assertNull(lender.lending.value)
        assertTrue(lender.restoring.isEmpty())
        env.thermal = 3
        assertOk(start(stopOwn = true), listOf(RestoreItem("chat", "small.gguf", 5000), RestoreItem("embed", "embed.gguf", 600)))
    }

    @Test fun aPhoneWithTooLittleMemoryRefusesBeforeStoppingAnything() {
        env.memAvail = 1_000
        val r = start(stopOwn = true, needMb = 7_000)
        assertTrue(r is RpcLender.StartResult.Refused)
        // 1000 MB free plus the 5600 MB its own servers would give back
        assertTrue((r as RpcLender.StartResult.Refused).message.contains("6600 MB") && r.message.contains("7000 MB"))
        assertTrue(env.calls.none { it.startsWith("stopOwn") || it.startsWith("startRpc") })
        assertNull(lender.lending.value)
    }

    @Test fun theServersAboutToBeStoppedCountAsFreeWhenTheLenderStopsThemItself() {
        env.memAvail = 3_000 // plus 5000 + 600 held by its own servers = 8600
        assertTrue(start(stopOwn = true, needMb = 7_000) is RpcLender.StartResult.Ok)
    }

    @Test fun whenTheDriverStoppedTheServersAlreadyOnlyWhatIsFreeCounts() {
        env.memAvail = 3_000
        assertTrue(start(stopOwn = false, needMb = 7_000) is RpcLender.StartResult.Refused)
        env.memAvail = 7_000
        assertTrue(start(stopOwn = false, needMb = 7_000) is RpcLender.StartResult.Ok)
    }

    @Test fun noNeedMeansNoCheckAndARunningLoanIsNotAskedAgain() {
        env.memAvail = 10 // an older driver, or the override: nothing asked
        assertTrue(start() is RpcLender.StartResult.Ok)
        // the loan holds the weights now, so memory is low and the phone is warm; the same driver starting again must not be refused
        env.thermal = 5
        assertOk(start(needMb = 7_000))
    }

    // ---------- finding 6: the lease runs on elapsed time, not on the wall clock ----------

    @Test fun aWallClockStepForwardDoesNotEndALoanThatHasTimeLeft() {
        start()
        env.now += 3_600_000 // the user or the network sets the clock an hour ahead
        env.elapsed += 1_000
        lender.tick()
        assertNotNull(lender.lending.value)
        assertTrue(lender.leaseMsLeft()!! in (RpcLender.LEASE_MS - 1_100)..(RpcLender.LEASE_MS - 900))
    }

    @Test fun aWallClockStepBackwardDoesNotExtendALoan() {
        start()
        env.now -= 3_600_000
        env.elapsed += RpcLender.LEASE_MS + 1
        lender.tick()
        assertNull("the lease ran out in real time whatever the clock says", lender.lending.value)
        assertTrue(env.calls.contains("restore chat small.gguf"))
    }

    @Test fun renewingMeasuresTheNewLeaseOnElapsedTime() {
        start()
        env.elapsed += RpcLender.LEASE_MS - 500
        env.now += 7 * 24 * 3_600_000L
        assertEquals(RpcLender.RenewResult.Ok(RpcLender.LEASE_MS), lender.renew("c"))
        assertEquals(RpcLender.LEASE_MS, lender.leaseMsLeft())
        env.now -= 30L * 24 * 3_600_000
        env.elapsed += RpcLender.LEASE_MS - 1
        lender.tick()
        assertNotNull(lender.lending.value)
    }

    @Test fun theLendingSinceStaysOnTheWallClock() {
        start()
        assertEquals(1_000_000L, lender.lending.value!!.sinceMs)
    }

    // ---------- finding 10: ownership is taken before the first suspension; each loan has a generation token ----------

    private fun tokenOf(r: RpcLender.StartResult) = (r as RpcLender.StartResult.Ok).loan!!
    private fun req(stopOwn: Boolean = false) = RpcStartRequest(bind = "192.168.49.1", stopOwn = stopOwn)

    @Test fun twoDriversCannotBothBeAcceptedWhileTheFirstStartIsStillSuspended() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        env.startGate = gate
        val first = async { lender.start(req(), null, "c") }
        yield() // the first start runs until rpc-server is starting
        assertTrue(env.calls.any { it.startsWith("startRpc") })
        val second = lender.start(req(), null, "x")
        assertTrue("$second", second is RpcLender.StartResult.Refused && second.message.contains("lent to Phone C"))
        gate.complete(Unit)
        assertOk(first.await())
        assertEquals("rpc-server was started once, for the first driver", 1, env.calls.count { it.startsWith("startRpc") })
        assertEquals("Phone C", lender.lending.value!!.driverName)
    }

    @Test fun theSameDriverAskingTwiceAtOnceIsToldTheFirstStartIsStillRunning() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        env.startGate = gate
        val first = async { lender.start(req(), null, "c") }
        yield()
        val again = lender.start(req(), null, "c")
        assertTrue("$again", again is RpcLender.StartResult.Refused && again.message.contains("still starting"))
        gate.complete(Unit)
        assertOk(first.await())
    }

    @Test fun aRefusedOrFailedStartGivesTheReservationBack() {
        env.thermal = 5
        assertTrue(start(from = "c") is RpcLender.StartResult.Refused)
        env.thermal = 0
        env.startFails = true
        assertEquals(RpcLender.StartResult.Ok(false), start(from = "c"))
        env.startFails = false
        assertOk(start(from = "x")) // nothing is held for c
        assertEquals("x", lender.lending.value!!.driverName)
    }

    @Test fun aStopFromTheDriverWhileRpcServerIsStartingEndsTheLoanForGood() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        env.startGate = gate
        val first = async { lender.start(RpcStartRequest(bind = "192.168.49.1", stopOwn = true), null, "c") }
        yield()
        assertEquals(RpcLender.StopResult.Ok, lender.stopFrom("c", null, "stopped by the driver"))
        assertTrue("the servers it had stopped are back", env.calls.contains("restore chat small.gguf") && env.calls.contains("restore embed embed.gguf"))
        gate.complete(Unit)
        val r = first.await()
        assertTrue("$r", r is RpcLender.StartResult.Refused)
        assertNull(lender.lending.value)
        assertFalse("rpc-server does not run on for nobody", env.ready)
        assertEquals(1, env.calls.count { it == "restore chat small.gguf" })
    }

    @Test fun renewAndStopMustCarryTheTokenOfTheLoan() {
        val t = tokenOf(start())
        assertTrue(lender.renew("c", "not-this-loan") is RpcLender.RenewResult.Refused)
        assertEquals(RpcLender.RenewResult.Ok(RpcLender.LEASE_MS), lender.renew("c", t))
        assertEquals("a driver on the older build sends no token and is the owner", RpcLender.RenewResult.Ok(RpcLender.LEASE_MS), lender.renew("c", null))
        assertTrue(lender.stopFrom("c", "not-this-loan", "late") is RpcLender.StopResult.Refused)
        assertTrue(lender.stopFrom("x", t, "late") is RpcLender.StopResult.Refused)
        assertNotNull("the loan runs on", lender.lending.value)
        assertEquals(RpcLender.StopResult.Ok, lender.stopFrom("c", t, "stopped by the driver"))
        assertNull(lender.lending.value)
        assertTrue(env.calls.contains("restore chat small.gguf"))
    }

    @Test fun aStopWithoutATokenIsAcceptedOnlyFromTheOwner() {
        start(from = "c")
        val other = lender.stopFrom("x", null, "stopped by the driver")
        assertTrue(other is RpcLender.StopResult.Refused && other.message.contains("Phone C"))
        assertNotNull(lender.lending.value)
        assertEquals(RpcLender.StopResult.Ok, lender.stopFrom("c", null, "stopped by the driver"))
        assertNull(lender.lending.value)
    }

    @Test fun aLateStopFromAnEarlierLoanCannotEndTheNewOne() {
        val t1 = tokenOf(start(from = "c"))
        assertEquals(RpcLender.StopResult.Ok, lender.stopFrom("c", t1, "stopped by the driver"))
        val t2 = tokenOf(start(from = "c"))
        assertNotEquals("every loan has its own token", t1, t2)
        // the same driver, an earlier loan: refused, and the new loan keeps its lease
        assertTrue(lender.stopFrom("c", t1, "late") is RpcLender.StopResult.Refused)
        assertTrue(lender.renew("c", t1) is RpcLender.RenewResult.Refused)
        assertNotNull(lender.lending.value)
        // another driver takes over; the old one's late stop (right token for its own loan) is refused too
        lender.stopFrom("c", t2, "stopped by the driver")
        assertOk(start(from = "x"))
        assertTrue(lender.stopFrom("c", t2, "late") is RpcLender.StopResult.Refused)
        assertNotNull(lender.lending.value)
    }

    @Test fun aRestartByTheSameDriverKeepsTheLoanAndItsToken() {
        val t = tokenOf(start(from = "c"))
        assertEquals(t, tokenOf(start(from = "c")))
        assertEquals(1, env.calls.count { it.startsWith("startRpc") })
    }

    @Test fun theLendingActivityOwnerIsNotTheDrivers() {
        assertTrue(RpcLender.ACTIVITY_OWNER != SplitCoordinator.ACTIVITY_OWNER)
    }
}
