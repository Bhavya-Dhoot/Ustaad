package com.swarmos.app.memcloud

import com.swarmos.app.llm.Role
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A worker phone with a clock the test moves. */
private class FakeLender : LenderEnv {
    var now = 1_000_000L
    val calls = mutableListOf<String>()
    var ready = false
    var bind: String? = null
    var port = 0
    val models = mutableSetOf("small.gguf", "embed.gguf", "evil.gguf")
    var startFails = false
    var links = listOf(LinkAddr("192.168.242.23", false), LinkAddr("192.168.49.1", true))

    override fun nowMs() = now
    override fun selfName() = "Phone A"
    override fun linkAddresses() = links
    override fun peerName(nodeId: String?) = if (nodeId == "c") "Phone C" else null
    override fun threads() = 6
    override suspend fun startRpc(bind: String, port: Int, threads: Int, cache: Boolean): Boolean {
        calls += "startRpc $bind:$port t=$threads cache=$cache"
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
    override fun setActivity(owner: String, text: String, onStop: () -> Unit) { calls += "set $owner $text" }
    override fun clearActivity(owner: String) { calls += "clear $owner" }
    override fun audit(kind: String, vararg kv: Pair<String, String>) { calls += "audit $kind" }
}

class RpcLenderTest {
    private val env = FakeLender()
    private val lender = RpcLender(env)
    private val restore = listOf(RestoreItem("chat", "small.gguf"), RestoreItem("embed", "embed.gguf"))

    private fun start(bind: String? = "192.168.49.1", from: String? = "c", restore: List<RestoreItem> = this.restore) =
        runBlocking { lender.start(RpcStartRequest(bind = bind, restore = restore), null, from) }

    @Test fun theBindAddressMustBeAnAdvertisableLinkAddress() {
        for (bad in listOf("0.0.0.0", "::", "127.0.0.1", "", "10.20.30.40" /* e.g. a cellular address */, "192.168.49.2" /* not ours */)) {
            val r = start(bad)
            assertTrue("$bad: $r", r is RpcLender.StartResult.Refused)
        }
        assertFalse(env.ready)
        assertTrue(env.calls.none { it.startsWith("startRpc") })
        // the request's own local address is used when the body names none
        val r = runBlocking { lender.start(RpcStartRequest(), "192.168.242.23", "c") }
        assertEquals(RpcLender.StartResult.Ok(true), r)
        assertEquals("192.168.242.23", env.bind)
        assertEquals("Wi-Fi network", lender.lending.value!!.link)
    }

    @Test fun aDirectAddressIsNamedAsTheDirectLink() {
        assertEquals(RpcLender.StartResult.Ok(true), start("192.168.49.1"))
        val l = lender.lending.value!!
        assertEquals("direct link", l.link)
        assertEquals("Phone A is lending memory to Phone C on the direct link (192.168.49.1).", l.sentence)
        assertTrue(l.leaseSentence.contains("${RpcLender.LEASE_MS / 1000} s"))
        assertTrue(env.calls.contains("set ${RpcLender.ACTIVITY_OWNER} Lending memory to Phone C."))
    }

    @Test fun theLoanEndsWhenTheLeaseRunsOutWhateverTheDriverHeartbeatSays() {
        start()
        env.now += RpcLender.LEASE_MS - 1_000
        lender.tick()
        assertNotNull(lender.lending.value)
        env.now += 2_000
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
            env.now += RpcLender.LEASE_MS - 2_000
            assertEquals(RpcLender.RenewResult.Ok(RpcLender.LEASE_MS), lender.renew("c"))
            lender.tick()
            assertNotNull("renewed loan still alive after ${it + 1} renewals", lender.lending.value)
        }
        assertEquals(RpcLender.LEASE_MS, lender.leaseMsLeft())
        env.now += RpcLender.LEASE_MS + 1
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
        assertEquals(RpcLender.StartResult.Ok(true), start(from = "c"))
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

    @Test fun theLendingActivityOwnerIsNotTheDrivers() {
        assertTrue(RpcLender.ACTIVITY_OWNER != SplitCoordinator.ACTIVITY_OWNER)
    }
}
