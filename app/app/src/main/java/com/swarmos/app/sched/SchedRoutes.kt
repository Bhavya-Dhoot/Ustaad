package com.swarmos.app.sched

import com.swarmos.app.busy.BusyMode
import com.swarmos.app.busy.BusyReport
import com.swarmos.app.core.Signer
import com.swarmos.app.core.SwarmJson
import com.swarmos.app.link.ErrorBody
import com.swarmos.app.llm.ChatRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.request.receiveText
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/** Runs [check]; when it throws [RequestLimitException] answers 413 naming the limit and returns false. */
private suspend fun RoutingCall.withinLimits(check: () -> Unit): Boolean =
    try {
        check()
        true
    } catch (e: RequestLimitException) {
        respond(HttpStatusCode.PayloadTooLarge, LimitBody(e.message ?: "request too large", e.limit))
        false
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

/**
 * GET /caps, GET /busy, POST /chat, POST /chat-stream, POST /embed: what a phone offers to the rest of the swarm.
 * [peerName] turns the node id that signed a request into the phone's name for the live state line (null when unknown).
 * /busy is read-only: the "Keep busy" switch, its state and counters, and the share of the last 60 s spent on filler.
 */
fun Route.schedNodeRoutes(
    node: SchedNode,
    secret: () -> String,
    streamPingMs: Long = STREAM_PING_MS,
    peerName: (String?) -> String? = { null },
    busy: () -> BusyReport = BusyMode::report,
) {
    fun RoutingCall.caller(): String? = peerName(request.header(Signer.HEADER_NODE))

    get("/caps") { call.answer { node.caps() } }

    get("/busy") { call.answer { busy() } }

    post("/chat") {
        val job = call.receiveJson<ChatJobRequest>() ?: return@post
        if (!call.withinLimits { job.request.checkLimits() }) return@post
        SchedActivity.whileServing(call.caller()) {
            call.answer { node.chat(job.jobId, job.request.toRequest(), job.model) }
        }
    }

    // Server-sent events. The slot is taken before the 200 is committed, so a full phone still answers 429.
    post("/chat-stream") {
        val job = call.receiveJson<ChatJobRequest>() ?: return@post
        if (!call.withinLimits { job.request.checkLimits() }) return@post
        val slot = try {
            node.openStream(job.model)
        } catch (e: BusyException) {
            call.respond(HttpStatusCode.TooManyRequests, BusyBody(running = e.running, capacity = e.capacity, queued = e.queued))
            return@post
        } catch (e: ModelMismatchException) {
            call.respond(HttpStatusCode.Conflict, MismatchBody(model = e.model))
            return@post
        }
        try {
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            SchedActivity.whileServing(call.caller()) {
                call.respondBytesWriter(ContentType.Text.EventStream) { writeEvents(slot, job.request.toRequest(), job.jobId, secret(), streamPingMs) }
            }
        } finally {
            slot.close() // also runs when the caller hangs up: the next write or ping throws and ends the stream
        }
    }

    post("/embed") {
        val job = call.receiveJson<EmbedJobRequest>() ?: return@post
        if (!call.withinLimits { job.checkLimits() }) return@post
        SchedActivity.whileServing(call.caller()) {
            call.answer { node.embed(job.jobId, job.texts) }
        }
    }
}

const val STREAM_PING_MS = 2_000L

/** Writes the chunks of [slot]'s reply as events, with a comment line every [pingMs] so a vanished caller is noticed. */
private suspend fun ByteWriteChannel.writeEvents(slot: SchedNode.StreamSlot, req: ChatRequest, requestId: String, secret: String, pingMs: Long) = coroutineScope {
    val writing = Mutex()
    suspend fun send(text: String) = writing.withLock {
        writeStringUtf8(text)
        flush()
    }
    suspend fun event(name: String, data: String) = send("event: $name\ndata: $data\n\n")

    val ping = launch {
        while (true) {
            delay(pingMs)
            send(": ping\n\n")
        }
    }
    var finished = false
    val text = StringBuilder()
    // the last event carries the signature over the request id and everything sent in chunks
    suspend fun done(stats: StatsDto?) {
        finished = true
        val mac = StreamMac.compute(secret, requestId, text.toString())
        event("done", SwarmJson.encodeToString(StreamDone(stats, slot.nodeId, slot.modelName, mac)))
    }
    try {
        slot.chunks(req).collect { c ->
            if (c.done) {
                done(c.stats?.toDto())
            } else if (c.delta.isNotEmpty()) {
                text.append(c.delta)
                event("chunk", SwarmJson.encodeToString(ChunkDto(c.delta)))
            }
        }
        if (!finished) done(null)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        runCatching { event("error", SwarmJson.encodeToString(ErrorBody(e.message ?: e.javaClass.simpleName))) }
    } finally {
        ping.cancel()
    }
}

/** POST /batch starts a batch, GET /batch/{id} reads its progress and, when done, its report. */
fun Route.schedBatchRoutes(batches: BatchStore) {
    post("/batch") {
        val body = call.receiveJson<BatchStartRequest>() ?: return@post
        if (!call.withinLimits { body.checkLimits() }) return@post
        val requests = body.requests?.map { it.toRequest() } ?: body.builtin?.let { Bench.builtin(it) }
        if (requests.isNullOrEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ErrorBody("give requests or builtin \"${Bench.BENCH32}\""))
            return@post
        }
        if (body.mode != BatchStore.MODE_SWARM && body.mode != BatchStore.MODE_LOCAL) {
            call.respond(HttpStatusCode.BadRequest, ErrorBody("mode must be swarm or local"))
            return@post
        }
        // one batch at a time: two at once share the phones and neither measures anything
        val id = batches.tryStart(requests, body.mode)
        if (id == null) {
            call.respond(HttpStatusCode.Conflict, ErrorBody("a batch is already running"))
            return@post
        }
        call.respond(BatchStarted(id, requests.size))
    }

    get("/batch/{id}") {
        val report = batches.status(call.parameters["id"].orEmpty())?.value
        if (report == null) call.respond(HttpStatusCode.NotFound, ErrorBody("unknown batch")) else call.respond(report)
    }
}

/** GET /phones: how this phone currently sees every phone in the swarm (reachable, slots, own jobs in flight). */
fun Route.schedPhonesRoute(swarm: () -> SwarmLlm) {
    get("/phones") {
        call.answer {
            swarm().also { it.refreshCaps() }.phoneViews()
        }
    }
}

/** POST /debug/capacity {slots}: debug builds only, so a phone with the placeholder model can be given slots over adb. */
fun Route.schedCapacityRoute(set: (Int) -> Unit, caps: () -> Caps) {
    post("/debug/capacity") {
        val body = call.receiveJson<CapacityRequest>() ?: return@post
        set(body.slots)
        call.answer { caps() }
    }
}

/** POST /debug/peers {ips}: debug builds only; trusts phones by address through the link module (heartbeats included). */
fun Route.schedPeersRoute(register: suspend (List<String>) -> PeersResponse) {
    post("/debug/peers") {
        val body = call.receiveJson<PeersRequest>() ?: return@post
        call.answer { register(body.ips) }
    }
}
