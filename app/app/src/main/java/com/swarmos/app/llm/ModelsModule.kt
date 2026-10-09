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
import kotlinx.coroutines.launch
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
    /** false: the loaded model is chat only and refuses JSON-schema calls (see ModelCatalog). */
    val structuredOutput: Boolean = true,
)

@Serializable
data class PresentDto(val file: String, val bytes: Long, val complete: Boolean, val structuredOutput: Boolean = true)

@Serializable
data class ModelsStateDto(
    val node: String,
    val roles: Map<String, RoleStateDto>,
    val present: List<PresentDto>,
    val wanBytes: Long,
    val lastDecodeTokPerSec: Double?,
    val lastPrefillTokPerSec: Double?,
    val bench: BenchResult?,
    val benchRunning: Boolean,
    /** PowerManager thermal status now (0 NONE .. 6 SHUTDOWN) and its name; benchmarks are refused from 3 (SEVERE) up. */
    val thermalStatus: Int,
    val thermal: String,
    val benchBlockedReason: String?,
    val sustained: List<SustainedPoint>,
    /** The persisted "Load models at start" setting. */
    val autoload: Boolean,
    val selftestRunning: Boolean,
)

/**
 * role "chat" | "embed" | "rpc" loads [file] (rpc takes none); role "defaults" loads the default chat and embedding
 * models like a service start does. [wait] defaults to true for one role and to false for "defaults" (two loads take
 * longer than the 45 s the node server keeps an idle connection: answer 202 and poll GET /state).
 */
@Serializable
data class LoadRequest(val role: String, val file: String = "", val wait: Boolean? = null)

/** role "chat" | "embed" | "rpc" | "all" (every server this app started). */
@Serializable
data class StopRequest(val role: String)

@Serializable
data class AutoloadRequest(val enabled: Boolean)

@Serializable
data class SelftestStateDto(val running: Boolean, val result: SelftestResult?, val error: String?)

/** Models tab: model files, the llama-server processes, a benchmark. Routes are mounted under /v1/models/. */
object ModelsModule : SwarmModule {
    override val id = "models"
    override val title = "Models"

