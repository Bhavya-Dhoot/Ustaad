package com.swarmos.app.pdu

import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.swarmos.app.AppGraph
import com.swarmos.app.core.ThermalLevel
import com.swarmos.app.shell.photoLibraryPermission
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Whether an automatic index run may start now. One pure function, so the rule is read, and held by tests, in one place. */
object AutoIndexGate {
    const val MIN_BATTERY_PCT = 30

    enum class Decision(val why: String) {
        GO(""),
        RUN_ACTIVE("an index run is already going"),
        EMBEDDER("the embedder is not ready, or the index waits for the owner's re-index"),
        ASKING("a question is being answered"),
        HOT("thermal status 3 or more"),
        BATTERY("battery under $MIN_BATTERY_PCT % and not charging"),
    }

    /** [batteryPct] is -1 when unknown, which does not hold a run back (the phone is then most likely on a charger or a test rig). */
    fun decide(
        embedderReady: Boolean, reindexNeeded: Boolean, runActive: Boolean, asking: Boolean,
        thermalStatus: Int, batteryPct: Int, charging: Boolean,
    ): Decision = when {
        runActive -> Decision.RUN_ACTIVE
        !embedderReady || reindexNeeded -> Decision.EMBEDDER
        asking -> Decision.ASKING
        ThermalLevel.isTooHot(thermalStatus) -> Decision.HOT
        !charging && batteryPct in 0 until MIN_BATTERY_PCT -> Decision.BATTERY
        else -> Decision.GO
    }
}

/**
 * What waits to be indexed, and when it is due. Time is passed in, so the debounce, the longest wait and the period are held by tests
 * without a clock. Photos (the library) and picked folders are the two sources; a request, and the first run after start, cover both.
 */
class AutoIndexPlan(
    startMs: Long,
    private val debounceMs: Long = DEBOUNCE_MS,
    private val maxWaitMs: Long = MAX_WAIT_MS,
    private val periodMs: Long = PERIOD_MS,
) {
    /** [at] is when it was asked for, so a change that arrives while the run is going is not lost by [ran]. */
    class Due(val photos: Boolean, val trees: Boolean, val reasons: List<String>, internal val at: Long, internal val asked: Set<String>)

    private val asap = LinkedHashSet<String>()
    private var firstChangeMs = -1L
    private var lastChangeMs = -1L
    private var nextTreesMs = startMs + periodMs

    @Synchronized fun requested(reason: String) { asap += reason }

    @Synchronized fun libraryChanged(now: Long) {
        if (firstChangeMs < 0) firstChangeMs = now
        lastChangeMs = now
    }

    /** What is due at [now], or null. Stays due until [ran] says it was done. */
    @Synchronized fun due(now: Long): Due? {
        val changed = lastChangeMs >= 0 && (now - lastChangeMs >= debounceMs || now - firstChangeMs >= maxWaitMs)
        val timer = now >= nextTreesMs
        if (asap.isEmpty() && !changed && !timer) return null
        val reasons = asap.toList() + listOfNotNull("photo library changed".takeIf { changed }, "every 15 minutes".takeIf { timer && asap.isEmpty() })
        return Due(asap.isNotEmpty() || changed, asap.isNotEmpty() || timer, reasons, now, asap.toSet())
    }

    /** The run for [d] ended. An incomplete one (stopped by the gate half way) leaves everything due. */
    @Synchronized fun ran(now: Long, d: Due, complete: Boolean) {
        if (!complete) return
        asap -= d.asked
        if (d.photos) {
            if (lastChangeMs <= d.at) { firstChangeMs = -1; lastChangeMs = -1 } else firstChangeMs = lastChangeMs // a change during the run waits its own 30 s
        }
        if (d.trees) nextTreesMs = now + periodMs
    }

    companion object {
        const val DEBOUNCE_MS = 30_000L
        /** A library that never goes quiet (a camera burst, a sync) is still read after this long. */
        const val MAX_WAIT_MS = 10 * 60_000L
        const val PERIOD_MS = 15 * 60_000L
    }
}

/** The last automatic run, as the Data Union screen shows it. */
data class AutoRun(val atMs: Long, val reason: String, val summary: String)

