package com.swarmos.app.pdu

import android.net.Uri
import com.swarmos.app.AppGraph
import com.swarmos.app.BuildConfig
import com.swarmos.app.core.Signer
import com.swarmos.app.core.SwarmJson
import com.swarmos.app.link.ErrorBody
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import kotlinx.serialization.decodeFromString

@Serializable
data class DebugScanRequest(val path: String, val title: String, val keepPrivate: Boolean = false)

@Serializable
data class DebugPeersRequest(val ips: List<String>)

@Serializable
data class AskSource(
    val n: Int, val phone: String, val nodeId: String, val local: Boolean, val uriHash: String, val title: String, val kind: String,
    val dateMs: Long, val snippet: String, val score: Double,
)

@Serializable
data class AskDevice(
    val phone: String, val nodeId: String, val local: Boolean, val status: String, val detail: String?, val hits: Int, val bytes: Int,
    val rttMs: Long, val tookMs: Long, val withheldPrivate: Int, val indexedChunks: Int, val titles: List<String>,
)

@Serializable
data class AskTimings(val embedMs: Long, val localSearchMs: Long, val firstCardsMs: Long, val ttftMs: Long, val totalMs: Long)

@Serializable
data class AskReport(
    val qid: String, val question: String, val answer: String, val model: String, val incomplete: Boolean,
    val withheldPrivate: Int, val sources: List<AskSource>, val devices: List<AskDevice>, val timings: AskTimings,
)

@Serializable
data class FolderReport(val folder: String, val isPrivate: Boolean, val docs: Int)

@Serializable
data class StatusReport(
    val photos: Int, val documents: Int, val scans: Int, val noText: Int, val chunks: Int, val lastRunMs: Long,
    val embedModel: String?, val running: Boolean, val folders: List<FolderReport>, val lastIndex: IndexReport?,
)

/** Everything a caller of the signed debug routes may see about this phone's own private items is a count, never text. */
fun QueryUi.toReport(): AskReport = AskReport(
    qid = qid, question = text, answer = answer, model = model, incomplete = incomplete,
    withheldPrivate = devices.sumOf { it.withheldPrivate },
    sources = sources.map {
        AskSource(
            it.n, it.deviceName, it.deviceId, it.local, if (it.localPrivate) "" else it.hit.uriHash,
            if (it.localPrivate) "(private file on this phone)" else it.hit.title, it.hit.kind, it.hit.dateMs,
            if (it.localPrivate) "" else it.hit.snippet, it.hit.score,
        )
    },
    devices = devices.map {
        AskDevice(
            it.name, it.deviceId, it.local, it.status, it.detail, it.hits, it.bytes, it.rttMs, it.tookMs, it.withheldPrivate,
            it.indexedChunks, if (it.local) emptyList() else it.titles,
        )
    },
    timings = AskTimings(timings.embedMs, timings.localSearchMs, timings.firstCardsMs, timings.ttftMs, timings.totalMs),
)

/**
 * Routes under /v1/pdu (signed by NodeServer):
 *  POST /query        peer asks this phone; answered through the share policy
 *  POST /open         peer asks this phone to open a cited file on its own screen
 *  POST /index        test/dev: index MediaStore photos and/or an all-files-access directory, returns timings
 *  POST /ask          test/dev: run the whole federated flow from this phone and return sources and timings
 *  POST /debug/peers  test/dev: register peers by IP (GET /v1/hello on each) until discovery lands
 *  GET  /status       index counts
 */
fun Route.pduRoutes() {
    PduEngine.start()

    post("/query") {
        val q = SwarmJson.decodeFromString<PduQuery>(call.receiveText())
        call.respond(PduEngine.servePeer(q, call.request.header(Signer.HEADER_NODE) ?: "unknown"))
    }

    post("/open") {
        val r = SwarmJson.decodeFromString<PduOpenRequest>(call.receiveText())
        call.respond(PduEngine.openForPeer(r.uriHash))
    }

    // test and dev routes: debug builds only, so a release build exposes just /query and /open
    if (BuildConfig.DEBUG) {
        post("/index") {
            val req = SwarmJson.decodeFromString<IndexRequest>(call.receiveText().ifBlank { "{}" })
            val report = PduEngine.runRequest(req)
            call.respond(if (report.error == null) HttpStatusCode.OK else HttpStatusCode.Conflict, report)
        }

        post("/ask") {
            val req = SwarmJson.decodeFromString<AskRequest>(call.receiveText())
            if (req.text.isBlank()) return@post call.respond(HttpStatusCode.BadRequest, ErrorBody("empty question"))
            call.respond(PduEngine.ask(req.text, req.k, req.deadlineMs).toReport())
        }

        // stands in for the camera shutter: same OCR, MediaStore save and indexing as "Scan a document" (needs all-files access for the path)
        post("/debug/scan") {
            val r = SwarmJson.decodeFromString<DebugScanRequest>(call.receiveText())
            val rep = withContext(Dispatchers.IO) {
                val ctx = AppGraph.appContext
                val f = File(r.path)
                val bmp = Ocr.decode(ctx, Uri.fromFile(f).toString())
                val text = Ocr.recognize(bmp).also { bmp.recycle() }
                val (uri, modified) = PduIndexer.saveScan(ctx, f, r.keepPrivate)
                PduEngine.indexScan(uri, modified, r.title, r.keepPrivate, text)
            }
            call.respond(rep)
        }

        post("/debug/peers") {
            val req = SwarmJson.decodeFromString<DebugPeersRequest>(call.receiveText())
            call.respond(PduEngine.addPeersByIp(req.ips))
        }

        get("/status") {
            val c = PduEngine.store.counts()
            call.respond(
                StatusReport(
                    c.photos, c.documents, c.scans, c.skipped, c.chunks, c.lastRunMs,
                    PduEngine.store.meta("embedModel"), PduEngine.progress.value.running,
                    PduEngine.store.folders().map { FolderReport(it.folder, it.isPrivate, it.docs) }, PduEngine.progress.value.last,
                ),
            )
        }
    }
}
