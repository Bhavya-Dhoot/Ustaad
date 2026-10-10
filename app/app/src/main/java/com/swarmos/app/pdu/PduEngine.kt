package com.swarmos.app.pdu

import android.os.Environment
import android.os.SystemClock
import com.swarmos.app.AppGraph
import com.swarmos.app.core.AuditScope
import com.swarmos.app.core.PeerState
import com.swarmos.app.core.Ports
import com.swarmos.app.link.ConnectResult
import com.swarmos.app.link.PeerLinks
import com.swarmos.app.llm.LlmClient
import com.swarmos.app.sched.Sched
import com.swarmos.app.core.SwarmJson
import com.swarmos.app.core.SwarmPrefs
import com.swarmos.app.kb.KbEngine
import com.swarmos.app.kb.KbHit
import com.swarmos.app.kb.KbSearch
import com.swarmos.app.kb.KbSearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.UUID

data class IndexProgress(
    val running: Boolean = false,
    val label: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val current: String = "",
    val last: IndexReport? = null,
)

/** What one phone answered (or why it did not). `bytes` is the size of its JSON response. */
data class DeviceResult(
    val deviceId: String,
    val name: String,
    val local: Boolean,
    val status: String, // waiting | ok | unreachable | offline | error
    val detail: String? = null,
    val hits: Int = 0,
    val bytes: Int = 0,
    val rttMs: Long = 0,
    val tookMs: Long = 0,
    val withheldPrivate: Int = 0,
    val indexedChunks: Int = 0,
    val titles: List<String> = emptyList(),
    val sentBytes: Int = 0,
)

data class SourceUi(val n: Int, val deviceId: String, val deviceName: String, val local: Boolean, val hit: PduHit, val localPrivate: Boolean)

data class Timings(
    val embedMs: Long = -1,
    val localSearchMs: Long = -1,
    val firstCardsMs: Long = -1,
    val ttftMs: Long = -1,
    val totalMs: Long = -1,
)

data class QueryUi(
    val qid: String,
    val text: String,
    val phase: String, // searching | answering | done
    val sources: List<SourceUi> = emptyList(),
    val devices: List<DeviceResult> = emptyList(),
    val incomplete: Boolean = false,
    val answer: String = "",
    /** The chat model that wrote the answer; empty when no real chat model is loaded (no model text is ever shown then). */
    val model: String = "",
    /** Said in the answer pane next to or instead of model text: waiting for the model, no model loaded, gave up, stopped early. */
    val note: String? = null,
    val timings: Timings = Timings(),
    val auditHash: String? = null,
    /** True when this phone answered by keywords only, because no usable embedder was available. */
    val lexicalOnly: Boolean = false,
    /** Saved answers (knowledge bank cards) that matched, best first; never revoked ones. */
    val cards: List<KbHit> = emptyList(),
    /** A card whose answer clears the relevance floor: shown at once as the answer, no live search ran. */
    val savedAnswer: KbHit? = null,
)

/** Said when an ask ends by an error nobody planned for (the cause is in the log and the audit record). */
internal const val ASK_FAILED_NOTE = "Something went wrong while answering. Ask again."

/** An ask that was stopped ([note] is AnswerNotes.STOPPED) or failed ([ASK_FAILED_NOTE]), closed: "done", keeping its own note if it has one, with the total time. */
internal fun closedAsk(q: QueryUi, note: String, totalMs: Long): QueryUi =
    if (q.phase == "done") q else q.copy(phase = "done", note = q.note ?: note, timings = q.timings.copy(totalMs = totalMs))

/** Whether a real embedding model is loaded and answering. A fake or stopped one is never "ready". */
data class EmbedderStatus(val name: String, val ready: Boolean)

/** What this phone handed to a peer, kept in memory for the audit view (the audit log has the counts). */
data class ServedRecord(
    val qid: String,
    val peer: String,
    val n: Int,
    val bytes: Int,
    val withheldPrivate: Int,
    val titles: List<String>,
    val atMs: Long,
)

@Serializable
data class IndexRequest(
    val photos: Boolean = false,
    val relPrefix: String? = null,
    val limit: Int? = null,
    val dir: String? = null,
    val force: Boolean = false,
)

@Serializable
data class AskRequest(val text: String, val k: Int = 5, val deadlineMs: Int = 1500, val useSaved: Boolean = true)

class LocalSearch(val candidates: List<Candidate>, val terms: List<String>, val embedMs: Long, val searchMs: Long, val dense: Boolean = true)

/** What one paired phone answered to a query: its [response], or why there is none ([status] unreachable or error, with [detail]). */
class PeerAnswer(val response: PduResponse?, val status: String, val detail: String?, val rttMs: Long)

/** Owns the store and every flow of this module: indexing, local search, the federated ask, serving peers. */
object PduEngine {
    // The process lives as long as SwarmService's foreground notification, so a process-level scope is the indexing scope.
    // An exception nobody caught is one audit entry, not the end of the process.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + AuditScope.handler("pdu") { AppGraph.audit })

