package com.swarmos.app.hands.android

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * PhoneDriver over the accessibility service. One call at a time (a mutex), all of them off the main thread.
 * It keeps the nodes of the last read() by handle, with the class, text and description of each node of every subtree
 * as read, and acts only on those nodes through accessibility actions (no gesture, no touch by position); a node the
 * system no longer has, or whose subtree differs, gives false and the core reads again. It carries no policy and
 * logs no screen content, only the class of an exception.
 */
class AccessibilityDriver(ctx: Context) : PhoneDriver {
    private val app = ctx.applicationContext
    private val lock = Mutex()

    // handle -> node of the last read, and handle -> facts of that node and its subtree as read. Both guarded by lock;
    // empty when the last read gave no app window.
    private var nodes: Map<String, AccessibilityNodeInfo> = emptyMap()
    private var facts: Map<String, FactNode> = emptyMap()

    override val name: String = "accessibility"

    override fun ready(): Boolean = HandsAccessibilityService.instance != null

    override suspend fun read(): RawScreen {
        val (w, h) = screenSize()
        val none = RawScreen(pkg = "", activity = "", widthPx = w, heightPx = h, root = null, unreadable = true)
        return step(none) { svc ->
            val job = currentCoroutineContext()
            // Stop cancels the call; the walk notices between two child lookups
            readNow(svc, w, h, checkCancelled = { job.ensureActive() }) ?: none
        }
    }

    // tap, setText, scroll and enter check again, at call time, that none of our own windows is in front

    /**
     * ACTION_CLICK on the node when it is clickable, else on its nearest clickable ancestor (the guard judged the element
     * together with that ancestor's text), else false. No gesture: nothing is ever touched by position, so cx and cy are ignored.
     */
    override suspend fun tap(handle: String, cx: Int, cy: Int): Boolean = step(false) { svc ->
        if (ownInFront(svc)) return@step false
        val job = currentCoroutineContext()
        val v = verified(handle) { job.ensureActive() } ?: return@step false
        val target = if (v.node.isClickable) v.node else v.ancestor ?: return@step false
        target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    override suspend fun setText(handle: String, cx: Int, cy: Int, text: String): Boolean = step(false) { svc ->
        if (ownInFront(svc)) return@step false
        val job = currentCoroutineContext()
        val node = verified(handle) { job.ensureActive() }?.node ?: return@step false
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

    /** ACTION_SCROLL on the exact node when it is scrollable (false when it refuses, at the end), else false. No handle: false, no swipe. */
    override suspend fun scroll(handle: String?, forward: Boolean): Boolean {
        if (handle == null) return false
        return step(false) { svc ->
            if (ownInFront(svc)) return@step false
            val job = currentCoroutineContext()
            val node = verified(handle) { job.ensureActive() }?.node ?: return@step false
            if (!node.isScrollable) return@step false
            node.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        }
    }

    override suspend fun back(): Boolean = step(false) { it.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }

    override suspend fun home(): Boolean = step(false) { it.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) }

    override suspend fun openApp(pkg: String): Boolean = step(false) { svc ->
        val intent = svc.packageManager.getLaunchIntentForPackage(pkg) ?: return@step false
        svc.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }

    /** Asks the package manager who would open the link; nothing is started. */
    override suspend fun linkTarget(uri: String): String = withContext(Dispatchers.IO) {
        try {
            if (uri.isBlank()) "" else AppCatalog.handlerOf(app, Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "link target failed: ${e.javaClass.simpleName}")
            ""
        }
    }

    override suspend fun openLink(uri: String, pkg: String): Boolean = step(false) { svc ->
        if (uri.isBlank() || pkg.isBlank()) return@step false
        svc.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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

    /**
     * The node of the last read, brought up to date, when its whole subtree (class, text and description of up to 300
     * nodes, see ScreenMapping.subtreeSignature) is the same now as at read(). null when the handle is unknown, the
     * system dropped the node, or anything in the subtree changed: the screen moved under the action, the core reads again.
     * Cost: one refresh plus one child lookup per subtree node beyond the first, at most 300, a handful for a button.
     */
    private fun unchanged(handle: String, cancelled: () -> Unit): AccessibilityNodeInfo? {
        val node = nodes[handle] ?: return null
        val read = facts[handle] ?: return null
        if (!node.refresh()) return null
        val depthLeft = ScreenMapping.MAX_DEPTH - ScreenMapping.depthOf(handle)
        val before = ScreenMapping.subtreeSignature(read, { it.kids.size }, { n, i -> n.kids[i] }, { it.facts }, maxDepth = depthLeft)
        val now = ScreenMapping.subtreeSignature(
            node, { it.childCount }, { n, i -> n.getChild(i) }, { factsOf(it) },
            release = { release(it) }, maxDepth = depthLeft, checkCancelled = cancelled,
        )
        return node.takeIf { before == now }
    }

    /** The node to act on, and for a node that is not clickable its nearest clickable ancestor (null when there is none). */
    private class Verified(val node: AccessibilityNodeInfo, val ancestor: AccessibilityNodeInfo?)

    /**
     * The node and, when it is not clickable, its nearest clickable ancestor (found by walking the handle path upward),
     * each only if [unchanged]. null when either changed. The ancestor costs one more refresh and up to 300 more lookups,
     * so a tap on a plain label is at most about 600 lookups, usually under ten.
     */
    private fun verified(handle: String, cancelled: () -> Unit): Verified? {
        val node = unchanged(handle, cancelled) ?: return null
        if (node.isClickable) return Verified(node, null)
        val up = generateSequence(ScreenMapping.parentOf(handle)) { ScreenMapping.parentOf(it) }
            .firstOrNull { nodes[it]?.isClickable == true } ?: return Verified(node, null)
        val ancestor = unchanged(up, cancelled)?.takeIf { it.isClickable } ?: return null
        return Verified(node, ancestor)
    }

    private fun factsOf(n: AccessibilityNodeInfo): NodeFacts {
        val password = n.isPassword
        return ScreenMapping.factsOf(n.className, if (password) null else n.text, n.contentDescription, password)
    }

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
        facts = emptyMap()
        old.values.forEach { release(it) }
    }

    private fun readNow(svc: AccessibilityService, w: Int, h: Int, checkCancelled: () -> Unit): RawScreen? {
        val picked = when (val l = look(svc)) {
            is Look.Open -> l
            // our own window in front is a screen to report, not one to read; no handle stays valid
            is Look.Own -> {
                clearNodes()
                return RawScreen(pkg = svc.packageName, activity = "", widthPx = w, heightPx = h, root = null, unreadable = true)
            }
            is Look.None -> { clearNodes(); return null }
        }
        val taken = HashMap<String, AccessibilityNodeInfo>()
        val takenFacts = HashMap<String, FactNode>()
        val root: RawNode = try {
            ScreenMapping.buildTree(
                root = picked.root,
                childCount = { it.childCount },
                childAt = { n, i -> n.getChild(i) },
                visit = { n, handle -> taken[handle] = n },
                make = { n, handle, kids ->
                    // kids were made first, so their facts are in the map
                    takenFacts[handle] = FactNode(factsOf(n), kids.map { takenFacts.getValue(it.handle) })
                    toRaw(n, handle, kids)
                },
                checkCancelled = checkCancelled,
            )
        } catch (e: Exception) {
            taken.values.forEach { release(it) }
            if (taken.isEmpty()) release(picked.root)
            clearNodes()
            throw e
        }
        clearNodes()
        nodes = taken
        facts = takenFacts
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

    private companion object {
        const val TAG = "HandsDriver"
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