    override fun Route.routes() {
        get("/state") { call.respond(stateDto()) }

        post("/load") {
            val req = runCatching { SwarmJson.decodeFromString<LoadRequest>(call.receiveText()) }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorBody("body must be {\"role\":\"chat|embed\",\"file\":\"<name>.gguf\"}"))
            if (req.role.trim().equals("defaults", ignoreCase = true)) { // the models a service start would load
                val jobs = LlamaRuntime.loadDefaults()
                if (req.wait == true) jobs.forEach { it.await() }
                return@post call.respond(if (req.wait == true) HttpStatusCode.OK else HttpStatusCode.Accepted, stateDto())
            }
            val role = Role.parse(req.role)
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorBody("role must be chat, embed, rpc or defaults"))
            if (role == Role.RPC) { // rpc-server takes no model: {"role":"rpc","file":""} starts it on 127.0.0.1:50052
                val st = LlamaRuntime.start(Role.RPC, null)
                return@post call.respond(if (st.state == ServerState.READY) HttpStatusCode.OK else HttpStatusCode.Conflict, stateDto())
            }
            if (req.file != File(req.file).name || !ModelStore.fileOf(req.file).isFile) {
                return@post call.respond(HttpStatusCode.NotFound, ErrorBody("no such model file: ${req.file}"))
            }
            val job = loadModelAsync(role, req.file)
            if (req.wait != false) {
                job.await()
                call.respond(stateDto())
            } else {
                call.respond(HttpStatusCode.Accepted, stateDto())
            }
        }

        post("/stop") {
            val req = runCatching { SwarmJson.decodeFromString<StopRequest>(call.receiveText()) }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorBody("body must be {\"role\":\"chat|embed\"}"))
            if (req.role.trim().equals("all", ignoreCase = true)) { // unload everything: chat, embed and rpc
                LlamaRuntime.stopAll()
                return@post call.respond(stateDto())
            }
            val role = Role.parse(req.role)
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorBody("role must be chat, embed, rpc or all"))
            LlamaRuntime.stop(role)
            call.respond(stateDto())
        }

        // POST /v1/models/autoload {"enabled":false}  the persisted "Load models at start" setting (affects the next service start)
        post("/autoload") {
            val req = runCatching { SwarmJson.decodeFromString<AutoloadRequest>(call.receiveText()) }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorBody("body must be {\"enabled\":true|false}"))
            ModelStore.setAutoload(req.enabled)
            call.respond(stateDto())
        }

        // POST /v1/models/selftest   starts the check (streamed chat, 10 JSON-schema generations, embeddings, one thinking probe)
        //                            and answers 202 at once; poll GET /v1/models/selftest. ?wait=true answers with the result.
        post("/selftest") {
            if (Selftest.running.value) return@post call.respond(HttpStatusCode.Conflict, ErrorBody("a selftest is already running"))
            if (call.request.queryParameters["wait"] == "true") {
                Selftest.runRecorded()
                return@post call.respond(selftestDto())
            }
            scope.launch { Selftest.runRecorded() }
            // give the coroutine a moment to flip `running` so the answer already says so
            kotlinx.coroutines.delay(50)
            call.respond(HttpStatusCode.Accepted, selftestDto())
        }
        get("/selftest") { call.respond(selftestDto()) }

        // POST /v1/models/bench                 one run (512-token prompt, 128 generated tokens), answers with the result
        // POST /v1/models/bench?minutes=N       repeated for N minutes; answers 202 at once (the node server drops a
        //                                        connection that is idle for 45 s), poll GET /state: benchRunning, sustained
        // POST /v1/models/bench?wait=false      same for a single run
        post("/bench") {
            val minutes = call.request.queryParameters["minutes"]?.toIntOrNull() ?: 0
            val wait = call.request.queryParameters["wait"] != "false" && minutes == 0
            Bench.blockReason()?.let { return@post call.respond(HttpStatusCode.Conflict, ErrorBody(it)) }
            if (Bench.running.value) return@post call.respond(HttpStatusCode.Conflict, ErrorBody("a benchmark is already running"))
            if (LlamaRuntime.statusOf(Role.CHAT).state != ServerState.READY) {
                return@post call.respond(HttpStatusCode.Conflict, ErrorBody("chat server is not READY"))
            }
            if (!wait) {
                scope.launch { runCatching { if (minutes > 0) Bench.runSustained(minutes) else Bench.run() } }
                return@post call.respond(HttpStatusCode.Accepted, stateDto())
            }
            try {
                call.respond(Bench.run())
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ErrorBody(e.message ?: e.toString()))
            }
        }
    }

    @Composable
    override fun Screen() = ModelsScreen()

    private fun selftestDto() = SelftestStateDto(Selftest.running.value, Selftest.last.value, Selftest.error.value)

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
                structuredOutput = st.modelName?.let { ModelCatalog.allowsSchema(it) } ?: true,
            )
        }
        val stats = LlamaRuntime.lastStats.value
        val bench = Bench.last.value
        val thermalNow = Bench.thermalStatusNow()
        return ModelsStateDto(
            node = AppGraph.registry.self.value.name,
            roles = roles,
            present = ModelStore.present().map { PresentDto(it.file, it.bytes, it.complete, ModelCatalog.allowsSchema(it.file)) },
            wanBytes = ModelStore.wanBytes.value,
            lastDecodeTokPerSec = stats?.decodeTokPerSec?.takeIf { it > 0 },
            lastPrefillTokPerSec = bench?.prefillTokPerSec?.takeIf { it > 0 },
            bench = bench,
            benchRunning = Bench.running.value,
            thermalStatus = thermalNow,
            thermal = com.swarmos.app.core.HealthMonitor.thermalName(thermalNow),
            benchBlockedReason = Bench.blockReasonFor(thermalNow),
            sustained = Bench.sustained.value,
            autoload = ModelStore.autoload.value,
            selftestRunning = Selftest.running.value,
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