/**
 * The automatic index runs, all through the same incremental run as the buttons (one at a time): once after the service starts and the
 * embedder is ready, 30 s after the photo library last changed, every 15 minutes for picked folders, and when another part asks with
 * [PduEngine.requestIndex]. Photos are only read again when the owner has indexed them before and the permission is still held.
 */
object PduAutoIndex {
    private const val TICK_MS = 5_000L
    private const val PHOTOS_KEY = "autoPhotos"

    private val plan by lazy { AutoIndexPlan(SystemClock.elapsedRealtime()) }
    private val _last = MutableStateFlow<AutoRun?>(null)
    val last: StateFlow<AutoRun?> = _last.asStateFlow()
    private var lastWait: AutoIndexGate.Decision? = null

    fun request(reason: String) = plan.requested(reason)

    /** The owner indexed photos by hand: from now on the library is followed. */
    fun followPhotos() { if (PduEngine.store.meta(PHOTOS_KEY) != "1") PduEngine.store.setMeta(PHOTOS_KEY, "1") }

    private fun photosFollowed() = PduEngine.store.meta(PHOTOS_KEY) == "1" || PduEngine.counts.value.photos > 0

    /** Runs until cancelled (the service's start job): watches the library, checks the timer, starts runs the gate allows. */
    suspend fun serve() {
        val resolver = AppGraph.appContext.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = plan.libraryChanged(SystemClock.elapsedRealtime())
        }
        PduEngine.start()
        resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        plan.requested("app start")
        try {
            while (true) {
                delay(TICK_MS)
                StepGuard.run({ t -> AppGraph.audit.append("pdu.auto_index_error", JsonObject(mapOf("error" to JsonPrimitive(StepGuard.describe("auto index", t))))) }) { tick() }
            }
        } finally {
            resolver.unregisterContentObserver(observer)
        }
    }

    private fun gate(): AutoIndexGate.Decision {
        val h = AppGraph.health.state.value
        return AutoIndexGate.decide(
            PduEngine.embedder.value.ready, PduEngine.reindexNeeded.value, PduEngine.progress.value.running, PduEngine.asking,
            h.thermalStatus, h.batteryPct, h.charging,
        )
    }

    private suspend fun tick() {
        val due = plan.due(SystemClock.elapsedRealtime()) ?: return
        val g = gate()
        if (g != AutoIndexGate.Decision.GO) {
            if (g != lastWait) AppGraph.audit.append("pdu.auto_index_wait", JsonObject(mapOf("why" to JsonPrimitive(g.why))))
            lastWait = g
            return
        }
        lastWait = null
        val ctx = AppGraph.appContext
        val parts = ArrayList<String>()
        var complete = true
        // the gate is read again between sources: a phone that got hot during the photos does not go on to the folders
        suspend fun step(name: String, go: suspend () -> IndexReport) {
            if (!complete) return
            if (parts.isNotEmpty() && gate() != AutoIndexGate.Decision.GO) { complete = false; return }
            val r = go()
            parts += "$name: " + (r.error ?: "${r.indexed} indexed, ${r.unchanged} unchanged, ${r.removed} removed")
        }
        if (due.photos && photosFollowed() &&
            ContextCompat.checkSelfPermission(ctx, photoLibraryPermission(Build.VERSION.SDK_INT)) == PackageManager.PERMISSION_GRANTED
        ) step("photos") { PduEngine.indexPhotos() }
        if (due.trees) {
            for (t in ctx.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }) {
                step(android.net.Uri.decode(t.lastPathSegment ?: "folder")) { PduEngine.indexTree(t) }
            }
        }
        plan.ran(SystemClock.elapsedRealtime(), due, complete)
        val reason = due.reasons.joinToString(", ")
        val summary = parts.joinToString("; ").ifEmpty { "nothing to read" }
        _last.value = AutoRun(System.currentTimeMillis(), reason, summary)
        // the audit log gets the reason and the number of sources, not their names: a picked folder's name is the owner's own
        AppGraph.audit.append(
            "pdu.auto_index",
            JsonObject(mapOf("reason" to JsonPrimitive(reason), "sources" to JsonPrimitive(parts.size), "complete" to JsonPrimitive(complete))),
        )
    }
}
