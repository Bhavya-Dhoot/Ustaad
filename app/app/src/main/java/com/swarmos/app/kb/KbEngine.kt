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
import com.swarmos.app.pdu.QueryVectors
import com.swarmos.app.pdu.StepGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class KbSearchResult(val hits: List<KbHit>, val embedMs: Long, val dense: Boolean) {
    companion object { val NONE = KbSearchResult(emptyList(), 0, false) }
}

/** The knowledge bank on this phone: the store, the service, search with the embedder guard, and the sync with peers. */
object KbEngine {
    /** A peer that stays online is synced again this often. */
    const val SYNC_EVERY_MS = 3 * 60_000L

    /** A sync that failed is tried again after this long, up to [MAX_TRIES] tries in all; after that the timer above takes over. */
    const val RETRY_MS = KbEngineRules.RETRY_MS
    const val MAX_TRIES = KbEngineRules.MAX_TRIES
    private const val EMBED_KEY = "kbEmbedKey"
    private const val SECRET_KEY = "kbSecretFp"

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

    private val _status = MutableStateFlow<String?>(null)
    /** One line for the Knowledge bank page when a background step failed (embedding, syncing, repair); null while all is well. */
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _lastOk = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** For each peer (node id), when a sync with it last finished without an error (wall clock ms). The card detail compares it with a revocation. */
    val lastOk: StateFlow<Map<String, Long>> = _lastOk.asStateFlow()

    val selfId: String get() = AppGraph.registry.self.value.nodeId

    private val embedLock = Mutex()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val lastSync = ConcurrentHashMap<String, Long>()
    private val tries = ConcurrentHashMap<String, Int>()
    private val retries = ConcurrentHashMap<String, Job>()
    private var started = false

    @Volatile private var lastSecret: String? = null

    @Synchronized
    fun start() {
        if (started) return
        started = true
        PduEngine.scope.launch(Dispatchers.IO) {
            StepGuard.run(::noteError) {
                observeSecret()
                service.repair()
                service.purgeExpiredTombstones()
                refresh()
            }
            embedMissing()
        }
        // a peer that comes online is synced at once, and again every SYNC_EVERY_MS while it stays online
        PduEngine.scope.launch {
            var online = emptySet<String>()
            PeerLinks.peers.collect { list ->
                StepGuard.run(::noteError) {
                    observeSecret()
                    val now = online
                    online = list.filter { it.state == LinkState.ONLINE }.map { it.nodeId }.toSet()
                    for (gone in now - online) { lastSync.remove(gone); tries.remove(gone); retries.remove(gone)?.cancel() }
                    for (n in online) {
                        val last = lastSync[n]
                        val t = SystemClock.elapsedRealtime()
                        if (last == null || t - last >= SYNC_EVERY_MS) {
                            lastSync[n] = t // no second sync is started for this peer while this one runs; a failure puts the retry rules in charge
                            launch { syncWith(n) }
                        }
                    }
                }
            }
        }
    }

    fun refresh() {
        _entries.value = service.entries()
    }

    /** Remembers what went wrong in a background step for the page, once per distinct message, and writes it to the audit log. */
    private fun noteError(t: Throwable) {
        val m = StepGuard.describe("Knowledge bank", t)
        if (_status.value == m) return
        _status.value = m
        AppGraph.audit.append("kb.error", JsonObject(mapOf("error" to JsonPrimitive(m))))
    }

    /**
     * Where a change of the pairing secret is noticed. The secret lives in AppGraph (hosting again replaces it, adopting another
     * phone's code sets it) and AppGraph has no hook for others, so this phone compares it with the one it saw last at every entry
     * into the knowledge bank: start, a peer change, a sync, a save or revoke, a request from a peer. On a change it re-seals this
     * phone's own cards and tombstones in one transaction ([KbService.rekey]) while the old secret is still known. If the app was
     * stopped between the change and the next entry, the old secret is gone; only a note in the audit log is left (the fingerprint
     * stored with the index says the change happened).
     */
    @Synchronized
    fun observeSecret() {
        val now = AppGraph.pairingSecret
        val before = lastSecret
        lastSecret = now
        val fp = fingerprint(now)
        if (before == null) {
            val stored = PduEngine.store.meta(SECRET_KEY)
            if (stored != null && stored != fp) {
                AppGraph.audit.append("kb.secret_changed_unseen", JsonObject(mapOf("note" to JsonPrimitive("own records may fail the seal check on other phones"))))
            }
        } else if (before != now) {
            service.rekey(before, now)
            refresh()
        }
        if (PduEngine.store.meta(SECRET_KEY) != fp) PduEngine.store.setMeta(SECRET_KEY, fp)
    }

