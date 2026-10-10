package com.swarmos.app.choir

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * One evaluation at a time with a Stop: the eval runs on its own job, the live-state line of the phone shows its
 * progress with a Stop action, and stopping it keeps what was finished. A stopped eval stores its partial results
 * (`choir-eval-<ts>.json`, `stopped: true`), updates [progress] and clears the live-state line; the caller gets that
 * partial report back instead of an error. No Android types: the app glue lives in [ChoirController].
 */
class EvalSession(private val scope: CoroutineScope) {
    private val _progress = MutableStateFlow(EvalProgress())
    val progress: StateFlow<EvalProgress> = _progress.asStateFlow()

    @Volatile private var job: Job? = null
    @Volatile private var stoppedReport: EvalReport? = null

    /** Stops the running evaluation (the Stop on the live-state line calls this). Nothing happens when none runs. */
    fun cancel() {
        job?.cancel(CancellationException("stopped from the phone"))
    }

    /** Shows why an evaluation did not start. */
    fun refuse(reason: String) {
        _progress.value = EvalProgress(error = reason)
    }

    suspend fun run(
        problems: List<Problem>,
        runOne: suspend (Problem) -> RunTrace,
        runSamples: (suspend (Problem) -> SamplesResult)?,
        useProblemCheck: Boolean,
        newReport: (List<EvalRow>) -> EvalReport,
        dir: File,
        fakeSeen: () -> Boolean = { false },
    ): EvalReport {
        stoppedReport = null
        val running = scope.async { body(problems, runOne, runSamples, useProblemCheck, newReport, dir, fakeSeen) }
        job = running
        try {
            return running.await()
        } catch (e: CancellationException) {
            // stopped from the phone while the caller is still alive: hand back what was kept, not a failure
            val kept = stoppedReport
            if (running.isCancelled && currentCoroutineContext().isActive && kept != null) return kept
            running.cancel()
            throw e
        } finally {
            job = null
        }
    }

    private suspend fun body(
        problems: List<Problem>,
        runOne: suspend (Problem) -> RunTrace,
        runSamples: (suspend (Problem) -> SamplesResult)?,
        useProblemCheck: Boolean,
        newReport: (List<EvalRow>) -> EvalReport,
        dir: File,
        fakeSeen: () -> Boolean,
    ): EvalReport {
        val n = problems.size
        ChoirActivity.set(ChoirActivity.check(0, n), ::cancel)
        _progress.value = EvalProgress(running = true, total = n, useProblemCheck = useProblemCheck)
        try {
            val rows = ChoirEval.run(
                problems,
                runOne = runOne,
                runSamples = runSamples,
                onProgress = { done, cur ->
                    ChoirActivity.set(ChoirActivity.check(done.size, n), ::cancel)
                    _progress.value = EvalProgress(
                        running = cur != null, done = done.size, total = n, current = cur?.id,
                        rows = done, summary = ChoirEval.summarize(done), fake = fakeSeen(), useProblemCheck = useProblemCheck,
                    )
                },
            )
            val report = ChoirEval.store(dir, newReport(rows))
            _progress.value = EvalProgress(
                running = false, done = rows.size, total = n, lastFile = report.file,
                summary = report.summary, rows = rows, fake = report.fake, useProblemCheck = useProblemCheck,
            )
            return report
        } catch (e: EvalCancelled) {
            // keep what was finished, even though this coroutine is being cancelled
            withContext(NonCancellable) {
                val partial = newReport(e.rows).copy(stopped = true)
                val kept = if (e.rows.isEmpty()) partial else ChoirEval.store(dir, partial)
                stoppedReport = kept
                _progress.value = EvalProgress(
                    running = false, done = e.rows.size, total = n, lastFile = kept.file,
                    summary = kept.summary, rows = e.rows, fake = kept.fake, useProblemCheck = useProblemCheck, stopped = true,
                )
            }
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            _progress.value = _progress.value.copy(running = false, error = t.message ?: t::class.simpleName)
            throw t
        } finally {
            ChoirActivity.clear()
        }
    }
}
