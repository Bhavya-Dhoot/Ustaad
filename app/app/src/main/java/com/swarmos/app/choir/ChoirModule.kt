package com.swarmos.app.choir

import androidx.compose.runtime.Composable
import com.swarmos.app.AppGraph
import com.swarmos.app.SwarmModule
import com.swarmos.app.core.SwarmJson
import com.swarmos.app.link.ErrorBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/** POST /v1/choir/run: a built-in problem by id, or free text. `scripts` forces fake per-node answers (debug). */
@Serializable
data class RunRequest(
    val problemId: String? = null,
    val problem: String? = null,
    val kind: Kind? = null,
    val answer: String? = null,
    val check: String? = null,
    val expected: List<String> = emptyList(),
    val source: String? = null,
    val useProblemCheck: Boolean = false,
    val scripts: Map<String, FakeScript> = emptyMap(),
    val keepScripts: Boolean = false,
)

@Serializable
data class ProblemInfo(val id: String, val kind: Kind, val group: String, val text: String)

object ChoirModule : SwarmModule {
    override val id = "choir"
    override val title = "Choir"

    override fun Route.routes() {
        // node side of the protocol
        post("/solve") { call.rpc<SolveRequest, NodeAnswer>("solve") { ChoirController.node.solve(it) } }
        post("/revise") { call.rpc<ReviseRequest, NodeAnswer>("revise") { ChoirController.node.revise(it) } }
        post("/judge") { call.rpc<JudgeRequest, JudgeAnswer>("judge") { ChoirController.node.judge(it) } }

        // debug: register peers by IP, script fake answers on this node
        post("/debug/peers") {
            call.plain<DebugPeers, DebugPeersResult>("debug.peers") { ChoirController.registerPeers(it.ips) }
        }
        post("/debug/script") {
            call.plain<FakeScript, JsonObject>("debug.script") { sc ->
                ChoirController.node.script = sc.takeUnless { it.isEmpty() }
                buildJsonObject { put("ok", true); put("active", ChoirController.node.script != null) }
            }
        }

        // coordinator side: run one task, run the built-in set
        post("/run") {
            call.plain<RunRequest, RunTrace>("run") { r ->
                val p = resolveProblem(r)
                ChoirController.runProblem(p, RunOptions(r.useProblemCheck), r.scripts, r.keepScripts)
            }
        }
        post("/eval") {
            val body = runCatching { SwarmJson.parseToJsonElement(call.receiveText().ifBlank { "{}" }) as JsonObject }.getOrNull()
            if (body == null) { call.respondJson(ErrorBody("body must be a JSON object"), HttpStatusCode.BadRequest); return@post }
            val ids = (body["problemIds"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
            val useCheck = (body["useProblemCheck"] as? JsonPrimitive)?.booleanOrNull ?: false
            try {
                call.respondJson(ChoirController.runEval(ids, RunOptions(useCheck)))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                call.respondJson(ErrorBody(t.message ?: t::class.simpleName ?: "eval failed"), HttpStatusCode.InternalServerError)
            }
        }
        get("/eval/status") { call.respondJson(ChoirController.eval.value) }
        get("/problems") {
            call.respondText(
                SwarmJson.encodeToString(ListSerializer(ProblemInfo.serializer()), ChoirController.problems.map { ProblemInfo(it.id, it.kind, it.group, it.text) }),
                ContentType.Application.Json,
            )
        }
        get("/last") {
            val last = ChoirController.run.value
            if (last == null) call.respondText("{}", ContentType.Application.Json) else call.respondJson(last)
        }
    }

    @Composable override fun Screen() = ChoirScreen()

    private fun resolveProblem(r: RunRequest): Problem {
        r.problemId?.let { id ->
            return ChoirController.problems.firstOrNull { it.id == id } ?: throw IllegalArgumentException("unknown problemId $id")
        }
        val text = r.problem ?: throw IllegalArgumentException("give problemId or problem")
        return Problem(
            id = "custom", kind = r.kind ?: Kind.MATH, text = text, answer = r.answer ?: "",
            check = r.check, expected = r.expected, source = r.source,
        )
    }
}

// ---- small route helpers ---------------------------------------------------------------------------

private suspend inline fun <reified T> ApplicationCall.respondJson(v: T, status: HttpStatusCode = HttpStatusCode.OK) =
    respondText(SwarmJson.encodeToString(v), ContentType.Application.Json, status)

private inline fun <reified T> audit(kind: String, v: T) {
    AppGraph.audit.append(kind, SwarmJson.encodeToJsonElement(v))
}

/** Protocol message handler: decode, audit the message in and out, respond. */
private suspend inline fun <reified Req, reified Res> ApplicationCall.rpc(name: String, crossinline f: suspend (Req) -> Res) {
    val req = try {
        SwarmJson.decodeFromString<Req>(receiveText())
    } catch (t: Throwable) {
        return respondJson(ErrorBody("bad request: ${t.message}"), HttpStatusCode.BadRequest)
    }
    audit("choir.$name.in", req)
    val res = try {
        f(req)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        return respondJson(ErrorBody(t.message ?: t::class.simpleName ?: "failed"), HttpStatusCode.InternalServerError)
    }
    audit("choir.$name.out", res)
    respondJson(res)
}

/** Debug / control handler: like [rpc] but audited as one entry. */
private suspend inline fun <reified Req, reified Res> ApplicationCall.plain(name: String, crossinline f: suspend (Req) -> Res) {
    val req = try {
        SwarmJson.decodeFromString<Req>(receiveText().ifBlank { "{}" })
    } catch (t: Throwable) {
        return respondJson(ErrorBody("bad request: ${t.message}"), HttpStatusCode.BadRequest)
    }
    audit("choir.$name", req)
    val res = try {
        f(req)
    } catch (e: CancellationException) {
        throw e
    } catch (t: IllegalArgumentException) {
        return respondJson(ErrorBody(t.message ?: "bad request"), HttpStatusCode.BadRequest)
    } catch (t: Throwable) {
        return respondJson(ErrorBody(t.message ?: t::class.simpleName ?: "failed"), HttpStatusCode.InternalServerError)
    }
    respondJson(res)
}