    val store: PduStore by lazy { PduStore(File(AppGraph.appContext.filesDir, "pdu.db").path) }

    private val _progress = MutableStateFlow(IndexProgress())
    val progress: StateFlow<IndexProgress> = _progress.asStateFlow()
    private val _counts = MutableStateFlow(StoreCounts())
    val counts: StateFlow<StoreCounts> = _counts.asStateFlow()
    private val _folders = MutableStateFlow<List<FolderInfo>>(emptyList())
    val folders: StateFlow<List<FolderInfo>> = _folders.asStateFlow()
    private val _query = MutableStateFlow<QueryUi?>(null)
    val query: StateFlow<QueryUi?> = _query.asStateFlow()
    private val _served = MutableStateFlow<List<ServedRecord>>(emptyList())
    val served: StateFlow<List<ServedRecord>> = _served.asStateFlow()

    private val _embedder = MutableStateFlow(EmbedderStatus(IndexGuard.FAKE, false))
    val embedder: StateFlow<EmbedderStatus> = _embedder.asStateFlow()
    private val _reindexNeeded = MutableStateFlow(false)
    /** Stored vectors were made by another embedder or document prefix: the owner has to re-index. */
    val reindexNeeded: StateFlow<Boolean> = _reindexNeeded.asStateFlow()

    private val _note = MutableStateFlow<String?>(null)
    /** One line for the library screen when the background loop of this module hit an error; null while it is healthy. */
    val engineNote: StateFlow<String?> = _note.asStateFlow()

    private val openLimiter = RateLimiter(max = 3, windowMs = 60_000)

    @Volatile var lastSearchMs: Long = -1
        private set

    /** Debug builds only: a page the Scan screen should open straight into its review sheet, standing in for the shutter. */
    @Volatile var debugScanFile: File? = null

    /**
     * Give the model fewer, better sources (see [PromptBuilder.leanSelect]). Off by default; the pref `pdu_lean_sources` holds it, and a
     * debug build can set it with the intent extra `leanSources`.
     */
    @Volatile var leanSources: Boolean = false
        private set

    fun setLeanSources(on: Boolean) {
        leanSources = on
        scope.launch(Dispatchers.IO) { SwarmPrefs(AppGraph.appContext).setPduLeanSources(on) }
    }

    private val indexLock = Mutex()
    @Volatile private var indexControl: IndexControl? = null
    private val askSlot = AskSlot()
    private fun now() = SystemClock.elapsedRealtime()

