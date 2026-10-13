package com.swarmos.app.hands.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.swarmos.app.hands.AppInfo
import com.swarmos.app.hands.PhoneDriver
import com.swarmos.app.hands.RawNode
import com.swarmos.app.hands.RawScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * PhoneDriver over the accessibility service. One call at a time (a mutex), all of them off the main thread.
 * It keeps the nodes of the last read() by handle and acts on those; a node the system no longer has gives false,
 * the core reads again. It carries no policy and logs no screen content, only the class of an exception.
 */
class AccessibilityDriver(ctx: Context) : PhoneDriver {
    private val app = ctx.applicationContext
    private val lock = Mutex()

    // handle -> node of the last read, in reading order (parent before children). Guarded by lock.
    private var nodes: Map<String, AccessibilityNodeInfo> = emptyMap()

    override val name: String = "accessibility"

    override fun ready(): Boolean = HandsAccessibilityService.instance != null

    override suspend fun read(): RawScreen {
        val (w, h) = screenSize()
        val none = RawScreen(pkg = "", activity = "", widthPx = w, heightPx = h, root = null, unreadable = true)
        return step(none) { svc -> readNow(svc, w, h) ?: none }
    }

    // tap, setText, scroll and enter check again, at call time, that none of our own windows is in front

    override suspend fun tap(handle: String, cx: Int, cy: Int): Boolean = step(false) { svc ->
        if (ownInFront(svc)) return@step false
        val node = live(handle) ?: return@step false
        val target = (listOf(handle) + ScreenMapping.ancestorsOf(handle).take(MAX_CLICK_CLIMB))
            .firstNotNullOfOrNull { h -> nodes[h]?.takeIf { it.isClickable } }
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return@step true
        // no clickable node near it, or it refused: touch the spot, but only where the person could see it
        node.isVisibleToUser && gestureTap(svc, cx, cy)
    }

    override suspend fun setText(handle: String, cx: Int, cy: Int, text: String): Boolean = step(false) { svc ->
        if (ownInFront(svc)) return@step false
        val node = live(handle) ?: return@step false
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    override suspend fun enter(): Boolean = step(false) { svc ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@step false
        val picked = look(svc) as? Look.Open ?: return@step false
        try {
            val field = picked.root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return@step false
            try {
                field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            } finally {
                release(field)
            }
        } finally {
            release(picked.root)
        }
    }

    override suspend fun scroll(handle: String?, forward: Boolean): Boolean = step(false) { svc ->
        // with or without a handle: a swipe or a scroll action must not land on our own prompt
        if (ownInFront(svc)) return@step false
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val area = Rect()
        val target = if (handle != null) {
            live(handle)?.getBoundsInScreen(area) ?: return@step false
            (listOf(handle) + ScreenMapping.ancestorsOf(handle)).firstNotNullOfOrNull { h -> nodes[h]?.takeIf { it.isScrollable } }
        } else {
            nodes.values.firstOrNull { it.isScrollable && it.isVisibleToUser }
        }
        // a scrollable node that refuses (already at the end) answers false; a swipe is only for screens with none
        if (target != null) return@step target.performAction(action)
        if (handle == null) screenSize().let { (w, h) -> area.set(0, 0, w, h) }
        if (area.isEmpty) return@step false
        val (y1, y2) = ScreenMapping.swipeSpan(area.top, area.bottom, forward)
        val path = Path().apply { moveTo(area.centerX().toFloat(), y1.toFloat()); lineTo(area.centerX().toFloat(), y2.toFloat()) }
        gesture(svc, path, SWIPE_MS)
    }

    override suspend fun back(): Boolean = step(false) { it.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }

    override suspend fun home(): Boolean = step(false) { it.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) }

    override suspend fun openApp(pkg: String): Boolean = step(false) { svc ->
        val intent = svc.packageManager.getLaunchIntentForPackage(pkg) ?: return@step false
        svc.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }

    override suspend fun openLink(uri: String): Boolean = step(false) { svc ->
        if (uri.isBlank()) return@step false
        svc.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }

