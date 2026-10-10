package com.swarmos.app.kb

import android.os.SystemClock
import com.swarmos.app.AppGraph
import com.swarmos.app.link.LinkState
import com.swarmos.app.link.PeerLinks
import com.swarmos.app.pdu.BatchEmbedder
import com.swarmos.app.pdu.EmbedderStopped
import com.swarmos.app.pdu.IndexGuard
import com.swarmos.app.pdu.PduEmbedConfig
import com.swarmos.app.pdu.PduEngine
import com.swarmos.app.pdu.PduStore
import com.swarmos.app.pdu.QueryTerms
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class KbSearchResult(val hits: List<KbHit>, val embedMs: Long, val dense: Boolean) {
    companion object { val NONE = KbSearchResult(emptyList(), 0, false) }
}

/** The knowledge bank on this phone: the store, the service, search with the embedder guard, and the sync with peers. */
object KbEngine {
    /** A peer that stays online is synced again this often. */
    const val SYNC_EVERY_MS = 3 * 60_000L
    private const val EMBED_KEY = "kbEmbedKey"

    val store: KbStore by lazy { KbStore(PduEngine.store) }
    val service: KbService by lazy {
        KbService(
            store,
            secret = { AppGraph.pairingSecret },
            selfId = { AppGraph.registry.self.value.nodeId },
            selfName = { AppGraph.registry.self.value.name },
            audit = { kind, payload -> AppGraph.audit.append(kind, payload).hash },
            // decided on this phone from its own index: a title that matches a private file makes the source private
            isPrivateSource = { KbPrivacy.isPrivate(it, AppGraph.registry.self.value.name, PduEngine.store::anyPrivateTitled) },
            // a shared save or a revocation of a shared card goes to every online peer now, not at the next timer tick
            onChange = { syncOnlinePeers() },
        )
    }

    private val _entries = MutableStateFlow<List<KbEntry>>(emptyList())
    val entries: StateFlow<List<KbEntry>> = _entries.asStateFlow()

    val selfId: String get() = AppGraph.registry.self.value.nodeId

