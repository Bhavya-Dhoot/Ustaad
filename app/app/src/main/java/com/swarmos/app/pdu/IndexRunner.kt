package com.swarmos.app.pdu

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull

/** The index is at its chunk cap ([VectorMatrix.MAX_CHUNKS]); no file can be added. */
class IndexFull : RuntimeException(ChunkCap.message())

/** One file took longer than its budget; it is marked failed and the run goes on. */
class FileTimedOut(val ms: Long) : RuntimeException("timed out after ${ms / 1000} s")

/**
 * Which stored files one index run is responsible for: those whose uri starts with [uriPrefix], of [kinds] if given, and whose
 * folder starts with [folderPrefix] if given. A finished run removes the stored files of its root that its listing did not contain.
 */
data class IndexRoot(val uriPrefix: String, val kinds: Set<String>? = null, val folderPrefix: String? = null)

object IndexSweep {
    /** Stored files that the finished run did not see: deleted, moved elsewhere or no longer in the listed folder. */
    internal fun stale(stored: List<StoredDoc>, seenUris: Set<String>): List<StoredDoc> = stored.filter { it.uri !in seenUris }

    /**
     * An empty listing proves nothing (a permission taken away and a storage that is not mounted look the same as an empty
     * folder), so it never wipes what is stored.
     */
    fun mayRemove(listed: Int, stored: Int): Boolean = listed > 0 || stored == 0
}

/** The Stop control of the index run in progress: [stop] ends it after the file in hand, and cancels that file's work. */
class IndexControl {
    @Volatile var stopRequested = false
        private set
    @Volatile private var inFlight: Job? = null

    internal fun track(j: Job?) { inFlight = j }

    fun stop() {
        stopRequested = true
        inFlight?.cancel()
    }
}

enum class LoopEnd { COMPLETE, STOPPED, EMBEDDER_STOPPED, FULL }

/** [done] files were finished (stored, skipped or marked failed) before the loop ended for [end]. */
class LoopResult(val end: LoopEnd, val done: Int)

/**
 * The file loop of an index run. Each file has two steps: prepare (read it, OCR it, cut it into chunks) and commit (embed and store).
 * Commits happen in the order of the listing, so a run that is killed resumes where it was; the prepare of file n+1 runs while file n
 * is being embedded, and never further ahead than that. Each step has a budget; a file that does not make it is reported through
 * [onFailure] and the run goes on. The prepare of a file runs on its own scope and is abandoned, not waited for, when it times out,
 * so a blocking PDF or OCR call cannot hold the run.
 */
object IndexLoop {
    const val FILE_TIMEOUT_MS = 90_000L
    const val REFRESH_EVERY = 25

    /** An embedder that times out this many files in a row is treated as stopped. */
    private const val STUCK_COMMITS = 2

    private val workers = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Pending<P : Any>(val job: Deferred<P>, val startedAtNs: Long)

    suspend fun <P : Any> run(
        refs: List<DocRef>,
        control: IndexControl,
        timeoutMs: Long,
        prepare: suspend (DocRef) -> P,
        commit: suspend (DocRef, P) -> Unit,
        onProgress: (index: Int, ref: DocRef) -> Unit,
        onFailure: (DocRef, Throwable) -> Unit,
        refresh: () -> Unit,
        commitBudgetMs: (P) -> Long = { timeoutMs },
        scope: CoroutineScope = workers,
    ): LoopResult {
        fun start(ref: DocRef) = Pending(scope.async { prepare(ref) }, System.nanoTime())
        var ahead: Pending<P>? = null
        var stuck = 0
        try {
            for (i in refs.indices) {
                if (control.stopRequested) return LoopResult(LoopEnd.STOPPED, i)
                val ref = refs[i]
                onProgress(i, ref)
                val pending = ahead ?: start(ref)
                ahead = null
                control.track(pending.job)
                val prepared: P? = try {
                    val left = timeoutMs - (System.nanoTime() - pending.startedAtNs) / 1_000_000
                    val got = withTimeoutOrNull(left.coerceAtLeast(1)) { pending.job.await() }
                    if (got == null) {
                        pending.job.cancel()
                        onFailure(ref, FileTimedOut(timeoutMs))
                    }
                    got
                } catch (e: CancellationException) {
                    if (control.stopRequested && currentCoroutineContext().isActive) return LoopResult(LoopEnd.STOPPED, i)
                    throw e
                } catch (t: Throwable) {
                    onFailure(ref, t)
                    null
                } finally {
                    control.track(null)
                }
                // the next file is read while this one is embedded
                if (i + 1 < refs.size && !control.stopRequested) ahead = start(refs[i + 1])
                if (prepared != null) {
                    try {
                        val budget = commitBudgetMs(prepared)
                        val done = withTimeoutOrNull(budget) {
                            coroutineScope {
                                val j = async { commit(ref, prepared) }
                                control.track(j)
                                try { j.await() } finally { control.track(null) }
                            }
                            true
                        }
                        if (done == null) {
                            onFailure(ref, FileTimedOut(budget))
                            if (++stuck >= STUCK_COMMITS) return LoopResult(LoopEnd.EMBEDDER_STOPPED, i)
                        } else {
                            stuck = 0
                        }
                    } catch (e: CancellationException) {
                        if (control.stopRequested && currentCoroutineContext().isActive) return LoopResult(LoopEnd.STOPPED, i)
                        throw e
                    } catch (e: EmbedderStopped) {
                        return LoopResult(LoopEnd.EMBEDDER_STOPPED, i)
                    } catch (e: IndexFull) {
                        return LoopResult(LoopEnd.FULL, i)
                    } catch (t: Throwable) {
                        onFailure(ref, t)
                    }
                }
                if ((i + 1) % REFRESH_EVERY == 0) refresh()
            }
            return LoopResult(LoopEnd.COMPLETE, refs.size)
        } finally {
            ahead?.job?.cancel()
        }
    }
}