    private fun fingerprint(secret: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(("kb-secret-fp:$secret").toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    // ---- the owner's actions ----------------------------------------------------------------------------------

    suspend fun save(d: KbDraft.Draft, share: Boolean): KbSave = withContext(Dispatchers.IO) {
        observeSecret()
        service.save(d.question, d.answer, d.sources, share).also { refresh(); PduEngine.scope.launch { embedMissing() } }
    }

    suspend fun setShared(id: String, share: Boolean): String? = withContext(Dispatchers.IO) { observeSecret(); service.setShared(id, share).also { refresh() } }

    suspend fun revoke(id: String): String? = withContext(Dispatchers.IO) { observeSecret(); service.revoke(id).also { refresh() } }

    // ---- search -----------------------------------------------------------------------------------------------

    /**
     * Cards for a question, best first. Dense only when [dense] is on, the embedder is real and answering and the card vectors were
     * made by it. The ask passes false after its time budget for the embedder ran out: keywords only, no embedder is asked.
     */
    suspend fun search(text: String, dense: Boolean = true): KbSearchResult = withContext(Dispatchers.IO) {
        val entries = service.entries().filter { it.usable }
        if (entries.isEmpty()) return@withContext KbSearchResult.NONE
        val client = AppGraph.embedder
        val vectors = store.vectors()
        var qv: FloatArray? = null
        var ms = 0L
        if (dense && vectors.isNotEmpty() && IndexGuard.usable(client) && PduEngine.store.meta(EMBED_KEY) == embedKey(client.modelName)) {
            val t0 = SystemClock.elapsedRealtime()
            qv = try { QueryVectors.of(client, text) } catch (t: Throwable) { if (t is CancellationException) throw t else null }
            ms = SystemClock.elapsedRealtime() - t0
        }
        KbSearchResult(KbSearch.rank(entries, vectors, qv, QueryTerms.terms(text)), ms, qv != null)
    }

    private fun embedKey(model: String) = PduEmbedConfig.indexKey(model, PduEmbedConfig.documentPrefix)

    /**
     * Gives every card without a vector one, with the same embedder guard as the file index: never the placeholder client,
     * and a changed embedder or prefix drops all card vectors first because vectors of two embedders cannot be compared.
     * Never throws except for cancellation: a failure is shown on the page ([status]) and the next call tries again.
     */
    suspend fun embedMissing() = withContext(Dispatchers.IO) {
        embedLock.withLock {
            val ok = StepGuard.run(::noteError) {
                val client = AppGraph.embedder
                if (!IndexGuard.usable(client)) return@run
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
            if (ok) _status.value = null
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

    /**
     * One sync with [nodeId]. A second call for the same phone waits for the first, so a card saved during a sync is offered by the next one.
     * A sync that finished without an error clears the retry state and is recorded in [lastOk]; a failed one is tried again after
     * [RETRY_MS], up to [MAX_TRIES] tries in all, and only then left to the timer.
     */
    suspend fun syncWith(nodeId: String): KbSyncResult = locks.getOrPut(nodeId) { Mutex() }.withLock {
        observeSecret()
        val r = try {
            KbSync.run(service, NodePeer(nodeId))
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            KbSyncResult(error = "${t.javaClass.simpleName}: ${t.message}".take(160))
        }
        lastSync[nodeId] = SystemClock.elapsedRealtime()
        if (r.error == null) {
            tries.remove(nodeId)
            retries.remove(nodeId)?.cancel()
            _lastOk.update { it + (nodeId to System.currentTimeMillis()) }
            StepGuard.run(::noteError) {
                if (r.sentCards + r.sentTombstones + r.gotCards + r.gotTombstones > 0 || r.rejected.isNotEmpty()) service.auditSync(nodeId, r)
                if (r.gotCards + r.gotTombstones > 0) {
                    withContext(Dispatchers.IO) { refresh() }
                    PduEngine.scope.launch { embedMissing() }
                }
            }
        } else {
            scheduleRetry(nodeId)
        }
        r
    }

    private fun scheduleRetry(nodeId: String) {
        val n = (tries[nodeId] ?: 0) + 1
        tries[nodeId] = n
        val wait = KbEngineRules.retryDelay(n)
        if (wait == null) { retries.remove(nodeId)?.cancel(); return } // out of tries: the timer syncs again after SYNC_EVERY_MS
        val job = PduEngine.scope.launch {
            delay(wait)
            if (PeerLinks.peers.value.any { it.nodeId == nodeId && it.state == LinkState.ONLINE }) syncWith(nodeId)
        }
        retries.put(nodeId, job)?.cancel()
    }

    /** A node id, an address, or a name, as a tester writes it, to the node id of an online peer. */
    fun resolvePeer(peer: String): String? =
        PeerLinks.peers.value.firstOrNull { it.nodeId == peer || it.ip == peer.substringBefore(':') || it.name == peer }?.nodeId
}

/** What the card shows about a revocation that has not reached every paired phone. */
object KbDelivery {
    /** A paired phone, by node id and name. */
    class Peer(val nodeId: String, val name: String)

    /**
     * The names of the phones a revocation made at [revokedMs] has not been delivered to: those with no finished sync since then.
     * A finished sync after the revocation offers the tombstone and pushes it when the other phone lacks it, so it counts as delivery.
     */
    fun pending(revokedMs: Long, peers: List<Peer>, lastOk: Map<String, Long>): List<String> =
        peers.filter { (lastOk[it.nodeId] ?: Long.MIN_VALUE) < revokedMs }.map { it.name }

    fun line(name: String) = "Not delivered to $name yet"
}

/** How a failed sync is retried: after 20 s, and at most 5 tries in all, then the 3 minute timer is the only thing that syncs again. */
object KbEngineRules {
    const val RETRY_MS = 20_000L
    const val MAX_TRIES = 5

    /** How long to wait before the next try after [failedTries] failed ones, or null when the tries are used up. */
    fun retryDelay(failedTries: Int): Long? = if (failedTries >= MAX_TRIES) null else RETRY_MS
}
