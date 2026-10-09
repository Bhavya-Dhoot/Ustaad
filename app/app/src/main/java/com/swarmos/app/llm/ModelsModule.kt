package com.swarmos.app.llm

import androidx.compose.runtime.Composable
import com.swarmos.app.AppGraph
import com.swarmos.app.SwarmModule
import com.swarmos.app.core.SwarmJson
import com.swarmos.app.link.ErrorBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class RoleStateDto(
    val state: String,
    val model: String?,
    val file: String?,
    val reason: String?,
    val pid: Long?,
    val port: Int,
    val rssMb: Long?,
    val loadMs: Long?,
)

@Serializable
data class PresentDto(val file: String, val bytes: Long, val complete: Boolean)

@Serializable
data class ModelsStateDto(
    val node: String,
    val roles: Map<String, RoleStateDto>,
    val present: List<PresentDto>,
    val wanBytes: Long,
    val lastDecodeTokPerSec: Double?,
    val lastPrefillTokPerSec: Double?,
    val bench: BenchResult?,
)

@Serializable
data class LoadRequest(val role: String, val file: String, val wait: Boolean = true)

@Serializable
data class StopRequest(val role: String)

/** Models tab: model files, the llama-server processes, a benchmark. Routes are mounted under /v1/models/. */
object ModelsModule : SwarmModule {
    override val id = "models"
    override val title = "Models"

    override fun Route.routes() {
        get("/state") { call.respond(stateDto()) }

        post("/load") {
            val req = runCatching { SwarmJson.decodeFromString<LoadRequest>(call.receiveText()) }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorBody("body must be {\"role\":\"chat|embed\",\"file\":\"<name>.gguf\"}"))
            val role = Role.parse(req.role)?.takeIf { it == Role.CHAT || it == Role.EMBED }
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorBody("role must be chat or embed"))
            if (req.file != File(req.file).name || !ModelStore.fileOf(req.file).isFile) {
                return@post call.respond(HttpStatusCode.NotFound, ErrorBody("no such model file: ${req.file}"))
            }
            val job = loadModelAsync(role, req.file)
            if (req.wait) {
                job.await()
                call.respond(stateDto())
            } else {
                call.respond(HttpStatusCode.Accepted, stateDto())
            }
        }

        post("/stop") {
            val req = runCatching { SwarmJson.decodeFromString<StopRequest>(call.receiveText()) }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorBody("body must be {\"role\":\"chat|embed\"}"))
            val role = Role.parse(req.role)
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorBody("role must be chat, embed or rpc"))
            LlamaRuntime.stop(role)
            call.respond(stateDto())
        }

        // POST /v1/models/bench            one run (512-token prompt, 128 generated tokens)
        // POST /v1/models/bench?minutes=N  repeated for N minutes, per-minute decode tok/s in "sustained"
        post("/bench") {
            val minutes = call.request.queryParameters["minutes"]?.toIntOrNull() ?: 0
            try {
                if (minutes > 0) {
                    val points = Bench.runSustained(minutes)
                    call.respond(SustainedResponse(Bench.last.value, points))
                } else {
                    call.respond(SustainedResponse(Bench.run(), emptyList()))
                }
            } catch (e: IllegalStateException) {
                call.respond(HttpStatusCode.Conflict, ErrorBody(e.message ?: "bench failed"))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ErrorBody(e.message ?: e.toString()))
            }
        }
    }

    @Serializable
    data class SustainedResponse(val bench: BenchResult?, val sustained: List<SustainedPoint>)

    @Composable
    override fun Screen() = ModelsScreen()

    internal fun stateDto(): ModelsStateDto {
        val roles = Role.entries.associate { r ->
            val st = LlamaRuntime.statusOf(r)
            r.tag to RoleStateDto(
                state = st.state.name,
                model = st.modelName,
                file = st.modelPath?.let { File(it).name },
                reason = st.reason,
                pid = st.pid,
                port = st.port,
                rssMb = if (st.state == ServerState.STOPPED) null else LlamaRuntime.rssKb(r)?.div(1024),
                loadMs = st.readyAfterMs,
            )
        }
        val stats = LlamaRuntime.lastStats.value
        val bench = Bench.last.value
        return ModelsStateDto(
            node = AppGraph.registry.self.value.name,
            roles = roles,
            present = ModelStore.present().map { PresentDto(it.file, it.bytes, it.complete) },
            wanBytes = ModelStore.wanBytes.value,
            lastDecodeTokPerSec = stats?.decodeTokPerSec?.takeIf { it > 0 },
            lastPrefillTokPerSec = bench?.prefillTokPerSec?.takeIf { it > 0 },
            bench = bench,
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Loads [file] as [role]; when it comes up READY it is remembered as the file to auto-load at the next service start. */
    internal fun loadModelAsync(role: Role, file: String): Deferred<ServerStatus> = scope.async {
        val st = LlamaRuntime.start(role, ModelStore.fileOf(file).absolutePath)
        if (st.state == ServerState.READY) ModelStore.rememberLoaded(role, file)
        st
    }
}
