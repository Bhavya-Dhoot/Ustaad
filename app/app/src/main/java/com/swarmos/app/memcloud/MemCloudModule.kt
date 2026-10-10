package com.swarmos.app.memcloud

import androidx.compose.runtime.Composable
import com.swarmos.app.SwarmModule
import com.swarmos.app.core.Signer
import com.swarmos.app.core.SwarmJson
import com.swarmos.app.link.ErrorBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * MemoryCloud: one phone drives a model whose layers sit on several phones (llama.cpp rpc-server), and the
 * Memory tab shows where every layer lives. Routes under /v1/memcloud/ (all signed):
 *
 * Worker: POST /rpc/start {port, cache, bind, threads, restore}, POST /rpc/renew, POST /rpc/stop, GET /rpc/state.
 * Coordinator: POST /start {file, peers, mode, layers}, POST /replan {peers}, POST /stop, POST /ask {prompt, maxTokens},
 * POST /baseline {timeoutSec}, GET /state, GET /plan?file=&peers=&mode=. Long operations answer 202 at once
 * (the node server drops a connection idle for 45 s); poll GET /state.
 */
object MemCloudModule : SwarmModule {
    override val id = "memcloud"
    override val title = "Memory"

    override fun Route.routes() {
        post("/rpc/start") {
            val req = body<RpcStartRequest>() ?: RpcStartRequest()
            // the address the signed request arrived on is the link the driver uses: bind there unless the body names one
            val via = runCatching { call.request.local.localAddress }.getOrNull()
            val from = call.request.headers[Signer.HEADER_NODE]
            when (val r = RpcWorker.start(req, via, from)) {
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
        post("/rpc/stop") { call.respond(RpcWorker.stop("stopped by the driver")) }
        get("/rpc/state") { call.respond(RpcWorker.state()) }

        get("/state") { call.respond(MemCloud.state.value) }

        get("/plan") {
            val q = call.request.queryParameters
            val file = q["file"] ?: return@get call.respond(HttpStatusCode.BadRequest, ErrorBody("file= is required"))
            val peers = q["peers"]?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
            val mode = if (q["mode"].equals("ram", true)) SplitMode.RAM else SplitMode.EQUAL
            try {
                val plan = MemCloud.previewPlan(file, peers, mode)
                call.respond(plan.shares.zip(plan.fits()).map { (s, f) -> "${s.device.name} layers ${s.range} weights ${s.weightBytes / (1024 * 1024)} MB need ${f.needMb} MB have ${f.haveMb} MB" })
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorBody(e.message ?: e.toString()))
            }
        }

        post("/start") {
            val req = body<StartRequest>()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorBody("""body must be {"file":"<name>.gguf","peers":["Phone B"],"mode":"equal|ram","layers":[..]}"""))
            val refused = MemCloud.start(req)
            if (refused != null) call.respond(HttpStatusCode.Conflict, ErrorBody(refused)) else call.respond(HttpStatusCode.Accepted, MemCloud.state.value)
        }

        post("/replan") {
            val req = body<ReplanRequest>() ?: ReplanRequest()
            val refused = MemCloud.replan(req.peers)
            if (refused != null) call.respond(HttpStatusCode.Conflict, ErrorBody(refused)) else call.respond(HttpStatusCode.Accepted, MemCloud.state.value)
        }

        post("/stop") {
            MemCloud.stop()
            call.respond(HttpStatusCode.Accepted, MemCloud.state.value)
        }

        post("/ask") {
            val req = body<AskRequest>() ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorBody("""body must be {"prompt":"...","maxTokens":200}"""))
            val refused = MemCloud.ask(req.prompt, req.maxTokens)
            if (refused != null) call.respond(HttpStatusCode.Conflict, ErrorBody(refused)) else call.respond(HttpStatusCode.Accepted, MemCloud.state.value)
        }

        post("/baseline") {
            val req = body<BaselineRequest>() ?: BaselineRequest()
            val refused = MemCloud.baseline(req.timeoutSec)
            if (refused != null) call.respond(HttpStatusCode.Conflict, ErrorBody(refused)) else call.respond(HttpStatusCode.Accepted, MemCloud.state.value)
        }
    }

    private suspend inline fun <reified T> RoutingContext.body(): T? {
        val text = call.receiveText()
        if (text.isBlank()) return null
        return runCatching { SwarmJson.decodeFromString<T>(text) }.getOrNull()
    }

    @Composable
    override fun Screen() = MemCloudScreen()
}
