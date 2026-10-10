import os
base = r"H:\UStaaf\.claude\worktrees\agent-ac34ad0bc1b4ddd46\app\app\src\main\java\com\swarmos\app\memcloud"

def edit(name, pairs):
    p = os.path.join(base, name)
    s = open(p, encoding="utf-8").read()
    for old, new in pairs:
        if old not in s:
            raise SystemExit("missing in %s: %s" % (name, old[:70]))
        s = s.replace(old, new, 1)
    open(p, "w", encoding="utf-8").write(s)

edit("MemCloudState.kt", [
("""    /** Interface address to listen on; must belong to this phone. Default: this phone's link address. */
    val bind: String? = null,
    val threads: Int = 8,
)""", """    /** Link address to listen on; must belong to this phone's Wi-Fi or Direct interface. Never 0.0.0.0. Default: the address this request arrived on. */
    val bind: String? = null,
    val threads: Int = 8,
    /** Model servers the driver stopped on this phone; they are restarted when rpc-server stops, whoever stops it. */
    val restore: List<RestoreItem> = emptyList(),
)"""),
("""    val args: List<String>,
)""", """    val args: List<String>,
    /** Name of the phone this one is lending memory to, null when not lending. */
    val lendingTo: String? = null,
)"""),
])

edit("MemCloudModule.kt", [
("""            val req = body<RpcStartRequest>() ?: RpcStartRequest()
            when (val r = RpcWorker.start(req)) {""", """            val req = body<RpcStartRequest>() ?: RpcStartRequest()
            // the address the signed request arrived on is the link the driver uses: bind there unless the body names one
            val via = runCatching { call.request.local.localAddress }.getOrNull()
            val from = call.request.headers[Signer.HEADER_NODE]
            when (val r = RpcWorker.start(req, via, from)) {"""),
("""import com.swarmos.app.core.SwarmJson""", """import com.swarmos.app.core.Signer
import com.swarmos.app.core.SwarmJson"""),
("""import io.ktor.http.HttpStatusCode""", """import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header"""),
("""        post("/rpc/stop") { call.respond(RpcWorker.stop()) }""", """        post("/rpc/stop") { call.respond(RpcWorker.stop("stopped by the driver")) }"""),
])

edit("MemCloud.kt", [
# pass restore items with rpc/start
("""        val body = SwarmJson.encodeToString(RpcStartRequest(port = Ports.RPC, cache = true, bind = p.host))""",
 """        val body = SwarmJson.encodeToString(RpcStartRequest(port = Ports.RPC, cache = true, bind = p.host, restore = restore[p.nodeId] ?: emptyList()))"""),
# peers restore themselves when rpc-server stops
("""                    runCatching { AppGraph.nodeClient.raw(HttpMethod.Post, p.info.ip, "/memcloud/rpc/stop", "", 10_000) }
                    for (i in restore[p.info.nodeId] ?: emptyList()) {
                        runCatching { AppGraph.nodeClient.raw(HttpMethod.Post, p.info.ip, "/models/load", \"\"\"{"role":"${i.role}","file":"${i.file}","wait":false}\"\"\", 15_000) }
                    }""".replace('\\"\\"\\"', '"""'),
 """                    // the worker restarts its own model servers when rpc-server stops (it holds the list from /rpc/start),
                    // and does the same on its own if this phone's heartbeat is lost for 10 s
                    runCatching { AppGraph.nodeClient.raw(HttpMethod.Post, p.info.ip, "/memcloud/rpc/stop", "", 10_000) }"""),
])
print("ok")
