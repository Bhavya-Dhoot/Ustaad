package com.swarmos.app.hands.android

import com.swarmos.app.hands.AppInfo
import com.swarmos.app.hands.RawNode

// The rules of the accessibility driver that need no Android class, so plain JVM tests cover them.
// AccessibilityDriver feeds these with the fields it reads; nothing here logs or stores screen content.

/** One application window as the driver sees it. pkg null: the window gave no root (secure window or gone). */
internal data class WindowCand(val active: Boolean, val focused: Boolean, val layer: Int, val pkg: String?)

internal object ScreenMapping {
    const val MAX_NODES = 1500
    const val MAX_DEPTH = 40

    /** The root of every read. A child is its parent's handle plus the child index: "0.3.1". */
    const val ROOT_HANDLE = "0"

    fun childHandle(parent: String, index: Int): String = "$parent.$index"

    /** null for the root. */
    fun parentOf(handle: String): String? {
        val i = handle.lastIndexOf('.')
        return if (i < 0) null else handle.substring(0, i)
    }

    /** Nearest first, root last; empty for the root. */
    fun ancestorsOf(handle: String): List<String> = generateSequence(parentOf(handle)) { parentOf(it) }.toList()

    /**
     * The password rule lives here: the text of a password node is never copied, whatever the caller passes.
     * A field that shows its hint as text (empty field) has no value; the hint moves to hint.
     */
    fun rawNodeOf(
        cls: String?,
        text: CharSequence?,
        desc: CharSequence?,
        hint: CharSequence?,
        showingHint: Boolean,
        resId: String?,
        clickable: Boolean,
        editable: Boolean,
        scrollable: Boolean,
        checkable: Boolean,
        checked: Boolean,
        enabled: Boolean,
        password: Boolean,
        visible: Boolean,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        children: List<RawNode>,
        handle: String,
    ): RawNode {
        val shown = if (password) "" else text?.toString().orEmpty()
        val hintText = hint?.toString().orEmpty()
        return RawNode(
            cls = cls.orEmpty(),
            text = if (showingHint) "" else shown,
            desc = desc?.toString().orEmpty(),
            hint = if (showingHint && hintText.isEmpty()) shown else hintText,
            resId = resId.orEmpty(),
            clickable = clickable, editable = editable, scrollable = scrollable,
            checkable = checkable, checked = checked, enabled = enabled,
            password = password, visible = visible,
            left = left, top = top, right = right, bottom = bottom,
            children = children, handle = handle,
        )
    }

    /**
     * Builds the tree depth first, parent before children, at most maxNodes nodes and nodes down to maxDepth
     * (the root is depth 0). visit runs when a node is entered (so a caller's map is in reading order), make runs
     * once its children are built. A child that cannot be fetched (null) is skipped and keeps its index.
     */
    fun <T : Any> buildTree(
        root: T,
        childCount: (T) -> Int,
        childAt: (T, Int) -> T?,
        visit: (T, String) -> Unit,
        make: (T, String, List<RawNode>) -> RawNode,
        maxNodes: Int = MAX_NODES,
        maxDepth: Int = MAX_DEPTH,
    ): RawNode {
        var seen = 0
        fun walk(node: T, handle: String, depth: Int): RawNode {
            seen++
            visit(node, handle)
            val kids = ArrayList<RawNode>()
            if (depth < maxDepth) {
                for (i in 0 until childCount(node)) {
                    if (seen >= maxNodes) break
                    val child = childAt(node, i) ?: continue
                    kids += walk(child, childHandle(handle, i), depth + 1)
                }
            }
            return make(node, handle, kids)
        }
        return walk(root, ROOT_HANDLE, 0)
    }

    /**
     * The window to read: among application windows that are not ours, the active one, else the focused one,
     * else the topmost. Returns its index, or -1 when there is none. A window without a root can win; the caller
     * then reports the screen as unreadable instead of reading a window behind it.
     */
    fun pickWindow(cands: List<WindowCand>, ownPkg: String): Int =
        cands.withIndex()
            .filter { it.value.pkg != ownPkg }
            .sortedWith(
                compareByDescending<IndexedValue<WindowCand>> { it.value.active }
                    .thenByDescending { it.value.focused }
                    .thenByDescending { it.value.layer }
                    .thenBy { it.index },
            )
            .firstOrNull()?.index ?: -1

    /** Vertical swipe inside [top, bottom] that moves the content forward (finger up) or backward (finger down): from, to. */
    fun swipeSpan(top: Int, bottom: Int, forward: Boolean): Pair<Int, Int> {
        val h = bottom - top
        val low = top + h * 7 / 10
        val high = top + h * 3 / 10
        return if (forward) low to high else high to low
    }

    /** One entry per package (an app can have several launcher activities), sorted by label. */
    fun dedupeApps(apps: List<AppInfo>): List<AppInfo> =
        apps.distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
}
