package com.swarmos.app.sched

import com.swarmos.app.core.SwarmJson
import com.swarmos.app.link.ErrorBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.request.receiveText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException

/** Parses the request body as [T]; answers 400 and returns null when it is malformed. */
private suspend inline fun <reified T> RoutingCall.receiveJson(): T? =
    try {
        SwarmJson.decodeFromString<T>(receiveText())
    } catch (e: SerializationException) {
        respond(HttpStatusCode.BadRequest, ErrorBody("bad request: ${e.message}"))
        null
    } catch (e: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, ErrorBody("bad request: ${e.message}"))
        null
    }

/** Runs [block] and turns the scheduler's refusals into their HTTP status: 429 busy, 409 model mismatch, 500 anything else. */
private suspend inline fun <reified T : Any> RoutingCall.answer(block: () -> T) {
    try {
        respond(block())
    } catch (e: BusyException) {
        respond(HttpStatusCode.TooManyRequests, BusyBody(running = e.running, capacity = e.capacity, queued = e.queued))
    } catch (e: ModelMismatchException) {
        respond(HttpStatusCode.Conflict, MismatchBody(model = e.model))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        respond(HttpStatusCode.InternalServerError, ErrorBody(e.message ?: e.javaClass.simpleName))
    }
}

/** GET /caps, POST /chat, POST /embed: what a phone offers to the rest of the swarm. */
fun Route.schedNodeRoutes(node: SchedNode) {
    get("/caps") { call.answer { node.caps() } }

    post("/chat") {
        val job = call.receiveJson<ChatJobRequest>() ?: return@post
        call.answer { node.chat(job.jobId, job.request.toRequest(), job.model) }
    }

    post("/embed") {
        val job = call.receiveJson<EmbedJobRequest>() ?: return@post
        call.answer { node.embed(job.jobId, job.texts) }
    }
}

/** POST /batch starts a batch, GET /batch/{id} reads its progress and, when done, its report. */
fun Route.schedBatchRoutes(batches: BatchStore) {
    post("/batch") {
        val body = call.receiveJson<BatchStartRequest>() ?: return@post
        val requests = body.requests?.map { it.toRequest() } ?: body.builtin?.let { Bench.builtin(it) }
        if (requests.isNullOrEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ErrorBody("give requests or builtin \"${Bench.BENCH32}\""))
            return@post
        }
        if (body.mode != BatchStore.MODE_SWARM && body.mode != BatchStore.MODE_LOCAL) {
            call.respond(HttpStatusCode.BadRequest, ErrorBody("mode must be swarm or local"))
            return@post
        }
        val id = batches.start(requests, body.mode)
        call.respond(BatchStarted(id, requests.size))
    }

    get("/batch/{id}") {
        val report = batches.status(call.parameters["id"].orEmpty())?.value
        if (report == null) call.respond(HttpStatusCode.NotFound, ErrorBody("unknown batch")) else call.respond(report)
    }
}

/** POST /debug/peers {ips}: registers phones by address until the discovery module does it. */
fun Route.schedPeersRoute(register: suspend (List<String>) -> PeersResponse) {
    post("/debug/peers") {
        val body = call.receiveJson<PeersRequest>() ?: return@post
        call.answer { register(body.ips) }
    }
}