    private val embedLock = Mutex()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val lastSync = ConcurrentHashMap<String, Long>()
    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        PduEngine.scope.launch(Dispatchers.IO) {
            refresh()
            embedMissing()
        }
        // a peer that comes online is synced at once, and again every SYNC_EVERY_MS while it stays online
        PduEngine.scope.launch {
            var online = emptySet<String>()
            PeerLinks.peers.collect { list ->
                val now = online
                online = list.filter { it.state == LinkState.ONLINE }.map { it.nodeId }.toSet()
                for (gone in now - online) lastSync.remove(gone)
                for (n in online) {
                    val last = lastSync[n]
                    val t = SystemClock.elapsedRealtime()
                    if (last == null || t - last >= SYNC_EVERY_MS) {
                        lastSync[n] = t
                        launch { syncWith(n) }
                    }
                }
            }
        }
    }

    fun refresh() {
        _entries.value = service.entries()
    }

    // ---- the owner's actions ----------------------------------------------------------------------------------

    suspend fun save(d: KbDraft.Draft, share: Boolean): KbSave = withContext(Dispatchers.IO) {
        service.save(d.question, d.answer, d.sources, share).also { refresh(); PduEngine.scope.launch { embedMissing() } }
    }

    suspend fun setShared(id: String, share: Boolean): String? = withContext(Dispatchers.IO) { service.setShared(id, share).also { refresh() } }

    suspend fun revoke(id: String): String? = withContext(Dispatchers.IO) { service.revoke(id).also { refresh() } }

    // ---- search -----------------------------------------------------------------------------------------------

    /** Cards for a question, best first. Dense only when the embedder is real and answering and the card vectors were made by it. */
    suspend fun search(text: String): KbSearchResult = withContext(Dispatchers.IO) {
        val entries = service.entries().filter { it.usable }
        if (entries.isEmpty()) return@withContext KbSearchResult.NONE
        val client = AppGraph.embedder
        val vectors = store.vectors()
        var qv: FloatArray? = null
        var ms = 0L
        if (vectors.isNotEmpty() && IndexGuard.usable(client) && PduEngine.store.meta(EMBED_KEY) == embedKey(client.modelName)) {
            val t0 = SystemClock.elapsedRealtime()
            qv = runCatching { PduStore.fit(client.embed(listOf(PduEmbedConfig.query(text))).first()) }.getOrNull()
            ms = SystemClock.elapsedRealtime() - t0
        }
        KbSearchResult(KbSearch.rank(entries, vectors, qv, QueryTerms.terms(text)), ms, qv != null)
    }

    private fun embedKey(model: String) = PduEmbedConfig.indexKey(model, PduEmbedConfig.documentPrefix)

    /**
     * Gives every card without a vector one, with the same embedder guard as the file index: never the placeholder client,
     * and a changed embedder or prefix drops all card vectors first because vectors of two embedders cannot be compared.
     */
    suspend fun embedMissing() = withContext(Dispatchers.IO) {
        embedLock.withLock {
            val client = AppGraph.embedder
            if (!IndexGuard.usable(client)) return@withLock
            val key = embedKey(client.modelName)
            if (PduEngine.store.meta(EMBED_KEY) != key) {
                store.clearVectors()
                PduEngine.store.setMeta(EMBED_KEY, key)
            }
            val todo = store.withoutVector()
            try {
                for (batch in todo.chunked(BatchEmbedder.BATCH)) {
                    val vecs = BatchEmbedder.embedAll(client, { AppGraph.embedder }, batch.map { PduEmbedConfig.document(it.question, it.answer) })
                    batch.forEachIndexed { i, c -> store.setVector(c.id, PduStore.fit(vecs[i])) }
                }
            } catch (_: EmbedderStopped) {
                // the embedder went away: the cards stay keyword-searchable, the next call carries on
            }
        }
    }

    // ---- sync -------------------------------------------------------------------------------------------------

    private class NodePeer(private val nodeId: String) : KbPeer {
        private fun host() = AppGraph.nodeClient.hostFor(nodeId) ?: throw IOException("no path to that phone")
        override suspend fun offer(o: KbOffer) = AppGraph.nodeClient.post<KbOfferReply, KbOffer>(host(), "/kb/offer", o, 10_000)
        override suspend fun cards(c: KbCards) = AppGraph.nodeClient.post<KbCards, KbCards>(host(), "/kb/cards", c, 20_000)
    }

    /** Same code path as the timer: one sync towards every peer that is online right now. */
    fun syncOnlinePeers() {
        PduEngine.scope.launch {
            val online = PeerLinks.peers.value.filter { it.state == LinkState.ONLINE }.map { it.nodeId }.distinct()
            for (n in online) launch { syncWith(n) }
        }
    }

    /** One sync with [nodeId]. A second call for the same phone waits for the first, so a card saved during a sync is offered by the next one. */
    suspend fun syncWith(nodeId: String): KbSyncResult = locks.getOrPut(nodeId) { Mutex() }.withLock {
        lastSync[nodeId] = SystemClock.elapsedRealtime()
        val r = try {
            KbSync.run(service, NodePeer(nodeId))
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            KbSyncResult(error = "${t.javaClass.simpleName}: ${t.message}".take(160))
        }
        if (r.error == null && (r.sentCards + r.sentTombstones + r.gotCards + r.gotTombstones > 0 || r.rejected.isNotEmpty())) service.auditSync(nodeId, r)
        if (r.gotCards + r.gotTombstones > 0) {
            withContext(Dispatchers.IO) { refresh() }
            PduEngine.scope.launch { embedMissing() }
        }
        r
    }

    /** A node id, an address, or a name, as a tester writes it, to the node id of an online peer. */
    fun resolvePeer(peer: String): String? =
        PeerLinks.peers.value.firstOrNull { it.nodeId == peer || it.ip == peer.substringBefore(':') || it.name == peer }?.nodeId
}