    /** Asks the package manager again every time, so an app installed a minute ago is found. */
    override suspend fun apps(): List<AppInfo> = withContext(Dispatchers.IO) {
        try {
            AppCatalog.apps(app, refresh = true)
        } catch (e: Exception) {
            Log.w(TAG, "apps failed: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    // ------------------------------------------------------------------ plumbing

    /** Runs one call under the lock on the IO dispatcher with the connected service; any failure is the fallback. */
    private suspend fun <T> step(fallback: T, block: suspend (HandsAccessibilityService) -> T): T = lock.withLock {
        withContext(Dispatchers.IO) {
            val svc = HandsAccessibilityService.instance ?: return@withContext fallback
            try {
                block(svc)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "call failed: ${e.javaClass.simpleName}")
                fallback
            }
        }
    }

    private fun screenSize(): Pair<Int, Int> = app.resources.displayMetrics.let { it.widthPixels to it.heightPixels }

    /** The node of the last read, brought up to date; null when the handle is unknown or the system dropped the node. */
    private fun live(handle: String): AccessibilityNodeInfo? = nodes[handle]?.takeIf { it.refresh() }

    /** What is in front: nothing readable, one of our own application windows, or another app (its root is the caller's to release). */
    private sealed interface Look {
        object None : Look
        object Own : Look
        class Open(val root: AccessibilityNodeInfo, val pkg: String) : Look
    }

    /**
     * Looks at the windows now. Only application windows count (the Stop chip is an accessibility overlay, the keyboard
     * and system bars are other types); the choice is ScreenMapping.pickWindow. Every other node and window object
     * taken here is released.
     */
    private fun look(svc: AccessibilityService): Look {
        val windows = svc.windows.orEmpty()
        if (windows.isEmpty()) {
            val root = svc.rootInActiveWindow ?: return Look.None
            val pkg = root.packageName?.toString().orEmpty()
            if (pkg == svc.packageName) { release(root); return Look.Own }
            return Look.Open(root, pkg)
        }
        val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val roots = apps.map { it.root }
        val cands = apps.mapIndexed { i, w -> WindowCand(w.isActive, w.isFocused, w.layer, roots[i]?.packageName?.toString().orEmpty().ifEmpty { null }) }
        val best = ScreenMapping.pickWindow(cands, svc.packageName)
        roots.forEachIndexed { i, r -> if (i != best && r != null) release(r) }
        windows.forEach { release(it) }
        if (best == ScreenMapping.PICK_OWN_FRONT) return Look.Own
        return roots.getOrNull(best)?.let { Look.Open(it, cands[best].pkg.orEmpty()) } ?: Look.None
    }

    /** True when one of our own application windows (a prompt, a sheet) is in front: nothing is acted on then. Checked on every acting call. */
    private fun ownInFront(svc: AccessibilityService): Boolean = when (val l = look(svc)) {
        is Look.Own -> true
        is Look.Open -> { release(l.root); false }
        is Look.None -> false
    }

    private fun clearNodes() {
        val old = nodes
        nodes = emptyMap()
        old.values.forEach { release(it) }
    }

    private fun readNow(svc: AccessibilityService, w: Int, h: Int): RawScreen? {
        val picked = when (val l = look(svc)) {
            is Look.Open -> l
            // our own window in front is a screen to report, not one to read; no handle stays valid
            is Look.Own -> {
                clearNodes()
                return RawScreen(pkg = svc.packageName, activity = "", widthPx = w, heightPx = h, root = null, unreadable = true)
            }
            is Look.None -> { clearNodes(); return null }
        }
        val taken = LinkedHashMap<String, AccessibilityNodeInfo>()
        val root: RawNode = try {
            ScreenMapping.buildTree(
                root = picked.root,
                childCount = { it.childCount },
                childAt = { n, i -> n.getChild(i) },
                visit = { n, handle -> taken[handle] = n },
                make = { n, handle, kids -> toRaw(n, handle, kids) },
            )
        } catch (e: Exception) {
            taken.values.forEach { release(it) }
            if (taken.isEmpty()) release(picked.root)
            clearNodes()
            throw e
        }
        clearNodes()
        nodes = taken
        val front = HandsAccessibilityService.front
        val activity = if (front.pkg == picked.pkg) front.cls else ""
        return RawScreen(pkg = picked.pkg, activity = activity, widthPx = w, heightPx = h, root = root, unreadable = false)
    }

    @Suppress("DEPRECATION") // isChecked: the tri-state getChecked() needs API 36
    private fun toRaw(n: AccessibilityNodeInfo, handle: String, kids: List<RawNode>): RawNode {
        val b = Rect()
        n.getBoundsInScreen(b)
        val password = n.isPassword
        return ScreenMapping.rawNodeOf(
            cls = n.className?.toString(),
            // a password node's text is not even fetched; rawNodeOf blanks it again
            text = if (password) null else n.text,
            desc = n.contentDescription,
            hint = n.hintText,
            showingHint = n.isShowingHintText,
            resId = n.viewIdResourceName,
            clickable = n.isClickable, editable = n.isEditable, scrollable = n.isScrollable,
            checkable = n.isCheckable, checked = n.isChecked, enabled = n.isEnabled,
            password = password, visible = n.isVisibleToUser,
            left = b.left, top = b.top, right = b.right, bottom = b.bottom,
            children = kids, handle = handle,
        )
    }

    private suspend fun gestureTap(svc: AccessibilityService, cx: Int, cy: Int): Boolean {
        val (w, h) = screenSize()
        val path = Path().apply { moveTo(cx.coerceIn(0, w - 1).toFloat(), cy.coerceIn(0, h - 1).toFloat()) }
        return gesture(svc, path, TAP_MS)
    }

    private suspend fun gesture(svc: AccessibilityService, path: Path, durationMs: Long): Boolean {
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        return withTimeoutOrNull(GESTURE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val sent = svc.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                    override fun onCancelled(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(false) }
                }, null)
                if (!sent && cont.isActive) cont.resume(false)
            }
        } ?: false
    }

    private companion object {
        const val TAG = "HandsDriver"
        const val MAX_CLICK_CLIMB = 4
        const val TAP_MS = 50L
        const val SWIPE_MS = 300L
        const val GESTURE_TIMEOUT_MS = 5_000L
    }
}

/** Nodes are pooled below Android 13 and must be given back; from 13 on the call does nothing. */
@Suppress("DEPRECATION")
private fun release(n: AccessibilityNodeInfo) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) n.recycle()
}

@Suppress("DEPRECATION")
private fun release(w: AccessibilityWindowInfo) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) w.recycle()
}
