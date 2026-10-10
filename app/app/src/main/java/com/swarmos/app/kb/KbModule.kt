package com.swarmos.app.kb

import androidx.compose.runtime.Composable
import com.swarmos.app.BuildConfig
import com.swarmos.app.SwarmModule
import com.swarmos.app.core.Signer
import com.swarmos.app.core.SwarmJson
import com.swarmos.app.link.ErrorBody
import com.swarmos.app.pdu.PduEngine
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString

/** The knowledge bank. No tab of its own: its page is opened from the Data tab, its routes are mounted under /v1/kb. */
object KbModule : SwarmModule {
    override val id = "kb"
    override val title = "Knowledge"
    override val showInNav = false
    override fun Route.routes() = kbRoutes()
    @Composable override fun Screen() = KbPage(onBack = {})
}

@Serializable
data class KbDebugSave(val question: String, val answer: String, val sources: List<KbSource>, val share: Boolean = false)

@Serializable
data class KbDebugId(val id: String)

@Serializable
data class KbDebugSync(val peer: String)

/** A card as the debug route shows it. A card that is not shared never shows its text here: any paired phone can call the route. */
@Serializable
data class KbCardView(
    val id: String, val question: String, val answer: String, val tier: String, val author: String, val authorId: String,
    val createdMs: Long, val revokedMs: Long?, val sources: List<KbSource>, val auditHash: String, val mac: String, val hasVector: Boolean,
)

private fun KbEntry.view(hasVector: Boolean): KbCardView {
    val shown = tier == KbTier.SWARM
    return KbCardView(
        card.id, if (shown) card.question else "(card kept on this phone)", if (shown) card.answer else "",
        tier, card.authorName, card.authorId, card.createdMs, revokedMs,
        if (shown) card.sources else card.sources.map { it.copy(title = "", phone = it.phone) }, card.auditHash, card.mac, hasVector,
    )
}

/**
 * Routes under /v1/kb (signed by NodeServer):
 *  POST /offer         a peer lists the shared cards and tombstones it holds; the answer lists what is missing here and what is held here
 *  POST /cards         a peer pushes cards and tombstones (checked, capped) and asks for others
 *  GET  /cards         debug: this phone's cards, text only for shared ones
 *  POST /debug/save    debug: make a card {question, answer, sources:[{phone,title,dateMs,priv}], share}
 *  POST /debug/revoke  debug: {id}
 *  POST /debug/sync    debug: {peer} (node id, address or name) run one sync now
 */
fun Route.kbRoutes() {
    KbEngine.start()

    post("/offer") {
        val o = SwarmJson.decodeFromString<KbOffer>(call.receiveText())
        call.respond(KbEngine.service.handleOffer(o))
    }

    post("/cards") {
        val c = SwarmJson.decodeFromString<KbCards>(call.receiveText())
        val r = KbEngine.service.handleCards(c, call.request.header(Signer.HEADER_NODE) ?: "unknown")
        if (r.accepted > 0) {
            KbEngine.refresh()
            PduEngine.scope.launch { KbEngine.embedMissing() }
        }
        call.respond(r)
    }

    if (BuildConfig.DEBUG) {
        get("/cards") {
            val vectors = KbEngine.store.vectors().keys
            call.respond(KbEngine.service.entries().map { it.view(it.card.id in vectors) })
        }

        post("/debug/save") {
            val r = SwarmJson.decodeFromString<KbDebugSave>(call.receiveText())
            when (val s = KbEngine.service.save(r.question, r.answer, r.sources, r.share)) {
                is KbSave.Saved -> { KbEngine.refresh(); call.respond(s.entry.view(false)) }
                is KbSave.Refused -> call.respond(HttpStatusCode.Conflict, ErrorBody(s.reason))
            }
        }

        post("/debug/revoke") {
            val r = SwarmJson.decodeFromString<KbDebugId>(call.receiveText())
            val why = KbEngine.service.revoke(r.id)
            KbEngine.refresh()
            if (why == null) call.respond(mapOf("revoked" to r.id)) else call.respond(HttpStatusCode.Conflict, ErrorBody(why))
        }

        post("/debug/sync") {
            val r = SwarmJson.decodeFromString<KbDebugSync>(call.receiveText())
            val node = KbEngine.resolvePeer(r.peer) ?: return@post call.respond(HttpStatusCode.NotFound, ErrorBody("no online peer matches ${r.peer}"))
            call.respond(KbEngine.syncWith(node).let { KbSyncReport(it.sentCards, it.sentTombstones, it.gotCards, it.gotTombstones, it.rejected, it.error) })
        }
    }
}

@Serializable
data class KbSyncReport(
    val sentCards: Int, val sentTombstones: Int, val gotCards: Int, val gotTombstones: Int, val rejected: List<String>, val error: String?,
)
