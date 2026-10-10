import os
base = r"H:\UStaaf\.claude\worktrees\agent-ac34ad0bc1b4ddd46\app\app\src\main\java\com\swarmos\app\memcloud"

def edit(name, pairs):
    p = os.path.join(base, name)
    s = open(p, encoding="utf-8").read()
    for old, new in pairs:
        if old not in s:
            raise SystemExit("missing in %s: %s" % (name, old[:80]))
        s = s.replace(old, new, 1)
    open(p, "w", encoding="utf-8").write(s)

# ---------------- state
edit("MemCloudState.kt", [
("""/** PLANNING, STARTING_PEERS""", """@Serializable
data class RestoreItem(val role: String, val file: String, val mb: Long? = null)

/** PLANNING, STARTING_PEERS"""),
("""    /** Heartbeat round trip from the driver, null for the driver itself and while the peer is away. */
    val rttMs: Double? = null,""", """    /** Heartbeat round trip from the driver (an HTTP call), null for the driver itself and while the peer is away. */
    val rttMs: Double? = null,
    /** Median TCP connect time from the driver to this phone's rpc-server port: one network round trip, no HTTP on top. */
    val tcpRttMs: Double? = null,"""),
("""    /** Mean heartbeat round trip over the peers in the split. */
    val rttMs: Double? = null,""", """    /** Mean heartbeat round trip (HTTP) over the peers in the split. */
    val rttMs: Double? = null,
    /** Mean TCP connect round trip to the peers' rpc-server ports; the number ExpertMesh uses. */
    val tcpRttMs: Double? = null,"""),
("""    /** Name of the phone this one is lending memory to, null when not lending. */
    val lendingTo: String? = null,""", """    /** Name of the phone this one is lending memory to, null when not lending. */
    val lendingTo: String? = null,
    /** Milliseconds until the loan ends unless the driver renews it, null when not lending. */
    val leaseMsLeft: Long? = null,"""),
])

# ---------------- module
edit("MemCloudModule.kt", [
("""            when (val r = RpcWorker.start(req, via, from)) {
                is RpcWorker.StartResult.Ok -> call.respond(if (r.state.state == "READY") HttpStatusCode.OK else HttpStatusCode.Conflict, r.state)
                is RpcWorker.StartResult.Refused -> call.respond(HttpStatusCode.BadRequest, ErrorBody(r.message))
            }
        }
        post("/rpc/stop") { call.respond(RpcWorker.stop("stopped by the driver")) }""", """            when (val r = RpcWorker.start(req, via, from)) {
                is RpcLender.StartResult.Ok -> call.respond(if (r.ready) HttpStatusCode.OK else HttpStatusCode.Conflict, RpcWorker.state())
                is RpcLender.StartResult.Refused -> call.respond(HttpStatusCode.BadRequest, ErrorBody(r.message))
            }
        }
        // the driver extends the loan every few seconds; without a renewal it ends by itself after RpcLender.LEASE_MS
        post("/rpc/renew") {
            when (val r = RpcWorker.renew(call.request.headers[Signer.HEADER_NODE])) {
                is RpcLender.RenewResult.Ok -> call.respond(RpcWorker.state())
                is RpcLender.RenewResult.Refused -> call.respond(HttpStatusCode.Conflict, ErrorBody(r.message))
            }
        }
        post("/rpc/stop") { call.respond(RpcWorker.stop("stopped by the driver")) }"""),
(""" * Worker: POST /rpc/start {port, cache, bind, threads}, POST /rpc/stop, GET /rpc/state.""", """ * Worker: POST /rpc/start {port, cache, bind, threads, restore}, POST /rpc/renew, POST /rpc/stop, GET /rpc/state."""),
])

# ---------------- screen
edit("MemCloudScreen.kt", [
("""                Text("It stops when the split ends or that phone is out of reach for 10 s.", style = SwarmType.label.copy(color = SwarmColors.graphite))""",
 """                Text(l.leaseSentence, style = SwarmType.label.copy(color = SwarmColors.graphite))"""),
("""                if (!fit.ok) ErrorNote("May not fit: needs ${gb(fit.needMb * 1048576)} GB with buffers, has ${gb(fit.haveMb * 1048576)} GB.")""",
 """                if (!fit.ok) ErrorNote("May not fit: the planning estimate is ${gb(fit.needMb * 1048576)} GB (weights plus a ${SplitPlanner.OVERHEAD_MB / 1024.0} GB allowance), this phone can give ${gb(fit.haveMb * 1048576)} GB.")"""),
("""            p.rttMs?.let { MetricRow("Round trip from this phone", "%.1f".format(it), "ms") }""",
 """            p.rttMs?.let { MetricRow("Heartbeat round trip (HTTP)", "%.1f".format(it), "ms") }
            p.tcpRttMs?.let { MetricRow("TCP round trip to rpc-server", "%.1f".format(it), "ms") }"""),
("""        for (p in st.phones.filter { !it.isDriver }) p.rttMs?.let { MetricRow("Round trip to ${p.name}", "%.1f".format(it), "ms") }""",
 """        for (p in st.phones.filter { !it.isDriver }) p.tcpRttMs?.let { MetricRow("TCP round trip to ${p.name}", "%.1f".format(it), "ms") }"""),
("""        val rtt = st.rttMs
        val boundaries""", """        val rtt = st.tcpRttMs
        val boundaries"""),
("""            ErrorNote("No round trip measured yet: load a split with at least one other phone.")""",
 """            ErrorNote("No round trip measured yet: load a split with at least one other phone (it is a TCP connect to the rpc-server port).")"""),
("""            MetricRow("Round trip (heartbeat)", "%.1f".format(rtt), "ms")
            MetricRow("Experts on other phones: ${m.moeLayers} layers x %.1f ms".format(rtt), "%.0f".format(remoteMs), "ms per token")
            MetricRow("Ceiling with remote experts", tok(ceiling), "tok/s")""",
 """            MetricRow("Round trip, TCP connect to rpc-server", "%.1f".format(rtt), "ms")
            MetricRow("Estimate if experts were remote: ${m.moeLayers} layers x %.1f ms".format(rtt), "%.0f".format(remoteMs), "ms per token")
            MetricRow("Estimated ceiling, network wait only", tok(ceiling), "tok/s")"""),
("""                MetricRow("Layer ranges: $boundaries boundaries x %.1f ms".format(rtt), "%.0f".format(rangeMs), "ms per token")""",
 """                MetricRow("Estimate with layer ranges: $boundaries boundaries x %.1f ms".format(rtt), "%.0f".format(rangeMs), "ms per token")"""),
("""    val lending by RpcWorker.lending.collectAsStateWithLifecycle()
""", """    val lending by RpcWorker.lending.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { MemCloud.onAppStart() } // cleans up after a killed session, off the main thread
"""),
])

# ---------------- ExpertMesh doc
edit("ExpertMesh.kt", [
(""" * ExpertMesh arithmetic. Inputs are the model file (layer count, experts) and live measurements (round-trip time of
 * the link); nothing is assumed.""", """ * ExpertMesh arithmetic. Inputs are the model file (layer count, experts) and a live measurement: the TCP connect time to
 * the rpc-server port, which is one network round trip (not the HTTP heartbeat, which adds the server's own handling).
 * The results are estimates of network wait only, never measured speeds."""),
("""    /** Mean of the positive samples (heartbeat round trips of the phones in the split), null when there are none. */""",
 """    /** Mean of the positive samples (round trips of the phones in the split), null when there are none. */"""),
])
print("ok")