    /** Open the store and load the in-memory matrix off the main thread. Safe to call repeatedly. */
    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch(Dispatchers.IO) {
            StepGuard.run(::noteError) {
                PduEmbedConfig.load(store)
                leanSources = SwarmPrefs(AppGraph.appContext).pduLeanSources()
                refresh()
            }
            while (true) {
                if (StepGuard.run(::noteError) { embedderStatus(); refresh() }) _note.value = null
                delay(4_000)
            }
        }
    }

    /** Remembers what went wrong in a background step, once per distinct message, so the screen can say it and the audit log has it. */
    private fun noteError(t: Throwable) {
        val m = StepGuard.describe("Data Union", t)
        if (_note.value == m) return
        _note.value = m
        AppGraph.audit.append("pdu.error", JsonObject(mapOf("error" to JsonPrimitive(m))))
    }

    fun refresh() {
        _counts.value = store.counts()
        _folders.value = store.folders()
        val e = _embedder.value
        _reindexNeeded.value = e.ready && IndexGuard.reindexNeeded(store, e.name, PduEmbedConfig.documentPrefix)
    }

    /** The embedder this phone has right now. Never ready for the fake client or a server that does not answer. */
    suspend fun embedderStatus(e: LlmClient = AppGraph.embedder): EmbedderStatus {
        return EmbedderStatus(e.modelName, IndexGuard.usable(e)).also { _embedder.value = it }
    }

    fun setFolderPrivate(folder: String, isPrivate: Boolean) {
        scope.launch(Dispatchers.IO) {
            StepGuard.run(::noteError) {
                store.setFolderPrivate(folder, isPrivate)
                refresh()
                AppGraph.audit.append(
                    "pdu.policy",
                    JsonObject(mapOf("folder" to JsonPrimitive(folder), "private" to JsonPrimitive(isPrivate))),
                )
            }
        }
    }

    // ---- indexing -----------------------------------------------------------------------------------------

    /**
     * One run at a time. [list] runs on the IO dispatcher; each file is committed as soon as it is embedded, so a killed run resumes.
     * [roots] say which stored files this run is responsible for: when the run finishes, stored files of those roots that the listing
     * did not contain are removed (a deleted file, or one moved out of the listed folder, stops being searchable and served).
     * [stopIndex] ends the run after the file in hand.
     */
    suspend fun runIndex(label: String, ownerForce: Boolean, roots: List<IndexRoot> = emptyList(), list: () -> List<DocRef>): IndexReport {
        if (!indexLock.tryLock()) return IndexReport(label, error = "an index run is already in progress")
        val control = IndexControl()
        indexControl = control
        return try {
            withContext(Dispatchers.IO) {
                // the embedder is captured once; every batch checks it is still this real, answering client (see BatchEmbedder)
                val client = AppGraph.embedder
                val st = embedderStatus(client)
                val g = IndexGuard.prepare(store, st.name, st.ready, PduEmbedConfig.documentPrefix, ownerForce)
                if (g is IndexGuard.Outcome.Refused) return@withContext refuse(label, g.reason)
                val run = IndexRun(
                    store, client, { AppGraph.embedder }, PduEmbedConfig.documentPrefix,
                    replacing = g is IndexGuard.Outcome.ProceedReplacing,
                )
                val stats = IndexStats(label)
                val t0 = now()
                _progress.value = IndexProgress(true, label, current = "listing files")
                PduActivity.set(PduActivity.listing())
                val refs = list()
                val ctx = AppGraph.appContext
                val res = IndexLoop.run(
                    refs, control, IndexLoop.FILE_TIMEOUT_MS,
                    prepare = { PduIndexer.prepare(ctx, store, it, run.rereadAll) },
                    commit = { ref, p -> PduIndexer.commit(store, ref, p, stats, run) },
                    onProgress = { i, ref ->
                        stats.seen++
                        _progress.value = IndexProgress(true, label, i, refs.size, ref.title)
                        PduActivity.set(PduActivity.indexing(refs.size, i))
                    },
                    onFailure = { ref, t -> PduIndexer.recordFailure(store, ref, t, stats) },
                    refresh = ::refresh, // every 25 files, not after every one
                    // embedding is slower for a long text, so the budget of the embedding step grows with the number of chunks
                    commitBudgetMs = { p -> IndexLoop.FILE_TIMEOUT_MS + (p as? PduIndexer.Ready)?.bodies?.size.let { (it ?: 0) * 250L } },
                )
                if (res.end == LoopEnd.COMPLETE && roots.isNotEmpty()) stats.removed += sweep(roots, refs, stats)
                stats.totalMs = now() - t0
                store.setMeta("lastRunMs", System.currentTimeMillis().toString())
                refresh()
                when (res.end) {
                    LoopEnd.EMBEDDER_STOPPED -> abort(label, stats, refs.size, res.done)
                    LoopEnd.FULL -> endedEarly(label, stats, refs.size, res.done, ChunkCap.message(), "pdu.index_full")
                    LoopEnd.STOPPED -> endedEarly(label, stats, refs.size, res.done, "Stopped after ${res.done} of ${refs.size} files", "pdu.index_stopped")
                    LoopEnd.COMPLETE -> {
                        val report = stats.report()
                        _progress.value = IndexProgress(false, label, refs.size, refs.size, "", report)
                        AppGraph.audit.append(
                            "pdu.index",
                            JsonObject(mapOf(
                                "label" to JsonPrimitive(label), "seen" to JsonPrimitive(report.seen),
                                "indexed" to JsonPrimitive(report.indexed), "chunks" to JsonPrimitive(report.chunks),
                                "moved" to JsonPrimitive(report.moved), "removed" to JsonPrimitive(report.removed),
                            )),
                        )
                        report
                    }
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) {
                _progress.update { if (it.running) IndexProgress(false, it.label, it.done, it.total) else it }
                throw t
            }
            val r = IndexReport(label, error = "${t.javaClass.simpleName}: ${t.message}")
            _progress.value = IndexProgress(false, label, last = r)
            r
        } finally {
            indexControl = null
            PduActivity.clear()
            indexLock.unlock()
        }
    }

    /** Ends the index run in progress after the file it is on. False when no run is going. */
    fun stopIndex(): Boolean = indexControl?.let { it.stop(); true } ?: false

    /** Removes the stored files of [roots] that a finished run did not see. Returns how many files went. */
    private fun sweep(roots: List<IndexRoot>, refs: List<DocRef>, stats: IndexStats): Int {
        val seen = refs.mapTo(HashSet(refs.size * 2)) { it.uri }
        var n = 0
        for (root in roots) {
            val stored = store.docsUnder(root)
            if (!IndexSweep.mayRemove(refs.size, stored.size)) {
                stats.errors += "nothing was listed, so ${stored.size} stored files were left alone"
                continue
            }
            n += store.removeDocs(IndexSweep.stale(stored, seen).map { it.docId })
        }
        return n
    }

    /** The embedder went away mid-run. [done] files were stored before it; the file in hand and the rest were not touched. */
    private fun abort(label: String, stats: IndexStats, total: Int, done: Int): IndexReport {
        val r = stats.report().copy(error = embedderStoppedMessage(done))
        _progress.value = IndexProgress(false, label, done, total, "", r)
        AppGraph.audit.append(
            "pdu.index_aborted",
            JsonObject(mapOf("label" to JsonPrimitive(label), "done" to JsonPrimitive(done), "total" to JsonPrimitive(total))),
        )
        return r
    }

    /** The run ended before the listing did (the owner pressed Stop, or the index is full): what was stored stays, the reason is shown. */
    private fun endedEarly(label: String, stats: IndexStats, total: Int, done: Int, message: String, auditKind: String): IndexReport {
        val r = stats.report().copy(error = message)
        _progress.value = IndexProgress(false, label, done, total, "", r)
        AppGraph.audit.append(
            auditKind,
            JsonObject(mapOf("label" to JsonPrimitive(label), "done" to JsonPrimitive(done), "total" to JsonPrimitive(total))),
        )
        return r
    }

    fun embedderStoppedMessage(done: Int) = "Embedder stopped, index unchanged past file $done"

    /** The run did not start: nothing was read, written or wiped. The reason is shown in the library. */
    private fun refuse(label: String, reason: String): IndexReport {
        val r = IndexReport(label, error = reason)
        _progress.value = IndexProgress(false, label, last = r)
        refresh()
        AppGraph.audit.append("pdu.index_refused", JsonObject(mapOf("label" to JsonPrimitive(label), "reason" to JsonPrimitive(reason))))
        return r
    }

    /** The owner's explicit "Re-index everything": read every known file again with the current embedder. */
    suspend fun reindexAll(): IndexReport {
        val refs = store.allDocRefs()
        return runIndex("re-index", true) { refs }
    }

    /** True when the stored vectors were made under a different embedder or document prefix than the current ones. */
    fun reindexNeeded(): Boolean = _reindexNeeded.value

    fun setPrefixes(req: PrefixRequest): PrefixReport {
        req.query?.let { PduEmbedConfig.queryPrefix = it }
        req.document?.let { PduEmbedConfig.documentPrefix = it }
        PduEmbedConfig.save(store)
        refresh() // so the answer below is about the new prefix, not the last 4 s poll
        return PrefixReport(PduEmbedConfig.queryPrefix, PduEmbedConfig.documentPrefix, reindexNeeded())
    }

    /** Debug calibration: dense cosine and BM25 score of the strongest chunks for [text]. */
    suspend fun explain(text: String, top: Int = 10): ScoreReport {
        check(denseUsable()) { IndexGuard.NOT_LOADED }
        val terms = QueryTerms.terms(text)
        val vec = PduStore.fit(AppGraph.embedder.embed(listOf(PduEmbedConfig.query(text))).first())
        val (spread, rows) = withContext(Dispatchers.Default) { store.explain(vec, terms, top.coerceIn(1, 30)) }
        return ScoreReport(
            text, AppGraph.embedder.modelName, PduEmbedConfig.queryPrefix, terms, HybridRanker.DENSE_MIN_COSINE.toDouble(),
            HybridRanker.MIN_COVERAGE, store.chunkCount, spread, rows,
        )
    }

    suspend fun runRequest(req: IndexRequest): IndexReport {
        val label = listOfNotNull(
            if (req.photos) "photos" + (req.relPrefix?.let { " $it" } ?: "") else null,
            req.dir?.let { "dir $it" },
        ).joinToString(" + ").ifEmpty { "nothing selected" }
        val roots = ArrayList<IndexRoot>()
        if (req.photos && req.limit == null) roots += photosRoot(req.relPrefix) // a limited listing is not the whole root
        req.dir?.let { d -> roots += IndexRoot(android.net.Uri.fromFile(File(d).canonicalFile).toString().trimEnd('/') + "/") }
        return runIndex(label, req.force, roots) {
            val ctx = AppGraph.appContext
            val out = ArrayList<DocRef>()
            if (req.photos) out += PduIndexer.listPhotos(ctx, req.relPrefix, req.limit)
            req.dir?.let { d ->
                check(Environment.isExternalStorageManager()) { "all-files access is not granted to this app" }
                out += PduIndexer.listDir(File(d))
            }
            out
        }
    }

    private fun photosRoot(relPrefix: String?) = IndexRoot(
        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI.toString().trimEnd('/') + "/",
        kinds = setOf("photo"), folderPrefix = relPrefix?.trim('/')?.takeIf { it.isNotEmpty() },
    )

    suspend fun indexPhotos(relPrefix: String? = null): IndexReport =
        runIndex("photos", false, listOf(photosRoot(relPrefix))) { PduIndexer.listPhotos(AppGraph.appContext, relPrefix, null) }

    suspend fun indexTree(tree: android.net.Uri): IndexReport {
        val rootDoc = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree)).toString()
        return runIndex("folder", false, listOf(IndexRoot(rootDoc))) { PduIndexer.listTree(AppGraph.appContext, tree) }
    }

    /**
     * A scanned page that was already recognised: index it under the user's title. It stays on this phone (Pictures/UstaadScans)
     * unless [share] is on, which is the owner's explicit choice and marks Pictures/UstaadScans/Shared as shared. The image is saved
     * to the gallery by [save] only after the page has been embedded and is about to be stored, so a refusal leaves no image behind
     * and a retry cannot save it twice; [save] returns the image's uri and DATE_MODIFIED in ms.
     */
    suspend fun indexScan(title: String, share: Boolean, text: String, save: () -> Pair<String, Long>, savedHere: Boolean = true): IndexReport {
        if (!indexLock.tryLock()) return IndexReport("scan", error = "an index run is already in progress")
        return try {
            withContext(Dispatchers.IO) {
                val client = AppGraph.embedder
                val st = embedderStatus(client)
                val g = IndexGuard.prepare(store, st.name, st.ready, PduEmbedConfig.documentPrefix, false)
                if (g is IndexGuard.Outcome.Refused) return@withContext refuse("scan", g.reason)
                val run = IndexRun(store, client, { AppGraph.embedder }, PduEmbedConfig.documentPrefix, replacing = false)
                val stats = IndexStats("scan")
                val t0 = now()
                PduActivity.set(PduActivity.indexingScan())
                // folder and lastModified match what a later "Index photos" run sees, so it skips this scan instead of rewriting it
                val folder = PduIndexer.scanFolder(share)
                if (share) store.setFolderPrivate(folder, false)
                try {
                    PduIndexer.ingestScan(AppGraph.appContext, store, title, folder, text, stats, run, save, savedHere)
                } catch (e: EmbedderStopped) {
                    return@withContext IndexReport("scan", error = "Embedder stopped, the scan was not indexed")
                } catch (e: IndexFull) {
                    return@withContext IndexReport("scan", error = ChunkCap.message())
                }
                stats.totalMs = now() - t0
                store.setMeta("lastRunMs", System.currentTimeMillis().toString())
                refresh()
                stats.report()
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            IndexReport("scan", error = "${t.javaClass.simpleName}: ${t.message}")
        } finally {
            PduActivity.clear()
            indexLock.unlock()
        }
    }

    /** The debug route's form: the image was saved by the caller already. */
    suspend fun indexScan(imageUri: String, modifiedMs: Long, title: String, share: Boolean, text: String): IndexReport =
        indexScan(title, share, text, { imageUri to modifiedMs }, savedHere = false)

    // ---- search on this phone -----------------------------------------------------------------------------

    suspend fun searchLocal(text: String): LocalSearch = searchLocalWith(text, allowDense = true)

    /** The same search with keywords only: no embedder is asked, so it cannot wait for one. What the ask falls back to after its budget. */
    suspend fun searchLocalKeywords(text: String): LocalSearch = searchLocalWith(text, allowDense = false)

    private suspend fun searchLocalWith(text: String, allowDense: Boolean): LocalSearch {
        val terms = QueryTerms.terms(text)
        if (store.chunkCount == 0) return LocalSearch(emptyList(), terms, 0, 0)
        val dense = allowDense && denseUsable()
        val t0 = now()
        val vec = if (dense) QueryVectors.of(AppGraph.embedder, text) else null
        val t1 = now()
        val cands = withContext(Dispatchers.Default) { store.search(vec, terms) }
        val t2 = now()
        lastSearchMs = t2 - t1
        return LocalSearch(cands, terms, t1 - t0, t2 - t1, dense)
    }

    /** Dense search needs a real, answering embedder and vectors made by that same embedder; otherwise keywords only. */
    suspend fun denseUsable(): Boolean {
        val st = embedderStatus()
        return st.ready && !IndexGuard.reindexNeeded(store, st.name, PduEmbedConfig.documentPrefix)
    }

    /** Serve a peer: search, apply the share policy, return only what policy allows. Logs one audit line, keeps nothing else. */
    suspend fun servePeer(q: PduQuery, fromNode: String): PduResponse {
        val knownName = AppGraph.registry.peers.value.firstOrNull { it.info.nodeId == fromNode }?.info?.name
        return PduActivity.during(PduActivity.servingPeer(knownName)) {
            val t0 = now()
            val ls = searchLocal(q.text)
            val resp = PeerResponder.respond(q.qid, ls.candidates, ls.terms, q.k.coerceIn(1, 10), store.chunkCount, now() - t0)
            val bytes = SwarmJson.encodeToString(resp).toByteArray().size
            AppGraph.audit.append(
                "pdu.served",
                JsonObject(mapOf(
                    "qid" to JsonPrimitive(q.qid), "peer" to JsonPrimitive(fromNode),
                    "n" to JsonPrimitive(resp.hits.size), "bytes" to JsonPrimitive(bytes),
                    "withheld" to JsonPrimitive(resp.withheldPrivate),
                )),
            )
            val peerName = knownName ?: fromNode
            _served.update { (listOf(ServedRecord(q.qid, peerName, resp.hits.size, bytes, resp.withheldPrivate, resp.hits.map { it.title }, System.currentTimeMillis())) + it).take(20) }
            resp
        }
    }

    // ---- the federated ask --------------------------------------------------------------------------------

    /**
     * Sends the signed [q] to one paired phone and waits at most [deadlineMs]. Only cancellation is thrown; every other failure is in
     * the answer. What comes back is cut to the sizes a phone may send ([PduResponse.capped]): a peer is not trusted for size.
     */
    suspend fun queryPeer(p: PeerState, q: PduQuery, deadlineMs: Int): PeerAnswer {
        val r0 = now()
        return try {
            val resp = withTimeoutOrNull(deadlineMs.toLong()) { AppGraph.nodeClient.post<PduResponse, PduQuery>(p.info.ip, "/pdu/query", q) }?.capped()
            val rtt = now() - r0
            if (resp == null) PeerAnswer(null, "unreachable", "no answer in $deadlineMs ms", rtt) else PeerAnswer(resp, "ok", null, rtt)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            // connection refused / reset / timeout all mean "that phone is not answering"
            val status = if (t is java.io.IOException) "unreachable" else "error"
            PeerAnswer(null, status, "${t.javaClass.simpleName}: ${t.message}".take(120), now() - r0)
        }
    }

    /** Ends the ask in progress, keeping what it has found and written so far. False when none is running. */
    fun stopAsk(): Boolean = askSlot.stop()

    /** What an ask had when it was stopped or replaced. */
    private class AskHandle { @Volatile var last: QueryUi? = null }

    /**
     * Full flow: look in the knowledge bank, query every online peer and this phone, merge, show cards, stream the answer.
     * Returns the final state. A saved answer that clears the relevance floor ends the ask at once (no peers, no model) unless
     * [useSaved] is false, which is what "Ask again" passes. One ask runs at a time: a new one cancels the one before it, and
     * [stopAsk] cancels the running one, which then ends as "done" with a note and the text it had.
     */
    suspend fun ask(text: String, k: Int = 5, deadlineMs: Int = 1500, useSaved: Boolean = true): QueryUi {
        val handle = AskHandle()
        val result = askSlot.run {
            try {
                runAsk(handle, text, k, deadlineMs, useSaved)
            } finally {
                PduActivity.clear()
            }
        }
        return result ?: handle.last ?: _query.value ?: QueryUi("", text, "done", note = AnswerNotes.STOPPED)
    }

    private fun updateQuery(qid: String, f: (QueryUi) -> QueryUi) = _query.update { if (it?.qid == qid) f(it) else it }

    private suspend fun runAsk(handle: AskHandle, text: String, k: Int, deadlineMs: Int, useSaved: Boolean): QueryUi {
        val t0 = now()
        val qid = UUID.randomUUID().toString().take(8)
        // visible at once: Stop has something to stop, and a cancelled ask can be closed in the state the screen shows
        _query.value = QueryUi(qid, text, "searching")
        try {
            return askBody(qid, t0, text, k, deadlineMs, useSaved)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                updateQuery(qid) { closedAsk(it, AnswerNotes.STOPPED, now() - t0) }
                handle.last = _query.value?.takeIf { it.qid == qid }
            }
            throw e
        } catch (e: Throwable) {
            // any other failure must not leave the ask in "searching" or "answering": that disables Ask for good and leaves Stop with nothing to stop
            withContext(NonCancellable) {
                updateQuery(qid) { closedAsk(it, ASK_FAILED_NOTE, now() - t0) }
                handle.last = _query.value?.takeIf { it.qid == qid }
            }
            throw e
        }
    }

    private suspend fun askBody(qid: String, t0: Long, text: String, k: Int, deadlineMs: Int, useSaved: Boolean): QueryUi {
        val self = AppGraph.registry.self.value
        val peers = AppGraph.registry.peers.value
        val q = PduQuery(qid, text, k, deadlineMs)
        val qBytes = SwarmJson.encodeToString(q).toByteArray().size

        // the knowledge bank first: cards stay usable when the phone that held the files is away. Three seconds for the lookup with
        // the embedder; after that, keywords only.
        val kb = try {
            Bounded.withFallback(Bounded.LOOKUP_BUDGET_MS, { KbEngine.search(text) }, { KbEngine.search(text, dense = false) })
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            KbSearchResult.NONE
        }
        val saved = if (useSaved) kb.hits.firstOrNull()?.takeIf { KbSearch.answersByItself(it) } else null
        if (saved != null) {
            _query.value = QueryUi(
                qid, text, "done", cards = kb.hits, savedAnswer = saved,
                sources = KbSearch.sourcesOf(saved.entry.card), // the files the saved text cites, numbered as it numbers them
                timings = Timings(embedMs = kb.embedMs, totalMs = now() - t0),
                auditHash = AppGraph.audit.append(
                    "pdu.ask_saved", JsonObject(mapOf("qid" to JsonPrimitive(qid), "card" to JsonPrimitive(saved.entry.card.id))),
                ).writtenHash,
            )
            return _query.value!!
        }
        val cardSources = kb.hits.mapIndexed { i, h ->
            MergedHit(h.entry.card.authorId, h.entry.card.authorName, h.entry.card.authorId == self.nodeId, KbSearch.asHit(h, i + 1), 0.0)
        }

        // the chat model is captured once; the placeholder client or a server that does not answer never writes an answer
        val chat = AppGraph.llm
        val chatReady = IndexGuard.usable(chat)
        val model = if (chatReady) chat.modelName else ""

        val results = LinkedHashMap<String, DeviceResult>()
        val hits = HashMap<String, DeviceHits>()
        val localPrivate = HashSet<String>()
        results[self.nodeId] = DeviceResult(self.nodeId, self.name, true, "waiting")
        for (p in peers) {
            results[p.info.nodeId] = if (p.away) DeviceResult(p.info.nodeId, p.info.name, false, "offline", "marked away by the health monitor")
            else DeviceResult(p.info.nodeId, p.info.name, false, "waiting", sentBytes = qBytes)
        }
        val online = peers.filter { !it.away }
        PduActivity.set(PduActivity.searching(online.size))
        _query.value = QueryUi(qid, text, "searching", devices = results.values.toList(), model = model, cards = kb.hits)
        AppGraph.audit.append(
            "pdu.sent",
            JsonObject(mapOf("qid" to JsonPrimitive(qid), "peers" to JsonPrimitive(online.size), "bytes" to JsonPrimitive(qBytes * online.size))),
        )

        fun update(f: (QueryUi) -> QueryUi) = updateQuery(qid, f)
        val lock = Any()
        fun publish(timings: (Timings) -> Timings = { it }) = synchronized(lock) {
            val merged = DeviceMerge.merge(results.keys.mapNotNull { hits[it] })
            val sources = merged.mapIndexed { i, m ->
                SourceUi(i + 1, m.deviceId, m.deviceName, m.local, m.hit, m.local && m.hit.uriHash in localPrivate)
            }
            val incomplete = results.values.any { !it.local && it.status != "ok" && it.status != "waiting" }
            update {
                var t = timings(it.timings)
                if (t.firstCardsMs < 0 && sources.isNotEmpty()) t = t.copy(firstCardsMs = now() - t0)
                it.copy(sources = sources, devices = results.values.toList(), incomplete = incomplete, timings = t)
            }
        }
        publish()

        coroutineScope {
            launch {
                try {
                    // three seconds with the embedder, then keywords only on this phone's own files
                    val ls = Bounded.withFallback(Bounded.LOOKUP_BUDGET_MS, { searchLocal(text) }, { searchLocalKeywords(text) })
                    val local = ls.candidates.take(k)
                    synchronized(lock) {
                        local.filter { it.isPrivate }.forEach { localPrivate += it.docId }
                        hits[self.nodeId] = DeviceHits(self.nodeId, self.name, true, local.mapIndexed { i, c -> PeerResponder.hit(c, ls.terms, i + 1) })
                        results[self.nodeId] = DeviceResult(
                            self.nodeId, self.name, true, "ok", hits = local.size, tookMs = ls.embedMs + ls.searchMs,
                            indexedChunks = store.chunkCount, titles = local.map { it.title },
                        )
                    }
                    update { it.copy(lexicalOnly = !ls.dense) }
                    publish { it.copy(embedMs = ls.embedMs, localSearchMs = ls.searchMs) }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    synchronized(lock) { results[self.nodeId] = DeviceResult(self.nodeId, self.name, true, "error", "${t.javaClass.simpleName}: ${t.message}") }
                    publish()
                }
            }
            for (p in online) launch {
                val answer = queryPeer(p, q, deadlineMs)
                val resp = answer.response
                val result = if (resp == null) {
                    DeviceResult(p.info.nodeId, p.info.name, false, answer.status, answer.detail, rttMs = answer.rttMs, sentBytes = qBytes)
                } else {
                    val bytes = SwarmJson.encodeToString(resp).toByteArray().size
                    DeviceResult(
                        p.info.nodeId, p.info.name, false, "ok", hits = resp.hits.size, bytes = bytes, rttMs = answer.rttMs, tookMs = resp.tookMs,
                        withheldPrivate = resp.withheldPrivate, indexedChunks = resp.indexedChunks, titles = resp.hits.map { it.title }, sentBytes = qBytes,
                    )
                }
                val peerHits = resp?.let { DeviceHits(p.info.nodeId, p.info.name, false, it.hits) }
                // the only thing about a peer's answer that is written down: counts, never content
                AppGraph.audit.append(
                    "pdu.recv",
                    JsonObject(mapOf(
                        "qid" to JsonPrimitive(qid), "peer" to JsonPrimitive(p.info.nodeId), "n" to JsonPrimitive(result.hits),
                        "bytes" to JsonPrimitive(result.bytes), "status" to JsonPrimitive(result.status),
                    )),
                )
                synchronized(lock) {
                    results[p.info.nodeId] = result
                    peerHits?.let { hits[p.info.nodeId] = it }
                }
                publish()
            }
        }

        // cards are final; now the answer
        val sources = _query.value?.takeIf { it.qid == qid }?.sources.orEmpty()
        val incomplete = _query.value?.incomplete ?: false
        update { it.copy(phase = "answering", auditHash = AppGraph.audit.lastEntryHash) }
        val sb = StringBuilder()
        var ttft = -1L
        var note: String? = null
        if (sources.isEmpty() && cardSources.isEmpty()) {
            sb.append(AnswerNotes.NOTHING_FOUND)
            if (incomplete) sb.append(" Some phones did not answer.")
        } else if (!chatReady) {
            note = AnswerNotes.NO_CHAT_MODEL // the sources above are the result; no model text, in any build
        } else {
            PduActivity.set(PduActivity.answering(sources.size + cardSources.size))
            // take the chat slot here, with a bound on the wait, instead of inside GatedLlmClient where a wait has no end
            val exec = Sched.node.chatExec
            var slot = false
            var gaveUp = false
            if (exec.tryAcquire()) {
                slot = true
            } else {
                update { it.copy(note = AnswerNotes.WAITING) }
                when (val w = ModelWait.acquire(exec)) {
                    ModelWait.Result.TimedOut -> gaveUp = true
                    is ModelWait.Result.Slot -> slot = w.taken
                }
                update { it.copy(note = null) }
            }
            if (gaveUp) {
                note = AnswerNotes.GAVE_UP
            } else {
                try {
                    // a saved answer is listed as one source among the others; the model is not told it is more reliable
                    val merged = sources.map { MergedHit(it.deviceId, it.deviceName, it.local, it.hit, 0.0) } + cardSources
                    // no token for 45 s ends the answer with what was written so far: the stream's own read timeout is minutes long
                    IdleStream.collect(chat.chatStream(PromptBuilder.build(text, merged, lean = leanSources)), IdleStream.ANSWER_IDLE_MS) { c ->
                        c.stats?.let(Sched.node::noteStats)
                        if (ttft < 0 && c.delta.isNotEmpty()) ttft = now() - t0
                        sb.append(c.delta)
                        val snapshot = sb.toString()
                        update { it.copy(answer = snapshot) }
                    }
                } catch (e: StreamIdle) {
                    note = AnswerNotes.quiet(e.idleMs)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    note = AnswerNotes.stopped("${t.javaClass.simpleName} ${t.message}".take(120))
                } finally {
                    if (slot) exec.release()
                }
            }
        }
        val total = now() - t0
        update { it.copy(phase = "done", answer = sb.toString(), note = note, timings = it.timings.copy(ttftMs = ttft, totalMs = total)) }
        return _query.value!!
    }

    // ---- opening a cited file -----------------------------------------------------------------------------

    suspend fun openLocal(uriHash: String): PduOpenResult {
        val doc = store.doc(uriHash) ?: return PduOpenResult(false, "none", error = "unknown file")
        return FileOpener.open(AppGraph.appContext, doc)
    }

    /**
     * A peer asked for this file to be shown on this phone. Nothing opens by itself: the owner gets a notification and taps it.
     * Private files are refused, and each peer may ask three times a minute.
     */
    suspend fun openForPeer(uriHash: String, fromNode: String): PduOpenResult {
        if (!openLimiter.tryAcquire(fromNode)) return PduOpenResult(false, "none", error = "too many requests, try again in a minute")
        val doc = store.doc(uriHash) ?: return PduOpenResult(false, "none", error = "unknown file")
        if (doc.isPrivate) return PduOpenResult(false, "none", error = "not shared")
        val who = AppGraph.registry.peers.value.firstOrNull { it.info.nodeId == fromNode }?.info?.name ?: "Another phone"
        return FileOpener.askOwner(AppGraph.appContext, doc, who)
    }

    /** Local citation: open here. Remote citation: ask the owning phone to open it on its own screen. */
    suspend fun openSource(src: SourceUi): PduOpenResult {
        // a source listed with a saved answer is a name and a date, not a file this phone can ask for
        if (src.hit.uriHash.isEmpty()) return PduOpenResult(false, "none", error = "the file is on ${src.deviceName}; open it there")
        if (src.local) return openLocal(src.hit.uriHash)
        val peer = AppGraph.registry.peers.value.firstOrNull { it.info.nodeId == src.deviceId }
            ?: return PduOpenResult(false, "none", error = "${src.deviceName} is not in the registry")
        return try {
            AppGraph.nodeClient.post<PduOpenResult, PduOpenRequest>(peer.info.ip, "/pdu/open", PduOpenRequest(src.hit.uriHash))
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            PduOpenResult(false, "none", error = "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ---- debug: connect peers by IP through the link module (heartbeat included) --------------------------

    @Serializable
    data class DebugPeerResult(val ip: String, val ok: Boolean, val name: String? = null, val nodeId: String? = null, val error: String? = null)

    suspend fun addPeersByIp(ips: List<String>): List<DebugPeerResult> = ips.map { ip ->
        val host = ip.substringBefore(':')
        val port = ip.substringAfter(':', "").toIntOrNull() ?: Ports.NODE
        when (val r = PeerLinks.connect(host, port)) {
            is ConnectResult.Ok -> DebugPeerResult(ip, true, r.status.name, r.status.nodeId)
            is ConnectResult.Untrusted -> DebugPeerResult(ip, false, r.name, r.nodeId, "different pairing secret")
            is ConnectResult.Failed -> DebugPeerResult(ip, false, error = r.reason.take(120))
        }
    }
}
